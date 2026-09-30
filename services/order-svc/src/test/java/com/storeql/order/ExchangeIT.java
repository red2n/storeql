package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.V_B;
import static com.storeql.order.support.ReturnsRig.V_C;
import static com.storeql.order.support.ReturnsRig.V_TAX;
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
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct exchanges (intent/return-controls.md): the old goods come back and the new sale is placed
 * on one transaction; the returned value pays the new basket, and only the difference moves. Tried
 * with the wrong caller, the wrong business, a retry and a refusal half way, beside the right ones.
 */
@HelidonTest
class ExchangeIT {

  private static final String T = "01a0a1c4-1111-7000-8000-000000000001";
  private static final String T_POLICY = "01a0a1c4-1111-7000-8000-000000000002";
  private static final String OTHER_T = "01a0a1c4-1111-7000-8000-000000000003";
  private static final String STORE = "01a0a1c4-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1c4-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c4-4444-7000-8000-000000000002";
  private static final String KEEPER = "01a0a1c4-4444-7000-8000-000000000003";
  private static final String CUSTOMER = "01a0a1c4-5555-7000-8000-000000000001";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T, "USD", "US")
        .with(T_POLICY, "USD", "US")
        .with(OTHER_T, "USD", "US");
    PRICING = ReturnsRig.pricing(new AtomicBoolean(false));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // Set here rather than assumed: the new sale is priced by the quote, which is where its VAT
    // comes from, and an earlier class in this JVM may have left the switch the other way.
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
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

  private static String lineOf(String variant, int qty, String condition) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + (condition == null ? "" : ",\"condition\":\"" + condition + "\"")
        + "}";
  }

  private static String newLine(String variant, int qty) {
    return lineOf(variant, qty, null);
  }

  private static String exchangeBody(String returned, String bought, String extra) {
    return "{\"reason\":\"wrong size\",\"returnItems\":["
        + returned
        + "],\"newItems\":["
        + bought
        + "]"
        + extra
        + "}";
  }

  private Response exchange(
      String order, String json, String tenant, String roles, String user, String key) {
    return rig().post("/orders/" + order + "/exchange", json, tenant, roles, user, key);
  }

  private long ordersOf(String tenant) {
    return rig().count("orders", "tenant_id='" + tenant + "'");
  }

  private long returnsOf(String tenant) {
    return rig().count("returns", "tenant_id='" + tenant + "'");
  }

  private static BigDecimal money(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue();
  }

  // ── the money ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A like-for-like exchange moves no money, and every service is told how")
  void likeForLikeMovesNoMoney() {
    String order = rig().sale(T, STORE, V_A, 2, CUSTOMER, MANAGER);

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_A, 1), ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("10.00")));
    assertThat(money(x, "dueFromCustomer").signum(), is(0));
    assertThat(money(x, "refundToCustomer").signum(), is(0));

    JsonObject ret = x.getJsonObject("return");
    JsonObject bought = x.getJsonObject("order");
    assertThat(ret.getString("refundMethod"), is("EXCHANGE"));
    assertThat(ret.getString("orderId"), is(order));
    assertThat(ret.getString("exchangeOrderId"), is(bought.getString("id")));
    assertThat(ret.getString("createdBy"), is(CASHIER));
    assertThat(bought.getString("status"), is("PENDING"));
    assertThat(bought.getString("channel"), is("POS"));
    assertThat(bought.getString("fulfilmentType"), is("INSTORE"));
    assertThat(bought.getString("storeId"), is(STORE));
    // The customer of the returned sale carries over to the new one.
    assertThat(bought.getString("customerId"), is(CUSTOMER));

    // The two name each other.
    assertThat(
        rig()
            .one(
                "SELECT exchanged_from_return_id FROM \"order\".orders WHERE id='"
                    + bought.getString("id")
                    + "'"),
        is(ret.getString("id")));

    JsonObject ev = rig().event(order, "OrderReturned");
    assertThat(ev.getString("refundMethod"), is("EXCHANGE"));
    assertThat(ev.getString("returnId"), is(ret.getString("id")));
    assertThat(ev.getString("exchangeOrderId"), is(bought.getString("id")));
    assertThat(money(ev, "exchangeAmount"), is(new BigDecimal("10.00")));
    assertThat(money(ev, "refundAmount"), is(new BigDecimal("10.00")));
    assertThat(ev.getString("currency"), is("USD"));
    assertThat(ev.getString("customerId"), is(CUSTOMER));
    assertThat(ev.getJsonArray("items").getJsonObject(0).getString("condition"), is("SEALED"));
    assertThat(rig().events(bought.getString("id"), "OrderPlaced"), is(1L));
  }

  @Test
  @DisplayName("VAT is part of what comes back: a like-for-like swap of a taxed item is even")
  void vatIsPartOfTheReturnedValue() {
    String order = rig().sale(T, STORE, V_TAX, 2, null, MANAGER);

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(lineOf(V_TAX, 1, "SEALED"), newLine(V_TAX, 1), ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    // Ten plus its two of VAT, against a new basket of the same: nothing to pay either way.
    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("12.00")));
    assertThat(money(x, "dueFromCustomer").signum(), is(0));
    assertThat(money(x, "refundToCustomer").signum(), is(0));
    assertThat(money(x.getJsonObject("return"), "refundAmount"), is(new BigDecimal("12.00")));
  }

  @Test
  @DisplayName("A dearer new basket charges only the difference")
  void dearerChargesTheDifference() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(lineOf(V_A, 1, "OPENED"), newLine(V_B, 1), ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("10.00")));
    assertThat(money(x, "dueFromCustomer"), is(new BigDecimal("15.00")));
    assertThat(money(x, "refundToCustomer").signum(), is(0));
    assertThat(money(x.getJsonObject("order"), "total"), is(new BigDecimal("25.00")));
    assertThat(
        money(rig().event(order, "OrderReturned"), "exchangeAmount"), is(new BigDecimal("10.00")));
  }

  @Test
  @DisplayName("A cheaper new basket refunds only the difference")
  void cheaperRefundsTheDifference() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(lineOf(V_A, 2, "SEALED"), newLine(V_C, 1), ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("4.00")));
    assertThat(money(x, "dueFromCustomer").signum(), is(0));
    assertThat(money(x, "refundToCustomer"), is(new BigDecimal("16.00")));
    JsonObject ev = rig().event(order, "OrderReturned");
    assertThat(money(ev, "refundAmount"), is(new BigDecimal("20.00")));
    assertThat(money(ev, "exchangeAmount"), is(new BigDecimal("4.00")));
  }

  // ── the policy ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Outside the policy a cashier is refused and nothing is written; a manager is named")
  void outsideThePolicyNeedsAManager() {
    assertThat(
        rig()
            .put(
                "/admin/return-policy",
                "{\"windowDays\":7,\"noReceiptAllowed\":false}",
                T_POLICY,
                "OWNER",
                MANAGER)
            .getStatus(),
        is(200));
    String order = rig().sale(T_POLICY, STORE, V_A, 2, null, MANAGER);
    rig().backdate(order, 10);
    long orders = ordersOf(T_POLICY);

    Response refused =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), ""),
            T_POLICY,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(refused.getStatus(), is(403));
    String text = refused.readEntity(String.class);
    assertThat(text, containsString("ORDER_RETURN_NEEDS_MANAGER"));
    assertThat(text, containsString("WINDOW"));
    assertThat(returnsOf(T_POLICY), is(0L));
    assertThat(ordersOf(T_POLICY), is(orders));
    assertThat(rig().events(order, "OrderReturned"), is(0L));

    JsonObject ok =
        data(
            exchange(
                order,
                exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), ""),
                T_POLICY,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
            201);
    assertThat(ok.getJsonObject("return").getString("approvedBy"), is(MANAGER));
    assertThat(ok.getJsonObject("return").getJsonArray("outsidePolicy").getString(0), is("WINDOW"));
    assertThat(ordersOf(T_POLICY), is(orders + 1));
  }

  // ── a retry, a refusal half way ───────────────────────────────────────────

  @Test
  @DisplayName("A retry with the same key answers with the first exchange and writes nothing")
  void aRetryAnswersWithTheFirst() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    String key = Ids.newId().toString();
    String body = exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), "");
    long orders = ordersOf(T);

    JsonObject first = data(exchange(order, body, T, "CASHIER", CASHIER, key), 201);
    JsonObject again = data(exchange(order, body, T, "CASHIER", CASHIER, key), 201);

    assertThat(
        again.getJsonObject("return").getString("id"),
        is(first.getJsonObject("return").getString("id")));
    assertThat(
        again.getJsonObject("order").getString("id"),
        is(first.getJsonObject("order").getString("id")));
    assertThat(money(again, "dueFromCustomer"), is(new BigDecimal("15.00")));
    assertThat(ordersOf(T), is(orders + 1));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(1L));
    assertThat(rig().events(order, "OrderReturned"), is(1L));

    // The same key for a different sale is not an answer to it.
    String other = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    assertThat(exchange(other, body, T, "CASHIER", CASHIER, key).getStatus(), is(409));
  }

  @Test
  @DisplayName("A refusal after the return is worked out leaves no new sale behind")
  void aRefusalLeavesNothing() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long orders = ordersOf(T);
    long returns = returnsOf(T);

    // Three come back of two sold: refused when the return is written, on the sale's transaction.
    Response tooMany =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 3, "SEALED"), newLine(V_B, 1), ""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(tooMany.getStatus(), is(409));
    assertThat(ordersOf(T), is(orders));
    assertThat(returnsOf(T), is(returns));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }

  @Test
  @DisplayName("A bad request is refused before anything is written")
  void badRequestsAreRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long orders = ordersOf(T);
    long returns = returnsOf(T);
    String good = exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), "");

    // No key.
    Response noKey = exchange(order, good, T, "CASHIER", CASHIER, null);
    assertThat(noKey.getStatus(), is(400));
    assertThat(noKey.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    // No condition.
    Response noCondition =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, null), newLine(V_B, 1), ""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(noCondition.getStatus(), is(400));
    assertThat(
        noCondition.readEntity(String.class), containsString("ORDER_RETURN_CONDITION_REQUIRED"));
    // Nothing to buy.
    Response nothingBought =
        exchange(
            order,
            "{\"reason\":\"r\",\"returnItems\":[" + lineOf(V_A, 1, "SEALED") + "],\"newItems\":[]}",
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(nothingBought.getStatus(), is(400));
    assertThat(
        nothingBought.readEntity(String.class), containsString("ORDER_EXCHANGE_NO_NEW_ITEMS"));
    // Somebody who cannot ring a sale up cannot exchange.
    assertThat(
        exchange(order, good, T, "STOREKEEPER", KEEPER, Ids.newId().toString()).getStatus(),
        is(403));
    assertThat(
        exchange(order, good, T, "CUSTOMER", CUSTOMER, Ids.newId().toString()).getStatus(),
        is(403));
    // A sale that never left the store cannot be exchanged.
    Response unpaid =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":["
                    + newLine(V_A, 1)
                    + "]}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString());
    String pending = data(unpaid, 201).getString("id");
    long before = ordersOf(T);
    assertThat(
        exchange(pending, good, T, "CASHIER", CASHIER, Ids.newId().toString()).getStatus(),
        is(409));
    assertThat(ordersOf(T), is(before));
    assertThat(before, is(orders + 1));
    assertThat(rig().events(pending, "OrderReturned"), is(0L));
    assertThat(returnsOf(T), is(returns));
  }

  // ── another business ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business's staff of every role cannot exchange our sale, and nothing moves")
  void anotherBusinessCannotExchange() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long ours = ordersOf(T);
    long theirs = ordersOf(OTHER_T);
    String body = exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), "");

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Response r = exchange(order, body, OTHER_T, role, CASHIER, Ids.newId().toString());
      assertThat(role, r.getStatus() == 404 || r.getStatus() == 403, is(true));
    }
    // Even naming their own store, as a role that may sell, it is our sale they cannot find.
    Response asManager = exchange(order, body, OTHER_T, "MANAGER", MANAGER, Ids.newId().toString());
    assertThat(asManager.getStatus(), is(404));
    assertThat(asManager.readEntity(String.class), containsString("ORDER_NOT_FOUND"));

    assertThat(ordersOf(T), is(ours));
    assertThat(ordersOf(OTHER_T), is(theirs));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(0L));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }
}
