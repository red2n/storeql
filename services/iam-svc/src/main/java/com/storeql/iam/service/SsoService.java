package com.storeql.iam.service;

import com.storeql.iam.auth.KeySealer;
import com.storeql.iam.auth.Tokens;
import com.storeql.iam.config.ServiceConfig;
import com.storeql.iam.domain.Sso;
import com.storeql.iam.domain.User;
import com.storeql.iam.dto.Dtos.TokenResponse;
import com.storeql.iam.dto.SsoDtos;
import com.storeql.iam.repo.SsoRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.sso.Discovery;
import com.storeql.iam.sso.IdToken;
import com.storeql.iam.sso.Jwks;
import com.storeql.iam.sso.OidcClient;
import com.storeql.iam.sso.Pkce;
import com.storeql.iam.sso.SsoRefused;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Single sign-on through a business's own identity provider, over OpenID Connect's authorization
 * code flow with PKCE (20.x, SSO).
 *
 * <p>A sign-in goes: the app asks to start, naming the business by its sign-in name and holding a
 * PKCE verifier of its own; the browser goes to the provider; the provider sends it back to the
 * callback with a code; this service trades the code for an ID token, checks it, matches the person
 * to a login of that business, and sends the browser back to the app with a ticket; the app trades
 * the ticket and its verifier for exactly what a password sign-in answers — the token pair, or a
 * second factor owed. So everything after the first factor is the password path's, unchanged.
 *
 * <p>People are matched, never created. A login's roles come from the business's assignments in
 * tenant-svc, so a login made here would be a login that can do nothing; a person the business has
 * not added is told to ask to be added. The first sign-in matches by email — verified by the
 * provider, unless the business says its provider does not say so — and links the provider's
 * subject, which is what every later sign-in matches by, because an email can be handed to someone
 * else at the provider and a subject cannot.
 */
@ApplicationScoped
public class SsoService {

  /** The tiers a business may require its provider of. Never OWNER: see {@link #put}. */
  public static final Set<String> TIERS = Set.of("MANAGER", "STOREKEEPER", "CASHIER");

  /** What a sign-in asks the provider for: who, and their address. */
  private static final String SCOPES = "openid email profile";

  private static final System.Logger LOG = System.getLogger(SsoService.class.getName());

  @Inject SsoRepository sso;
  @Inject UserRepository users;
  @Inject OidcClient oidc;
  @Inject ServiceConfig config;
  @Inject AuthService auth;
  @Inject TenantStatusRepository tenantStatus;

  private KeySealer sealer;

  @PostConstruct
  void init() {
    sealer = new KeySealer(config.jwtSecret(), "storeql-sso-secret-seal:");
  }

  // ── a business's provider ───────────────────────────────────────────────────

  public Optional<SsoDtos.ConnectionView> view(UUID tenantId) {
    return sso.connection(tenantId).map(this::view);
  }

  /**
   * Connects a business's provider, or changes it.
   *
   * <p>An owner can never be made to use the provider. A business whose provider breaks — a secret
   * expired, a tenant deleted at the provider, a misconfiguration saved at eleven at night — would
   * otherwise have nobody left who can sign in to fix it.
   */
  public SsoDtos.ConnectionView put(UUID tenantId, UUID by, SsoDtos.ConnectionRequest req) {
    Set<String> tiers = new TreeSet<>(req.requiredTiers());
    if (tiers.contains("OWNER")) {
      throw ApiException.badRequest(
          "SSO_OWNER_NOT_REQUIRABLE",
          "An owner can always sign in with a password, so a provider that breaks cannot lock the"
              + " business out");
    }
    if (!TIERS.containsAll(tiers)) {
      throw ApiException.badRequest(
          "SSO_TIER_UNKNOWN", "Tiers are MANAGER, STOREKEEPER and CASHIER");
    }
    String issuer = issuerOf(req.issuer());
    Optional<Sso.Connection> current = sso.connection(tenantId);
    String secret = req.clientSecret() == null ? "" : req.clientSecret().trim();
    boolean secretHeld = current.map(c -> c.clientSecretSealed() != null).orElse(false);
    if (secret.isEmpty() && !secretHeld) {
      throw ApiException.badRequest(
          "SSO_SECRET_REQUIRED", "Enter the client secret the provider gave you");
    }
    Sso.Connection saved =
        sso.put(
            tenantId,
            req.slug(),
            issuer,
            req.clientId().trim(),
            secret.isEmpty() ? null : sealer.seal(secret.getBytes(StandardCharsets.UTF_8)),
            req.enabled(),
            tiers,
            req.requireVerifiedEmail() == null || req.requireVerifiedEmail(),
            by,
            Instant.now());
    users.audit(
        tenantId,
        by,
        "SSO_CONNECTION_CHANGED",
        saved.slug()
            + " "
            + saved.issuer()
            + (saved.enabled() ? " enabled" : " disabled")
            + " required="
            + String.join(",", new TreeSet<>(saved.requiredTiers())));
    return view(saved);
  }

  public void delete(UUID tenantId, UUID by) {
    if (!sso.delete(tenantId)) {
      throw ApiException.notFound("SSO_NOT_CONFIGURED", "This business has no identity provider");
    }
    users.audit(tenantId, by, "SSO_CONNECTION_REMOVED", null);
  }

  /**
   * Everything that must hold before staff can sign in through the provider — including asking it.
   * The provider is read fresh, not from the cache, since the point is to find out now.
   */
  public SsoDtos.Readiness readiness(UUID tenantId) {
    List<SsoDtos.ReadinessCheck> checks = new ArrayList<>();
    boolean callback = config.ssoCallbackUrl().isPresent();
    checks.add(
        check(
            "CALLBACK_CONFIGURED",
            callback,
            callback
                ? "Register " + config.ssoCallbackUrl().get() + " as the redirect URI"
                : "This deployment has no callback address: the platform operator sets"
                    + " storeql.sso.callback-url"));
    Optional<Sso.Connection> found = sso.connection(tenantId);
    checks.add(
        check(
            "CONNECTION_SAVED",
            found.isPresent(),
            found.isPresent() ? "Saved" : "Save the provider's issuer and client first"));
    if (found.isEmpty()) return new SsoDtos.Readiness(false, checks);
    Sso.Connection c = found.get();
    checks.add(
        check(
            "SECRET_HELD",
            c.clientSecretSealed() != null,
            c.clientSecretSealed() != null ? "Held" : "Enter the client secret again"));
    Discovery d = null;
    try {
      d = oidc.discoverFresh(c.issuer());
      checks.add(check("DISCOVERY_READ", true, "Read from " + Discovery.location(c.issuer())));
    } catch (SsoRefused e) {
      checks.add(check("DISCOVERY_READ", false, remedy(e.code(), c.issuer())));
    }
    if (d != null) {
      try {
        Jwks keys = oidc.keysFresh(d);
        checks.add(
            check(
                "KEYS_READ",
                keys.size() > 0,
                keys.size() > 0
                    ? keys.size() + " RS256 signing key(s)"
                    : "The provider publishes no RS256 signing key"));
      } catch (SsoRefused e) {
        checks.add(check("KEYS_READ", false, remedy(e.code(), d.jwksUri())));
      }
    } else {
      checks.add(check("KEYS_READ", false, "Not asked: the discovery document could not be read"));
    }
    checks.add(
        check(
            "ENABLED",
            c.enabled(),
            c.enabled() ? "Staff can choose it" : "Switch it on when the checks above hold"));
    return new SsoDtos.Readiness(
        checks.stream().allMatch(SsoDtos.ReadinessCheck::satisfied), checks);
  }

  public SsoDtos.LinkedLoginPage identities(UUID tenantId, UUID after, int limit) {
    List<SsoRepository.LinkedLogin> rows = sso.identities(tenantId, after, limit + 1);
    boolean more = rows.size() > limit;
    List<SsoRepository.LinkedLogin> page = more ? rows.subList(0, limit) : rows;
    return new SsoDtos.LinkedLoginPage(
        page.stream()
            .map(
                r ->
                    new SsoDtos.LinkedLogin(
                        r.identity().id().toString(),
                        r.identity().userId().toString(),
                        r.loginEmail(),
                        r.identity().subject(),
                        r.identity().email(),
                        r.identity().linkedAt().toString(),
                        r.identity().lastLoginAt().toString()))
            .toList(),
        more ? page.get(page.size() - 1).identity().id().toString() : null);
  }

  /** Unlinks a person: matched by email again at their next sign-in, as if the first time. */
  public void unlink(UUID tenantId, UUID id, UUID by) {
    if (!sso.unlink(tenantId, id)) {
      throw ApiException.notFound("SSO_LINK_NOT_FOUND", "No such link");
    }
    users.audit(tenantId, by, "SSO_UNLINKED", id.toString());
  }

  // ── a sign-in ───────────────────────────────────────────────────────────────

  /** Starts a sign-in: where to send the browser. */
  public SsoDtos.Started start(SsoDtos.StartRequest req) {
    String callback =
        config
            .ssoCallbackUrl()
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "SSO_UNAVAILABLE",
                        "Single sign-on is not available on this platform",
                        List.of()));
    String returnTo = returnTo(req.returnTo());
    Sso.Connection c =
        sso.connectionBySlug(req.slug().trim().toLowerCase(Locale.ROOT))
            .filter(Sso.Connection::enabled)
            .orElseThrow(
                () ->
                    ApiException.notFound("SSO_NOT_FOUND", "No business signs in with that name"));
    if (!tenantStatus.isActive(c.tenantId())) {
      throw ApiException.forbidden(
          "TENANT_INACTIVE", "This business account is suspended. Contact support.");
    }
    if (c.clientSecretSealed() == null) {
      throw ApiException.conflict(
          "SSO_NOT_READY", "This business's single sign-on is not finished: ask its owner");
    }
    Discovery d;
    try {
      d = oidc.discover(c.issuer());
    } catch (SsoRefused e) {
      LOG.log(System.Logger.Level.WARNING, "sso start for {0}: {1}", c.slug(), e.getMessage());
      throw new ApiException(
          502, e.code(), "Your business's identity provider could not be used", List.of(), e);
    }
    String state = Pkce.random(32);
    String nonce = Pkce.random(32);
    String verifier = Pkce.newVerifier();
    Instant now = Instant.now();
    sso.openFlow(
        c.tenantId(),
        c.id(),
        Tokens.hash(state),
        nonce,
        sealer.seal(verifier.getBytes(StandardCharsets.US_ASCII)),
        req.codeChallenge(),
        returnTo,
        now,
        now.plusSeconds(config.ssoFlowTtlSeconds()));
    Map<String, String> query = new LinkedHashMap<>();
    query.put("response_type", "code");
    query.put("client_id", c.clientId());
    query.put("redirect_uri", callback);
    query.put("scope", SCOPES);
    query.put("state", state);
    query.put("nonce", nonce);
    query.put("code_challenge", Pkce.challenge(verifier));
    query.put("code_challenge_method", "S256");
    return new SsoDtos.Started(withQuery(d.authorizationEndpoint(), query));
  }

  /**
   * The provider sent the browser back. Whatever happens, the answer is where to send the browser
   * next: the app, with a ticket, or with the reason it did not work. Nothing the provider said is
   * passed on — only a code the app knows how to explain.
   */
  public String returned(String code, String state, String error) {
    if (state == null || state.isBlank() || state.length() > 128) {
      return fragment(defaultReturn(), "sso_error", "SSO_STATE_INVALID");
    }
    Instant now = Instant.now();
    Optional<Sso.Flow> waiting = sso.returned(Tokens.hash(state), now);
    if (waiting.isEmpty()) {
      // Unknown, expired, or already back once: which, is nobody's business.
      return fragment(defaultReturn(), "sso_error", "SSO_STATE_INVALID");
    }
    Sso.Flow flow = waiting.get();
    try {
      if (error != null && !error.isBlank()) {
        throw new SsoRefused(
            "access_denied".equals(error) ? "SSO_CANCELLED" : SsoRefused.EXCHANGE_REFUSED,
            "the provider answered " + (error.matches("[a-z_]{1,64}") ? error : "an error"));
      }
      if (code == null || code.isBlank() || code.length() > 2048) {
        throw new SsoRefused(SsoRefused.EXCHANGE_REFUSED, "the provider sent no code");
      }
      Sso.Connection c =
          sso.connection(flow.tenantId())
              .filter(x -> x.id().equals(flow.connectionId()) && x.enabled())
              .filter(x -> x.clientSecretSealed() != null)
              .orElseThrow(() -> new SsoRefused("SSO_NOT_FOUND", "the connection changed"));
      if (!tenantStatus.isActive(flow.tenantId())) {
        throw new SsoRefused("TENANT_INACTIVE", "the business is suspended");
      }
      IdToken proved =
          oidc.exchange(
              oidc.discover(c.issuer()),
              c.clientId(),
              new String(sealer.open(c.clientSecretSealed()), StandardCharsets.UTF_8),
              code,
              config.ssoCallbackUrl().orElseThrow(),
              new String(sealer.open(flow.codeVerifierSealed()), StandardCharsets.US_ASCII),
              flow.nonce());
      User user = match(c, proved, now);
      String ticket = Tokens.newOpaqueToken();
      String amr =
          proved.multiFactor()
              ? AuthService.AMR_SSO + "," + AuthService.AMR_MFA
              : AuthService.AMR_SSO;
      sso.leaveTicket(
          flow.tenantId(),
          flow.id(),
          Tokens.hash(ticket),
          user.id(),
          amr,
          now.plusSeconds(config.ssoTicketTtlSeconds()));
      users.audit(flow.tenantId(), user.id(), "SSO_LOGIN_PROVED", amr);
      return fragment(flow.returnTo(), "sso_ticket", ticket);
    } catch (SsoRefused e) {
      LOG.log(System.Logger.Level.WARNING, "sso sign-in refused: {0}", e.getMessage());
      users.audit(flow.tenantId(), null, "SSO_LOGIN_REFUSED", e.code());
      return fragment(flow.returnTo(), "sso_error", e.code());
    }
  }

  /**
   * The app trades the ticket, and the verifier its start was made with, for the sign-in. A ticket
   * is spent by the first attempt, right or wrong: a wrong verifier means the ticket reached a
   * browser that did not start this sign-in, and that is exactly the ticket that must not work.
   */
  public TokenResponse redeem(SsoDtos.TicketRequest req) {
    Sso.Flow flow =
        sso.redeem(Tokens.hash(req.ticket()), Instant.now()).orElseThrow(SsoService::ticketInvalid);
    if (!Pkce.matches(req.codeVerifier(), flow.appChallenge())) {
      users.audit(flow.tenantId(), flow.userId(), "SSO_TICKET_REFUSED", "verifier mismatch");
      throw ticketInvalid();
    }
    User user =
        users
            .findById(flow.userId())
            .filter(u -> User.STATUS_ACTIVE.equals(u.status()))
            .filter(u -> flow.tenantId().equals(u.tenantId()))
            .orElseThrow(
                () ->
                    ApiException.unauthorized(
                        "SSO_ACCOUNT_UNAVAILABLE", "This login can no longer sign in here"));
    if (!tenantStatus.isActive(user.tenantId())) {
      throw ApiException.forbidden(
          "TENANT_INACTIVE", "This business account is suspended. Contact support.");
    }
    users.audit(user.tenantId(), user.id(), "SSO_LOGIN_OK", flow.amr());
    return auth.afterFirstFactor(user, List.of(flow.amr().split(",")));
  }

  // ── matching the provider's person to a login ───────────────────────────────

  private User match(Sso.Connection c, IdToken proved, Instant now) {
    UUID tenant = c.tenantId();
    Optional<Sso.Identity> linked = sso.identity(tenant, c.issuer(), proved.subject());
    if (linked.isPresent()) {
      Sso.Identity link = linked.get();
      Optional<User> user = users.findById(link.userId());
      if (user.isEmpty() || !tenant.equals(user.get().tenantId()) || !isStaff(user.get())) {
        // Taken off this business's staff since: the link has nothing left to point at. A login
        // made in the business stays in it when let go of its last store, holding no staff role.
        sso.unlink(tenant, link.id());
        throw new SsoRefused("SSO_NO_ACCOUNT", "the linked login is no longer this business's");
      }
      if (!User.STATUS_ACTIVE.equals(user.get().status())) {
        throw new SsoRefused("SSO_ACCOUNT_UNAVAILABLE", "the linked login is not active");
      }
      sso.touch(tenant, link.id(), proved.email(), now);
      return user.get();
    }
    String email = proved.email();
    if (email == null) {
      throw new SsoRefused("SSO_EMAIL_MISSING", "the provider gave no email address");
    }
    if (c.requireVerifiedEmail() && !proved.emailVerified()) {
      throw new SsoRefused("SSO_EMAIL_UNVERIFIED", "the provider has not verified the address");
    }
    // A login the business holds no staff role on — provisioned and not yet assigned, or let go of
    // its last store — is nobody's staff yet, as it was when such a login belonged to no business.
    User user =
        users
            .findByEmail(tenant, email)
            .filter(this::isStaff)
            .orElseThrow(() -> new SsoRefused("SSO_NO_ACCOUNT", "no login of this business"));
    if (!User.STATUS_ACTIVE.equals(user.status())) {
      throw new SsoRefused("SSO_ACCOUNT_UNAVAILABLE", "the login is not active");
    }
    // One of the provider's people per login: a second subject claiming the same address is
    // somebody the address was given to after the first — the owner unlinks the old one if so.
    if (sso.identityOfUser(tenant, user.id(), c.issuer()).isPresent()) {
      throw new SsoRefused("SSO_ALREADY_LINKED", "the login is linked to another person");
    }
    if (!sso.link(tenant, user.id(), c.issuer(), proved.subject(), email, now)) {
      // A first sign-in racing another: the link stands if it is this person's.
      boolean same =
          sso.identity(tenant, c.issuer(), proved.subject())
              .map(i -> i.userId().equals(user.id()))
              .orElse(false);
      if (!same) throw new SsoRefused("SSO_ALREADY_LINKED", "linked meanwhile to another person");
    } else {
      users.audit(tenant, user.id(), "SSO_LINKED", c.issuer());
    }
    return user;
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Whether the login holds a staff role: any role but a shopper's or the platform's. */
  private boolean isStaff(User user) {
    for (String role : users.rolesOf(user.id())) {
      if (!"CUSTOMER".equals(role) && !"PLATFORM_ADMIN".equals(role)) return true;
    }
    return false;
  }

  private SsoDtos.ConnectionView view(Sso.Connection c) {
    return new SsoDtos.ConnectionView(
        c.slug(),
        c.issuer(),
        c.clientId(),
        c.clientSecretSealed() != null,
        c.enabled(),
        new TreeSet<>(c.requiredTiers()).stream().toList(),
        c.requireVerifiedEmail(),
        config.ssoCallbackUrl().orElse(null),
        c.updatedAt().toString());
  }

  /**
   * An issuer as a business typed it, held to the address rule's shape here, where the business can
   * be told; its host is resolved and checked when it is called.
   */
  private String issuerOf(String typed) {
    String issuer = typed.trim();
    URI uri;
    try {
      uri = new URI(issuer);
    } catch (URISyntaxException e) {
      throw new ApiException(400, "SSO_ISSUER_INVALID", "The issuer is not a URL", List.of(), e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    boolean own = config.ssoInsecureHosts().contains(host);
    if (host.isEmpty()
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !("https".equals(scheme) || (own && "http".equals(scheme)))) {
      throw ApiException.badRequest(
          "SSO_ISSUER_INVALID",
          "The issuer is an https:// address with no query or fragment, as the provider states it");
    }
    return issuer;
  }

  /** Where the browser goes back to: one of the app's origins, or nowhere. */
  private String returnTo(String asked) {
    if (asked == null || asked.isBlank()) return defaultReturn();
    URI uri;
    try {
      uri = new URI(asked.trim());
    } catch (URISyntaxException e) {
      throw new ApiException(400, "SSO_RETURN_REFUSED", RETURN_REFUSED, List.of(), e);
    }
    if (uri.getScheme() == null || uri.getHost() == null || uri.getRawFragment() != null) {
      throw refusedReturn();
    }
    String origin =
        uri.getScheme().toLowerCase(Locale.ROOT)
            + "://"
            + uri.getHost().toLowerCase(Locale.ROOT)
            + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    if (!config.ssoReturnOrigins().contains(origin) || uri.getRawUserInfo() != null) {
      throw refusedReturn();
    }
    return uri.toString();
  }

  private String defaultReturn() {
    List<String> origins = config.ssoReturnOrigins();
    return origins.isEmpty() ? "/" : origins.get(0) + "/";
  }

  private static final String RETURN_REFUSED = "A sign-in returns only to the platform's own app";

  private static ApiException refusedReturn() {
    return ApiException.badRequest("SSO_RETURN_REFUSED", RETURN_REFUSED);
  }

  private static ApiException ticketInvalid() {
    return ApiException.unauthorized(
        "SSO_TICKET_INVALID", "This sign-in is no longer waiting: start it again");
  }

  /** The answer goes in the fragment, which a browser never sends to a server or a referrer. */
  private static String fragment(String base, String name, String value) {
    return base + "#" + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String withQuery(String endpoint, Map<String, String> query) {
    StringBuilder out = new StringBuilder(endpoint);
    char sep = endpoint.contains("?") ? '&' : '?';
    for (Map.Entry<String, String> e : query.entrySet()) {
      out.append(sep)
          .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
      sep = '&';
    }
    return out.toString();
  }

  private static SsoDtos.ReadinessCheck check(String code, boolean ok, String detail) {
    return new SsoDtos.ReadinessCheck(code, ok, detail);
  }

  private static String remedy(String code, String where) {
    return switch (code) {
      case SsoRefused.ADDRESS_REFUSED ->
          where + " is not an address this platform calls: it must be HTTPS on a public address";
      case SsoRefused.UNREACHABLE -> where + " did not answer: check the issuer, and try again";
      default ->
          where
              + " answered, but not as an OpenID Connect provider this platform can use: check the"
              + " issuer is exactly what the provider states";
    };
  }
}
