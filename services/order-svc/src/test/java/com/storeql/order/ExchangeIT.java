package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.STICKER_A;
import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.V_B;
import static com.storeql.order.support.ReturnsRig.V_C;
import static com.storeql.order.support.ReturnsRig.V_TAX;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
  private static final String T_SWITCHED = "01a0a1c4-1111-7000-8000-000000000004";
  private static final String STORE = "01a0a1c4-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1c4-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c4-4444-7000-8000-000000000002";
  private static final String KEEPER = "01a0a1c4-4444-7000-8000-000000000003";
  private static final String CUSTOMER = "01a0a1c4-5555-7000-8000-000000000001";

  /** Another store of {@link #T}'s, for a cashier held there and not at {@link #STORE}. */
  private static final String OTHER_STORE = "01a0a1c4-2222-7000-8000-00000000000b";

  // The register at T's store: one scale certified for trade, one whose re-verification is overdue.
  private static final String CERTIFIED = "01a0a1c4-6666-7000-8000-000000000001";
  private static final String OVERDUE = "01a0a1c4-6666-7000-8000-000000000002";

  /** The lot of {@code V_A} T has recalled; every other lot of it sells. */
  private static final String RECALLED_LOT = "L42";

  /**
   * Each business's open recalls, as inventory-svc's active list gives them: T has recalled one lot
   * of {@code V_A}; nobody else has recalled anything.
   */
  private static final Map<String, List<String>> RECALLS =
      Map.of(
          T,
          List.of(
              "{\"recallId\":\"01a0a1c4-7777-7000-8000-000000000001\",\"reference\":\"R-2026-042\","
                  + "\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\",\"customerNotice\":null,"
                  + "\"variantId\":\""
                  + V_A
                  + "\",\"batchNo\":\""
                  + RECALLED_LOT
                  + "\",\"expiryFrom\":null,\"expiryTo\":null}"));

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;
  private static final JsonStub INVENTORY;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T, "USD", "US")
        .with(T_POLICY, "USD", "US")
        .with(OTHER_T, "USD", "US")
        .with(T_SWITCHED, "USD", "US")
        .withInstrument(T, STORE, CERTIFIED, "Deli 1", "CERTIFIED")
        .withInstrument(T, STORE, OVERDUE, "Deli 2", "OVERDUE");
    PRICING = ReturnsRig.pricing(new AtomicBoolean(false));
    // Stop-sale on the new basket: inventory-svc's open recalls, each business its own.
    INVENTORY = JsonStub.start("inventory-svc");
    INVENTORY.on(
        "GET",
        "/admin/inventory/recalls/active",
        call ->
            JsonStub.Answer.ok(
                "["
                    + String.join(
                        ",",
                        RECALLS.getOrDefault(
                            call.tenantId() == null ? "" : call.tenantId(), List.of()))
                    + "]"));
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
    INVENTORY.close();
    PG.stop();
  }

  @BeforeEach
  void forget() {
    INVENTORY.reset();
    PRICING.reset();
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

  /** A new item as the till's scanner sends it: a label's weight written as read. */
  private static String newLine(String variant, String qty) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + "}";
  }

  /**
   * A new item as the till's scanner sends it with what its pack said: the fields named, as given.
   */
  private static String scannedLine(String variant, String... fields) {
    StringBuilder b = new StringBuilder("{\"variantId\":\"" + variant + "\",\"qty\":1");
    for (int i = 0; i < fields.length; i += 2) {
      b.append(",\"").append(fields[i]).append("\":\"").append(fields[i + 1]).append('"');
    }
    return b.append('}').toString();
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
  @DisplayName("An exchange at a till names the drawer on OrderReturned; a bad id is a 400")
  void anExchangeAtATillNamesTheDrawer() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    String drawer = Ids.newId().toString();
    long before = returnsOf(T);

    Response bad =
        exchange(
            order,
            exchangeBody(
                lineOf(V_A, 1, "SEALED"), newLine(V_C, 1), ",\"tillSessionId\":\"not-an-id\""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(bad.getStatus(), is(400));
    assertThat("nothing was written", returnsOf(T), is(before));

    data(
        exchange(
            order,
            exchangeBody(
                lineOf(V_A, 1, "SEALED"), newLine(V_C, 1), ",\"tillSessionId\":\"" + drawer + "\""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString()),
        201);

    JsonObject ev = rig().event(order, "OrderReturned");
    assertThat(ev.getString("refundMethod"), is("EXCHANGE"));
    assertThat(ev.getString("tillSessionId"), is(drawer));
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
  @DisplayName("A sale placed under the exchange's derived key is refused, and no return is made")
  void aSaleSquattingTheExchangeKeyIsRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    java.util.UUID key = Ids.newId();
    // The derived key is a plain hash, so anybody can place a till sale under it first.
    Response squat =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":["
                    + newLine(V_B, 1)
                    + "]}",
                T,
                "MANAGER",
                MANAGER,
                Ids.derived(key, "exchange-order").toString());
    data(squat, 201);
    long returns = returnsOf(T);

    Response r =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), ""),
            T,
            "CASHIER",
            CASHIER,
            key.toString());
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(409));
    assertThat(text, containsString("ORDER_EXCHANGE_INCOMPLETE"));
    assertThat(returnsOf(T), is(returns));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(0L));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
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
  @DisplayName("A business that now trades in another currency cannot exchange an old sale")
  void aSaleInAnotherCurrencyThanTheBusinessNowTradesInIsRefused() {
    String order = rig().sale(T_SWITCHED, STORE, V_A, 2, null, MANAGER);
    // The sale stands in dollars; the business has since moved to euros (the projection of the
    // tenant's own currency is what the new sale is placed in).
    com.storeql.test.Envelopes.exec(
        PG,
        "INSERT INTO \"order\".tenant_status (tenant_id, status, currency)"
            + " VALUES ('"
            + T_SWITCHED
            + "', 'ACTIVE', 'EUR')"
            + " ON CONFLICT (tenant_id) DO UPDATE SET currency = 'EUR'");
    long orders = ordersOf(T_SWITCHED);
    long returns = returnsOf(T_SWITCHED);

    Response r =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_A, 1), ""),
            T_SWITCHED,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(409));
    assertThat(text, containsString("ORDER_EXCHANGE_CURRENCY_MISMATCH"));
    // The new sale and the return share one transaction: neither is left behind.
    assertThat(ordersOf(T_SWITCHED), is(orders));
    assertThat(returnsOf(T_SWITCHED), is(returns));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }

  // ── a label's weight on the new basket ────────────────────────────────────

  @Test
  @DisplayName(
      "A label's weight scanned for the new basket is sold at the gram below, as on a sale")
  void aLabelWeightOnTheNewBasketIsCountedAtTheGramBelow() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    // The till scans a pack labelled 0.37512 kg (GS1 AI 3105) into the new basket and posts the
    // label's reading as it does on a sale (returns_screen.dart, scanBarcode).
    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_A, "0.37512"), ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    JsonObject bought = x.getJsonObject("order");
    // 0.375 kg at 10.00 is 3.75, charged, kept and held at that one figure; the 10.00 coming back
    // pays it and the rest goes back.
    assertThat(money(bought, "total"), is(new BigDecimal("3.75")));
    assertThat(
        rig()
            .one(
                "SELECT qty::text FROM \"order\".order_items WHERE order_id='"
                    + bought.getString("id")
                    + "'"),
        is("0.375"));
    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("3.75")));
    assertThat(money(x, "dueFromCustomer").signum(), is(0));
    assertThat(money(x, "refundToCustomer"), is(new BigDecimal("6.25")));
    // The till's receipt for the new sale shows the line at the figure it is charged at.
    assertThat(
        money(bought.getJsonArray("items").getJsonObject(0), "qty"), is(new BigDecimal("0.375")));
  }

  @Test
  @DisplayName(
      "A new item finer than any reading is refused by its own name, and nothing is written")
  void aNewItemFinerThanAReadingIsRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long orders = ordersOf(T);
    long returns = returnsOf(T);

    for (String qty : new String[] {"0.3755123", "0.0004"}) {
      Response r =
          exchange(
              order,
              exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_A, qty), ""),
              T,
              "CASHIER",
              CASHIER,
              Ids.newId().toString());
      String text = r.readEntity(String.class);
      assertThat(text, r.getStatus(), is(400));
      assertThat(text, containsString("VALIDATION_FAILED"));
      assertThat(text, containsString("newItems[0].qty"));
    }
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
    // Nothing coming back.
    Response nothingReturned =
        exchange(
            order,
            "{\"reason\":\"r\",\"returnItems\":[],\"newItems\":[" + newLine(V_B, 1) + "]}",
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(nothingReturned.getStatus(), is(400));
    assertThat(nothingReturned.readEntity(String.class), containsString("ORDER_RETURN_NO_ITEMS"));
    assertThat(ordersOf(T), is(orders));
    assertThat(returnsOf(T), is(returns));
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

  // ── the new basket is a till sale's: recalls, stickers, scales ───────────

  @Test
  @DisplayName(
      "A recalled lot scanned into the new basket is refused as on a sale; nothing written")
  void aRecalledLotOnTheNewBasketIsRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long orders = ordersOf(T);
    long returns = returnsOf(T);

    for (String lot : new String[] {RECALLED_LOT, " l42 "}) {
      Response r =
          exchange(
              order,
              exchangeBody(
                  lineOf(V_A, 1, "SEALED"),
                  scannedLine(V_A, "batchNo", lot, "expiry", "2026-12-31"),
                  ""),
              T,
              "CASHIER",
              CASHIER,
              Ids.newId().toString());
      String text = r.readEntity(String.class);
      assertThat(text, r.getStatus(), is(409));
      // The same refusal, by the same code, as a till sale of that pack gets.
      assertThat(text, containsString("ORDER_LINE_RECALLED"));
      assertThat(text, containsString("lot L42"));
    }
    // Neither the return nor the new sale: the customer keeps the goods they brought back.
    assertThat(ordersOf(T), is(orders));
    assertThat(returnsOf(T), is(returns));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
    assertThat(
        "the business's own recalls were asked, and only those",
        INVENTORY.calls().stream()
            .filter(c -> "/admin/inventory/recalls/active".equals(c.path()))
            .allMatch(c -> T.equals(c.tenantId())),
        is(true));
  }

  @Test
  @DisplayName("Another lot of the recalled product is exchanged as any pack is")
  void anotherLotOfTheRecalledProductIsExchanged() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(
                    lineOf(V_A, 1, "SEALED"),
                    scannedLine(V_A, "batchNo", "L43", "expiry", "2026-12-31"),
                    ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("10.00")));
    assertThat(money(x, "dueFromCustomer").signum(), is(0));
    // A pack that declared no lot is the cashier's to read, as on a sale: not refused here.
    String other = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    data(
        exchange(
            other,
            exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_A, 1), ""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString()),
        201);
  }

  @Test
  @DisplayName(
      "Two packs of one product in the new basket are judged each by its own lot, never merged")
  void twoPacksOfOneProductAreJudgedEachByItsOwnLot() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long orders = ordersOf(T);
    long returns = returnsOf(T);

    // A clean lot and the recalled one of the same product: the second is refused by its place,
    // the first does not carry it through, and nothing is written.
    Response mixed =
        exchange(
            order,
            exchangeBody(
                lineOf(V_A, 1, "SEALED"),
                scannedLine(V_A, "batchNo", "L43", "expiry", "2026-12-31")
                    + ","
                    + scannedLine(V_A, "batchNo", RECALLED_LOT, "expiry", "2026-12-31"),
                ""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    String text = mixed.readEntity(String.class);
    assertThat(text, mixed.getStatus(), is(409));
    assertThat(text, containsString("ORDER_LINE_RECALLED"));
    assertThat(text, containsString("item 2 is under"));
    assertThat(text, containsString("lot L42"));
    assertThat(text, not(containsString("item 1 is under")));
    assertThat(ordersOf(T), is(orders));
    assertThat(returnsOf(T), is(returns));
    assertThat(rig().events(order, "OrderReturned"), is(0L));

    // Two clean lots of the same product: two lines on the new sale, as sent.
    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(
                    lineOf(V_A, 1, "SEALED"),
                    scannedLine(V_A, "batchNo", "L43", "expiry", "2026-12-31")
                        + ","
                        + scannedLine(V_A, "batchNo", "L44", "expiry", "2027-01-31"),
                    ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);
    var lines = x.getJsonObject("order").getJsonArray("items");
    assertThat(lines.size(), is(2));
    for (int i = 0; i < lines.size(); i++) {
      assertThat(lines.getJsonObject(i).getString("variantId"), is(V_A));
    }
    assertThat(
        rig().count("order_items", "order_id='" + x.getJsonObject("order").getString("id") + "'"),
        is(2L));
  }

  @Test
  @DisplayName("A reduced-price sticker on the new basket prices the line at the sticker")
  void aStickerOnTheNewBasketIsHonoured() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(
                    lineOf(V_A, 1, "SEALED"), scannedLine(V_A, "markdownId", STICKER_A), ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);

    JsonObject bought = x.getJsonObject("order");
    JsonObject line = bought.getJsonArray("items").getJsonObject(0);
    // Six at the sticker, not ten: the ten coming back pays it and four go back.
    assertThat(money(bought, "total"), is(new BigDecimal("6.00")));
    assertThat(line.getString("markdownId"), is(STICKER_A));
    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("6.00")));
    assertThat(money(x, "refundToCustomer"), is(new BigDecimal("4.00")));
    assertThat(
        rig()
            .one(
                "SELECT markdown_id::text FROM \"order\".order_items WHERE order_id='"
                    + bought.getString("id")
                    + "'"),
        is(STICKER_A));
    // The sticker was asked of pricing-svc, and counts down once the new sale stands.
    assertThat(
        PRICING.calls().stream()
            .anyMatch(c -> "/prices/quote".equals(c.path()) && c.body().contains(STICKER_A)),
        is(true));
    assertThat(
        PRICING.calls().stream()
            .filter(c -> "/prices/markdown-redemptions".equals(c.path()))
            .filter(c -> c.body().contains(bought.getString("id")))
            .count(),
        is(1L));
  }

  @Test
  @DisplayName("A weighed new item names its scale, judged as on a sale; nothing written if unfit")
  void aWeighedNewItemsScaleIsJudged() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long orders = ordersOf(T);
    long returns = returnsOf(T);

    Response overdue =
        exchange(
            order,
            exchangeBody(
                lineOf(V_A, 1, "SEALED"), scannedLine(V_A, "weighingInstrumentId", OVERDUE), ""),
            T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    String text = overdue.readEntity(String.class);
    assertThat(text, overdue.getStatus(), is(409));
    assertThat(text, containsString("ORDER_SCALE_NOT_CERTIFIED"));
    assertThat(ordersOf(T), is(orders));
    assertThat(returnsOf(T), is(returns));
    assertThat(rig().events(order, "OrderReturned"), is(0L));

    JsonObject x =
        data(
            exchange(
                order,
                exchangeBody(
                    lineOf(V_A, 1, "SEALED"),
                    scannedLine(V_A, "weighingInstrumentId", CERTIFIED),
                    ""),
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString()),
            201);
    assertThat(
        x.getJsonObject("order")
            .getJsonArray("items")
            .getJsonObject(0)
            .getString("weighingInstrumentId"),
        is(CERTIFIED));
  }

  // ── the order of refusals: the request, the sale, the store, the key ──────

  @Test
  @DisplayName("A bad body is refused before the sale is looked for or the store judged")
  void aBadBodyIsRefusedBeforeTheSaleOrTheStore() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    long ours = ordersOf(T);
    long returns = returnsOf(T);
    String tooFine = exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_A, "0.3755123"), "");
    String badExpiry =
        exchangeBody(
            lineOf(V_A, 1, "SEALED"), scannedLine(V_A, "batchNo", "L43", "expiry", "31/12/26"), "");

    // Another business naming our sale: what is wrong with its body is what it is told, not that
    // a sale it could never reach does not exist.
    Response elsewhere =
        exchange(order, tooFine, OTHER_T, "MANAGER", MANAGER, Ids.newId().toString());
    String text = elsewhere.readEntity(String.class);
    assertThat(text, elsewhere.getStatus(), is(400));
    assertThat(text, containsString("VALIDATION_FAILED"));
    assertThat(text, containsString("newItems[0].qty"));
    // A sale of no business at all: still the body first.
    Response nowhere =
        exchange(Ids.newId().toString(), badExpiry, T, "CASHIER", CASHIER, Ids.newId().toString());
    text = nowhere.readEntity(String.class);
    assertThat(text, nowhere.getStatus(), is(400));
    assertThat(text, containsString("ORDER_LINE_EXPIRY_INVALID"));
    assertThat(text, containsString("newItems[0].expiry"));
    // A cashier held to another store of ours: the body first, then the store.
    Response heldElsewhere = exchangeHeld(order, tooFine, OTHER_STORE);
    text = heldElsewhere.readEntity(String.class);
    assertThat(text, heldElsewhere.getStatus(), is(400));
    assertThat(text, containsString("newItems[0].qty"));
    Response good =
        exchangeHeld(
            order, exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), ""), OTHER_STORE);
    text = good.readEntity(String.class);
    assertThat(text, good.getStatus(), is(403));
    assertThat(text, containsString("STORE_ACCESS_DENIED"));

    assertThat(ordersOf(T), is(ours));
    assertThat(returnsOf(T), is(returns));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }

  @Test
  @DisplayName("A retry under the key of a standing exchange is still judged by its body first")
  void aRetryWithABadBodyIsRefusedNotReplayed() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    String key = Ids.newId().toString();
    data(
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, "SEALED"), newLine(V_B, 1), ""),
            T,
            "CASHIER",
            CASHIER,
            key),
        201);
    long orders = ordersOf(T);

    Response r =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, "MINT"), newLine(V_B, 1), ""),
            T,
            "CASHIER",
            CASHIER,
            key);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(400));
    assertThat(text, containsString("ORDER_RETURN_CONDITION_INVALID"));
    assertThat(ordersOf(T), is(orders));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(1L));
  }

  private Response exchangeHeld(String order, String json, String stores) {
    return rig()
        .as("/orders/" + order + "/exchange", T, "CASHIER", CASHIER, stores)
        .header("Idempotency-Key", Ids.newId().toString())
        .post(
            jakarta.ws.rs.client.Entity.entity(
                json, jakarta.ws.rs.core.MediaType.APPLICATION_JSON));
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

    // Nor with the recalled lot in the basket: the sale is not found, and our recall is never
    // what they learn of.
    Response recalled =
        exchange(
            order,
            exchangeBody(lineOf(V_A, 1, "SEALED"), scannedLine(V_A, "batchNo", RECALLED_LOT), ""),
            OTHER_T,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    String text = recalled.readEntity(String.class);
    assertThat(text, recalled.getStatus(), is(404));
    assertThat(text, containsString("ORDER_NOT_FOUND"));

    assertThat(ordersOf(T), is(ours));
    assertThat(ordersOf(OTHER_T), is(theirs));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(0L));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }

  @Test
  @DisplayName("Our recall never stops another business's exchange of the same lot number")
  void ourRecallNeverReachesAnotherBusinesssExchange() {
    String theirSale = rig().sale(OTHER_T, STORE, V_A, 2, null, MANAGER);

    data(
        exchange(
            theirSale,
            exchangeBody(lineOf(V_A, 1, "SEALED"), scannedLine(V_A, "batchNo", RECALLED_LOT), ""),
            OTHER_T,
            "CASHIER",
            CASHIER,
            Ids.newId().toString()),
        201);
    assertThat(
        "their own recalls were asked, never ours",
        INVENTORY.calls().stream()
            .filter(c -> "/admin/inventory/recalls/active".equals(c.path()))
            .allMatch(c -> OTHER_T.equals(c.tenantId())),
        is(true));
  }
}
