package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import com.storeql.iam.messaging.StaffAssignedHandler;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Staff by name, not by id: {@code GET /auth/admin/staff-users?ids=} answers the login email of
 * each of the caller's business's staff among the ids, and nothing about anybody else's — another
 * business's staff, a customer, an id nobody holds. Management only; the business is the token's.
 */
@HelidonTest
class StaffUsersIT {

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

  private static final String PASSWORD = "strongpass1 for storeql";
  private static final String PATH = "/auth/admin/staff-users";

  private static final UUID OURS = Ids.newId();
  private static final UUID OUR_STORE = Ids.newId();
  private static final UUID THEIRS = Ids.newId();
  private static final UUID THEIR_STORE = Ids.newId();

  @Inject WebTarget target;
  @Inject StaffAssignedHandler staffAssigned;
  @Inject UserRepository users;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /** Who is asking, as the gateway forwards it. */
  private record Caller(UUID tenantId, String roles, UUID storeId) {}

  private static Caller of(UUID tenant, String role) {
    return new Caller(tenant, role, null);
  }

  private Response lookup(Caller who, String ids) {
    Invocation.Builder b =
        WebTargets.at(target, ids == null ? PATH : PATH + "?ids=" + ids).request();
    b = b.header("X-User-Id", Ids.newId().toString());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId().toString());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.storeId() != null) b = b.header("X-Store-Ids", who.storeId().toString());
    return b.get();
  }

  /** The answer as userId → email, in the order it came. */
  private static Map<String, String> named(Response r) {
    JsonArray data = Envelopes.okArray(r);
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (JsonObject o : data.getValuesAs(JsonObject.class)) {
      out.put(o.getString("userId"), o.getString("email"));
    }
    return out;
  }

  private static String csv(UUID... ids) {
    return java.util.Arrays.stream(ids).map(UUID::toString).collect(Collectors.joining(","));
  }

  /** Provisions a staff login as the business's owner, then assigns it at a store. */
  private UUID staff(UUID tenant, UUID store, String email, String role) {
    Response r =
        target
            .path(PATH)
            .request()
            .header("X-Tenant-Id", tenant.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                    MediaType.APPLICATION_JSON));
    UUID userId = Ids.parse(Envelopes.ok(r).getString("userId"));
    staffAssigned.handle(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"StaffAssigned\",\"tenantId\":\""
            + tenant
            + "\",\"aggregateId\":\""
            + userId
            + "\",\"occurredAt\":\"2026-09-25T00:00:00Z\",\"userId\":\""
            + userId
            + "\",\"storeId\":\""
            + store
            + "\",\"role\":\""
            + role
            + "\"}");
    return userId;
  }

  /**
   * Provisions a staff login bound directly to a business-wide role ({@code store_id IS NULL}) —
   * what a tenant-wide OWNER or MANAGER holds. There is no HTTP path to this today (tenant-svc's
   * own {@code assignStaff} always names a store), so it is bound the way {@link
   * StaffAssignedHandler} would with one, but with no store.
   */
  private UUID wideStaff(UUID tenant, String email, String role) {
    Response r =
        target
            .path(PATH)
            .request()
            .header("X-Tenant-Id", tenant.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                    MediaType.APPLICATION_JSON));
    UUID userId = Ids.parse(Envelopes.ok(r).getString("userId"));
    assertThat(
        users.bindStaffOnce(Ids.newId(), "staff-users-it", userId, tenant, role, null), is(true));
    return userId;
  }

  /** Binds an existing login to a store role, as tenant-svc's {@code StaffAssigned} would. */
  private void assign(UUID tenant, UUID store, UUID userId, String role) {
    staffAssigned.handle(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"StaffAssigned\",\"tenantId\":\""
            + tenant
            + "\",\"aggregateId\":\""
            + userId
            + "\",\"occurredAt\":\"2026-09-25T00:00:00Z\",\"userId\":\""
            + userId
            + "\",\"storeId\":\""
            + store
            + "\",\"role\":\""
            + role
            + "\"}");
  }

  private UUID customer(String email) {
    Response r =
        target
            .path("/auth/register")
            .request()
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                    MediaType.APPLICATION_JSON));
    String access = Envelopes.created(r).getString("accessToken");
    return Ids.parse(com.auth0.jwt.JWT.decode(access).getSubject());
  }

  /** Every login row as it stands, to prove a lookup changed nothing. */
  private static String users() {
    return Envelopes.scalar(
        PG,
        "SELECT string_agg(id || '|' || coalesce(tenant_id::text, '-') || '|' || type || '|'"
            + " || coalesce(email, '-') || '|' || status || '|' || updated_at, ',' ORDER BY id)"
            + " FROM iam.users");
  }

  private static String roles() {
    return Envelopes.scalar(
        PG,
        "SELECT string_agg(user_id || '|' || role_id || '|' || coalesce(store_id::text, '-'), ','"
            + " ORDER BY user_id, role_id) FROM iam.user_roles");
  }

  @Test
  void theBusinessNamesItsOwnStaffAndNobodyElse() {
    UUID ana = staff(OURS, OUR_STORE, "ana@ours.test", "CASHIER");
    UUID ben = staff(OURS, OUR_STORE, "ben@ours.test", "STOREKEEPER");
    UUID stranger = staff(THEIRS, THEIR_STORE, "sam@theirs.test", "MANAGER");
    UUID shopper = customer("shopper@example.com");
    // Provisioned but never assigned: not yet anybody's staff.
    UUID pending =
        Ids.parse(
            Envelopes.ok(
                    target
                        .path(PATH)
                        .request()
                        .header("X-Tenant-Id", OURS.toString())
                        .header("X-Roles", "OWNER")
                        .post(
                            Entity.entity(
                                "{\"email\":\"pending@ours.test\",\"password\":\""
                                    + PASSWORD
                                    + "\"}",
                                MediaType.APPLICATION_JSON)))
                .getString("userId"));
    UUID nobody = Ids.newId();
    String ids = csv(ana, stranger, shopper, nobody, pending, ben, ana);

    Map<String, String> expected = new TreeMap<>();
    expected.put(ana.toString(), "ana@ours.test");
    expected.put(ben.toString(), "ben@ours.test");

    for (String role : new String[] {"OWNER", "MANAGER", "PLATFORM_ADMIN"}) {
      Map<String, String> got = named(lookup(of(OURS, role), ids));
      assertThat(role, new TreeMap<>(got), is(expected));
      assertThat(role + ": each named once", got.size(), is(2));
    }

    // The other business names its own manager, and none of ours.
    assertThat(
        named(lookup(of(THEIRS, "OWNER"), ids)),
        is(Map.of(stranger.toString(), "sam@theirs.test")));
  }

  @Test
  void anotherBusinessLearnsNothingOfOurs() {
    UUID cara = staff(OURS, OUR_STORE, "cara@ours.test", "MANAGER");
    UUID dan = staff(OURS, OUR_STORE, "dan@ours.test", "CASHIER");
    String ours = csv(cara, dan);
    String usersBefore = users();
    String rolesBefore = roles();

    // Their management, even scoped to our store: an empty answer, not an error that says the
    // ids exist.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(role, named(lookup(of(THEIRS, role), ours)).keySet(), is(empty()));
      assertThat(
          role + " @ our store",
          named(lookup(new Caller(THEIRS, role, OUR_STORE), ours)).keySet(),
          is(empty()));
    }
    // Their till and warehouse staff are not management at all.
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      for (Caller who : new Caller[] {of(THEIRS, role), new Caller(THEIRS, role, OUR_STORE)}) {
        Response r = lookup(who, ours);
        String body = r.readEntity(String.class);
        assertThat(role + ": " + body, r.getStatus(), is(403));
        assertThat(body, containsString("FORBIDDEN"));
        assertThat(body.contains("@ours.test"), is(false));
      }
    }
    // A shopper, with or without a business in the request.
    assertThat(lookup(of(null, "CUSTOMER"), ours).getStatus(), is(403));
    assertThat(lookup(of(OURS, "CUSTOMER"), ours).getStatus(), is(403));

    assertThat(users(), is(usersBefore));
    assertThat(roles(), is(rolesBefore));
  }

  @Test
  void ourOwnTillAndWarehouseStaffAreRefused() {
    UUID eve = staff(OURS, OUR_STORE, "eve@ours.test", "MANAGER");
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response r = lookup(new Caller(OURS, role, OUR_STORE), csv(eve));
      String body = r.readEntity(String.class);
      assertThat(role + ": " + body, r.getStatus(), is(403));
      assertThat(body.contains("eve@ours.test"), is(false));
    }
    assertThat(lookup(of(OURS, null), csv(eve)).getStatus(), is(403));
  }

  @Test
  @DisplayName(
      "A caller held to a store names staff at it and business-wide staff, never another"
          + " store's; another business names none of ours even holding our store id")
  void namingIsScopedByTheCallersStores() {
    UUID otherStore = Ids.newId();
    UUID atOurStore = staff(OURS, OUR_STORE, "at-our-store@ours.test", "CASHIER");
    UUID atOtherStore = staff(OURS, otherStore, "at-other-store@ours.test", "CASHIER");
    UUID wide = wideStaff(OURS, "wide@ours.test", "MANAGER");
    // A shopper first, then staff at the other store only: the CUSTOMER role from signing up is
    // held at no store, and must not read as business-wide (found by the k6 flow on the stack).
    UUID shopperFirst = customer("shopper-first@ours.test");
    assign(OURS, otherStore, shopperFirst, "STOREKEEPER");
    String ids = csv(atOurStore, atOtherStore, wide, shopperFirst);

    // Held to OUR_STORE only: the login at that store and the business-wide one, never the
    // other store's — whatever else its login holds.
    Map<String, String> scoped = named(lookup(new Caller(OURS, "MANAGER", OUR_STORE), ids));
    assertThat(scoped.keySet(), is(Set.of(atOurStore.toString(), wide.toString())));

    // Held to no store at all: every one of them, as today.
    Map<String, String> unrestricted = named(lookup(of(OURS, "OWNER"), ids));
    assertThat(
        unrestricted.keySet(),
        is(
            Set.of(
                atOurStore.toString(),
                atOtherStore.toString(),
                wide.toString(),
                shopperFirst.toString())));

    // Another business, even naming our ids and holding the same store id as their own scope,
    // names none of ours — the tenant is the first condition, before any store filter runs.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          role, named(lookup(new Caller(THEIRS, role, OUR_STORE), ids)).keySet(), is(empty()));
    }
  }

  @Test
  void aBusinessIsNeededToAsk() {
    // A platform administrator acting for no business has no staff to name.
    Response r = lookup(of(null, "PLATFORM_ADMIN"), csv(Ids.newId()));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(401));
    assertThat(body, containsString("NO_TENANT"));
  }

  @Test
  void theIdsAreReadStrictly() {
    Caller owner = of(OURS, "OWNER");
    // None asked: none named.
    assertThat(named(lookup(owner, null)).keySet(), is(empty()));
    assertThat(named(lookup(owner, "")).keySet(), is(empty()));

    // A hundred is the most one request names; a hundred and one is refused whole.
    List<UUID> hundred = new ArrayList<>();
    for (int i = 0; i < 100; i++) hundred.add(Ids.newId());
    assertThat(named(lookup(owner, csv(hundred.toArray(UUID[]::new)))).keySet(), is(empty()));
    hundred.add(Ids.newId());
    Response tooMany = lookup(owner, csv(hundred.toArray(UUID[]::new)));
    String why = tooMany.readEntity(String.class);
    assertThat(why, tooMany.getStatus(), is(400));
    assertThat(why, containsString("STAFF_IDS_TOO_MANY"));

    // One id that is not a UUIDv7 — a version 4, or not an id at all — refuses the request.
    for (String bad : new String[] {"0b6d7d5e-3c1a-4f4e-9b8e-5f1a2c3d4e5f", "not-an-id"}) {
      Response r = lookup(owner, Ids.newId() + "," + bad);
      String body = r.readEntity(String.class);
      assertThat(bad + ": " + body, r.getStatus(), is(400));
      assertThat(body, containsString("INVALID_UUID"));
    }
  }
}
