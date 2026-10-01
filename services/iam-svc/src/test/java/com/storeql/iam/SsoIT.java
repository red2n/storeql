package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.iam.mfa.Totp;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.service.AuthService;
import com.storeql.iam.sso.FakeOidcProvider;
import com.storeql.iam.sso.FakeOidcProvider.Person;
import com.storeql.iam.sso.Pkce;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Single sign-on over HTTP and Postgres, against an OpenID Connect provider that holds its codes to
 * the rules: a business connecting its provider, a sign-in from start to tokens, the second factor
 * each way, a business requiring its provider of a tier — and what must not work: a ticket in the
 * wrong browser or twice, a state twice, a person the business never added, an address the provider
 * never verified, a subject claiming a login already linked, a forged token, a login taken off the
 * staff, a return to anywhere but the app.
 *
 * <p>Requests carry the identity headers the gateway would have stamped from a verified token.
 */
@HelidonTest
class SsoIT {

  private static final PostgresSupport PG;
  private static final FakeOidcProvider PROVIDER;
  private static final String PASSWORD = "a phrase long enough";
  private static final String CALLBACK = "http://localhost:8090/api/iam-svc/auth/sso/callback";
  private static final String APP = "http://localhost:8088/";
  private static final String CONSUMER = "sso-it";

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    try {
      PROVIDER = new FakeOidcProvider();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
    System.setProperty("storeql.sso.callback-url", CALLBACK);
    System.setProperty("storeql.sso.insecure-hosts", "localhost");
  }

  @Inject WebTarget target;
  @Inject UserRepository users;
  @Inject AuthService auth;

  @AfterAll
  static void stop() {
    PROVIDER.close();
    PG.stop();
  }

  @BeforeEach
  void reset() {
    PROVIDER.reset();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private record Caller(UUID userId, String roles, UUID tenantId, String scope, String methods) {
    static final Caller NOBODY = new Caller(null, null, null, null, null);

    Caller(UUID userId, String roles, UUID tenantId) {
      this(userId, roles, tenantId, null, null);
    }
  }

  private Answer call(String method, String path, Caller who, String json) {
    Invocation.Builder b = target.path(path).request();
    if (who.userId() != null) b = b.header("X-User-Id", who.userId());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId());
    if (who.scope() != null) b = b.header("X-Auth-Scope", who.scope());
    if (who.methods() != null) b = b.header("X-Auth-Methods", who.methods());
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "DELETE" -> b.delete();
          case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
          default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private UUID register(String email) {
    Answer a =
        call(
            "POST",
            "/auth/register",
            Caller.NOBODY,
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
  }

  private Answer passwordLogin(String email) {
    return call(
        "POST",
        "/auth/login",
        Caller.NOBODY,
        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
  }

  /** A business with an owner, and its staff by tier. */
  private record Business(UUID tenant, UUID store, UUID ownerId, Caller owner) {}

  private Business business(String label) {
    UUID tenant = Ids.newId();
    UUID ownerId = register(label + "-owner@example.com");
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    return new Business(tenant, Ids.newId(), ownerId, new Caller(ownerId, "OWNER", tenant));
  }

  /**
   * A member of staff made the one way there is: provisioned in the business, then bound at its
   * store, as a StaffAssigned binds only a login already there.
   */
  private UUID staff(Business b, String email, String tier) {
    UUID id = Ids.parse(auth.provisionStaff(b.tenant(), email, PASSWORD).userId());
    users.bindStaffOnce(Ids.newId(), CONSUMER, id, b.tenant(), tier, b.store());
    return id;
  }

  private static String connection(String slug, String issuer, String secret, String tiers) {
    return "{\"slug\":\""
        + slug
        + "\",\"issuer\":\""
        + issuer
        + "\",\"clientId\":\""
        + FakeOidcProvider.CLIENT_ID
        + "\""
        + (secret == null ? "" : ",\"clientSecret\":\"" + secret + "\"")
        + ",\"enabled\":true,\"requiredTiers\":["
        + tiers
        + "]}";
  }

  private void connect(Business b, String slug, String tiers) {
    Answer saved =
        call(
            "PUT",
            "/auth/admin/sso",
            b.owner(),
            connection(slug, PROVIDER.issuer(), FakeOidcProvider.CLIENT_SECRET, tiers));
    assertThat(saved.body().toString(), saved.status(), is(200));
  }

  /** A sign-in in flight: the verifier the app keeps, and where the browser was sent. */
  private record Started(String verifier, String authorizationUrl) {}

  private Started start(String slug) {
    String verifier = Pkce.newVerifier();
    Answer a =
        call(
            "POST",
            "/auth/sso/start",
            Caller.NOBODY,
            "{\"slug\":\""
                + slug
                + "\",\"codeChallenge\":\""
                + Pkce.challenge(verifier)
                + "\",\"returnTo\":\""
                + APP
                + "\"}");
    assertThat(a.body().toString(), a.status(), is(200));
    return new Started(verifier, a.data().getString("authorizationUrl"));
  }

  /** The browser coming back to the callback: where it is sent next. */
  private String callback(Map<String, String> query) {
    WebTarget t = target.path("/auth/sso/callback");
    for (Map.Entry<String, String> e : query.entrySet()) t = t.queryParam(e.getKey(), e.getValue());
    Response r = t.request().property("jersey.config.client.followRedirects", false).get();
    assertThat(r.getStatus(), is(303));
    assertThat(r.getHeaderString("Cache-Control"), is("no-store"));
    return r.getHeaderString("Location");
  }

  /** What the app finds in the fragment it was sent back with. */
  private static Map<String, String> fragment(String location) {
    String f = URI.create(location).getRawFragment();
    int eq = f.indexOf('=');
    return Map.of(
        f.substring(0, eq), URLDecoder.decode(f.substring(eq + 1), StandardCharsets.UTF_8));
  }

  private Answer redeem(String ticket, String verifier) {
    return call(
        "POST",
        "/auth/sso/token",
        Caller.NOBODY,
        "{\"ticket\":\"" + ticket + "\",\"codeVerifier\":\"" + verifier + "\"}");
  }

  /** A whole sign-in through the provider as this person: what the browser comes back with. */
  private Map<String, String> signIn(String slug, Person person, Started[] started) {
    Started s = start(slug);
    if (started != null) started[0] = s;
    return fragment(callback(PROVIDER.authorize(s.authorizationUrl(), person)));
  }

  /** A whole sign-in, redeemed: what the app is answered. */
  private Answer signInAndRedeem(String slug, Person person) {
    Started[] s = new Started[1];
    Map<String, String> back = signIn(slug, person, s);
    assertThat(back.toString(), back.containsKey("sso_ticket"), is(true));
    return redeem(back.get("sso_ticket"), s[0].verifier());
  }

  private static List<String> amr(Answer tokens) {
    return JWT.decode(tokens.data().getString("accessToken")).getClaim("amr").asList(String.class);
  }

  private Answer refresh(Answer tokens) {
    return call(
        "POST",
        "/auth/refresh",
        Caller.NOBODY,
        "{\"refreshToken\":\"" + tokens.data().getString("refreshToken") + "\"}");
  }

  private static void sql(String statement, Object... binds) throws Exception {
    try (Connection c =
        java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      // The service's own schema, where it writes; the test database's default is another.
      c.setSchema("iam");
      try (PreparedStatement ps = c.prepareStatement(statement)) {
        for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
        ps.executeUpdate();
      }
    }
  }

  // ── connecting a provider ──────────────────────────────────────────────────

  @Test
  @DisplayName("An owner connects the provider; the secret goes in and never comes out")
  void anOwnerConnectsTheProvider() {
    Business b = business("sso-connect");
    UUID managerId = staff(b, "sso-connect-manager@example.com", "MANAGER");
    Caller manager = new Caller(managerId, "MANAGER", b.tenant());
    Caller cashier = new Caller(Ids.newId(), "CASHIER", b.tenant());
    String issuer = PROVIDER.issuer();

    assertThat(call("GET", "/auth/admin/sso", b.owner(), null).code(), is("SSO_NOT_CONFIGURED"));
    assertThat(
        call("PUT", "/auth/admin/sso", manager, connection("acme", issuer, "s", "")).status(),
        is(403));
    assertThat(
        call("PUT", "/auth/admin/sso", cashier, connection("acme", issuer, "s", "")).status(),
        is(403));
    assertThat(
        call("PUT", "/auth/admin/sso", b.owner(), connection("acme", issuer, "s", "\"OWNER\""))
            .code(),
        is("SSO_OWNER_NOT_REQUIRABLE"));
    assertThat(
        call("PUT", "/auth/admin/sso", b.owner(), connection("acme", issuer, "s", "\"JANITOR\""))
            .code(),
        is("SSO_TIER_UNKNOWN"));
    assertThat(
        call("PUT", "/auth/admin/sso", b.owner(), connection("acme", issuer, null, "")).code(),
        is("SSO_SECRET_REQUIRED"));
    assertThat(
        "a provider is HTTPS unless the deployment names its host",
        call(
                "PUT",
                "/auth/admin/sso",
                b.owner(),
                connection("acme", "http://idp.example.com", "s", ""))
            .code(),
        is("SSO_ISSUER_INVALID"));
    assertThat(
        call(
                "PUT",
                "/auth/admin/sso",
                b.owner(),
                connection("acme", "https://idp.example.com?x=1", "s", ""))
            .code(),
        is("SSO_ISSUER_INVALID"));
    for (String slug : new String[] {"A-B", "ab", "-ab", "a b c", "x".repeat(64)}) {
      assertThat(
          slug,
          call("PUT", "/auth/admin/sso", b.owner(), connection(slug, issuer, "s", "")).status(),
          is(400));
    }

    connect(b, "sso-connect", "\"CASHIER\"");
    Answer read = call("GET", "/auth/admin/sso", manager, null);
    assertThat(read.body().toString(), read.status(), is(200));
    assertThat(read.data().getBoolean("clientSecretSet"), is(true));
    assertThat(read.data().getString("callbackUrl"), is(CALLBACK));
    assertThat(read.data().getJsonArray("requiredTiers").toString(), is("[\"CASHIER\"]"));
    assertThat(read.data().getBoolean("requireVerifiedEmail"), is(true));
    assertThat(read.body().toString(), not(containsString(FakeOidcProvider.CLIENT_SECRET)));

    // Changed without the secret: the one held is kept.
    Answer kept =
        call(
            "PUT",
            "/auth/admin/sso",
            b.owner(),
            connection("sso-connect", issuer, null, "\"CASHIER\",\"MANAGER\""));
    assertThat(kept.body().toString(), kept.status(), is(200));
    assertThat(kept.data().getBoolean("clientSecretSet"), is(true));

    // Another business cannot take the name.
    Business other = business("sso-connect-other");
    Answer taken =
        call("PUT", "/auth/admin/sso", other.owner(), connection("SSO-connect", issuer, "s", ""));
    assertThat("the name is judged in lower case", taken.status(), is(400));
    Answer taken2 =
        call("PUT", "/auth/admin/sso", other.owner(), connection("sso-connect", issuer, "s", ""));
    assertThat(taken2.status(), is(409));
    assertThat(taken2.code(), is("SSO_SLUG_TAKEN"));
  }

  @Test
  @DisplayName("Readiness asks the provider now, and says what to fix when it is down")
  void readiness() {
    Business b = business("sso-ready");
    Answer none = call("GET", "/auth/admin/sso/readiness", b.owner(), null);
    assertThat(none.data().getBoolean("ready"), is(false));
    connect(b, "sso-ready", "");

    Answer ready = call("GET", "/auth/admin/sso/readiness", b.owner(), null);
    assertThat(ready.body().toString(), ready.data().getBoolean("ready"), is(true));
    assertThat(ready.data().getJsonArray("checks").size(), is(6));

    PROVIDER.breakDiscovery(true);
    Answer down = call("GET", "/auth/admin/sso/readiness", b.owner(), null);
    assertThat(down.data().getBoolean("ready"), is(false));
    assertThat(down.body().toString(), containsString("did not answer"));
  }

  @Test
  @DisplayName("Readiness is the owner's and the manager's alone, and each business reads its own")
  void readinessIsRefusedToOthersAndStaysWithinTheBusiness() {
    Business b = business("sso-readyneg");
    UUID managerId = staff(b, "sso-readyneg-manager@example.com", "MANAGER");
    connect(b, "sso-readyneg", "");
    Business other = business("sso-readyneg-other");
    String path = "/auth/admin/sso/readiness";

    for (String role : List.of("CASHIER", "STOREKEEPER", "CUSTOMER")) {
      Answer refused = call("GET", path, new Caller(Ids.newId(), role, b.tenant()), null);
      assertThat(role, refused.status(), is(403));
      assertThat(role, refused.body().containsKey("data"), is(false));
    }
    assertThat(
        "a manager may read it",
        call("GET", path, new Caller(managerId, "MANAGER", b.tenant()), null).status(),
        is(200));

    Answer theirs = call("GET", path, other.owner(), null);
    assertThat(theirs.body().toString(), theirs.status(), is(200));
    assertThat(
        "the other business has connected nothing, whatever ours has",
        theirs.data().getBoolean("ready"),
        is(false));
    assertThat(
        "ours is untouched by the other's read",
        call("GET", path, b.owner(), null).data().getBoolean("ready"),
        is(true));
  }

  @Test
  @DisplayName("A ticket for a login taken off the business after the callback is refused")
  void aTicketForALoginTakenOffTheBusinessIsRefused() throws Exception {
    Business b = business("sso-gone");
    UUID cashierId = staff(b, "sso-gone-cashier@example.com", "CASHIER");
    connect(b, "sso-gone", "");
    Started[] s = new Started[1];
    Map<String, String> back =
        signIn("sso-gone", Person.verified("g", "sso-gone-cashier@example.com"), s);
    assertThat(back.toString(), back.containsKey("sso_ticket"), is(true));

    // The login leaves the business between the callback and the app's redeem.
    sql("UPDATE users SET tenant_id = NULL, type = 'CUSTOMER' WHERE id = ?", cashierId);

    Answer refused = redeem(back.get("sso_ticket"), s[0].verifier());
    assertThat(refused.body().toString(), refused.status(), is(401));
    assertThat(refused.code(), is("SSO_ACCOUNT_UNAVAILABLE"));
    assertThat("no tokens are issued", refused.body().containsKey("data"), is(false));
    assertThat(
        "the ticket was spent by the attempt",
        redeem(back.get("sso_ticket"), s[0].verifier()).code(),
        is("SSO_TICKET_INVALID"));
  }

  // ── a sign-in ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A sign-in: start, the provider, back with a ticket, the token pair — once")
  void aSignIn() throws Exception {
    Business b = business("sso-flow");
    UUID cashierId = staff(b, "sso-flow-cashier@example.com", "CASHIER");
    connect(b, "sso-flow", "");

    // What starting asks for, and refuses.
    assertThat(
        call(
                "POST",
                "/auth/sso/start",
                Caller.NOBODY,
                "{\"slug\":\"nobody-has-this\",\"codeChallenge\":\""
                    + Pkce.challenge(Pkce.newVerifier())
                    + "\"}")
            .code(),
        is("SSO_NOT_FOUND"));
    for (String returnTo :
        new String[] {"https://evil.example.com/", "http://localhost:8088/#x", "javascript:x"}) {
      assertThat(
          returnTo,
          call(
                  "POST",
                  "/auth/sso/start",
                  Caller.NOBODY,
                  "{\"slug\":\"sso-flow\",\"codeChallenge\":\""
                      + Pkce.challenge(Pkce.newVerifier())
                      + "\",\"returnTo\":\""
                      + returnTo
                      + "\"}")
              .code(),
          is("SSO_RETURN_REFUSED"));
    }
    assertThat(
        call(
                "POST",
                "/auth/sso/start",
                Caller.NOBODY,
                "{\"slug\":\"sso-flow\",\"codeChallenge\":\"plain-is-not-s256\"}")
            .status(),
        is(400));

    Started s = start("SSO-Flow");
    URI to = URI.create(s.authorizationUrl());
    assertThat(to.toString(), startsWith(PROVIDER.issuer() + "/authorize?"));
    assertThat(to.getRawQuery(), containsString("code_challenge_method=S256"));
    assertThat(to.getRawQuery(), containsString("scope=openid+email+profile"));
    assertThat(
        "the provider is sent this service's challenge, not the app's",
        to.getRawQuery(),
        not(containsString(Pkce.challenge(s.verifier()))));

    Person ana = Person.verified("ana-subject", "sso-flow-cashier@example.com");
    Map<String, String> back = PROVIDER.authorize(s.authorizationUrl(), ana);
    String location = callback(back);
    assertThat(location, startsWith(APP + "#sso_ticket="));
    String ticket = fragment(location).get("sso_ticket");

    assertThat(
        "the state is spent", fragment(callback(back)).get("sso_error"), is("SSO_STATE_INVALID"));
    assertThat(
        fragment(callback(Map.of("code", "x", "state", "never-issued"))).get("sso_error"),
        is("SSO_STATE_INVALID"));
    assertThat(fragment(callback(Map.of())).get("sso_error"), is("SSO_STATE_INVALID"));

    // A ticket in another browser: the verifier is not the one the start was made with. Spent.
    Answer lifted = redeem(ticket, Pkce.newVerifier());
    assertThat(lifted.status(), is(401));
    assertThat(lifted.code(), is("SSO_TICKET_INVALID"));
    assertThat(redeem(ticket, s.verifier()).code(), is("SSO_TICKET_INVALID"));

    // The whole way, and the token is the cashier's, proved by the provider.
    Answer in = signInAndRedeem("sso-flow", ana);
    assertThat(in.body().toString(), in.status(), is(200));
    DecodedJWT token = JWT.decode(in.data().getString("accessToken"));
    assertThat(token.getSubject(), is(cashierId.toString()));
    assertThat(token.getClaim("tenant").asString(), is(b.tenant().toString()));
    assertThat(token.getClaim("roles").asList(String.class), hasItem("CASHIER"));
    assertThat(amr(in), contains("sso"));
    Answer renewed = refresh(in);
    assertThat(renewed.body().toString(), renewed.status(), is(200));
    assertThat("renewed as what it was", amr(renewed), contains("sso"));

    // The link is by subject from now on: the provider changing the address changes nothing.
    Answer again =
        signInAndRedeem(
            "sso-flow", new Person("ana-subject", "ana.renamed@example.com", true, List.of("pwd")));
    assertThat(again.body().toString(), again.status(), is(200));
    assertThat(
        JWT.decode(again.data().getString("accessToken")).getSubject(), is(cashierId.toString()));

    Answer linked = call("GET", "/auth/admin/sso/identities", b.owner(), null);
    assertThat(linked.data().getJsonArray("items").size(), is(1));
    JsonObject link = linked.data().getJsonArray("items").getJsonObject(0);
    assertThat(link.getString("subject"), is("ana-subject"));
    assertThat(link.getString("loginEmail"), is("sso-flow-cashier@example.com"));
    assertThat(link.getString("providerEmail"), is("ana.renamed@example.com"));
    assertThat(
        call(
                "GET",
                "/auth/admin/sso/identities",
                new Caller(cashierId, "CASHIER", b.tenant()),
                null)
            .status(),
        is(403));

    // A ticket is good for minutes, not for ever.
    Started[] late = new Started[1];
    String lateTicket = signIn("sso-flow", ana, late).get("sso_ticket");
    sql(
        "UPDATE sso_flows SET expires_at = ? WHERE tenant_id = ? AND redeemed_at IS NULL"
            + " AND ticket_hash IS NOT NULL",
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        b.tenant());
    assertThat(redeem(lateTicket, late[0].verifier()).code(), is("SSO_TICKET_INVALID"));
  }

  @Test
  @DisplayName("Who is not let in: nobody the business added, an unverified address, a forgery")
  void whoIsNotMatched() throws Exception {
    Business b = business("sso-who");
    staff(b, "sso-who-cashier@example.com", "CASHIER");
    Business other = business("sso-who-other");
    staff(other, "sso-who-elsewhere@example.com", "CASHIER");
    register("sso-who-shopper@example.com");
    // Made in the business and never assigned: nobody's staff yet.
    auth.provisionStaff(b.tenant(), "sso-who-pending@example.com", PASSWORD);
    connect(b, "sso-who", "");

    assertThat(
        signIn("sso-who", Person.verified("s1", "stranger@example.com"), null).get("sso_error"),
        is("SSO_NO_ACCOUNT"));
    assertThat(
        "another business's staff is not this business's",
        signIn("sso-who", Person.verified("s2", "sso-who-elsewhere@example.com"), null)
            .get("sso_error"),
        is("SSO_NO_ACCOUNT"));
    assertThat(
        "a shopper's login is nobody's staff",
        signIn("sso-who", Person.verified("s3", "sso-who-shopper@example.com"), null)
            .get("sso_error"),
        is("SSO_NO_ACCOUNT"));
    assertThat(
        "a login the business made and never assigned is nobody's staff yet",
        signIn("sso-who", Person.verified("s8", "sso-who-pending@example.com"), null)
            .get("sso_error"),
        is("SSO_NO_ACCOUNT"));
    assertThat(
        signIn(
                "sso-who",
                new Person("s4", "sso-who-cashier@example.com", false, List.of("pwd")),
                null)
            .get("sso_error"),
        is("SSO_EMAIL_UNVERIFIED"));
    assertThat(
        signIn("sso-who", new Person("s5", "sso-who-cashier@example.com", null, null), null)
            .get("sso_error"),
        is("SSO_EMAIL_UNVERIFIED"));
    assertThat(
        signIn("sso-who", new Person("s6", null, null, null), null).get("sso_error"),
        is("SSO_EMAIL_MISSING"));

    PROVIDER.signWith(FakeOidcProvider.strangersKey());
    assertThat(
        signIn("sso-who", Person.verified("s7", "sso-who-cashier@example.com"), null)
            .get("sso_error"),
        is("SSO_ID_TOKEN_INVALID"));
    PROVIDER.reset();
    PROVIDER.tweakTokens(t -> t.withClaim("nonce", "another sign-in's"));
    assertThat(
        signIn("sso-who", Person.verified("s7", "sso-who-cashier@example.com"), null)
            .get("sso_error"),
        is("SSO_ID_TOKEN_INVALID"));
    PROVIDER.reset();

    Started s = start("sso-who");
    Map<String, String> cancelled =
        Map.of(
            "error",
            "access_denied",
            "state",
            PROVIDER
                .authorize(s.authorizationUrl(), Person.verified("x", "x@example.com"))
                .get("state"));
    assertThat(fragment(callback(cancelled)).get("sso_error"), is("SSO_CANCELLED"));

    // The first subject links the login; a second one claiming the same address does not.
    assertThat(
        signInAndRedeem("sso-who", Person.verified("first", "sso-who-cashier@example.com"))
            .status(),
        is(200));
    assertThat(
        signIn("sso-who", Person.verified("second", "sso-who-cashier@example.com"), null)
            .get("sso_error"),
        is("SSO_ALREADY_LINKED"));
    // Until the owner unlinks the first: the address was given to somebody else.
    String linkId =
        call("GET", "/auth/admin/sso/identities", b.owner(), null)
            .data()
            .getJsonArray("items")
            .getJsonObject(0)
            .getString("id");
    assertThat(
        call(
                "DELETE",
                "/auth/admin/sso/identities/" + linkId,
                new Caller(Ids.newId(), "MANAGER", b.tenant()),
                null)
            .status(),
        is(403));
    Answer notOurs = call("DELETE", "/auth/admin/sso/identities/" + linkId, other.owner(), null);
    assertThat("not another business's link", notOurs.status(), is(404));
    assertThat(notOurs.code(), is("SSO_LINK_NOT_FOUND"));
    assertThat(
        call("DELETE", "/auth/admin/sso/identities/" + linkId, b.owner(), null).status(), is(200));
    assertThat(
        signInAndRedeem("sso-who", Person.verified("second", "sso-who-cashier@example.com"))
            .status(),
        is(200));
  }

  @Test
  @DisplayName("A business that trusts its provider's addresses, verified or not, may say so")
  void unverifiedAddressesWhenTheBusinessSaysSo() {
    Business b = business("sso-trust");
    staff(b, "sso-trust-cashier@example.com", "CASHIER");
    Answer saved =
        call(
            "PUT",
            "/auth/admin/sso",
            b.owner(),
            connection("sso-trust", PROVIDER.issuer(), FakeOidcProvider.CLIENT_SECRET, "")
                .replace("}", ",\"requireVerifiedEmail\":false}"));
    assertThat(saved.body().toString(), saved.data().getBoolean("requireVerifiedEmail"), is(false));
    assertThat(
        signInAndRedeem(
                "sso-trust", new Person("t1", "sso-trust-cashier@example.com", null, List.of()))
            .status(),
        is(200));
  }

  // ── the password, when the provider is required ────────────────────────────

  @Test
  @DisplayName("Required of cashiers: their password stops working, an owner's never does")
  void requiredOfATier() {
    Business b = business("sso-req");
    UUID managerId = staff(b, "sso-req-manager@example.com", "MANAGER");
    staff(b, "sso-req-cashier@example.com", "CASHIER");
    connect(b, "sso-req", "");
    Answer before = passwordLogin("sso-req-cashier@example.com");
    assertThat(before.data().containsKey("accessToken"), is(true));

    connect(b, "sso-req", "\"CASHIER\"");
    Answer refused = passwordLogin("sso-req-cashier@example.com");
    assertThat(refused.status(), is(403));
    assertThat(refused.code(), is("SSO_REQUIRED"));
    assertThat(refused.body().toString(), containsString("slug=sso-req"));
    Answer renewal = refresh(before);
    assertThat("a password session is not renewed", renewal.code(), is("SSO_REQUIRED"));

    assertThat(passwordLogin("sso-req-owner@example.com").status(), is(200));
    assertThat(passwordLogin("sso-req-manager@example.com").status(), is(200));
    assertThat(
        signInAndRedeem("sso-req", Person.verified("c", "sso-req-cashier@example.com")).status(),
        is(200));

    // Switched off, the rule goes with it.
    Answer off =
        call(
            "PUT",
            "/auth/admin/sso",
            b.owner(),
            connection("sso-req", PROVIDER.issuer(), null, "\"CASHIER\"")
                .replace("\"enabled\":true", "\"enabled\":false"));
    assertThat(off.status(), is(200));
    assertThat(passwordLogin("sso-req-cashier@example.com").status(), is(200));
    assertThat(
        call(
                "POST",
                "/auth/sso/start",
                Caller.NOBODY,
                "{\"slug\":\"sso-req\",\"codeChallenge\":\""
                    + Pkce.challenge(Pkce.newVerifier())
                    + "\"}")
            .code(),
        is("SSO_NOT_FOUND"));
    assertThat(managerId, not(b.ownerId()));
  }

  // ── the second factor ──────────────────────────────────────────────────────

  @Test
  @DisplayName("A provider that asked for two factors is enough; one that did not is not")
  void secondFactors() {
    Business b = business("sso-mfa");
    UUID cashierId = staff(b, "sso-mfa-cashier@example.com", "CASHIER");
    connect(b, "sso-mfa", "");
    Answer rule =
        call("PUT", "/auth/admin/mfa-policy", b.owner(), "{\"requiredTiers\":[\"CASHIER\"]}");
    assertThat(rule.status(), is(200));

    Answer proved =
        signInAndRedeem(
            "sso-mfa", new Person("m", "sso-mfa-cashier@example.com", true, List.of("pwd", "mfa")));
    assertThat(proved.body().toString(), proved.status(), is(200));
    assertThat(amr(proved), contains("sso", "mfa"));
    assertThat(refresh(proved).status(), is(200));

    // The provider says nothing of a second factor: the business's rule has one set up here.
    Answer owed = signInAndRedeem("sso-mfa", Person.verified("m", "sso-mfa-cashier@example.com"));
    assertThat(owed.data().getBoolean("mfaEnrolmentRequired"), is(true));
    DecodedJWT limited = JWT.decode(owed.data().getString("accessToken"));
    assertThat(limited.getClaim("amr").asList(String.class), contains("sso"));

    Caller enrolling = new Caller(cashierId, "", null, "mfa-enrol", "sso");
    String secret = call("POST", "/auth/mfa/totp", enrolling, null).data().getString("secret");
    Answer done =
        call(
            "POST",
            "/auth/mfa/totp/confirm",
            enrolling,
            "{\"code\":\""
                + Totp.code(Totp.fromBase32(secret), Totp.stepAt(Instant.now()) - 1)
                + "\"}");
    assertThat(done.body().toString(), done.status(), is(200));
    DecodedJWT real = JWT.decode(done.data().getJsonObject("tokens").getString("accessToken"));
    assertThat(
        "the session records how it began",
        real.getClaim("amr").asList(String.class),
        contains("sso", "otp"));

    // With a factor held, the next sign-in owes it, and ends as the provider's and the app's.
    Answer waiting =
        signInAndRedeem("sso-mfa", Person.verified("m", "sso-mfa-cashier@example.com"));
    assertThat(waiting.data().getBoolean("mfaRequired"), is(true));
    Answer answered =
        call(
            "POST",
            "/auth/mfa/login",
            Caller.NOBODY,
            "{\"mfaToken\":\""
                + waiting.data().getString("mfaToken")
                + "\",\"method\":\"TOTP\",\"code\":\""
                + Totp.code(Totp.fromBase32(secret), Totp.stepAt(Instant.now()))
                + "\"}");
    assertThat(answered.body().toString(), answered.status(), is(200));
    assertThat(amr(answered), contains("sso", "otp"));
  }

  // ── what ends a link, or a session ─────────────────────────────────────────

  @Test
  @DisplayName("After a working day the provider is asked again")
  void theProviderIsAskedAgain() throws Exception {
    Business b = business("sso-age");
    UUID cashierId = staff(b, "sso-age-cashier@example.com", "CASHIER");
    connect(b, "sso-age", "");
    Answer in = signInAndRedeem("sso-age", Person.verified("a", "sso-age-cashier@example.com"));
    assertThat(refresh(in).status(), is(200));

    Answer second = signInAndRedeem("sso-age", Person.verified("a", "sso-age-cashier@example.com"));
    sql(
        "UPDATE refresh_tokens SET authenticated_at = ? WHERE user_id = ? AND revoked = false",
        java.sql.Timestamp.from(Instant.now().minusSeconds(13 * 3600)),
        cashierId);
    Answer stale = refresh(second);
    assertThat(stale.status(), is(401));
    assertThat(stale.code(), is("SSO_REAUTH_REQUIRED"));

    // A password session is not held to the provider's day.
    Answer password = passwordLogin("sso-age-owner@example.com");
    sql(
        "UPDATE refresh_tokens SET authenticated_at = ? WHERE user_id = ? AND revoked = false",
        java.sql.Timestamp.from(Instant.now().minusSeconds(13 * 3600)),
        b.ownerId());
    assertThat(refresh(password).status(), is(200));
  }

  @Test
  @DisplayName("Taken off the staff, the link goes; a new provider unlinks the old one's people")
  void whatEndsALink() {
    Business b = business("sso-end");
    UUID cashierId = staff(b, "sso-end-cashier@example.com", "CASHIER");
    connect(b, "sso-end", "");
    assertThat(
        signInAndRedeem("sso-end", Person.verified("e", "sso-end-cashier@example.com")).status(),
        is(200));

    users.unbindStaffOnce(Ids.newId(), CONSUMER, cashierId, b.tenant(), "CASHIER", b.store());
    assertThat(
        signIn("sso-end", Person.verified("e", "sso-end-cashier@example.com"), null)
            .get("sso_error"),
        is("SSO_NO_ACCOUNT"));
    assertThat(
        call("GET", "/auth/admin/sso/identities", b.owner(), null)
            .data()
            .getJsonArray("items")
            .size(),
        is(0));

    UUID managerId = staff(b, "sso-end-manager@example.com", "MANAGER");
    assertThat(
        signInAndRedeem("sso-end", Person.verified("m", "sso-end-manager@example.com")).status(),
        is(200));
    Answer moved =
        call(
            "PUT",
            "/auth/admin/sso",
            b.owner(),
            connection("sso-end", "http://localhost:1/another", null, ""));
    assertThat(moved.status(), is(200));
    assertThat(
        call("GET", "/auth/admin/sso/identities", b.owner(), null)
            .data()
            .getJsonArray("items")
            .size(),
        is(0));
    assertThat(managerId, not(cashierId));

    assertThat(call("DELETE", "/auth/admin/sso", b.owner(), null).status(), is(200));
    assertThat(call("GET", "/auth/admin/sso", b.owner(), null).code(), is("SSO_NOT_CONFIGURED"));
    assertThat(call("DELETE", "/auth/admin/sso", b.owner(), null).status(), is(404));
  }

  @Test
  @DisplayName("A suspended business signs nobody in through its provider either")
  void aSuspendedBusiness() throws Exception {
    Business b = business("sso-susp");
    staff(b, "sso-susp-cashier@example.com", "CASHIER");
    connect(b, "sso-susp", "");
    Started s = start("sso-susp");
    Map<String, String> back =
        PROVIDER.authorize(
            s.authorizationUrl(), Person.verified("z", "sso-susp-cashier@example.com"));
    sql(
        "INSERT INTO tenant_status (tenant_id, status, status_changed_at) VALUES (?, 'INACTIVE',"
            + " now())",
        b.tenant());
    assertThat(fragment(callback(back)).get("sso_error"), is("TENANT_INACTIVE"));
    assertThat(
        call(
                "POST",
                "/auth/sso/start",
                Caller.NOBODY,
                "{\"slug\":\"sso-susp\",\"codeChallenge\":\""
                    + Pkce.challenge(Pkce.newVerifier())
                    + "\"}")
            .code(),
        is("TENANT_INACTIVE"));
  }
}
