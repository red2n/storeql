package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.auth0.jwt.JWT;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.service.AuthService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Account security (flow catalogue 30 Sep 2026), against a real Postgres: the "your password was
 * changed" event on a signed-in change and on a reset, sign out everywhere, and the security-events
 * read with its tenant isolation. Every test makes its own businesses and logins (unique ids and
 * addresses) and counts only rows it made.
 */
@HelidonTest
class AccountSecurityIT {

  private static final PostgresSupport PG;
  private static final JsonStub TENANT_SVC;
  private static final java.util.Map<String, String> BUSINESS_NAMES = new ConcurrentHashMap<>();
  private static final String CONSUMER = "account-security-it";
  private static final String STRONG = "a phrase of several words works well";
  private static final String NEWER = "another phrase of many words works";

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
  @Inject AuthService auth;

  @AfterAll
  static void stopDb() {
    PG.stop();
    TENANT_SVC.close();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body, String text) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private static Answer read(Response r) {
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject(),
        text);
  }

  private Answer post(String path, String json) {
    return read(target.path(path).request().post(Entity.entity(json, MediaType.APPLICATION_JSON)));
  }

  private Answer login(String email, String password) {
    return post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private Answer refresh(String refreshToken) {
    return post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
  }

  private Answer changePassword(UUID userId, String current, String next) {
    return changePassword(userId, current, next, null);
  }

  private Answer changePassword(UUID userId, String current, String next, String language) {
    return read(
        target
            .path("/auth/change-password")
            .request()
            .header("X-User-Id", userId.toString())
            .put(
                Entity.entity(
                    "{\"currentPassword\":\""
                        + current
                        + "\",\"newPassword\":\""
                        + next
                        + "\""
                        + (language == null ? "" : ",\"language\":\"" + language + "\"")
                        + "}",
                    MediaType.APPLICATION_JSON)));
  }

  private Answer revokeAll(UUID userId) {
    Invocation.Builder b = target.path("/auth/sessions/revoke-all").request();
    if (userId != null) b = b.header("X-User-Id", userId.toString());
    return read(b.post(Entity.json("")));
  }

  private Answer events(UUID tenant, String roles, String stores, String query) {
    WebTarget t = target.path("/auth/admin/security-events");
    if (query != null) {
      for (String pair : query.split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv[1]);
      }
    }
    Invocation.Builder b = t.request();
    if (tenant != null) b = b.header("X-Tenant-Id", tenant.toString());
    if (roles != null) b = b.header("X-Roles", roles);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    b = b.header("X-User-Id", Ids.newId().toString());
    return read(b.get());
  }

  private UUID register(String email, String password) {
    Answer a =
        post("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    assertThat(a.text(), a.status(), is(201));
    return Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
  }

  private UUID staff(String email, UUID tenantId, String tier) {
    UUID id = Ids.parse(auth.provisionStaff(tenantId, email, STRONG).userId());
    assertThat(
        users.bindStaffOnce(Ids.newId(), CONSUMER, id, tenantId, tier, Ids.newId()), is(true));
    return id;
  }

  private static Connection iam() throws Exception {
    Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  private void makePlatformAdmin(UUID userId) throws Exception {
    try (Connection c = iam();
        var ps =
            c.prepareStatement(
                "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                    + " SELECT ?, ?, id, NULL FROM roles WHERE name = 'PLATFORM_ADMIN'")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, userId);
      ps.executeUpdate();
    }
  }

  private void audit(UUID tenant, UUID user, String action, String detail) throws Exception {
    try (Connection c = iam();
        var ps =
            c.prepareStatement(
                "INSERT INTO audit_log (id, tenant_id, user_id, action, detail)"
                    + " VALUES (?, ?, ?, ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenant);
      ps.setObject(3, user);
      ps.setString(4, action);
      ps.setString(5, detail);
      ps.executeUpdate();
    }
  }

  /** The PasswordChanged events written for this login, oldest first. */
  private java.util.List<JsonObject> changedEvents(UUID userId) throws Exception {
    java.util.List<JsonObject> out = new java.util.ArrayList<>();
    try (Connection c = iam();
        var ps =
            c.prepareStatement(
                "SELECT payload, topic, tenant_id FROM outbox WHERE event_type = 'PasswordChanged'"
                    + " AND payload LIKE ? ORDER BY id")) {
      ps.setString(1, "%\"userId\":\"" + userId + "\"%");
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          assertThat(rs.getString("topic"), is("storeql.iam.password-changed"));
          assertThat("belongs to no business", rs.getString("tenant_id"), is(nullValue()));
          out.add(Json.createReader(new StringReader(rs.getString("payload"))).readObject());
        }
      }
    }
    return out;
  }

  private static Set<String> userIds(Answer a) {
    Set<String> ids = new HashSet<>();
    for (JsonObject o : a.data().getJsonArray("items").getValuesAs(JsonObject.class)) {
      if (!o.isNull("userId")) ids.add(o.getString("userId"));
    }
    return ids;
  }

  // ── PasswordChanged event ──────────────────────────────────────────────────

  @Test
  @DisplayName("A signed-in change announces PasswordChanged once, with no secret; a refusal none")
  void aSignedInChangeAnnouncesOnceAndARefusalAnnouncesNothing() throws Exception {
    String email = "changed-" + Ids.newId() + "@example.com";
    UUID id = register(email, STRONG);

    assertThat(changePassword(id, "not the current password", NEWER).status(), is(401));
    assertThat(changedEvents(id), hasSize(0));

    assertThat(changePassword(id, STRONG, NEWER).status(), is(200));
    var events = changedEvents(id);
    assertThat(events, hasSize(1));
    JsonObject e = events.get(0);
    assertThat(e.getString("eventType"), is("PasswordChanged"));
    assertThat(e.getString("email"), is(email));
    assertThat(e.getString("kind"), is("SHOPPER"));
    assertThat(e.getString("via"), is("CHANGE"));
    assertThat(e.containsKey("businessName"), is(false));
    assertThat(Ids.isV7(Ids.parse(e.getString("eventId"))), is(true));
    assertThat(e.getString("changedAt"), containsString("T"));
    String text = e.toString();
    assertThat(text, not(containsString(STRONG)));
    assertThat(text, not(containsString(NEWER)));
    assertThat(text.toLowerCase(), not(containsString("hash")));
    assertThat(text.toLowerCase(), not(containsString("http")));
  }

  @Test
  @DisplayName("A reset announces PasswordChanged (via RESET); a refused reset announces nothing")
  void aResetAnnouncesAndARefusedResetDoesNot() throws Exception {
    String email = "reset-notice-" + Ids.newId() + "@example.com";
    UUID id = register(email, STRONG);
    post("/auth/password/forgot", "{\"email\":\"" + email + "\"}");
    String link;
    try (Connection c = iam();
        var ps =
            c.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = 'PasswordResetRequested'"
                    + " AND payload LIKE ?")) {
      ps.setString(1, "%\"email\":\"" + email + "\"%");
      try (var rs = ps.executeQuery()) {
        rs.next();
        link =
            Json.createReader(new StringReader(rs.getString(1)))
                .readObject()
                .getJsonArray("entries")
                .getJsonObject(0)
                .getString("link");
      }
    }
    String token = link.substring(link.lastIndexOf('/') + 1);

    // A password the policy refuses, and a made-up token: nothing changed, nothing announced.
    assertThat(
        post("/auth/password/reset", "{\"token\":\"" + token + "\",\"newPassword\":\"short\"}")
            .status(),
        is(400));
    assertThat(
        post(
                "/auth/password/reset",
                "{\"token\":\"not-a-token\",\"newPassword\":\"" + NEWER + "\"}")
            .status(),
        is(400));
    assertThat(changedEvents(id), hasSize(0));

    assertThat(
        post(
                "/auth/password/reset",
                "{\"token\":\""
                    + token
                    + "\",\"newPassword\":\""
                    + NEWER
                    + "\",\"language\":\"ro\"}")
            .status(),
        is(200));
    var events = changedEvents(id);
    assertThat(events, hasSize(1));
    assertThat(events.get(0).getString("via"), is("RESET"));
    assertThat(events.get(0).getString("language"), is("ro"));
    assertThat(events.get(0).getString("kind"), is("SHOPPER"));
    assertThat(events.get(0).getString("email"), is(email));

    // The same link again is refused and announces nothing more.
    assertThat(
        post(
                "/auth/password/reset",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + STRONG + "\"}")
            .status(),
        is(400));
    assertThat(changedEvents(id), hasSize(1));
  }

  @Test
  @DisplayName("The language sent on a change or a reset rides on the event; bad or none is null")
  void theLanguageRidesOnTheEvent() throws Exception {
    UUID a = register("lang-a-" + Ids.newId() + "@example.com", STRONG);
    assertThat(changePassword(a, STRONG, NEWER, "pl").status(), is(200));
    assertThat(changedEvents(a).get(0).getString("language"), is("pl"));

    UUID b = register("lang-b-" + Ids.newId() + "@example.com", STRONG);
    assertThat(changePassword(b, STRONG, NEWER, "not-a-language").status(), is(200));
    assertThat(changedEvents(b).get(0).isNull("language"), is(true));

    UUID c = register("lang-c-" + Ids.newId() + "@example.com", STRONG);
    assertThat(changePassword(c, STRONG, NEWER).status(), is(200));
    assertThat(changedEvents(c).get(0).isNull("language"), is(true));
  }

  @Test
  @DisplayName("A staff change names the business; an unreadable name is an explicit null")
  void aStaffChangeNamesItsBusiness() throws Exception {
    UUID tenant = Ids.newId();
    BUSINESS_NAMES.put(tenant.toString(), "Corner Stores Ltd");
    UUID named = staff("named-" + Ids.newId() + "@example.com", tenant, "MANAGER");
    assertThat(changePassword(named, STRONG, NEWER).status(), is(200));
    JsonObject e = changedEvents(named).get(0);
    assertThat(e.getString("kind"), is("STAFF"));
    assertThat(e.getString("businessName"), is("Corner Stores Ltd"));

    UUID unnamed = staff("unnamed-" + Ids.newId() + "@example.com", Ids.newId(), "MANAGER");
    assertThat(changePassword(unnamed, STRONG, NEWER).status(), is(200));
    JsonObject u = changedEvents(unnamed).get(0);
    assertThat(u.getString("kind"), is("STAFF"));
    assertThat(u.containsKey("businessName"), is(true));
    assertThat(u.isNull("businessName"), is(true));
  }

  @Test
  @DisplayName("The platform administrator's change is never announced")
  void thePlatformAdministratorsChangeIsNotAnnounced() throws Exception {
    UUID id = register("padmin-" + Ids.newId() + "@example.com", STRONG);
    makePlatformAdmin(id);
    assertThat(changePassword(id, STRONG, NEWER).status(), is(200));
    assertThat(changedEvents(id), hasSize(0));
  }

  // ── sign out everywhere ─────────────────────────────────────────────────

  @Test
  @DisplayName("Sign out everywhere ends every session of the caller's login and only theirs")
  void signOutEverywhereEndsOnlyTheCallersSessions() {
    String mine = "everywhere-" + Ids.newId() + "@example.com";
    String theirs = "elsewhere-" + Ids.newId() + "@example.com";
    UUID me = register(mine, STRONG);
    register(theirs, STRONG);
    Answer first = login(mine, STRONG);
    Answer second = login(mine, STRONG);
    Answer other = login(theirs, STRONG);
    assertThat(first.status(), is(200));

    Answer done = revokeAll(me);
    assertThat(done.text(), done.status(), is(200));
    // The sign-up's session and the two sign-ins.
    assertThat(done.data().getInt("revoked"), is(3));
    assertThat(refresh(first.data().getString("refreshToken")).status(), is(401));
    assertThat(refresh(second.data().getString("refreshToken")).status(), is(401));
    // Someone else's session is untouched.
    assertThat(refresh(other.data().getString("refreshToken")).status(), is(200));
    // Nothing left to end.
    assertThat(revokeAll(me).data().getInt("revoked"), is(0));
    // No identity, no answer.
    assertThat(revokeAll(null).status(), is(401));
    // Signing in again works: only sessions ended, not the login.
    assertThat(login(mine, STRONG).status(), is(200));
  }

  @Test
  @DisplayName("Another business's owner cannot end our staff's sessions, even naming their id")
  void anotherBusinessCannotSignOurStaffOut() {
    UUID tenantA = Ids.newId();
    UUID tenantB = Ids.newId();
    String emailA = "ours-" + Ids.newId() + "@example.com";
    UUID ourStaff = staff(emailA, tenantA, "MANAGER");
    UUID theirOwner = staff("theirs-" + Ids.newId() + "@example.com", tenantB, "OWNER");
    Answer session = login(emailA, STRONG);
    assertThat(session.status(), is(200));

    // The route takes the caller from the token alone: another business's owner ends their own.
    Answer theirs =
        read(
            target
                .path("/auth/sessions/revoke-all")
                .request()
                .header("X-User-Id", theirOwner.toString())
                .header("X-Tenant-Id", tenantB.toString())
                .header("X-Roles", "OWNER")
                .post(Entity.json("{\"userId\":\"" + ourStaff + "\"}")));
    assertThat(theirs.status(), is(200));
    assertThat(refresh(session.data().getString("refreshToken")).status(), is(200));
  }

  // ── security events ───────────────────────────────────────────────────────

  @Test
  @DisplayName("An owner and a manager read their own logins' events and never another business's")
  void ownersAndManagersReadOnlyTheirOwnBusinessesEvents() throws Exception {
    UUID tenantA = Ids.newId();
    UUID tenantB = Ids.newId();
    String emailA = "sec-a-" + Ids.newId() + "@example.com";
    String emailB = "sec-b-" + Ids.newId() + "@example.com";
    UUID staffA = staff(emailA, tenantA, "MANAGER");
    UUID staffB = staff(emailB, tenantB, "MANAGER");
    assertThat(login(emailA, "wrong password entirely").status(), is(401));
    assertThat(login(emailB, "wrong password entirely").status(), is(401));
    // Recorded with no business, as the second-factor events are: found through the login.
    audit(null, staffA, "MFA_LOCKED", null);
    audit(null, staffB, "MFA_LOCKED", null);
    // A shopper's: no business at all.
    UUID shopper = register("sec-shop-" + Ids.newId() + "@example.com", STRONG);
    audit(null, shopper, "MFA_LOCKED", null);

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer a = events(tenantA, role, null, "limit=100");
      assertThat(role + a.text(), a.status(), is(200));
      assertThat(role, userIds(a), is(Set.of(staffA.toString())));
      assertThat(role, a.text(), not(containsString(emailB)));
      assertThat(role, a.text(), not(containsString(shopper.toString())));
      assertThat(role, a.text(), containsString("MFA_LOCKED"));
      assertThat(role, a.text(), containsString("LOGIN_FAILED"));

      // Naming the other business's login, or its events, finds nothing at all.
      Answer named = events(tenantA, role, null, "userId=" + staffB);
      assertThat(named.status(), is(200));
      assertThat(named.data().getJsonArray("items"), hasSize(0));
      Answer namedShopper = events(tenantA, role, null, "userId=" + shopper);
      assertThat(namedShopper.data().getJsonArray("items"), hasSize(0));
    }
    // And the other way round.
    Answer b = events(tenantB, "OWNER", null, "limit=100");
    assertThat(userIds(b), is(Set.of(staffB.toString())));
    assertThat(b.text(), not(containsString(emailA)));
  }

  @Test
  @DisplayName("Only an owner or manager reads, and a manager held to stores does not")
  void onlyOwnersAndUnrestrictedManagersRead() {
    UUID tenant = Ids.newId();
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER", "GOD", "owner"}) {
      Answer a = events(tenant, role, null, null);
      assertThat(role, a.status(), is(403));
      assertThat(role, a.code(), is("FORBIDDEN"));
    }
    assertThat(events(tenant, null, null, null).status(), is(403));
    Answer held = events(tenant, "MANAGER", Ids.newId().toString(), null);
    assertThat(held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    // An owner or manager of a business with no events reads an empty page, not an error.
    Answer empty = events(tenant, "OWNER", null, null);
    assertThat(empty.status(), is(200));
    assertThat(empty.data().getJsonArray("items"), hasSize(0));
    assertThat(
        empty.data().get("nextCursor") == null || empty.data().isNull("nextCursor"), is(true));
  }

  @Test
  @DisplayName("The platform administrator reads every business's events")
  void thePlatformAdministratorReadsAll() throws Exception {
    UUID tenantA = Ids.newId();
    UUID tenantB = Ids.newId();
    UUID a = staff("pa-a-" + Ids.newId() + "@example.com", tenantA, "MANAGER");
    UUID b = staff("pa-b-" + Ids.newId() + "@example.com", tenantB, "MANAGER");
    audit(null, a, "MFA_LOCKED", null);
    audit(null, b, "MFA_LOCKED", null);
    Answer ra = events(null, "PLATFORM_ADMIN", null, "type=MFA_LOCKED&userId=" + a);
    Answer rb = events(null, "PLATFORM_ADMIN", null, "type=MFA_LOCKED&userId=" + b);
    assertThat(ra.text(), userIds(ra), is(Set.of(a.toString())));
    assertThat(rb.text(), userIds(rb), is(Set.of(b.toString())));
  }

  @Test
  @DisplayName("Newest first, cursor-paged, filtered by type and period; detail only where safe")
  void pagingFiltersAndSafeDetail() throws Exception {
    UUID tenant = Ids.newId();
    String email = "paging-" + Ids.newId() + "@example.com";
    UUID staff = staff(email, tenant, "MANAGER");
    audit(tenant, staff, "MFA_LOGIN_FAILED", "TOTP");
    audit(tenant, staff, "LOGIN_FAILED", "someone-typed@example.com");
    audit(
        tenant, staff, "REFRESH_REUSE_DETECTED", "revoked token presented - all sessions revoked");

    Answer all = events(tenant, "OWNER", null, "userId=" + staff + "&limit=100");
    JsonArray items = all.data().getJsonArray("items");
    // Newest first: the ids (UUIDv7) only ever fall.
    String previous = null;
    for (JsonObject o : items.getValuesAs(JsonObject.class)) {
      if (previous != null) assertThat(o.getString("id").compareTo(previous) < 0, is(true));
      previous = o.getString("id");
    }
    assertThat(items.getJsonObject(0).getString("type"), is("REFRESH_REUSE_DETECTED"));
    // The typed address is never shown; a method or a reason is.
    assertThat(all.text(), not(containsString("someone-typed@example.com")));
    assertThat(all.text(), containsString("\"detail\":\"TOTP\""));

    // Pages of one, walked to the end, lose and repeat nothing.
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      Answer page =
          events(
              tenant,
              "MANAGER",
              null,
              "userId=" + staff + "&limit=1" + (cursor == null ? "" : "&after=" + cursor));
      assertThat(page.data().getJsonArray("items").size() <= 1, is(true));
      for (JsonObject o : page.data().getJsonArray("items").getValuesAs(JsonObject.class)) {
        assertThat(seen.add(o.getString("id")), is(true));
      }
      cursor =
          page.data().get("nextCursor") == null || page.data().isNull("nextCursor")
              ? null
              : page.data().getString("nextCursor");
      pages++;
    } while (cursor != null && pages < 50);
    assertThat(seen.size(), is(items.size()));

    Answer typed = events(tenant, "OWNER", null, "type=MFA_LOGIN_FAILED&userId=" + staff);
    assertThat(typed.data().getJsonArray("items"), hasSize(1));

    // A period in the future holds nothing; one that ends before it starts is refused.
    Answer future = events(tenant, "OWNER", null, "from=2999-01-01T00:00:00Z");
    assertThat(future.data().getJsonArray("items"), hasSize(0));
    Answer backwards =
        events(tenant, "OWNER", null, "from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z");
    assertThat(backwards.status(), is(400));
    assertThat(backwards.code(), is("SECURITY_EVENT_PERIOD_INVALID"));
    Answer badType = events(tenant, "OWNER", null, "type=drop table");
    assertThat(badType.status(), is(400));
    assertThat(badType.code(), is("SECURITY_EVENT_TYPE_INVALID"));
    assertThat(events(tenant, "OWNER", null, "userId=nope").status(), is(400));
    assertThat(events(tenant, "OWNER", null, "from=yesterday").status(), is(400));
  }

  // ── flow catalogue cases the code already meets (AUTH-104, 110, 312, 313, 411, 412) ───────

  /**
   * AUTH-104: a business's staff or a shopper's right password opens nothing on the platform login,
   * and the answer is the generic one an unknown address gets. (The catalogue expected 403; the
   * code deliberately answers 401 INVALID_CREDENTIALS so neither door says which kind of login an
   * address holds.)
   */
  @Test
  @DisplayName("Platform login refuses staff and shopper credentials like an unknown address")
  void platformLoginRefusesEveryoneButThePlatformAdministrator() {
    String staffEmail = "pl-staff-" + Ids.newId() + "@example.com";
    staff(staffEmail, Ids.newId(), "OWNER");
    String shopperEmail = "pl-shop-" + Ids.newId() + "@example.com";
    register(shopperEmail, STRONG);
    String unknown = "pl-none-" + Ids.newId() + "@example.com";
    for (String email : new String[] {staffEmail, shopperEmail, unknown}) {
      Answer a =
          post(
              "/auth/platform-login",
              "{\"email\":\"" + email + "\",\"password\":\"" + STRONG + "\"}");
      assertThat(email, a.status(), is(401));
      assertThat(email, a.code(), is("INVALID_CREDENTIALS"));
    }
  }

  /** AUTH-110: signing out twice, or with a token nobody issued, is the same 200. */
  @Test
  @DisplayName("Logout is idempotent")
  void logoutIsIdempotent() {
    String email = "logout-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    String refresh = login(email, STRONG).data().getString("refreshToken");
    String body = "{\"refreshToken\":\"" + refresh + "\"}";
    Answer first = post("/auth/logout", body);
    Answer second = post("/auth/logout", body);
    Answer unknown =
        post("/auth/logout", "{\"refreshToken\":\"never-issued-" + Ids.newId() + "\"}");
    assertThat(first.status(), is(200));
    assertThat(first.text(), first.body().getString("data"), is("logged_out"));
    assertThat(second.status(), is(200));
    assertThat(unknown.status(), is(200));
    assertThat(refresh(refresh).status(), is(401));
  }

  @Test
  @DisplayName("Logout with no token is refused and revokes nothing")
  void logoutWithNoTokenIsRefused() {
    String email = "logout-none-" + Ids.newId() + "@example.com";
    register(email, STRONG);
    String refresh = login(email, STRONG).data().getString("refreshToken");

    List<String> bodies = List.of("{}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":\"   \"}");
    for (String body : bodies) {
      Answer refused = post("/auth/logout", body);
      assertThat(body, refused.status(), is(400));
      assertThat(body, refused.code(), is("VALIDATION_FAILED"));
    }
    assertThat("the session was not ended", refresh(refresh).status(), is(200));
  }

  /**
   * AUTH-312 and AUTH-313: the platform administrator clears any business's staff member's second
   * factor and sessions; another business's owner or manager naming that same real id finds nobody,
   * and nothing of theirs changes.
   */
  @Test
  @DisplayName("Second-factor reset: the platform administrator may, another business may not")
  void secondFactorResetAcrossBusinesses() {
    UUID tenantA = Ids.newId();
    UUID tenantB = Ids.newId();
    String emailA = "mfa-a-" + Ids.newId() + "@example.com";
    UUID staffA = staff(emailA, tenantA, "MANAGER");
    Answer session = login(emailA, STRONG);
    String path = "/auth/admin/staff-users/" + staffA + "/mfa";

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer stranger =
          read(
              target
                  .path(path)
                  .request()
                  .header("X-User-Id", Ids.newId().toString())
                  .header("X-Tenant-Id", tenantB.toString())
                  .header("X-Roles", role)
                  .delete());
      assertThat(role, stranger.status(), is(role.equals("OWNER") ? 404 : 403));
    }
    Answer stillThere = refresh(session.data().getString("refreshToken"));
    assertThat("nothing moved", stillThere.status(), is(200));
    String held = stillThere.data().getString("refreshToken");

    Answer admin =
        read(
            target
                .path(path)
                .request()
                .header("X-User-Id", Ids.newId().toString())
                .header("X-Roles", "PLATFORM_ADMIN")
                .delete());
    assertThat(admin.text(), admin.status(), is(200));
    // Their sessions ended with it; the login itself is untouched.
    assertThat(refresh(held).status(), is(401));
    assertThat(login(emailA, STRONG).status(), is(200));
  }

  private Answer sso(String method, UUID tenant, String roles) {
    Invocation.Builder b =
        target
            .path("/auth/admin/sso")
            .request()
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Tenant-Id", tenant.toString())
            .header("X-Roles", roles);
    return read(
        switch (method) {
          case "PUT" ->
              b.put(
                  Entity.entity(
                      "{\"slug\":\"biz-"
                          + tenant.toString().substring(0, 8)
                          + "\",\"issuer\":\"https://idp.example.com\",\"clientId\":\"client\","
                          + "\"clientSecret\":\"placeholder\",\"enabled\":true,"
                          + "\"requiredTiers\":[\"MANAGER\"]}",
                      MediaType.APPLICATION_JSON));
          case "DELETE" -> b.delete();
          default -> b.get();
        });
  }

  /**
   * AUTH-411 and AUTH-412: only an owner connects or removes the provider; a manager reads; a
   * cashier nothing; another business, whatever its role, finds none of it and changes none.
   */
  @Test
  @DisplayName(
      "Single sign-on: owner writes, manager reads, another business sees and changes none")
  void ssoRolesAndIsolation() {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    assertThat(sso("PUT", ours, "MANAGER").status(), is(403));
    assertThat(sso("PUT", ours, "CASHIER").status(), is(403));
    Answer connected = sso("PUT", ours, "OWNER");
    assertThat(connected.text(), connected.status(), is(200));

    assertThat(sso("GET", ours, "MANAGER").status(), is(200));
    assertThat(sso("GET", ours, "CASHIER").status(), is(403));
    assertThat(sso("DELETE", ours, "MANAGER").status(), is(403));
    assertThat(sso("DELETE", ours, "CASHIER").status(), is(403));

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer read = sso("GET", theirs, role);
      assertThat(role, read.code(), is("SSO_NOT_CONFIGURED"));
      assertThat(role, read.text(), not(containsString("idp.example.com")));
    }
    assertThat(sso("DELETE", theirs, "OWNER").status(), is(404));
    // Ours is still connected after all of that.
    assertThat(sso("GET", ours, "OWNER").status(), is(200));
    assertThat(sso("DELETE", ours, "OWNER").status(), is(200));
  }
}
