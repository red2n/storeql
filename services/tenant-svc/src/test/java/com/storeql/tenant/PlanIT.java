package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plans and packaging (21.8), over HTTP and a real database: the platform writes a price list, a
 * business signing up lands on the default plan, and what the plan allows is what the business can
 * actually do — a limit reached refuses the next store by name and by figure. And what is refused:
 * selling a plan with no price, promising something nobody enforces, changing how often a plan
 * bills while businesses are on it, moving a business onto a plan it already does not fit, and an
 * owner anywhere near the price list.
 */
@HelidonTest
class PlanIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String PLANS = "/platform/plans";
  private static final String MINE = "/admin/tenant/plan";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
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

  private Answer call(String method, String path, String json, String tenant, String roles) {
    Invocation.Builder b =
        target.path(path).request(MediaType.APPLICATION_JSON).header("X-User-Id", Ids.newId());
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "PUT" -> b.put(body);
          default -> b.post(body);
        };
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject());
  }

  /** As the platform administrator. */
  private Answer platform(String method, String path, String json) {
    return call(method, path, json, null, "PLATFORM_ADMIN");
  }

  /** As a business's owner. */
  private Answer owner(String method, String path, String json, String tenantId) {
    return call(method, path, json, tenantId, "OWNER");
  }

  private static String planBody(String code, String interval) {
    return "{\"code\":\""
        + code
        + "\",\"name\":\""
        + code
        + " plan\",\"description\":\"for a test\",\"billingInterval\":\""
        + interval
        + "\",\"trialDays\":14,\"isPublic\":true,\"sortOrder\":1}";
  }

  /** A plan on sale in sterling, allowing {@code stores} stores. */
  private String sellablePlan(String code, int stores) {
    return sellablePlan(
        code,
        "{\"key\":\"stores.max\",\"limitValue\":"
            + stores
            + "},{\"key\":\"feature.storefront\",\"enabled\":true}");
  }

  /** A plan on sale in sterling, allowing exactly what {@code grants} names. */
  private String sellablePlan(String code, String grants) {
    Answer written = platform("POST", PLANS, planBody(code, "MONTH"));
    assertThat(written.body().toString(), written.status(), is(201));
    String id = written.data().getString("id");
    assertThat(
        platform("POST", PLANS + "/" + id + "/prices", "{\"currency\":\"GBP\",\"amount\":49.00}")
            .status(),
        is(200));
    assertThat(
        platform("PUT", PLANS + "/" + id + "/includes", "{\"grants\":[" + grants + "]}").status(),
        is(200));
    assertThat(platform("POST", PLANS + "/" + id + "/activate").status(), is(200));
    return id;
  }

  private Answer platform(String method, String path) {
    return platform(method, path, null);
  }

  private String onboard(String name) {
    return TenantOnboarding.onboard(target, name, "GB", "GBP");
  }

  private Answer addStore(String tenantId, String code) {
    return owner(
        "POST",
        "/admin/stores",
        "{\"name\":\"Store "
            + code
            + "\",\"code\":\""
            + code
            + "\",\"timezone\":\"Europe/London\",\"country\":\"GB\"}",
        tenantId);
  }

  /** A field the answer leaves out, or gives as null: both say there is none. */
  private static boolean absent(JsonObject o, String field) {
    return !o.containsKey(field) || o.isNull(field);
  }

  private Answer assignStaff(String tenantId, String userId, String storeId, String role) {
    return owner(
        "POST",
        "/admin/staff",
        "{\"userId\":\"" + userId + "\",\"storeId\":\"" + storeId + "\",\"role\":\"" + role + "\"}",
        tenantId);
  }

  private static String storeIdOf(Answer created) {
    assertThat(created.body().toString(), created.status(), is(201));
    return created.data().getString("id");
  }

  private static JsonObject usage(Answer mine, String key) {
    for (JsonValue v : mine.data().getJsonArray("usage")) {
      if (key.equals(v.asJsonObject().getString("key", null))) return v.asJsonObject();
    }
    throw new AssertionError("no usage for " + key + " in " + mine.body());
  }

  // ── the price list, and a business on it ───────────────────────────────────

  @Test
  @DisplayName(
      "A plan is written, priced, sold and made the default; a business signing up lands on it")
  void aPlanIsWrittenAndABusinessLandsOnIt() {
    Answer draft = platform("POST", PLANS, planBody("STARTER-A", "MONTH"));
    assertThat(draft.status(), is(201));
    String id = draft.data().getString("id");
    assertThat(
        "nothing is sold until somebody sells it", draft.data().getString("status"), is("DRAFT"));
    assertThat(draft.data().getBoolean("isDefault"), is(false));

    Answer early = platform("POST", PLANS + "/" + id + "/activate");
    assertThat(early.status(), is(409));
    assertThat(early.code(), is("PLAN_HAS_NO_PRICE"));
    Answer tooEarly = platform("POST", PLANS + "/" + id + "/default");
    assertThat(
        "a draft is not on sale, so nobody starts on it", tooEarly.code(), is("PLAN_NOT_SOLD"));

    assertThat(
        platform("POST", PLANS + "/" + id + "/prices", "{\"currency\":\"GBP\",\"amount\":29.00}")
            .status(),
        is(200));
    Answer sold = platform("POST", PLANS + "/" + id + "/activate");
    assertThat(sold.data().getString("status"), is("ACTIVE"));
    assertThat(
        platform("POST", PLANS + "/" + id + "/default").data().getBoolean("isDefault"), is(true));

    assertThat(
        platform(
                "PUT",
                PLANS + "/" + id + "/includes",
                "{\"grants\":[{\"key\":\"stores.max\",\"limitValue\":2}]}")
            .status(),
        is(200));

    String shop = onboard("Lands on it");
    Answer mine = owner("GET", MINE, null, shop);
    assertThat(mine.body().toString(), mine.status(), is(200));
    assertThat(mine.data().getJsonObject("plan").getString("code"), is("STARTER-A"));
    assertThat("no plan means no note", mine.data().get("note"), is(nullValue()));
    JsonObject stores = usage(mine, "stores.max");
    assertThat(stores.getJsonNumber("limitValue").longValue(), is(2L));
    assertThat(
        "a business that has just signed up has no store yet",
        stores.getJsonNumber("used").longValue(),
        is(0L));
    assertThat(addStore(shop, "FIRST").status(), is(201));
    assertThat(
        usage(owner("GET", MINE, null, shop), "stores.max").getJsonNumber("used").longValue(),
        is(1L));
    assertThat(stores.getBoolean("over"), is(false));
    assertThat(mine.data().getJsonObject("plan").getJsonArray("prices").size(), is(1));
  }

  @Test
  @DisplayName("The limit is what the business can actually do, and the refusal names the figures")
  void theLimitRefusesTheNextStore() {
    String small = sellablePlan("SMALL-B", 2);
    assertThat(platform("POST", PLANS + "/" + small + "/default").status(), is(200));
    String shop = onboard("Two stores");

    assertThat("the first store fits", addStore(shop, "ONE").status(), is(201));
    assertThat("and the second", addStore(shop, "TWO").status(), is(201));
    Answer third = addStore(shop, "THREE");
    assertThat(third.status(), is(409));
    assertThat(third.code(), is("PLAN_LIMIT_REACHED"));
    assertThat(third.body().toString(), containsString("allows 2 stores"));
    assertThat(third.body().toString(), containsString("has 2"));

    JsonObject stores = usage(owner("GET", MINE, null, shop), "stores.max");
    assertThat(stores.getJsonNumber("used").longValue(), is(2L));

    // A larger plan lets it open another; the smaller one is still sold to whoever is on it.
    String big = sellablePlan("BIG-B", 10);
    Answer moved =
        platform(
            "PUT",
            "/platform/tenants/" + shop + "/plan",
            "{\"planId\":\"" + big + "\",\"reason\":\"they grew\"}");
    assertThat(moved.body().toString(), moved.status(), is(200));
    assertThat(moved.data().getJsonObject("plan").getString("code"), is("BIG-B"));
    assertThat(addStore(shop, "THREE").status(), is(201));

    // And back down is refused while they are past it, naming what is over.
    Answer down =
        platform("PUT", "/platform/tenants/" + shop + "/plan", "{\"planId\":\"" + small + "\"}");
    assertThat(down.status(), is(409));
    assertThat(down.code(), is("PLAN_LIMIT_EXCEEDED_NOW"));
    assertThat(down.body().toString(), containsString("Stores and warehouses 3 of 2"));
  }

  @Test
  @DisplayName("Retiring a plan keeps the businesses on it and offers it to nobody new")
  void retiringKeepsItsSubscribers() {
    String plan = sellablePlan("GOING-C", 5);
    assertThat(platform("POST", PLANS + "/" + plan + "/default").status(), is(200));
    String shop = onboard("Stays on it");

    assertThat(
        platform("POST", PLANS + "/" + plan + "/retire").data().getString("status"), is("RETIRED"));
    Answer mine = owner("GET", MINE, null, shop);
    assertThat(
        "the business keeps what it bought",
        mine.data().getJsonObject("plan").getString("code"),
        is("GOING-C"));
    assertThat(
        "and it is offered to nobody",
        owner("GET", MINE + "/available", null, shop).body().toString(),
        not(containsString("GOING-C")));
    assertThat(platform("POST", PLANS + "/" + plan + "/retire").code(), is("PLAN_NOT_SOLD"));

    // A business that signs up now lands on nothing, and says so rather than pretending.
    String later = onboard("No default now");
    Answer none = owner("GET", MINE, null, later);
    assertThat("a business on no plan carries no plan", absent(none.data(), "plan"), is(true));
    assertThat(none.data().getString("note"), is("This business is on no plan"));
    assertThat("no plan is no limit", addStore(later, "FREE").status(), is(201));
  }

  @Test
  @DisplayName("The staff limit counts people, not the stores they work at")
  void theStaffLimitCountsPeople() {
    String plan =
        sellablePlan(
            "STAFFED-G",
            "{\"key\":\"stores.max\",\"limitValue\":5},{\"key\":\"staff.max\",\"limitValue\":2}");
    assertThat(platform("POST", PLANS + "/" + plan + "/default").status(), is(200));
    String shop = onboard("Two people");
    String one = storeIdOf(addStore(shop, "ONE"));
    String two = storeIdOf(addStore(shop, "TWO"));

    String ann = Ids.newId().toString();
    String bob = Ids.newId().toString();
    assertThat(assignStaff(shop, ann, one, "CASHIER").status(), is(201));
    assertThat(assignStaff(shop, bob, one, "CASHIER").status(), is(201));

    assertThat(
        "the same person at a second store is not a second person",
        assignStaff(shop, ann, two, "MANAGER").status(),
        is(201));

    Answer third = assignStaff(shop, Ids.newId().toString(), one, "CASHIER");
    assertThat(third.status(), is(409));
    assertThat(third.code(), is("PLAN_LIMIT_REACHED"));
    assertThat(third.body().toString(), containsString("allows 2 staff"));
    assertThat(third.body().toString(), containsString("has 2"));

    JsonObject staff = usage(owner("GET", MINE, null, shop), "staff.max");
    assertThat(
        "tenant-svc owns the assignments, so the figure is known, not guessed",
        staff.getJsonNumber("used").longValue(),
        is(2L));
    assertThat(staff.getBoolean("over"), is(false));
  }

  // ── what is refused ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A plan that cannot be believed is refused, and a promise nobody enforces most of all")
  void whatCannotBeWritten() {
    assertThat(
        platform("POST", PLANS, planBody("lower case ok", "MONTH")).code(),
        is("PLAN_CODE_INVALID"));
    assertThat(platform("POST", PLANS, planBody("X", "MONTH")).code(), is("PLAN_CODE_INVALID"));
    assertThat(
        platform("POST", PLANS, planBody("FINE-D", "FORTNIGHT")).code(),
        is("PLAN_INTERVAL_UNKNOWN"));

    Answer plan = platform("POST", PLANS, planBody("FINE-D", "MONTH"));
    assertThat(plan.status(), is(201));
    String id = plan.data().getString("id");
    assertThat(
        "a code is taken once",
        platform("POST", PLANS, planBody("fine-d", "MONTH")).code(),
        is("PLAN_CODE_TAKEN"));

    assertThat(
        platform("POST", PLANS + "/" + id + "/prices", "{\"currency\":\"pounds\",\"amount\":1}")
            .status(),
        is(400));
    assertThat(
        platform(
                "POST",
                PLANS + "/" + id + "/prices",
                "{\"currency\":\"GBP\",\"amount\":1,\"effectiveFrom\":\"soon\"}")
            .code(),
        is("PLAN_PRICE_DATE_INVALID"));

    assertThat(
        "a key nobody enforces is a promise nobody keeps",
        platform(
                "PUT",
                PLANS + "/" + id + "/includes",
                "{\"grants\":[{\"key\":\"support.priority\",\"enabled\":true}]}")
            .code(),
        is("PLAN_ENTITLEMENT_UNKNOWN"));
    assertThat(
        platform(
                "PUT",
                PLANS + "/" + id + "/includes",
                "{\"grants\":[{\"key\":\"stores.max\",\"enabled\":true}]}")
            .code(),
        is("PLAN_ENTITLEMENT_SHAPE"));
    assertThat(
        platform(
                "PUT",
                PLANS + "/" + id + "/includes",
                "{\"grants\":[{\"key\":\"feature.storefront\",\"limitValue\":3}]}")
            .code(),
        is("PLAN_ENTITLEMENT_SHAPE"));

    assertThat(platform("GET", PLANS + "/" + Ids.newId()).code(), is("PLAN_NOT_FOUND"));
    assertThat(
        platform("PUT", "/platform/tenants/" + Ids.newId() + "/plan", "{\"planId\":\"" + id + "\"}")
            .code(),
        is("TENANT_NOT_FOUND"));

    // Unlimited is a limit row with no number, and it is allowed.
    assertThat(
        platform("PUT", PLANS + "/" + id + "/includes", "{\"grants\":[{\"key\":\"stores.max\"}]}")
            .status(),
        is(200));
  }

  @Test
  @DisplayName("How often a plan bills cannot change under the businesses on it")
  void theIntervalIsFixedOnceSomebodyIsOnIt() {
    String plan = sellablePlan("YEARLY-E", 3);
    assertThat(
        "nobody is on it yet",
        platform("PUT", PLANS + "/" + plan, planBody("YEARLY-E", "YEAR"))
            .data()
            .getString("billingInterval"),
        is("YEAR"));

    assertThat(platform("POST", PLANS + "/" + plan + "/default").status(), is(200));
    onboard("Bills yearly");

    Answer changed = platform("PUT", PLANS + "/" + plan, planBody("YEARLY-E", "MONTH"));
    assertThat(changed.status(), is(409));
    assertThat(changed.code(), is("PLAN_INTERVAL_IN_USE"));
    assertThat(
        "its name may still change",
        platform("PUT", PLANS + "/" + plan, planBody("YEARLY-E", "YEAR")).status(),
        is(200));
  }

  // ── whose it is ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The price list is the platform's, and a business sees only its own plan")
  void whoMayTouchIt() {
    String plan = sellablePlan("GUARDED-F", 4);
    assertThat(platform("POST", PLANS + "/" + plan + "/default").status(), is(200));
    String shop = onboard("Guarded");
    String rival = onboard("Rival");

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"}) {
      assertThat(role, call("GET", PLANS, null, shop, role).status(), is(403));
      assertThat(
          role, call("POST", PLANS, planBody("SNEAK", "MONTH"), shop, role).status(), is(403));
      assertThat(
          role, call("POST", PLANS + "/" + plan + "/retire", null, shop, role).status(), is(403));
      assertThat(
          role + " cannot put itself on a plan",
          call(
                  "PUT",
                  "/platform/tenants/" + shop + "/plan",
                  "{\"planId\":\"" + plan + "\"}",
                  shop,
                  role)
              .status(),
          is(403));
    }

    assertThat(
        "a cashier does not read the business's plan",
        call("GET", MINE, null, shop, "CASHIER").status(),
        is(403));
    assertThat(call("GET", MINE, null, shop, "STOREKEEPER").status(), is(403));
    assertThat(
        "nor does anybody without a role", call("GET", MINE, null, shop, null).status(), is(403));
    assertThat(owner("GET", MINE, null, shop).status(), is(200));

    // Each business reads its own, and there is no route that takes somebody else's.
    UUID shopId = Ids.parse(shop);
    UUID rivalId = Ids.parse(rival);
    assertThat(shopId, is(not(rivalId)));
    assertThat(
        owner("GET", MINE, null, rival).data().getJsonObject("plan").getString("code"),
        is("GUARDED-F"));
  }

  @Test
  @DisplayName("The entitlement keys on offer are the ones something actually enforces")
  void theCatalogueIsWhatIsEnforced() {
    Answer keys = platform("GET", PLANS + "/entitlement-keys");
    assertThat(keys.status(), is(200));
    String body = keys.body().toString();
    assertThat(body, containsString("stores.max"));
    assertThat(body, containsString("staff.max"));
    assertThat(body, containsString("products.max"));
    assertThat(body, containsString("feature.storefront"));
    // 21.11: a request rate and two storage caps, each with the door that refuses.
    assertThat(body, containsString("requests.per-minute"));
    assertThat(body, containsString("images.mb.max"));
    assertThat(body, containsString("documents.mb.max"));
    assertThat("each names who refuses when it is exceeded", body, containsString("tenant-svc"));
    assertThat(body, containsString("gateway"));
    assertThat(call("GET", PLANS + "/entitlement-keys", null, null, "OWNER").status(), is(403));
  }
}
