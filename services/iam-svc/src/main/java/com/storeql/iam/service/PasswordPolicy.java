package com.storeql.iam.service;

import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The password policy, to NIST SP 800-63B-4 (2025) and OWASP ASVS 5.0 V6: a password that is the
 * only factor is at least fifteen characters and at most a hundred and twenty-eight, any printable
 * character including spaces, no composition rules and no expiry; it is not the account's own
 * identity; and it is screened against known-breached passwords through the Pwned Passwords range
 * API, which sees only the first five characters of the SHA-1 (k-anonymity), never the password.
 *
 * <p>A screen that cannot be reached does not lock people out: the password is accepted and the
 * miss is logged, so the operator can see how often the screen was skipped.
 */
@ApplicationScoped
public class PasswordPolicy {

  private static final System.Logger LOG = System.getLogger(PasswordPolicy.class.getName());
  private static final Duration RANGE_TTL = Duration.ofHours(1);
  private static final int RANGE_CACHE_MAX = 4096;

  @Inject
  @ConfigProperty(name = "storeql.iam.password.min-length", defaultValue = "15")
  int minLength;

  @Inject
  @ConfigProperty(name = "storeql.iam.password.max-length", defaultValue = "128")
  int maxLength;

  @Inject
  @ConfigProperty(name = "storeql.iam.password.breach-check.enabled", defaultValue = "true")
  boolean breachCheck;

  @Inject
  @ConfigProperty(
      name = "storeql.iam.password.breach-check.url",
      defaultValue = "https://api.pwnedpasswords.com/range/")
  String breachUrl;

  @Inject
  @ConfigProperty(name = "storeql.iam.password.breach-check.timeout-ms", defaultValue = "3000")
  long breachTimeoutMs;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** Breached-suffix sets by SHA-1 prefix, so a busy sign-up page does not ask twice. */
  private final Map<String, CachedRange> ranges = new ConcurrentHashMap<>();

  private record CachedRange(Set<String> breachedSuffixes, Instant expiresAt) {}

  /**
   * The fewest characters a password may have.
   *
   * @return {@code storeql.iam.password.min-length}, 15 unless overridden
   */
  public int minLength() {
    return minLength;
  }

  /**
   * The most characters a password may have.
   *
   * @return {@code storeql.iam.password.max-length}, 128 unless overridden
   */
  public int maxLength() {
    return maxLength;
  }

  /**
   * Whether a password is screened against known data breaches before it is accepted — what {@code
   * GET /auth/password-policy} publishes as {@code breachScreened}, so a person is never refused by
   * a rule the page did not show them.
   *
   * @return {@code storeql.iam.password.breach-check.enabled}, true unless overridden
   */
  public boolean breachCheckEnabled() {
    return breachCheck;
  }

  /** For tests and callers outside CDI. */
  static PasswordPolicy of(int minLength, int maxLength, boolean breachCheck, String breachUrl) {
    PasswordPolicy p = new PasswordPolicy();
    p.minLength = minLength;
    p.maxLength = maxLength;
    p.breachCheck = breachCheck;
    p.breachUrl = breachUrl;
    p.breachTimeoutMs = 3000;
    return p;
  }

  /**
   * Refuses a password the policy does not accept.
   *
   * @param password the password as the person typed it
   * @param email the account's login, which the password must not be
   * @throws ApiException {@code 400 PASSWORD_TOO_SHORT}, {@code PASSWORD_TOO_LONG}, {@code
   *     PASSWORD_IS_IDENTITY} or {@code PASSWORD_BREACHED}
   */
  public void check(String password, String email) {
    if (password == null || password.codePointCount(0, password.length()) < minLength) {
      throw ApiException.badRequest(
          "PASSWORD_TOO_SHORT",
          "use at least "
              + minLength
              + " characters — a phrase of a few words is easiest to remember and hardest to guess;"
              + " spaces are fine");
    }
    if (password.codePointCount(0, password.length()) > maxLength) {
      throw ApiException.badRequest(
          "PASSWORD_TOO_LONG", "use at most " + maxLength + " characters");
    }
    String lower = password.toLowerCase(Locale.ROOT);
    if (email != null) {
      String e = email.trim().toLowerCase(Locale.ROOT);
      String local = e.contains("@") ? e.substring(0, e.indexOf('@')) : e;
      if (lower.equals(e) || (local.length() >= 4 && lower.contains(local))) {
        throw ApiException.badRequest(
            "PASSWORD_IS_IDENTITY", "the password must not be, or contain, your login");
      }
    }
    if (breachCheck && breached(password).orElse(false)) {
      throw ApiException.badRequest(
          "PASSWORD_BREACHED",
          "this password appears in known data breaches and would be guessed; choose another");
    }
  }

  /**
   * Whether the password appears in known breaches, or empty when the screen could not be reached.
   * Only the first five characters of the SHA-1 leave this service.
   */
  public Optional<Boolean> breached(String password) {
    String sha1 = sha1Hex(password);
    String prefix = sha1.substring(0, 5);
    String suffix = sha1.substring(5);
    CachedRange cached = ranges.get(prefix);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return Optional.of(cached.breachedSuffixes().contains(suffix));
    }
    Optional<Set<String>> fetched = fetchRange(prefix);
    if (fetched.isEmpty()) {
      LOG.log(
          System.Logger.Level.WARNING,
          "breached-password screen unavailable; the password was accepted unscreened");
      return Optional.empty();
    }
    if (ranges.size() >= RANGE_CACHE_MAX) ranges.clear();
    ranges.put(prefix, new CachedRange(fetched.get(), Instant.now().plus(RANGE_TTL)));
    return Optional.of(fetched.get().contains(suffix));
  }

  private Optional<Set<String>> fetchRange(String prefix) {
    try {
      HttpResponse<String> res =
          http.send(
              HttpRequest.newBuilder(URI.create(breachUrl + prefix))
                  .timeout(Duration.ofMillis(Math.max(500, breachTimeoutMs)))
                  .header("Add-Padding", "true")
                  .header("User-Agent", "storeql-iam")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (res.statusCode() != 200) return Optional.empty();
      Set<String> breachedSuffixes = ConcurrentHashMap.newKeySet();
      for (String line : res.body().split("\\r?\\n")) {
        int colon = line.indexOf(':');
        if (colon <= 0) continue;
        // Padded entries carry a count of 0 and are not breaches.
        long count;
        try {
          count = Long.parseLong(line.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
          continue;
        }
        if (count > 0)
          breachedSuffixes.add(line.substring(0, colon).trim().toUpperCase(Locale.ROOT));
      }
      return Optional.of(breachedSuffixes);
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  static String sha1Hex(String password) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)))
          .toUpperCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
