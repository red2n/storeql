package com.storeql.inventory.messaging;

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
 * Cross-docking at the warehouse against a real database (intent/cross-docking.md): purchase-svc's
 * allocation snapshot counted as on its way to the shops; the delivery's allocated lines crossing
 * the dock on one transaction — a PENDING transfer per shop shipping from the batch the delivery
 * made, never put away, the surplus put away as any delivery; a short delivery shared fairly; the
 * snapshot replaced and cleared; nothing twice. Written before the code.
 */
@HelidonTest
class CrossDockIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String T = "01a0db00-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0db00-611e-702c-a97b-d1b8025478e2";
  private static final String DC = "01a0db00-611e-703c-a378-a4972ea461d1";
  private static final String LEEDS = "01a0db00-611e-703c-a378-a4972ea461e1";
  private static final String YORK = "01a0db00-611e-703c-a378-a4972ea461e2";
  private static final String APPLES = "01a0db00-611e-7037-a4b7-c854f0266ae1";
  private static final String PEARS = "01a0db00-611e-7037-a4b7-c854f0266ae2";
  private static final String USER = "01a0db00-611e-700b-bde4-50df0324c3e1";

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .with(T2, "GBP", "GB")
            .withWarehouse(T, DC)
            .withStore(T, LEEDS, "GB")
            .withStore(T, YORK, "GB");
    PG.wire("inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject GoodsReceivedHandler receipts;
  @Inject CrossDockAllocationsHandler allocations;

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
          "TRUNCATE TABLE inventory.crossdock_expected, inventory.transfer_proposal_runs,"
              + " inventory.serving_exceptions, inventory.serving_relationships,"
              + " inventory.transfer_order_lines, inventory.transfer_orders,"
              + " inventory.reorder_point_plans, inventory.stock_movements, inventory.reservations,"
              + " inventory.putaway_tasks, inventory.inventory_batches, inventory.processed_events,"
              + " inventory.outbox CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", "OWNER")
            .header("Idempotency-Key", Ids.newId().toString());
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private void serve(String shop) {
    assertThat(
        call(
                "PUT",
                "/admin/inventory/network/serving",
                "{\"storeId\":\"" + shop + "\",\"warehouseId\":\"" + DC + "\",\"leadTimeDays\":1}",
                T)
            .getStatus(),
        is(200));
  }

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
            T));
  }

  /** purchase-svc's snapshot of what the order owes the shops. */
  private void allocated(String tenant, String po, String... storeVariantQty) {
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < storeVariantQty.length; i += 3) {
      if (i > 0) rows.append(',');
      rows.append("{\"variantId\":\"")
          .append(storeVariantQty[i + 1])
          .append("\",\"storeId\":\"")
          .append(storeVariantQty[i])
          .append("\",\"qty\":")
          .append(storeVariantQty[i + 2])
          .append('}');
    }
    allocations.handle(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"CrossDockAllocationsSet\",\"tenantId\":\""
            + tenant
            + "\",\"poId\":\""
            + po
            + "\",\"warehouseId\":\""
            + DC
            + "\",\"allocations\":["
            + rows
            + "]}");
  }

  /** purchase-svc's GoodsReceived for a delivery to the warehouse. */
  private static String delivered(String eventId, String po, String receipt, String lines) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"tenantId\":\""
        + T
        + "\",\"storeId\":\""
        + DC
        + "\",\"refId\":\""
        + receipt
        + "\",\"poId\":\""
        + po
        + "\",\"ownership\":\"OWNED\",\"supplierId\":\""
        + Ids.newId()
        + "\",\"dutyStatus\":\"DUTY_PAID\",\"lines\":["
        + lines
        + "]}";
  }

  private static String line(String variant, int qty) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"batchNo\":\"SUP-1\",\"costPrice\":2.00,\"expiryDate\":\"2026-11-01\"}";
  }

  private JsonArray transfersTo(String store, String tenant) {
    return Envelopes.okArray(
        call("GET", "/admin/inventory/transfers?store=" + store + "&status=PENDING", null, tenant));
  }

  private static BigDecimal qty(JsonObject transfer, String variant) {
    for (JsonValue v : transfer.getJsonArray("lines")) {
      if (variant.equals(v.asJsonObject().getString("variantId"))) {
        return v.asJsonObject().getJsonNumber("requestedQty").bigDecimalValue();
      }
    }
    throw new AssertionError("no " + variant + " in " + transfer);
  }

  // ── a delivery across the dock ─────────────────────────────────────────────

  @Test
  void anAllocatedDeliveryCrossesTheDockAndOnlyTheSurplusIsPutAway() {
    serve(LEEDS);
    serve(YORK);
    plan(LEEDS, APPLES, "10", "2");
    plan(YORK, APPLES, "10", "1");
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "25", YORK, APPLES, "15");

    // What the order owes the shops can be read.
    JsonArray owed =
        Envelopes.okArray(
            call("GET", "/admin/inventory/network/crossdock?purchaseOrderId=" + po, null, T));
    assertThat(owed, hasSize(2));
    assertThat(
        Envelopes.okArray(
            call("GET", "/admin/inventory/network/crossdock?purchaseOrderId=" + po, null, T2)),
        hasSize(0));
    // Owed on the order, the apples are on their way to both shops: a replenishment run proposes
    // nothing for them.
    receive(DC, APPLES, 100);
    JsonObject run =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/network/proposals",
                "{\"warehouseId\":\"" + DC + "\"}",
                T));
    assertThat(run.getInt("transfers"), is(0));

    // Forty-four apples and ten pears arrive: forty apples cross (twenty-five and fifteen), four
    // and the pears are put away.
    String receipt = Ids.newId().toString();
    String event = Ids.newId().toString();
    receipts.handle(delivered(event, po, receipt, line(APPLES, 44) + "," + line(PEARS, 10)));
    JsonArray leeds = transfersTo(LEEDS, T);
    assertThat(leeds, hasSize(1));
    JsonObject toLeeds = leeds.getJsonObject(0);
    assertThat(toLeeds.getString("source"), is("CROSSDOCK"));
    assertThat(toLeeds.getString("status"), is("PENDING"));
    assertThat(toLeeds.getString("transferType"), is("INTRANSIT"));
    assertThat(toLeeds.getString("fromStoreId"), is(DC));
    assertThat(toLeeds.getString("purchaseOrderId"), is(po));
    assertThat(toLeeds.getString("goodsReceiptId"), is(receipt));
    assertThat(qty(toLeeds, APPLES), comparesEqualTo(new BigDecimal("25")));
    assertThat(
        toLeeds.getJsonArray("lines").getJsonObject(0).getString("reason"),
        containsString("cross-docked: 25 allocated"));
    assertThat(
        qty(transfersTo(YORK, T).getJsonObject(0), APPLES), comparesEqualTo(new BigDecimal("15")));
    // The crossing batch is not put away; the surplus and the pears are, as is the hundred
    // received by hand earlier — three tasks at the warehouse.
    String crossBatch =
        Envelopes.scalar(
            PG,
            "SELECT id::text FROM inventory.inventory_batches WHERE store_id = '"
                + DC
                + "' AND variant_id = '"
                + APPLES
                + "' AND received_qty = 40");
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.putaway_tasks WHERE batch_id = '" + crossBatch + "'"),
        is("0"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.putaway_tasks WHERE store_id = '" + DC + "'"),
        is("3"));
    // What the order owed is paid: nothing is owed across the dock any more.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.crossdock_expected WHERE purchase_order_id = '"
                + po
                + "'"),
        is("0"));
    // The same delivery again raises nothing twice.
    receipts.handle(delivered(event, po, receipt, line(APPLES, 44) + "," + line(PEARS, 10)));
    assertThat(transfersTo(LEEDS, T), hasSize(1));

    // Shipped, the Leeds transfer draws the batch the delivery made — not the older hundred.
    String transferId = toLeeds.getString("id");
    Envelopes.ok(call("POST", "/admin/inventory/transfers/" + transferId + "/ship", "{}", T));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT batch_id::text FROM inventory.stock_movements WHERE type = 'TRANSFER' AND"
                + " ref_id = '"
                + transferId
                + "'"),
        is(crossBatch));
    // Another business sees none of it.
    assertThat(transfersTo(LEEDS, T2), hasSize(0));
  }

  @Test
  void aShortDeliveryIsSharedInProportionAndTheRestToTheLeastCover() {
    serve(LEEDS);
    serve(YORK);
    // Leeds has plenty (twenty at one a day: twenty days' cover), York none.
    plan(LEEDS, APPLES, "5", "1");
    plan(YORK, APPLES, "5", "1");
    receive(LEEDS, APPLES, 20);
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "10", YORK, APPLES, "5");
    // Ten arrive against fifteen owed: 10·10/15 → 6, 10·5/15 → 3, the one left to York.
    receipts.handle(
        delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 10)));
    assertThat(
        qty(transfersTo(LEEDS, T).getJsonObject(0), APPLES), comparesEqualTo(new BigDecimal("6")));
    JsonObject york = transfersTo(YORK, T).getJsonObject(0);
    assertThat(qty(york, APPLES), comparesEqualTo(new BigDecimal("4")));
    assertThat(
        york.getJsonArray("lines").getJsonObject(0).getString("reason"),
        containsString("the delivery came short"));
    // What was not delivered is still owed: four to Leeds, one to York.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT sum(qty) FROM inventory.crossdock_expected WHERE purchase_order_id = '"
                + po
                + "'"),
        is("5.000"));
  }

  @Test
  void aSnapshotReplacesWhatWasOwedAndAnEmptyOneClearsIt() {
    serve(LEEDS);
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "10");
    allocated(T, po, LEEDS, APPLES, "12");
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT sum(qty) FROM inventory.crossdock_expected WHERE purchase_order_id = '"
                + po
                + "'"),
        is("12.000"));
    allocated(T, po);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.crossdock_expected WHERE purchase_order_id = '"
                + po
                + "'"),
        is("0"));
    // A delivery on an order nothing is owed on is received as always.
    receipts.handle(delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 8)));
    assertThat(transfersTo(LEEDS, T), hasSize(0));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT sum(remaining_qty) FROM inventory.inventory_batches WHERE store_id = '"
                + DC
                + "'"),
        is("8.000"));
  }
}
