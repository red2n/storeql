package com.storeql.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Gateway configuration (Consul location for upstream resolution). */
@ApplicationScoped
public class GatewayConfig {

  @Inject
  @ConfigProperty(name = "storeql.consul.host", defaultValue = "localhost")
  String consulHost;

  @Inject
  @ConfigProperty(name = "storeql.consul.port", defaultValue = "8500")
  int consulPort;

  @Inject
  @ConfigProperty(name = "storeql.gateway.rate-limit.enabled", defaultValue = "true")
  boolean rateLimitEnabled;

  @Inject
  @ConfigProperty(name = "storeql.gateway.rate-limit.requests-per-minute", defaultValue = "100")
  int rateLimitRequestsPerMinute;

  /**
   * Services the proxy is allowed to route to. Anything not on this list is 404 even if it is
   * registered in Consul — internal services (config, discovery, observability) must never become
   * internet-reachable just by registering.
   */
  @Inject
  @ConfigProperty(
      name = "storeql.gateway.routable-services",
      defaultValue =
          "iam-svc,tenant-svc,product-svc,inventory-svc,pricing-svc,cart-svc,order-svc,"
              + "payment-svc,purchase-svc,customer-svc,notification-svc,reporting-svc")
  String routableServices;

  /**
   * Only enable when the gateway runs behind a trusted reverse proxy / LB that overwrites
   * X-Forwarded-For. When false (default) throttling keys on the socket remote address, which a
   * client cannot spoof.
   */
  @Inject
  @ConfigProperty(name = "storeql.gateway.trust-forwarded-headers", defaultValue = "false")
  boolean trustForwardedHeaders;

  /**
   * Refuse any request that carries a payment card number. This is what makes the PCI DSS scope
   * (SAQ-A: no card detail reaches any StoreQL service) a fact rather than a design intention — see
   * {@code com.storeql.web.CardData}. Off only for a stack that has to prove the guard is why a
   * request was refused.
   */
  @Inject
  @ConfigProperty(name = "storeql.gateway.card-data-guard.enabled", defaultValue = "true")
  boolean cardDataGuardEnabled;

  /** Bodies larger than this are scanned only up to this many bytes; card numbers are short. */
  @Inject
  @ConfigProperty(name = "storeql.gateway.card-data-guard.max-scan-bytes", defaultValue = "8388608")
  int cardDataGuardMaxScanBytes;

  /** The largest request body a route takes unless it is a named upload route. */
  @Inject
  @ConfigProperty(name = "storeql.gateway.max-body-bytes", defaultValue = "1048576")
  int maxBodyBytes;

  /**
   * Routes that take a document rather than JSON, each with its own cap, as {@code path=bytes}
   * separated by commas: a supplier's e-invoice PDF may be 20 MB (07.13), uploaded by a person or
   * delivered by a network — each network's delivery route is its own entry, since the cap matches
   * a path exactly. An acquirer's settlement file is a day of card payments as text (11.10). {@code
   * server.max-payload-size} must be at least the largest. The list is kept here and nowhere else:
   * a copy in the packaged properties once went stale and held every network's delivery to the
   * small cap (SJ-D64).
   */
  @Inject
  @ConfigProperty(
      name = "storeql.gateway.upload-routes",
      defaultValue =
          "/api/purchase-svc/e-invoices=21000000,"
              + "/api/purchase-svc/e-invoices/inbound/peppol=21000000,"
              + "/api/purchase-svc/e-invoices/inbound/fr_pdp=21000000,"
              + "/api/purchase-svc/e-invoices/inbound/simulated=21000000,"
              + "/api/payment-svc/admin/settlements=6000000")
  String uploadRoutes;

  @Inject
  @ConfigProperty(name = "storeql.gateway.brute-force.enabled", defaultValue = "true")
  boolean bruteForceEnabled;

  @Inject
  @ConfigProperty(name = "storeql.gateway.brute-force.max-failures", defaultValue = "5")
  int bruteForceMaxFailures;

  @Inject
  @ConfigProperty(name = "storeql.gateway.brute-force.block-minutes", defaultValue = "15")
  int bruteForceBlockMinutes;

  /** Suffix-matched against the request path; must point at the credential-checking endpoint. */
  @Inject
  @ConfigProperty(name = "storeql.gateway.brute-force.login-path", defaultValue = "/auth/login")
  String bruteForceLoginPath;

  /**
   * Unauthenticated endpoints that accept a secret in the body and answer 404 when it is wrong,
   * comma-separated and suffix-matched like the login path. A wrong secret counts as a failure
   * against the client IP under the same lockout as a wrong password: the opt-out link in a
   * marketing message is a bearer token, and while 256 random bits cannot be guessed, an endpoint
   * that would let someone try forever is the kind of thing an auditor asks about.
   */
  @Inject
  @ConfigProperty(
      name = "storeql.gateway.brute-force.token-paths",
      defaultValue = "/marketing/unsubscribe")
  String bruteForceTokenPaths;

  /**
   * Browser origins allowed to call the API (CORS). Absent/empty (the default) means no CORS
   * headers are emitted at all, so browser frontends are denied until origins are configured
   * explicitly. "*" allows any origin (dev only). Injected as Optional because MP Config treats an
   * empty value as "property not present" and refuses to inject a plain String for it.
   */
  @Inject
  @ConfigProperty(name = "storeql.gateway.cors.allowed-origins")
  java.util.Optional<String> corsAllowedOrigins;

  @Inject
  @ConfigProperty(name = "storeql.jwt.issuer", defaultValue = "storeql")
  String jwtIssuer;

  @Inject
  @ConfigProperty(name = "storeql.gateway.upstream.connect-timeout-seconds", defaultValue = "2")
  int upstreamConnectTimeoutSeconds;

  @Inject
  @ConfigProperty(name = "storeql.gateway.upstream.read-timeout-seconds", defaultValue = "10")
  int upstreamReadTimeoutSeconds;

  /**
   * Per-upstream-service circuit breaker (see {@link UpstreamCircuitBreaker}): after this many
   * consecutive connect/read failures to one service, its circuit opens and the gateway fails fast
   * for that service alone instead of dispatching every request at full volume.
   */
  @Inject
  @ConfigProperty(name = "storeql.gateway.circuit-breaker.failure-threshold", defaultValue = "5")
  int circuitBreakerFailureThreshold;

  @Inject
  @ConfigProperty(name = "storeql.gateway.circuit-breaker.open-seconds", defaultValue = "10")
  int circuitBreakerOpenSeconds;

  /**
   * Rate-limit and brute-force counters live in Redis, not gateway heap — with multiple gateway
   * replicas a per-instance map lets an attacker simply round-robin past the limit. Defaults match
   * docker-compose's redis service (golden rule #5: external config, no hardcoded host:port).
   */
  @Inject
  @ConfigProperty(name = "storeql.redis.host", defaultValue = "localhost")
  String redisHost;

  @Inject
  @ConfigProperty(name = "storeql.redis.port", defaultValue = "6379")
  int redisPort;

  @Inject
  @ConfigProperty(name = "storeql.redis.password", defaultValue = "redis_dev_change_me")
  String redisPassword;

  /**
   * {@code /.well-known/security.txt} (RFC 9116). Published only when a contact and an expiry are
   * both configured and valid; there is no default, because an invented contact is worse than none.
   */
  @Inject
  @ConfigProperty(name = "storeql.gateway.security-txt.contact")
  java.util.Optional<String> securityTxtContact;

  @Inject
  @ConfigProperty(name = "storeql.gateway.security-txt.expires")
  java.util.Optional<String> securityTxtExpires;

  @Inject
  @ConfigProperty(name = "storeql.gateway.security-txt.policy")
  java.util.Optional<String> securityTxtPolicy;

  @Inject
  @ConfigProperty(name = "storeql.gateway.security-txt.canonical")
  java.util.Optional<String> securityTxtCanonical;

  @Inject
  @ConfigProperty(name = "storeql.gateway.security-txt.preferred-languages")
  java.util.Optional<String> securityTxtPreferredLanguages;

  /** Parsed once at startup — these are consulted on every proxied request. */
  // ── API versioning (22.8) ──────────────────────────────────────────────────
  // The versions the door answers, the one new integrations are pointed at, and the alias's dates.
  // Parsed once at start so an inconsistent policy stops the gateway rather than a client.

  @Inject
  @ConfigProperty(name = "storeql.gateway.api.current", defaultValue = "v1")
  String apiCurrent;

  @Inject
  @ConfigProperty(name = "storeql.gateway.api.versions", defaultValue = "v1")
  String apiVersionsCsv;

  @Inject
  @ConfigProperty(name = "storeql.gateway.api.alias-deprecated-since", defaultValue = "2026-09-23")
  String apiAliasDeprecatedSince;

  @Inject
  @ConfigProperty(name = "storeql.gateway.api.alias-sunset", defaultValue = "2027-09-30")
  String apiAliasSunset;

  private ApiVersions.Policy apiVersionPolicy;

  private java.util.Set<String> routableServiceSet;

  private java.util.Set<String> corsAllowedOriginSet;

  @jakarta.annotation.PostConstruct
  void parseSets() {
    routableServiceSet = csvToSet(routableServices);
    corsAllowedOriginSet = csvToSet(corsAllowedOrigins.orElse(""));
    apiVersionPolicy =
        ApiVersions.Policy.of(apiCurrent, apiVersionsCsv, apiAliasDeprecatedSince, apiAliasSunset);
  }

  /** The API versioning policy (22.8): versions answered, the current one, the alias's dates. */
  public ApiVersions.Policy apiVersions() {
    return apiVersionPolicy;
  }

  private static java.util.Set<String> csvToSet(String csv) {
    var out = new java.util.HashSet<String>();
    for (String s : csv.split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return java.util.Set.copyOf(out);
  }

  public String consulHost() {
    return consulHost;
  }

  public int consulPort() {
    return consulPort;
  }

  public boolean rateLimitEnabled() {
    return rateLimitEnabled;
  }

  public int rateLimitRequestsPerMinute() {
    return rateLimitRequestsPerMinute;
  }

  public java.util.Set<String> routableServices() {
    return routableServiceSet;
  }

  public boolean trustForwardedHeaders() {
    return trustForwardedHeaders;
  }

  public java.util.Set<String> corsAllowedOrigins() {
    return corsAllowedOriginSet;
  }

  public int upstreamConnectTimeoutSeconds() {
    return upstreamConnectTimeoutSeconds;
  }

  public int upstreamReadTimeoutSeconds() {
    return upstreamReadTimeoutSeconds;
  }

  public int circuitBreakerFailureThreshold() {
    return circuitBreakerFailureThreshold;
  }

  public int circuitBreakerOpenSeconds() {
    return circuitBreakerOpenSeconds;
  }

  public String redisHost() {
    return redisHost;
  }

  public int redisPort() {
    return redisPort;
  }

  public String redisPassword() {
    return redisPassword;
  }

  public boolean cardDataGuardEnabled() {
    return cardDataGuardEnabled;
  }

  public int cardDataGuardMaxScanBytes() {
    return cardDataGuardMaxScanBytes;
  }

  public int maxBodyBytes() {
    return maxBodyBytes;
  }

  public String uploadRoutes() {
    return uploadRoutes;
  }

  public boolean bruteForceEnabled() {
    return bruteForceEnabled;
  }

  /**
   * @return the token-bearing public paths guarded against guessing, as configured
   */
  public String bruteForceTokenPaths() {
    return bruteForceTokenPaths;
  }

  public int bruteForceMaxFailures() {
    return bruteForceMaxFailures;
  }

  public int bruteForceBlockMinutes() {
    return bruteForceBlockMinutes;
  }

  public String bruteForceLoginPath() {
    return bruteForceLoginPath;
  }

  public String jwtIssuer() {
    return jwtIssuer;
  }

  /**
   * @return the security.txt settings as configured, each possibly absent
   */
  public SecurityTxt.Settings securityTxt() {
    return new SecurityTxt.Settings(
        securityTxtContact,
        securityTxtExpires,
        securityTxtPolicy,
        securityTxtCanonical,
        securityTxtPreferredLanguages);
  }
}
