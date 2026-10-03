package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.auth0.jwt.JWT;
import com.storeql.iam.mfa.Totp;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.service.AuthService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform administrator's own surface in iam-svc — the one-shot bootstrap, the signing keys,
 * the POS sweep across every business, and the reset of anybody's second factor — and who is turned
 * away from it. Every role of the business that holds the thing and of another business, a shopper
 * and nobody at all are each refused with the one answer, and what the surface would have moved (an
 * administrator, a key, a session, a factor) is read back unmoved. Each refusal is then set beside
 * the platform administrator's own call, so the unmoved state is not an empty one.
 *
 * <p>Requests carry the identity headers the gateway would have stamped from a verified token.
 */
@HelidonTest
class PlatformAdminSurfaceIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";
  private static final String CONSUMER = "platform-surface-it";

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
  }

  @Inject WebTarget target;
  @Inject UserRepository users;
  @Inject AuthService auth;

  @AfterAll
  static void stop() {
    PG.stop();
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

  private record Caller(UUID userId, String roles, UUID tenantId) {
    static final Caller NOBODY = new Caller(null, null, null);

    static Caller platform() {
      return new Caller(Ids.newId(), "PLATFORM_ADMIN", null);
    }

    @Override
    public String toString() {
      return roles == null ? "nobody" : roles + (tenantId == null ? "" : " of " + tenantId);
    }
  }

  private Answer call(String method, String path, Caller who, String json) {
    Invocation.Builder b = target.path(path).request();
    if (who.userId() != null) b = b.header("X-User-Id", who.userId());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId());
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "DELETE" -> b.delete();
          default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private static String credentials(String email) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
  }

  private Answer login(String email) {
    return call("POST", "/auth/login", Caller.NOBODY, credentials(email));
  }

  /** A business: its tenant, a store, and the owner who made it. */
  private record Biz(UUID tenant, UUID store, Caller owner) {}

  private Biz business(String label) {
    Answer a =
        call("POST", "/auth/register", Caller.NOBODY, credentials(label + "-owner@example.com"));
    assertThat(a.body().toString(), a.status(), is(201));
    UUID ownerId = Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
    UUID tenant = Ids.newId();
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    return new Biz(tenant, Ids.newId(), new Caller(ownerId, "OWNER", tenant));
  }

  /** A member of staff made the one way there is: provisioned in the business, then bound. */
  private Caller staff(Biz b, String email, String tier) {
    UUID id = Ids.parse(auth.provisionStaff(b.tenant(), email, PASSWORD).userId());
    users.bindStaffOnce(Ids.newId(), CONSUMER, id, b.tenant(), tier, b.store());
    return new Caller(id, tier, b.tenant());
  }

  /** Every way of not being the platform administrator: each role of two businesses, and nobody. */
  private static List<Caller> everyoneElse(Biz ours, Biz theirs) {
    List<Caller> out = new ArrayList<>();
    for (Biz b : List.of(ours, theirs)) {
      for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
        out.add(new Caller(Ids.newId(), role, b.tenant()));
      }
    }
    out.add(Caller.NOBODY);
    return out;
  }

  private static Connection iam() throws SQLException {
    Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  private static int count(String sql, Object... binds) throws SQLException {
    try (Connection c = iam();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static String sessionStatus(UUID id) throws SQLException {
    try (Connection c = iam();
        PreparedStatement ps = c.prepareStatement("SELECT status FROM pos_sessions WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /** A till session whose idle time ran out hours ago, which a sweep would expire. */
  private static UUID idleSession(Biz b) throws SQLException {
    UUID id = Ids.newId();
    OffsetDateTime started = Instant.now().minusSeconds(3 * 3600).atOffset(ZoneOffset.UTC);
    try (Connection c = iam();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO pos_sessions (id, tenant_id, user_id, store_id, started_at,"
                    + " last_activity_at, idle_timeout_seconds, status)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 60, 'ACTIVE')")) {
      ps.setObject(1, id);
      ps.setObject(2, b.tenant());
      ps.setObject(3, b.owner().userId());
      ps.setObject(4, b.store());
      ps.setObject(5, started);
      ps.setObject(6, started);
      ps.executeUpdate();
    }
    return id;
  }

  private static String code(String base32Secret, int stepsFromNow) {
    return Totp.code(Totp.fromBase32(base32Secret), Totp.stepAt(Instant.now()) + stepsFromNow);
  }

  private void enrolTotp(Caller who) {
    Answer begun = call("POST", "/auth/mfa/totp", who, null);
    assertThat(begun.body().toString(), begun.status(), is(200));
    String secret = begun.data().getString("secret");
    Answer confirmed =
        call("POST", "/auth/mfa/totp/confirm", who, "{\"code\":\"" + code(secret, -1) + "\"}");
    assertThat(confirmed.body().toString(), confirmed.status(), is(200));
  }

  // ── the bootstrap ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("The bootstrap makes one platform administrator, and refuses everybody after")
  void theBootstrapIsOnceWhoeverKnocks() throws Exception {
    Biz ours = business("boot-ours");
    Biz theirs = business("boot-theirs");
    assertThat("no administrator yet", count(adminRoles()), is(0));

    Answer born =
        call("POST", "/bootstrap/admin", Caller.NOBODY, credentials("boot-root@example.com"));
    assertThat(born.body().toString(), born.status(), is(201));
    assertThat(born.data().getString("role"), is("PLATFORM_ADMIN"));
    assertThat(count(adminRoles()), is(1));

    List<Caller> knockers = new ArrayList<>(everyoneElse(ours, theirs));
    knockers.add(Caller.platform());
    int n = 0;
    for (Caller who : knockers) {
      String email = "boot-second-" + n++ + "@example.com";
      Answer refused = call("POST", "/bootstrap/admin", who, credentials(email));
      assertThat(who + " -> " + refused.body(), refused.status(), is(409));
      assertThat(who.toString(), refused.code(), is("BOOTSTRAP_ALREADY_DONE"));
      assertThat(who + " made no account", login(email).status(), is(401));
    }
    assertThat("still one administrator, and no role was granted", count(adminRoles()), is(1));
    assertThat(
        "nor did a business's owner become one",
        count(
            "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                + " WHERE r.name = 'PLATFORM_ADMIN' AND ur.user_id = ?",
            ours.owner().userId()),
        is(0));
  }

  private static String adminRoles() {
    return "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
        + " WHERE r.name = 'PLATFORM_ADMIN'";
  }

  // ── the signing keys ───────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Only the platform administrator lists or rotates the signing keys, and a refusal moves none")
  void onlyThePlatformAdministratorTouchesTheKeys() throws Exception {
    Biz ours = business("keys-ours");
    Biz theirs = business("keys-theirs");
    int keys = count("SELECT count(*) FROM signing_keys");
    assertThat("signing a token made the first key", keys >= 1, is(true));

    for (Caller who : everyoneElse(ours, theirs)) {
      Answer list = call("GET", "/auth/admin/signing-keys", who, null);
      assertThat(who + " -> " + list.body(), list.status(), is(403));
      assertThat(who.toString(), list.code(), is("FORBIDDEN"));
      assertThat(who.toString(), list.body().containsKey("data"), is(false));
      Answer rotate = call("POST", "/auth/admin/signing-keys/rotate", who, null);
      assertThat(who + " -> " + rotate.body(), rotate.status(), is(403));
      assertThat(who.toString(), rotate.code(), is("FORBIDDEN"));
    }
    assertThat(
        "no key was made by a refusal", count("SELECT count(*) FROM signing_keys"), is(keys));

    Answer listed = call("GET", "/auth/admin/signing-keys", Caller.platform(), null);
    assertThat(listed.body().toString(), listed.status(), is(200));
    Answer rotated = call("POST", "/auth/admin/signing-keys/rotate", Caller.platform(), null);
    assertThat(rotated.body().toString(), rotated.status(), is(201));
    assertThat(
        "the platform's own rotation made one",
        count("SELECT count(*) FROM signing_keys"),
        is(keys + 1));
    assertThat(
        "and shows no key material", rotated.body().toString(), not(containsString("private")));
  }

  // ── the POS sweep ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("Only the platform administrator sweeps idle till sessions, across every business")
  void onlyThePlatformAdministratorSweepsTheTills() throws Exception {
    Biz ours = business("sweep-ours");
    Biz theirs = business("sweep-theirs");
    UUID mine = idleSession(ours);
    UUID yours = idleSession(theirs);

    for (Caller who : everyoneElse(ours, theirs)) {
      Answer refused = call("POST", "/auth/pos/sessions/sweep", who, null);
      assertThat(who + " -> " + refused.body(), refused.status(), is(403));
      assertThat(who.toString(), refused.code(), is("FORBIDDEN"));
      assertThat(who.toString(), refused.body().containsKey("data"), is(false));
    }
    assertThat("our idle session is untouched", sessionStatus(mine), is("ACTIVE"));
    assertThat("and so is the other business's", sessionStatus(yours), is("ACTIVE"));

    Answer swept = call("POST", "/auth/pos/sessions/sweep", Caller.platform(), null);
    assertThat(swept.body().toString(), swept.status(), is(200));
    assertThat(swept.data().getInt("sessionsExpired") >= 2, is(true));
    assertThat("the platform's sweep crosses businesses: ours", sessionStatus(mine), is("EXPIRED"));
    assertThat("and theirs", sessionStatus(yours), is("EXPIRED"));
  }

  // ── a second factor ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A second factor is reset by the owner of its business or by the platform, and by no one else")
  void aSecondFactorIsResetOnlyByItsOwnersOrThePlatform() throws Exception {
    Biz ours = business("reset-ours");
    Biz theirs = business("reset-theirs");
    String email = "reset-cashier@example.com";
    Caller cashier = staff(ours, email, "CASHIER");
    enrolTotp(cashier);
    assertThat(login(email).data().getBoolean("mfaRequired"), is(true));
    String path = "/auth/admin/staff-users/" + cashier.userId() + "/mfa";

    // Another business's owner finds nobody there to reset: not forbidden, not found.
    Answer stranger = call("DELETE", path, theirs.owner(), null);
    assertThat(stranger.body().toString(), stranger.status(), is(404));
    assertThat(stranger.code(), is("USER_NOT_FOUND"));

    // Everyone else is turned away by role, in whichever business, and nobody by themselves.
    List<Caller> turnedAway = new ArrayList<>();
    for (Biz b : List.of(ours, theirs)) {
      for (String role : List.of("MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
        turnedAway.add(new Caller(Ids.newId(), role, b.tenant()));
      }
    }
    turnedAway.add(Caller.NOBODY);
    for (Caller who : turnedAway) {
      Answer refused = call("DELETE", path, who, null);
      assertThat(who + " -> " + refused.body(), refused.status(), is(403));
      assertThat(who.toString(), refused.code(), is("FORBIDDEN"));
    }
    assertThat(
        "the factor is still there to be asked for",
        login(email).data().getBoolean("mfaRequired"),
        is(true));
    assertThat(
        "and it is still enrolled",
        count(
            "SELECT count(*) FROM mfa_totp WHERE user_id = ? AND status = 'ACTIVE'",
            cashier.userId()),
        is(1));

    // The platform's own call, beside the refusals above, resets it.
    Answer reset = call("DELETE", path, Caller.platform(), null);
    assertThat(reset.body().toString(), reset.status(), is(200));
    Answer after = login(email);
    assertThat(after.data().containsKey("mfaRequired"), is(false));
    assertThat(after.data().containsKey("accessToken"), is(true));
  }
}
