package com.storeql.iam.auth;

import com.storeql.iam.config.ServiceConfig;
import com.storeql.iam.domain.SigningKey;
import com.storeql.iam.repo.SigningKeyRepository;
import com.storeql.ids.Ids;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The keys access tokens are signed with (20.15). One key signs at a time; when it is rotated — by
 * a platform administrator, or by age — the new key is published first and signs only after a lead
 * time, so a verifier that reads the key set on a schedule has seen it before any token names it;
 * the old key keeps being published while the tokens it signed can still be alive, then is retired
 * and its private half wiped. The private half exists unsealed only in this service's memory.
 *
 * <p>Several replicas share the table: a key another replica made is picked up within {@link
 * #REFRESH}, and the partial unique index lets only one of them win a race to create or rotate.
 */
@ApplicationScoped
public class SigningKeys {

  private static final System.Logger LOG = System.getLogger(SigningKeys.class.getName());
  private static final Duration REFRESH = Duration.ofSeconds(60);
  private static final Duration MAINTENANCE = Duration.ofHours(1);
  private static final int RSA_BITS = 2048;

  /** The key that signs now, unsealed. */
  public record Signer(String kid, RSAPrivateKey privateKey) {}

  @Inject SigningKeyRepository repo;
  @Inject ServiceConfig config;

  private KeySealer sealer;
  private volatile Signer signer;
  private volatile Instant signerExpiresAt = Instant.EPOCH;
  private volatile Instant maintainedAt = Instant.EPOCH;

  /** A lock, not a monitor: the reload does JDBC, and a virtual thread must not pin on it. */
  private final ReentrantLock reloadLock = new ReentrantLock();

  private final Map<String, RSAPublicKey> verifying = new ConcurrentHashMap<>();
  private volatile Instant verifyingLoadedAt = Instant.EPOCH;

  @PostConstruct
  void init() {
    sealer = new KeySealer(config.jwtSecret());
    long needed = config.accessTtlSeconds() + config.jwtPublishLeadSeconds();
    if (config.jwtRetireAfterSeconds() < needed) {
      LOG.log(
          System.Logger.Level.WARNING,
          "storeql.jwt.retire-after-seconds ({0}) is shorter than a token a retiring key signed can"
              + " live ({1}): such tokens will stop verifying early",
          config.jwtRetireAfterSeconds(),
          needed);
    }
  }

  /** The key to sign with, created on first use and rotated by age. */
  public Signer signer() {
    Instant now = Instant.now();
    if (Duration.between(maintainedAt, now).compareTo(MAINTENANCE) > 0 && reloadLock.tryLock()) {
      // One caller maintains; the others carry on with the signer they have.
      try {
        if (Duration.between(maintainedAt, now).compareTo(MAINTENANCE) > 0) {
          maintainedAt = now;
          maintain(now);
        }
      } finally {
        reloadLock.unlock();
      }
    }
    Signer s = signer;
    if (s != null && now.isBefore(signerExpiresAt)) return s;
    reloadLock.lock();
    try {
      // Re-checked under the lock: whoever got here first has already reloaded.
      s = signer;
      if (s != null && now.isBefore(signerExpiresAt)) return s;
      SigningKey active = repo.active().orElseGet(() -> create(now));
      Instant signsFrom = active.createdAt().plusSeconds(config.jwtPublishLeadSeconds());
      SigningKey signing = active;
      Instant expires = now.plus(REFRESH);
      if (now.isBefore(signsFrom)) {
        // Just rotated: published, but not every verifier has read the key set again yet. The key
        // before it signs until the lead time is over (the first key ever has none before it).
        Optional<SigningKey> previous =
            repo.published().stream()
                .filter(k -> SigningKey.RETIRING.equals(k.status()))
                .filter(k -> !k.privateKeySealed().isEmpty())
                .findFirst();
        if (previous.isPresent()) {
          signing = previous.get();
          if (signsFrom.isBefore(expires)) expires = signsFrom;
        }
      }
      s = new Signer(signing.kid(), privateKey(sealer.open(signing.privateKeySealed())));
      signer = s;
      signerExpiresAt = expires;
      return s;
    } finally {
      reloadLock.unlock();
    }
  }

  /** The public half of a published key, by its id; empty for a key retired or never ours. */
  public Optional<RSAPublicKey> verifier(String kid) {
    if (kid == null) return Optional.empty();
    Instant now = Instant.now();
    if (!verifying.containsKey(kid)
        || Duration.between(verifyingLoadedAt, now).compareTo(REFRESH) > 0) {
      Map<String, RSAPublicKey> fresh = new ConcurrentHashMap<>();
      for (SigningKey k : repo.published()) fresh.put(k.kid(), publicKey(k.publicKey()));
      verifying.keySet().retainAll(fresh.keySet());
      verifying.putAll(fresh);
      verifyingLoadedAt = now;
    }
    return Optional.ofNullable(verifying.get(kid));
  }

  /** The keys a verifier must know, newest first: what the JWKS endpoint serves. */
  public List<SigningKey> published() {
    if (repo.active().isEmpty()) signer();
    return repo.published();
  }

  /** The recent keys of every status, for the platform administrator. */
  public List<SigningKey> recent() {
    return repo.recent();
  }

  /**
   * Makes a new key the active one; the old one starts retiring, and goes on signing for the
   * publish lead time.
   *
   * @return the new key
   */
  public SigningKey rotate() {
    Instant now = Instant.now();
    SigningKey next = generate(now);
    if (!repo.activate(next)) {
      // Another replica rotated at the same moment: its key is the new one.
      next = repo.active().orElseThrow();
    }
    signer = null;
    signerExpiresAt = Instant.EPOCH;
    verifyingLoadedAt = Instant.EPOCH;
    LOG.log(
        System.Logger.Level.INFO,
        "token signing key rotated: {0} is published and signs after the lead time",
        next.kid());
    return next;
  }

  /** Rotates a key past its age and retires the ones no live token can still name. */
  void maintain(Instant now) {
    try {
      Optional<SigningKey> active = repo.active();
      if (active.isPresent()
          && active
              .get()
              .createdAt()
              .plus(Duration.ofDays(config.jwtRotationDays()))
              .isBefore(now)) {
        rotate();
      }
      int retired = repo.retireBefore(now.minusSeconds(config.jwtRetireAfterSeconds()), now);
      if (retired > 0) {
        verifyingLoadedAt = Instant.EPOCH;
        LOG.log(System.Logger.Level.INFO, "{0} token signing key(s) retired", retired);
      }
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "signing key maintenance failed: " + e.getMessage());
    }
  }

  private SigningKey create(Instant now) {
    SigningKey first = generate(now);
    if (repo.activate(first)) {
      LOG.log(System.Logger.Level.INFO, "token signing key created: {0}", first.kid());
      return first;
    }
    return repo.active().orElseThrow();
  }

  private SigningKey generate(Instant now) {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(RSA_BITS);
      KeyPair pair = gen.generateKeyPair();
      return new SigningKey(
          Ids.newId().toString(),
          SigningKey.ALGORITHM_RS256,
          Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
          sealer.seal(pair.getPrivate().getEncoded()),
          SigningKey.ACTIVE,
          now,
          null,
          null);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not generate a signing key", e);
    }
  }

  /** The public key a stored key carries. */
  public static RSAPublicKey publicKey(String base64) {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("not an RSA public key", e);
    }
  }

  private static RSAPrivateKey privateKey(byte[] pkcs8) {
    try {
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("not an RSA private key", e);
    }
  }
}
