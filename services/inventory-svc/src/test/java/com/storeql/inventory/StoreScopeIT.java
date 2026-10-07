package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SJ-D74: the store scope on a login (and on an API key) is a promise the stock ledger has to keep.
 * A storekeeper assigned to store A books goods in at A and nowhere else, reads A's stock and
 * nobody else's, and moves stock only from a store they keep; an owner, who is assigned to no
 * store, is held to none.
 */
@HelidonTest
class StoreScopeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = "01a090ae-7c1e-7d2c-a97b-d1b8025478e1";
  private static final String A = "01a090ae-7c1e-7a01-a378-a4972ea461c8";
  private static final String B = "01a090ae-7c1e-7b02-a378-a4972ea461c8";
  private static final String C = "01a090ae-7c1e-7c03-a378-a4972ea461c8";
  private static final String V = "01a090ae-7c1e-7e04-bde4-50df0324c37c";
  private static final String KEEPER_A = "01a090ae-7c1e-7f05-bde4-50df0324c37c";
  private static final String KEEPER_B = "01a090ae-7c1e-7f06-bde4-50df0324c37c";
  private static final String OTHER_TENANT = "01a090ae-7c1e-7d3c-a97b-d1b8025478e2";
  private static final String STRANGER = "01a090ae-7c1e-7f07-bde4-50df0324c37c";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Invocation.Builder as(String path, String role, String user, String stores) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", user)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return stores == null ? b : b.header("X-Store-Ids", stores);
  }

  private Response keeperOfA(String method, String path, String json) {
    return send(as(path, "STOREKEEPER", KEEPER_A, A), method, json);
  }

  private Response keeperOfB(String method, String path, String json) {
    return send(as(path, "STOREKEEPER", KEEPER_B, B), method, json);
  }

  private Response owner(String method, String path, String json) {
    return send(as(path, "OWNER", KEEPER_A, null), method, json);
  }

  /** A manager held to the named stores (comma-separated), or the whole business when null. */
  private Response managerOf(String stores, String method, String path, String json) {
    return send(as(path, "MANAGER", KEEPER_A, stores), method, json);
  }

  /** Staff of another business, held to a store id that happens to be one of ours. */
  private Response stranger(String role, String method, String path, String json) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", OTHER_TENANT)
            .header("X-User-Id", STRANGER)
            .header("X-Roles", role)
            .header("X-Store-Ids", A)
            .header("Idempotency-Key", Ids.newId().toString());
    return send(b, method, json);
  }

  private static Response send(Invocation.Builder b, String method, String json) {
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private static String receiveBody(String store, int qty, String lot) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + V
        + "\",\"qty\":"
        + qty
        + ",\"batchNo\":\""
        + lot
        + "\",\"costPrice\":\"2.00\"}";
  }

  private static String transferBody(String from, String to) {
    return "{\"fromStoreId\":\""
        + from
        + "\",\"toStoreId\":\""
        + to
        + "\",\"transferType\":\"INTRANSIT\",\"lines\":[{\"variantId\":\""
        + V
        + "\",\"requestedQty\":1}]}";
  }

  private static String countBody(String store) {
    return "{\"storeId\":\"" + store + "\",\"name\":\"Aisle 4\"}";
  }

  private String statusOfCount(String id) {
    return data(owner("GET", "/admin/inventory/cycle-counts/" + id, null)).getString("status");
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static void refusedForStore(Response r, String what) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(403));
    assertThat(what, body, containsString("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("Goods are booked in, adjusted and read only at the stores the caller keeps")
  void receiptsAdjustmentsAndReadsHoldToTheCallersStores() {
    refusedForStore(
        keeperOfA("POST", "/admin/inventory/receive", receiveBody(B, 5, "AT-B")), "receive at B");
    assertThat(
        keeperOfA("POST", "/admin/inventory/receive", receiveBody(A, 5, "AT-A")).getStatus(),
        is(201));
    refusedForStore(
        keeperOfA(
            "POST",
            "/admin/inventory/receive/batch",
            "{\"items\":[" + receiveBody(A, 1, "OK") + "," + receiveBody(B, 1, "NOT") + "]}"),
        "a batch with one line at B");
    refusedForStore(
        keeperOfA(
            "POST",
            "/admin/inventory/adjust",
            "{\"storeId\":\""
                + B
                + "\",\"variantId\":\""
                + V
                + "\",\"delta\":-1,\"reason\":\"DAMAGED\"}"),
        "adjust at B");

    // The owner, assigned to no store, stocks B; the keeper of A sees none of it.
    String atB =
        data(owner("POST", "/admin/inventory/receive", receiveBody(B, 7, "OWNER-B")))
            .getString("id");
    refusedForStore(keeperOfA("GET", "/admin/inventory/levels?store=" + B, null), "levels of B");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/levels/summary?store=" + B, null), "summary of B");
    refusedForStore(keeperOfA("GET", "/admin/inventory/batches?store=" + B, null), "batches of B");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/movements?store=" + B, null), "movements of B");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/batches/" + atB, null), "a batch at B by id");
    refusedForStore(
        keeperOfA(
            "PUT",
            "/admin/inventory/batches/" + atB + "/material-status",
            "{\"materialStatus\":\"QUARANTINE\"}"),
        "quarantining a batch at B");
    assertThat(owner("GET", "/admin/inventory/levels?store=" + B, null).getStatus(), is(200));

    // Naming no store, a keeper of one store reads that store: A's five, not B's seven.
    Response mine = keeperOfA("GET", "/admin/inventory/levels", null);
    String levels = mine.readEntity(String.class);
    assertThat(levels, mine.getStatus(), is(200));
    assertThat(levels, containsString("\"onHand\":5"));
    assertThat(levels, not(containsString("\"onHand\":7")));
  }

  @Test
  @DisplayName("Stock moves only from a store the caller keeps, and arrives only at one they keep")
  void transfersHoldToTheCallersStores() {
    assertThat(
        owner("POST", "/admin/inventory/receive", receiveBody(A, 4, "FOR-TRANSFER")).getStatus(),
        is(201));
    refusedForStore(
        keeperOfA("POST", "/admin/inventory/transfers", transferBody(B, A)), "sending from B");
    refusedForStore(
        keeperOfA(
            "POST",
            "/admin/inventory/move-orders",
            "{\"fromStoreId\":\""
                + B
                + "\",\"toStoreId\":\""
                + B
                + "\",\"lines\":[{\"variantId\":\""
                + V
                + "\",\"requestedQty\":1}]}"),
        "a move within B");
    Response raised = keeperOfA("POST", "/admin/inventory/transfers", transferBody(A, B));
    assertThat(raised.getStatus(), is(201));
    String id = data(raised).getString("id");
    refusedForStore(
        keeperOfB("POST", "/admin/inventory/transfers/" + id + "/ship", ""),
        "B shipping A's stock");
    assertThat(
        keeperOfA("POST", "/admin/inventory/transfers/" + id + "/ship", "").getStatus(), is(200));
    refusedForStore(
        keeperOfA("POST", "/admin/inventory/transfers/" + id + "/receive", ""), "A receiving at B");
    assertThat(
        keeperOfB("POST", "/admin/inventory/transfers/" + id + "/receive", "").getStatus(),
        is(200));
    refusedForStore(
        send(as("/admin/inventory/transfers/" + id, "STOREKEEPER", KEEPER_B, C), "GET", null),
        "a keeper of C reading a transfer between A and B");
    assertThat(keeperOfB("GET", "/admin/inventory/transfers/" + id, null).getStatus(), is(200));
  }

  /**
   * A cycle count names its store once, on the header; every later step names only the count. A
   * keeper of A could read, count, approve and post B's count by its id, and list every store's.
   */
  @Test
  @DisplayName("A cycle count is opened, read, counted and posted only at a store the caller keeps")
  void cycleCountsHoldToTheCallersStores() {
    String atB = data(owner("POST", "/admin/inventory/cycle-counts", countBody(B))).getString("id");
    String atA =
        data(keeperOfA("POST", "/admin/inventory/cycle-counts", countBody(A))).getString("id");
    String countsOfB = "/admin/inventory/cycle-counts/" + atB;

    refusedForStore(
        keeperOfA("POST", "/admin/inventory/cycle-counts", countBody(B)), "opening a count at B");
    refusedForStore(keeperOfA("GET", countsOfB, null), "B's count by id");
    refusedForStore(
        keeperOfA("POST", countsOfB + "/lines/" + V + "/count", "{\"countedQty\":3}"),
        "counting at B");
    refusedForStore(keeperOfA("POST", countsOfB + "/approve", ""), "approving B's count");
    refusedForStore(keeperOfA("POST", countsOfB + "/adjust", ""), "posting B's count");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/cycle-counts?storeId=" + B, null), "listing B's counts");
    assertThat(statusOfCount(atB), is("OPEN"));

    // Naming no store, a keeper of A lists A's counts and not B's.
    Response mine = keeperOfA("GET", "/admin/inventory/cycle-counts", null);
    String listed = mine.readEntity(String.class);
    assertThat(listed, mine.getStatus(), is(200));
    assertThat(listed, containsString(atA));
    assertThat(listed, not(containsString(atB)));

    assertThat(keeperOfB("GET", countsOfB, null).getStatus(), is(200));
    assertThat(keeperOfB("POST", countsOfB + "/approve", "").getStatus(), is(200));
  }

  @Test
  @DisplayName("Another business's staff never see or move a cycle count, even naming our store")
  void cycleCountsStayInTheirBusiness() {
    String ours =
        data(owner("POST", "/admin/inventory/cycle-counts", countBody(A))).getString("id");
    String path = "/admin/inventory/cycle-counts/" + ours;
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response read = stranger(role, "GET", path, null);
      assertThat(role + " reading", read.getStatus(), is(404));
      read.close();
      Response counted =
          stranger(role, "POST", path + "/lines/" + V + "/count", "{\"countedQty\":3}");
      assertThat(role + " counting", counted.getStatus(), is(404));
      counted.close();
      // A cashier is refused the permission before the count is looked up; everyone else finds
      // none.
      int expected = "CASHIER".equals(role) ? 403 : 404;
      for (String step : new String[] {"approve", "adjust"}) {
        Response r = stranger(role, "POST", path + "/" + step, "");
        assertThat(role + " " + step, r.getStatus(), is(expected));
        r.close();
      }
      for (String list :
          new String[] {
            "/admin/inventory/cycle-counts", "/admin/inventory/cycle-counts?storeId=" + A
          }) {
        Response listed = stranger(role, "GET", list, null);
        String body = listed.readEntity(String.class);
        assertThat(role + " " + list + ": " + body, listed.getStatus(), is(200));
        assertThat(role + " " + list, body, not(containsString(ours)));
      }
    }
    assertThat(statusOfCount(ours), is("OPEN"));
  }

  private static String inventoryBody(String store) {
    return "{\"storeId\":\"" + store + "\",\"notes\":\"Year end\"}";
  }

  private static final String TAG = "{\"variantId\":\"" + V + "\",\"systemQty\":2}";

  private String statusOfInventory(String id) {
    return data(owner("GET", "/admin/inventory/physical-inventories/" + id, null))
        .getString("status");
  }

  @Test
  @DisplayName("A physical inventory is started, read, tagged and completed only at a kept store")
  void physicalInventoriesHoldToTheCallersStores() {
    String atB =
        data(owner("POST", "/admin/inventory/physical-inventories", inventoryBody(B)))
            .getString("id");
    String atA =
        data(keeperOfA("POST", "/admin/inventory/physical-inventories", inventoryBody(A)))
            .getString("id");
    String ofB = "/admin/inventory/physical-inventories/" + atB;

    refusedForStore(
        keeperOfA("POST", "/admin/inventory/physical-inventories", inventoryBody(B)),
        "starting one at B");
    refusedForStore(keeperOfA("GET", ofB, null), "B's by id");
    refusedForStore(keeperOfA("POST", ofB + "/tags", TAG), "tagging at B");
    refusedForStore(
        keeperOfA("POST", ofB + "/tags/" + V + "/count", "{\"countedQty\":3}"), "counting at B");
    refusedForStore(keeperOfA("POST", ofB + "/complete", ""), "completing B's");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/physical-inventories?store=" + B, null), "listing B's");
    assertThat(statusOfInventory(atB), is("OPEN"));

    Response mine = keeperOfA("GET", "/admin/inventory/physical-inventories", null);
    String listed = mine.readEntity(String.class);
    assertThat(listed, mine.getStatus(), is(200));
    assertThat(listed, containsString(atA));
    assertThat(listed, not(containsString(atB)));

    assertThat(keeperOfB("GET", ofB, null).getStatus(), is(200));
    assertThat(keeperOfB("POST", ofB + "/tags", TAG).getStatus(), is(200));
  }

  /**
   * The stock reports under {@code /reports/*} (management-only) take an optional store. Named, it
   * is checked exactly as any other route; unnamed, a manager held to no store still reads the
   * whole business (as before this fix), and one held to some stores reads exactly those, added
   * together — never a store they do not keep, and never nothing at all (SJ-D74 follow-up).
   */
  @Test
  @DisplayName("Stock valuation, unnamed, sums exactly the stores a manager keeps")
  void valuationScopedToTheCallersStores() {
    assertThat(
        owner("POST", "/admin/inventory/receive", receiveBody(A, 10, "VAL-A")).getStatus(),
        is(201));
    assertThat(
        owner("POST", "/admin/inventory/receive", receiveBody(B, 20, "VAL-B")).getStatus(),
        is(201));
    assertThat(
        owner("POST", "/admin/inventory/receive", receiveBody(C, 30, "VAL-C")).getStatus(),
        is(201));

    String path = "/admin/inventory/reports/valuation";

    // A manager held to A alone, naming none, reads A and only A.
    Response mine = managerOf(A, "GET", path, null);
    String mineBody = mine.readEntity(String.class);
    assertThat(mineBody, mine.getStatus(), is(200));
    assertThat(mineBody, containsString(A));
    assertThat(mineBody, not(containsString(B)));
    assertThat(mineBody, not(containsString(C)));

    // Naming a store they don't keep is refused, whatever the answer for "none named" would be.
    refusedForStore(managerOf(A, "GET", path + "?storeId=" + B, null), "a manager of A naming B");

    // Held to A and B, naming none: both, combined -- never C.
    Response combined = managerOf(A + "," + B, "GET", path, null);
    String combinedBody = combined.readEntity(String.class);
    assertThat(combinedBody, combined.getStatus(), is(200));
    assertThat(combinedBody, containsString(A));
    assertThat(combinedBody, containsString(B));
    assertThat(combinedBody, not(containsString(C)));

    // The owner, held to no store, still reads the whole business.
    Response whole = owner("GET", path, null);
    String wholeBody = whole.readEntity(String.class);
    assertThat(wholeBody, whole.getStatus(), is(200));
    assertThat(wholeBody, containsString(A));
    assertThat(wholeBody, containsString(B));
    assertThat(wholeBody, containsString(C));

    // Another business's staff, even naming our store A, see none of our figures; a cashier or
    // storekeeper is refused by the management tier before any store is even looked at.
    for (String role : new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER"}) {
      Response read = stranger(role, "GET", path, null);
      String body = read.readEntity(String.class);
      assertThat(role + ": " + body, read.getStatus(), is(200));
      assertThat(role, body, not(containsString(A)));
      assertThat(role, body, not(containsString(C)));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      Response read = stranger(role, "GET", path, null);
      assertThat(role, read.getStatus(), is(403));
      read.close();
    }
  }

  @Test
  @DisplayName("The shrinkage report, unnamed, sums exactly the stores a manager keeps")
  void shrinkageScopedToTheCallersStores() {
    for (String store : new String[] {A, B, C}) {
      assertThat(
          owner("POST", "/admin/inventory/receive", receiveBody(store, 10, "SHR-" + store))
              .getStatus(),
          is(201));
      assertThat(
          owner(
                  "POST",
                  "/admin/inventory/adjust",
                  "{\"storeId\":\""
                      + store
                      + "\",\"variantId\":\""
                      + V
                      + "\",\"delta\":-1,\"reason\":\"DAMAGED\",\"reasonCode\":\"DAMAGED\"}")
              .getStatus(),
          is(200));
    }

    String path = "/admin/inventory/reports/shrinkage?groupBy=STORE";

    Response mine = managerOf(A, "GET", path, null);
    String mineBody = mine.readEntity(String.class);
    assertThat(mineBody, mine.getStatus(), is(200));
    assertThat(mineBody, containsString(A));
    assertThat(mineBody, not(containsString(B)));
    assertThat(mineBody, not(containsString(C)));

    refusedForStore(managerOf(A, "GET", path + "&storeId=" + B, null), "a manager of A naming B");

    Response combined = managerOf(A + "," + B, "GET", path, null);
    String combinedBody = combined.readEntity(String.class);
    assertThat(combinedBody, combined.getStatus(), is(200));
    assertThat(combinedBody, containsString(A));
    assertThat(combinedBody, containsString(B));
    assertThat(combinedBody, not(containsString(C)));

    Response whole = owner("GET", path, null);
    String wholeBody = whole.readEntity(String.class);
    assertThat(wholeBody, whole.getStatus(), is(200));
    assertThat(wholeBody, containsString(A));
    assertThat(wholeBody, containsString(B));
    assertThat(wholeBody, containsString(C));

    for (String role : new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER"}) {
      Response read = stranger(role, "GET", path, null);
      String body = read.readEntity(String.class);
      assertThat(role + ": " + body, read.getStatus(), is(200));
      assertThat(role, body, not(containsString(A)));
      assertThat(role, body, not(containsString(C)));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      Response read = stranger(role, "GET", path, null);
      assertThat(role, read.getStatus(), is(403));
      read.close();
    }
  }

  @Test
  @DisplayName("Another business's staff never see or move a physical inventory, even naming ours")
  void physicalInventoriesStayInTheirBusiness() {
    String ours =
        data(owner("POST", "/admin/inventory/physical-inventories", inventoryBody(A)))
            .getString("id");
    String path = "/admin/inventory/physical-inventories/" + ours;
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response read = stranger(role, "GET", path, null);
      assertThat(role + " reading", read.getStatus(), is(404));
      read.close();
      Response counted =
          stranger(role, "POST", path + "/tags/" + V + "/count", "{\"countedQty\":3}");
      assertThat(role + " counting", counted.getStatus(), is(404));
      counted.close();
      // A cashier is refused the permission before the count is looked up; everyone else finds
      // none.
      int expected = "CASHIER".equals(role) ? 403 : 404;
      Response tagged = stranger(role, "POST", path + "/tags", TAG);
      assertThat(role + " tagging", tagged.getStatus(), is(expected));
      tagged.close();
      Response completed = stranger(role, "POST", path + "/complete", "");
      assertThat(role + " completing", completed.getStatus(), is(expected));
      completed.close();
      for (String list :
          new String[] {
            "/admin/inventory/physical-inventories",
            "/admin/inventory/physical-inventories?store=" + A
          }) {
        Response listed = stranger(role, "GET", list, null);
        String body = listed.readEntity(String.class);
        assertThat(role + " " + list + ": " + body, listed.getStatus(), is(200));
        assertThat(role + " " + list, body, not(containsString(ours)));
      }
    }
    assertThat(statusOfInventory(ours), is("OPEN"));
  }

  @Test
  @DisplayName("Ending a bond approval and working a kanban card hold to the caller's stores")
  void bondEndAndKanbanCardsHoldToTheCallersStores() {
    assertThat(
        owner(
                "PUT",
                "/admin/inventory/bond/approvals/" + B,
                "{\"approvalNumber\":\"AP-SCOPE-1\",\"regime\":\"EXCISE\"}")
            .getStatus(),
        is(200));
    refusedForStore(
        managerOf(A, "POST", "/admin/inventory/bond/approvals/" + B + "/end", "{}"),
        "a manager of A ending B's approval");
    // nothing moved: the approval is still live
    Response live = owner("GET", "/admin/inventory/bond/approvals", null);
    String approvals = live.readEntity(String.class);
    assertThat(approvals, containsString("AP-SCOPE-1"));
    assertThat(approvals, containsString("\"active\":true"));
    assertThat(
        managerOf(B, "POST", "/admin/inventory/bond/approvals/" + B + "/end", "{}").getStatus(),
        is(200));

    String card =
        data(owner(
                "POST",
                "/admin/inventory/kanban-cards",
                "{\"storeId\":\""
                    + B
                    + "\",\"variantId\":\""
                    + V
                    + "\",\"kanbanType\":\"PRODUCTION\",\"reorderQty\":5}"))
            .getString("id");
    String path = "/admin/inventory/kanban-cards/" + card;
    refusedForStore(keeperOfA("POST", path + "/trigger", "{}"), "trigger B's card from A");
    refusedForStore(keeperOfA("POST", path + "/replenish", ""), "replenish B's card from A");
    assertThat(data(owner("GET", path, null)).getString("status"), is("EMPTY"));
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      for (String step : new String[] {"trigger", "replenish"}) {
        Response r = stranger(role, "POST", path + "/" + step, "{}");
        assertThat(role + " " + step, r.getStatus(), is(404));
        r.close();
      }
    }
    assertThat(keeperOfB("POST", path + "/trigger", "{}").getStatus(), is(200));
  }
}
