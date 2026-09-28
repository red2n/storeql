package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-docking's purchasing side (intent/cross-docking.md): a warehouse order's line allocated to
 * the shops it serves, by hand and from their needs, only while a draft, never beyond the line;
 * announced to inventory-svc on submission and cleared on cancellation; the refusals. inventory-svc
 * and tenant-svc are stubs. Written before the code.
 */
@HelidonTest
class CrossDockIT {

  static final String T = "01a0da00-8a1e-7d2c-a97b-d1b8025478e1";
  static final String T2 = "01a0da00-8a1e-7d2c-a97b-d1b8025478e2";
  static final String OWNER = "01a0da00-8a1e-7d2c-b111-d1b8025478e1";
  static final String DC = "01a0da00-8a1e-7d2c-b222-d1b8025478d1";
  static final String LEEDS = "01a0da00-8a1e-7d2c-b222-d1b8025478e1";
  static final String YORK = "01a0da00-8a1e-7d2c-b222-d1b8025478e2";
  static final String HULL = "01a0da00-8a1e-7d2c-b222-d1b8025478e3";

  private static final PostgresSupport PG;
  private static final JsonStub INVENTORY;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(T2, "GBP", "GB")
        .withWarehouse(T, DC)
        .withStore(T, LEEDS, "GB")
        .withStore(T, YORK, "GB")
        .withStore(T, HULL, "GB");
    INVENTORY = JsonStub.start("inventory-svc");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  @Inject WebTarget target;

  private final UUID beans = Ids.newId();

  @BeforeEach
  void fresh() throws SQLException {
    PurchaseFixtures.truncateAll(PG);
    INVENTORY.reset();
    // The warehouse serves Leeds and York, not Hull.
    INVENTORY.on(
        "GET",
        "/admin/inventory/network/sourcing",
        200,
        "{\"data\":{\"storeId\":\""
            + DC
            + "\",\"warehouse\":true,\"direct\":[],\"shops\":[\""
            + LEEDS
            + "\",\""
            + YORK
            + "\"],\"demand\":[]}}");
  }

  private Response call(String method, String path, String json, String tenant, String role) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", OWNER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER");
  }

  private static JsonObject data(Response r, int expected) {
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(expected));
    return Json.createReader(new StringReader(text)).readObject().getJsonObject("data");
  }

  private static JsonArray list(Response r, int expected) {
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(expected));
    return Json.createReader(new StringReader(text)).readObject().getJsonArray("data");
  }

  private static String code(Response r, int expected) {
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(expected));
    return Json.createReader(new StringReader(text)).readObject().getString("code");
  }

  /** A draft order for forty beans delivered to the store; returns {order, line}. */
  private String[] order(String store, String ownership) {
    String supplier =
        data(
                post(
                    "/suppliers",
                    "{\"name\":\"Bean Co "
                        + Ids.newId()
                        + "\",\"vatRegistered\":false,\"currency\":\"GBP\"}"),
                201)
            .getString("id");
    String po =
        data(
                post(
                    "/purchase-orders",
                    "{\"supplierId\":\""
                        + supplier
                        + "\",\"storeId\":\""
                        + store
                        + "\""
                        + (ownership == null ? "" : ",\"ownership\":\"" + ownership + "\"")
                        + "}"),
                201)
            .getString("id");
    String line =
        data(
                post(
                    "/purchase-orders/" + po + "/lines",
                    "{\"variantId\":\"" + beans + "\",\"qty\":40,\"unitPrice\":2.00}"),
                201)
            .getString("id");
    return new String[] {po, line};
  }

  private static String allocations(String... storeQty) {
    StringBuilder sb = new StringBuilder("{\"allocations\":[");
    for (int i = 0; i < storeQty.length; i += 2) {
      if (i > 0) sb.append(',');
      sb.append("{\"storeId\":\"")
          .append(storeQty[i])
          .append("\",\"qty\":")
          .append(storeQty[i + 1])
          .append('}');
    }
    return sb.append("]}").toString();
  }

  @Test
  void aWarehouseOrdersLineIsAllocatedToItsShopsAndAnnouncedOnSubmission() {
    String[] o = order(DC, null);
    String path = "/purchase-orders/" + o[0] + "/lines/" + o[1] + "/allocations";
    JsonArray set =
        list(call("PUT", path, allocations(LEEDS, "25", YORK, "15"), T, "STOREKEEPER"), 200);
    assertThat(set.size(), is(2));
    // Replaced, not added to.
    list(call("PUT", path, allocations(LEEDS, "20", YORK, "10"), T, "OWNER"), 200);
    JsonArray read =
        list(call("GET", "/purchase-orders/" + o[0] + "/allocations", null, T, "OWNER"), 200);
    assertThat(read.size(), is(2));
    BigDecimal total = BigDecimal.ZERO;
    for (var v : read) total = total.add(v.asJsonObject().getJsonNumber("qty").bigDecimalValue());
    assertThat(total.compareTo(new BigDecimal("30")), is(0));

    // Filled from the shops' needs: the warehouse's shares of the line, as inventory-svc says.
    INVENTORY.on(
        "GET",
        "/admin/inventory/network/needs",
        200,
        "{\"data\":[{\"storeId\":\""
            + LEEDS
            + "\",\"need\":30,\"qty\":26},{\"storeId\":\""
            + YORK
            + "\",\"need\":16,\"qty\":14}]}");
    JsonArray filled = list(post(path + "/fill", "{}"), 200);
    assertThat(filled.size(), is(2));
    for (var v : filled) {
      JsonObject a = v.asJsonObject();
      String want = LEEDS.equals(a.getString("storeId")) ? "26" : "14";
      assertThat(a.getJsonNumber("qty").bigDecimalValue().compareTo(new BigDecimal(want)), is(0));
    }

    // Refusals: a shop the warehouse does not serve, more than the line, nothing, a cashier.
    assertThat(
        code(call("PUT", path, allocations(HULL, "5"), T, "OWNER"), 400),
        is("PURCHASE_ALLOCATION_NOT_SERVED"));
    assertThat(
        code(call("PUT", path, allocations(LEEDS, "30", YORK, "11"), T, "OWNER"), 400),
        is("PURCHASE_ALLOCATION_EXCEEDS_LINE"));
    assertThat(
        code(call("PUT", path, allocations(LEEDS, "0"), T, "OWNER"), 400),
        is("PURCHASE_ALLOCATION_QTY_INVALID"));
    assertThat(call("PUT", path, allocations(LEEDS, "5"), T, "CASHIER").getStatus(), is(403));
    // Another business finds no such order.
    assertThat(
        call("GET", "/purchase-orders/" + o[0] + "/allocations", null, T2, "OWNER").getStatus(),
        is(404));

    // Submitted: the allocations are announced to inventory-svc, and set no more.
    data(post("/purchase-orders/" + o[0] + "/submit", ""), 200);
    String announced =
        Envelopes.scalar(
            PG,
            "SELECT payload FROM purchase.outbox WHERE event_type = 'CrossDockAllocationsSet' AND"
                + " aggregate_id = '"
                + o[0]
                + "'");
    assertThat(announced, containsString("\"warehouseId\":\"" + DC + "\""));
    assertThat(announced, containsString("\"storeId\":\"" + LEEDS + "\",\"qty\":26"));
    assertThat(announced, containsString("\"storeId\":\"" + YORK + "\",\"qty\":14"));
    assertThat(
        code(call("PUT", path, allocations(LEEDS, "5"), T, "OWNER"), 409),
        is("PURCHASE_ALLOCATION_ORDER_NOT_DRAFT"));

    // Cancelled: an empty snapshot says nothing is owed to the shops any more.
    data(post("/purchase-orders/" + o[0] + "/cancel", "{\"reason\":\"supplier out\"}"), 200);
    String last =
        Envelopes.scalar(
            PG,
            "SELECT payload FROM purchase.outbox WHERE event_type = 'CrossDockAllocationsSet' AND"
                + " aggregate_id = '"
                + o[0]
                + "' ORDER BY created_at DESC, id DESC LIMIT 1");
    assertThat(last, containsString("\"allocations\":[]"));
  }

  @Test
  void onlyAWarehousesOwnedDutyPaidOrderIsCrossDocked() {
    String[] shopOrder = order(LEEDS, null);
    assertThat(
        code(
            call(
                "PUT",
                "/purchase-orders/" + shopOrder[0] + "/lines/" + shopOrder[1] + "/allocations",
                allocations(YORK, "5"),
                T,
                "OWNER"),
            400),
        is("PURCHASE_ALLOCATION_NOT_A_WAREHOUSE"));
    String[] consigned = order(DC, "CONSIGNMENT");
    assertThat(
        code(
            call(
                "PUT",
                "/purchase-orders/" + consigned[0] + "/lines/" + consigned[1] + "/allocations",
                allocations(LEEDS, "5"),
                T,
                "OWNER"),
            409),
        is("PURCHASE_ALLOCATION_STOCK_NOT_OWNED"));
    // An order with no allocations announces nothing when submitted.
    String[] plain = order(DC, null);
    data(post("/purchase-orders/" + plain[0] + "/submit", ""), 200);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM purchase.outbox WHERE event_type = 'CrossDockAllocationsSet' AND"
                + " aggregate_id = '"
                + plain[0]
                + "'"),
        is("0"));
  }
}
