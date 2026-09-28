package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.auth0.jwt.JWT;
import com.storeql.iam.mfa.Totp;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Forgotten password (intent/password-reset.md): {@code GET /auth/password-policy}, {@code POST
 * /auth/password/forgot} and {@code POST /auth/password/reset} against a real Postgres
 * (Testcontainers) and a stub tenant-svc (for the business names a staff link carries). Every
 * Acceptance line of the intent page that is iam-svc's own.
 */
@HelidonTest
class PasswordResetIT {

  private static final PostgresSupport PG;
  private static final JsonStub TENANT_SVC;
  private static final java.util.Map<String, String> BUSINESS_NAMES = new ConcurrentHashMap<>();
  private static final String CONSUMER = "password-reset-it";
  private static final String STRONG = "a phrase of several words works well";

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
    TENANT_SVC = JsonStub.start("tenant-svc");
    TENANT_SVC.on(
        "GET",
        "/admin/tenant",
        call -> {
          String name = BUSINESS_NAMES.get(call.tenantId());
          return name == null
              ? new JsonStub.Answer(
                  404, "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"no such tenant\"}}")
              : JsonStub.Answer.ok("{\"name\":\"" + name + "\"}");
        });
  }

  @Inject WebTarget target;
  @Inject UserRepository users;

  @AfterAll
  static void stopDb() {
    PG.stop();
    TENANT_SVC.close();
  }

  // ── HTTP helpers ────────────────────────────────────────────────────────────

  /** A response, read once. */
  private record Answer(int status, JsonObject body) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private Answer post(String path, String json) {
    Response r = target.path(path).request().post(Entity.entity(json, MediaType.APPLICATION_JSON));
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject());
  }

  private Answer forgot(String email, String language) {
    String body =
        language == null
            ? "{\"email\":\"" + email + "\"}"
            : "{\"email\":\"" + email + "\",\"language\":\"" + language + "\"}";
    return post("/auth/password/forgot", body);
  }

  private Answer resetWith(String token, String newPassword) {
    return post(
        "/auth/password/reset",
        "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
  }

  private Answer policyAs(UUID tenantId, String roles) {
    Invocation.Builder b = target.path("/auth/password-policy").request();
    if (tenantId != null) b = b.header("X-Tenant-Id", tenantId.toString());
    if (roles != null) b = b.header("X-Roles", roles);
    Response r = b.get();
    String text = r.readEntity(String.class);
    return new Answer(r.getStatus(), Json.createReader(new StringReader(text)).readObject());
  }

  private Answer login(String email, String password) {
    return post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private Answer refresh(String refreshToken) {
    return post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** Registers a fresh shopper login (tenant_id NULL) and returns its id. */
  private UUID register(String email, String password) {
    Answer a =
        post("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
  }

  /**
   * As {@link #register}, then bound to a tenant as staff at a store — the login leaves the global
   * scope, freeing the email for a fresh {@link #register} of the same address.
   */
  private UUID staff(String email, String password, UUID tenantId, String tier, UUID storeId) {
    UUID id = register(email, password);
    assertThat(users.bindStaffOnce(Ids.newId(), CONSUMER, id, tenantId, tier, storeId), is(true));
    return id;
  }

  private void nameBusiness(UUID tenantId, String name) {
    BUSINESS_NAMES.put(tenantId.toString(), name);
  }

  private void disable(UUID userId) throws Exception {
    try (Connection c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET status = 'DISABLED' WHERE id = ?")) {
      ps.setObject(1, userId);
      ps.executeUpdate();
    }
  }

  private void makePlatformAdmin(UUID userId) throws Exception {
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                    + " SELECT ?, ?, id, NULL FROM roles WHERE name = 'PLATFORM_ADMIN'")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, userId);
      ps.executeUpdate();
    }
  }

  private void suspendTenant(UUID tenantId) throws Exception {
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO tenant_status (tenant_id, status, status_changed_at)"
                    + " VALUES (?, 'INACTIVE', now())")) {
      ps.setObject(1, tenantId);
      ps.executeUpdate();
    }
  }

  private static Connection iamConnection() throws Exception {
    Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  private static String tokenFromLink(String link) {
    return link.substring(link.lastIndexOf('/') + 1);
  }

  private JsonObject latestEventForEmail(String email) throws Exception {
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = 'PasswordResetRequested'"
                    + " AND payload LIKE ? ORDER BY created_at DESC LIMIT 1")) {
      ps.setString(1, "%\"email\":\"" + email + "\"%");
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return Json.createReader(new StringReader(rs.getString(1))).readObject();
      }
    }
  }

  private int outboxCountForEmail(String email) throws Exception {
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM outbox WHERE event_type = 'PasswordResetRequested'"
                    + " AND payload LIKE ?")) {
      ps.setString(1, "%\"email\":\"" + email + "\"%");
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private String outboxTenantColumnForEmail(String email) throws Exception {
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement(
                "SELECT tenant_id FROM outbox WHERE event_type = 'PasswordResetRequested'"
                    + " AND payload LIKE ? ORDER BY created_at DESC LIMIT 1")) {
      ps.setString(1, "%\"email\":\"" + email + "\"%");
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private int tokenRowCount(UUID userId) throws Exception {
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?")) {
      ps.setObject(1, userId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private String passwordHashOf(UUID userId) throws Exception {
    try (Connection c = iamConnection();
        var ps = c.prepareStatement("SELECT password_hash FROM users WHERE id = ?")) {
      ps.setObject(1, userId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static JsonObject entryOfKind(JsonArray entries, String kind) {
    for (JsonObject o : entries.getValuesAs(JsonObject.class)) {
      if (kind.equals(o.getString("kind"))) {
        return o;
      }
    }
    throw new AssertionError("no entry of kind " + kind + " in " + entries);
  }

  private static JsonObject entryOfBusiness(JsonArray entries, String businessName) {
    for (JsonObject o : entries.getValuesAs(JsonObject.class)) {
      if (businessName.equals(o.getString("businessName", null))) {
        return o;
      }
    }
    throw new AssertionError("no entry named " + businessName + " in " + entries);
  }

  // ── the published policy ────────────────────────────────────────────────

  @Test
  @DisplayName("The password policy is published, and the same for everyone")
  void thePolicyIsPublishedTheSameForEveryone() {
    Answer nobody = policyAs(null, null);
    assertThat(nobody.body().toString(), nobody.status(), is(200));
    assertThat(nobody.data().getInt("minLength"), is(15));
    assertThat(nobody.data().getInt("maxLength"), is(128));
    assertThat(nobody.data().getBoolean("mustNotContainLogin"), is(true));
    assertThat(nobody.data().containsKey("breachScreened"), is(true));

    // Another business's owner, and a rival's cashier, read the exact same answer — this route
    // knows nothing about a caller's identity.
    Answer owner = policyAs(Ids.newId(), "OWNER");
    Answer cashier = policyAs(Ids.newId(), "CASHIER");
    assertThat(owner.data().toString(), is(nobody.data().toString()));
    assertThat(cashier.data().toString(), is(nobody.data().toString()));
  }

  // ── forgot: same answer, known or not ───────────────────────────────────

  @Test
  @DisplayName(
      "Forgot answers 202 the same for an unknown address, a known active one, and a suspended one")
  void forgotAnswersTheSameWhetherOrNotTheAddressIsKnown() throws Exception {
    String unknown = "nobody-" + Ids.newId() + "@example.com";
    Answer a1 = forgot(unknown, null);
    assertThat(a1.status(), is(202));
    assertThat(a1.data().getBoolean("accepted"), is(true));
    assertThat(outboxCountForEmail(unknown), is(0));

    String known = "known-" + Ids.newId() + "@example.com";
    UUID id = register(known, STRONG);
    Answer a2 = forgot(known, null);
    assertThat(a2.status(), is(202));
    assertThat(a2.data().getBoolean("accepted"), is(true));
    assertThat(outboxCountForEmail(known), is(1));
    assertThat(tokenRowCount(id), is(1));

    String suspended = "suspended-" + Ids.newId() + "@example.com";
    UUID suspendedId = register(suspended, STRONG);
    disable(suspendedId);
    Answer a3 = forgot(suspended, null);
    assertThat(a3.status(), is(202));
    assertThat(a3.data().getBoolean("accepted"), is(true));
    assertThat(outboxCountForEmail(suspended), is(0));
    assertThat(tokenRowCount(suspendedId), is(0));
  }

  @Test
  @DisplayName("Missing, blank, malformed or too-long an email is refused before any work is done")
  void forgotRefusesABadEmail() {
    for (String json :
        new String[] {
          "{}",
          "{\"email\":\"\"}",
          "{\"email\":\"notanemail\"}",
          "{\"email\":\"" + "a".repeat(250) + "@example.com\"}"
        }) {
      Answer a = post("/auth/password/forgot", json);
      assertThat(json, a.status(), is(400));
      assertThat(json, a.code(), is("VALIDATION_FAILED"));
    }
  }

  // ── one address, several businesses ─────────────────────────────────────

  @Test
  @DisplayName(
      "One address holding a shopper login and staff at two businesses gets one email naming"
          + " each; resetting one leaves the others' passwords unchanged")
  void oneAddressAcrossTwoBusinessesIsResetIndependently() throws Exception {
    String email = "multi-" + Ids.newId() + "@example.com";
    UUID tenantA = Ids.newId();
    UUID tenantB = Ids.newId();
    nameBusiness(tenantA, "Corner Stores Ltd");
    nameBusiness(tenantB, "Big Retail plc");

    UUID staffA = staff(email, STRONG, tenantA, "MANAGER", Ids.newId());
    UUID staffB = staff(email, STRONG, tenantB, "MANAGER", Ids.newId());
    UUID shopper = register(email, STRONG);

    Answer f = forgot(email, null);
    assertThat(f.status(), is(202));

    JsonObject event = latestEventForEmail(email);
    assertThat(event, not(nullValue()));
    JsonArray entries = event.getJsonArray("entries");
    assertThat(entries.size(), is(3));

    JsonObject shopperEntry = entryOfKind(entries, "SHOPPER");
    assertThat(shopperEntry.containsKey("businessName"), is(false));
    assertThat(shopperEntry.getString("userId"), is(shopper.toString()));
    JsonObject entryA = entryOfBusiness(entries, "Corner Stores Ltd");
    assertThat(entryA.getString("kind"), is("STAFF"));
    assertThat(entryA.getString("userId"), is(staffA.toString()));
    JsonObject entryB = entryOfBusiness(entries, "Big Retail plc");
    assertThat(entryB.getString("kind"), is("STAFF"));
    assertThat(entryB.getString("userId"), is(staffB.toString()));

    String hashBefore = passwordHashOf(staffB);
    String shopperHashBefore = passwordHashOf(shopper);

    // Spend business A's own link with a new password.
    String tokenA = tokenFromLink(entryA.getString("link"));
    Answer reset = resetWith(tokenA, "a brand new phrase for business a only");
    assertThat(reset.body().toString(), reset.status(), is(200));
    assertThat(reset.data().getBoolean("reset"), is(true));

    // Business A signs in with the new password only.
    assertThat(login(email, "a brand new phrase for business a only").status(), is(200));

    // Business B and the shopper: untouched, down to the stored hash.
    assertThat(passwordHashOf(staffB), is(hashBefore));
    assertThat(passwordHashOf(shopper), is(shopperHashBefore));
  }

  @Test
  @DisplayName("The language sent is carried on the event, read strictly")
  void languageIsCarriedOnTheEventReadStrictly() throws Exception {
    String withLang = "lang-pl-" + Ids.newId() + "@example.com";
    register(withLang, STRONG);
    forgot(withLang, "pl");
    JsonObject event = latestEventForEmail(withLang);
    assertThat(event.getString("language"), is("pl"));

    String withBadLang = "lang-bad-" + Ids.newId() + "@example.com";
    register(withBadLang, STRONG);
    forgot(withBadLang, "not-a-language-code");
    JsonObject event2 = latestEventForEmail(withBadLang);
    assertThat(event2.isNull("language"), is(true));

    String withNoLang = "lang-none-" + Ids.newId() + "@example.com";
    register(withNoLang, STRONG);
    forgot(withNoLang, null);
    JsonObject event3 = latestEventForEmail(withNoLang);
    assertThat(event3.isNull("language"), is(true));
  }

  // ── the platform administrator and single sign-on ───────────────────────

  @Test
  @DisplayName("The platform administrator gets no entry and no event")
  void thePlatformAdministratorGetsNoEntry() throws Exception {
    String email = "admin-" + Ids.newId() + "@example.com";
    UUID id = register(email, STRONG);
    makePlatformAdmin(id);
    Answer f = forgot(email, null);
    assertThat(f.status(), is(202));
    assertThat(outboxCountForEmail(email), is(0));
    assertThat(tokenRowCount(id), is(0));
  }

  @Test
  @DisplayName("Staff of a business requiring single sign-on are named with no link")
  void ssoRequiredStaffGetNoLink() throws Exception {
    String ownerEmail = "sso-owner-" + Ids.newId() + "@example.com";
    UUID tenantId = Ids.newId();
    UUID ownerId = register(ownerEmail, STRONG);
    assertThat(users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenantId, "OWNER"), is(true));
    nameBusiness(tenantId, "Provider Signs In Ltd");

    String staffEmail = "sso-staff-" + Ids.newId() + "@example.com";
    UUID staffId = staff(staffEmail, STRONG, tenantId, "MANAGER", Ids.newId());
    connectSso(tenantId, ownerId, "MANAGER");

    Answer f = forgot(staffEmail, null);
    assertThat(f.status(), is(202));
    JsonObject event = latestEventForEmail(staffEmail);
    assertThat(event, not(nullValue()));
    JsonArray entries = event.getJsonArray("entries");
    assertThat(entries.size(), is(1));
    JsonObject entry = entries.getJsonObject(0);
    assertThat(entry.getString("kind"), is("STAFF_SSO"));
    assertThat(entry.getString("businessName"), is("Provider Signs In Ltd"));
    assertThat(entry.getString("userId"), is(staffId.toString()));
    assertThat(entry.containsKey("link"), is(false));
    // No token minted for this login: it has no link to spend.
    assertThat(tokenRowCount(staffId), is(0));
  }

  @Test
  @DisplayName(
      "A staff business name that cannot be read is sent as businessName: null, never omitted")
  void anUnreadableBusinessNameIsSentAsExplicitNull() throws Exception {
    String email = "unnamed-biz-" + Ids.newId() + "@example.com";
    // Deliberately never registered with nameBusiness(...): the stub answers 404 for it.
    UUID tenantId = Ids.newId();
    UUID staffId = staff(email, STRONG, tenantId, "MANAGER", Ids.newId());

    forgot(email, null);
    JsonObject entry = entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "STAFF");
    assertThat(entry.containsKey("businessName"), is(true));
    assertThat(entry.isNull("businessName"), is(true));
    assertThat(entry.getString("userId"), is(staffId.toString()));
  }

  private void connectSso(UUID tenantId, UUID ownerId, String tier) {
    Response r =
        target
            .path("/auth/admin/sso")
            .request()
            .header("X-User-Id", ownerId.toString())
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"slug\":\"biz-"
                        + tenantId.toString().substring(0, 8)
                        + "\",\"issuer\":\"https://idp.example.com\",\"clientId\":\"client\","
                        + "\"clientSecret\":\"secret\",\"enabled\":true,\"requiredTiers\":[\""
                        + tier
                        + "\"]}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
  }

  @Test
  @DisplayName(
      "A suspended business's staff get no entry; a link minted before the suspension is refused"
          + " after — eligibility is re-checked at reset time, not only when the link was minted")
  void aSuspendedBusinessGetsNoEntryAndEarlierLinksAreRefused() throws Exception {
    String email = "biz-suspended-" + Ids.newId() + "@example.com";
    UUID tenantId = Ids.newId();
    staff(email, STRONG, tenantId, "MANAGER", Ids.newId());

    forgot(email, null);
    String token =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "STAFF")
                .getString("link"));

    suspendTenant(tenantId);

    // No new entry once the business is suspended: still the one event from before.
    Answer f = forgot(email, null);
    assertThat(f.status(), is(202));
    assertThat(outboxCountForEmail(email), is(1));

    // The link minted before the suspension no longer works.
    Answer reset = resetWith(token, "a phrase that would otherwise be accepted fine");
    assertThat(reset.status(), is(400));
    assertThat(reset.code(), is("PASSWORD_RESET_TOKEN_INVALID"));
  }

  // ── a link resets once ───────────────────────────────────────────────────

  @Test
  @DisplayName("A link resets once: the new password signs in, the old does not, sessions end")
  void aLinkResetsOnceAndEndsEverySession() throws Exception {
    String email = "once-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    // Hold a refresh token from before the reset.
    Answer signedIn = login(email, STRONG);
    assertThat(signedIn.body().toString(), signedIn.status(), is(200));
    String oldRefresh = signedIn.data().getString("refreshToken");

    forgot(email, null);
    String token =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "SHOPPER")
                .getString("link"));

    Answer reset = resetWith(token, "a completely different strong phrase");
    assertThat(reset.body().toString(), reset.status(), is(200));

    assertThat(login(email, STRONG).status(), is(401));
    assertThat(login(email, "a completely different strong phrase").status(), is(200));
    // Every earlier session ended.
    assertThat(refresh(oldRefresh).status(), is(401));

    // The same token cannot be spent again.
    Answer again = resetWith(token, "yet another different strong phrase");
    assertThat(again.status(), is(400));
    assertThat(again.code(), is("PASSWORD_RESET_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("After a reset, a login with a second factor still gets mfaRequired")
  void aSecondFactorIsStillAskedAfterReset() throws Exception {
    String email = "mfa-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    // Enrol an authenticator app the same way MfaIT does.
    Answer begun =
        readAnswer(
            target
                .path("/auth/mfa/totp")
                .request()
                .header("X-User-Id", meIdOf(email))
                .post(Entity.entity("{}", MediaType.APPLICATION_JSON)));
    assertThat(begun.body().toString(), begun.status(), is(200));
    String secret = begun.data().getString("secret");
    String firstCode = Totp.code(Totp.fromBase32(secret), Totp.stepAt(Instant.now()) - 1);
    Answer confirmed =
        readAnswer(
            target
                .path("/auth/mfa/totp/confirm")
                .request()
                .header("X-User-Id", meIdOf(email))
                .post(
                    Entity.entity("{\"code\":\"" + firstCode + "\"}", MediaType.APPLICATION_JSON)));
    assertThat(confirmed.body().toString(), confirmed.status(), is(200));

    forgot(email, null);
    String token =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "SHOPPER")
                .getString("link"));
    Answer reset = resetWith(token, "another quite different strong phrase");
    assertThat(reset.body().toString(), reset.status(), is(200));

    Answer signIn = login(email, "another quite different strong phrase");
    assertThat(signIn.status(), is(200));
    assertThat(signIn.data().getBoolean("mfaRequired"), is(true));
    assertThat(signIn.data().containsKey("accessToken"), is(false));
  }

  private String meIdOf(String email) {
    Answer a = login(email, STRONG);
    if (a.status() != 200 || !a.data().containsKey("accessToken")) {
      throw new AssertionError("could not sign in as " + email + ": " + a.body());
    }
    return JWT.decode(a.data().getString("accessToken")).getSubject();
  }

  private Answer readAnswer(Response r) {
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject());
  }

  // ── used, expired, replaced and made-up tokens ──────────────────────────

  @Test
  @DisplayName("A made-up token is refused the same as a real one that no longer works")
  void aMadeUpTokenIsRefused() {
    Answer a = resetWith("not-a-real-token-at-all", STRONG);
    assertThat(a.status(), is(400));
    assertThat(a.code(), is("PASSWORD_RESET_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("An expired token is refused, and never spent")
  void anExpiredTokenIsRefused() throws Exception {
    String email = "expired-" + Ids.newId() + "@example.com";
    UUID id = register(email, STRONG);
    forgot(email, null);
    String token =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "SHOPPER")
                .getString("link"));
    try (Connection c = iamConnection();
        var ps =
            c.prepareStatement(
                "UPDATE password_reset_tokens SET expires_at = now() - interval '1 minute'"
                    + " WHERE user_id = ?")) {
      ps.setObject(1, id);
      assertThat(ps.executeUpdate(), is(1));
    }
    Answer a = resetWith(token, STRONG);
    assertThat(a.status(), is(400));
    assertThat(a.code(), is("PASSWORD_RESET_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("A newer link replaces an older one: the older is refused, the newer still works")
  void aReplacedTokenIsRefusedTheNewerWorks() throws Exception {
    String email = "replaced-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    forgot(email, null);
    String firstToken =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "SHOPPER")
                .getString("link"));
    forgot(email, null);
    String secondToken =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "SHOPPER")
                .getString("link"));
    assertThat(firstToken, not(is(secondToken)));

    Answer stale = resetWith(firstToken, STRONG);
    assertThat(stale.status(), is(400));
    assertThat(stale.code(), is("PASSWORD_RESET_TOKEN_INVALID"));

    Answer fresh = resetWith(secondToken, "the newer link still works fine");
    assertThat(fresh.body().toString(), fresh.status(), is(200));
  }

  @Test
  @DisplayName("A password the policy refuses does not spend the token; a good one after does")
  void aPolicyRefusalLeavesTheTokenUsable() throws Exception {
    String email = "policy-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    forgot(email, null);
    String token =
        tokenFromLink(
            entryOfKind(latestEventForEmail(email).getJsonArray("entries"), "SHOPPER")
                .getString("link"));

    Answer tooShort = resetWith(token, "short");
    assertThat(tooShort.status(), is(400));
    assertThat(tooShort.code(), is("PASSWORD_TOO_SHORT"));

    // The very same token still works with a password the policy accepts.
    Answer ok = resetWith(token, "a perfectly acceptable long phrase");
    assertThat(ok.body().toString(), ok.status(), is(200));
  }

  // ── the throttle ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("More than the allowed requests an hour send nothing more, and still answer 202")
  void moreThanAllowedRequestsSendNothingMore() throws Exception {
    String email = "throttled-" + Ids.newId() + "@example.com";
    UUID id = register(email, STRONG);
    for (int i = 0; i < 3; i++) {
      Answer a = forgot(email, null);
      assertThat(a.status(), is(202));
    }
    int rowsAfterThree = tokenRowCount(id);
    int outboxAfterThree = outboxCountForEmail(email);
    assertThat(rowsAfterThree, is(3)); // each request replaces, none removed yet

    Answer fourth = forgot(email, null);
    assertThat(fourth.status(), is(202));
    assertThat(fourth.data().getBoolean("accepted"), is(true));
    assertThat("no fifth-vs-fourth token minted", tokenRowCount(id), is(rowsAfterThree));
    assertThat("no additional event published", outboxCountForEmail(email), is(outboxAfterThree));
  }

  // ── the record belongs to no business (tenant isolation) ──────────────

  @Test
  @DisplayName(
      "The reset's record belongs to no business: the outbox row names no tenant, and no route"
          + " exposes it to another business's staff of any role")
  void theRecordBelongsToNoBusiness() throws Exception {
    String email = "noone-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    forgot(email, null);
    assertThat(outboxTenantColumnForEmail(email), is(nullValue()));

    // Another business's staff, of every role, asking the very same public routes: no special
    // treatment, no data back — these routes read no identity header at all.
    UUID rival = Ids.newId();
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Invocation.Builder b =
          target
              .path("/auth/password/forgot")
              .request()
              .header("X-Tenant-Id", rival.toString())
              .header("X-Roles", role);
      Response r =
          b.post(Entity.entity("{\"email\":\"" + email + "\"}", MediaType.APPLICATION_JSON));
      String body = r.readEntity(String.class);
      assertThat(role + ": " + body, r.getStatus(), is(202));
      assertThat(body, not(emptyString()));
    }
  }
}
