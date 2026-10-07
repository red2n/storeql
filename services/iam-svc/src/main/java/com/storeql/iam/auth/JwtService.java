package com.storeql.iam.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.iam.config.ServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * <p>Tokens are signed <strong>RS256</strong> with the key {@link SigningKeys} holds (20.15): the
 * private half never leaves this service, the token's header names the key ({@code kid}), and every
 * verifier — the gateway, the MQTT broker — fetches the public half from {@code
 * /auth/.well-known/jwks.json}. Nothing outside iam-svc can mint a token, and the key rotates. The
 * algorithm is pinned on both sides (RFC 8725): a token that says anything but RS256 is refused.
 *
 * <p>Claims: {@code sub}=userId, {@code tenant}=tenantId (absent for global customers), {@code
 * roles}=string list, {@code type}=STAFF|CUSTOMER, {@code storeIds}=string list (absent means
 * unrestricted — e.g. OWNER/PLATFORM_ADMIN — present means the holder may only operate in those
 * stores, e.g. a CASHIER bound to one store). These map to what the gateway forwards as X-Tenant-Id
 * / X-User-Id / X-Roles / X-Store-Ids.
 */
@ApplicationScoped
public class JwtService {

  @Inject ServiceConfig config;
  @Inject SigningKeys keys;

  /** Issue a signed access token for a user. */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds) {
    return issueAccessToken(userId, tenantId, userType, email, roles, storeIds, null);
  }

  /**
   * A token for a login that must have a second factor and has none (20.12): it says who, and
   * nothing else — no role, no tenant — and its {@code scope} lets the gateway pass it to the
   * second-factor routes alone. Ten minutes: long enough to scan a QR code.
   *
   * @param first what the sign-in proved: {@code pwd}, or {@code sso} for one the business's
   *     identity provider vouched for. Carried in {@code amr}, which the gateway stamps as {@code
   *     X-Auth-Methods}, so the session the set-up ends in records how it began.
   */
  public String issueEnrolmentToken(UUID userId, String userType, String email, String first) {
    SigningKeys.Signer signer = keys.signer();
    Instant now = Instant.now();
    return JWT.create()
        .withKeyId(signer.kid())
        .withIssuer(config.jwtIssuer())
        .withSubject(userId.toString())
        .withClaim("type", userType)
        .withClaim("roles", List.of())
        .withClaim("email", email)
        .withClaim("scope", com.storeql.web.HttpHeaders.SCOPE_MFA_ENROL)
        .withClaim("amr", List.of(first))
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(ENROLMENT_TTL_SECONDS))
        .sign(Algorithm.RSA256(null, signer.privateKey()));
  }

  /** How long an enrolment token lasts. */
  public static final long ENROLMENT_TTL_SECONDS = 600;

  /**
   * Issue a signed access token carrying a permission claim (20.10).
   *
   * @param permissions the {@code perms} claim, or {@code null} to omit it — a login with no custom
   *     role carries none and is judged by its tiers' defaults
   */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds,
      Set<String> permissions) {
    return issueAccessToken(
        userId, tenantId, userType, email, roles, storeIds, permissions, List.of("pwd"));
  }

  /**
   * Issue a signed access token that says how its holder was authenticated (20.12).
   *
   * @param amr the authentication methods, as RFC 8176 names them: {@code pwd} for the password,
   *     then {@code otp} (an authenticator app or a recovery code) or {@code hwk} (a passkey)
   */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds,
      Set<String> permissions,
      List<String> amr) {
    return issueAccessToken(
        userId, tenantId, userType, email, roles, storeIds, permissions, amr, null);
  }

  /**
   * Issue an access token that names the sign-in it belongs to.
   *
   * @param sessionId the session (the {@code sid} claim), or null to omit it
   */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds,
      Set<String> permissions,
      List<String> amr,
      UUID sessionId) {
    Instant now = Instant.now();
    var builder =
        JWT.create()
            .withIssuer(config.jwtIssuer())
            .withSubject(userId.toString())
            .withClaim("type", userType)
            .withClaim("roles", List.copyOf(roles))
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(config.accessTtlSeconds()));
    if (tenantId != null) {
      builder.withClaim("tenant", tenantId.toString());
    }
    // The holder's own email, which the gateway forwards downstream as X-User-Email. A shopper's
    // login is global while the shop's customer record is not, so without it customer-svc has no
    // way to know who the person signing in actually is, and an online order belongs to nobody the
    // shop can email, credit or erase (SJ-D44). Omitted rather than empty for a deleted login,
    // whose email has been erased.
    if (email != null && !email.isBlank()) {
      builder.withClaim("email", email);
    }
    // Omitted (not an empty claim) when unrestricted, so the gateway/TenantContext distinguish
    // "no claim present" from "claim present but empty" — both mean unrestricted, but only the
    // omitted form is what an unrestricted-access user (OWNER/PLATFORM_ADMIN) actually carries.
    if (storeIds != null && !storeIds.isEmpty()) {
      builder.withClaim("storeIds", storeIds.stream().map(UUID::toString).toList());
    }
    // Present, possibly empty, only for a login that holds a custom role: the gateway stamps the
    // claim as X-Permissions ("-" when empty) and a service judges the holder by it alone.
    if (permissions != null) {
      builder.withClaim("perms", List.copyOf(new java.util.TreeSet<>(permissions)));
    }
    builder.withClaim("amr", List.copyOf(amr));
    if (sessionId != null) {
      builder.withClaim("sid", sessionId.toString());
    }
    SigningKeys.Signer signer = keys.signer();
    return builder.withKeyId(signer.kid()).sign(Algorithm.RSA256(null, signer.privateKey()));
  }

  /**
   * Verify a token and return its decoded claims, or throw {@link JWTVerificationException}: the
   * algorithm must be RS256 and the key one this service still publishes.
   */
  public DecodedJWT verify(String token) throws JWTVerificationException {
    DecodedJWT decoded = JWT.decode(token);
    if (!"RS256".equals(decoded.getAlgorithm())) {
      throw new JWTVerificationException(
          "tokens are RS256; this one says " + decoded.getAlgorithm());
    }
    var publicKey =
        keys.verifier(decoded.getKeyId())
            .orElseThrow(() -> new JWTVerificationException("unknown signing key"));
    return JWT.require(Algorithm.RSA256(publicKey, null))
        .withIssuer(config.jwtIssuer())
        .build()
        .verify(token);
  }
}
