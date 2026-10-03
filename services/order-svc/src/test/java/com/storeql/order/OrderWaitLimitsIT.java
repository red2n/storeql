package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The waits a business sets on an order, one row for both: how long an unpaid order is held
 * (fulfilment-overrides slice 2) and how long an order waits for a price (unit-pricing slice 5).
 */
@HelidonTest
class OrderWaitLimitsIT {

  private static final String T = "01a0a1c6-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c6-1111-7000-8000-000000000003";
  private static final String STORE = "01a0a1c6-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c6-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1c6-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c6-4444-7000-8000-000000000002";
  private static final String SHOPPER = "01a0a1c6-4444-7000-8000-000000000009";
  private static final String[] ROLES = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER"};

  private static final String T_FRESH = "01a0a1c6-1111-7000-8000-0000000000f1";
  private static final String T_FRESH2 = "01a0a1c6-1111-7000-8000-0000000000f2";
  private static final String T_OWN = "01a0a1c6-1111-7000-8000-0000000000f3";
  private static final String T_DEF = "01a0a1c6-1111-7000-8000-0000000000f4";
  private static final String T_EXP = "01a0a1c6-1111-7000-8000-0000000000f5";
  private static final String T_WAIT = "01a0a1c6-1111-7000-8000-0000000000f6";
  private static final String T_PRICED = "01a0a1c6-1111-7000-8000-0000000000f7";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T, "USD", "US")
        .with(OTHER_T, "USD", "US")
        .with(T_FRESH, "USD", "US")
        .with(T_FRESH2, "USD", "US")
        .with(T_OWN, "USD", "US")
        .with(T_DEF, "USD", "US")
        .with(T_EXP, "USD", "US")
        .with(T_WAIT, "USD", "US")
        .with(T_PRICED, "USD", "US");
    PRICING = ReturnsRig.pricing(new AtomicBoolean(false));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
    System.setProperty("storeql.order.pending-sweeper.enabled", "false");
    System.setProperty("storeql.order.awaiting-price-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.order.pricing.enforce");
    PRICING.close();
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  private static final String PENDING = "/admin/orders/settings/pending-limit";
  private static final String PRICE = "/admin/orders/settings/price-wait";

  private Response put(String path, String json, String tenant, String role, String stores) {
    var b = rig().as(path, tenant, role, MANAGER, stores);
    return b.put(
        jakarta.ws.rs.client.Entity.entity(json, jakarta.ws.rs.core.MediaType.APPLICATION_JSON));
  }

  private JsonObject pendingOf(String tenant) {
    return data(rig().get(PENDING, tenant, "OWNER", MANAGER), 200);
  }

  private JsonObject place(String tenant, boolean awaitingPrice) {
    String body =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":[{\"variantId\":\""
            + V_A
            + "\",\"qty\":1}]"
            + (awaitingPrice ? ",\"awaitingPrice\":true" : "")
            + "}";
    return data(
        rig().post("/orders", body, tenant, "CASHIER", CASHIER, Ids.newId().toString()), 201);
  }

  /** One order as the staff list shows it. */
  private JsonObject listed(String tenant, String orderId) {
    Response r = rig().get("/orders", tenant, "OWNER", MANAGER);
    assertThat(r.getStatus(), is(200));
    for (jakarta.json.JsonValue v :
        com.storeql.test.Envelopes.parse(r.readEntity(String.class)).getJsonArray("data")) {
      if (orderId.equals(v.asJsonObject().getString("id"))) return v.asJsonObject();
    }
    throw new AssertionError("order " + orderId + " is not in the list");
  }

  private String status(String order) {
    return rig().one("SELECT status FROM \"order\".orders WHERE id='" + order + "'");
  }

  private void age(String order, String interval) {
    com.storeql.test.Envelopes.exec(
        PG,
        "UPDATE \"order\".orders SET created_at = now() - interval '"
            + interval
            + "', updated_at = now() - interval '"
            + interval
            + "' WHERE id='"
            + order
            + "'");
  }

  @Test
  @DisplayName(
      "Every limit is off until set; a limit is a whole number and the cancel is not before the flag")
  void offUntilSetAndValidated() {
    JsonObject none = pendingOf(T_FRESH);
    assertThat(none.getBoolean("usingDefault"), is(true));
    assertThat(none.containsKey("pendingLimitHours"), is(false));
    assertThat(none.getInt("effectiveHours"), is(24));
    JsonObject wait = data(rig().get(PRICE, T_FRESH, "OWNER", MANAGER), 200);
    assertThat(wait.containsKey("flagMinutes"), is(false));
    assertThat(wait.containsKey("cancelMinutes"), is(false));

    for (String bad : new String[] {"0", "-1"}) {
      Response r = put(PENDING, "{\"pendingLimitHours\":" + bad + "}", T_FRESH, "OWNER", null);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(r.readEntity(String.class), containsString("ORDER_PENDING_LIMIT_INVALID"));
    }
    for (String bad :
        new String[] {
          "{\"flagMinutes\":60,\"cancelMinutes\":30}",
          "{\"flagMinutes\":0}",
          "{\"cancelMinutes\":-5}"
        }) {
      Response r = put(PRICE, bad, T_FRESH, "OWNER", null);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(r.readEntity(String.class), containsString("ORDER_PRICE_WAIT_INVALID"));
    }
    assertThat(pendingOf(T_FRESH).getBoolean("usingDefault"), is(true));

    // One row: setting the price wait leaves the unpaid limit as it was, and the reverse.
    assertThat(
        put(PENDING, "{\"pendingLimitHours\":6}", T_FRESH, "OWNER", null).getStatus(), is(200));
    assertThat(
        put(PRICE, "{\"flagMinutes\":30,\"cancelMinutes\":90}", T_FRESH, "OWNER", null).getStatus(),
        is(200));
    assertThat(pendingOf(T_FRESH).getInt("pendingLimitHours"), is(6));
    assertThat(put(PENDING, "{}", T_FRESH, "OWNER", null).getStatus(), is(200));
    JsonObject after = data(rig().get(PRICE, T_FRESH, "OWNER", MANAGER), 200);
    assertThat(after.getInt("flagMinutes"), is(30));
    assertThat(after.getInt("cancelMinutes"), is(90));
    assertThat(pendingOf(T_FRESH).getBoolean("usingDefault"), is(true));
    assertThat(rig().count("order_settings", "tenant_id='" + T_FRESH + "'"), is(1L));
  }

  @Test
  @DisplayName(
      "Management reads them; only a caller held to no store changes them; others are refused")
  void whoMayReadAndChange() {
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, rig().get(PENDING, T, role, CASHIER).getStatus(), is(403));
      assertThat(
          role, put(PENDING, "{\"pendingLimitHours\":1}", T, role, null).getStatus(), is(403));
      assertThat(role, put(PRICE, "{\"flagMinutes\":1}", T, role, null).getStatus(), is(403));
    }
    // A manager held to a store reads, and is refused a change that belongs to the whole business.
    assertThat(rig().getHeld(PENDING, T, "MANAGER", MANAGER, STORE).getStatus(), is(200));
    Response held = put(PENDING, "{\"pendingLimitHours\":1}", T, "MANAGER", STORE);
    assertThat(held.getStatus(), is(403));
    assertThat(held.readEntity(String.class), containsString("BUSINESS_WIDE_ONLY"));
    assertThat(put(PRICE, "{\"flagMinutes\":1}", T, "MANAGER", STORE).getStatus(), is(403));
    assertThat(
        rig()
            .count(
                "order_settings",
                "tenant_id='" + T + "' AND (pending_limit_hours=1 OR price_wait_flag_minutes=1)"),
        is(0L));
  }

  @Test
  @DisplayName("Another business's limits are its own: setting theirs moves none of ours")
  void limitsAreNotShared() {
    assertThat(
        put(PENDING, "{\"pendingLimitHours\":2}", OTHER_T, "OWNER", null).getStatus(), is(200));
    assertThat(
        put(PRICE, "{\"flagMinutes\":5,\"cancelMinutes\":10}", OTHER_T, "OWNER", null).getStatus(),
        is(200));
    assertThat(pendingOf(T_FRESH2).getBoolean("usingDefault"), is(true));
    assertThat(pendingOf(OTHER_T).getInt("pendingLimitHours"), is(2));
    assertThat(rig().count("order_settings", "tenant_id='" + T_FRESH2 + "'"), is(0L));
  }

  @Test
  @DisplayName(
      "The business's own unpaid limit cancels an unpaid order after its hours; unset uses the default")
  void ownLimitApplies() {
    put(PENDING, "{\"pendingLimitHours\":1}", T_OWN, "OWNER", null);
    JsonObject mine = place(T_OWN, false);
    JsonObject young = place(T_OWN, false);
    JsonObject theirs = place(T_DEF, false);
    age(mine.getString("id"), "90 minutes");
    age(theirs.getString("id"), "90 minutes");

    orderService.sweepExpiredPendingOrders(24, 200);

    assertThat(status(mine.getString("id")), is("CANCELLED"));
    // Not yet an hour old, and a business with no limit keeps the platform's 24 hours.
    assertThat(status(young.getString("id")), is("PENDING"));
    assertThat(status(theirs.getString("id")), is("PENDING"));
    age(theirs.getString("id"), "25 hours");
    orderService.sweepExpiredPendingOrders(24, 200);
    assertThat(status(theirs.getString("id")), is("CANCELLED"));
  }

  @Test
  @DisplayName("A PENDING order shows when it lapses; a paid one does not")
  void expiresAtIsShown() {
    put(PENDING, "{\"pendingLimitHours\":6}", T_EXP, "OWNER", null);
    JsonObject order = place(T_EXP, false);
    assertThat(order.containsKey("expiresAt"), is(true));
    java.time.Instant updated = java.time.Instant.parse(order.getString("updatedAt"));
    java.time.Instant expires = java.time.Instant.parse(order.getString("expiresAt"));
    // The placement answer is built before the insert, so its updatedAt differs from the stored
    // one by the write's own latency: the deadline is six hours on, to the second.
    assertThat(
        Math.abs(
            java.time.Duration.between(updated.plus(java.time.Duration.ofHours(6)), expires)
                .toMillis()),
        org.hamcrest.Matchers.lessThan(1000L));
    JsonObject read =
        data(rig().get("/orders/" + order.getString("id"), T_EXP, "OWNER", MANAGER), 200);
    assertThat(
        Math.abs(
            java.time.Duration.between(
                    java.time.Instant.parse(read.getString("expiresAt")), expires)
                .toMillis()),
        org.hamcrest.Matchers.lessThan(1000L));

    // The list says the same, so a client need not read each waiting order again.
    assertThat(
        listed(T_EXP, order.getString("id")).getString("expiresAt"),
        is(read.getString("expiresAt")));

    orderService.handlePaymentCaptured(
        Ids.parse(T_EXP),
        Ids.parse(order.getString("id")),
        Ids.newId(),
        order.getJsonNumber("total").bigDecimalValue());
    JsonObject paid =
        data(rig().get("/orders/" + order.getString("id"), T_EXP, "OWNER", MANAGER), 200);
    assertThat(paid.containsKey("expiresAt"), is(false));
    assertThat(listed(T_EXP, order.getString("id")).containsKey("expiresAt"), is(false));

    // With no limit set the platform's default applies.
    JsonObject plain = place(T_DEF, false);
    assertThat(
        Math.abs(
            java.time.Duration.between(
                    java.time.Instant.parse(plain.getString("updatedAt"))
                        .plus(java.time.Duration.ofHours(24)),
                    java.time.Instant.parse(plain.getString("expiresAt")))
                .toMillis()),
        org.hamcrest.Matchers.lessThan(1000L));
  }

  @Test
  @DisplayName(
      "An order waiting for a price is flagged once at the first limit and cancelled at the second")
  void awaitingPriceIsFlaggedThenCancelled() {
    put(PRICE, "{\"flagMinutes\":30,\"cancelMinutes\":90}", T_WAIT, "OWNER", null);
    JsonObject a = place(T_WAIT, true);
    JsonObject young = place(T_WAIT, true);
    JsonObject unlimited = place(T_DEF, true);
    String id = a.getString("id");
    assertThat(status(id), is("AWAITING_PRICE"));
    age(id, "40 minutes");
    age(unlimited.getString("id"), "500 minutes");

    assertThat(orderService.sweepAwaitingPriceOrders(200), is(1));
    assertThat(status(id), is("AWAITING_PRICE"));
    assertThat(rig().events(id, "OrderPriceOverdue"), is(1L));
    JsonObject overdue = rig().event(id, "OrderPriceOverdue");
    assertThat(overdue.getString("orderId"), is(id));
    assertThat(overdue.getString("storeId"), is(STORE));
    assertThat(overdue.containsKey("eventId"), is(true));
    assertThat(overdue.containsKey("awaitingSince"), is(true));

    // A second sweep at the same limit flags nothing more.
    assertThat(orderService.sweepAwaitingPriceOrders(200), is(0));
    assertThat(rig().events(id, "OrderPriceOverdue"), is(1L));

    // Past the second limit it is cancelled, through the ordinary cancel event.
    age(id, "100 minutes");
    assertThat(orderService.sweepAwaitingPriceOrders(200), is(1));
    assertThat(status(id), is("CANCELLED"));
    assertThat(rig().events(id, "OrderCancelled"), is(1L));
    assertThat(rig().event(id, "OrderCancelled").getString("reason"), is("PRICE_WAIT_EXPIRED"));
    assertThat(
        rig()
            .one(
                "SELECT reason FROM \"order\".order_status_history WHERE order_id='"
                    + id
                    + "' AND to_status='CANCELLED'"),
        is("PRICE_WAIT_EXPIRED"));

    // A young order, and a business with no limit however old, are left waiting.
    assertThat(status(young.getString("id")), is("AWAITING_PRICE"));
    assertThat(status(unlimited.getString("id")), is("AWAITING_PRICE"));
    assertThat(rig().events(unlimited.getString("id"), "OrderPriceOverdue"), is(0L));
  }

  @Test
  @DisplayName("An order a manager prices before the limit is not swept")
  void aPricedOrderIsNotSwept() {
    put(PRICE, "{\"flagMinutes\":30,\"cancelMinutes\":90}", T_PRICED, "OWNER", null);
    JsonObject o = place(T_PRICED, true);
    String id = o.getString("id");
    age(id, "200 minutes");
    // The manager prices it at the same moment the sweeper would reach it.
    com.storeql.test.Envelopes.exec(
        PG, "UPDATE \"order\".orders SET status='PENDING' WHERE id='" + id + "'");
    assertThat(orderService.sweepAwaitingPriceOrders(200), is(0));
    assertThat(status(id), is("PENDING"));
    assertThat(rig().events(id, "OrderPriceOverdue"), is(0L));
    assertThat(rig().events(id, "OrderCancelled"), is(0L));
  }
}
