package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which roles may reach purchase-svc's endpoints.
 *
 * <p><b>This suite exists because a claim in the readiness review turned out to be false.</b> It
 * said the Testcontainers suites "inject roles directly and never cross the filter at all", and
 * used that to explain why SJ-D19 — five inventory reports readable by any cashier — could only
 * have been caught by driving the running stack. Probing it disproves that: {@link
 * com.storeql.web.AdminAuthorizationFilter} is a JAX-RS provider on the classpath, so it runs
 * inside {@code @HelidonTest} exactly as it does in production, and a request here with no role is
 * refused the same way.
 *
 * <p>SJ-D19 was therefore catchable in-process all along. Nobody had written the test. That is a
 * cheaper explanation than the one the page recorded, and a more uncomfortable one, because it
 * means the authorisation tier of every endpoint on this branch could have been pinned for the cost
 * of a file like this.
 *
 * <p>What these tests genuinely <em>cannot</em> reach is upstream of the filter: the gateway
 * turning a JWT into the identity headers, stripping any client-supplied copies, and routing to the
 * right service. That is a real gap and still open — but it is a much narrower one than "the filter
 * is not exercised".
 */
@HelidonTest
class PurchaseAuthorizationIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(PurchaseAuthorizationIT.T, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String STORE_A = "01a090ae-611e-703c-a378-a4972ea461c8";
  private static final String USER = "01a090ae-611e-700b-bde4-50df0324c37c";

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── the filter is live in this harness ──────────────────────────────────────

  @Test
  @DisplayName("A read with no role at all is refused — the filter runs here, in-process")
  void noRoleIsRefused() {
    assertThat(get("/suppliers", null).getStatus(), is(403));
  }

  @Test
  @DisplayName("A signed-in shopper cannot read the tenant's suppliers or their terms (SJ-D11)")
  void customerIsRefused() {
    assertThat(get("/suppliers", "CUSTOMER").getStatus(), is(403));
    assertThat(get("/purchase-orders", "CUSTOMER").getStatus(), is(403));
  }

  @Test
  @DisplayName("A customer cannot reach any of the four approval endpoints")
  void customerCannotReachApproval() {
    String po = draftOrder("OWNER");
    assertThat(post("/purchase-orders/" + po + "/submit", "{}", "CUSTOMER").getStatus(), is(403));
    assertThat(post("/purchase-orders/" + po + "/approve", "{}", "CUSTOMER").getStatus(), is(403));
    assertThat(
        post("/purchase-orders/" + po + "/reject", "{\"reason\":\"x\"}", "CUSTOMER").getStatus(),
        is(403));
    assertThat(get("/purchase-orders/" + po + "/approvals", "CUSTOMER").getStatus(), is(403));
    assertThat(
        get("/purchase-orders/spend-authority?currency=GBP", "CUSTOMER").getStatus(), is(403));
  }

  @Test
  @DisplayName("Staff roles reach them, so the guard above is not simply refusing everyone")
  void staffReachThem() {
    // A test that only ever asserts 403 passes just as well when the endpoint does not exist.
    String po = draftOrder("OWNER");
    assertThat(get("/purchase-orders/" + po + "/approvals", "MANAGER").getStatus(), is(200));
    assertThat(
        get("/purchase-orders/spend-authority?currency=GBP", "MANAGER").getStatus(), is(200));
  }

  // ── the tier that is documented rather than fixed ───────────────────────────

  @Test
  @DisplayName(
      "A CASHIER can still raise and submit a purchase order when no limits are configured")
  void cashierCanCommitWhenApprovalIsOff() {
    // This pins the DOCUMENTED default rather than approving of it. /purchase-orders sits outside
    // /admin/, so the filter's mutation tier asks only for a staff role, and with
    // storeql.purchase.approval.limits unset the spend check authorises everyone. A till operator
    // can therefore commit the business to an unbounded amount.
    //
    // That is the gap PO approval was built to close, and it closes it only once limits are
    // configured — which is a deliberate trade (turning mandatory approval on for every existing
    // tenant is not a config default) but leaves the default posture unchanged. Whether a cashier
    // should be doing procurement at all is a role-policy decision for the product, not one to
    // invent inside a spend-limit feature. The test is here so the decision is visible rather than
    // implied, and so that changing it breaks something.
    String po = draftOrder("CASHIER");
    // An order has something on it before it goes anywhere (the owner adds the line; what is being
    // pinned is who may send it).
    assertThat(
        post("/purchase-orders/" + po + "/lines", PurchaseFixtures.lineJson(1, "10.00"), "OWNER")
            .getStatus(),
        is(201));
    Response submit = post("/purchase-orders/" + po + "/submit", "{}", "CASHIER");
    assertThat(submit.getStatus(), is(200));
    assertThat(submit.readEntity(String.class).contains("\"status\":\"SUBMITTED\""), is(true));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private String draftOrder(String role) {
    // Adding a supplier is warehouse and management work; the role under test raises the order.
    Response sup = post("/suppliers", "{\"name\":\"auth-" + Ids.newId() + "\"}", "OWNER");
    assertThat(sup.getStatus(), is(201));
    String supId = id(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\"" + supId + "\",\"storeId\":\"" + STORE_A + "\"}",
            role);
    assertThat(po.getStatus(), is(201));
    return id(po.readEntity(String.class));
  }

  private Response post(String path, String json, String role) {
    var req = target.path(path).request().header("X-Tenant-Id", T).header("X-User-Id", USER);
    if (role != null) req = req.header("X-Roles", role);
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery, String role) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    var req = t.request().header("X-Tenant-Id", T).header("X-User-Id", USER);
    if (role != null) req = req.header("X-Roles", role);
    return req.get();
  }

  private static String id(String json) {
    int i = json.indexOf("\"id\":\"") + 6;
    return json.substring(i, json.indexOf("\"", i));
  }
}
