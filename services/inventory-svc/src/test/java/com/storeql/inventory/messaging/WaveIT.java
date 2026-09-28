package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
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
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wave picking (readiness review, Supply chain, warehouse &amp; logistics).
 *
 * <p>Confirmed online orders wait at their store; a wave gathers them into one walk through the
 * zones, directed to the batch the picking rule chooses and naming the orders each line serves;
 * completing it deducts exactly what was picked, consumes the holds, tells order-svc to fulfil the
 * orders, and a later OrderFulfilled for those lines deducts nothing twice. Written before the
 * code.
 */
@HelidonTest
class WaveIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a092ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a092ae-611e-702c-a97b-d1b8025478e2";
  private static final String STORE = "01a092ae-611e-703c-a378-a4972ea461e1";
  private static final String OTHER_STORE = "01a092ae-611e-703c-a378-a4972ea461e2";
  private static final String APPLES = "01a092ae-611e-7037-a4b7-c854f0266ae1";
  private static final String PEARS = "01a092ae-611e-7037-a4b7-c854f0266ae2";
  private static final String ZONE_A = "01a092ae-611e-7041-a4b7-c854f0266aa1";
  private static final String ZONE_B = "01a092ae-611e-7041-a4b7-c854f0266aa2";
  private static final String USER = "01a092ae-611e-700b-bde4-50df0324c3e1";

  @Inject WebTarget target;
  @Inject OrderEventHandler orders;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE inventory.wave_picked_lines, inventory.pick_wave_allocations,"
              + " inventory.pick_wave_lines, inventory.pick_waves, inventory.awaiting_order_lines,"
              + " inventory.awaiting_orders, inventory.putaway_tasks, inventory.putaway_rules,"
              + " inventory.picking_rule_zone_priorities, inventory.picking_rule_assignments,"
              + " inventory.picking_rules, inventory.lot_genealogy, inventory.stock_movements,"
              + " inventory.reservations, inventory.sale_revenue, inventory.inventory_batches,"
              + " inventory.processed_events, inventory.outbox CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
    return call(method, path, json, tenant, roles, null);
  }

  private Response call(
      String method, String path, String json, String tenant, String roles, String storeIds) {
    return call(method, path, json, tenant, roles, storeIds, null);
  }

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String roles,
      String storeIds,
      String idempotencyKey) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    if ("POST".equals(method)) {
      b =
          b.header(
              "Idempotency-Key", idempotencyKey != null ? idempotencyKey : Ids.newId().toString());
    }
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "STOREKEEPER");
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static BigDecimal num(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private static JsonObject find(JsonArray rows, String field, String value) {
    for (JsonValue v : rows) {
      if (value.equals(v.asJsonObject().getString(field, null))) return v.asJsonObject();
    }
    throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
  }

  /** Stock on the shelf, placed in a zone, with a lot and a date. */
  private JsonObject receive(String variant, int qty, String zone, String lot, String expiry) {
    return receive(variant, qty, zone, lot, expiry, "");
  }

  /** As above, with more of the receipt's fields (a consignment's owner, say). */
  private JsonObject receive(
      String variant, int qty, String zone, String lot, String expiry, String extraJson) {
    return Envelopes.created(
        call(
            "POST",
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + STORE
                + "\",\"variantId\":\""
                + variant
                + "\",\"qty\":"
                + qty
                + ",\"batchNo\":\""
                + lot
                + "\",\"costPrice\":1.00"
                + (zone == null ? "" : ",\"zoneId\":\"" + zone + "\"")
                + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
                + extraJson
                + "}",
            T,
            "OWNER"));
  }

  /** The hold checkout places for a line, as order-svc does. */
  private void hold(String orderId, String variant, int qty) {
    assertThat(
        post(
                "/inventory/reservations",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + ",\"orderId\":\""
                    + orderId
                    + "\"}")
            .getStatus(),
        is(201));
  }

  private static String confirmed(
      String eventId,
      String tenant,
      String orderId,
      String store,
      String fulfilment,
      String lines) {
    return confirmed(eventId, tenant, orderId, store, fulfilment, lines, null);
  }

  /** As above, saying when order-svc confirmed the order ({@code occurredAt}). */
  private static String confirmed(
      String eventId,
      String tenant,
      String orderId,
      String store,
      String fulfilment,
      String lines,
      String occurredAt) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderConfirmed\","
        + (occurredAt == null ? "" : "\"occurredAt\":\"" + occurredAt + "\",")
        + "\"tenantId\":\""
        + tenant
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + store
        + "\",\"channel\":\"ONLINE\",\"customerId\":null,\"total\":10.00,\"taxAmount\":0,"
        + "\"currency\":\"GBP\",\"fulfilmentType\":\""
        + fulfilment
        + "\",\"lines\":["
        + lines
        + "]}";
  }

  private static String line(String variant, int qty) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + ",\"unitPrice\":2.50}";
  }

  private static String fulfilled(String eventId, String orderId, String items) {
    return fulfilled(eventId, orderId, items, null);
  }

  /** As above, saying the order's status after the handover (FULFILLED ends its wait for good). */
  private static String fulfilled(String eventId, String orderId, String items, String status) {
    return fulfilled(eventId, orderId, items, status, "ONLINE", "DELIVERY");
  }

  /** As above, for an order of another kind (a till sale, say). */
  private static String fulfilled(
      String eventId,
      String orderId,
      String items,
      String status,
      String channel,
      String fulfilment) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + STORE
        + "\","
        + (status == null ? "" : "\"status\":\"" + status + "\",")
        + "\"channel\":\""
        + channel
        + "\",\"fulfilmentType\":\""
        + fulfilment
        + "\",\"items\":["
        + items
        + "]}";
  }

  /** A line of an online order closed short by the store (substitutions for out-of-stock lines). */
  private static String lineClosed(
      String eventId, String tenant, String orderId, String variant, int qty) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderLineShortClosed\",\"tenantId\":\""
        + tenant
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + STORE
        + "\",\"customerId\":null,\"loginId\":null,\"currency\":\"GBP\",\"orderTotal\":5.00,"
        + "\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"variantId\":\""
        + variant
        + "\",\"variantName\":\"Apples\",\"qty\":"
        + qty
        + ",\"refundAmount\":2.50}";
  }

  /** A line of an online order replaced by a substitute the store put in the bag. */
  private static String lineSubstituted(
      String eventId, String orderId, String from, String to, int qty) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderLineSubstituted\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + STORE
        + "\",\"customerId\":null,\"loginId\":null,\"currency\":\"GBP\",\"orderTotal\":5.00,"
        + "\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"fromVariantId\":\""
        + from
        + "\",\"fromName\":\"Apples\",\"toVariantId\":\""
        + to
        + "\",\"toName\":\"Pears\",\"qty\":"
        + qty
        + ",\"chargedAmount\":5.00,\"refundAmount\":0.00}";
  }

  private static String cancelled(String orderId) {
    return "{\"eventType\":\"OrderCancelled\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + orderId
        + "\",\"reason\":\"changed mind\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\"}";
  }

  private static String item(String variant, int qty, String net) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + ",\"netAmount\":" + net + "}";
  }

  /** As above, saying what the line still has outstanding after this handover. */
  private static String item(String variant, int qty, String net, int outstanding) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"netAmount\":"
        + net
        + ",\"outstandingQty\":"
        + outstanding
        + "}";
  }

  private BigDecimal onHand(String variant) {
    JsonArray levels = Envelopes.okArray(get("/admin/inventory/levels?store=" + STORE));
    for (JsonValue v : levels) {
      if (variant.equals(v.asJsonObject().getString("variantId"))) {
        return v.asJsonObject().getJsonNumber("onHand").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  /** When order-svc confirmed order 1 and, five seconds later, order 2. */
  private static final String CONFIRMED_1 = "2026-09-20T09:00:00Z";

  private static final String CONFIRMED_2 = "2026-09-20T09:00:05Z";

  /**
   * Two orders confirmed for the store: order 1 wants 3 apples and 2 pears, order 2 wants 4 apples.
   * Order 2's confirmation arrives first — two confirmations ride different partitions and land in
   * either order — yet order 1 was confirmed first and waits first.
   */
  private String[] twoOrdersWaiting() {
    String order1 = Ids.newId().toString();
    String order2 = Ids.newId().toString();
    hold(order1, APPLES, 3);
    hold(order1, PEARS, 2);
    hold(order2, APPLES, 4);
    orders.handle(
        confirmed(
            Ids.newId().toString(), T, order2, STORE, "PICKUP", line(APPLES, 4), CONFIRMED_2));
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            order1,
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2),
            CONFIRMED_1));
    return new String[] {order1, order2};
  }

  // ── confirmed orders wait at the store ─────────────────────────────────────

  @Test
  void confirmedOrdersWaitAtTheStoreOnce() {
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    // The same confirmation again projects nothing twice; a till sale and a delivery at another
    // store are not this store's to pick.
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            ids[0],
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2)));
    String till = Ids.newId().toString();
    orders.handle(
        confirmed(Ids.newId().toString(), T, till, STORE, "INSTORE", line(APPLES, 1))
            .replace("\"channel\":\"ONLINE\"", "\"channel\":\"POS\""));
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            Ids.newId().toString(),
            OTHER_STORE,
            "DELIVERY",
            line(APPLES, 1)));

    JsonArray waiting = Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE));
    assertThat(waiting.size(), is(2));
    // Listed by when order-svc confirmed them, not by when the confirmations arrived here.
    assertThat(waiting.getJsonObject(0).getString("orderId"), is(ids[0]));
    assertThat(waiting.getJsonObject(1).getString("orderId"), is(ids[1]));
    JsonObject first = find(waiting, "orderId", ids[0]);
    assertThat(Instant.parse(first.getString("confirmedAt")), is(Instant.parse(CONFIRMED_1)));
    assertThat(first.getString("fulfilmentType"), is("DELIVERY"));
    assertThat(first.getJsonArray("lines").size(), is(2));
    assertThat(
        num(find(first.getJsonArray("lines"), "variantId", APPLES), "qtyOutstanding"),
        comparesEqualTo(new BigDecimal("3")));

    // A cancelled order leaves the list; a fulfilled one too.
    orders.handle(cancelled(ids[1]));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(1));
    // Its confirmation arriving after the cancellation (the topics land in either order) does not
    // make it wait again: done is done.
    orders.handle(
        confirmed(
            Ids.newId().toString(), T, ids[1], STORE, "PICKUP", line(APPLES, 4), CONFIRMED_2));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(1));
    orders.handle(
        fulfilled(
            Ids.newId().toString(),
            ids[0],
            item(APPLES, 3, "7.50", 0) + "," + item(PEARS, 2, "5.00", 0),
            "FULFILLED"));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
    // Fulfilled by hand, the stock left the ordinary way.
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("17")));
    // And a late confirmation of the fulfilled order changes nothing either.
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            ids[0],
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2),
            CONFIRMED_1));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
    // Two orders done, two tombstones; a till sale handed over leaves none — it never waits.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.awaiting_orders_done WHERE order_id IN ('"
                + ids[0]
                + "','"
                + ids[1]
                + "')"),
        is("2"));
    String tillSale = Ids.newId().toString();
    orders.handle(
        fulfilled(
            Ids.newId().toString(),
            tillSale,
            item(APPLES, 1, "2.50", 0),
            "FULFILLED",
            "POS",
            "INSTORE"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.awaiting_orders_done WHERE order_id = '"
                + tillSale
                + "'"),
        is("0"));
  }

  // ── a wave is one walk through the zones ───────────────────────────────────

  @Test
  void aWaveIsOneWalkThroughTheZonesDirectedByTheRule() {
    // Older apples in zone B, newer apples in zone A; pears in zone A. FEFO by default.
    JsonObject oldApples = receive(APPLES, 5, ZONE_B, "A-OLD", "2026-10-01");
    JsonObject newApples = receive(APPLES, 10, ZONE_A, "A-NEW", "2026-11-01");
    JsonObject pears = receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();

    String key = Ids.newId().toString();
    String build = "{\"storeId\":\"" + STORE + "\"}";
    JsonObject wave =
        Envelopes.created(
            call("POST", "/admin/inventory/waves", build, T, "STOREKEEPER", null, key));
    assertThat(wave.getString("status"), is("OPEN"));
    assertThat(wave.getInt("orderCount"), is(2));
    JsonArray lines = wave.getJsonArray("lines");
    assertThat(lines.size(), is(3));
    // Zone A first (new apples for order 2's remainder, the pears), zone B last (the old apples
    // that both orders draw first, FEFO).
    assertThat(lines.getJsonObject(0).getString("zoneId"), is(ZONE_A));
    assertThat(lines.getJsonObject(1).getString("zoneId"), is(ZONE_A));
    JsonObject last = lines.getJsonObject(2);
    assertThat(last.getString("zoneId"), is(ZONE_B));
    assertThat(last.getString("batchId"), is(oldApples.getString("id")));
    assertThat(last.getString("batchNo"), is("A-OLD"));
    assertThat(num(last, "directedQty"), comparesEqualTo(new BigDecimal("5")));
    JsonArray served = last.getJsonArray("orders");
    assertThat(served.size(), is(2));
    assertThat(num(find(served, "orderId", ids[0]), "qty"), comparesEqualTo(new BigDecimal("3")));
    assertThat(num(find(served, "orderId", ids[1]), "qty"), comparesEqualTo(new BigDecimal("2")));
    JsonObject newLine = find(lines, "batchId", newApples.getString("id"));
    assertThat(num(newLine, "directedQty"), comparesEqualTo(new BigDecimal("2")));
    assertThat(find(lines, "batchId", pears.getString("id")).getInt("walkOrder") <= 2, is(true));

    // Nothing else waits, so a second wave has nothing to pick; the orders are in this wave.
    assertThat(
        code(post("/admin/inventory/waves", build), 409), is("INVENTORY_WAVE_NOTHING_TO_PICK"));
    // The same key again is the same wave, even now that nothing waits: the key is looked up
    // before the waiting list is judged.
    assertThat(
        Envelopes.created(
                call("POST", "/admin/inventory/waves", build, T, "STOREKEEPER", null, key))
            .getString("id"),
        is(wave.getString("id")));
    assertThat(
        code(
            post(
                "/admin/inventory/waves",
                "{\"storeId\":\"" + STORE + "\",\"orderIds\":[\"" + ids[0] + "\"]}"),
            409),
        is("INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE"));
    // Listed for the store; read back whole; cancelled, the orders wait again and nothing moved.
    assertThat(Envelopes.okArray(get("/admin/inventory/waves?storeId=" + STORE)).size(), is(1));
    assertThat(
        Envelopes.ok(get("/admin/inventory/waves/" + wave.getString("id")))
            .getJsonArray("lines")
            .size(),
        is(3));
    assertThat(
        Envelopes.ok(post("/admin/inventory/waves/" + wave.getString("id") + "/cancel", "{}"))
            .getString("status"),
        is("CANCELLED"));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(2));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("15")));
    assertThat(
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"))
            .getJsonArray("lines")
            .size(),
        is(3));
  }

  // ── completing deducts what was picked and tells order-svc ─────────────────

  @Test
  void completingAWaveDeductsWhatWasPickedConsumesTheHoldsAndTellsOrderSvcOnce() {
    JsonObject oldApples = receive(APPLES, 5, ZONE_B, "A-OLD", "2026-10-01");
    receive(APPLES, 10, ZONE_A, "A-NEW", "2026-11-01");
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    JsonArray lines = wave.getJsonArray("lines");
    JsonObject oldLine = find(lines, "batchId", oldApples.getString("id"));

    // The old apples are picked short: 4 of the 5 directed. Everything else in full.
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : lines) {
      JsonObject l = v.asJsonObject();
      boolean shortLine = l.getString("id").equals(oldLine.getString("id"));
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(shortLine ? "4" : l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    assertThat(
        code(
            post(
                "/admin/inventory/waves/" + waveId + "/picks",
                "{\"lines\":[{\"lineId\":\"" + oldLine.getString("id") + "\",\"pickedQty\":9}]}"),
            400),
        is("INVENTORY_WAVE_PICK_EXCEEDS_LINE"));
    assertThat(
        code(post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[null]}"), 400),
        is("VALIDATION_FAILED"));
    JsonObject picked =
        Envelopes.ok(
            post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    assertThat(
        num(find(picked.getJsonArray("lines"), "batchId", oldApples.getString("id")), "pickedQty"),
        comparesEqualTo(new BigDecimal("4")));

    JsonObject done = Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    assertThat(done.getString("status"), is("COMPLETED"));
    // Order 1 (the earlier) got its 3 old apples; order 2 got 1 of its 2 from the old batch plus
    // 2 from the new: 15 apples became 15 - 6 = 9; 8 pears became 6.
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("9")));
    assertThat(onHand(PEARS), comparesEqualTo(new BigDecimal("6")));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '"
                + oldApples.getString("id")
                + "'"),
        is("1.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE type = 'SALE' AND ref_type ="
                + " 'ORDER' AND ref_id = '"
                + ids[1]
                + "'"),
        is("2"));
    // Order 1's holds are consumed; order 2's apple hold keeps the 1 still to come.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.reservations WHERE order_id = '"
                + ids[0]
                + "' AND status = 'HELD'"),
        is("0"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT qty FROM inventory.reservations WHERE order_id = '"
                + ids[1]
                + "' AND status = 'HELD'"),
        is("1.000"));
    // Order 2 still waits for one apple; order 1 waits for nothing.
    JsonArray waiting = Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE));
    assertThat(waiting.size(), is(1));
    assertThat(
        num(
            find(find(waiting, "orderId", ids[1]).getJsonArray("lines"), "variantId", APPLES),
            "qtyOutstanding"),
        comparesEqualTo(new BigDecimal("1")));
    // order-svc is told what to fulfil, with the picked quantities.
    String announced =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'WavePicked'");
    assertThat(announced, containsString("\"waveId\":\"" + waveId + "\""));
    assertThat(announced, containsString("\"orderId\":\"" + ids[0] + "\""));
    assertThat(announced, containsString("\"variantId\":\"" + PEARS + "\",\"qty\":2"));
    assertThat(announced, containsString("\"variantId\":\"" + APPLES + "\",\"qty\":3"));

    // order-svc fulfils and announces; the fulfilment of what the wave picked deducts nothing
    // more but keeps the revenue; a fulfilment the wave did not pick (the last apple, by hand)
    // deducts as always.
    orders.handle(
        fulfilled(
            Ids.newId().toString(),
            ids[0],
            item(APPLES, 3, "7.50") + "," + item(PEARS, 2, "5.00")));
    // The wave's own fulfilment of order 2, in the older shape that states no outstanding figure:
    // the waiting line is reduced only by what this fulfilment deducted — nothing — and a
    // redelivery reduces it no further.
    String wavesOwn = Ids.newId().toString();
    orders.handle(fulfilled(wavesOwn, ids[1], item(APPLES, 3, "7.50")));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("9")));
    assertThat(onHand(PEARS), comparesEqualTo(new BigDecimal("6")));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.sale_revenue WHERE order_id = '" + ids[0] + "'"),
        is("2"));
    // The hold on the apple still to come is not a leftover of that fulfilment: it stays HELD.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT qty FROM inventory.reservations WHERE order_id = '"
                + ids[1]
                + "' AND status = 'HELD'"),
        is("1.000"));
    // The same fulfilment again changes nothing: not the shelf, not the waiting list.
    orders.handle(fulfilled(wavesOwn, ids[1], item(APPLES, 3, "7.50")));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("9")));
    assertThat(
        num(
            find(
                find(
                        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)),
                        "orderId",
                        ids[1])
                    .getJsonArray("lines"),
                "variantId",
                APPLES),
            "qtyOutstanding"),
        comparesEqualTo(new BigDecimal("1")));
    orders.handle(
        fulfilled(Ids.newId().toString(), ids[1], item(APPLES, 1, "2.50", 0), "FULFILLED"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("8")));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.reservations WHERE order_id = '"
                + ids[1]
                + "' AND status = 'HELD'"),
        is("0"));
    // Completed once.
    assertThat(
        code(post("/admin/inventory/waves/" + waveId + "/complete", "{}"), 409),
        is("INVENTORY_WAVE_NOT_OPEN"));
  }

  // ── the store's own ────────────────────────────────────────────────────────

  @Test
  void wavesAreTheStoresOwn() {
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    twoOrdersWaiting();
    String body = "{\"storeId\":\"" + STORE + "\"}";
    assertThat(call("POST", "/admin/inventory/waves", body, T, "CASHIER").getStatus(), is(403));
    assertThat(
        call("POST", "/admin/inventory/waves", body, T, "STOREKEEPER", OTHER_STORE).getStatus(),
        is(403));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/waves/awaiting?storeId=" + STORE, null, T2, "OWNER"))
            .size(),
        is(0));
    // Naming no store, a keeper of another store reads that store — not every store's list; a
    // keeper of several must say which.
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/waves/awaiting", null, T, "STOREKEEPER", OTHER_STORE))
            .size(),
        is(0));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/waves/awaiting", null, T, "STOREKEEPER", STORE))
            .size(),
        is(2));
    assertThat(
        code(
            call(
                "GET",
                "/admin/inventory/waves/awaiting",
                null,
                T,
                "STOREKEEPER",
                STORE + "," + OTHER_STORE),
            400),
        is("STORE_REQUIRED"));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/waves", null, T, "STOREKEEPER", OTHER_STORE))
            .size(),
        is(0));
    assertThat(
        code(call("POST", "/admin/inventory/waves", body, T2, "OWNER"), 409),
        is("INVENTORY_WAVE_NOTHING_TO_PICK"));
    JsonObject wave =
        Envelopes.created(call("POST", "/admin/inventory/waves", body, T, "STOREKEEPER", STORE));
    assertThat(
        call("GET", "/admin/inventory/waves/" + wave.getString("id"), null, T2, "OWNER")
            .getStatus(),
        is(404));
    assertThat(
        call(
                "POST",
                "/admin/inventory/waves/" + wave.getString("id") + "/cancel",
                "{}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
  }

  // ── a handover larger than the wave picked ─────────────────────────────────

  @Test
  void aHandoverLargerThanTheWavePickedDeductsOnlyTheRest() {
    // Order 2 wants 4 apples; the wave picks it 3 (the old batch short by one). Then a person hands
    // all 4 over at once, before order-svc has applied the wave — the fulfilment names 4, the wave
    // drew 3: only the last apple leaves now, the revenue is recorded once for the line, and the
    // hold on that apple is consumed, not released.
    JsonObject oldApples = receive(APPLES, 5, ZONE_B, "A-OLD", "2026-10-01");
    receive(APPLES, 10, ZONE_A, "A-NEW", "2026-11-01");
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : wave.getJsonArray("lines")) {
      JsonObject l = v.asJsonObject();
      boolean shortLine = l.getString("batchId").equals(oldApples.getString("id"));
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(shortLine ? "4" : l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    Envelopes.ok(
        post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("9")));

    String handover = Ids.newId().toString();
    orders.handle(fulfilled(handover, ids[1], item(APPLES, 4, "10.00", 0), "FULFILLED"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("8")));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.sale_revenue WHERE order_id = '" + ids[1] + "'"),
        is("1"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.reservations WHERE order_id = '"
                + ids[1]
                + "' AND status <> 'CONSUMED'"),
        is("0"));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
    // Redelivered, the same handover deducts nothing more.
    orders.handle(fulfilled(handover, ids[1], item(APPLES, 4, "10.00", 0), "FULFILLED"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("8")));
  }

  // ── an order that left while the wave was open ─────────────────────────────

  @Test
  void anOrderThatLeftWhileTheWaveWasOpenIsNotDrawn() {
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    // Order 2 is cancelled while the picker is walking.
    orders.handle(cancelled(ids[1]));
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : wave.getJsonArray("lines")) {
      JsonObject l = v.asJsonObject();
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    Envelopes.ok(
        post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    JsonObject done = Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    assertThat(done.getString("status"), is("COMPLETED"));
    // Order 1's 3 apples and 2 pears left; order 2's 4 apples did not, though they were picked.
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("17")));
    assertThat(onHand(PEARS), comparesEqualTo(new BigDecimal("6")));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE ref_type = 'ORDER' AND ref_id = '"
                + ids[1]
                + "'"),
        is("0"));
    // The line says so: 7 picked, 3 of them for the order that is still here.
    JsonObject appleLine = find(done.getJsonArray("lines"), "variantId", APPLES);
    assertThat(num(appleLine, "pickedQty"), comparesEqualTo(new BigDecimal("7")));
    assertThat(
        num(find(appleLine.getJsonArray("orders"), "orderId", ids[1]), "pickedQty"),
        comparesEqualTo(BigDecimal.ZERO));
    String announced =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'WavePicked'");
    assertThat(announced, containsString("\"orderId\":\"" + ids[0] + "\""));
    assertThat(announced, not(containsString("\"orderId\":\"" + ids[1] + "\"")));
  }

  // ── consignment stock sold by a wave ───────────────────────────────────────

  @Test
  void aWaveSellingConsignmentStockTellsTheSupplierSide() {
    String supplier = Ids.newId().toString();
    receive(
        APPLES,
        5,
        ZONE_A,
        "C-1",
        null,
        ",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\"" + supplier + "\"");
    String order = Ids.newId().toString();
    hold(order, APPLES, 4);
    orders.handle(
        confirmed(Ids.newId().toString(), T, order, STORE, "PICKUP", line(APPLES, 4), CONFIRMED_1));
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    JsonObject l = wave.getJsonArray("lines").getJsonObject(0);
    Envelopes.ok(
        post(
            "/admin/inventory/waves/" + waveId + "/picks",
            "{\"lines\":[{\"lineId\":\"" + l.getString("id") + "\",\"pickedQty\":4}]}"));
    Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("1")));
    // The supplier is owed the moment the stock leaves, exactly as on an ordinary sale.
    String sold =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type ="
                + " 'ConsignmentStockSold'");
    assertThat(sold, containsString("\"supplierId\":\"" + supplier + "\""));
    assertThat(sold, containsString("\"orderId\":\"" + order + "\""));
    assertThat(sold, containsString("\"qty\":4"));
  }

  // ── an order handed over by hand while the wave was open ───────────────────

  @Test
  void anOrderHandedOverByHandMidWaveIsDrawnOnlyForWhatItStillWaits() {
    // Order 2's four apples are split across two of the wave's lines (two from the old batch, two
    // from the new). While the picker walks, three apples arrive with an even earlier date and a
    // person hands three of order 2's apples over by hand — FEFO draws them from that batch, so
    // the wave's own batches are untouched. Across both of its lines the wave then draws only the
    // one apple order 2 still waits for: the clamp is per order and product, not per line.
    receive(APPLES, 5, ZONE_B, "A-OLD", "2026-10-01");
    receive(APPLES, 10, ZONE_A, "A-NEW", "2026-11-01");
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    receive(APPLES, 3, ZONE_A, "A-EARLY", "2026-09-30");
    orders.handle(
        fulfilled(
            Ids.newId().toString(), ids[1], item(APPLES, 3, "7.50", 1), "PARTIALLY_FULFILLED"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("15")));
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : wave.getJsonArray("lines")) {
      JsonObject l = v.asJsonObject();
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    Envelopes.ok(
        post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    JsonObject done = Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    // Order 1: 3 apples and 2 pears; order 2: one apple only, across both apple lines: 15 - 4.
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("11")));
    BigDecimal drawnForOrder2 = BigDecimal.ZERO;
    for (JsonValue v : done.getJsonArray("lines")) {
      for (JsonValue o : v.asJsonObject().getJsonArray("orders")) {
        if (ids[1].equals(o.asJsonObject().getString("orderId"))) {
          drawnForOrder2 = drawnForOrder2.add(num(o.asJsonObject(), "pickedQty"));
        }
      }
    }
    assertThat(drawnForOrder2, comparesEqualTo(BigDecimal.ONE));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
  }

  // ── a handover smaller than the pick keeps the rest of the credit ──────────

  @Test
  void aHandoverSmallerThanThePickKeepsTheRestOfTheCredit() {
    // The wave picked order 2's apples in full (4); a person hands 3 over before order-svc applies
    // the wave. That fulfilment is covered by the pick and deducts nothing; the fourth apple's
    // credit is kept, so when the last one is handed over it deducts nothing either.
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : wave.getJsonArray("lines")) {
      JsonObject l = v.asJsonObject();
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    Envelopes.ok(
        post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("13")));
    orders.handle(
        fulfilled(
            Ids.newId().toString(), ids[1], item(APPLES, 3, "7.50", 1), "PARTIALLY_FULFILLED"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("13")));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT sum(qty) FROM inventory.wave_picked_lines WHERE order_id = '"
                + ids[1]
                + "' AND acknowledged_by IS NULL"),
        is("1.000"));
    orders.handle(
        fulfilled(Ids.newId().toString(), ids[1], item(APPLES, 1, "2.50", 0), "FULFILLED"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("13")));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.sale_revenue WHERE order_id = '" + ids[1] + "'"),
        is("2"));
  }

  // ── a line closed short or substituted gives its hold back and waits for less ──

  /**
   * Substitutions for out-of-stock online lines: the store closes one of three apples short — the
   * hold shrinks to two, a RELEASE movement says so, and the waiting line needs two; a substitute
   * for the other two releases the rest and the apples wait no more, the pears still do; a
   * redelivered event changes nothing; and another business's event for the same order id touches
   * nothing of ours.
   */
  @Test
  void aLineClosedShortOrSubstitutedGivesBackItsHoldAndWaitsForLess() {
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String order = Ids.newId().toString();
    hold(order, APPLES, 3);
    hold(order, PEARS, 2);
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            order,
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2)));
    String applesHold =
        "FROM inventory.reservations WHERE order_id = '"
            + order
            + "' AND variant_id = '"
            + APPLES
            + "'";
    String applesWait =
        "FROM inventory.awaiting_order_lines WHERE order_id = '"
            + order
            + "' AND variant_id = '"
            + APPLES
            + "'";

    String closed = lineClosed(Ids.newId().toString(), T, order, APPLES, 1);
    orders.handle(closed);
    orders.handle(closed); // redelivered
    assertThat(Envelopes.scalar(PG, "SELECT qty || ' ' || status " + applesHold), is("2.000 HELD"));
    assertThat(Envelopes.scalar(PG, "SELECT qty_outstanding " + applesWait), is("2.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE type = 'RELEASE' AND ref_type ="
                + " 'RESERVATION' AND ref_id = (SELECT id "
                + applesHold
                + ")"),
        is("1"));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("20")));

    // Another business's event naming our order id: nothing of ours moves.
    orders.handle(lineClosed(Ids.newId().toString(), T2, order, APPLES, 2));
    assertThat(Envelopes.scalar(PG, "SELECT qty || ' ' || status " + applesHold), is("2.000 HELD"));
    assertThat(Envelopes.scalar(PG, "SELECT qty_outstanding " + applesWait), is("2.000"));

    // The other two apples are replaced by pears: the apples' hold is released in full and told,
    // the apples wait no more, the pears still do.
    orders.handle(lineSubstituted(Ids.newId().toString(), order, APPLES, PEARS, 2));
    assertThat(
        Envelopes.scalar(PG, "SELECT qty || ' ' || status " + applesHold), is("2.000 RELEASED"));
    assertThat(Envelopes.scalar(PG, "SELECT qty_outstanding " + applesWait), is("0.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockReleased' AND"
                + " aggregate_id = (SELECT id "
                + applesHold
                + ")"),
        is("1"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT qty_outstanding FROM inventory.awaiting_order_lines WHERE order_id = '"
                + order
                + "' AND variant_id = '"
                + PEARS
                + "'"),
        is("2.000"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.awaiting_orders WHERE order_id = '" + order + "'"),
        is("1"));
    JsonArray awaiting = Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE));
    assertThat(awaiting.toString(), containsString(order));
  }

  // ── a part handover keeps the holds of the lines still waiting ─────────────

  @Test
  void aPartHandoverKeepsTheHoldsOfLinesStillWaiting() {
    // The picker finds the apples and none of the pears: the pears line is picked at nought.
    // order-svc hands the apples over (PARTIALLY_FULFILLED); the pears' hold is not a leftover to
    // release — the pears still wait, held, for the next wave.
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String order1 = Ids.newId().toString();
    hold(order1, APPLES, 3);
    hold(order1, PEARS, 2);
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            order1,
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2),
            CONFIRMED_1));
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    JsonObject appleLine = find(wave.getJsonArray("lines"), "variantId", APPLES);
    JsonObject pearLine = find(wave.getJsonArray("lines"), "variantId", PEARS);
    Envelopes.ok(
        post(
            "/admin/inventory/waves/" + waveId + "/picks",
            "{\"lines\":[{\"lineId\":\""
                + appleLine.getString("id")
                + "\",\"pickedQty\":3},{\"lineId\":\""
                + pearLine.getString("id")
                + "\",\"pickedQty\":0}]}"));
    Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    orders.handle(
        fulfilled(
            Ids.newId().toString(), order1, item(APPLES, 3, "7.50", 0), "PARTIALLY_FULFILLED"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT qty FROM inventory.reservations WHERE order_id = '"
                + order1
                + "' AND variant_id = '"
                + PEARS
                + "' AND status = 'HELD'"),
        is("2.000"));
    JsonArray waiting = Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE));
    assertThat(waiting.size(), is(1));
    assertThat(
        num(
            find(waiting.getJsonObject(0).getJsonArray("lines"), "variantId", PEARS),
            "qtyOutstanding"),
        comparesEqualTo(new BigDecimal("2")));
  }

  // ── a batch no longer sellable stops the completion ────────────────────────

  @Test
  void aBatchNoLongerSellableStopsTheCompletion() {
    JsonObject apples = receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : wave.getJsonArray("lines")) {
      JsonObject l = v.asJsonObject();
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    Envelopes.ok(
        post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    // The apples are quarantined between the walk and the completion.
    Envelopes.scalar(
        PG,
        "UPDATE inventory.inventory_batches SET material_status = 'QUARANTINE' WHERE id = '"
            + apples.getString("id")
            + "' RETURNING id::text");
    assertThat(
        code(post("/admin/inventory/waves/" + waveId + "/complete", "{}"), 422),
        is("INVENTORY_WAVE_STOCK_GONE"));
    // Nothing moved: the wave is still open, the batch untouched (quarantined stock is not on
    // hand, so the batch itself is read) and no sale was written.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '"
                + apples.getString("id")
                + "'"),
        is("20.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE type = 'SALE' AND batch_id = '"
                + apples.getString("id")
                + "'"),
        is("0"));
    assertThat(
        Envelopes.ok(get("/admin/inventory/waves/" + waveId)).getString("status"), is("OPEN"));
  }
}
