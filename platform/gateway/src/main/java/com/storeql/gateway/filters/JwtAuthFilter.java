package com.storeql.gateway.filters;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.gateway.GatewayConfig;
import com.storeql.ids.Ids;
import com.storeql.web.HttpHeaders;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Security boundary: validates the inbound JWT, then stamps verified identity headers (X-Tenant-Id,
 * X-User-Id, X-Roles) onto the request before it reaches ProxyResource.
 *
 * <p>Any client-supplied copies of those headers are removed first, so downstream services can
 * trust that only the gateway sets them (golden rule #3).
 *
 * <p>Public paths (register / login / refresh) bypass JWT validation. All other /api/** paths
 * require a valid Bearer token.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 1)
public class JwtAuthFilter implements ContainerRequestFilter {

  private static final System.Logger LOG = System.getLogger(JwtAuthFilter.class.getName());

  /**
   * How far apart two pods' clocks may be before this gateway stops believing a token iam has just
   * minted. Well inside the "few minutes" RFC 7519 allows, and a small fraction of an access
   * token's fifteen minutes.
   */
  private static final long CLOCK_SKEW_SECONDS = 60;

  /** Exact request paths (normalized, no leading/trailing slash) that do NOT require a token. */
  private static final Set<String> PUBLIC_PATHS =
      Set.of(
          "api/iam-svc/auth/register",
          // The price list a prospect reads before signing up (21.13): plans on sale, no identity.
          "api/tenant-svc/plans",
          // The API's versions and their policy (22.8): what an integrator reads before holding
          // any credential. Exactly this path — normalize() leaves it alone, having no version
          // segment to collapse — and nothing under it.
          "api/versions",
          "api/iam-svc/auth/login",
          // The token signing keys' public halves (20.15): public by nature.
          "api/iam-svc/auth/.well-known/jwks.json",
          "api/iam-svc/auth/platform-login",
          // A sign-in answering its second factor (20.12): the password was right, no token exists
          // yet, and the mfaToken in the body names the waiting sign-in.
          "api/iam-svc/auth/mfa/login",
          "api/iam-svc/auth/mfa/login/passkey-options",
          "api/iam-svc/auth/refresh",
          // A forgotten password (password reset): asking for a link answers the same for any
          // address, and spending one needs the link's 256-bit token, the whole capability. The
          // rules a new password must meet are read before anyone has signed in.
          "api/iam-svc/auth/password/forgot",
          "api/iam-svc/auth/password/reset",
          "api/iam-svc/auth/password-policy",
          // Single sign-on through a business's identity provider (20.x): starting it, the
          // provider sending the browser back, and the app trading the ticket it came back with.
          // Nobody signing in holds a token yet; the random state and the ticket with the app's
          // PKCE verifier are the capabilities, each spent on use.
          "api/iam-svc/auth/sso/start",
          "api/iam-svc/auth/sso/callback",
          "api/iam-svc/auth/sso/token",
          "api/iam-svc/bootstrap/admin",
          // The opt-out link in a marketing message (PECR reg.23). Necessarily public: the person
          // clicking it may be on a device that was never signed in, may have no password at all,
          // and must not be made to prove who they are in order to be left alone. The token in the
          // link is the whole capability, and it can only ever withdraw permission.
          "api/customer-svc/marketing/unsubscribe",
          // RFC 9116: whoever found a vulnerability has no account; the file holds only the
          // contact a deployment chose to publish.
          ".well-known/security.txt");

  /**
   * The pay link in a dunning notice (21.12): {@code POST /api/tenant-svc/billing/pay/{token}}.
   *
   * <p>Not in {@link #PUBLIC_PATHS} because that set is matched exactly and this path ends in a
   * token. Same reasoning as the unsubscribe link one entry above: by the time the later notices go
   * the business has been suspended, so it cannot sign in, and a pay link that demands a session is
   * a dead end. The token is the whole capability — 256 bits, stored only as a hash, naming one
   * invoice — and it can do exactly one thing to it.
   *
   * <p>tenant-svc's {@code AdminAuthorizationFilter} carries the matching carve-out. Both are
   * needed: with either missing, a suspended business is told to pay and cannot.
   */
  private static final String PAY_LINK_PREFIX = "api/tenant-svc/billing/pay/";

  /**
   * Public storefront access (guest shopping). These tenant-scoped paths expose only public data
   * (active, sellable-online products and their prices) plus guest checkout, so they may be reached
   * without a token. The tenant is taken from {@code X-Storefront-Tenant}, which in production the
   * gateway derives from the storefront's domain/subdomain; in dev the storefront sends it directly
   * to simulate that. (Any client-supplied {@code X-Tenant-Id} is still stripped above, so this is
   * the only way a guest can name a storefront, and only for these whitelisted paths.)
   */
  static final String STOREFRONT_TENANT_HEADER = "X-Storefront-Tenant";

  @Inject GatewayConfig config;
  @Inject TenantStatusGate tenantStatusGate;

  /** What a business's API key may do (22.7), as iam-svc says. */
  @Inject ApiKeyIntrospector apiKeys;

  @Inject SigningKeySet signingKeys;

  /** A verifier per signing key, built once the key is known. */
  private final java.util.Map<String, JWTVerifier> verifiers =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * The key id a token names, for the log alone.
   *
   * @return the {@code kid}, or {@code "unreadable"} when the token will not even decode — which is
   *     itself the answer, and distinguishes a malformed header from a real token refused
   */
  private static String keyIdOf(String token) {
    try {
      String kid = JWT.decode(token).getKeyId();
      return kid == null || kid.isBlank() ? "none" : kid;
    } catch (JWTVerificationException e) {
      return "unreadable";
    }
  }

  /**
   * Verifies a token (20.15; RFC 8725): it must say RS256 — never {@code none}, never an HMAC a
   * public key could be passed off as the secret of — and name a key iam-svc publishes; the
   * signature, issuer and expiry are then checked against that key.
   */
  private DecodedJWT verify(String token) throws JWTVerificationException {
    DecodedJWT decoded = JWT.decode(token);
    if (!"RS256".equals(decoded.getAlgorithm())) {
      throw new JWTVerificationException("not an RS256 token");
    }
    String kid = decoded.getKeyId();
    var key =
        signingKeys.key(kid).orElseThrow(() -> new JWTVerificationException("unknown signing key"));
    return verifiers
        .computeIfAbsent(
            kid + ":" + key.getModulus().hashCode(),
            k ->
                JWT.require(Algorithm.RSA256(key, null))
                    .withIssuer(config.jwtIssuer())
                    // Clock skew, on the two claims where tolerating it only ever helps a
                    // legitimate caller. RFC 7519 §4.1.5 anticipates exactly this and allows "a
                    // small leeway, usually no more than a few minutes".
                    //
                    // SJ-D66, and it cost a whole k6 run in 401s that looked random: the gateway
                    // judged a token at 02:51:42.978Z whose `iat` was 02:51:43Z, so it refused a
                    // token iam had minted for that very request. `iat` is whole seconds and
                    // java-jwt floors it (checked), so the extra second is not rounding — it is
                    // iam's clock reading a few milliseconds ahead of this one. Two processes do
                    // not share a clock to the millisecond, and with zero leeway every such
                    // millisecond is a 401 nobody can reproduce.
                    //
                    // `exp` is deliberately left strict: leeway there would extend a token's life
                    // past the moment it was meant to die, which is the one direction skew must
                    // never be allowed to help.
                    .acceptIssuedAt(CLOCK_SKEW_SECONDS)
                    .acceptNotBefore(CLOCK_SKEW_SECONDS)
                    .build())
        .verify(token);
  }

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    // Always strip any client-supplied identity headers to prevent spoofing.
    // Exception: onboarding paths may provide X-Tenant-Id for tenant context when JWT has no tenant
    // claim yet.
    String path = ctx.getUriInfo().getPath();
    String normalizedPath = normalize(path);
    boolean isOnboarding = isOnboarding(normalizedPath, ctx.getMethod());

    // Preserve X-Tenant-Id for onboarding paths (user may have just created tenant and is setting
    // up stores)
    String preservedTenantId = null;
    if (isOnboarding) {
      preservedTenantId = ctx.getHeaderString(HttpHeaders.TENANT_ID);
    }

    ctx.getHeaders().remove(HttpHeaders.TENANT_ID);
    ctx.getHeaders().remove(HttpHeaders.USER_ID);
    ctx.getHeaders().remove(HttpHeaders.USER_EMAIL);
    ctx.getHeaders().remove(HttpHeaders.ROLES);
    ctx.getHeaders().remove(HttpHeaders.STORE_IDS);
    ctx.getHeaders().remove(HttpHeaders.PERMISSIONS);
    ctx.getHeaders().remove(HttpHeaders.AUTH_SCOPE);
    ctx.getHeaders().remove(HttpHeaders.AUTH_METHODS);

    // Allow public auth paths without a token.
    if (isPublic(path)) {
      return;
    }

    // Payment provider webhooks. The provider calls these from its own infrastructure: no JWT, no
    // storefront header, and no tenant to resolve — the intent named in the body is what says
    // which tenant the event concerns. They are authenticated instead by the provider's signature
    // over the raw body, verified inside payment-svc before anything is applied. Identity headers
    // have already been stripped above, so the request reaches the service with no role, which is
    // exactly right: it should be able to do nothing except be verified.
    if (isProviderWebhook(normalizedPath, ctx.getMethod())) {
      return;
    }

    // A network delivering a supplier's e-invoice (07.13, the transport seam). Like a payment
    // provider, the access point calls from its own infrastructure with no JWT and no tenant; the
    // receiver is the business the document itself names. purchase-svc holds the delivery key the
    // request presents against the deployment's own before it reads a byte, and identity headers
    // have been stripped above, so the request carries no role a spoofed header could claim.
    if (isEInvoiceDelivery(normalizedPath, ctx.getMethod())) {
      return;
    }

    // OpenAPI contract documents are not sensitive (no tenant data) and need to be reachable by
    // an unauthenticated browser (Swagger UI) for API discovery/docs.
    if ("GET".equals(ctx.getMethod()) && isOpenApiSpec(normalizedPath)) {
      return;
    }

    // Storefront public reads: tenant comes from the storefront header, regardless of whether
    // the caller also happens to carry a customer Bearer token (e.g. a signed-in customer still
    // browsing the catalog after checkout). These paths expose nothing sensitive — anyone can
    // already reach them with no token at all — so a present-but-irrelevant Bearer must not force
    // JWT verification and reject the request for lacking a tenant claim.
    //
    // But these same paths are ALSO called by authenticated staff with no storefront context at
    // all — e.g. the admin console checking inventory availability, or POS clock-in listing
    // stores via this same cashier-safe endpoint (see posStoresProvider: "tenant is taken from
    // the authenticated staff JWT"). Without X-Storefront-Tenant, that is NOT a guest/customer
    // call — fall through to normal Bearer verification below so the tenant gets resolved from
    // the JWT instead of being silently left unset (which previously surfaced downstream as a
    // blanket 401 NO_TENANT, e.g. every product showing "unavailable" regardless of real stock).
    if (isStorefrontPublic(normalize(path), ctx.getMethod())) {
      String storefrontTenant = ctx.getHeaderString(STOREFRONT_TENANT_HEADER);
      if (storefrontTenant != null && !storefrontTenant.isBlank()) {
        String tenant = storefrontTenant.trim();
        if (!tenantStatusGate.isActive(tenant)) {
          ctx.abortWith(tenantSuspended());
          return;
        }
        ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenant);
        return;
      }
    }

    String authHeader = ctx.getHeaderString("Authorization");
    boolean hasBearer = authHeader != null && authHeader.startsWith("Bearer ");
    if (!hasBearer) {
      ctx.abortWith(unauthorized("Missing or malformed Authorization header"));
      return;
    }

    String token = authHeader.substring(7).trim();
    // A business's API key as the bearer (22.7): a key, not a token, and judged by iam-svc.
    if (token.startsWith(ApiKeyIntrospector.KEY_PREFIX)) {
      authenticateApiKey(ctx, token, normalize(path));
      return;
    }
    DecodedJWT jwt;
    try {
      jwt = verify(token);
    } catch (JWTVerificationException e) {
      if (!signingKeys.loaded()) {
        // No key set has ever been read: iam-svc is not up yet. That is the platform's fault, not
        // the caller's, and a 401 would sign a browser out for it.
        ctx.abortWith(keysUnavailable());
        return;
      }
      // The caller is told "invalid or expired" and nothing more — which of the four reasons it
      // was is an oracle, and none of them is the caller's to act on differently. But it is the
      // platform's to act on, and one message for four causes hides the difference where it
      // matters: an unknown key id is a key-set problem, a bad signature is a forgery or a
      // rotation gone wrong, a stale expiry is a clock. Recorded here with the key id and never
      // the token, because a 401 nobody can explain is a 401 that will happen again.
      LOG.log(
          System.Logger.Level.WARNING,
          "token refused on {0}: {1} (kid {2})",
          normalize(path),
          e.getMessage(),
          keyIdOf(token));
      ctx.abortWith(unauthorized("Invalid or expired token"));
      return;
    }

    // A token that may only set a second factor up (20.12) reaches those routes and nothing else;
    // a scope this gateway does not know reaches nothing at all.
    String scope = jwt.getClaim("scope").asString();
    if (scope != null) {
      String target = normalize(path);
      boolean secondFactorRoute =
          "api/iam-svc/auth/mfa".equals(target) || target.startsWith("api/iam-svc/auth/mfa/");
      if (!HttpHeaders.SCOPE_MFA_ENROL.equals(scope) || !secondFactorRoute) {
        ctx.abortWith(enrolmentOwed());
        return;
      }
      ctx.getHeaders().putSingle(HttpHeaders.AUTH_SCOPE, scope);
    }

    // Stamp verified claims as trusted headers for downstream services.
    String userId = jwt.getSubject();
    String email = jwt.getClaim("email").asString();
    String tenantId = jwt.getClaim("tenant").asString();
    List<String> roles = jwt.getClaim("roles").asList(String.class);
    List<String> storeIds = jwt.getClaim("storeIds").asList(String.class);
    List<String> perms = jwt.getClaim("perms").asList(String.class);
    List<String> amr = jwt.getClaim("amr").asList(String.class);

    if (userId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.USER_ID, userId);
    }
    // The caller's own verified email. A shopper's login is global and their orders are placed at
    // a shop that holds no record of them, so without this the shop cannot email, credit or erase
    // the person who bought (SJ-D44); customer-svc matches the login to its customer record on it.
    if (email != null && !email.isBlank()) {
      ctx.getHeaders().putSingle(HttpHeaders.USER_EMAIL, email.trim());
    }
    if (tenantId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenantId);
    } else if (isStorefrontCustomer(normalize(path), ctx.getMethod())) {
      // A signed-in customer carries identity (userId) but no tenant — a customer account is global
      // and shops across storefronts. For the whitelisted storefront-customer paths only, the
      // tenant is taken from the storefront header (same trusted source as guest browsing), so the
      // order is recorded against the right business while still being tied to the authenticated
      // customerId. This fallback is deliberately scoped to those paths: it must never let a
      // customer token name a tenant for admin endpoints.
      String storefrontTenant = ctx.getHeaderString(STOREFRONT_TENANT_HEADER);
      if (storefrontTenant != null && !storefrontTenant.isBlank()) {
        String tenant = storefrontTenant.trim();
        if (!tenantStatusGate.isActive(tenant)) {
          ctx.abortWith(tenantSuspended());
          return;
        }
        ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenant);
      }
    }
    if (roles != null && !roles.isEmpty()) {
      ctx.getHeaders().putSingle(HttpHeaders.ROLES, String.join(",", roles));
    }
    if (storeIds != null && !storeIds.isEmpty()) {
      ctx.getHeaders().putSingle(HttpHeaders.STORE_IDS, String.join(",", storeIds));
    }
    // The permission claim (20.10). Present-but-empty is a real value — a role narrowed to
    // nothing — and must reach the service as one, so it goes as "-" rather than as no header,
    // which would read as "no claim, judge by the tier" and undo the narrowing.
    if (perms != null) {
      ctx.getHeaders()
          .putSingle(HttpHeaders.PERMISSIONS, perms.isEmpty() ? "-" : String.join(",", perms));
    }

    // How the session was authenticated (20.12, 20.x SSO): a password or the business's identity
    // provider, then any second factor. iam-svc reads it when a sign-in that owed a second factor
    // sets one up, so the session it ends in records how it began.
    if (amr != null && !amr.isEmpty()) {
      ctx.getHeaders().putSingle(HttpHeaders.AUTH_METHODS, String.join(",", amr));
    }

    // Restore preserved tenant ID for onboarding paths (flow guard: user provides tenant context)
    if (preservedTenantId != null && !preservedTenantId.isBlank() && tenantId == null) {
      ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, preservedTenantId.trim());
    }
  }

  /**
   * Onboarding paths where user may provide tenant context before it's in the JWT. These paths are
   * part of the tenant creation flow and need X-Tenant-Id for the newly created tenant.
   */
  private static boolean isOnboarding(String path, String method) {
    // POST /onboarding/stores — create store for newly created tenant
    if ("POST".equals(method) && "api/tenant-svc/onboarding/stores".equals(path)) {
      return true;
    }
    // GET /onboarding/status — check onboarding progress for tenant
    if ("GET".equals(method) && "api/tenant-svc/onboarding/status".equals(path)) {
      return true;
    }
    return false;
  }

  /**
   * Whitelisted authenticated-customer storefront paths (already normalized). These require a valid
   * customer token AND derive the tenant from the storefront header: guest checkout that a
   * signed-in customer makes (so the order links to their customerId) and the customer's own order
   * history.
   */
  private static boolean isStorefrontCustomer(String path, String method) {
    if ("POST".equals(method) && "api/order-svc/orders".equals(path)) {
      return true;
    }
    // Opening a payment intent, and polling it after the customer returns from SCA. Same shopper
    // and same point in checkout as api/payment-svc/payments/online below. Capturing is absent on
    // purpose: POST .../capture is staff-only and goes through normal Bearer verification.
    if ("POST".equals(method) && "api/payment-svc/payments/intents".equals(path)) {
      return true;
    }
    if ("GET".equals(method) && isPaymentIntentRead(path)) {
      return true;
    }
    if ("GET".equals(method) && "api/order-svc/orders/mine".equals(path)) {
      return true;
    }
    // The shopper opening one of their own orders: the id-addressed reads the downstream filter
    // already treats as self-reads, with order-svc's object-level check behind them (the owning
    // login gets the order, anyone else a 404). Until this, a customer token could list its orders
    // through /orders/mine and could not open any of them, because no tenant was derived for the
    // read by id — a gap the privacy-flow k6 suite hit the first time it drove the real door.
    if ("GET".equals(method) && isOrderSelfRead(path)) {
      return true;
    }
    // A split checkout (order orchestration): the shopper reading the parts of the delivery they
    // placed, with order-svc's object-level check behind it like an order read by id.
    if ("GET".equals(method) && isOrderGroupSelfRead(path)) {
      return true;
    }
    // The shopper's recall notices and their choice of remedy (05.10): keyed on the login in
    // order-svc like /orders/mine, with the storefront header naming the shop. Two shapes and no
    // more — the recall's list and its settlement are staff work through the normal door.
    if (isStorefrontRecallNotice(path, method)) {
      return true;
    }
    if ("POST".equals(method) && "api/payment-svc/payments/online".equals(path)) {
      return true;
    }
    // The shopper's own account with one shop: the customer record their login owns there, the
    // marketing they have agreed to, and the data export art.20 entitles them to. Same shape as
    // /orders/mine — a global customer token plus the storefront's tenant header — and, like it,
    // every one of these resolves the caller from the token and can reach no other person's data.
    if (isStorefrontCustomerAccount(path, method)) {
      return true;
    }
    return false;
  }

  /**
   * The {@code customer-svc} self-service shapes a signed-in shopper reaches from a storefront.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} for the caller's own customer record, preference centre and export
   */
  private static boolean isStorefrontCustomerAccount(String path, String method) {
    boolean get = "GET".equals(method);
    return switch (path) {
      // Claiming the record, reading it, and editing the profile on it (12.10).
      case "api/customer-svc/customers/me" -> get || "POST".equals(method) || "PUT".equals(method);
      case "api/customer-svc/customers/me/marketing" -> get || "PUT".equals(method);
      case "api/customer-svc/customers/me/export" -> get;
      // The shopper's own privacy (13.12): what they consented to, withdrawn in one step, and what
      // they asked for. The notice itself is public, below.
      case "api/customer-svc/customers/me/privacy" -> get;
      case "api/customer-svc/customers/me/privacy/consents" ->
          "PUT".equals(method) || "DELETE".equals(method);
      case "api/customer-svc/customers/me/privacy/requests" -> get || "POST".equals(method);
      // The shopper's own address book (12.10), and below, one address in it by id.
      case "api/customer-svc/customers/me/addresses" -> get || "POST".equals(method);
      // The shopper's own push devices (13.7), and below, one device by id.
      case "api/notification-svc/notifications/devices" -> get || "POST".equals(method);
      default ->
          isStorefrontCustomerAddress(path, method) || isStorefrontCustomerDevice(path, method);
    };
  }

  /** One id-addressed device in the shopper's own list: remove only. */
  private static boolean isStorefrontCustomerDevice(String path, String method) {
    String prefix = "api/notification-svc/notifications/devices/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    return looksLikeUuid(path.substring(prefix.length())) && "DELETE".equals(method);
  }

  /**
   * One id-addressed address in the shopper's own book: replace or remove. Matched by shape, like
   * the order self-reads, so a literal child under the book is not admitted by accident — the first
   * live run of the account flow found the book itself missing from this list, which is exactly the
   * failure mode this comment exists to remember.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} for PUT or DELETE of {@code .../customers/me/addresses/{uuid}}
   */
  private static boolean isStorefrontCustomerAddress(String path, String method) {
    String prefix = "api/customer-svc/customers/me/addresses/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String id = path.substring(prefix.length());
    return looksLikeUuid(id) && ("PUT".equals(method) || "DELETE".equals(method));
  }

  /** Whether a path segment has the shape of a UUID: 36 characters of hex and hyphens. */
  /**
   * Whether a path segment is an id this platform could have made: a canonical UUIDv7. Any other
   * version names nothing here, so a shape that turns on "an id goes here" never opens for one.
   */
  private static boolean looksLikeUuid(String id) {
    try {
      Ids.parse(id);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Whitelisted public storefront paths (already normalized): catalog reads, price resolve. Public
   * regardless of caller identity — reachable by guests, and equally by signed-in customers who
   * happen to carry a Bearer token while browsing (see the call site: this check runs before JWT
   * verification, so an irrelevant token never turns these into authenticated-only paths).
   */
  private static boolean isStorefrontPublic(String path, String method) {
    if ("GET".equals(method) && path.startsWith("api/product-svc/catalog")) {
      return true;
    }
    // Per-store storefront config (show-prices flag) and stock availability.
    // The privacy notice a shopper reads before they sign up (13.12, DPDP Act s.5): public, one
    // exact path, resolved to the shop by the storefront header like the rest of the storefront.
    if ("GET".equals(method) && "api/customer-svc/customers/privacy/notice".equals(path)) {
      return true;
    }
    if ("GET".equals(method) && path.startsWith("api/tenant-svc/storefront")) {
      return true;
    }
    // Soft delivery-coverage check before checkout (pincode → fulfilling store).
    if ("GET".equals(method) && "api/tenant-svc/fulfilment/resolve".equals(path)) {
      return true;
    }
    // The delivery and collection windows a store offers over the next week, with what each has
    // left (delivery and collection slots): read by a guest choosing one at checkout.
    if ("GET".equals(method) && "api/order-svc/storefront/fulfilment-slots".equals(path)) {
      return true;
    }
    if ("GET".equals(method) && path.startsWith("api/inventory-svc/inventory/availability")) {
      return true;
    }
    if ("POST".equals(method) && "api/pricing-svc/prices/resolve".equals(path)) {
      return true;
    }
    // The currencies a shop shows prices in (03.x): the picker a visitor sees before signing in.
    if ("GET".equals(method) && "api/pricing-svc/prices/currencies".equals(path)) {
      return true;
    }
    // Active promotions powering the storefront offers banner (advertised, public offers).
    if ("GET".equals(method) && "api/pricing-svc/promotions".equals(path)) {
      return true;
    }
    // NOTE: order placement and payment are NOT listed here — they require a signed-in customer
    // token and are handled by isStorefrontCustomer(). Allowing unauthenticated callers to
    // supply X-Storefront-Tenant on mutating endpoints would let any party inject orders or
    // payment records into any tenant's namespace without authentication.
    return false;
  }

  /**
   * {@code POST api/payment-svc/payments/webhooks/{provider}} — exactly five segments, so nothing
   * deeper inherits the exemption.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} if this is a provider webhook delivery
   */
  private static boolean isProviderWebhook(String path, String method) {
    return "POST".equals(method) && isLeafUnder(path, "api/payment-svc/payments/webhooks/");
  }

  /**
   * {@code POST api/purchase-svc/e-invoices/inbound/{network}} — exactly six segments, so nothing
   * deeper inherits the exemption, and never the upload route beside it.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} if this is a network delivering an e-invoice
   */
  private static boolean isEInvoiceDelivery(String path, String method) {
    return "POST".equals(method) && isLeafUnder(path, "api/purchase-svc/e-invoices/inbound/");
  }

  /** Whether the path is the prefix plus exactly one more non-empty segment. */
  private static boolean isLeafUnder(String path, String prefix) {
    if (!path.startsWith(prefix)) {
      return false;
    }
    String leaf = path.substring(prefix.length());
    return !leaf.isEmpty() && leaf.indexOf('/') < 0;
  }

  /**
   * {@code api/payment-svc/payments/intents/{id}} and nothing under it.
   *
   * @param path the normalized request path
   * @return {@code true} for exactly that shape
   */
  /**
   * {@code GET api/order-svc/orders/{uuid}} and its {@code history}, {@code returns} and {@code
   * fiscal-receipt} children — and only an id-shaped segment, so a literal such as {@code export}
   * (staff-only) can never pass as an order.
   *
   * @param path the normalized request path
   * @return whether it is a shopper's id-addressed read of one order
   */
  /**
   * {@code GET .../orders/recall-notices/mine} and {@code POST
   * .../orders/recall-notices/{id}/remedy}.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} for exactly those two shapes
   */
  private static boolean isStorefrontRecallNotice(String path, String method) {
    String prefix = "api/order-svc/orders/recall-notices/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    if ("GET".equals(method)) {
      return "mine".equals(rest);
    }
    int slash = rest.indexOf('/');
    return "POST".equals(method)
        && slash > 0
        && looksLikeUuid(rest.substring(0, slash))
        && "remedy".equals(rest.substring(slash + 1));
  }

  private static boolean isOrderGroupSelfRead(String path) {
    String prefix = "api/order-svc/order-groups/";
    return path.startsWith(prefix) && looksLikeUuid(path.substring(prefix.length()));
  }

  private static boolean isOrderSelfRead(String path) {
    String prefix = "api/order-svc/orders/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf('/');
    String id = slash < 0 ? rest : rest.substring(0, slash);
    if (!looksLikeUuid(id)) {
      return false;
    }
    if (slash < 0) {
      return true;
    }
    String child = rest.substring(slash + 1);
    return "history".equals(child) || "returns".equals(child) || "fiscal-receipt".equals(child);
  }

  private static boolean isPaymentIntentRead(String path) {
    String prefix = "api/payment-svc/payments/intents/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    return !rest.isEmpty() && rest.indexOf('/') < 0;
  }

  private static boolean isPublic(String path) {
    // Exact match only — a substring match would let any URL that merely embeds a public
    // suffix (e.g. /api/x-svc/foo/iam-svc/auth/login) skip token validation.
    String normalized = normalize(path);
    return PUBLIC_PATHS.contains(normalized) || isPayLink(normalized);
  }

  /**
   * The pay link, matched by shape: the prefix plus exactly one more segment.
   *
   * @param normalized the normalized path, no leading or trailing slash
   * @return {@code true} for exactly {@code api/tenant-svc/billing/pay/{token}}
   */
  private static boolean isPayLink(String normalized) {
    if (!normalized.startsWith(PAY_LINK_PREFIX)) return false;
    String token = normalized.substring(PAY_LINK_PREFIX.length());
    return !token.isEmpty() && token.indexOf('/') < 0;
  }

  /**
   * {@code GET /api/{service}/openapi} — the MicroProfile OpenAPI contract document Helidon exposes
   * on every business service. Exactly three segments (the trailing-segment match keeps this from
   * also matching e.g. {@code api/order-svc/orders/openapi-discount}); the service name itself is
   * still gated by {@link com.storeql.gateway.GatewayConfig#routableServices()} in {@code
   * ProxyResource}, so this only ever reaches a real, routable service.
   */
  private static boolean isOpenApiSpec(String normalizedPath) {
    String[] segments = normalizedPath.split("/");
    return segments.length == 3 && "api".equals(segments[0]) && "openapi".equals(segments[2]);
  }

  private static String normalize(String path) {
    String p = path;
    while (p.startsWith("/")) p = p.substring(1);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    // Collapse an optional API version segment so /api/v1/... matches the same public/storefront/
    // onboarding whitelists as the unversioned /api/... alias (golden rule #2 stays exact-match).
    p = p.replaceFirst("^api/v\\d+/", "api/");
    return p;
  }

  /**
   * A business's API key at the door (22.7). The key is what one of the business's systems presents
   * instead of a person's sign-in, so it acts as the business in the tier the key was given, for
   * the stores it was given, and is stamped like a token would be — with the key as the actor, so
   * every audit trail says which key did what. Three things a key is not: a person who can sign in,
   * set up a second factor or trade a ticket (everything under iam-svc); a person who can start a
   * business or add a store to one (onboarding); the platform (anything under a service's {@code
   * /platform}). Those routes are refused before iam-svc is even asked. A key iam-svc does not
   * know, has revoked, or that has expired is "invalid" and nothing more — which it was is not the
   * caller's to act on differently; a key of a business that was switched off is refused the way
   * the business is; and when iam-svc cannot be asked the caller is told to try again, because a
   * 401 would tell an integrator its key is bad when it is not.
   */
  private void authenticateApiKey(ContainerRequestContext ctx, String key, String target) {
    if (isNoRouteForAKey(target)) {
      ctx.abortWith(keyRouteForbidden());
      return;
    }
    ApiKeyIntrospector.Verdict verdict = apiKeys.introspect(key);
    switch (verdict) {
      case ApiKeyIntrospector.Unavailable unavailable -> ctx.abortWith(keyCheckUnavailable());
      case ApiKeyIntrospector.Refused refused -> {
        if ("tenant suspended".equals(refused.reason())) {
          ctx.abortWith(tenantSuspended());
          return;
        }
        LOG.log(
            System.Logger.Level.INFO,
            "API key {0}… refused on {1}: {2}",
            key.substring(0, Math.min(key.length(), 12)),
            target,
            refused.reason());
        ctx.abortWith(unauthorized("Invalid, expired or revoked API key"));
      }
      case ApiKeyIntrospector.Active active -> {
        ctx.getHeaders().putSingle(HttpHeaders.USER_ID, active.keyId());
        ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, active.tenantId());
        ctx.getHeaders().putSingle(HttpHeaders.ROLES, active.role());
        if (!active.storeIds().isEmpty()) {
          ctx.getHeaders().putSingle(HttpHeaders.STORE_IDS, String.join(",", active.storeIds()));
        }
        ctx.getHeaders().putSingle(HttpHeaders.AUTH_METHODS, "api-key");
      }
    }
  }

  /** The routes a key never reaches, whatever tier it holds: see {@link #authenticateApiKey}. */
  static boolean isNoRouteForAKey(String target) {
    return target.startsWith("api/iam-svc/")
        || target.matches("api/[a-z0-9-]+/onboarding(/.*)?")
        || target.matches("api/[a-z0-9-]+/platform(/.*)?");
  }

  private static Response keyRouteForbidden() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.storeql.web.ApiResponse.error(
                com.storeql.web.ErrorBody.of(
                    "API_KEY_ROUTE_FORBIDDEN",
                    "An API key cannot sign in, manage logins, start a business or act for the"
                        + " platform; use a person's sign-in for that")))
        .build();
  }

  private static Response keyCheckUnavailable() {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Retry-After", "5")
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.storeql.web.ApiResponse.error(
                com.storeql.web.ErrorBody.of(
                    "API_KEY_CHECK_UNAVAILABLE",
                    "the API key cannot be checked right now; try again shortly")))
        .build();
  }

  private static Response tenantSuspended() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.storeql.web.ApiResponse.error(
                com.storeql.web.ErrorBody.of(
                    "TENANT_INACTIVE", "This store is currently unavailable.")))
        .build();
  }

  private static Response keysUnavailable() {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Retry-After", "5")
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.storeql.web.ApiResponse.error(
                com.storeql.web.ErrorBody.of(
                    "AUTH_KEYS_UNAVAILABLE",
                    "tokens cannot be verified yet: the signing keys have not been read")))
        .build();
  }

  private static Response enrolmentOwed() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.storeql.web.ApiResponse.error(
                com.storeql.web.ErrorBody.of(
                    "MFA_ENROLMENT_REQUIRED",
                    "This sign-in must set up a second factor before it can do anything else")))
        .build();
  }

  private static Response unauthorized(String message) {
    return Response.status(Response.Status.UNAUTHORIZED)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.storeql.web.ApiResponse.error(
                com.storeql.web.ErrorBody.of("UNAUTHORIZED", message)))
        .build();
  }
}
