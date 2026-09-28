package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for order-svc against real Postgres (Testcontainers): place order, confirm, void
 * (POS), return, layaway, gift card issue/reload/redeem. Kafka/Consul disabled.
 */
@HelidonTest
class OrderIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TENANTS =
        TenantSvcStub.start()
            .with(OrderIT.T, "USD", "US")
            .with("01a090ae-611e-7014-8cd5-baf0862fa319", "USD", "US")
            .with("01a090ae-611e-7015-8c11-fd62230bf57a", "USD", "US")
            .with("01a090ae-611e-7016-a809-076a3374b722", "USD", "US")
            .with("01a090ae-611e-7017-bdd9-d7612c647032", "USD", "US")
            .with("01a090ae-611e-7019-ba7e-5901486ca70a", "USD", "US")
            .with("01a090ae-611e-701b-8b9c-fe24949dad64", "USD", "US");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // Disable server-side pricing enforcement so tests don't need a live pricing-svc.
    System.setProperty("storeql.order.pricing.enforce", "false");
    // Disable checkout stock holds so ONLINE orders don't need a live inventory-svc.
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  private static final String T = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String S = "01a090ae-611e-700f-b645-a14095230b77";
  private static final String USER = "01a090ae-611e-7099-8000-000000000001";
  private static final String V = "01a090ae-611e-7011-ae7d-1bd68c966ff6";

  @Inject WebTarget target;
  @Inject OrderService orderService;
  @Inject com.storeql.service.TenantStatusRepository tenantStatus;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /**
   * Like {@link #post(String, String, String)} but with an Idempotency-Key header — required by
   * {@code POST /orders} now that the server rejects order placement without one.
   */
  private Response post(String path, String json, String tenant, String idempotencyKey) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", idempotencyKey)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  private Response listOrders(String tenant, int limit, String after) {
    WebTarget t = target.path("/orders").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private Response listSpecialOrders(String tenant, int limit, String after) {
    WebTarget t = target.path("/admin/special-orders").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private Response listPosLog(String tenant, int limit, String after) {
    WebTarget t = target.path("/admin/pos-log").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  @Test
  void placeOrderConfirmAndReturn() {
    // place POS order
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(r1.getStatus(), is(201));
    String body1 = r1.readEntity(String.class);
    assertThat(body1, containsString("PENDING"));
    String orderId = extractId(body1);

    // confirm — this is a POS in-store sale, so confirming it also hands it over (SJ-D40). This
    // assertion said CONFIRMED, the state every till sale used to be left in: paid for and never
    // deducted from stock.
    Response r2 = post("/orders/" + orderId + "/confirm", "{}", T);
    assertThat(r2.getStatus(), is(200));
    assertThat(r2.readEntity(String.class), containsString("FULFILLED"));

    // return one unit
    Response r3 =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"customer changed mind\","
                + "\"refundMethod\":\"ORIGINAL\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1}]}",
            T);
    assertThat(r3.getStatus(), is(201));
    assertThat(r3.readEntity(String.class), containsString("COMPLETED"));
  }

  @Test
  void paymentRefundedFlipsOrderToPartiallyThenFullyRefunded() {
    // place + confirm a POS order (total = 2 × 10.00 = 20.00)
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}]}",
            T,
            Ids.newId().toString());
    assertThat(placed.getStatus(), is(201));
    String orderId = extractId(placed.readEntity(String.class));
    assertThat(post("/orders/" + orderId + "/confirm", "{}", T).getStatus(), is(200));

    UUID tenant = Ids.parse(T);
    UUID order = Ids.parse(orderId);

    // A 12.00 refund on a 20.00 order → PARTIALLY_REFUNDED (as PaymentEventHandler would call it).
    UUID e1 = Ids.newId();
    orderService.applyRefund(e1, tenant, order, new java.math.BigDecimal("12.00"));
    assertThat(
        get("/orders/" + orderId, T).readEntity(String.class),
        containsString("PARTIALLY_REFUNDED"));

    // Redelivery of the SAME refund event must not add again — a broken dedupe would push
    // cumulative to 24 ≥ 20 and prematurely show REFUNDED.
    orderService.applyRefund(e1, tenant, order, new java.math.BigDecimal("12.00"));
    assertThat(
        get("/orders/" + orderId, T).readEntity(String.class),
        containsString("PARTIALLY_REFUNDED"));

    // The remaining 8.00 (distinct event) → cumulative 20.00 = total → REFUNDED.
    orderService.applyRefund(Ids.newId(), tenant, order, new java.math.BigDecimal("8.00"));
    String finalBody = get("/orders/" + orderId, T).readEntity(String.class);
    // REFUNDED present and PARTIALLY_REFUNDED absent together prove the status is exactly REFUNDED.
    assertThat(finalBody, containsString("REFUNDED"));
    assertThat(finalBody, not(containsString("PARTIALLY_REFUNDED")));
  }

  @Test
  void sweeperCancelsExpiredPendingOrdersButNotConfirmedOnes() {
    String orderJson =
        "{\"storeId\":\""
            + S
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
            + "\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":1,\"unitPrice\":10.00}]}";

    // A: placed, left PENDING (client never paid).
    String aId =
        extractId(post("/orders", orderJson, T, Ids.newId().toString()).readEntity(String.class));
    // B: an online click-and-collect order, placed then confirmed — paid for and waiting to be
    // collected. It used to be a POS in-store order, but a confirmed till sale is now handed over
    // at once (SJ-D40), and this test exists to prove the sweeper spares a CONFIRMED order.
    String collectJson =
        orderJson.replace(
            "\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\"",
            "\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\"");
    String bId =
        extractId(post("/orders", collectJson, T, Ids.newId().toString()).readEntity(String.class));
    assertThat(post("/orders/" + bId + "/confirm", "{}", T).getStatus(), is(200));

    // TTL of 0h → every still-PENDING order is expired. B is CONFIRMED so the status guard skips
    // it.
    orderService.sweepExpiredPendingOrders(0, 200);

    assertThat(get("/orders/" + aId, T).readEntity(String.class), containsString("CANCELLED"));
    String bBody = get("/orders/" + bId, T).readEntity(String.class);
    assertThat(bBody, containsString("CONFIRMED"));
    assertThat(bBody, not(containsString("CANCELLED")));
  }

  /**
   * Simulates what {@code PaymentEventHandler} does on each PaymentCaptured event — Kafka is
   * disabled in this IT, so the events are driven directly through {@link OrderService} instead of
   * a real consumer loop.
   */
  @Test
  void splitTendersAccumulateAndConfirmOnlyOnceTotalIsCovered() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(placed.getStatus(), is(201));
    UUID orderId = Ids.parse(extractId(placed.readEntity(String.class)));
    UUID tenantId = Ids.parse(T);

    // First tender (cash, $4) — covers less than the $10 total: still PENDING.
    orderService.handlePaymentCaptured(tenantId, orderId, Ids.newId(), new BigDecimal("4.00"));
    Response afterFirst = get("/orders/" + orderId, T);
    assertThat(afterFirst.readEntity(String.class), containsString("PENDING"));
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(0L));

    // Second tender (card, $6) — the two together cover the total, and this is a till sale, so it
    // is handed over: FULFILLED, not CONFIRMED. This assertion used to say CONFIRMED, which is the
    // state SJ-D40 left every till sale in — paid for, and never deducted from stock.
    orderService.handlePaymentCaptured(tenantId, orderId, Ids.newId(), new BigDecimal("6.00"));
    assertThat(statusOf(orderId), is("FULFILLED"));
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(1L));
  }

  /**
   * Redelivery of the same Kafka event (same paymentId) must not double-count toward paid_amount —
   * verified on a second order where double-counting a single $6 tender (redelivered once) would
   * incorrectly push paid_amount past the $10 total and confirm prematurely.
   */
  @Test
  void redeliveredPaymentEventIsNotDoubleCounted() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(placed.getStatus(), is(201));
    UUID orderId = Ids.parse(extractId(placed.readEntity(String.class)));
    UUID tenantId = Ids.parse(T);

    UUID paymentId = Ids.newId();
    orderService.handlePaymentCaptured(tenantId, orderId, paymentId, new BigDecimal("6.00"));
    // Same paymentId redelivered: if paid_amount were double-counted (6+6=12 >= 10) the order
    // would wrongly confirm. The unique key on order_payment_events must make this a no-op.
    orderService.handlePaymentCaptured(tenantId, orderId, paymentId, new BigDecimal("6.00"));

    Response after = get("/orders/" + orderId, T);
    assertThat(after.readEntity(String.class), containsString("PENDING"));
  }

  // ── SJ-D40: a till sale is handed over the moment it is paid for ──────────

  private static long outboxCount(UUID orderId, String eventType) {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM \"order\".outbox WHERE aggregate_id = ? AND event_type = ?")) {
      ps.setObject(1, orderId);
      ps.setString(2, eventType);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String outboxPayload(UUID orderId, String eventType) {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM \"order\".outbox WHERE aggregate_id = ? AND event_type = ?"
                    + " LIMIT 1")) {
      ps.setObject(1, orderId);
      ps.setString(2, eventType);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Places a two-unit order at $10.00 each ($20.00 total) and returns its id. */
  private UUID placeAt(String channel, String fulfilment) {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\""
                + channel
                + "\",\"fulfilmentType\":\""
                + fulfilment
                + "\",\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    String body = placed.readEntity(String.class);
    assertThat(body, placed.getStatus(), is(201));
    return Ids.parse(extractId(body));
  }

  private String statusOf(UUID orderId) {
    String body = get("/orders/" + orderId, T).readEntity(String.class);
    var m = java.util.regex.Pattern.compile("\"status\":\"([A-Z_]+)\"").matcher(body);
    return m.find() ? m.group(1) : body;
  }

  /**
   * The defect. Proved on the running stack before the fix: a till sale paid in full sat at
   * CONFIRMED with no SALE movement, and the only SALE movements in the system belonged to one
   * order a manager had fulfilled by hand.
   */
  @Test
  void aTillSaleIsHandedOverTheMomentItIsPaidFor() {
    UUID orderId = placeAt("POS", "INSTORE");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));

    assertThat(statusOf(orderId), is("FULFILLED"));
    // The event inventory-svc deducts stock on. Before this fix a till sale never produced one.
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(1L));
    String payload = outboxPayload(orderId, "OrderFulfilled");
    assertThat(payload, containsString(V));
    assertThat(payload, containsString("\"qty\":2"));
  }

  @Test
  void aRedeliveredCaptureDoesNotSellTheStockTwice() {
    UUID orderId = placeAt("POS", "INSTORE");
    UUID paymentId = Ids.newId();
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, paymentId, new BigDecimal("20.00"));
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, paymentId, new BigDecimal("20.00"));

    // Two OrderFulfilled events would deduct the stock twice — inventory-svc dedupes per event,
    // and each OrderFulfilled carries a fresh event id, so this has to be stopped here.
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(1L));
    assertThat(statusOf(orderId), is("FULFILLED"));
  }

  @Test
  void aTillSaleQueuedOfflineAsPickupIsStillHandedOver() {
    // The till sent PICKUP for every tendered sale until this fix, including the ones sitting in
    // offline queues on devices now, which replay with the request they were queued with.
    UUID orderId = placeAt("POS", "PICKUP");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));

    assertThat(statusOf(orderId), is("FULFILLED"));
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(1L));
  }

  @Test
  void anOnlineCollectOrderIsConfirmedButNotHandedOver() {
    // The other direction of the same rule: an online click-and-collect order is paid for now and
    // collected later. Fulfilling it at payment would deduct stock that is still on the shelf.
    UUID orderId = placeAt("ONLINE", "PICKUP");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));

    assertThat(statusOf(orderId), is("CONFIRMED"));
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(0L));
  }

  @Test
  void confirmingATillSaleByHandAlsoHandsItOver() {
    UUID orderId = placeAt("POS", "INSTORE");
    assertThat(post("/orders/" + orderId + "/confirm", "{}", T).getStatus(), is(200));

    assertThat(statusOf(orderId), is("FULFILLED"));
    assertThat(outboxCount(orderId, "OrderFulfilled"), is(1L));
  }

  // ── ...and a voided till sale puts that stock back ────────────────────────

  @Test
  void voidingATillSaleThatWasHandedOverPutsItsStockBack() {
    UUID orderId = placeAt("POS", "INSTORE");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));
    assertThat(
        post("/orders/" + orderId + "/void", "{\"reason\":\"wrong item scanned\"}", T).getStatus(),
        is(200));

    // Before SJ-D40 a till sale never deducted stock, so a void had nothing to put back and
    // OrderVoided carried no lines. Now it deducts at payment, and a void that did not restock
    // would lose the stock permanently.
    String payload = outboxPayload(orderId, "OrderVoided");
    assertThat(payload, containsString(V));
    assertThat(payload, containsString("\"qty\":2"));
    assertThat(payload, containsString("\"storeId\":\"" + S + "\""));
    assertThat(payload, containsString("\"eventId\":\""));
  }

  /**
   * A return used to be refused only for cancelled and voided orders, so an order nobody had paid
   * for or collected could be "returned" and a refund recorded against it.
   */
  @Test
  void onlyGoodsThatWereHandedOverCanBeReturned() {
    String oneBack =
        "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}";

    UUID unpaid = placeAt("POS", "INSTORE");
    Response pending = post("/orders/" + unpaid + "/returns", oneBack, T);
    assertThat(pending.getStatus(), is(409));
    assertThat(pending.readEntity(String.class), containsString("ORDER_CANNOT_RETURN"));

    // Paid online but not yet collected: CONFIRMED, still on the shelf.
    UUID awaitingPickup = placeAt("ONLINE", "PICKUP");
    orderService.handlePaymentCaptured(
        Ids.parse(T), awaitingPickup, Ids.newId(), new BigDecimal("20.00"));
    assertThat(statusOf(awaitingPickup), is("CONFIRMED"));
    assertThat(post("/orders/" + awaitingPickup + "/returns", oneBack, T).getStatus(), is(409));
    assertThat(outboxCount(awaitingPickup, "OrderReturned"), is(0L));

    // Handed over at the till the moment it was paid for: now it can come back.
    UUID sold = placeAt("POS", "INSTORE");
    orderService.handlePaymentCaptured(Ids.parse(T), sold, Ids.newId(), new BigDecimal("20.00"));
    assertThat(post("/orders/" + sold + "/returns", oneBack, T).getStatus(), is(201));
  }

  @Test
  void voidingASaleWithAReturnAgainstItPutsBackOnlyWhatIsLeft() {
    UUID orderId = placeAt("POS", "INSTORE");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));
    Response ret =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"one was bruised\",\"refundMethod\":\"ORIGINAL\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1}]}",
            T);
    assertThat(ret.getStatus(), is(201));
    assertThat(
        post("/orders/" + orderId + "/void", "{\"reason\":\"rang the rest up wrong\"}", T)
            .getStatus(),
        is(200));

    // Two were sold and one came back through the return, which restocked it the moment the
    // return was created. Restocking both on the void would count that one twice.
    String payload = outboxPayload(orderId, "OrderVoided");
    assertThat(payload, containsString(V));
    assertThat(payload, containsString("\"qty\":1"));
    assertThat(payload, not(containsString("\"qty\":2")));
  }

  @Test
  void voidingASaleBeforeItIsPaidForPutsNothingBack() {
    UUID orderId = placeAt("POS", "INSTORE");
    assertThat(
        post("/orders/" + orderId + "/void", "{\"reason\":\"customer walked away\"}", T)
            .getStatus(),
        is(200));

    // Nothing was handed over, so nothing was deducted — restocking here would invent stock.
    assertThat(outboxPayload(orderId, "OrderVoided"), containsString("\"items\":[]"));
  }

  @Test
  void retriedCheckoutWithSameIdempotencyKeyReplaysOriginalOrder() {
    String orderJson =
        "{\"storeId\":\""
            + S
            + "\","
            + "\"channel\":\"POS\","
            + "\"fulfilmentType\":\"INSTORE\","
            + "\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":1,\"unitPrice\":5.00}],"
            + "\"currency\":\"USD\","
            + "\"idempotencyKey\":\""
            + Ids.newId()
            + "\"}";

    Response first = post("/orders", orderJson, T);
    assertThat(first.getStatus(), is(201));
    String firstId = extractId(first.readEntity(String.class));

    // retry (e.g. client timeout + resubmit) must return the SAME order, not an error
    Response retry = post("/orders", orderJson, T);
    assertThat(retry.getStatus(), is(201));
    assertThat(extractId(retry.readEntity(String.class)), is(firstId));
  }

  @Test
  void negativeTaxAndOversizedDiscountAreRejected() {
    // negative taxAmount must fail bean validation (gap #63)
    Response negTax =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"taxAmount\":-5.00,\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(negTax.getStatus(), is(400));

    // discount larger than the subtotal must not drive the total negative
    Response bigDisc =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"discountAmount\":50.00,\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(bigDisc.getStatus(), is(400));
    assertThat(bigDisc.readEntity(String.class), containsString("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL"));
  }

  @Test
  void listOrdersPaginatesWithCursor() {
    // Dedicated tenant so orders created by other tests never leak into these pages.
    String tenant = "01a090ae-611e-7014-8cd5-baf0862fa319";
    var allIds = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      Response r =
          post(
              "/orders",
              "{\"storeId\":\""
                  + S
                  + "\","
                  + "\"channel\":\"POS\","
                  + "\"fulfilmentType\":\"INSTORE\","
                  + "\"items\":[{\"variantId\":\""
                  + V
                  + "\",\"qty\":1,\"unitPrice\":1.00}],"
                  + "\"currency\":\"USD\"}",
              tenant,
              Ids.newId().toString());
      assertThat(r.getStatus(), is(201));
      allIds.add(extractId(r.readEntity(String.class)));
    }

    // page 1: two orders + a nextCursor
    Response p1 = listOrders(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllIds(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    // page 2: the remaining order, no further cursor
    Response p2 = listOrders(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllIds(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    // the two pages cover all three orders with no overlap
    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen.size(), is(3));
    assertThat(seen, is(allIds));

    // a garbage cursor is a clean 400, not a 500
    Response bad = listOrders(tenant, 2, "!!not-base64!!");
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("INVALID_CURSOR"));
  }

  @Test
  void listSpecialOrdersPaginatesWithCursor() {
    // Dedicated tenant so special orders created by other tests never leak into these pages.
    String tenant = "01a090ae-611e-7019-ba7e-5901486ca70a";
    var allNames = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      String name = "Cust" + i;
      Response r =
          post(
              "/admin/special-orders",
              "{\"storeId\":\""
                  + S
                  + "\",\"customerName\":\""
                  + name
                  + "\",\"items\":[{\"variantId\":\""
                  + V
                  + "\",\"qty\":1,\"unitPrice\":1.00}]}",
              tenant);
      assertThat(r.getStatus(), is(201));
      allNames.add(name);
    }

    // Each special order's own "id" plus its single item's "id" both match a naive "id":"..."
    // scan, so page membership is checked via the per-order customerName instead (unique, and
    // absent from the nested item objects).
    Response p1 = listSpecialOrders(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllCustomerNames(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    Response p2 = listSpecialOrders(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllCustomerNames(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen, is(allNames));
  }

  private static java.util.Set<String> extractAllCustomerNames(String json) {
    var names = new java.util.HashSet<String>();
    int from = 0;
    while (true) {
      int start = json.indexOf("\"customerName\":\"", from);
      if (start < 0) break;
      start += "\"customerName\":\"".length();
      int end = json.indexOf('"', start);
      names.add(json.substring(start, end));
      from = end;
    }
    return names;
  }

  @Test
  void listPosLogPaginatesWithCursor() {
    // Dedicated tenant so POSLog entries created by other tests never leak into these pages.
    String tenant = "01a090ae-611e-701b-8b9c-fe24949dad64";
    var allIds = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      Response placed =
          post(
              "/orders",
              "{\"storeId\":\""
                  + S
                  + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                  + "\"items\":[{\"variantId\":\""
                  + V
                  + "\",\"qty\":1,\"unitPrice\":1.00}],\"currency\":\"USD\"}",
              tenant,
              Ids.newId().toString());
      assertThat(placed.getStatus(), is(201));
      String orderId = extractId(placed.readEntity(String.class));
      Response logged = post("/pos/log/orders/" + orderId, "", tenant);
      assertThat(logged.getStatus(), is(201));
      allIds.add(extractId(logged.readEntity(String.class)));
    }

    Response p1 = listPosLog(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllIds(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    Response p2 = listPosLog(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllIds(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen, is(allIds));
  }

  @Test
  void returnQuantityCannotExceedPurchased() {
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(r1.getStatus(), is(201));
    String orderId = extractId(r1.readEntity(String.class));
    // Paid for at the till, which hands it over: only then can anything come back.
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(orderId), Ids.newId(), new BigDecimal("20.00"));

    // returning 3 when only 2 were purchased must be rejected outright
    Response tooMany =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"too many\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":3}]}",
            T);
    assertThat(tooMany.getStatus(), is(409));
    assertThat(tooMany.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));

    // returning 1 (of 2) succeeds...
    Response first =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"first\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}",
            T);
    assertThat(first.getStatus(), is(201));

    // ...but a second return of 2 more (1 already returned + 2 > 2 purchased) must be rejected
    Response second =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"second\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":2}]}",
            T);
    assertThat(second.getStatus(), is(409));
    assertThat(second.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));
  }

  @Test
  void posVoidOrder() {
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],"
                + "\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(r1.getStatus(), is(201));
    String orderId = extractId(r1.readEntity(String.class));

    Response rv = post("/orders/" + orderId + "/void", "{\"reason\":\"cashier error\"}", T);
    assertThat(rv.getStatus(), is(200));
    assertThat(rv.readEntity(String.class), containsString("cashier error"));
  }

  @Test
  void layawayCreateAndDeposit() {
    Response r1 =
        post(
            "/layaways",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":100.00}],"
                + "\"initialDeposit\":30.00,"
                + "\"paymentMethod\":\"CASH\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String body = r1.readEntity(String.class);
    assertThat(body, containsString("ACTIVE"));
    assertThat(body, containsString("30"));
    String layawayId = extractLayawayId(body);

    // add more deposit
    Response r2 =
        post(
            "/layaways/" + layawayId + "/deposits",
            "{\"amount\":70.00,\"paymentMethod\":\"CARD\"}",
            T);
    assertThat(r2.getStatus(), is(200));

    // complete
    Response r3 = post("/layaways/" + layawayId + "/complete", "{}", T);
    assertThat(r3.getStatus(), is(200));
    assertThat(r3.readEntity(String.class), containsString("COMPLETED"));
  }

  @Test
  void layawayDepositCannotExceedOutstandingBalance() {
    Response created =
        post(
            "/layaways",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":100.00}],"
                + "\"initialDeposit\":30.00,"
                + "\"paymentMethod\":\"CASH\"}",
            T);
    assertThat(created.getStatus(), is(201));
    String layawayId = extractLayawayId(created.readEntity(String.class));

    // Outstanding balance is $70 — a $71 deposit must be rejected, not silently overpay.
    Response overpay =
        post(
            "/layaways/" + layawayId + "/deposits",
            "{\"amount\":71.00,\"paymentMethod\":\"CARD\"}",
            T);
    assertThat(overpay.getStatus(), is(400));
    assertThat(overpay.readEntity(String.class), containsString("LAYAWAY_DEPOSIT_EXCEEDS_BALANCE"));

    // The exact remaining balance is still accepted.
    Response exact =
        post(
            "/layaways/" + layawayId + "/deposits",
            "{\"amount\":70.00,\"paymentMethod\":\"CARD\"}",
            T);
    assertThat(exact.getStatus(), is(200));
  }

  @Test
  void giftCardIssueReloadRedeem() {
    Response r1 =
        post(
            "/gift-cards",
            "{\"storeId\":\"" + S + "\",\"amount\":50.00,\"currency\":\"USD\",\"paidBy\":\"CASH\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String gcBody = r1.readEntity(String.class);
    assertThat(gcBody, containsString("ACTIVE"));
    String code = extractCode(gcBody);

    // lookup
    Response rg = get("/gift-cards/" + code, T);
    assertThat(rg.getStatus(), is(200));

    // reload
    Response r2 =
        post("/gift-cards/" + code + "/reload", "{\"amount\":20.00,\"paidBy\":\"CARD\"}", T);
    assertThat(r2.getStatus(), is(200));
    assertThat(r2.readEntity(String.class), containsString("70"));

    // redeem
    Response r3 = post("/gift-cards/" + code + "/redeem", "{\"amount\":30.00}", T);
    assertThat(r3.getStatus(), is(200));

    // tenant isolation — other tenant cannot see this card
    Response rIso = get("/gift-cards/" + code, "01a090ae-611e-701d-9d60-a9d7516ed03b");
    assertThat(rIso.getStatus(), is(404));
  }

  /**
   * A POS sale captured while the till is offline is replayed later by re-sending every write in
   * the sale. Order placement and tender capture already replay on their Idempotency-Key; gift-card
   * redemption had no key at all and simply decremented, so a replay took the money twice. Redeem
   * is now idempotent per (card, order).
   */
  @Test
  void giftCardRedeemIsIdempotentPerOrder() {
    String gcBody =
        post("/gift-cards", "{\"storeId\":\"" + S + "\",\"amount\":50.00,\"paidBy\":\"CASH\"}", T)
            .readEntity(String.class);
    String code = extractCode(gcBody);

    String orderId =
        extractId(
            post(
                    "/orders",
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"POS\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":5.00}]}",
                    T,
                    Ids.newId().toString())
                .readEntity(String.class));

    String redeem = "{\"amount\":30.00,\"orderId\":\"" + orderId + "\"}";
    Response first = post("/gift-cards/" + code + "/redeem", redeem, T);
    assertThat(first.getStatus(), is(200));
    assertThat(first.readEntity(String.class), containsString("\"currentBalance\":20.0"));

    // The replay must be a no-op, not a second deduction.
    Response replay = post("/gift-cards/" + code + "/redeem", redeem, T);
    assertThat(replay.getStatus(), is(200));
    assertThat(replay.readEntity(String.class), containsString("\"currentBalance\":20.0"));

    assertThat(
        get("/gift-cards/" + code, T).readEntity(String.class),
        containsString("\"currentBalance\":20.0"));

    // A different order genuinely redeems again — the guard is per order, not per card.
    String otherOrder =
        extractId(
            post(
                    "/orders",
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"POS\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":5.00}]}",
                    T,
                    Ids.newId().toString())
                .readEntity(String.class));
    Response second =
        post(
            "/gift-cards/" + code + "/redeem",
            "{\"amount\":5.00,\"orderId\":\"" + otherOrder + "\"}",
            T);
    assertThat(second.getStatus(), is(200));
    assertThat(second.readEntity(String.class), containsString("\"currentBalance\":15.0"));
  }

  /**
   * A gift card sold is a liability against the money taken (17.11): issuing and reloading say how
   * the value was paid for, refuse what is not a tender, and announce the load once, with the
   * write.
   */
  @Test
  void aGiftCardLoadSaysHowItWasPaidForAndIsAnnouncedOnce() {
    String body = "{\"storeId\":\"" + S + "\",\"amount\":40.00";
    assertThat(post("/gift-cards", body + "}", T).getStatus(), is(400));
    for (String refused : new String[] {"GIFT_CARD", "VOUCHER", "STORE_CREDIT", "IOU", " "}) {
      assertThat(
          refused,
          post("/gift-cards", body + ",\"paidBy\":\"" + refused + "\"}", T).getStatus(),
          is(400));
    }
    assertThat(
        post("/gift-cards", body + ",\"paidBy\":\"VOUCHER\"}", T).readEntity(String.class),
        containsString("GIFT_CARD_PAID_BY_INVALID"));

    String issued = post("/gift-cards", body + ",\"paidBy\":\"card\"}", T).readEntity(String.class);
    UUID cardId = Ids.parse(extractId(issued));
    String code = extractCode(issued);
    assertThat(outboxCount(cardId, "GiftCardLoaded"), is(1L));
    String issue = outboxPayload(cardId, "GiftCardLoaded");
    assertThat(issue, containsString("\"kind\":\"ISSUE\""));
    assertThat(issue, containsString("\"paidBy\":\"CARD\""));
    assertThat(issue, containsString("\"amount\":40"));

    assertThat(
        post("/gift-cards/" + code + "/reload", "{\"amount\":10.00}", T).getStatus(), is(400));
    assertThat(
        post("/gift-cards/" + code + "/reload", "{\"amount\":10.00,\"paidBy\":\"STORE_CREDIT\"}", T)
            .getStatus(),
        is(400));
    assertThat(outboxCount(cardId, "GiftCardLoaded"), is(1L));
    assertThat(
        post("/gift-cards/" + code + "/reload", "{\"amount\":10.00,\"paidBy\":\"PROMOTIONAL\"}", T)
            .getStatus(),
        is(200));
    assertThat(outboxCount(cardId, "GiftCardLoaded"), is(2L));
    assertThat(
        outboxPayloads(cardId, "GiftCardLoaded").stream()
            .anyMatch(
                p -> p.contains("\"kind\":\"RELOAD\"") && p.contains("\"paidBy\":\"PROMOTIONAL\"")),
        is(true));
    assertThat(
        post("/gift-cards/NOPE-NOPE-NOPE/reload", "{\"amount\":10.00,\"paidBy\":\"CASH\"}", T)
            .getStatus(),
        is(404));
  }

  @Test
  void voidOnlineOrderFails() {
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"ONLINE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],"
                + "\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    String orderId = extractId(r1.readEntity(String.class));
    Response rv = post("/orders/" + orderId + "/void", "{\"reason\":\"test\"}", T);
    assertThat(rv.getStatus(), is(409));
  }

  @Test
  void orderByIdReadsAreObjectLevelAuthorized() {
    // Placed by the shopper themselves, which is what "their order" means: the order records the
    // login their token carries. It used to be placed at a till with an invented customerId that
    // the test then sent as a user id — two different kinds of id that only matched because the
    // same string played both parts (SJ-D44).
    String owningCustomer = Ids.newId().toString();
    Response placed =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", owningCustomer)
            .header("X-User-Email", "owner@example.com")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":5.00}],\"currency\":\"USD\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(placed.getStatus(), is(201));
    String orderId = extractId(placed.readEntity(String.class));

    // The owning customer may read their order, its history and its returns.
    assertThat(getAs("/orders/" + orderId, T, owningCustomer, "CUSTOMER").getStatus(), is(200));
    assertThat(
        getAs("/orders/" + orderId + "/history", T, owningCustomer, "CUSTOMER").getStatus(),
        is(200));
    assertThat(
        getAs("/orders/" + orderId + "/returns", T, owningCustomer, "CUSTOMER").getStatus(),
        is(200));

    // Another authenticated customer in the same tenant gets 404 (not 403 — no existence oracle).
    String otherCustomer = Ids.newId().toString();
    assertThat(getAs("/orders/" + orderId, T, otherCustomer, "CUSTOMER").getStatus(), is(404));
    assertThat(
        getAs("/orders/" + orderId + "/history", T, otherCustomer, "CUSTOMER").getStatus(),
        is(404));
    assertThat(
        getAs("/orders/" + orderId + "/returns", T, otherCustomer, "CUSTOMER").getStatus(),
        is(404));

    // Staff read any order in the tenant. Stated with an actual role: this assertion used to send
    // none and pass through the service-to-service exemption below, so it was not testing staff.
    assertThat(getAs("/orders/" + orderId, T, null, "CASHIER").getStatus(), is(200));

    // A caller with no principal at all is now refused. That exemption existed for payment-svc,
    // and rested on the gateway never forwarding a tenant here without a verified user — but guest
    // checkout does exactly that, so any order id could be read by anyone holding one. payment-svc
    // stamps a staff role instead (see payment-svc OrderClient).
    Response anonymous = target.path("/orders/" + orderId).request().header("X-Tenant-Id", T).get();
    assertThat(anonymous.getStatus(), is(404));

    // A till sale attached to a customer record is not reachable by a shopper's token: it carries
    // no login, and a customer id is not a login id. Staff see it; the storefront does not.
    Response till =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"customerId\":\""
                + owningCustomer
                + "\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(till.getStatus(), is(201));
    String tillOrder = extractId(till.readEntity(String.class));
    assertThat(getAs("/orders/" + tillOrder, T, owningCustomer, "CUSTOMER").getStatus(), is(404));
    assertThat(getAs("/orders/" + tillOrder, T, null, "CASHIER").getStatus(), is(200));
  }

  @Test
  void placeOrderRejectsNonPositiveItemQty() {
    // items is @NotNull @Valid — a zero qty must be rejected by cascading Bean Validation instead
    // of silently placing an order for nothing.
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":0,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void cancelRejectsABlankReason() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(placed.getStatus(), is(201));
    String orderId = extractId(placed.readEntity(String.class));

    // A body with a blank reason violates VoidRequest's @NotBlank and must be rejected — this
    // constraint previously went unenforced because the resource never called Validations.validate.
    assertThat(post("/orders/" + orderId + "/cancel", "{\"reason\":\"\"}", T).getStatus(), is(400));

    // A body with a real reason still works.
    assertThat(
        post("/orders/" + orderId + "/cancel", "{\"reason\":\"customer changed mind\"}", T)
            .getStatus(),
        is(200));
  }

  // ── Sales by hour / by staff ───────────────────────────────────────────────

  /**
   * The timezone is the whole report. The same order, bucketed on two different clocks, has to land
   * in two different hours — otherwise a shop outside UTC is being told its peak is at the wrong
   * time of day, which is the one thing the report is for.
   */
  @Test
  void salesByHourBucketsOnTheRequestedTimezoneNotUtc() {
    String tenant = "01a090ae-611e-7015-8c11-fd62230bf57a";
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":15.00}]}",
            tenant,
            Ids.newId().toString());
    assertThat(placed.getStatus(), is(201));
    confirm(tenant, extractId(placed.readEntity(String.class)));

    int utcHour = hourOf(salesByHour(tenant, "UTC"));
    // Asia/Dubai is UTC+4 all year and never observes DST, so the shift is exactly four hours
    // whenever this test happens to run. A half-hour zone like Asia/Kolkata would have been the
    // more interesting case and a worse assertion: whether its hour lands +5 or +6 depends on
    // the minute the test started. Modulo 24 because the day can roll over.
    int dubaiHour = hourOf(salesByHour(tenant, "Asia/Dubai"));
    assertThat(dubaiHour, is((utcHour + 4) % 24));

    // The admin app cannot read the browser's IANA zone name, so it sends a fixed offset. The
    // form matters and the trap is silent: Postgres reads "UTC+04:00" under the POSIX
    // convention, where the sign is INVERTED, while Java's ZoneId.of accepts it meaning the
    // opposite — so that spelling would validate and then bucket every hour eight hours out.
    // "+04:00" is an ISO offset to both. This pins that the form the client sends agrees with a
    // named zone at the same offset.
    assertThat(hourOf(salesByHour(tenant, "+04:00")), is(dubaiHour));

    // 2 x 15.00 in one order: the basket average is the order value, not the line value.
    String body = salesByHour(tenant, "UTC");
    assertThat(body, containsString("\"orders\":1"));
    assertThat(body, containsString("\"averageBasket\":30.00"));

    // An unparseable zone is the caller's error, not a 500 from Postgres rejecting it.
    Response bad = getQuery("/admin/reports/sales-by-hour", tenant, "tz", "Europe/Londn");
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("ORDER_INVALID_TIMEZONE"));

    Response badChannel = getQuery("/admin/reports/sales-by-hour", tenant, "channel", "CARRIER");
    assertThat(badChannel.getStatus(), is(400));
    assertThat(badChannel.readEntity(String.class), containsString("ORDER_INVALID_CHANNEL"));
  }

  /**
   * Only money that was actually taken counts. A PENDING order has not been paid for and a
   * cancelled one has been unmade — including either would put a trading peak where none happened.
   */
  @Test
  void salesByHourCountsOnlyRevenueOrders() {
    String tenant = "01a090ae-611e-7016-a809-076a3374b722";
    // Placed and left PENDING: no money has changed hands.
    Response pending =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":99.00}]}",
            tenant,
            Ids.newId().toString());
    assertThat(pending.getStatus(), is(201));

    assertThat(salesByHour(tenant, "UTC"), not(containsString("\"orders\"")));

    // Confirming the same order makes it revenue, and now it counts.
    confirm(tenant, extractId(pending.readEntity(String.class)));
    assertThat(salesByHour(tenant, "UTC"), containsString("\"grossAmount\":99.00"));
  }

  /**
   * Takings per cashier come from the POS journal, which is the only place that knows who served
   * whom. A cashier who journalled nothing is absent rather than zero, and the discount rate is a
   * share of the undiscounted ticket, not of what was left after the discount.
   */
  @Test
  void salesByStaffAttributesTakingsToTheCashierWhoJournalledThem() {
    String tenant = "01a090ae-611e-7017-bdd9-d7612c647032";
    String cashier = "01a090ae-611e-7028-aeff-c236d3874dec";

    // 20.00 ticket with 5.00 off: 15.00 taken, 25% of the ticket given away.
    String orderId =
        extractId(
            postAs(
                    "/orders",
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"POS\","
                        + "\"discountAmount\":5.00,\"discountReason\":\"damaged box\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":20.00}]}",
                    tenant,
                    cashier,
                    "MANAGER",
                    Ids.newId().toString())
                .readEntity(String.class));
    assertThat(
        postAs("/pos/log/orders/" + orderId, "{}", tenant, cashier, "CASHIER", null).getStatus(),
        is(201));

    String body = get("/admin/reports/sales-by-staff", tenant).readEntity(String.class);
    assertThat(body, containsString(cashier));
    assertThat(body, containsString("\"sales\":1"));
    assertThat(body, containsString("\"grossAmount\":15.00"));
    assertThat(body, containsString("\"discountAmount\":5.00"));
    assertThat(body, containsString("\"averageBasket\":15.00"));
    // 5 of the 20 the ticket would have fetched: 25.0%, not 33.3% of the 15 taken.
    assertThat(body, containsString("\"discountRate\":25.0"));

    // Another tenant's takings are never in this one's report.
    assertThat(
        get("/admin/reports/sales-by-staff", "01a090ae-611e-7018-b51a-6051d93278c1")
            .readEntity(String.class),
        not(containsString(cashier)));
  }

  /** A backwards window is rejected before either query runs, on both endpoints. */
  @Test
  void salesReportsRejectABackwardsWindow() {
    for (String path :
        new String[] {"/admin/reports/sales-by-hour", "/admin/reports/sales-by-staff"}) {
      Response r =
          target
              .path(path)
              .queryParam("from", "2026-02-01T00:00:00Z")
              .queryParam("to", "2026-01-01T00:00:00Z")
              .request()
              .header("X-Tenant-Id", T)
              .header("X-Roles", "OWNER")
              .get();
      assertThat(r.getStatus(), is(400));
      assertThat(r.readEntity(String.class), containsString("ORDER_INVALID_PERIOD"));
    }
  }

  /** Move a placed order to CONFIRMED, which is what makes it revenue. */
  private void confirm(String tenant, String orderId) {
    assertThat(post("/orders/" + orderId + "/confirm", "{}", tenant).getStatus(), is(200));
  }

  private String salesByHour(String tenant, String tz) {
    return getQuery("/admin/reports/sales-by-hour", tenant, "tz", tz).readEntity(String.class);
  }

  /** The single hourOfDay in a one-row sales-by-hour response. */
  private static int hourOf(String json) {
    String key = "\"hourOfDay\":";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError("no hourOfDay in " + json);
    int start = i + key.length();
    int end = start;
    while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) end++;
    return Integer.parseInt(json.substring(start, end).trim());
  }

  // ── Staff exception report ─────────────────────────────────────────────────

  /**
   * The report exists to answer "which cashier is an outlier". These pin the two things that make
   * it an answer rather than a table: the three logs are merged rather than joined, and a group
   * that appears in only one of them still gets a row.
   */
  @Test
  void exceptionReportMergesTheThreeLogsPerActor() {
    String cashier = "01a090ae-611e-7024-812b-f6199d71d0ca";
    String other = "01a090ae-611e-7025-802e-c47f396e20e3";

    // One discounted POS sale by `cashier`.
    String orderId =
        extractId(
            postAs(
                    "/orders",
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"POS\","
                        + "\"discountAmount\":2.00,\"discountReason\":\"damaged box\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,"
                        + "\"unitPrice\":20.00}]}",
                    T,
                    cashier,
                    "MANAGER",
                    Ids.newId().toString())
                .readEntity(String.class));

    // Two no-sales: one by the same cashier, one by somebody else who sold nothing at all.
    for (String who : new String[] {cashier, other}) {
      Response ns =
          postAs(
              "/pos/no-sale",
              "{\"storeId\":\"" + S + "\",\"reason\":\"drawer check\"}",
              T,
              who,
              "CASHIER",
              null);
      assertThat(ns.getStatus(), is(201));
    }

    // Journal the sale, so the report has a denominator for `cashier`.
    Response journal = postAs("/pos/log/orders/" + orderId, "{}", T, cashier, "CASHIER", null);
    assertThat(journal.getStatus(), is(201));

    String body =
        getQuery("/admin/reports/exceptions", T, "groupBy", "ACTOR").readEntity(String.class);

    // The discounting cashier: one discount worth 2.00, one no-sale, one journalled sale.
    assertThat(body, containsString(cashier));
    assertThat(body, containsString("\"discounts\":1"));
    assertThat(body, containsString("\"noSales\":1"));
    assertThat(body, containsString("\"sales\":1"));

    // The second cashier sold nothing and opened the drawer anyway — the case the report is for.
    // A join across the three logs would have dropped this row entirely.
    assertThat(body, containsString(other));

    // A denominator exists, so rates are meaningful.
    assertThat(body, containsString("\"journalCoverage\":true"));
  }

  /**
   * With nothing journalled there is no denominator, and the report has to say so rather than
   * present zeroes that read as "this cashier made no sales".
   */
  @Test
  void exceptionReportDeclaresWhenItHasNoDenominator() {
    Response ns =
        postAs(
            "/pos/no-sale",
            "{\"storeId\":\"" + S + "\",\"reason\":\"no journal here\"}",
            "01a090ae-611e-7014-8cd5-baf0862fa319",
            "01a090ae-611e-7026-b9ca-bb6f7d2eb775",
            "CASHIER",
            null);
    assertThat(ns.getStatus(), is(201));

    String body =
        get("/admin/reports/exceptions", "01a090ae-611e-7014-8cd5-baf0862fa319")
            .readEntity(String.class);
    assertThat(body, containsString("\"noSales\":1"));
    assertThat(body, containsString("\"sales\":0"));
    assertThat(body, containsString("\"journalCoverage\":false"));
  }

  @Test
  void exceptionReportRejectsAnUnknownGrouping() {
    Response r = getQuery("/admin/reports/exceptions", T, "groupBy", "WEATHER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("ORDER_INVALID_GROUPING"));
  }

  /**
   * The journal write used to sit under /admin/, which is management-gated — so the cashier who
   * took the sale could not journal it and nothing ever did. It is now on the till's own path.
   */
  @Test
  void aCashierCanJournalTheirOwnSaleAndReplayIsANoOp() {
    String cashier = "01a090ae-611e-7027-bae4-be01a178d6ef";
    String orderId =
        extractId(
            postAs(
                    "/orders",
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"POS\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,"
                        + "\"unitPrice\":9.00}]}",
                    T,
                    cashier,
                    "CASHIER",
                    Ids.newId().toString())
                .readEntity(String.class));

    Response first = postAs("/pos/log/orders/" + orderId, "{}", T, cashier, "CASHIER", null);
    assertThat(first.getStatus(), is(201));
    String firstId = extractId(first.readEntity(String.class));

    // A retry — or an offline sale replayed later — returns the same entry, not a 409.
    Response replay = postAs("/pos/log/orders/" + orderId, "{}", T, cashier, "CASHIER", null);
    assertThat(replay.getStatus(), is(201));
    assertThat(extractId(replay.readEntity(String.class)), is(firstId));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** GET with one query parameter — `get` bakes its argument into the path, which encodes '?'. */
  private Response getQuery(String path, String tenant, String key, String value) {
    return target
        .path(path)
        .queryParam(key, value)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  /** POST as a specific principal and role, which the OWNER-stamped helpers cannot express. */
  private Response postAs(
      String path, String json, String tenant, String userId, String roles, String idempotencyKey) {
    var req =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", userId)
            .header("X-Roles", roles);
    if (idempotencyKey != null) req = req.header("Idempotency-Key", idempotencyKey);
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response getAs(String path, String tenant, String userId, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", userId)
        .header("X-Roles", roles)
        .get();
  }

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  /**
   * JSON-B (Yasson) serialises record components alphabetically, so in LayawayResponse the
   * "deposits" array (with its own "id" fields) appears before the top-level "id" in the JSON
   * stream. A naïve first-occurrence search grabs a deposit UUID instead of the layaway UUID. This
   * helper skips past the closing "]" of the deposits array and then reads the next "id" value,
   * which is the layaway's own id.
   */
  private static String extractLayawayId(String json) {
    // Find where the "deposits" array key starts, then skip to its closing ']'
    int depositsKey = json.indexOf("\"deposits\":");
    int depositsArrayClose = json.indexOf("]", depositsKey);
    // The next "id" after the deposits array is the top-level layaway id
    int start = json.indexOf("\"id\":\"", depositsArrayClose) + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static java.util.Set<String> extractAllIds(String json) {
    var ids = new java.util.HashSet<String>();
    int from = 0;
    while (true) {
      int start = json.indexOf("\"id\":\"", from);
      if (start < 0) break;
      start += 6;
      int end = json.indexOf("\"", start);
      ids.add(json.substring(start, end));
      from = end;
    }
    return ids;
  }

  /** Returns meta.nextCursor, or null when the field is absent/null (no further page). */
  private static String extractNextCursor(String json) {
    int key = json.indexOf("\"nextCursor\":");
    if (key < 0) return null;
    int valueStart = key + "\"nextCursor\":".length();
    if (json.startsWith("null", valueStart)) return null;
    int start = json.indexOf("\"", valueStart) + 1;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static String extractCode(String json) {
    int start = json.indexOf("\"code\":\"") + 8;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  // ── SJ-D2: currency comes from the tenant, not a hardcoded literal ──────────

  /**
   * Orders, gift cards and special orders each used to stamp their own literal ("USD", "USD",
   * "GBP") while pricing-svc priced lines in the price list's currency. All three now resolve
   * through the tenant's projected currency, a request naming a different one is rejected, and a
   * tenant with no projection yet is read from tenant-svc. The single configured default that used
   * to stand behind it is gone (SJ-D53): it stamped pounds on any tenant whose projection lagged,
   * so an undescribed tenant is now refused rather than guessed.
   */
  @Test
  void currencyResolvesFromTheTenantNotAHardcodedLiteral() {
    // A tenant whose TenantCreated has been projected — the normal case in a running system.
    String tenant = Ids.newId().toString();
    boolean projected =
        tenantStatus.projectTenantCurrencyOnce(
            Ids.newId(), "order-svc/tenant-created", Ids.parse(tenant), "gbp");
    assertThat(projected, is(true));

    // Omitting currency stamps the tenant's own, not "USD".
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}]}",
            tenant,
            Ids.newId().toString());
    assertThat(placed.getStatus(), is(201));
    assertThat(placed.readEntity(String.class), containsString("\"currency\":\"GBP\""));

    // A gift card for the same tenant agrees — it used to default to "USD" independently.
    Response giftCard =
        post(
            "/gift-cards",
            "{\"storeId\":\"" + S + "\",\"amount\":25.00,\"paidBy\":\"CASH\"}",
            tenant);
    assertThat(giftCard.getStatus(), is(201));
    assertThat(giftCard.readEntity(String.class), containsString("\"currency\":\"GBP\""));

    // A special order too — it used to default to "GBP", i.e. right by accident, wrong in general.
    Response specialOrder =
        post(
            "/admin/special-orders",
            "{\"storeId\":\""
                + S
                + "\",\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}]}",
            tenant);
    assertThat(specialOrder.getStatus(), is(201));
    assertThat(specialOrder.readEntity(String.class), containsString("\"currency\":\"GBP\""));

    // Naming a currency the tenant does not trade in is rejected, not silently overridden —
    // otherwise a mispriced basket would be hidden rather than surfaced.
    Response mismatch =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            tenant,
            Ids.newId().toString());
    assertThat(mismatch.getStatus(), is(400));
    assertThat(mismatch.readEntity(String.class), containsString("ORDER_CURRENCY_MISMATCH"));

    // A tenant with no projection yet (onboarded before this existed, or event lag) is read from
    // tenant-svc: a yen tenant's till sale is in yen, not in a configured default.
    String unprojected = Ids.newId().toString();
    TENANTS.with(unprojected, "JPY", "JP");
    Response fromProfile =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":1000}]}",
            unprojected,
            Ids.newId().toString());
    String fromProfileBody = fromProfile.readEntity(String.class);
    assertThat(fromProfileBody, fromProfile.getStatus(), is(201));
    assertThat(fromProfileBody, containsString("\"currency\":\"JPY\""));

    // ...and the same rule holds there: pounds for a yen tenant are refused.
    Response yenMismatch =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],\"currency\":\"GBP\"}",
            unprojected,
            Ids.newId().toString());
    assertThat(yenMismatch.getStatus(), is(400));

    // A tenant neither the projection nor tenant-svc can describe is refused, not guessed.
    String undescribed = Ids.newId().toString();
    Response refused =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}]}",
            undescribed,
            Ids.newId().toString());
    String refusedBody = refused.readEntity(String.class);
    assertThat(refusedBody, refused.getStatus(), is(503));
    assertThat(refusedBody, containsString("TENANT_PROFILE_UNAVAILABLE"));
    assertThat(get("/orders", undescribed).readEntity(String.class), not(containsString(S)));
  }

  /** A redelivered TenantCreated must not re-apply the projection (golden rule #7). */
  @Test
  void tenantCurrencyProjectionIsIdempotent() {
    UUID tenant = Ids.newId();
    UUID eventId = Ids.newId();
    assertThat(
        tenantStatus.projectTenantCurrencyOnce(eventId, "order-svc/tenant-created", tenant, "EUR"),
        is(true));
    assertThat(
        tenantStatus.projectTenantCurrencyOnce(eventId, "order-svc/tenant-created", tenant, "EUR"),
        is(false));
    assertThat(tenantStatus.findCurrency(tenant).orElseThrow(), is("EUR"));
  }

  // ── SJ-D35: a customer order can be part-fulfilled ─────────────────────────

  private UUID placeOnlinePickup(int qty) {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":"
                + qty
                + ",\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    String body = placed.readEntity(String.class);
    assertThat(body, placed.getStatus(), is(201));
    return Ids.parse(extractId(body));
  }

  private static String fulfilBody(String variantId, String qty) {
    return "{\"lines\":[{\"variantId\":\"" + variantId + "\",\"qty\":" + qty + "}]}";
  }

  private static BigDecimal fulfilledQtyOf(String orderJson) {
    var m = java.util.regex.Pattern.compile("\"fulfilledQty\":([0-9.]+)").matcher(orderJson);
    assertThat(orderJson, m.find(), is(true));
    return new BigDecimal(m.group(1));
  }

  private static java.util.List<String> outboxPayloads(UUID orderId, String eventType) {
    var out = new java.util.ArrayList<String>();
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM \"order\".outbox WHERE aggregate_id = ? AND event_type = ?"
                    + " ORDER BY created_at")) {
      ps.setObject(1, orderId);
      ps.setString(2, eventType);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  @Test
  @DisplayName("Four of five units are handed over, then the fifth — one event per handover")
  void partOfAnOrderIsHandedOverThenTheRest() {
    UUID order = placeOnlinePickup(5);
    assertThat(post("/orders/" + order + "/confirm", "{}", T).getStatus(), is(200));

    Response part = post("/orders/" + order + "/fulfil", fulfilBody(V, "4"), T);
    assertThat(part.getStatus(), is(200));
    String body = part.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"PARTIALLY_FULFILLED\""));
    assertThat(fulfilledQtyOf(body).compareTo(new BigDecimal("4")), is(0));
    // inventory-svc deducts what left the store now: four, not five and not nine later.
    var events = outboxPayloads(order, "OrderFulfilled");
    assertThat(events.size(), is(1));
    assertThat(events.get(0), containsString("\"qty\":4"));
    assertThat(
        get("/orders/" + order + "/history", T).readEntity(String.class),
        containsString("part-fulfilled: 4 of 5 units handed over"));

    // No body: the rest.
    Response rest = post("/orders/" + order + "/fulfil", "{}", T);
    assertThat(rest.getStatus(), is(200));
    String done = rest.readEntity(String.class);
    assertThat(done, containsString("\"status\":\"FULFILLED\""));
    assertThat(fulfilledQtyOf(done).compareTo(new BigDecimal("5")), is(0));
    events = outboxPayloads(order, "OrderFulfilled");
    assertThat(events.size(), is(2));
    assertThat(events.get(1), containsString("\"qty\":1"));
    // Nothing left: a third handover is refused, not silently a no-op.
    Response again = post("/orders/" + order + "/fulfil", "{}", T);
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("ORDER_NOT_FULFILLABLE"));
  }

  @Test
  @DisplayName("More than is outstanding, a line not on the order, or nothing at all is refused")
  void handoverIsCheckedAgainstWhatIsOutstanding() {
    UUID order = placeOnlinePickup(3);
    assertThat(post("/orders/" + order + "/confirm", "{}", T).getStatus(), is(200));
    Response tooMany = post("/orders/" + order + "/fulfil", fulfilBody(V, "4"), T);
    assertThat(tooMany.getStatus(), is(409));
    assertThat(
        tooMany.readEntity(String.class), containsString("ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING"));
    Response unknown =
        post("/orders/" + order + "/fulfil", fulfilBody(Ids.newId().toString(), "1"), T);
    assertThat(unknown.getStatus(), is(400));
    assertThat(unknown.readEntity(String.class), containsString("ORDER_FULFIL_LINE_UNKNOWN"));
    assertThat(post("/orders/" + order + "/fulfil", fulfilBody(V, "0"), T).getStatus(), is(400));
    assertThat(post("/orders/" + order + "/fulfil", fulfilBody(V, "-1"), T).getStatus(), is(400));
    // None of that moved anything.
    String still = get("/orders/" + order, T).readEntity(String.class);
    assertThat(still, containsString("\"status\":\"CONFIRMED\""));
    assertThat(fulfilledQtyOf(still).signum(), is(0));
    assertThat(outboxCount(order, "OrderFulfilled"), is(0L));
    // Two handovers of two: the second is one too many.
    assertThat(post("/orders/" + order + "/fulfil", fulfilBody(V, "2"), T).getStatus(), is(200));
    Response over = post("/orders/" + order + "/fulfil", fulfilBody(V, "2"), T);
    assertThat(over.getStatus(), is(409));
    assertThat(over.readEntity(String.class), containsString("1 still outstanding"));
  }

  @Test
  @DisplayName("A wave's handover is applied once per dedupe id, on the handover's own transaction")
  void aWaveHandoverIsAppliedOncePerDedupeId() {
    // A wave picked at the store hands the order over through the same path as the button, once
    // per event-derived id: the dedupe mark rides the handover's own transaction, so told twice
    // the service hands over once, and what is left is exactly what a person can still hand over.
    UUID order = placeOnlinePickup(5);
    assertThat(post("/orders/" + order + "/confirm", "{}", T).getStatus(), is(200));
    UUID dedupe = Ids.newId();
    var two =
        new com.storeql.order.dto.Dtos.FulfilRequest(
            List.of(new com.storeql.order.dto.Dtos.FulfilLine(V, new BigDecimal("2"))));
    assertThat(
        orderService.fulfilOrderOnce(dedupe, "test/wave", Ids.parse(T), order, two), is(true));
    assertThat(
        orderService.fulfilOrderOnce(dedupe, "test/wave", Ids.parse(T), order, two), is(false));
    // Two handed over, once: four more is too many, three is exactly what is left.
    Response tooMany = post("/orders/" + order + "/fulfil", fulfilBody(V, "4"), T);
    assertThat(tooMany.getStatus(), is(409));
    assertThat(
        tooMany.readEntity(String.class), containsString("ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING"));
    assertThat(post("/orders/" + order + "/fulfil", fulfilBody(V, "3"), T).getStatus(), is(200));
  }

  @Test
  @DisplayName(
      "A part-fulfilled order cannot be cancelled, and returns are capped by what was handed over")
  void partFulfilledOrderKeepsItsGoodsHonest() {
    UUID order = placeOnlinePickup(5);
    assertThat(post("/orders/" + order + "/confirm", "{}", T).getStatus(), is(200));
    assertThat(post("/orders/" + order + "/fulfil", fulfilBody(V, "2"), T).getStatus(), is(200));
    // Some goods went out: cancelling would release stock the customer is holding.
    Response cancel = post("/orders/" + order + "/cancel", "{\"reason\":\"changed mind\"}", T);
    assertThat(cancel.getStatus(), is(409));
    assertThat(cancel.readEntity(String.class), containsString("ORDER_PARTLY_FULFILLED"));
    // Three back when only two were handed over: a refund for goods the customer never had.
    String three =
        "{\"reason\":\"faulty\",\"refundMethod\":\"CASH\",\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":3}]}";
    Response tooMany = post("/orders/" + order + "/returns", three, T);
    assertThat(tooMany.getStatus(), is(409));
    assertThat(tooMany.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));
    String two =
        "{\"reason\":\"faulty\",\"refundMethod\":\"CASH\",\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":2}]}";
    assertThat(post("/orders/" + order + "/returns", two, T).getStatus(), is(201));
  }

  @Test
  @DisplayName("A PENDING order, or one at another store, cannot be handed over")
  void handoverNeedsAConfirmedOrderAtYourStore() {
    UUID pending = placeOnlinePickup(1);
    Response early = post("/orders/" + pending + "/fulfil", "{}", T);
    assertThat(early.getStatus(), is(409));
    assertThat(early.readEntity(String.class), containsString("ORDER_NOT_FULFILLABLE"));
    assertThat(post("/orders/" + pending + "/confirm", "{}", T).getStatus(), is(200));
    // A cashier scoped to a different store: the order is not theirs to hand over.
    Response elsewhere =
        target
            .path("/orders/" + pending + "/fulfil")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .header("X-Store-Ids", Ids.newId().toString())
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(elsewhere.getStatus(), is(403));
    assertThat(
        get("/orders/" + pending, T).readEntity(String.class),
        containsString("\"status\":\"CONFIRMED\""));
  }

  @Test
  @DisplayName("A till sale is still handed over whole: every line marked, one event")
  void tillSaleIsHandedOverWhole() {
    UUID order = placeAt("POS", "INSTORE");
    assertThat(post("/orders/" + order + "/confirm", "{}", T).getStatus(), is(200));
    String body = get("/orders/" + order, T).readEntity(String.class);
    assertThat(body, containsString("\"status\":\"FULFILLED\""));
    assertThat(fulfilledQtyOf(body).compareTo(new BigDecimal("2")), is(0));
    assertThat(outboxCount(order, "OrderFulfilled"), is(1L));
  }

  // ── SJ-D41: a catalog-mode till order waits for a price, and is not swept ───

  private UUID placeAwaitingPrice() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"PICKUP\",\"awaitingPrice\":true,"
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":3,\"unitPrice\":0}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    String body = placed.readEntity(String.class);
    assertThat(body, placed.getStatus(), is(201));
    assertThat(body, containsString("\"status\":\"AWAITING_PRICE\""));
    return Ids.parse(extractId(body));
  }

  private static String priceBody(String variantId, String unitPrice, String tax) {
    return "{\"lines\":[{\"variantId\":\""
        + variantId
        + "\",\"unitPrice\":"
        + unitPrice
        + "}],\"taxAmount\":"
        + tax
        + "}";
  }

  @Test
  @DisplayName("A catalog-mode order is not swept as stranded, however old it is")
  void awaitingPriceOrderIsNotSwept() {
    UUID order = placeAwaitingPrice();
    // TTL 0: every PENDING order is expired. This one is not PENDING; it is waiting for a person.
    orderService.sweepExpiredPendingOrders(0, 200);
    String body = get("/orders/" + order, T).readEntity(String.class);
    assertThat(body, containsString("\"status\":\"AWAITING_PRICE\""));
    assertThat(body, not(containsString("CANCELLED")));
  }

  @Test
  @DisplayName("A manager prices it; the totals follow and it becomes an ordinary PENDING order")
  void managerPricesTheOrder() {
    UUID order = placeAwaitingPrice();
    Response priced =
        postAs(
            "/orders/" + order + "/price", priceBody(V, "4.50", "2.70"), T, USER, "MANAGER", null);
    assertThat(priced.getStatus(), is(200));
    String body = priced.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"PENDING\""));
    assertThat(body, containsString("\"subtotal\":13.50"));
    assertThat(body, containsString("\"taxAmount\":2.70"));
    assertThat(body, containsString("\"total\":16.20"));
    assertThat(body, containsString("\"unitPrice\":4.50"));
    assertThat(body, containsString("\"lineTotal\":13.50"));
    assertThat(
        get("/orders/" + order + "/history", T).readEntity(String.class),
        containsString("priced: total 16.20"));
    // Priced, it is paid for like any other till order — and handed over when the payment lands.
    orderService.handlePaymentCaptured(Ids.parse(T), order, Ids.newId(), new BigDecimal("16.20"));
    assertThat(statusOf(order), is("FULFILLED"));
    // And it cannot be priced twice.
    Response again =
        postAs("/orders/" + order + "/price", priceBody(V, "9.99", "0"), T, USER, "MANAGER", null);
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("ORDER_NOT_AWAITING_PRICE"));
  }

  @Test
  @DisplayName("Pricing needs every line, a price of at least zero, and a manager")
  void pricingIsCheckedAndManagementOnly() {
    UUID order = placeAwaitingPrice();
    Response cashier =
        postAs("/orders/" + order + "/price", priceBody(V, "4.50", "0"), T, USER, "CASHIER", null);
    assertThat(cashier.getStatus(), is(403));
    Response negative =
        postAs("/orders/" + order + "/price", priceBody(V, "-1", "0"), T, USER, "MANAGER", null);
    assertThat(negative.getStatus(), is(400));
    Response unknown =
        postAs(
            "/orders/" + order + "/price",
            priceBody(Ids.newId().toString(), "4.50", "0"),
            T,
            USER,
            "MANAGER",
            null);
    assertThat(unknown.getStatus(), is(400));
    assertThat(unknown.readEntity(String.class), containsString("ORDER_PRICE_LINE"));
    Response empty =
        postAs("/orders/" + order + "/price", "{\"lines\":[]}", T, USER, "MANAGER", null);
    assertThat(empty.getStatus(), is(400));
    // Nothing moved.
    String body = get("/orders/" + order, T).readEntity(String.class);
    assertThat(body, containsString("\"status\":\"AWAITING_PRICE\""));
    // An ordinary PENDING order is not "awaiting a price", and an online order cannot be placed as
    // one.
    UUID pending = placeAt("POS", "INSTORE");
    Response notAwaiting =
        postAs(
            "/orders/" + pending + "/price", priceBody(V, "4.50", "0"), T, USER, "MANAGER", null);
    assertThat(notAwaiting.getStatus(), is(409));
    Response online =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\",\"awaitingPrice\":true,"
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":0}],\"currency\":\"USD\"}",
            T,
            Ids.newId().toString());
    assertThat(online.getStatus(), is(400));
    assertThat(online.readEntity(String.class), containsString("ORDER_AWAITING_PRICE_POS_ONLY"));
    // A manager may also decide not to price it at all.
    assertThat(
        post("/orders/" + order + "/cancel", "{\"reason\":\"not stocked\"}", T).getStatus(),
        is(200));
  }

  /**
   * The back-office void (09.13) is a management action. The shared filter refuses a cashier or a
   * storekeeper before the resource is reached, so the sale stands and nothing is restocked; a
   * manager's void is recorded against them, and the staff exception report reads it — which is
   * what turns the report's voids column from a permanent zero into a number.
   */
  @Test
  @DisplayName("A completed till sale is voided from the back office by a manager, by nobody below")
  void backOfficeVoidIsAManagementActionAndIsCountedAgainstWhoMadeIt() {
    UUID orderId = placeAt("POS", "INSTORE");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));
    assertThat(statusOf(orderId), is("FULFILLED"));

    String cashier = Ids.newId().toString();
    String keeper = Ids.newId().toString();
    String manager = Ids.newId().toString();
    String why = "{\"reason\":\"rang up twice\"}";

    // The till and the warehouse are refused before the sale is touched.
    for (String[] who : new String[][] {{cashier, "CASHIER"}, {keeper, "STOREKEEPER"}}) {
      Response refused = postAs("/orders/" + orderId + "/void", why, T, who[0], who[1], null);
      assertThat(who[1] + " must be refused", refused.getStatus(), is(403));
    }
    assertThat(statusOf(orderId), is("FULFILLED"));
    assertThat(outboxCount(orderId, "OrderVoided"), is(0L));

    // A manager voids it: the sale is VOIDED, the stock goes back, the reason is on the record.
    Response voided = postAs("/orders/" + orderId + "/void", why, T, manager, "MANAGER", null);
    assertThat(voided.getStatus(), is(200));
    assertThat(voided.readEntity(String.class), containsString("rang up twice"));
    assertThat(statusOf(orderId), is("VOIDED"));
    assertThat(outboxCount(orderId, "OrderVoided"), is(1L));

    // Not twice: the receipt keeps its number and the second void is refused.
    Response again = postAs("/orders/" + orderId + "/void", why, T, manager, "MANAGER", null);
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("ORDER_CANNOT_VOID"));
    assertThat(outboxCount(orderId, "OrderVoided"), is(1L));

    // And the exception report counts it against the manager, not against nobody.
    String body =
        getQuery("/admin/reports/exceptions", T, "groupBy", "ACTOR").readEntity(String.class);
    int at = body.indexOf("\"groupKey\":\"" + manager + "\"");
    assertThat("the manager has a row", at, greaterThanOrEqualTo(0));
    String row = body.substring(at, body.indexOf('}', at));
    assertThat(row, containsString("\"voids\":1"));
  }

  // ── the back-office void under abuse ──────────────────────────────────────

  private static long voidLogRows(UUID orderId) {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement("SELECT count(*) FROM \"order\".pos_void_log WHERE order_id = ?")) {
      ps.setObject(1, orderId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID paidTillSale() {
    UUID orderId = placeAt("POS", "INSTORE");
    orderService.handlePaymentCaptured(Ids.parse(T), orderId, Ids.newId(), new BigDecimal("20.00"));
    assertThat(statusOf(orderId), is("FULFILLED"));
    return orderId;
  }

  /**
   * A refused caller hammering the endpoint wears nothing down: twenty-five attempts by the cashier
   * who rang the sale are twenty-five refusals, the sale stands, nothing is restocked and the void
   * log has no row to show for it.
   */
  @Test
  @DisplayName("Hammering the void as a cashier never gets through")
  void hammeringTheVoidAsACashierNeverGetsThrough() {
    UUID orderId = paidTillSale();
    String cashier = Ids.newId().toString();
    for (int i = 0; i < 25; i++) {
      Response r =
          postAs(
              "/orders/" + orderId + "/void",
              "{\"reason\":\"attempt " + i + "\"}",
              T,
              cashier,
              "CASHIER",
              null);
      assertThat("attempt " + i, r.getStatus(), is(403));
      r.close();
    }
    assertThat(statusOf(orderId), is("FULFILLED"));
    assertThat(outboxCount(orderId, "OrderVoided"), is(0L));
    assertThat(voidLogRows(orderId), is(0L));
  }

  /**
   * Eight managers void the same sale at the same moment. The row lock decides: one void, one
   * restock event, one log row, and everyone else is told the sale is already voided. Without the
   * lock the stock would go back once per caller.
   */
  @Test
  @DisplayName("Concurrent voids of one sale produce exactly one void")
  void concurrentVoidsOfOneSaleProduceExactlyOneVoid() throws Exception {
    UUID orderId = paidTillSale();
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(callers);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        String manager = Ids.newId().toString();
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  Response r =
                      postAs(
                          "/orders/" + orderId + "/void",
                          "{\"reason\":\"race\"}",
                          T,
                          manager,
                          "MANAGER",
                          null);
                  int status = r.getStatus();
                  r.close();
                  return status;
                }));
      }
      start.countDown();
      for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 200).count(), is(1L));
    assertThat(
        statuses.toString(), statuses.stream().filter(s -> s == 409).count(), is(callers - 1L));
    assertThat(statusOf(orderId), is("VOIDED"));
    assertThat(outboxCount(orderId, "OrderVoided"), is(1L));
    assertThat(voidLogRows(orderId), is(1L));
  }

  /** The void is scoped like every other write: by tenant first, then by store (SJ-D48). */
  @Test
  @DisplayName("A void from another tenant or another store does not reach the sale")
  void voidIsScopedByTenantThenStore() {
    UUID orderId = paidTillSale();
    String why = "{\"reason\":\"not mine\"}";
    // Another tenant's manager holding the order id: not found, because tenant_id filters first.
    Response rival =
        postAs(
            "/orders/" + orderId + "/void",
            why,
            "01a090ae-611e-7014-8cd5-baf0862fa319",
            Ids.newId().toString(),
            "MANAGER",
            null);
    assertThat(rival.getStatus(), is(404));
    // Same tenant, a manager scoped to a different store: refused.
    Response elsewhere =
        target
            .path("/orders/" + orderId + "/void")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "MANAGER")
            .header("X-Store-Ids", Ids.newId().toString())
            .post(Entity.entity(why, MediaType.APPLICATION_JSON));
    assertThat(elsewhere.getStatus(), is(403));
    assertThat(statusOf(orderId), is("FULFILLED"));
    assertThat(outboxCount(orderId, "OrderVoided"), is(0L));
    assertThat(voidLogRows(orderId), is(0L));
  }

  /**
   * The reason is free text, and free text is bounded at the boundary: it goes onto two records and
   * a receipt, so a megabyte of it is refused before anything is written.
   */
  @Test
  @DisplayName("The void reason is required and bounded at 500 characters")
  void voidReasonIsRequiredAndBounded() {
    UUID orderId = placeAt("POS", "INSTORE");
    String manager = Ids.newId().toString();
    for (String bad :
        new String[] {"{}", "{\"reason\":\"   \"}", "{\"reason\":\"" + "x".repeat(501) + "\"}"}) {
      Response r = postAs("/orders/" + orderId + "/void", bad, T, manager, "MANAGER", null);
      assertThat(bad.length() > 40 ? "501 characters" : bad, r.getStatus(), is(400));
      r.close();
    }
    assertThat(statusOf(orderId), is("PENDING"));
    assertThat(voidLogRows(orderId), is(0L));
    Response atTheLimit =
        postAs(
            "/orders/" + orderId + "/void",
            "{\"reason\":\"" + "x".repeat(500) + "\"}",
            T,
            manager,
            "MANAGER",
            null);
    assertThat(atTheLimit.getStatus(), is(200));
    assertThat(voidLogRows(orderId), is(1L));
  }
}
