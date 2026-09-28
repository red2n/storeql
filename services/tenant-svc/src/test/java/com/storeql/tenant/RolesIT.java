package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Custom roles (20.10): a tenant defines a role on a tier holding fewer of that tier's permissions,
 * assigns staff to it, redefines it, and cannot delete it while it is held; the assignment and
 * removal events carry what iam-svc needs (SJ-D51); and the refusals and the abuse around each.
 */
@HelidonTest
class RolesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String OWNER = "01a090ae-611e-700b-bde4-50df0324c37c";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** A tenant with one store, both ids returned. */
  private String[] tenantWithStore(String name) {
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"" + name + "\",\"country\":\"GB\",\"currency\":\"GBP\"}",
            null,
            // A login owns one business (21.13), so each business here has its own owner.
            Ids.newId().toString(),
            null);
    assertThat(t.getStatus(), is(201));
    String tenantId = data(t.readEntity(String.class)).getString("id");
    Response s =
        post(
            "/admin/stores",
            "{\"name\":\"Main\",\"code\":\"MAIN-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"1 High St\",\"country\":\"GB\",\"city\":\"London\",\"postcode\":\"E1 6AN\",\"timezone\":\"Europe/London\"}",
            tenantId,
            OWNER,
            "OWNER");
    assertThat(s.readEntity(String.class), s.getStatus(), is(201));
    Response list = get("/admin/stores", tenantId, "OWNER", null);
    String storeId = data(list.readEntity(String.class)).getString("id");
    return new String[] {tenantId, storeId};
  }

  private static String define(String code, String tier, String perms) {
    return "{\"code\":\""
        + code
        + "\",\"name\":\""
        + code.toLowerCase()
        + "\",\"baseTier\":\""
        + tier
        + "\",\"permissions\":"
        + perms
        + ",\"description\":\"test\"}";
  }

  @Test
  @DisplayName(
      "A role is defined on a tier with fewer permissions, listed beside the built-in ones, read and redefined")
  void aRoleIsDefinedListedAndRedefined() throws Exception {
    String[] ts = tenantWithStore("Roles Ltd");
    String tenant = ts[0];
    Response r =
        post(
            "/admin/roles",
            define("shift_lead", "manager", "[\"purchasing.approve\",\"finance.journal\"]"),
            tenant,
            OWNER,
            "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject role = data(body);
    assertThat(role.getString("code"), is("SHIFT_LEAD"));
    assertThat(role.getString("baseTier"), is("MANAGER"));
    assertThat(role.getBoolean("custom"), is(true));
    assertThat(
        role.getJsonArray("permissions").toString(),
        is("[\"finance.journal\",\"purchasing.approve\"]"));
    // The event iam-svc binds on carries the tier, the code and the set.
    assertThat(
        outbox(tenant, "RoleDefined"),
        containsString(
            "\"code\":\"SHIFT_LEAD\",\"baseTier\":\"MANAGER\",\"permissions\":[\"finance.journal\",\"purchasing.approve\"]"));

    JsonArray listed =
        Json.createReader(
                new StringReader(
                    get("/admin/roles", tenant, "OWNER", null).readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(listed.getJsonObject(0).getString("code"), is("OWNER"));
    assertThat(listed.getJsonObject(0).getBoolean("custom"), is(false));
    assertThat(listed.getJsonObject(1).getString("code"), is("MANAGER"));
    // The permissions catalogue common-web publishes: thirteen since stock.transfer (SJ-D73).
    assertThat(listed.getJsonObject(1).getJsonArray("permissions").size(), is(13));
    assertThat(listed.getJsonObject(3).getString("code"), is("CASHIER"));
    assertThat(
        listed.getJsonObject(3).getJsonArray("permissions").toString(),
        is("[\"purchasing.approve\",\"till.no_sale\"]"));
    assertThat(listed.getJsonObject(4).getString("code"), is("SHIFT_LEAD"));
    assertThat(listed.size(), is(5));

    JsonObject read =
        data(get("/admin/roles/shift_lead", tenant, "MANAGER", null).readEntity(String.class));
    assertThat(read.getString("name"), is("shift_lead"));

    Response updated =
        put(
            "/admin/roles/SHIFT_LEAD",
            "{\"name\":\"Shift lead\",\"permissions\":[\"purchasing.approve\"],\"description\":\"\"}",
            tenant,
            OWNER,
            "OWNER");
    String ub = updated.readEntity(String.class);
    assertThat(ub, updated.getStatus(), is(200));
    assertThat(data(ub).getString("name"), is("Shift lead"));
    assertThat(data(ub).getJsonArray("permissions").toString(), is("[\"purchasing.approve\"]"));
    assertThat(data(ub).containsKey("description"), is(false));
    assertThat(
        outbox(tenant, "RoleDefined"),
        containsString("\"permissions\":[\"purchasing.approve\"],\"updatedAt\":\""));

    // The catalogue: every permission with the tiers holding it by default.
    JsonArray catalogue =
        Json.createReader(
                new StringReader(
                    get("/admin/roles/permissions", tenant, "MANAGER", null)
                        .readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(catalogue.size(), is(13));
    boolean sawNoSale = false;
    for (JsonValue v : catalogue) {
      JsonObject p = v.asJsonObject();
      if ("till.no_sale".equals(p.getString("code"))) {
        sawNoSale = true;
        assertThat(p.getJsonArray("defaultFor").toString(), is("[\"CASHIER\",\"MANAGER\"]"));
      }
    }
    assertThat(sawNoSale, is(true));
  }

  @Test
  @DisplayName("Every way a role can be wrong is refused by name, and none of them is stored")
  void badRolesAreRefused() {
    String tenant = tenantWithStore("Bad Roles Ltd")[0];
    assertRefused(tenant, define("shift lead", "MANAGER", "[]"), 400, "ROLE_CODE_INVALID");
    assertRefused(tenant, define("S", "MANAGER", "[]"), 400);
    assertRefused(tenant, define("MANAGER", "MANAGER", "[]"), 400, "ROLE_CODE_RESERVED");
    assertRefused(tenant, define("owner", "MANAGER", "[]"), 400, "ROLE_CODE_RESERVED");
    assertRefused(tenant, define("PLATFORM_ADMIN", "MANAGER", "[]"), 400, "ROLE_CODE_RESERVED");
    assertRefused(tenant, define("GOD", "OWNER", "[]"), 400, "ROLE_TIER_INVALID");
    assertRefused(tenant, define("GOD", "PLATFORM_ADMIN", "[]"), 400, "ROLE_TIER_INVALID");
    assertRefused(
        tenant,
        define("BOSS", "CASHIER", "[\"orders.everything\"]"),
        400,
        "ROLE_PERMISSION_UNKNOWN");
    // A cashier who can void sales is a manager, not a cashier: a role only narrows its tier.
    assertRefused(
        tenant,
        define("SUPER_CASHIER", "CASHIER", "[\"sales.void\"]"),
        400,
        "ROLE_PERMISSION_OUTSIDE_TIER");
    assertRefused(
        tenant,
        define("SUPER_KEEPER", "STOREKEEPER", "[\"till.no_sale\"]"),
        400,
        "ROLE_PERMISSION_OUTSIDE_TIER");
    assertRefused(tenant, "{\"code\":\"X_ROLE\",\"name\":\"x\",\"baseTier\":\"MANAGER\"}", 400);
    assertRefused(
        tenant,
        "{\"code\":\"X_ROLE\",\"name\":\"\",\"baseTier\":\"MANAGER\",\"permissions\":[]}",
        400);
    StringBuilder many = new StringBuilder("[");
    for (int i = 0; i < 51; i++) many.append(i > 0 ? "," : "").append("\"sales.void\"");
    assertRefused(tenant, define("X_ROLE", "MANAGER", many + "]"), 400);
    assertRefused(tenant, define("X_ROLE", "MANAGER", "[\"" + "x".repeat(65) + "\"]"), 400);
    // Nothing landed.
    JsonArray listed =
        Json.createReader(
                new StringReader(
                    get("/admin/roles", tenant, "OWNER", null).readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(listed.size(), is(4));
    assertThat(get("/admin/roles/X_ROLE", tenant, "OWNER", null).getStatus(), is(404));
    assertThat(
        put("/admin/roles/X_ROLE", "{\"name\":\"x\",\"permissions\":[]}", tenant, OWNER, "OWNER")
            .getStatus(),
        is(404));
    assertThat(delete("/admin/roles/X_ROLE", tenant, OWNER, "OWNER").getStatus(), is(404));
  }

  @Test
  @DisplayName(
      "Roles are finance-and-people work: a storekeeper, a cashier and a narrowed manager cannot define one, and the rival shop sees none")
  void whoMayDefineRoles() {
    String tenant = tenantWithStore("Gated Ltd")[0];
    String rival = tenantWithStore("Rival Ltd")[0];
    assertThat(
        post("/admin/roles", define("A_ROLE", "CASHIER", "[]"), tenant, OWNER, "STOREKEEPER")
            .getStatus(),
        is(403));
    assertThat(
        post("/admin/roles", define("A_ROLE", "CASHIER", "[]"), tenant, OWNER, "CASHIER")
            .getStatus(),
        is(403));
    // A manager whose own role was narrowed out of staff.manage: the tier admits them by path,
    // the permission refuses them by name.
    Response narrowed =
        postWithPermissions(
            "/admin/roles", define("A_ROLE", "CASHIER", "[]"), tenant, "MANAGER", "sales.void");
    assertThat(narrowed.getStatus(), is(403));
    assertThat(narrowed.readEntity(String.class), containsString("PERMISSION_DENIED"));
    // A manager narrowed to nothing at all.
    assertThat(
        postWithPermissions(
                "/admin/roles", define("A_ROLE", "CASHIER", "[]"), tenant, "MANAGER", "-")
            .getStatus(),
        is(403));
    // A manager with the permission may.
    assertThat(
        postWithPermissions(
                "/admin/roles",
                define("A_ROLE", "CASHIER", "[]"),
                tenant,
                "MANAGER",
                "staff.manage")
            .getStatus(),
        is(201));
    assertThat(get("/admin/roles/A_ROLE", rival, "OWNER", null).getStatus(), is(404));
    assertThat(
        get("/admin/roles", rival, "OWNER", null).readEntity(String.class),
        not(containsString("A_ROLE")));
    assertThat(delete("/admin/roles/A_ROLE", rival, OWNER, "OWNER").getStatus(), is(404));
    // The rival defines its own of the same code: separate.
    assertThat(
        post("/admin/roles", define("A_ROLE", "MANAGER", "[\"sales.void\"]"), rival, OWNER, "OWNER")
            .getStatus(),
        is(201));
    assertThat(
        data(get("/admin/roles/A_ROLE", tenant, "OWNER", null).readEntity(String.class))
            .getString("baseTier"),
        is("CASHIER"));
  }

  @Test
  @DisplayName(
      "Staff are assigned to a custom role, the event carries its permissions, and the role cannot be deleted while held")
  void assignmentsCarryTheRoleAndPinIt() throws Exception {
    String[] ts = tenantWithStore("Held Ltd");
    String tenant = ts[0];
    String store = ts[1];
    assertThat(
        post("/admin/roles", define("TRAINEE", "CASHIER", "[]"), tenant, OWNER, "OWNER")
            .getStatus(),
        is(201));
    String user = Ids.newId().toString();
    Response assigned =
        post(
            "/admin/staff",
            "{\"userId\":\"" + user + "\",\"storeId\":\"" + store + "\",\"role\":\"trainee\"}",
            tenant,
            OWNER,
            "OWNER");
    assertThat(assigned.getStatus(), is(201));
    String event = outbox(tenant, "StaffAssigned");
    assertThat(event, containsString("\"role\":\"CASHIER\""));
    assertThat(
        event, containsString("\"roleCode\":\"TRAINEE\",\"permissions\":[],\"roleUpdatedAt\":\""));
    String staff = get("/admin/staff", tenant, "OWNER", null).readEntity(String.class);
    assertThat(staff, containsString("\"role\":\"TRAINEE\""));
    assertThat(staff, containsString("\"baseTier\":\"CASHIER\""));
    // A plain tier still assigns as before, with no code on the event.
    String plain = Ids.newId().toString();
    assertThat(
        post(
                "/admin/staff",
                "{\"userId\":\"" + plain + "\",\"storeId\":\"" + store + "\",\"role\":\"CASHIER\"}",
                tenant,
                OWNER,
                "OWNER")
            .getStatus(),
        is(201));
    assertThat(outbox(tenant, "StaffAssigned"), not(containsString("\"roleCode\":\"CASHIER\"")));
    // A role that is neither.
    Response unknown =
        post(
            "/admin/staff",
            "{\"userId\":\"" + plain + "\",\"storeId\":\"" + store + "\",\"role\":\"SHIFT_LEAD\"}",
            tenant,
            OWNER,
            "OWNER");
    assertThat(unknown.getStatus(), is(400));
    assertThat(unknown.readEntity(String.class), containsString("STAFF_ROLE_UNKNOWN"));
    // Held: cannot be deleted.
    Response inUse = delete("/admin/roles/TRAINEE", tenant, OWNER, "OWNER");
    assertThat(inUse.getStatus(), is(409));
    assertThat(inUse.readEntity(String.class), containsString("ROLE_IN_USE"));
    // Removing the assignment publishes StaffRemoved with the tier (SJ-D51) and frees the role.
    Response removed = delete("/admin/staff/" + user + "?store=" + store, tenant, OWNER, "OWNER");
    assertThat(removed.getStatus(), is(200));
    String removedEvent = outbox(tenant, "StaffRemoved");
    assertThat(removedEvent, containsString("\"userId\":\"" + user + "\""));
    assertThat(removedEvent, containsString("\"role\":\"CASHIER\""));
    assertThat(delete("/admin/roles/TRAINEE", tenant, OWNER, "OWNER").getStatus(), is(204));
    assertThat(get("/admin/roles/TRAINEE", tenant, "OWNER", null).getStatus(), is(404));
    // Staff work needs the permission too.
    assertThat(
        postWithPermissions(
                "/admin/staff",
                "{\"userId\":\"" + plain + "\",\"storeId\":\"" + store + "\",\"role\":\"CASHIER\"}",
                tenant,
                "MANAGER",
                "-")
            .getStatus(),
        is(403));
    assertThat(
        deleteWithPermissions(
                "/admin/staff/" + plain + "?store=" + store, tenant, "MANAGER", "sales.void")
            .getStatus(),
        is(403));
  }

  @Test
  @DisplayName("Twenty definitions of the same code at once produce one role")
  void twentyDefinitionsAtOnce() throws Exception {
    String tenant = tenantWithStore("Race Ltd")[0];
    int n = 20;
    var pool = Executors.newFixedThreadPool(n);
    var go = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return post(
                        "/admin/roles", define("RACER", "CASHIER", "[]"), tenant, OWNER, "OWNER")
                    .getStatus();
              }));
    }
    go.countDown();
    int created = 0;
    int conflicts = 0;
    for (Future<Integer> f : results) {
      int s = f.get();
      if (s == 201) created++;
      else if (s == 409) conflicts++;
      else throw new AssertionError("unexpected " + s);
    }
    pool.shutdown();
    assertThat(created, is(1));
    assertThat(conflicts, is(n - 1));
  }

  @Test
  @DisplayName("GET /admin/staff is scoped by the caller's stores, cursor pagination included")
  void staffListingIsScopedByTheCallersStores() {
    String[] ts = tenantWithStore("Scoped Staff Ltd");
    String tenant = ts[0];
    String s1 = ts[1];
    String s2 = addStore(tenant, "Second");

    String atS1a = Ids.newId().toString();
    String atS1b = Ids.newId().toString();
    String atS2 = Ids.newId().toString();
    assign(tenant, atS1a, s1, "CASHIER");
    assign(tenant, atS1b, s1, "STOREKEEPER");
    assign(tenant, atS2, s2, "CASHIER");

    // A manager held to S1 only: never S2's assignment, even walked across pages of one.
    Response page1 = getScoped("/admin/staff?limit=1", tenant, "MANAGER", s1);
    String page1Body = page1.readEntity(String.class);
    assertThat(page1Body, page1.getStatus(), is(200));
    String cursor = nextCursor(page1Body);
    assertThat("a first page of one leaves a second", cursor, not(nullValue()));
    List<String> seenAtS1 = new ArrayList<>();
    seenAtS1.add(data(page1Body).getString("userId"));

    Response page2 = getScoped("/admin/staff?limit=1&after=" + cursor, tenant, "MANAGER", s1);
    String page2Body = page2.readEntity(String.class);
    assertThat(page2Body, page2.getStatus(), is(200));
    seenAtS1.add(data(page2Body).getString("userId"));
    assertThat("only the two of ours at S1: no third page", nextCursor(page2Body), is(nullValue()));

    assertThat(new TreeSet<>(seenAtS1), is(new TreeSet<>(List.of(atS1a, atS1b))));
    assertThat(seenAtS1, not(hasItem(atS2)));

    // The owner sees every assignment, S2's included.
    String all = get("/admin/staff", tenant, "OWNER", null).readEntity(String.class);
    assertThat(all, containsString(atS1a));
    assertThat(all, containsString(atS1b));
    assertThat(all, containsString(atS2));

    // Another business's owner, even naming our S1 as their own store scope, names none of ours.
    String rival = tenantWithStore("Rival Scoped Ltd")[0];
    Response rivalTry = getScoped("/admin/staff", rival, "OWNER", s1);
    String rivalBody = rivalTry.readEntity(String.class);
    assertThat(rivalBody, rivalTry.getStatus(), is(200));
    assertThat(rivalBody, not(containsString(atS1a)));
    assertThat(rivalBody, not(containsString(atS1b)));
    assertThat(rivalBody, not(containsString(atS2)));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A second store on an existing tenant, its id. */
  private String addStore(String tenant, String name) {
    Response s =
        post(
            "/admin/stores",
            "{\"name\":\""
                + name
                + "\",\"code\":\"S-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"2 High St\",\"country\":\"GB\",\"city\":\"London\",\"postcode\":\"E1"
                + " 6AN\",\"timezone\":\"Europe/London\"}",
            tenant,
            OWNER,
            "OWNER");
    String body = s.readEntity(String.class);
    assertThat(body, s.getStatus(), is(201));
    return data(body).getString("id");
  }

  private void assign(String tenant, String userId, String storeId, String role) {
    Response r =
        post(
            "/admin/staff",
            "{\"userId\":\""
                + userId
                + "\",\"storeId\":\""
                + storeId
                + "\",\"role\":\""
                + role
                + "\"}",
            tenant,
            OWNER,
            "OWNER");
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  /** As {@link #get}, with the caller held to one store ({@code X-Store-Ids}). */
  private Response getScoped(String pathAndQuery, String tenant, String roles, String storeId) {
    WebTarget t = com.storeql.test.WebTargets.at(target, pathAndQuery);
    return t.request()
        .header("X-User-Id", OWNER)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-Store-Ids", storeId)
        .get();
  }

  /** {@code meta.nextCursor}, or null when the envelope carries none or it is JSON null. */
  private static String nextCursor(String body) {
    JsonObject meta;
    try (var reader = Json.createReader(new StringReader(body))) {
      meta = reader.readObject().getJsonObject("meta");
    }
    return meta != null && meta.containsKey("nextCursor") && !meta.isNull("nextCursor")
        ? meta.getString("nextCursor")
        : null;
  }

  private void assertRefused(String tenant, String json, int status) {
    assertThat(post("/admin/roles", json, tenant, OWNER, "OWNER").getStatus(), is(status));
  }

  private void assertRefused(String tenant, String json, int status, String code) {
    Response r = post("/admin/roles", json, tenant, OWNER, "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString(code));
  }

  private Response post(String path, String json, String tenant, String user, String roles) {
    var req = target.path(path).request().header("X-User-Id", user);
    if (tenant != null) req = req.header("X-Tenant-Id", tenant);
    if (roles != null) req = req.header("X-Roles", roles);
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response postWithPermissions(
      String path, String json, String tenant, String roles, String permissions) {
    return target
        .path(path)
        .request()
        .header("X-User-Id", OWNER)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-Permissions", permissions)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response deleteWithPermissions(
      String pathAndQuery, String tenant, String roles, String permissions) {
    return request(pathAndQuery, tenant, roles, permissions).delete();
  }

  private Response put(String path, String json, String tenant, String user, String roles) {
    return target
        .path(path)
        .request()
        .header("X-User-Id", user)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response delete(String pathAndQuery, String tenant, String user, String roles) {
    return request(pathAndQuery, tenant, roles, null).delete();
  }

  private Response get(String pathAndQuery, String tenant, String roles, String permissions) {
    return request(pathAndQuery, tenant, roles, permissions).get();
  }

  private jakarta.ws.rs.client.Invocation.Builder request(
      String pathAndQuery, String tenant, String roles, String permissions) {
    WebTarget t = com.storeql.test.WebTargets.at(target, pathAndQuery);
    var req =
        t.request()
            .header("X-User-Id", OWNER)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles);
    if (permissions != null) req = req.header("X-Permissions", permissions);
    return req;
  }

  private static JsonObject data(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      JsonValue v = reader.readObject().get("data");
      if (v.getValueType() == JsonValue.ValueType.ARRAY) return v.asJsonArray().getJsonObject(0);
      return v.asJsonObject();
    }
  }

  /** The newest outbox payload of a type for a tenant. */
  private String outbox(String tenantId, String type) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid AND event_type = ?"
                    + " ORDER BY created_at DESC LIMIT 1")) {
      ps.setString(1, tenantId);
      ps.setString(2, type);
      var rs = ps.executeQuery();
      return rs.next() ? rs.getString(1) : "";
    }
  }
}
