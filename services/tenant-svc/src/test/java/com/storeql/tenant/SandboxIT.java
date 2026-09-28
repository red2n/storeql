package com.storeql.tenant;

import static com.storeql.test.Envelopes.parse;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sandbox for a business (22.8): a second tenant of its own, marked as such, on a plan the
 * platform reserves for sandboxes, with the live business's default store copied in — where an
 * integrator can create products, book stock, place orders and receive webhooks against nothing
 * real. One at a time, never of a sandbox, made and removed by the owner alone; removed, it is
 * switched off with the reason and its data erased everywhere, and another can be made.
 *
 * <p>Requests carry the identity headers the gateway stamps from a verified token.
 */
@HelidonTest
class SandboxIT {
  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");
  private static final String SANDBOX = "/admin/tenant/sandbox";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── a sandbox made ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "The owner makes a sandbox: a tenant of its own, marked, on the SANDBOX plan, the default"
          + " store copied, announced as a sandbox")
  void theOwnerMakesASandbox() {
    Business live = business("Acme");
    Response made = owner(live, "POST", SANDBOX, null);
    String body = made.readEntity(String.class);
    assertThat(body, made.getStatus(), is(201));
    JsonObject sandbox = parse(body).getJsonObject("data");
    String sandboxId = sandbox.getString("id");
    assertThat(sandboxId, not(is(live.tenant())));
    Ids.parse(sandboxId);
    assertThat(sandbox.getString("mode"), is("SANDBOX"));
    assertThat(sandbox.getString("sandboxOf"), is(live.tenant()));
    assertThat(sandbox.getString("status"), is("ACTIVE"));
    assertThat(sandbox.getString("name"), containsString("(sandbox)"));
    assertThat(sandbox.getString("country"), is("GB"));
    assertThat(sandbox.getString("currency"), is("GBP"));

    // A manager reads it; the live business's own profile says it is live.
    JsonObject read = data(call("GET", SANDBOX, null, live.tenant(), "MANAGER", Ids.newId()), 200);
    assertThat(read.getString("id"), is(sandboxId));
    JsonObject liveProfile = data(owner(live, "GET", "/admin/tenant", null), 200);
    assertThat(liveProfile.getString("mode"), is("LIVE"));
    assertThat(!liveProfile.containsKey("sandboxOf") || liveProfile.isNull("sandboxOf"), is(true));

    // Inside the sandbox — the owner's token there names the sandbox as its tenant.
    JsonObject inside =
        data(call("GET", "/admin/tenant", null, sandboxId, "OWNER", live.owner()), 200);
    assertThat(inside.getString("mode"), is("SANDBOX"));
    assertThat(inside.getString("sandboxOf"), is(live.tenant()));

    // On the plan the platform keeps for sandboxes, with its allowances.
    JsonObject plan =
        data(call("GET", "/admin/tenant/plan", null, sandboxId, "OWNER", live.owner()), 200);
    assertThat(plan.getJsonObject("plan").getString("code"), is("SANDBOX"));
    JsonArray grants =
        data(call("GET", "/admin/tenant/plan/limits", null, sandboxId, "OWNER", live.owner()), 200)
            .getJsonArray("grants");
    assertThat(grant(grants, "requests.per-minute"), is(300L));
    assertThat(grant(grants, "stores.max"), is(2L));
    assertThat(grant(grants, "products.max"), is(200L));

    // The live business's default store, copied: same code, the sandbox's default.
    JsonArray stores =
        parse(bodyOf(call("GET", "/admin/stores", null, sandboxId, "OWNER", live.owner()), 200))
            .getJsonArray("data");
    assertThat(stores.size(), is(1));
    assertThat(stores.getJsonObject(0).getString("code"), is(live.storeCode()));
    assertThat(stores.getJsonObject(0).getBoolean("isDefault"), is(true));
    assertThat(stores.getJsonObject(0).getString("id"), not(is(live.store())));

    // Announced as a sandbox, so iam-svc knows not to bind an owner and what it is a sandbox of.
    String announced =
        scalar(
            PG,
            "SELECT payload FROM tenant.outbox WHERE event_type = 'TenantCreated' AND aggregate_id = '"
                + sandboxId
                + "'");
    assertThat(announced, containsString("\"mode\":\"SANDBOX\""));
    assertThat(announced, containsString("\"sandboxOf\":\"" + live.tenant() + "\""));
    assertThat(announced, containsString("\"ownerUserId\":\"" + live.owner() + "\""));

    // No subscription: a sandbox is never billed.
    assertThat(
        scalar(
            PG, "SELECT count(*) FROM tenant.subscriptions WHERE tenant_id = '" + sandboxId + "'"),
        is("0"));

    // The platform sees it for what it is.
    JsonArray all =
        parse(bodyOf(platform("GET", "/platform/tenants?limit=100", null), 200))
            .getJsonArray("data");
    JsonObject listed = com.storeql.test.Envelopes.find(all, "id", sandboxId);
    assertThat(listed.getString("mode"), is("SANDBOX"));
    assertThat(listed.getString("sandboxOf"), is(live.tenant()));
  }

  // ── one at a time, never of a sandbox, the owner alone ─────────────────────

  @Test
  @DisplayName("One sandbox at a time, never a sandbox of a sandbox, made by the owner alone")
  void oneAtATime() {
    Business live = business("Brix");
    String sandboxId = data(owner(live, "POST", SANDBOX, null), 201).getString("id");

    assertError(owner(live, "POST", SANDBOX, null), 409, "SANDBOX_EXISTS");
    assertError(
        call("POST", SANDBOX, null, sandboxId, "OWNER", live.owner()), 409, "SANDBOX_NESTED");
    assertError(
        call("DELETE", SANDBOX, null, sandboxId, "OWNER", live.owner()), 409, "SANDBOX_NESTED");
    // From inside, the sandbox reads as itself.
    assertThat(
        data(call("GET", SANDBOX, null, sandboxId, "OWNER", live.owner()), 200).getString("id"),
        is(sandboxId));
    assertThat(
        call("POST", SANDBOX, null, live.tenant(), "MANAGER", Ids.newId()).getStatus(), is(403));
    assertThat(
        call("DELETE", SANDBOX, null, live.tenant(), "MANAGER", Ids.newId()).getStatus(), is(403));

    Business other = business("Crux");
    assertError(owner(other, "GET", SANDBOX, null), 404, "SANDBOX_NOT_FOUND");
    assertError(owner(other, "DELETE", SANDBOX, null), 404, "SANDBOX_NOT_FOUND");
    // Another business's sandbox is not this one's.
    assertThat(data(owner(live, "GET", SANDBOX, null), 200).getString("id"), is(sandboxId));
  }

  // ── the plan ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "The SANDBOX plan is for sandboxes only: not on sale, not chosen at signup, not given to a live business")
  void theSandboxPlanIsForSandboxesOnly() {
    JsonArray plans =
        parse(bodyOf(platform("GET", "/platform/plans", null), 200)).getJsonArray("data");
    JsonObject sandboxPlan = com.storeql.test.Envelopes.find(plans, "code", "SANDBOX");
    assertThat(sandboxPlan.getString("status"), is("ACTIVE"));
    assertThat(sandboxPlan.getBoolean("isPublic"), is(false));
    assertThat(sandboxPlan.getBoolean("isDefault"), is(false));

    JsonArray onSale =
        parse(bodyOf(target.path("/plans").request(MediaType.APPLICATION_JSON).get(), 200))
            .getJsonArray("data");
    for (JsonObject p : onSale.getValuesAs(JsonObject.class)) {
      assertThat(p.getString("code"), not(is("SANDBOX")));
    }

    Business live = business("Dyne");
    assertError(
        platform(
            "PUT",
            "/platform/tenants/" + live.tenant() + "/plan",
            "{\"planId\":\"" + sandboxPlan.getString("id") + "\"}"),
        409,
        "PLAN_SANDBOX_ONLY");
    assertError(
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"businessName\":\"Nope\",\"country\":\"GB\",\"currency\":\"GBP\",\"planId\":\""
                        + sandboxPlan.getString("id")
                        + "\"}",
                    MediaType.APPLICATION_JSON)),
        409,
        "PLAN_NOT_PUBLIC");
  }

  // ── removed ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Removed, a sandbox is switched off with the reason, its data erased everywhere, and another can be made")
  void removingASandbox() {
    Business live = business("Eyre");
    String first = data(owner(live, "POST", SANDBOX, null), 201).getString("id");

    JsonObject gone = data(owner(live, "DELETE", SANDBOX, null), 200);
    assertThat(gone.getString("id"), is(first));
    assertThat(gone.getString("status"), is("INACTIVE"));
    assertThat(gone.getString("deactivatedReason"), is("SANDBOX_DELETED"));
    assertThat(gone.getString("mode"), is("SANDBOX"));
    assertError(owner(live, "GET", SANDBOX, null), 404, "SANDBOX_NOT_FOUND");

    // Its stores closed with it; the live business trades on.
    JsonArray stores =
        parse(bodyOf(call("GET", "/admin/stores", null, first, "OWNER", live.owner()), 200))
            .getJsonArray("data");
    assertThat(stores.getJsonObject(0).getString("status"), is("SUSPENDED"));
    assertThat(
        data(owner(live, "GET", "/admin/tenant", null), 200).getString("status"), is("ACTIVE"));

    // Announced: switched off, and every service told to erase what it holds of the sandbox.
    assertThat(
        scalar(
            PG,
            "SELECT payload FROM tenant.outbox WHERE event_type = 'TenantStatusChanged' AND aggregate_id = '"
                + first
                + "'"),
        containsString("\"status\":\"INACTIVE\""));
    String due =
        scalar(
            PG,
            "SELECT payload FROM tenant.outbox WHERE event_type = 'TenantDataErasureDue' AND aggregate_id = '"
                + first
                + "'");
    assertThat(due, containsString("\"intent\":\"SANDBOX_DELETED\""));
    assertThat(due, containsString("\"tenantId\":\"" + first + "\""));

    // And another can be made — a fresh one, not the old one brought back.
    JsonObject second = data(owner(live, "POST", SANDBOX, null), 201);
    assertThat(second.getString("id"), not(is(first)));
    assertThat(second.getString("status"), is("ACTIVE"));
    assertThat(
        data(owner(live, "GET", SANDBOX, null), 200).getString("id"), is(second.getString("id")));
    assertThat(
        scalar(
            PG, "SELECT count(*) FROM tenant.tenants WHERE sandbox_of = '" + live.tenant() + "'"),
        is("2"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** A live business with an owner and a default store. */
  private record Business(String tenant, String owner, String store, String storeCode) {}

  private Business business(String name) {
    String owner = Ids.newId().toString();
    Response made =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", owner)
            .header("X-User-Email", TenantOnboarding.ownerEmail(name))
            .post(
                Entity.entity(
                    "{\"businessName\":\""
                        + name
                        + " "
                        + Ids.newId()
                        + "\",\"country\":\"GB\",\"currency\":\"GBP\"}",
                    MediaType.APPLICATION_JSON));
    String tenant = data(made, 201).getString("id");
    String code = "MAIN-" + Ids.newId().toString().substring(30);
    Response store =
        target
            .path("/onboarding/stores")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", owner)
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"name\":\"Main\",\"code\":\""
                        + code
                        + "\",\"line1\":\"1 High St\",\"city\":\"Leeds\","
                        + "\"country\":\"GB\",\"pincode\":\"LS1 1AA\",\"timezone\":\"Europe/London\"}",
                    MediaType.APPLICATION_JSON));
    return new Business(tenant, owner, data(store, 201).getString("id"), code);
  }

  private Response owner(Business b, String method, String path, String json) {
    return call(method, path, json, b.tenant(), "OWNER", b.owner());
  }

  private Response platform(String method, String path, String json) {
    return call(method, path, json, null, "PLATFORM_ADMIN", Ids.newId());
  }

  private Response call(
      String method, String path, String json, String tenant, String roles, Object user) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv.length == 2 ? kv[1] : "");
      }
    }
    var b =
        t.request(MediaType.APPLICATION_JSON)
            .header("X-Roles", roles)
            .header("X-User-Id", user.toString());
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    return switch (method) {
      case "GET" -> b.get();
      case "DELETE" -> b.delete();
      case "PUT" -> b.put(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
    };
  }

  private static String bodyOf(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private static JsonObject data(Response r, int status) {
    return parse(bodyOf(r, status)).getJsonObject("data");
  }

  private static void assertError(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, parse(body).getString("code", null), is(code));
  }

  private static Long grant(JsonArray grants, String key) {
    for (JsonObject g : grants.getValuesAs(JsonObject.class)) {
      if (key.equals(g.getString("key", null))) {
        return g.isNull("limitValue") ? null : g.getJsonNumber("limitValue").longValue();
      }
    }
    throw new AssertionError("no grant " + key + " in " + grants);
  }
}
