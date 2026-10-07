package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.iam.messaging.StaffAssignedHandler;
import com.storeql.iam.messaging.StaffRemovedHandler;
import com.storeql.iam.service.AuthService;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A business-wide staff assignment (head office): a MANAGER-tier role with no store. The event
 * names no store and says {@code "businessWide":true}; the login's token then carries no store ids
 * (held to no store, as an owner is), business-wide wins over stores the person also holds, and
 * taking the assignment away puts the stores (or nothing) back at the next sign-in.
 */
@HelidonTest
class BusinessWideStaffIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";

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
  @Inject AuthService auth;
  @Inject StaffAssignedHandler assigned;
  @Inject StaffRemovedHandler removed;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private UUID login(UUID tenant, String email) {
    return Ids.parse(auth.provisionStaff(tenant, email, PASSWORD).userId());
  }

  private DecodedJWT token(String email) {
    Response r =
        target
            .path("/auth/login")
            .request()
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return JWT.decode(
        Json.createReader(new StringReader(body))
            .readObject()
            .getJsonObject("data")
            .getString("accessToken"));
  }

  private static List<String> stores(DecodedJWT t) {
    var c = t.getClaim("storeIds");
    return c.isMissing() || c.isNull() ? List.of() : c.asList(String.class);
  }

  private static List<String> roles(DecodedJWT t) {
    return t.getClaim("roles").asList(String.class);
  }

  private static List<String> perms(DecodedJWT t) {
    var c = t.getClaim("perms");
    return c.isMissing() || c.isNull() ? null : c.asList(String.class);
  }

  private static String store(UUID tenant, UUID user, UUID store, String role, String custom) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"StaffAssigned\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + user
        + "\",\"occurredAt\":\"2026-09-30T10:00:00Z\",\"userId\":\""
        + user
        + "\",\"storeId\":\""
        + store
        + "\",\"role\":\""
        + role
        + "\""
        + custom
        + "}";
  }

  private static String wide(UUID tenant, UUID user, String role, String custom, UUID eventId) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"StaffAssigned\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + user
        + "\",\"occurredAt\":\"2026-09-30T10:00:00Z\",\"userId\":\""
        + user
        + "\",\"businessWide\":true,\"role\":\""
        + role
        + "\""
        + custom
        + "}";
  }

  private static String custom(String code, String perms) {
    return ",\"roleCode\":\""
        + code
        + "\",\"permissions\":["
        + perms
        + "],\"roleUpdatedAt\":\"2026-09-30T10:00:00Z\"";
  }

  private static String unwide(UUID tenant, UUID user, String role) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"StaffRemoved\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + user
        + "\",\"occurredAt\":\"2026-09-30T10:00:00Z\",\"userId\":\""
        + user
        + "\",\"businessWide\":true,\"role\":\""
        + role
        + "\"}";
  }

  private static String unstore(UUID tenant, UUID user, UUID store, String role) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"StaffRemoved\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + user
        + "\",\"occurredAt\":\"2026-09-30T10:00:00Z\",\"userId\":\""
        + user
        + "\",\"storeId\":\""
        + store
        + "\",\"role\":\""
        + role
        + "\"}";
  }

  private static long rows(UUID user, String roleName) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM iam.user_roles ur JOIN iam.roles r ON r.id = ur.role_id"
                    + " WHERE ur.user_id = ?::uuid AND r.name = ? AND ur.store_id IS NULL")) {
      ps.setString(1, user.toString());
      ps.setString(2, roleName);
      var rs = ps.executeQuery();
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  @DisplayName(
      "A business-wide manager's token carries no store ids and the manager's roles and permissions; removal brings the stores back")
  void aBusinessWideManagersToken() throws Exception {
    UUID tenant = Ids.newId();
    UUID s1 = Ids.newId();
    UUID s2 = Ids.newId();
    String email = "head-office-1@example.com";
    UUID user = login(tenant, email);

    // Held to two stores first.
    assigned.handle(store(tenant, user, s1, "MANAGER", ""));
    assigned.handle(store(tenant, user, s2, "MANAGER", ""));
    DecodedJWT held = token(email);
    assertThat(roles(held), contains("MANAGER"));
    assertThat(stores(held), containsInAnyOrder(s1.toString(), s2.toString()));

    // Business-wide wins over the stores they also hold.
    assigned.handle(wide(tenant, user, "MANAGER", "", Ids.newId()));
    DecodedJWT wideToken = token(email);
    assertThat(roles(wideToken), contains("MANAGER"));
    assertThat("held to no store", stores(wideToken), is(empty()));

    // Removing the business-wide assignment puts them back to their stores at the next sign-in.
    removed.handle(unwide(tenant, user, "MANAGER"));
    DecodedJWT back = token(email);
    assertThat(roles(back), contains("MANAGER"));
    assertThat(stores(back), containsInAnyOrder(s1.toString(), s2.toString()));
    assertThat(rows(user, "MANAGER"), is(0L));

    // Removing their stores as well leaves the login in the business, holding no role.
    removed.handle(unstore(tenant, user, s1, "MANAGER"));
    removed.handle(unstore(tenant, user, s2, "MANAGER"));
    DecodedJWT none = token(email);
    assertThat(roles(none), is(empty()));
    assertThat(stores(none), is(empty()));
  }

  @Test
  @DisplayName(
      "A custom manager-tier role held business-wide carries exactly its permissions and no store")
  void aCustomBusinessWideRole() throws Exception {
    UUID tenant = Ids.newId();
    String email = "head-office-2@example.com";
    UUID user = login(tenant, email);
    UUID grant = Ids.newId();
    String event =
        wide(
            tenant,
            user,
            "MANAGER",
            custom("HEAD_OFFICE", "\"finance.journal\",\"purchasing.approve\""),
            grant);
    assigned.handle(event);
    // The same event again, and the same role granted afresh: one row, never two.
    assigned.handle(event);
    assigned.handle(
        wide(
            tenant,
            user,
            "MANAGER",
            custom("HEAD_OFFICE", "\"finance.journal\",\"purchasing.approve\",\"staff.manage\""),
            Ids.newId()));
    assertThat(rows(user, "MANAGER"), is(1L));

    DecodedJWT t = token(email);
    assertThat(roles(t), contains("MANAGER"));
    assertThat(stores(t), is(empty()));
    assertThat(
        perms(t), containsInAnyOrder("finance.journal", "purchasing.approve", "staff.manage"));

    removed.handle(unwide(tenant, user, "MANAGER"));
    DecodedJWT gone = token(email);
    assertThat(roles(gone), is(empty()));
    assertThat(perms(gone), is((List<String>) null));
    assertThat(rows(user, "MANAGER"), is(0L));
  }

  @Test
  @DisplayName(
      "Only the MANAGER tier goes business-wide; another business's login is refused; a store removal leaves the wide one")
  void refusalsAndIndependence() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID s1 = Ids.newId();
    String cashierEmail = "head-office-3@example.com";
    UUID cashier = login(tenant, cashierEmail);
    assigned.handle(wide(tenant, cashier, "CASHIER", "", Ids.newId()));
    assigned.handle(wide(tenant, cashier, "STOREKEEPER", "", Ids.newId()));
    assertThat("no role was bound", roles(token(cashierEmail)), is(empty()));

    // Another business's login cannot be made head office by naming it.
    String strangerEmail = "head-office-4@example.com";
    UUID stranger = login(other, strangerEmail);
    assigned.handle(wide(tenant, stranger, "MANAGER", "", Ids.newId()));
    assertThat(roles(token(strangerEmail)), is(empty()));
    assertThat(rows(stranger, "MANAGER"), is(0L));

    // A store assignment removed leaves the business-wide one, and the person stays unrestricted.
    String mixedEmail = "head-office-5@example.com";
    UUID mixed = login(tenant, mixedEmail);
    assigned.handle(store(tenant, mixed, s1, "MANAGER", ""));
    assigned.handle(wide(tenant, mixed, "MANAGER", "", Ids.newId()));
    removed.handle(unstore(tenant, mixed, s1, "MANAGER"));
    DecodedJWT still = token(mixedEmail);
    assertThat(roles(still), contains("MANAGER"));
    assertThat(stores(still), is(empty()));
    assertThat(rows(mixed, "MANAGER"), is(1L));
  }
}
