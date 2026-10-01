package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the iam-svc auth flow against a real Postgres (Testcontainers): register →
 * login → refresh (with single-use rotation) → /auth/me, plus validation and duplicate-detection.
 * Kafka/Consul disabled.
 */
@HelidonTest
class AuthIT {

  private static final PostgresSupport PG;

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

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json) {
    return target.path(path).request().post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void registerLoginRefreshMe() {
    // register
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"it-user@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(reg.getStatus(), is(201));
    String regBody = reg.readEntity(String.class);
    String access = extract(regBody, "accessToken");
    String refresh = extract(regBody, "refreshToken");

    // me, as the gateway forwards it
    String me = me(access);
    assertThat(me, containsString("it-user@example.com"));
    assertThat(me, containsString("CUSTOMER"));

    // login
    Response login =
        post(
            "/auth/login",
            "{\"email\":\"it-user@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(login.getStatus(), is(200));

    // refresh rotates: old token then fails
    Response refreshed = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(refreshed.getStatus(), is(200));
    Response reuseOld = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(reuseOld.getStatus(), is(401));
  }

  @Test
  void wrongPasswordIs401() {
    post(
        "/auth/register",
        "{\"email\":\"pw@example.com\",\"password\":\"correctpass1 for storeql\"}");
    Response bad =
        post("/auth/login", "{\"email\":\"pw@example.com\",\"password\":\"wrongwrong\"}");
    assertThat(bad.getStatus(), is(401));
    assertThat(bad.readEntity(String.class), containsString("INVALID_CREDENTIALS"));
  }

  @Test
  void disabledUserCannotLogIn() throws Exception {
    post(
        "/auth/register",
        "{\"email\":\"disabled@example.com\",\"password\":\"correctpass1 for storeql\"}");
    try (var c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET status='DISABLED' WHERE lower(email)=?")) {
      ps.setString(1, "disabled@example.com");
      assertThat(ps.executeUpdate(), is(1));
    }
    // Right password, but the account is disabled — must still be rejected, not silently logged
    // in (and not via a different/faster code path that would leak the account's status by
    // timing — see AuthService.login()'s burn() call on the non-ACTIVE branch).
    Response login =
        post(
            "/auth/login",
            "{\"email\":\"disabled@example.com\",\"password\":\"correctpass1 for storeql\"}");
    assertThat(login.getStatus(), is(401));
    assertThat(login.readEntity(String.class), containsString("INVALID_CREDENTIALS"));
  }

  @Test
  void invalidInputIs400WithCleanEnvelope() {
    Response bad = post("/auth/register", "{\"email\":\"notanemail\",\"password\":\"short\"}");
    assertThat(bad.getStatus(), is(400));
    String body = bad.readEntity(String.class);
    assertThat(body, containsString("VALIDATION_FAILED"));
    assertThat(body, not(containsString("WeldSubclass"))); // no framework internals leaked
  }

  @Test
  void changePasswordRevokesOutstandingRefreshTokens() {
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"rotate@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(reg.getStatus(), is(201));
    String regBody = reg.readEntity(String.class);
    String access = extract(regBody, "accessToken");
    String refresh = extract(regBody, "refreshToken");

    String me = me(access);
    String userId = extract(me, "userId");

    // change password (X-User-Id simulates the gateway-stamped identity header)
    Response changed =
        target
            .path("/auth/change-password")
            .request()
            .header("X-User-Id", userId)
            .put(
                Entity.entity(
                    "{\"currentPassword\":\"strongpass1 for storeql\",\"newPassword\":\"evenstronger2 for storeql\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(changed.getStatus(), is(200));

    // the pre-change refresh token must be dead
    Response reuse = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(reuse.getStatus(), is(401));

    // and the new password logs in
    Response login =
        post(
            "/auth/login",
            "{\"email\":\"rotate@example.com\",\"password\":\"evenstronger2 for storeql\"}");
    assertThat(login.getStatus(), is(200));
  }

  @Test
  void posSweepRequiresPlatformAdminRole() {
    // no identity headers → no roles → must be 403, not a tenant-wide sweep
    Response sweep =
        target
            .path("/auth/pos/sessions/sweep")
            .request()
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(sweep.getStatus(), is(403));

    // a CUSTOMER (non-admin) must also be rejected
    Response sweepAsCustomer =
        target
            .path("/auth/pos/sessions/sweep")
            .request()
            .header("X-Roles", "CUSTOMER")
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(sweepAsCustomer.getStatus(), is(403));

    // PLATFORM_ADMIN passes the guard and executes (0 idle sessions → 200)
    Response sweepAsAdmin =
        target
            .path("/auth/pos/sessions/sweep")
            .request()
            .header("X-Roles", "PLATFORM_ADMIN")
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(sweepAsAdmin.getStatus(), is(200));
  }

  @Test
  void duplicateRegisterIs409() {
    post(
        "/auth/register",
        "{\"email\":\"dup@example.com\",\"password\":\"strongpass1 for storeql\"}");
    Response dup =
        post(
            "/auth/register",
            "{\"email\":\"dup@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(dup.getStatus(), is(409));
  }

  @Test
  void suspendedTenantBlocksLoginAndRefresh() throws Exception {
    // Register a user, then bind it to a tenant and mark that tenant INACTIVE in iam's projection
    // (simulating the TenantStatusChanged event the consumer would apply).
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"susp@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(reg.getStatus(), is(201));
    String refresh = extract(reg.readEntity(String.class), "refreshToken");

    java.util.UUID tenantId = com.storeql.ids.Ids.newId();
    try (var c = iamConnection()) {
      try (var ps =
          c.prepareStatement(
              "UPDATE users SET tenant_id=?, type='STAFF' WHERE lower(email)=lower(?)")) {
        ps.setObject(1, tenantId);
        ps.setString(2, "susp@example.com");
        ps.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "INSERT INTO tenant_status (tenant_id, status, status_changed_at)"
                  + " VALUES (?, 'INACTIVE', now())")) {
        ps.setObject(1, tenantId);
        ps.executeUpdate();
      }
    }

    // Login is now forbidden for this tenant's staff, even with the correct password.
    Response blocked =
        post(
            "/auth/login",
            "{\"email\":\"susp@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(blocked.getStatus(), is(403));
    assertThat(blocked.readEntity(String.class), containsString("TENANT_INACTIVE"));

    // An existing refresh token can't mint new access tokens either.
    Response refreshBlocked = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(refreshBlocked.getStatus(), is(403));
    // The refusal does not spend the token, so the app retrying it is refused the same way rather
    // than taken for a stolen token (401, and every session revoked).
    Response retried = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(retried.getStatus(), is(403));
    assertThat(retried.readEntity(String.class), containsString("TENANT_INACTIVE"));

    // Reactivating the tenant restores login.
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "UPDATE tenant_status SET status='ACTIVE', status_changed_at=now()"
                    + " WHERE tenant_id=?")) {
      ps.setObject(1, tenantId);
      ps.executeUpdate();
    }
    Response ok =
        post(
            "/auth/login",
            "{\"email\":\"susp@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(ok.getStatus(), is(200));
    // ...and the refresh token held through the suspension works again.
    Response refreshed = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(refreshed.getStatus(), is(200));
  }

  @Test
  void provisionStaffRejectsBlankAndShortPassword() {
    java.util.UUID tenantId = com.storeql.ids.Ids.newId();
    Response blank =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-blankpw@example.com\",\"password\":\"\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(blank.getStatus(), is(400));
    assertThat(blank.readEntity(String.class), containsString("VALIDATION_FAILED"));

    Response tooShort =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-shortpw@example.com\",\"password\":\"short1\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(tooShort.getStatus(), is(400));
    assertThat(tooShort.readEntity(String.class), containsString("VALIDATION_FAILED"));
  }

  @Test
  void provisionStaffRequiresManagementRole() {
    java.util.UUID tenantId = com.storeql.ids.Ids.newId();
    // Asserted directly in AuthResource.provisionStaff as a backstop independent of the shared
    // filter's "/admin/" path-prefix rule — see its javadoc.
    Response asCashier =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "CASHIER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-forbidden@example.com\",\"password\":\"strongpass1 for storeql\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(asCashier.getStatus(), is(403));
  }

  @Test
  void provisionStaffCreatesAccountWithoutRoleAssignment() throws Exception {
    // Regression: the old code passed "STAFF" as roleName to createUserWithOutbox, which called
    // roleIdByName("STAFF") — but "STAFF" is a user *type*, not a roles-table row, so every call
    // threw "role not found: STAFF" and returned 500. Now roleName is null → role assignment is
    // skipped, and the real store-scoped role arrives later via StaffAssigned event.
    java.util.UUID tenantId = com.storeql.ids.Ids.newId();
    Response resp =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-new@example.com\",\"password\":\"strongpass1 for storeql\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(resp.getStatus(), is(200));
    String body = resp.readEntity(String.class);
    String userId = extract(body, "userId");

    // Verify no user_roles row was created — the account is intentionally role-less at this point.
    try (var c = iamConnection();
        var ps = c.prepareStatement("SELECT count(*) FROM user_roles WHERE user_id = ?")) {
      ps.setObject(1, Ids.parse(userId));
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1), is(0));
      }
    }
  }

  @Test
  void provisionStaffIsIdempotentForSameEmail() {
    java.util.UUID tenantId = com.storeql.ids.Ids.newId();
    String body1 =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-idem@example.com\",\"password\":\"strongpass1 for storeql\"}",
                    MediaType.APPLICATION_JSON))
            .readEntity(String.class);
    String body2 =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-idem@example.com\",\"password\":\"strongpass1 for storeql\"}",
                    MediaType.APPLICATION_JSON))
            .readEntity(String.class);
    assertThat(extract(body1, "userId"), is(extract(body2, "userId")));
  }

  /** A JDBC connection scoped to iam-svc's schema (the app uses storeql.db.schema=iam). */
  private static java.sql.Connection iamConnection() throws java.sql.SQLException {
    var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  /** Tiny JSON field extractor (avoids pulling a JSON lib into the test). */
  /**
   * The gateway verifies the access token and forwards its subject and roles as identity headers,
   * never the token itself.
   */
  private String me(String accessToken) {
    DecodedJWT jwt = JWT.decode(accessToken);
    return target
        .path("/auth/me")
        .request()
        .header("X-User-Id", jwt.getSubject())
        .header("X-Roles", String.join(",", jwt.getClaim("roles").asList(String.class)))
        .get(String.class);
  }

  /** A bearer token without gateway identity is not an identity: /auth/me reads only the latter. */
  @Test
  void meNeedsTheGatewayIdentity() {
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"me-direct@example.com\",\"password\":\"strongpass1 for storeql\"}");
    String access = extract(reg.readEntity(String.class), "accessToken");
    Response bearerOnly =
        target.path("/auth/me").request().header("Authorization", "Bearer " + access).get();
    assertThat(bearerOnly.getStatus(), is(401));
    assertThat(bearerOnly.readEntity(String.class), containsString("NO_USER"));
  }

  private static String extract(String json, String field) {
    String key = "\"" + field + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) {
      throw new AssertionError("field " + field + " not in: " + json);
    }
    int start = i + key.length();
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }

  // ── SJ-D43: the account holder deletes their own login ─────────────────────

  private String registerAndGetUserId(String email) {
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(reg.getStatus(), is(201));
    String access = extract(reg.readEntity(String.class), "accessToken");
    return extract(me(access), "userId");
  }

  private Response deleteAccount(String userId, String password) {
    return target
        .path("/auth/delete-account")
        .request()
        .header("X-User-Id", userId)
        .post(Entity.entity("{\"password\":\"" + password + "\"}", MediaType.APPLICATION_JSON));
  }

  @Test
  void aCustomerCanDeleteTheirOwnAccount() throws Exception {
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"leaving@example.com\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(reg.getStatus(), is(201));
    String regBody = reg.readEntity(String.class);
    String refresh = extract(regBody, "refreshToken");
    String userId = extract(me(extract(regBody, "accessToken")), "userId");

    // A session left open on a shared device is not enough: the password is asked for again.
    assertThat(deleteAccount(userId, "not-my-password").getStatus(), is(401));
    assertThat(deleteAccount(userId, "strongpass1 for storeql").getStatus(), is(200));

    // The login no longer works, and no session survives it.
    assertThat(
        post(
                "/auth/login",
                "{\"email\":\"leaving@example.com\",\"password\":\"strongpass1 for storeql\"}")
            .getStatus(),
        is(401));
    assertThat(
        post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}").getStatus(), is(401));

    // What identified the person is gone from the row; the row itself stays.
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "SELECT email, phone, password_hash, status FROM users WHERE id = ?")) {
      ps.setObject(1, Ids.parse(userId));
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next(), is(true));
        assertThat(rs.getString("email"), org.hamcrest.Matchers.nullValue());
        assertThat(rs.getString("password_hash"), org.hamcrest.Matchers.nullValue());
        assertThat(rs.getString("status"), is("DELETED"));
      }
      // Other services are told, and the event carries no email.
      try (var ev =
          c.prepareStatement(
              "SELECT payload FROM outbox WHERE event_type = 'AccountDeleted' AND aggregate_id = ?")) {
        ev.setObject(1, Ids.parse(userId));
        try (var rs = ev.executeQuery()) {
          assertThat(rs.next(), is(true));
          assertThat(rs.getString(1).contains("leaving@example.com"), is(false));
        }
      }
    }

    // The same address can open a new account later — a new one, not the old one back.
    Response again =
        post(
            "/auth/register",
            "{\"email\":\"leaving@example.com\",\"password\":\"anotherpass2 for storeql\"}");
    assertThat(again.getStatus(), is(201));
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "SJ-D45: the sign-in trail keeps its history and loses the deleted address")
  void deletingAnAccountRedactsTheAuditTrail() throws Exception {
    String email = "audited@example.com";
    String userId = registerAndGetUserId(email);
    // A successful sign-in and a failed one: both recorded the address until this fix.
    assertThat(
        post(
                "/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"strongpass1 for storeql\"}")
            .getStatus(),
        is(200));
    assertThat(
        post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrongpass99\"}")
            .getStatus(),
        is(401));

    try (var c = iamConnection();
        var ps =
            c.prepareStatement("SELECT count(*) FROM audit_log WHERE user_id = ? AND detail = ?")) {
      ps.setObject(1, Ids.parse(userId));
      ps.setString(2, email);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat("the address is in the trail before the deletion", rs.getInt(1) > 0, is(true));
      }
    }

    assertThat(deleteAccount(userId, "strongpass1 for storeql").getStatus(), is(200));

    try (var c = iamConnection()) {
      try (var ps =
          c.prepareStatement("SELECT count(*) FROM audit_log WHERE user_id = ? AND detail = ?")) {
        ps.setObject(1, Ids.parse(userId));
        ps.setString(2, email);
        try (var rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getInt(1), is(0));
        }
      }
      // Append-only holds: the rows are still there, and so is what happened.
      try (var ps =
          c.prepareStatement(
              "SELECT count(*) FROM audit_log WHERE user_id = ? AND action IN"
                  + " ('USER_REGISTERED','LOGIN_OK','LOGIN_FAILED','ACCOUNT_DELETED')")) {
        ps.setObject(1, Ids.parse(userId));
        try (var rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getInt(1) >= 4, is(true));
        }
      }
    }
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "SJ-D44: the access token carries the holder's email, so a shop can match them to a record")
  void theAccessTokenCarriesTheEmail() {
    String email = "claims@example.com";
    Response reg =
        post(
            "/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(reg.getStatus(), is(201));
    String access = extract(reg.readEntity(String.class), "accessToken");

    String payload = access.split("\\.")[1];
    String claims =
        new String(
            java.util.Base64.getUrlDecoder().decode(payload),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(claims.contains("\"email\":\"" + email + "\""), is(true));
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "SJ-D48: a shopper promoted to cashier at one store is scoped to that store, not freed by"
          + " their customer role")
  void aCustomerPromotedToCashierKeepsTheStoreScope() throws Exception {
    String email = "promoted@example.com";
    String userId = registerAndGetUserId(email);
    java.util.UUID tenant = com.storeql.ids.Ids.newId();
    java.util.UUID store = com.storeql.ids.Ids.newId();
    // What the StaffAssigned consumer writes: a CASHIER row at one store, beside the CUSTOMER
    // row registration made with no store at all.
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                    + " SELECT ?, ?, id, ? FROM roles WHERE name = 'CASHIER'")) {
      ps.setObject(1, com.storeql.ids.Ids.newId());
      ps.setObject(2, Ids.parse(userId));
      ps.setObject(3, store);
      ps.executeUpdate();
    }
    try (var c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET tenant_id = ? WHERE id = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, Ids.parse(userId));
      ps.executeUpdate();
    }
    Response login =
        post(
            "/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(login.getStatus(), is(200));
    String access = extract(login.readEntity(String.class), "accessToken");
    String claims =
        new String(
            java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(claims.contains("\"storeIds\":[\"" + store + "\"]"), is(true));
  }

  @Test
  void deletingTwiceIsRefusedNotRepeated() {
    String userId = registerAndGetUserId("twice@example.com");
    assertThat(deleteAccount(userId, "strongpass1 for storeql").getStatus(), is(200));
    // The account is no longer active, so the same request cannot be verified a second time.
    assertThat(deleteAccount(userId, "strongpass1 for storeql").getStatus(), is(401));
  }

  @Test
  void aStaffAccountIsNotDeletedHere() throws Exception {
    String userId = registerAndGetUserId("employee@example.com");
    try (var c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET type = 'STAFF' WHERE id = ?")) {
      ps.setObject(1, Ids.parse(userId));
      ps.executeUpdate();
    }
    // A staff login belongs to the business that employs its holder, which removes it.
    Response r = deleteAccount(userId, "strongpass1 for storeql");
    assertThat(r.getStatus(), is(403));
    assertThat(r.readEntity(String.class).contains("ACCOUNT_MANAGED_BY_EMPLOYER"), is(true));
  }

  // ── custom roles and the permission claim (20.10) ───────────────────────────

  @Inject com.storeql.iam.messaging.StaffAssignedHandler staffAssigned;
  @Inject com.storeql.iam.messaging.StaffRemovedHandler staffRemoved;
  @Inject com.storeql.iam.messaging.RoleDefinedHandler roleDefined;
  @Inject com.storeql.iam.service.AuthService auth;

  /**
   * A staff login made in the tenant the one way there is — staff provisioning — with the password
   * {@link #claimsOf} signs in with; its id. A StaffAssigned binds only a login already there.
   */
  private String provisionedUserId(String tenant, String email) {
    return auth.provisionStaff(Ids.parse(tenant), email, "strongpass1 for storeql").userId();
  }

  /**
   * A login stamped into the tenant: as {@code TenantCreated} makes its owner's, and as a
   * StaffAssigned made a shopper's before 29 Sep 2026.
   */
  private static void stampedInto(String userId, String tenant) {
    Envelopes.exec(
        PG,
        "UPDATE iam.users SET tenant_id = '"
            + tenant
            + "', type = 'STAFF' WHERE id = '"
            + userId
            + "' AND tenant_id IS NULL");
  }

  private static String staffEvent(
      String type,
      String eventId,
      String tenant,
      String user,
      String store,
      String role,
      String roleCode,
      String perms) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + user
        + "\",\"occurredAt\":\"2026-09-13T00:00:00Z\",\"userId\":\""
        + user
        + "\",\"storeId\":\""
        + store
        + "\",\"role\":\""
        + role
        + "\""
        + (roleCode == null ? "" : ",\"roleCode\":\"" + roleCode + "\"")
        + (perms == null ? "" : ",\"permissions\":" + perms)
        + "}";
  }

  private static String roleEvent(String eventId, String tenant, String code, String perms) {
    return roleEvent(eventId, tenant, code, perms, "2026-09-13T12:00:00Z");
  }

  private static String roleEvent(
      String eventId, String tenant, String code, String perms, String updatedAt) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"RoleDefined\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + tenant
        + "\",\"occurredAt\":\"2026-09-13T00:00:00Z\",\"code\":\""
        + code
        + "\",\"baseTier\":\"MANAGER\",\"permissions\":"
        + perms
        + ",\"updatedAt\":\""
        + updatedAt
        + "\"}";
  }

  private String claimsOf(String email) {
    Response login =
        post(
            "/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"strongpass1 for storeql\"}");
    assertThat(login.getStatus(), is(200));
    String access = extract(login.readEntity(String.class), "accessToken");
    return new String(
        java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private String meOf(String email) {
    Response login =
        post(
            "/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"strongpass1 for storeql\"}");
    String access = extract(login.readEntity(String.class), "accessToken");
    DecodedJWT jwt = JWT.decode(access);
    var req = target.path("/auth/me").request().header("X-User-Id", jwt.getSubject());
    if (!jwt.getClaim("tenant").isMissing())
      req = req.header("X-Tenant-Id", jwt.getClaim("tenant").asString());
    req = req.header("X-Roles", String.join(",", jwt.getClaim("roles").asList(String.class)));
    if (!jwt.getClaim("perms").isMissing()) {
      var perms = jwt.getClaim("perms").asList(String.class);
      req = req.header("X-Permissions", perms.isEmpty() ? "-" : String.join(",", perms));
    }
    return req.get(String.class);
  }

  @Test
  void aCustomRoleMintsItsPermissionsAndAPlainTierMintsNone() {
    String tenant = com.storeql.ids.Ids.newId().toString();
    String store = com.storeql.ids.Ids.newId().toString();
    // A plain cashier: no claim at all — judged by the tier's defaults, as before.
    String plain = provisionedUserId(tenant, "plain-cashier@example.com");
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            plain,
            store,
            "CASHIER",
            null,
            null));
    assertThat(claimsOf("plain-cashier@example.com"), not(containsString("\"perms\"")));
    assertThat(
        meOf("plain-cashier@example.com"),
        containsString("\"permissions\":[\"purchasing.approve\",\"till.no_sale\"]"));

    // A trainee: a cashier narrowed to nothing. The claim is present and empty, and /auth/me
    // shows nothing — not the cashier's drawer.
    String trainee = provisionedUserId(tenant, "trainee@example.com");
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            trainee,
            store,
            "CASHIER",
            "TRAINEE",
            "[]"));
    assertThat(claimsOf("trainee@example.com"), containsString("\"perms\":[]"));
    assertThat(meOf("trainee@example.com"), containsString("\"permissions\":[]"));

    // A shift lead: a manager who may approve purchases and nothing else; sorted in the claim.
    String lead = provisionedUserId(tenant, "lead@example.com");
    String assigned = com.storeql.ids.Ids.newId().toString();
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            assigned,
            tenant,
            lead,
            store,
            "MANAGER",
            "SHIFT_LEAD",
            "[\"purchasing.approve\",\"finance.journal\"]"));
    String claims = claimsOf("lead@example.com");
    assertThat(claims, containsString("\"perms\":[\"finance.journal\",\"purchasing.approve\"]"));
    assertThat(claims, containsString("\"roles\":["));
    assertThat(claims, containsString("MANAGER"));
    // Redelivered: nothing changes.
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            assigned,
            tenant,
            lead,
            store,
            "MANAGER",
            "SHIFT_LEAD",
            "[\"sales.void\"]"));
    assertThat(claimsOf("lead@example.com"), not(containsString("sales.void")));
  }

  @Test
  void aRoleRedefinedReachesItsHoldersAtTheirNextLogin() {
    String tenant = com.storeql.ids.Ids.newId().toString();
    String other = com.storeql.ids.Ids.newId().toString();
    String store = com.storeql.ids.Ids.newId().toString();
    String lead = provisionedUserId(tenant, "redefined@example.com");
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            lead,
            store,
            "MANAGER",
            "SHIFT_LEAD",
            "[\"purchasing.approve\"]"));
    String defined = com.storeql.ids.Ids.newId().toString();
    roleDefined.handle(
        roleEvent(defined, tenant, "SHIFT_LEAD", "[\"purchasing.approve\",\"sales.void\"]"));
    assertThat(
        claimsOf("redefined@example.com"),
        containsString("\"perms\":[\"purchasing.approve\",\"sales.void\"]"));
    // Redelivered: still the same set. Another tenant's role of the same code: not ours.
    roleDefined.handle(roleEvent(defined, tenant, "SHIFT_LEAD", "[]"));
    roleDefined.handle(
        roleEvent(com.storeql.ids.Ids.newId().toString(), other, "SHIFT_LEAD", "[]"));
    assertThat(claimsOf("redefined@example.com"), containsString("sales.void"));
    // Narrowed to nothing, one second later: the claim is present and empty.
    roleDefined.handle(
        roleEvent(
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            "SHIFT_LEAD",
            "[]",
            "2026-09-13T12:00:01Z"));
    assertThat(claimsOf("redefined@example.com"), containsString("\"perms\":[]"));
    // A definition older than the one applied, arriving late — two redefinitions in one second can
    // land in either order — changes nothing.
    roleDefined.handle(
        roleEvent(
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            "SHIFT_LEAD",
            "[\"sales.void\"]",
            "2026-09-13T11:59:59Z"));
    assertThat(claimsOf("redefined@example.com"), containsString("\"perms\":[]"));
    // Malformed, and the wrong event type: skipped, not thrown.
    roleDefined.handle("{\"eventType\":\"RoleDefined\"}");
    roleDefined.handle("{\"eventType\":\"SomethingElse\",\"eventId\":\"x\"}");
    roleDefined.handle("not json");
  }

  @Test
  void aRemovedAssignmentLeavesTheLogin() {
    // SJ-D51: removing an assignment in tenant-svc used to leave the role on the login for good.
    String tenant = com.storeql.ids.Ids.newId().toString();
    String store = com.storeql.ids.Ids.newId().toString();
    String otherStore = com.storeql.ids.Ids.newId().toString();
    // A shopper taken on before 29 Sep 2026: the login that goes back to being a shopper's when
    // its last role goes. (One made in the business by provisioning stays in it: BusinessSignUpIT.)
    String cashier = registerAndGetUserId("unassigned@example.com");
    stampedInto(cashier, tenant);
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            cashier,
            store,
            "CASHIER",
            null,
            null));
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            cashier,
            otherStore,
            "CASHIER",
            null,
            null));
    assertThat(claimsOf("unassigned@example.com"), containsString("CASHIER"));
    String removed = com.storeql.ids.Ids.newId().toString();
    staffRemoved.handle(
        staffEvent("StaffRemoved", removed, tenant, cashier, store, "CASHIER", null, null));
    String claims = claimsOf("unassigned@example.com");
    // Still a cashier — at the other store only.
    assertThat(claims, containsString("CASHIER"));
    assertThat(claims, containsString(otherStore));
    assertThat(claims, not(containsString(store)));
    staffRemoved.handle(
        staffEvent(
            "StaffRemoved",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            cashier,
            otherStore,
            "CASHIER",
            null,
            null));
    String last = claimsOf("unassigned@example.com");
    assertThat(last, not(containsString("CASHIER")));
    // The last staff role gone, the login is no longer the tenant's: a shopper's token naming a
    // tenant would make the gateway record their next order at another shop against this one.
    assertThat(last, not(containsString("\"tenant\"")));
    assertThat(last, containsString("\"type\":\"CUSTOMER\""));
    // Redelivered and malformed: no-ops.
    staffRemoved.handle(
        staffEvent("StaffRemoved", removed, tenant, cashier, store, "CASHIER", null, null));
    staffRemoved.handle("{\"eventType\":\"StaffRemoved\"}");
    staffRemoved.handle("{\"eventType\":\"StaffAssigned\",\"eventId\":\"x\"}");
  }

  @Test
  void anOwnerIsNeverNarrowed() throws Exception {
    String tenant = com.storeql.ids.Ids.newId().toString();
    String store = com.storeql.ids.Ids.newId().toString();
    String owner = registerAndGetUserId("narrow-owner@example.com");
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                    + " SELECT ?, ?, id, NULL FROM roles WHERE name = 'OWNER'")) {
      ps.setObject(1, com.storeql.ids.Ids.newId());
      ps.setObject(2, Ids.parse(owner));
      ps.executeUpdate();
    }
    // The owner's login is the tenant's, or no StaffAssigned would bind it at all.
    stampedInto(owner, tenant);
    // An owner who is also given a narrowed role somewhere still carries no claim: the owner is
    // the tenant's root, and a custom role narrows staff, not them.
    staffAssigned.handle(
        staffEvent(
            "StaffAssigned",
            com.storeql.ids.Ids.newId().toString(),
            tenant,
            owner,
            store,
            "CASHIER",
            "TRAINEE",
            "[]"));
    assertThat(claimsOf("narrow-owner@example.com"), not(containsString("\"perms\"")));
    assertThat(meOf("narrow-owner@example.com"), containsString("staff.manage"));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }

  /**
   * Storefront trust slice 1: a sign-in that names its kind opens only that kind, and an address
   * holding only the other kind answers exactly as a wrong password; naming none is unchanged.
   */
  @Test
  void aSignInThatNamesItsKindNeverOpensTheOther() throws Exception {
    String tail = Ids.newId().toString().replace("-", "");
    tail = tail.substring(tail.length() - 12);
    String pw = "strongpass1 for storeql";
    String shopperOnly = "shopper-" + tail + "@example.com";
    String staffOnly = "staff-" + tail + "@example.com";
    String both = "both-" + tail + "@example.com";
    String tmp1 = "tmp1-" + tail + "@example.com";
    String tmp2 = "tmp2-" + tail + "@example.com";
    for (String e : new String[] {shopperOnly, both, tmp1}) {
      assertThat(
          post("/auth/register", "{\"email\":\"" + e + "\",\"password\":\"" + pw + "\"}")
              .getStatus(),
          is(201));
    }
    assertThat(
        post("/auth/register", "{\"email\":\"" + tmp2 + "\",\"password\":\"" + pw + "\"}")
            .getStatus(),
        is(201));
    // Turn two of the shoppers' logins into business logins, the second at an address that also
    // holds a shopper's.
    try (var c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET type = 'STAFF', email = ? WHERE email = ?")) {
      ps.setString(1, staffOnly);
      ps.setString(2, tmp1);
      ps.executeUpdate();
      ps.setString(1, both);
      ps.setString(2, tmp2);
      ps.executeUpdate();
    }
    java.util.function.BiFunction<String, String, Response> login =
        (email, kind) ->
            post(
                "/auth/login",
                "{\"email\":\""
                    + email
                    + "\",\"password\":\""
                    + pw
                    + "\""
                    + (kind == null ? "" : ",\"accountType\":\"" + kind + "\"")
                    + "}");

    // Wrong kind for an address holding only the other: the answer of a wrong password.
    Response wrongPassword =
        post("/auth/login", "{\"email\":\"" + shopperOnly + "\",\"password\":\"nope nope nope\"}");
    for (Response r :
        new Response[] {login.apply(shopperOnly, "STAFF"), login.apply(staffOnly, "CUSTOMER")}) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(401));
      assertThat(body, containsString("INVALID_CREDENTIALS"));
      assertThat(body, not(containsString("accessToken")));
    }
    assertThat(wrongPassword.getStatus(), is(401));
    assertThat(wrongPassword.readEntity(String.class), containsString("INVALID_CREDENTIALS"));

    // The right kind signs in.
    assertThat(login.apply(shopperOnly, "CUSTOMER").getStatus(), is(200));
    assertThat(login.apply(staffOnly, "STAFF").getStatus(), is(200));

    // An address holding both: each kind opens its own account, never the other's.
    String asShopper = login.apply(both, "CUSTOMER").readEntity(String.class);
    String asStaff = login.apply(both, "STAFF").readEntity(String.class);
    String shopperSub = JWT.decode(extract(asShopper, "accessToken")).getSubject();
    String staffSub = JWT.decode(extract(asStaff, "accessToken")).getSubject();
    assertThat(shopperSub.equals(staffSub), is(false));

    // Naming no kind is unchanged: staff first, the other kind when none exists.
    assertThat(login.apply(shopperOnly, null).getStatus(), is(200));
    assertThat(login.apply(staffOnly, null).getStatus(), is(200));
    assertThat(
        JWT.decode(extract(login.apply(both, null).readEntity(String.class), "accessToken"))
            .getSubject(),
        is(staffSub));
  }
}
