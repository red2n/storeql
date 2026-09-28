package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Depot / DC replenishment against a real database (intent/depot-dc-replenishment.md): the network
 * set with warehouses only, a run proposing DRAFT transfers for the shops below their reorder
 * point, released, shipped and received; a short warehouse sharing fairly; the refusals. tenant-svc
 * is a stub that knows which stores are warehouses. Written before the code.
 */
@HelidonTest
class NetworkIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String T = "01a0d900-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0d900-611e-702c-a97b-d1b8025478e2";
  private static final String DC = "01a0d900-611e-703c-a378-a4972ea461d1";
  private static final String DC2 = "01a0d900-611e-703c-a378-a4972ea461d2";
  private static final String LEEDS = "01a0d900-611e-703c-a378-a4972ea461e1";
  private static final String YORK = "01a0d900-611e-703c-a378-a4972ea461e2";
  private static final String HULL = "01a0d900-611e-703c-a378-a4972ea461e3";
  private static final String APPLES = "01a0d900-611e-7037-a4b7-c854f0266ae1";
  private static final String PEARS = "01a0d900-611e-7037-a4b7-c854f0266ae2";
  private static final String USER = "01a0d900-611e-700b-bde4-50df0324c3e1";

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .with(T2, "GBP", "GB")
            .withWarehouse(T, DC)
            .withWarehouse(T, DC2)
            .withStore(T, LEEDS, "GB")
            .withStore(T, YORK, "GB")
            .withStore(T, HULL, "GB");
    PG.wire("inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE inventory.transfer_proposal_runs, inventory.serving_exceptions,"
              + " inventory.serving_relationships, inventory.transfer_order_lines,"
              + " inventory.transfer_orders, inventory.reorder_point_plans,"
              + " inventory.stock_movements, inventory.reservations, inventory.putaway_tasks,"
              + " inventory.inventory_batches, inventory.outbox CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(
      String method, String path, String json, String tenant, String roles, String storeIds) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      case "DELETE" -> b.delete();
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response call(String method, String path, String json, String roles) {
    return call(method, path, json, T, roles, null);
  }

  private Response proposeWithKey(String warehouse, String key) {
    return com.storeql.test.WebTargets.at(target, "/admin/inventory/network/proposals")
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", USER)
        .header("X-Roles", "STOREKEEPER")
        .header("Idempotency-Key", key)
        .post(Entity.entity("{\"warehouseId\":\"" + warehouse + "\"}", MediaType.APPLICATION_JSON));
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static String serving(String store, String warehouse, int lead) {
    return "{\"storeId\":\""
        + store
        + "\",\"warehouseId\":\""
        + warehouse
        + "\",\"leadTimeDays\":"
        + lead
        + "}";
  }

  private void serve(String store, String warehouse, int lead) {
    assertThat(
        call("PUT", "/admin/inventory/network/serving", serving(store, warehouse, lead), "MANAGER")
            .getStatus(),
        is(200));
  }

  private void receive(String store, String variant, int qty) {
    Envelopes.created(
        call(
            "POST",
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + variant
                + "\",\"qty\":"
                + qty
                + ",\"costPrice\":1.00}",
            "OWNER"));
  }

  /** A shop's reorder plan as a compute would leave it. */
  private static void plan(String store, String variant, String rop, String avgDaily) {
    Envelopes.scalar(
        PG,
        "INSERT INTO inventory.reorder_point_plans (id, tenant_id, store_id, variant_id,"
            + " lead_time_days, avg_daily_demand, rop, computed_at) VALUES ('"
            + Ids.newId()
            + "','"
            + T
            + "','"
            + store
            + "','"
            + variant
            + "',2,"
            + avgDaily
            + ","
            + rop
            + ",now()) RETURNING id::text");
  }

  private BigDecimal available(String store, String variant) {
    for (JsonValue v :
        Envelopes.okArray(call("GET", "/admin/inventory/levels?store=" + store, null, "OWNER"))) {
      if (variant.equals(v.asJsonObject().getString("variantId"))) {
        return v.asJsonObject().getJsonNumber("available").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  private static JsonObject lineOf(JsonObject transfer, String variant) {
    for (JsonValue v : transfer.getJsonArray("lines")) {
      if (variant.equals(v.asJsonObject().getString("variantId"))) return v.asJsonObject();
    }
    throw new AssertionError("no " + variant + " line in " + transfer);
  }

  // ── the network ────────────────────────────────────────────────────────────

  @Test
  void theNetworkIsSetWithWarehousesServingShopsOnly() {
    serve(LEEDS, DC, 2);
    // Set again, the relationship is replaced, not doubled.
    serve(LEEDS, DC2, 3);
    serve(LEEDS, DC, 2);
    serve(YORK, DC, 1);
    JsonArray network =
        Envelopes.okArray(call("GET", "/admin/inventory/network/serving", null, "OWNER"));
    assertThat(network, hasSize(2));

    assertThat(
        code(
            call("PUT", "/admin/inventory/network/serving", serving(HULL, YORK, 1), "MANAGER"),
            400),
        is("INVENTORY_SERVING_NOT_A_WAREHOUSE"));
    assertThat(
        code(call("PUT", "/admin/inventory/network/serving", serving(DC2, DC, 1), "MANAGER"), 400),
        is("INVENTORY_SERVING_WAREHOUSE_TO_WAREHOUSE"));
    assertThat(
        code(call("PUT", "/admin/inventory/network/serving", serving(DC, DC, 1), "MANAGER"), 400),
        is("INVENTORY_SERVING_SELF"));
    assertThat(
        code(
            call(
                "PUT",
                "/admin/inventory/network/serving",
                serving(Ids.newId().toString(), DC, 1),
                "MANAGER"),
            400),
        is("INVENTORY_SERVING_STORE_UNKNOWN"));
    assertThat(
        code(
            call("PUT", "/admin/inventory/network/serving", serving(HULL, DC, 91), "MANAGER"), 400),
        is("INVENTORY_SERVING_LEAD_TIME_INVALID"));
    assertThat(
        call("PUT", "/admin/inventory/network/serving", serving(HULL, DC, 1), "STOREKEEPER")
            .getStatus(),
        is(403));

    // Pears at Leeds are bought direct; an unserved shop has nothing to except.
    String direct = "/admin/inventory/network/serving/" + LEEDS + "/direct/" + PEARS;
    assertThat(call("PUT", direct, "{}", "MANAGER").getStatus(), is(204));
    assertThat(call("PUT", direct, "{}", "MANAGER").getStatus(), is(204));
    assertThat(
        code(
            call(
                "PUT",
                "/admin/inventory/network/serving/" + HULL + "/direct/" + PEARS,
                "{}",
                "MANAGER"),
            404),
        is("INVENTORY_SERVING_NOT_FOUND"));
    JsonObject sourcing =
        Envelopes.ok(
            call("GET", "/admin/inventory/network/sourcing?storeId=" + LEEDS, null, "STOREKEEPER"));
    assertThat(sourcing.getString("servedBy"), is(DC));
    assertThat(sourcing.getInt("leadTimeDays"), is(2));
    assertThat(sourcing.getJsonArray("direct").getString(0), is(PEARS));
    assertThat(sourcing.getBoolean("warehouse"), is(false));

    // Another business sees none of it.
    assertThat(
        Envelopes.okArray(call("GET", "/admin/inventory/network/serving", null, T2, "OWNER", null)),
        hasSize(0));
    // A keeper of Hull sees none of Leeds's or York's.
    assertThat(
        Envelopes.okArray(
            call("GET", "/admin/inventory/network/serving", null, T, "STOREKEEPER", HULL)),
        hasSize(0));

    assertThat(call("DELETE", direct, null, "MANAGER").getStatus(), is(204));
    assertThat(
        code(call("DELETE", direct, null, "MANAGER"), 404),
        is("INVENTORY_SERVING_EXCEPTION_NOT_FOUND"));
    assertThat(
        call("DELETE", "/admin/inventory/network/serving/" + YORK, null, "MANAGER").getStatus(),
        is(204));
    assertThat(
        code(call("DELETE", "/admin/inventory/network/serving/" + YORK, null, "MANAGER"), 404),
        is("INVENTORY_SERVING_NOT_FOUND"));
  }

  // ── a run ──────────────────────────────────────────────────────────────────

  @Test
  void aRunProposesDraftTransfersThatAreReleasedShippedAndReceived() {
    serve(LEEDS, DC, 2);
    serve(YORK, DC, 1);
    String pearsDirect = "/admin/inventory/network/serving/" + LEEDS + "/direct/" + PEARS;
    assertThat(call("PUT", pearsDirect, "{}", "MANAGER").getStatus(), is(204));
    receive(DC, APPLES, 100);
    receive(DC, PEARS, 50);
    // Leeds: 4 apples at a reorder point of 10, selling 1.5 a day; its pears are bought direct.
    receive(LEEDS, APPLES, 4);
    plan(LEEDS, APPLES, "10", "1.5");
    plan(LEEDS, PEARS, "10", "1");
    // York: 20 apples at a reorder point of 10 — nothing needed.
    receive(YORK, APPLES, 20);
    plan(YORK, APPLES, "10", "1");

    JsonObject run =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\"}",
                "STOREKEEPER"));
    assertThat(run.getInt("shops"), is(2));
    assertThat(run.getInt("transfers"), is(1));
    assertThat(run.getInt("lines"), is(1));
    assertThat(run.getInt("shortLines"), is(0));
    JsonObject transfer = run.getJsonArray("transferOrders").getJsonObject(0);
    assertThat(transfer.getString("status"), is("DRAFT"));
    assertThat(transfer.getString("source"), is("PROPOSAL"));
    assertThat(transfer.getString("fromStoreId"), is(DC));
    assertThat(transfer.getString("toStoreId"), is(LEEDS));
    assertThat(transfer.getJsonArray("lines"), hasSize(1));
    // Back to 10 (6) plus 1.5 a day over 2 days' lead and 7 days' cover (13.5): 19.5.
    JsonObject apples = lineOf(transfer, APPLES);
    assertThat(
        apples.getJsonNumber("requestedQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("19.5")));
    assertThat(
        apples.getString("reason"), containsString("on hand 4 + inbound 0 = 4 ≤ reorder point 10"));
    String transferId = transfer.getString("id");

    // While a draft waits, a second run is refused; the same key is the same run.
    assertThat(
        code(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\"}",
                "STOREKEEPER"),
            409),
        is("INVENTORY_PROPOSAL_OPEN"));

    // The warehouse's purchase side sees what its shops need and what is committed to them.
    JsonObject dcSourcing =
        Envelopes.ok(
            call("GET", "/admin/inventory/network/sourcing?storeId=" + DC, null, "STOREKEEPER"));
    assertThat(dcSourcing.getBoolean("warehouse"), is(true));
    assertThat(dcSourcing.getJsonArray("shops"), hasSize(2));
    JsonObject appleDemand = null;
    for (JsonValue v : dcSourcing.getJsonArray("demand")) {
      assertThat(
          "a product Leeds buys direct and York does not stock is not the warehouse's demand",
          v.asJsonObject().getString("variantId"),
          is(APPLES));
      appleDemand = v.asJsonObject();
    }
    assertThat(
        appleDemand.getJsonNumber("next28").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("70")));
    assertThat(
        appleDemand.getJsonNumber("committed").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("19.5")));
    assertThat(appleDemand.getInt("shops"), is(2));

    // Released by the warehouse's keeper, it is an ordinary transfer; released once.
    String release = "/admin/inventory/transfers/" + transferId + "/release";
    assertThat(call("POST", release, "{}", T, "STOREKEEPER", LEEDS).getStatus(), is(403));
    JsonObject released = Envelopes.ok(call("POST", release, "{}", T, "STOREKEEPER", DC));
    assertThat(released.getString("status"), is("PENDING"));
    assertThat(
        code(call("POST", release, "{}", "STOREKEEPER"), 409), is("INVENTORY_TRANSFER_NOT_DRAFT"));

    // Released but not shipped, it is already on its way as far as Leeds's position goes: a run now
    // proposes nothing more.
    JsonObject again =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\"}",
                "STOREKEEPER"));
    assertThat(again.getInt("transfers"), is(0));

    Envelopes.ok(
        call("POST", "/admin/inventory/transfers/" + transferId + "/ship", "{}", "STOREKEEPER"));
    assertThat(available(DC, APPLES), comparesEqualTo(new BigDecimal("80.5")));
    Envelopes.ok(
        call("POST", "/admin/inventory/transfers/" + transferId + "/receive", "{}", "STOREKEEPER"));
    assertThat(available(LEEDS, APPLES), comparesEqualTo(new BigDecimal("23.5")));
    // The transfer's events say what moved.
    String shipped =
        Envelopes.scalar(
            PG,
            "SELECT payload FROM inventory.outbox WHERE event_type = 'TransferOrderShipped' AND"
                + " aggregate_id = '"
                + transferId
                + "'");
    assertThat(shipped, containsString("\"variantId\":\"" + APPLES + "\",\"qty\":19.5"));
    String received =
        Envelopes.scalar(
            PG,
            "SELECT payload FROM inventory.outbox WHERE event_type = 'TransferOrderReceived' AND"
                + " aggregate_id = '"
                + transferId
                + "'");
    assertThat(received, containsString("\"fromStoreId\":\"" + DC + "\""));
    assertThat(received, containsString("\"qty\":19.5"));
  }

  @Test
  void aRetriedRunIsTheSameRunAndADraftMayBeDiscarded() {
    serve(LEEDS, DC, 0);
    receive(DC, APPLES, 50);
    plan(LEEDS, APPLES, "5", "1");
    String key = Ids.newId().toString();
    JsonObject first = Envelopes.created(proposeWithKey(DC, key));
    JsonObject retry = Envelopes.created(proposeWithKey(DC, key));
    assertThat(retry.getString("id"), is(first.getString("id")));
    assertThat(retry.getJsonArray("transferIds"), hasSize(1));
    String transferId = first.getJsonArray("transferIds").getString(0);
    JsonObject cancelled =
        Envelopes.ok(
            call(
                "POST",
                "/admin/inventory/transfers/" + transferId + "/cancel",
                "{}",
                "STOREKEEPER"));
    assertThat(cancelled.getString("status"), is("CANCELLED"));
    // Discarded, nothing waits: a new run may be made.
    assertThat(
        call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\"}",
                "STOREKEEPER")
            .getStatus(),
        is(201));
  }

  // ── a short warehouse ──────────────────────────────────────────────────────

  @Test
  void aShortWarehouseSharesInProportionToNeed() {
    serve(LEEDS, DC, 0);
    serve(YORK, DC, 0);
    receive(DC, APPLES, 10);
    // Each needs 10 more than it has at a reorder point of 10 with no demand to cover: Leeds holds
    // 0 at 1 a day (no cover), York holds 0 at 1 a day too; 10 on hand shared 5 and 5.
    plan(LEEDS, APPLES, "10", "0");
    plan(YORK, APPLES, "10", "0");
    JsonObject run =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\",\"coverDays\":1}",
                "STOREKEEPER"));
    assertThat(run.getInt("transfers"), is(2));
    assertThat(run.getInt("shortLines"), is(2));
    BigDecimal total = BigDecimal.ZERO;
    for (JsonValue t : run.getJsonArray("transferOrders")) {
      JsonObject line = lineOf(t.asJsonObject(), APPLES);
      assertThat(
          line.getJsonNumber("requestedQty").bigDecimalValue(),
          comparesEqualTo(new BigDecimal("5")));
      assertThat(line.getString("reason"), containsString("the warehouse is short"));
      total = total.add(line.getJsonNumber("requestedQty").bigDecimalValue());
    }
    assertThat(total, comparesEqualTo(BigDecimal.TEN));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  void runsAreTheWarehousesAndRefusedWhereTheyCannotBe() {
    String body = "{\"warehouseId\":\"" + DC + "\"}";
    assertThat(
        code(call("POST", "/admin/inventory/network/proposals", body, "STOREKEEPER"), 409),
        is("INVENTORY_PROPOSAL_NOTHING_SERVED"));
    serve(LEEDS, DC, 1);
    assertThat(
        call("POST", "/admin/inventory/network/proposals", body, "CASHIER").getStatus(), is(403));
    assertThat(
        call("POST", "/admin/inventory/network/proposals", body, T, "STOREKEEPER", LEEDS)
            .getStatus(),
        is(403));
    assertThat(
        code(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + LEEDS + "\"}",
                "STOREKEEPER"),
            400),
        is("INVENTORY_SERVING_NOT_A_WAREHOUSE"));
    assertThat(
        code(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\",\"coverDays\":0}",
                "STOREKEEPER"),
            400),
        is("INVENTORY_PROPOSAL_COVER_INVALID"));
    // Another business has no warehouse of this one's and releases nothing of it.
    receive(DC, APPLES, 10);
    plan(LEEDS, APPLES, "5", "1");
    JsonObject run =
        Envelopes.created(call("POST", "/admin/inventory/network/proposals", body, "STOREKEEPER"));
    String transferId = run.getJsonArray("transferIds").getString(0);
    assertThat(
        call(
                "POST",
                "/admin/inventory/transfers/" + transferId + "/release",
                "{}",
                T2,
                "STOREKEEPER",
                null)
            .getStatus(),
        is(404));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/network/proposals?warehouseId=" + DC, null, "OWNER"))
            .size(),
        is(1));
  }

  // ── stock by store, for routing an online order ────────────────────────────

  @Test
  void theStockOfEveryStoreIsReadForTheProductsNamedByStaffOnly() {
    receive(LEEDS, APPLES, 4);
    receive(YORK, APPLES, 9);
    receive(YORK, PEARS, 2);
    JsonObject stock =
        Envelopes.ok(
            call(
                "GET",
                "/admin/inventory/network/stock?variants=" + APPLES,
                null,
                T,
                "STOREKEEPER",
                null));
    JsonArray levels = stock.getJsonArray("levels");
    assertThat(levels, hasSize(2));
    for (JsonValue v : levels) {
      JsonObject l = v.asJsonObject();
      String want = LEEDS.equals(l.getString("storeId")) ? "4" : "9";
      assertThat(
          l.getJsonNumber("available").bigDecimalValue(), comparesEqualTo(new BigDecimal(want)));
    }
    assertThat(
        call("GET", "/admin/inventory/network/stock?variants=" + APPLES, null, T, "CUSTOMER", null)
            .getStatus(),
        is(403));
    assertThat(
        Envelopes.ok(
                call(
                    "GET",
                    "/admin/inventory/network/stock?variants=" + APPLES,
                    null,
                    T2,
                    "OWNER",
                    null))
            .getJsonArray("levels"),
        hasSize(0));
  }
}
