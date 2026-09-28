package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the tenant-svc onboarding flow against real Postgres (Testcontainers):
 * create tenant → create first store (auto DEFAULT zone) → tenant isolation → duplicate-code 409.
 * Kafka/Consul disabled; schema isolation off (the test container is private), so default schema is
 * fine.
 */
@HelidonTest
class OnboardingIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // Migrate into the test schema the app will use.
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "tenant");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  // A login owns one business (21.13): every test signs up a fresh owner.
  private String owner;

  @BeforeEach
  void freshOwner() {
    owner = Ids.newId().toString();
  }

  private static final String TENANT_B = "01a090ae-611e-7037-a4b7-c854f0266ace";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json, String... headers) {
    var req = target.path(path).request();
    for (int i = 0; i < headers.length; i += 2) {
      req = req.header(headers[i], headers[i + 1]);
    }
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void fullOnboardingFlow() {
    // create tenant (owner identity from gateway header)
    Response tenantResp =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Acme\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            owner);
    assertThat(tenantResp.getStatus(), is(201));
    String tenantId = field(tenantResp.readEntity(String.class), "id");

    // create first store → default + auto DEFAULT zone (caller has owner by now — see RBAC filter)
    Response storeResp =
        post(
            "/onboarding/stores",
            "{\"name\":\"Main\",\"code\":\"MAIN\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            owner,
            "X-Roles",
            "OWNER");
    assertThat(storeResp.getStatus(), is(201));
    String storeBody = storeResp.readEntity(String.class);
    assertThat(storeBody, containsString("\"isDefault\":true"));
    String storeId = field(storeBody, "id");

    // the DEFAULT zone exists
    String zones =
        target
            .path("/admin/stores/" + storeId + "/zones")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(zones, containsString("DEFAULT"));

    // duplicate store code → 409
    Response dup =
        post(
            "/onboarding/stores",
            "{\"name\":\"Dup\",\"code\":\"MAIN\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            owner,
            "X-Roles",
            "OWNER");
    assertThat(dup.getStatus(), is(409));

    // onboarding status reflects the store that now exists
    String status =
        target
            .path("/onboarding/status")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-User-Id", owner)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(status, containsString("\"hasDefaultStore\":true"));
  }

  /**
   * cart-svc, order-svc and iam-svc gate trading on a local store_status projection and let through
   * a store they have no row for. If creating a store did not announce its status, no row would
   * name the store's owner until its status first changed, and any tenant could open carts, POS
   * sessions and orders against the store's id.
   */
  @Test
  void creatingAStoreAnnouncesItsStatus() throws Exception {
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Announce Ltd\",\"country\":\"gb\",\"currency\":\"gbp\"}",
            "X-User-Id",
            owner);
    String tenantId = field(t.readEntity(String.class), "id");
    Response first =
        post(
            "/onboarding/stores",
            "{\"name\":\"First\",\"code\":\"FIRST\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            owner,
            "X-Roles",
            "OWNER");
    Response second =
        post(
            "/admin/stores",
            "{\"name\":\"Second\",\"code\":\"SECOND\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(first.getStatus(), is(201));
    assertThat(second.getStatus(), is(201));

    for (Response created : List.of(first, second)) {
      String storeId = field(created.readEntity(String.class), "id");
      List<String> announced = outboxPayloads(tenantId, "StoreStatusChanged", storeId);
      assertThat(announced, hasSize(1));
      assertThat(announced.get(0), containsString("\"tenantId\":\"" + tenantId + "\""));
      assertThat(announced.get(0), containsString("\"storeId\":\"" + storeId + "\""));
      assertThat(announced.get(0), containsString("\"status\":\"ACTIVE\""));
    }
  }

  @Test
  void aStoreIsAShopOrAWarehouseAndNothingElse() {
    // Depot / DC replenishment reads the type to know which stores may serve shops: a type it
    // cannot read would be a warehouse nobody could use, or a shop that quietly serves others.
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Depot Ltd\",\"country\":\"gb\",\"currency\":\"gbp\"}",
            "X-User-Id",
            owner);
    String tenantId = field(t.readEntity(String.class), "id");
    assertThat(
        post(
                "/onboarding/stores",
                "{\"name\":\"First\",\"code\":\"FIRST\",\"timezone\":\"Europe/London\"}",
                "X-Tenant-Id",
                tenantId,
                "X-User-Id",
                owner,
                "X-Roles",
                "OWNER")
            .getStatus(),
        is(201));
    Response depot =
        post(
            "/admin/stores",
            "{\"name\":\"Depot\",\"code\":\"DC1\",\"type\":\"warehouse\","
                + "\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    String body = depot.readEntity(String.class);
    assertThat(body, depot.getStatus(), is(201));
    assertThat(body, containsString("\"type\":\"WAREHOUSE\""));
    // A dark store is a third kind (ship-from-store and dark-store picking): the storefront lists
    // it
    // with the shops, delivery-only — no collection is offered there; a shop offers collection.
    Response dark =
        post(
            "/admin/stores",
            "{\"name\":\"Online hub\",\"code\":\"DARK1\",\"type\":\"dark_store\","
                + "\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    String darkBody = dark.readEntity(String.class);
    assertThat(darkBody, dark.getStatus(), is(201));
    assertThat(darkBody, containsString("\"type\":\"DARK_STORE\""));
    String darkId = field(darkBody, "id");
    Response storefront =
        target.path("/storefront/stores").request().header("X-Tenant-Id", tenantId).get();
    String listed = storefront.readEntity(String.class);
    assertThat(listed, storefront.getStatus(), is(200));
    var stores = jakarta.json.Json.createReader(new java.io.StringReader(listed)).readObject();
    java.util.Map<String, jakarta.json.JsonObject> byId = new java.util.HashMap<>();
    for (var v : stores.getJsonArray("data"))
      byId.put(v.asJsonObject().getString("storeId"), v.asJsonObject());
    assertThat(listed, byId.get(darkId).getString("type"), is("DARK_STORE"));
    assertThat(listed, byId.get(darkId).getBoolean("pickupOffered"), is(false));
    var shop =
        byId.values().stream()
            .filter(o -> "STORE".equals(o.getString("type")))
            .findFirst()
            .orElseThrow();
    assertThat(listed, shop.getBoolean("pickupOffered"), is(true));
    // Another business's storefront lists none of these stores, dark or not.
    String rival =
        field(
            post(
                    "/onboarding/tenants",
                    "{\"businessName\":\"Rival Ltd\",\"country\":\"gb\",\"currency\":\"gbp\"}",
                    "X-User-Id",
                    Ids.newId().toString())
                .readEntity(String.class),
            "id");
    String rivalListed =
        target
            .path("/storefront/stores")
            .request()
            .header("X-Tenant-Id", rival)
            .get()
            .readEntity(String.class);
    assertThat(rivalListed, not(containsString(darkId)));
    Response odd =
        post(
            "/admin/stores",
            "{\"name\":\"Odd\",\"code\":\"ODD\",\"type\":\"SHED\","
                + "\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    String oddBody = odd.readEntity(String.class);
    assertThat(oddBody, odd.getStatus(), is(400));
    assertThat(oddBody, containsString("TENANT_STORE_TYPE_INVALID"));
  }

  /**
   * The storefront names the business — the European Accessibility Act's service provider, whose
   * accessibility statement the shop shows — never one of its stores and never another business.
   * The name is the tenant's legal name, else the name it signed up with, and it is always the
   * tenant whose storefront is asked (the resolved storefront header), whatever store is named.
   */
  @Test
  @org.junit.jupiter.api.DisplayName(
      "The storefront names its own business (legal name, else name) and never another's")
  void theStorefrontNamesItsOwnBusinessAndNeverAnother() {
    String suffix = Ids.newId().toString().substring(24);
    String ourName = "Harbour Foods " + suffix;
    String ourLegal = "Harbour Foods Trading Ltd " + suffix;
    String ours =
        field(
            post(
                    "/onboarding/tenants",
                    "{\"businessName\":\""
                        + ourName
                        + "\",\"legalName\":\""
                        + ourLegal
                        + "\",\"country\":\"gb\",\"currency\":\"gbp\"}",
                    "X-User-Id",
                    owner)
                .readEntity(String.class),
            "id");
    String ourStore =
        storeIn(
            ours,
            owner,
            "/onboarding/stores",
            "{\"name\":\"Quay Street\",\"code\":\"QUAY\",\"timezone\":\"Europe/London\"}");
    String ourSecond =
        storeIn(
            ours,
            owner,
            "/admin/stores",
            "{\"name\":\"Online hub\",\"code\":\"HUB\",\"type\":\"dark_store\","
                + "\"timezone\":\"Europe/London\"}");

    String rivalOwner = Ids.newId().toString();
    String rivalName = "Quayside Grocers " + suffix;
    String rival =
        field(
            post(
                    "/onboarding/tenants",
                    "{\"businessName\":\""
                        + rivalName
                        + "\",\"country\":\"gb\",\"currency\":\"gbp\"}",
                    "X-User-Id",
                    rivalOwner)
                .readEntity(String.class),
            "id");
    String rivalStore =
        storeIn(
            rival,
            rivalOwner,
            "/onboarding/stores",
            "{\"name\":\"Rival Main\",\"code\":\"RMAIN\",\"timezone\":\"Europe/London\"}");

    // Our storefront: every store it lists names the business by its legal name — not the store.
    var listed = storefrontStores(ours);
    assertThat(listed.toString(), listed.size(), is(2));
    for (var s : listed.getValuesAs(jakarta.json.JsonObject.class)) {
      assertThat(listed.toString(), s.getString("businessName"), is(ourLegal));
      assertThat(listed.toString(), s.getString("storeName"), not(ourLegal));
    }
    assertThat(
        storefrontConfig(ours, ourStore, 200).getJsonObject("data").getString("businessName"),
        is(ourLegal));
    assertThat(
        storefrontConfig(ours, ourSecond, 200).getJsonObject("data").getString("businessName"),
        is(ourLegal));

    // The rival's storefront answers its own name (it has no legal name, so its name), never ours.
    var rivalListed = storefrontStores(rival);
    assertThat(rivalListed.toString(), rivalListed.size(), is(1));
    assertThat(rivalListed.getJsonObject(0).getString("businessName"), is(rivalName));
    assertThat(rivalListed.toString(), not(containsString("Harbour")));
    assertThat(
        storefrontConfig(rival, rivalStore, 200).getJsonObject("data").getString("businessName"),
        is(rivalName));

    // Naming the other business's store never borrows its name: the store is not found there.
    String theirsAskedOfUs = storefrontConfig(rival, ourStore, 404).toString();
    assertThat(theirsAskedOfUs, containsString("STORE_NOT_FOUND"));
    assertThat(theirsAskedOfUs, not(containsString("Harbour")));
    String oursAskedOfThem = storefrontConfig(ours, rivalStore, 404).toString();
    assertThat(oursAskedOfThem, not(containsString("Quayside")));

    // No storefront named, or one nobody owns: no business is named at all.
    Response anonymous = target.path("/storefront/stores").request().get();
    String anonymousBody = anonymous.readEntity(String.class);
    assertThat(anonymousBody, anonymous.getStatus(), is(401));
    assertThat(anonymousBody, not(containsString("Harbour")));
    Response nobody =
        target
            .path("/storefront/stores")
            .request()
            .header("X-Tenant-Id", Ids.newId().toString())
            .get();
    String nobodyBody = nobody.readEntity(String.class);
    assertThat(nobodyBody, nobody.getStatus(), is(404));
    assertThat(nobodyBody, not(containsString("Harbour")));

    // A legal name cleared to blank falls back to the business's name, read as it stands now.
    Response cleared =
        target
            .path("/admin/tenant")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", ours)
            .header("X-User-Id", owner)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"businessName\":\"" + ourName + "\",\"legalName\":\"  \"}",
                    MediaType.APPLICATION_JSON));
    assertThat(cleared.readEntity(String.class), cleared.getStatus(), is(200));
    for (var s : storefrontStores(ours).getValuesAs(jakarta.json.JsonObject.class)) {
      assertThat(s.getString("businessName"), is(ourName));
    }
    assertThat(
        storefrontConfig(ours, ourStore, 200).getJsonObject("data").getString("businessName"),
        is(ourName));
    assertThat(storefrontStores(rival).getJsonObject(0).getString("businessName"), is(rivalName));
  }

  private String storeIn(String tenant, String by, String path, String json) {
    Response r = post(path, json, "X-Tenant-Id", tenant, "X-User-Id", by, "X-Roles", "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private jakarta.json.JsonArray storefrontStores(String tenant) {
    Response r = target.path("/storefront/stores").request().header("X-Tenant-Id", tenant).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return jakarta.json.Json.createReader(new java.io.StringReader(body))
        .readObject()
        .getJsonArray("data");
  }

  private jakarta.json.JsonObject storefrontConfig(String tenant, String store, int status) {
    Response r =
        target
            .path("/storefront/config")
            .queryParam("store", store)
            .request()
            .header("X-Tenant-Id", tenant)
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return jakarta.json.Json.createReader(new java.io.StringReader(body)).readObject();
  }

  /**
   * Regression test for the tenant-ownership check in TenantService#createDefaultStore /
   * #onboardingStatus: the gateway's onboarding carve-out (JwtAuthFilter#isOnboarding) forwards a
   * caller-supplied X-Tenant-Id whenever the caller's JWT has no tenant claim yet, precisely so a
   * user can name the tenant they just created — but that means tenantId alone is not proof of
   * authorization. A second user must not be able to reach into the first user's tenant just by
   * knowing its id.
   */
  @Test
  void onboardingStoresRejectsNonOwner() {
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"VictimCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            owner);
    String tenantId = field(t.readEntity(String.class), "id");

    String attacker = "01a090ae-611e-7056-8f30-ecdbb48160eb";
    Response attackerStore =
        post(
            "/onboarding/stores",
            "{\"name\":\"Evil\",\"code\":\"EVIL1\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            attacker,
            "X-Roles",
            "OWNER");
    assertThat(attackerStore.getStatus(), is(403));
    assertThat(attackerStore.readEntity(String.class), containsString("TENANT_ACCESS_DENIED"));

    Response attackerStatus =
        target
            .path("/onboarding/status")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-User-Id", attacker)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(attackerStatus.getStatus(), is(403));
    assertThat(attackerStatus.readEntity(String.class), containsString("TENANT_ACCESS_DENIED"));

    // the real owner is unaffected
    Response ownerStore =
        post(
            "/onboarding/stores",
            "{\"name\":\"Main\",\"code\":\"MAIN\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            owner,
            "X-Roles",
            "OWNER");
    assertThat(ownerStore.getStatus(), is(201));
  }

  @Test
  void createTenantRequiresUser() {
    Response noUser =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"X\",\"country\":\"in\",\"currency\":\"inr\"}",
            null,
            null);
    assertThat(noUser.getStatus(), is(401));
    assertThat(noUser.readEntity(String.class), containsString("NO_USER"));
  }

  @Test
  void tenantIsolationOnStores() {
    // tenant A creates a store
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"IsoCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            owner);
    String tenantA = field(t.readEntity(String.class), "id");
    post(
        "/onboarding/stores",
        "{\"name\":\"A-store\",\"code\":\"AST\",\"timezone\":\"Europe/London\"}",
        "X-Tenant-Id",
        tenantA,
        "X-User-Id",
        owner,
        "X-Roles",
        "OWNER");

    // tenant B sees no stores
    String listB =
        target
            .path("/admin/stores")
            .request()
            .header("X-Tenant-Id", TENANT_B)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(listB, not(containsString("A-store")));
  }

  @Test
  void combinedOnboardCreatesTenatAndStore() {
    // single POST /onboarding creates tenant + first store atomically — no JWT refresh needed
    Response resp =
        post(
            "/onboarding",
            """
            {"businessName":"OneShot Co","country":"gb","currency":"gbp",\
            "storeName":"London HQ","storeCode":"LDN","storeCity":"London","storeCountry":"gb",\
            "storeTimezone":"Europe/London"}""",
            "X-User-Id",
            owner);
    assertThat(resp.getStatus(), is(201));
    String body = resp.readEntity(String.class);
    assertThat(body, containsString("\"name\":\"OneShot Co\""));
    assertThat(body, containsString("\"isDefault\":true"));
    assertThat(body, containsString("LDN"));
  }

  @Test
  void staffAssignment() {
    // create tenant + store
    Response tr =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"StaffCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            owner);
    String tenantId = field(tr.readEntity(String.class), "id");

    Response sr =
        post(
            "/onboarding/stores",
            "{\"name\":\"StaffStore\",\"code\":\"SS1\",\"timezone\":\"Europe/London\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            owner,
            "X-Roles",
            "OWNER");
    String storeId = field(sr.readEntity(String.class), "id");

    // assign a staff user
    String staffUserId = "01a090ae-611e-703c-a378-a4972ea461c8";
    Response assign =
        post(
            "/admin/staff",
            String.format(
                "{\"userId\":\"%s\",\"storeId\":\"%s\",\"role\":\"CASHIER\"}",
                staffUserId, storeId),
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(assign.getStatus(), is(201));

    // list staff — the assignment is visible
    String staffList =
        target
            .path("/admin/staff")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(staffList, containsString(staffUserId));
    assertThat(staffList, containsString("CASHIER"));

    // idempotency: posting the same assignment again → 409 (UNIQUE constraint)
    Response dup =
        post(
            "/admin/staff",
            String.format(
                "{\"userId\":\"%s\",\"storeId\":\"%s\",\"role\":\"CASHIER\"}",
                staffUserId, storeId),
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(dup.getStatus(), is(409));
  }

  @Test
  void adminListsAreCursorPaginated() {
    Response tr =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"PageCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            owner);
    String tenantId = field(tr.readEntity(String.class), "id");

    // 5 stores: the first via onboarding (default), the rest via the admin endpoint.
    post(
        "/onboarding/stores",
        "{\"name\":\"Page Store 1\",\"code\":\"PG1\",\"timezone\":\"Europe/London\"}",
        "X-Tenant-Id",
        tenantId,
        "X-User-Id",
        owner,
        "X-Roles",
        "OWNER");
    for (int i = 2; i <= 5; i++) {
      Response r =
          post(
              "/admin/stores",
              "{\"name\":\"Page Store "
                  + i
                  + "\",\"code\":\"PG"
                  + i
                  + "\",\"timezone\":\"Europe/London\"}",
              "X-Tenant-Id",
              tenantId,
              "X-Roles",
              "OWNER");
      assertThat(r.getStatus(), is(201));
    }

    // Walk /admin/stores with limit=2: pages of 2,2,1 and every store seen exactly once.
    java.util.Set<String> seen = new java.util.HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      WebTarget t = target.path("/admin/stores").queryParam("limit", 2);
      if (cursor != null) t = t.queryParam("after", cursor);
      String body =
          t.request().header("X-Tenant-Id", tenantId).header("X-Roles", "OWNER").get(String.class);
      pages++;
      for (int i = 1; i <= 5; i++) {
        String code = "\"code\":\"PG" + i + "\"";
        if (body.contains(code)) {
          assertThat("store PG" + i + " served twice", seen.add(code), is(true));
        }
      }
      int c = body.indexOf("\"nextCursor\":\"");
      cursor = c < 0 ? null : body.substring(c + 14, body.indexOf('"', c + 14));
    } while (cursor != null);
    assertThat(pages, is(3));
    assertThat(seen.size(), is(5));

    // A malformed cursor is rejected with 400 INVALID_CURSOR, not a 500.
    Response bad =
        target
            .path("/admin/stores")
            .queryParam("after", "not-base64-%%%")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("INVALID_CURSOR"));
  }

  // ── SJ-D2 repair: re-announcing a tenant's declared currency ────────────────

  /**
   * order-svc stamps money onto orders using a currency it projects from TenantCreated. A tenant
   * onboarded before that consumer existed has no projection and silently trades in the platform
   * default, and TenantCreated cannot simply be replayed to fix it — iam-svc consumes it too, and
   * would re-run onboarding work. This endpoint re-announces just the currency.
   */
  @Test
  void republishCurrencyEmitsOneEventPerTenantWithACurrency() throws Exception {
    Response created =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Replay Ltd\",\"country\":\"gb\",\"currency\":\"gbp\"}",
            "X-User-Id",
            owner);
    assertThat(created.getStatus(), is(201));
    String tenantId = field(created.readEntity(String.class), "id");

    int before = outboxCount("TenantCurrencyDeclared", tenantId);

    Response replay =
        target
            .path("/platform/tenants/republish-currency")
            .queryParam("tenantId", tenantId)
            .request()
            .header("X-Roles", "PLATFORM_ADMIN")
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(replay.getStatus(), is(200));
    assertThat(replay.readEntity(String.class), containsString("\"tenantsAnnounced\":1"));
    assertThat(outboxCount("TenantCurrencyDeclared", tenantId), is(before + 1));

    // Repeatable by design: consumers dedupe on eventId and each replay carries fresh ones, so a
    // second run re-applies the projection rather than being swallowed as a redelivery.
    target
        .path("/platform/tenants/republish-currency")
        .queryParam("tenantId", tenantId)
        .request()
        .header("X-Roles", "PLATFORM_ADMIN")
        .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(outboxCount("TenantCurrencyDeclared", tenantId), is(before + 2));

    // The payload has to carry the currency, or the consumer has nothing to project.
    assertThat(lastCurrencyEvent(tenantId), containsString("\"currency\":\"GBP\""));
  }

  /** Cross-tenant reach, so it is PLATFORM_ADMIN only — an owner must not be able to run it. */
  @Test
  void republishCurrencyIsPlatformAdminOnly() {
    Response asOwner =
        target
            .path("/platform/tenants/republish-currency")
            .request()
            .header("X-Tenant-Id", TENANT_B)
            .header("X-Roles", "OWNER")
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(asOwner.getStatus(), is(403));
  }

  /** A tenantId that is not a UUID is the caller's mistake, so 400 rather than 500. */
  @Test
  void republishCurrencyRejectsAMalformedTenantId() {
    Response bad =
        target
            .path("/platform/tenants/republish-currency")
            .queryParam("tenantId", "not-a-uuid")
            .request()
            .header("X-Roles", "PLATFORM_ADMIN")
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("INVALID_UUID"));
  }

  private static int outboxCount(String eventType, String tenantId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM tenant.outbox WHERE event_type = ? AND tenant_id = ?::uuid")) {
      ps.setString(1, eventType);
      ps.setString(2, tenantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static List<String> outboxPayloads(String tenantId, String eventType, String aggregateId)
      throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid AND event_type = ?"
                    + " AND aggregate_id = ?::uuid")) {
      ps.setString(1, tenantId);
      ps.setString(2, eventType);
      ps.setString(3, aggregateId);
      try (var rs = ps.executeQuery()) {
        List<String> payloads = new ArrayList<>();
        while (rs.next()) {
          payloads.add(rs.getString(1));
        }
        return payloads;
      }
    }
  }

  private static String lastCurrencyEvent(String tenantId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE event_type = 'TenantCurrencyDeclared'"
                    + " AND tenant_id = ?::uuid ORDER BY created_at DESC LIMIT 1")) {
      ps.setString(1, tenantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  // ── SJ-D54: a store's time zone is never defaulted ────────────────────────

  private String tenantFor(String owner, String country, String currency) {
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Zone Co "
                + com.storeql.ids.Ids.newId()
                + "\",\"country\":\""
                + country
                + "\",\"currency\":\""
                + currency
                + "\"}",
            "X-User-Id",
            owner);
    assertThat(t.getStatus(), is(201));
    return field(t.readEntity(String.class), "id");
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "A store needs a real time zone; an update that leaves it out keeps it (SJ-D54)")
  void aStoreTimezoneIsRequiredValidatedAndKept() {
    String owner = com.storeql.ids.Ids.newId().toString();
    String tenant = tenantFor(owner, "us", "usd");

    Response none =
        post(
            "/onboarding/stores",
            "{\"name\":\"Main\",\"code\":\"ZMAIN\"}",
            "X-User-Id",
            owner,
            "X-Tenant-Id",
            tenant);
    assertThat(none.getStatus(), is(400));
    assertThat(none.readEntity(String.class), containsString("STORE_TIMEZONE_REQUIRED"));
    for (String bad :
        new String[] {
          "Mars/Olympus", "UTC+01:00", "europe/london", "Europe/London'; DROP TABLE stores;--"
        }) {
      Response r =
          post(
              "/onboarding/stores",
              "{\"name\":\"Main\",\"code\":\"ZMAIN\",\"timezone\":\"" + bad + "\"}",
              "X-User-Id",
              owner,
              "X-Tenant-Id",
              tenant);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, r.readEntity(String.class), containsString("STORE_TIMEZONE_INVALID"));
    }

    Response made =
        post(
            "/onboarding/stores",
            "{\"name\":\"Main\",\"code\":\"ZMAIN\",\"timezone\":\"America/Chicago\"}",
            "X-User-Id",
            owner,
            "X-Tenant-Id",
            tenant);
    String madeBody = made.readEntity(String.class);
    assertThat(madeBody, made.getStatus(), is(201));
    assertThat(madeBody, containsString("\"timezone\":\"America/Chicago\""));
    String store = field(madeBody, "id");

    java.util.function.Supplier<jakarta.ws.rs.client.Invocation.Builder> admin =
        () ->
            target
                .path("/admin/stores/" + store)
                .request()
                .header("X-Tenant-Id", tenant)
                .header("X-Roles", "OWNER");
    Response renamed =
        admin.get().put(Entity.entity("{\"name\":\"Renamed\"}", MediaType.APPLICATION_JSON));
    String renamedBody = renamed.readEntity(String.class);
    assertThat(renamedBody, renamed.getStatus(), is(200));
    assertThat(
        "an update without a zone keeps it, not UTC",
        renamedBody,
        containsString("\"timezone\":\"America/Chicago\""));
    Response badZone =
        admin
            .get()
            .put(
                Entity.entity(
                    "{\"name\":\"Renamed\",\"timezone\":\"Nowhere/Land\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(badZone.getStatus(), is(400));
    assertThat(badZone.readEntity(String.class), containsString("STORE_TIMEZONE_INVALID"));
    assertThat(admin.get().get(String.class), containsString("\"timezone\":\"America/Chicago\""));
    Response moved =
        admin
            .get()
            .put(
                Entity.entity(
                    "{\"name\":\"Renamed\",\"timezone\":\"America/Denver\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(moved.readEntity(String.class), containsString("\"timezone\":\"America/Denver\""));

    // One-shot onboarding without a zone is refused before the business is created.
    Response oneShot =
        post(
            "/onboarding",
            "{\"businessName\":\"No Zone\",\"country\":\"au\",\"currency\":\"aud\",\"storeName\":\"Sydney\",\"storeCode\":\"SYD\"}",
            "X-User-Id",
            com.storeql.ids.Ids.newId().toString());
    assertThat(oneShot.getStatus(), is(400));
    assertThat(oneShot.readEntity(String.class), containsString("STORE_TIMEZONE_REQUIRED"));
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "Twenty stores created at once without a zone are twenty refusals and no store")
  void concurrentStoresWithoutAZoneAreAllRefused() throws Exception {
    String owner = com.storeql.ids.Ids.newId().toString();
    String tenant = tenantFor(owner, "gb", "gbp");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 20; i++) {
        String code = "Z" + i;
        futures.add(
            pool.submit(
                () ->
                    target
                        .path("/admin/stores")
                        .request()
                        .header("X-Tenant-Id", tenant)
                        .header("X-Roles", "OWNER")
                        .post(
                            Entity.entity(
                                "{\"name\":\"S\",\"code\":\"" + code + "\"}",
                                MediaType.APPLICATION_JSON))
                        .getStatus()));
      }
      for (var f : futures) assertThat(f.get(), is(400));
    } finally {
      pool.shutdownNow();
    }
    String stores =
        target
            .path("/admin/stores")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(stores, containsString("\"data\":[]"));
  }
}
