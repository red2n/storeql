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
import java.util.List;
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
              + " inventory.recalls, inventory.outbox CASCADE");
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
    allocations.handle(snapshot(Ids.newId().toString(), tenant, po, storeVariantQty));
  }

  /** The snapshot as the event carries it, under the event id given (a redelivery repeats it). */
  private static String snapshot(
      String eventId, String tenant, String po, String... storeVariantQty) {
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
    return raw(eventId, tenant, po, "[" + rows + "]");
  }

  /** A snapshot event whose allocations (a JSON array, or whatever else) are as given. */
  private static String raw(String eventId, String tenant, String po, String allocationsJson) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"CrossDockAllocationsSet\",\"tenantId\":\""
        + tenant
        + "\",\"poId\":\""
        + po
        + "\",\"warehouseId\":\""
        + DC
        + "\",\"allocations\":"
        + allocationsJson
        + "}";
  }

  /** purchase-svc's GoodsReceived for a delivery to the warehouse. */
  private static String delivered(String eventId, String po, String receipt, String lines) {
    return deliveredTo(T, DC, eventId, po, receipt, lines);
  }

  /** As above, for any business and any store the delivery may be made to. */
  private static String deliveredTo(
      String tenant, String store, String eventId, String po, String receipt, String lines) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"tenantId\":\""
        + tenant
        + "\",\"storeId\":\""
        + store
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
        + ",\"batchNo\":\"SUP-1\",\"costPrice\":2.00,\"expiryDate\":\""
        + java.time.LocalDate.now().plusDays(60)
        + "\"}";
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

  /** A read as any role of any business, optionally held to stores. */
  private Response readAs(String path, String tenant, String roles, String storeIds) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return b.get();
  }

  private static String codeOf(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static String owedPath(String po) {
    return "/admin/inventory/network/crossdock?purchaseOrderId=" + po;
  }

  /** How many rows say what the order owes the shops, for one business. */
  private static String rowsFor(String tenant, String po) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM inventory.crossdock_expected WHERE tenant_id = '"
            + tenant
            + "' AND purchase_order_id = '"
            + po
            + "'");
  }

  /** What the order owes the shops in all, for one business. */
  private static String owedFor(String tenant, String po) {
    return Envelopes.scalar(
        PG,
        "SELECT sum(qty) FROM inventory.crossdock_expected WHERE tenant_id = '"
            + tenant
            + "' AND purchase_order_id = '"
            + po
            + "'");
  }

  /** How many events the consumer has taken as applied. */
  private static String appliedBy(String consumer) {
    return Envelopes.scalar(
        PG, "SELECT count(*) FROM inventory.processed_events WHERE consumer = '" + consumer + "'");
  }

  /** How many batches a business holds at a store. */
  private static String batchesAt(String tenant, String store) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '"
            + tenant
            + "' AND store_id = '"
            + store
            + "'");
  }

  /** An open withdrawal of one product, every lot and date, as the recall flow leaves it. */
  private static void openWithdrawal(String tenant, String variant) {
    String recall = Ids.newId().toString();
    Envelopes.exec(
        PG,
        "INSERT INTO inventory.recalls (id, tenant_id, reference, kind, hazard, reason, source,"
            + " opened_by) VALUES ('"
            + recall
            + "', '"
            + tenant
            + "', 'XD-"
            + Ids.shortRef(Ids.parse(recall))
            + "', 'WITHDRAWAL', 'QUALITY', 'Withdrawn for the test', 'INTERNAL', '"
            + USER
            + "')");
    Envelopes.exec(
        PG,
        "INSERT INTO inventory.recall_items (id, tenant_id, recall_id, variant_id) VALUES ('"
            + Ids.newId()
            + "', '"
            + tenant
            + "', '"
            + recall
            + "', '"
            + variant
            + "')");
  }

  /** The batch a delivery of the product made for what crosses the dock (forty of forty-four). */
  private static String crossingBatch(String variant) {
    return Envelopes.scalar(
        PG,
        "SELECT id::text FROM inventory.inventory_batches WHERE store_id = '"
            + DC
            + "' AND variant_id = '"
            + variant
            + "' AND received_qty = 40");
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

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  void aMalformedSnapshotOrReceiptIsSkippedAndChangesNothing() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "10");
    String notV7 = "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    String good = "{\"variantId\":\"" + APPLES + "\",\"storeId\":\"" + LEEDS + "\",\"qty\":99}";
    String[] malformed = {
      "this is not json",
      "[]",
      // no purchase order named
      "{\"eventId\":\""
          + Ids.newId()
          + "\",\"tenantId\":\""
          + T
          + "\",\"warehouseId\":\""
          + DC
          + "\",\"allocations\":["
          + good
          + "]}",
      // a purchase order that is not an id, and one that is an id of another version
      raw(Ids.newId().toString(), T, "PO-7", "[" + good + "]"),
      raw(Ids.newId().toString(), T, notV7, "[" + good + "]"),
      // no allocations member at all
      "{\"eventId\":\""
          + Ids.newId()
          + "\",\"tenantId\":\""
          + T
          + "\",\"poId\":\""
          + po
          + "\",\"warehouseId\":\""
          + DC
          + "\"}",
      // a good row and then a shop that is not an id: the whole snapshot is refused
      raw(
          Ids.newId().toString(),
          T,
          po,
          "["
              + good
              + ",{\"variantId\":\""
              + PEARS
              + "\",\"storeId\":\""
              + notV7
              + "\",\"qty\":1}]"),
      // a quantity that is not a number
      raw(
          Ids.newId().toString(),
          T,
          po,
          "[{\"variantId\":\"" + APPLES + "\",\"storeId\":\"" + LEEDS + "\",\"qty\":\"lots\"}]"),
    };
    for (String event : malformed) {
      allocations.handle(event);
    }
    // What was owed stands, and not one of them was taken as applied.
    assertThat(rowsFor(T, po), is("1"));
    assertThat(owedFor(T, po), is("10.000"));
    assertThat(appliedBy(CrossDockAllocationsHandler.CONSUMER), is("1"));

    // A receipt that names no business is skipped too: nothing is received or sent on.
    receipts.handle(
        deliveredTo(
            "not-a-business",
            DC,
            Ids.newId().toString(),
            po,
            Ids.newId().toString(),
            line(APPLES, 10)));
    assertThat(transfersTo(LEEDS, T), hasSize(0));
    assertThat(batchesAt(T, DC), is("0"));
    assertThat(owedFor(T, po), is("10.000"));
  }

  @Test
  void anAllocationOfNothingOrLessIsNeverOwed() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "10");
    // A zero and a negative quantity are not allocations: the snapshot replaces what was owed and
    // owes nothing, and the delivery that follows crosses nothing.
    allocated(T, po, LEEDS, APPLES, "0", YORK, APPLES, "-5");
    assertThat(rowsFor(T, po), is("0"));
    assertThat(Envelopes.okArray(readAs(owedPath(po), T, "OWNER", null)), hasSize(0));
    receipts.handle(
        delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 10)));
    assertThat(transfersTo(LEEDS, T), hasSize(0));
    assertThat(transfersTo(YORK, T), hasSize(0));
    assertThat(batchesAt(T, DC), is("1"));
  }

  @Test
  void aRedeliveredOlderSnapshotNeverRewindsTheNewerOne() {
    String po = Ids.newId().toString();
    String older = Ids.newId().toString();
    allocations.handle(snapshot(older, T, po, LEEDS, APPLES, "10"));
    allocated(T, po, LEEDS, APPLES, "12");
    assertThat(owedFor(T, po), is("12.000"));
    // Kafka hands the older one over again: it was applied once, and is not applied now.
    allocations.handle(snapshot(older, T, po, LEEDS, APPLES, "10"));
    assertThat(owedFor(T, po), is("12.000"));
    assertThat(rowsFor(T, po), is("1"));
    assertThat(appliedBy(CrossDockAllocationsHandler.CONSUMER), is("2"));
  }

  @Test
  void anotherBusinessNeitherSeesNorChangesWhatOurOrderOwes() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "25", YORK, APPLES, "15");

    // Whatever their role, another business is told nothing is owed on our order.
    for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER")) {
      assertThat(role, Envelopes.okArray(readAs(owedPath(po), T2, role, null)), hasSize(0));
    }
    // A shopper is refused at the door, of either business.
    for (String tenant : List.of(T, T2)) {
      assertThat(
          tenant, codeOf(readAs(owedPath(po), tenant, "CUSTOMER", null), 403), is("FORBIDDEN"));
    }

    // Their snapshot for an order of the very same id replaces only their own rows, and their
    // empty one clears only theirs.
    allocations.handle(snapshot(Ids.newId().toString(), T2, po, LEEDS, APPLES, "1"));
    assertThat(owedFor(T2, po), is("1.000"));
    assertThat(owedFor(T, po), is("40.000"));
    assertThat(rowsFor(T, po), is("2"));
    allocations.handle(snapshot(Ids.newId().toString(), T2, po));
    assertThat(rowsFor(T2, po), is("0"));
    assertThat(owedFor(T, po), is("40.000"));

    // Their delivery of that order to that warehouse is received as theirs: nothing of ours is
    // sent across the dock or drawn down.
    receipts.handle(
        deliveredTo(T2, DC, Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 44)));
    assertThat(batchesAt(T2, DC), is("1"));
    assertThat(batchesAt(T, DC), is("0"));
    assertThat(transfersTo(LEEDS, T), hasSize(0));
    assertThat(transfersTo(YORK, T), hasSize(0));
    assertThat(transfersTo(LEEDS, T2), hasSize(0));
    assertThat(owedFor(T, po), is("40.000"));
    assertThat(Envelopes.okArray(readAs(owedPath(po), T, "OWNER", null)), hasSize(2));
  }

  @Test
  void aKeeperSeesOnlyWhatIsOwedAtTheStoresTheyKeep() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "25", YORK, APPLES, "15");

    // A keeper of a store the order does not touch is owed nothing.
    assertThat(
        Envelopes.okArray(readAs(owedPath(po), T, "STOREKEEPER", Ids.newId().toString())),
        hasSize(0));
    // A keeper of one shop sees that shop's line and not the other's.
    JsonArray leeds = Envelopes.okArray(readAs(owedPath(po), T, "STOREKEEPER", LEEDS));
    assertThat(leeds, hasSize(1));
    assertThat(leeds.getJsonObject(0).getString("storeId"), is(LEEDS));
    // The warehouse's keeper sees every line, and a manager of both shops sees both.
    assertThat(Envelopes.okArray(readAs(owedPath(po), T, "STOREKEEPER", DC)), hasSize(2));
    assertThat(
        Envelopes.okArray(readAs(owedPath(po), T, "MANAGER", LEEDS + "," + YORK)), hasSize(2));
    // Nothing the readers did moved what is owed.
    assertThat(owedFor(T, po), is("40.000"));
    assertThat(rowsFor(T, po), is("2"));
  }

  @Test
  void aReadWithNoOrderOrABadOneIsRefused() {
    String path = "/admin/inventory/network/crossdock";
    String notV7 = "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    for (String url :
        List.of(path, path + "?purchaseOrderId=PO-7", path + "?purchaseOrderId=" + notV7)) {
      assertThat(url, codeOf(readAs(url, T, "OWNER", null), 400), is("INVALID_UUID"));
    }
  }

  @Test
  void aConsignmentOrBondedDeliveryNeverCrossesTheDock() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "10");
    // The supplier's own stock on consignment, and goods held in bond, are not the business's to
    // send on: each is received at the warehouse as it arrived and what the order owes stays owed.
    receipts.handle(
        delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 10))
            .replace("\"ownership\":\"OWNED\"", "\"ownership\":\"CONSIGNMENT\""));
    receipts.handle(
        delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 10))
            .replace("\"dutyStatus\":\"DUTY_PAID\"", "\"dutyStatus\":\"DUTY_SUSPENDED\""));
    assertThat(transfersTo(LEEDS, T), hasSize(0));
    assertThat(owedFor(T, po), is("10.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + DC
                + "' AND ownership = 'CONSIGNMENT'"),
        is("1"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + DC
                + "' AND duty_status = 'DUTY_SUSPENDED'"),
        is("1"));
  }

  @Test
  void aDeliveryToAnyStoreButTheOrdersWarehouseCrossesNothing() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "10");
    // The same order delivered to the shop itself is received there: it never crossed a dock.
    receipts.handle(
        deliveredTo(T, LEEDS, Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 8)));
    assertThat(transfersTo(LEEDS, T), hasSize(0));
    assertThat(owedFor(T, po), is("10.000"));
    assertThat(batchesAt(T, LEEDS), is("1"));
    assertThat(batchesAt(T, DC), is("0"));
  }

  @Test
  void aCrossingBatchAnOpenRecallCoversIsHeldAndItsTransferShipsNothing() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "25", YORK, APPLES, "15");
    openWithdrawal(T, APPLES);
    receipts.handle(
        delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 44)));

    // Held the moment it arrived, though it is never put away.
    String crossBatch = crossingBatch(APPLES);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT material_status FROM inventory.inventory_batches WHERE id = '"
                + crossBatch
                + "'"),
        is("RECALLED"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT quarantined_on FROM inventory.recall_batches WHERE batch_id = '"
                + crossBatch
                + "'"),
        is("ARRIVAL"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.putaway_tasks WHERE batch_id = '" + crossBatch + "'"),
        is("0"));

    // The shops' transfers were raised, but held stock cannot cross: shipping is refused and
    // nothing leaves the warehouse.
    JsonArray leeds = transfersTo(LEEDS, T);
    assertThat(leeds, hasSize(1));
    String transferId = leeds.getJsonObject(0).getString("id");
    assertThat(
        codeOf(call("POST", "/admin/inventory/transfers/" + transferId + "/ship", "{}", T), 422),
        is("INSUFFICIENT_STOCK"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT status FROM inventory.transfer_orders WHERE id = '" + transferId + "'"),
        is("PENDING"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.stock_movements WHERE type = 'TRANSFER'"),
        is("0"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '"
                + crossBatch
                + "'"),
        is("40.000"));
  }

  @Test
  void anotherBusinessesRecallDoesNotHoldOurCrossingBatch() {
    String po = Ids.newId().toString();
    allocated(T, po, LEEDS, APPLES, "25", YORK, APPLES, "15");
    // Theirs, over the very same product: it holds nothing of ours.
    openWithdrawal(T2, APPLES);
    receipts.handle(
        delivered(Ids.newId().toString(), po, Ids.newId().toString(), line(APPLES, 44)));

    String crossBatch = crossingBatch(APPLES);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT material_status FROM inventory.inventory_batches WHERE id = '"
                + crossBatch
                + "'"),
        is("AVAILABLE"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.recall_batches WHERE batch_id = '" + crossBatch + "'"),
        is("0"));
    // So the transfer ships, drawing the batch the delivery made.
    String transferId = transfersTo(LEEDS, T).getJsonObject(0).getString("id");
    Envelopes.ok(call("POST", "/admin/inventory/transfers/" + transferId + "/ship", "{}", T));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT batch_id::text FROM inventory.stock_movements WHERE type = 'TRANSFER' AND"
                + " ref_id = '"
                + transferId
                + "'"),
        is(crossBatch));
  }
}
