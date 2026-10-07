package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.events.contract.GiftCardLoadReversed;
import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A gift card sold with a sale goes back off the card when the sale is voided or cancelled
 * (till-sessions-and-registers slice 8, the gift-card void hole): the same transaction, a ledger
 * row naming the order, {@code GiftCardLoadReversed} announced; a card that was spent refuses the
 * whole void or cancel and nothing moves.
 */
@HelidonTest
class GiftCardReversalIT {

  private static final String T = "01a0a1c6-1111-7000-8000-000000000011";
  private static final String OTHER_T = "01a0a1c6-1111-7000-8000-000000000013";
  private static final String STORE = "01a0a1c6-2222-7000-8000-00000000001a";
  private static final String OTHER_STORE = "01a0a1c6-2222-7000-8000-00000000001b";
  private static final String MANAGER = "01a0a1c6-4444-7000-8000-000000000011";
  private static final String CASHIER = "01a0a1c6-4444-7000-8000-000000000012";
  private static final String[] ROLES = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER"};

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US").with(OTHER_T, "USD", "US");
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

  private BigDecimal balanceOf(String cardId) {
    return new BigDecimal(
        rig().one("SELECT current_balance FROM \"order\".gift_cards WHERE id='" + cardId + "'"));
  }

  private String statusOfCard(String cardId) {
    return rig().one("SELECT status FROM \"order\".gift_cards WHERE id='" + cardId + "'");
  }

  private String statusOf(String order) {
    return rig().one("SELECT status FROM \"order\".orders WHERE id='" + order + "'");
  }

  private long reversals(String cardId) {
    return rig()
        .count(
            "gift_card_transactions", "gift_card_id='" + cardId + "' AND tx_type='LOAD_REVERSED'");
  }

  private long voidRows(String order) {
    return rig().count("pos_void_log", "order_id='" + order + "'");
  }

  private static boolean eq(BigDecimal a, String b) {
    return a.compareTo(new BigDecimal(b)) == 0;
  }

  private JsonObject place(String items, String loads) {
    String body =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":["
            + items
            + "],\"giftCardLoads\":["
            + loads
            + "]}";
    return data(rig().post("/orders", body, T, "CASHIER", CASHIER, Ids.newId().toString()), 201);
  }

  private void pay(JsonObject order) {
    orderService.handlePaymentCaptured(
        Ids.parse(T),
        Ids.parse(order.getString("id")),
        Ids.newId(),
        order.getJsonNumber("total").bigDecimalValue());
  }

  private JsonObject firstLoad(String order) {
    Response r =
        rig().getHeld("/orders/" + order + "/gift-card-loads", T, "CASHIER", CASHIER, STORE);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Envelopes.parse(body).getJsonArray("data").getJsonObject(0);
  }

  /** A paid till sale of a new card of {@code amount}, with {@code items} (may be empty). */
  private JsonObject paidCardSale(String items, String amount) {
    JsonObject order = place(items, "{\"amount\":" + amount + "}");
    pay(order);
    return order;
  }

  private Response voidIt(
      String order, String tenant, String role, String user, String stores, String key) {
    var b = rig().as("/orders/" + order + "/void", tenant, role, user, stores);
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(Entity.entity("{\"reason\":\"rang up wrongly\"}", MediaType.APPLICATION_JSON));
  }

  private Response cancelIt(String order, String tenant, String role, String user) {
    return rig()
        .as("/orders/" + order + "/cancel", tenant, role, user)
        .post(Entity.entity("{\"reason\":\"changed mind\"}", MediaType.APPLICATION_JSON));
  }

  private String redeem(String code, String amount) {
    // The card is spent as a tender on some other sale (a goods sale, still unpaid).
    String other = place("{\"variantId\":\"" + V_A + "\",\"qty\":1}", "").getString("id");
    Response r =
        rig()
            .post(
                "/gift-cards/" + code + "/redeem",
                "{\"amount\":" + amount + ",\"orderId\":\"" + other + "\"}",
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString());
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    return code;
  }

  /** Rule 1 and 5: a card-only sale, paid then voided: the card is taken back and cancelled. */
  @Test
  @DisplayName("Voiding a paid card sale takes the card back, cancels a new card, announces it")
  void voidTakesTheCardBack() {
    JsonObject order = paidCardSale("", "50.00");
    String id = order.getString("id");
    String cardId = firstLoad(id).getString("giftCardId");
    assertThat(eq(balanceOf(cardId), "50"), is(true));

    data(voidIt(id, T, "MANAGER", MANAGER, null, Ids.newId().toString()), 200);

    assertThat(statusOf(id), is("VOIDED"));
    assertThat(voidRows(id), is(1L));
    assertThat(eq(balanceOf(cardId), "0"), is(true));
    assertThat(statusOfCard(cardId), is("CANCELLED"));
    assertThat(reversals(cardId), is(1L));
    assertThat(
        rig()
            .one(
                "SELECT amount || '/' || balance_before || '/' || balance_after || '/' || order_id"
                    + " FROM \"order\".gift_card_transactions WHERE gift_card_id='"
                    + cardId
                    + "' AND tx_type='LOAD_REVERSED'"),
        is("50.00/50.00/0.00/" + id));
    // The ISSUE row is still there, untouched: the ledger only grows.
    assertThat(
        rig().count("gift_card_transactions", "gift_card_id='" + cardId + "' AND tx_type='ISSUE'"),
        is(1L));

    assertThat(rig().events(cardId, "GiftCardLoadReversed"), is(1L));
    var read =
        GiftCardLoadReversed.read(
            Envelopes.scalar(
                PG,
                "SELECT payload FROM \"order\".outbox WHERE aggregate_id='"
                    + cardId
                    + "' AND event_type='GiftCardLoadReversed'"));
    assertThat(read.orderId().toString(), is(id));
    assertThat(read.giftCardId().toString(), is(cardId));
    assertThat(read.amount().compareTo(new BigDecimal("50")), is(0));
    assertThat(read.currency(), is("USD"));
    assertThat(read.source(), is("SALE"));
    assertThat(read.storeId().orElseThrow().toString(), is(STORE));
    assertThat(read.envelope().tenantId().orElseThrow().toString(), is(T));
    assertThat(rig().events(id, "OrderVoided"), is(1L));
  }

  /** Rule 7: a retry under the same key answers the first outcome and reverses nothing twice. */
  @Test
  @DisplayName("A retried void answers the first and reverses once")
  void aRetryReversesOnce() {
    JsonObject order = paidCardSale("", "20.00");
    String id = order.getString("id");
    String cardId = firstLoad(id).getString("giftCardId");
    String key = Ids.newId().toString();
    JsonObject first = data(voidIt(id, T, "MANAGER", MANAGER, null, key), 200);
    JsonObject again = data(voidIt(id, T, "MANAGER", MANAGER, null, key), 200);
    assertThat(again.getString("voidedAt"), is(first.getString("voidedAt")));
    assertThat(reversals(cardId), is(1L));
    assertThat(rig().events(cardId, "GiftCardLoadReversed"), is(1L));
    assertThat(voidRows(id), is(1L));
    assertThat(eq(balanceOf(cardId), "0"), is(true));
  }

  /** Rule 1 for a mixed sale, and rule 4: the goods refund at what they cost, not the card. */
  @Test
  @DisplayName("A mixed sale: the goods are returned at their price only; the void takes the card")
  void aMixedSaleReturnsGoodsOnlyAndVoidsTheCard() {
    JsonObject sale = paidCardSale("{\"variantId\":\"" + V_A + "\",\"qty\":1}", "25.00");
    String id = sale.getString("id");
    assertThat(eq(sale.getJsonNumber("total").bigDecimalValue(), "35"), is(true));
    String cardId = firstLoad(id).getString("giftCardId");

    Response ret =
        rig()
            .post(
                "/orders/" + id + "/returns",
                "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\""
                    + V_A
                    + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString());
    assertThat(ret.readEntity(String.class), ret.getStatus(), is(201));
    JsonObject ev = rig().event(id, "OrderReturned");
    assertThat(eq(ev.getJsonNumber("refundAmount").bigDecimalValue(), "10"), is(true));
    // The card is not something you return: it still holds its value.
    assertThat(eq(balanceOf(cardId), "25"), is(true));
    assertThat(reversals(cardId), is(0L));
  }

  @Test
  @DisplayName("A mixed sale voided takes the card back, and the goods restock")
  void aMixedSaleVoided() {
    JsonObject sale = paidCardSale("{\"variantId\":\"" + V_A + "\",\"qty\":1}", "25.00");
    String id = sale.getString("id");
    String cardId = firstLoad(id).getString("giftCardId");
    data(voidIt(id, T, "MANAGER", MANAGER, null, Ids.newId().toString()), 200);
    assertThat(statusOf(id), is("VOIDED"));
    assertThat(eq(balanceOf(cardId), "0"), is(true));
    assertThat(statusOfCard(cardId), is("CANCELLED"));
    assertThat(rig().events(cardId, "GiftCardLoadReversed"), is(1L));
  }

  /** A card topped up by the sale loses only that line's amount and stays ACTIVE. */
  @Test
  @DisplayName("A top-up is taken back to what the card held before; the card stays active")
  void aTopUpIsTakenBack() {
    JsonObject mine =
        data(
            rig()
                .post(
                    "/gift-cards",
                    "{\"storeId\":\"" + STORE + "\",\"amount\":5.00,\"reason\":\"GOODWILL\"}",
                    T,
                    "MANAGER",
                    MANAGER,
                    Ids.newId().toString()),
            201);
    String cardId = mine.getString("id");
    JsonObject order = place("", "{\"amount\":20.00,\"code\":\"" + mine.getString("code") + "\"}");
    pay(order);
    assertThat(eq(balanceOf(cardId), "25"), is(true));
    data(voidIt(order.getString("id"), T, "MANAGER", MANAGER, null, Ids.newId().toString()), 200);
    assertThat(eq(balanceOf(cardId), "5"), is(true));
    assertThat(statusOfCard(cardId), is("ACTIVE"));
    assertThat(reversals(cardId), is(1L));
  }

  /** Rule 1: a card left at nothing by the reversal reads DEPLETED, as a redeem to nothing does. */
  @Test
  @DisplayName("A top-up taken back that leaves the card at nothing leaves it depleted, not active")
  void aTopUpTakenBackToNothingIsDepleted() {
    JsonObject mine =
        data(
            rig()
                .post(
                    "/gift-cards",
                    "{\"storeId\":\"" + STORE + "\",\"amount\":5.00,\"reason\":\"GOODWILL\"}",
                    T,
                    "MANAGER",
                    MANAGER,
                    Ids.newId().toString()),
            201);
    String cardId = mine.getString("id");
    String code = mine.getString("code");
    JsonObject order = place("", "{\"amount\":20.00,\"code\":\"" + code + "\"}");
    pay(order);
    assertThat(eq(balanceOf(cardId), "25"), is(true));
    // The customer spends the five the card held before the top-up.
    redeem(code, "5.00");
    data(voidIt(order.getString("id"), T, "MANAGER", MANAGER, null, Ids.newId().toString()), 200);
    assertThat(eq(balanceOf(cardId), "0"), is(true));
    assertThat(statusOfCard(cardId), is("DEPLETED"));
    assertThat(reversals(cardId), is(1L));
  }

  /** Rule 1 for a cancel (the sale was paid and is CONFIRMED, not yet handed over). */
  @Test
  @DisplayName("Cancelling a paid card sale takes the card back on the same transaction")
  void cancelTakesTheCardBack() {
    JsonObject order = paidCardSale("", "30.00");
    String id = order.getString("id");
    String cardId = firstLoad(id).getString("giftCardId");
    // A till sale is handed over when paid; put it back to CONFIRMED, as an unhanded sale is.
    Envelopes.exec(PG, "UPDATE \"order\".orders SET status='CONFIRMED' WHERE id='" + id + "'");
    data(cancelIt(id, T, "MANAGER", MANAGER), 200);
    assertThat(statusOf(id), is("CANCELLED"));
    assertThat(eq(balanceOf(cardId), "0"), is(true));
    assertThat(statusOfCard(cardId), is("CANCELLED"));
    assertThat(reversals(cardId), is(1L));
    assertThat(rig().events(cardId, "GiftCardLoadReversed"), is(1L));
    assertThat(rig().events(id, "OrderCancelled"), is(1L));
  }

  /** Rule 2: a spent card refuses the whole void and nothing moves. */
  @Test
  @DisplayName("A spent card refuses the void with ORDER_GIFT_CARD_SPENT and nothing moves")
  void aSpentCardRefusesTheVoid() {
    JsonObject order = paidCardSale("", "50.00");
    String id = order.getString("id");
    JsonObject load = firstLoad(id);
    String cardId = load.getString("giftCardId");
    String code = load.getString("code");
    redeem(code, "10.00");
    long outbox = rig().count("outbox", "tenant_id='" + T + "'");

    Response r = voidIt(id, T, "MANAGER", MANAGER, null, Ids.newId().toString());
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(409));
    assertThat(body, containsString("ORDER_GIFT_CARD_SPENT"));
    assertThat(body, containsString(code.substring(code.length() - 4)));
    assertThat(body, containsString("50"));
    assertThat(body, containsString("40"));
    assertThat(body, not(containsString(code)));

    assertThat(statusOf(id), is("FULFILLED"));
    assertThat(voidRows(id), is(0L));
    assertThat(eq(balanceOf(cardId), "40"), is(true));
    assertThat(statusOfCard(cardId), is("ACTIVE"));
    assertThat(reversals(cardId), is(0L));
    assertThat(rig().count("outbox", "tenant_id='" + T + "'"), is(outbox));
    assertThat(rig().events(id, "OrderVoided"), is(0L));
  }

  @Test
  @DisplayName("A spent card refuses the cancel too, and nothing moves")
  void aSpentCardRefusesTheCancel() {
    JsonObject order = paidCardSale("", "50.00");
    String id = order.getString("id");
    JsonObject load = firstLoad(id);
    String cardId = load.getString("giftCardId");
    redeem(load.getString("code"), "50.00");
    Envelopes.exec(PG, "UPDATE \"order\".orders SET status='CONFIRMED' WHERE id='" + id + "'");
    long outbox = rig().count("outbox", "tenant_id='" + T + "'");

    Response r = cancelIt(id, T, "MANAGER", MANAGER);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(409));
    assertThat(body, containsString("ORDER_GIFT_CARD_SPENT"));
    assertThat(statusOf(id), is("CONFIRMED"));
    assertThat(eq(balanceOf(cardId), "0"), is(true));
    assertThat(reversals(cardId), is(0L));
    assertThat(rig().count("outbox", "tenant_id='" + T + "'"), is(outbox));
    assertThat(
        rig().count("order_status_history", "order_id='" + id + "' AND to_status='CANCELLED'"),
        is(0L));
  }

  /**
   * Rule 3: an unpaid sale was never loaded, so there is nothing to reverse; a late payment loads
   * nothing.
   */
  @Test
  @DisplayName("Cancelling an unpaid card sale reverses nothing, and a late payment loads no card")
  void anUnpaidCancelAndALatePayment() {
    long cardsBefore = rig().count("gift_cards", "tenant_id='" + T + "'");
    JsonObject order = place("", "{\"amount\":40.00}");
    String id = order.getString("id");
    data(cancelIt(id, T, "CASHIER", CASHIER), 200);
    assertThat(statusOf(id), is("CANCELLED"));
    assertThat(rig().count("gift_card_transactions", "order_id='" + id + "'"), is(0L));
    assertThat(
        rig().count("outbox", "event_type='GiftCardLoadReversed' AND payload LIKE '%" + id + "%'"),
        is(0L));

    // The payment lands after the cancel: the order is not payable any more, so no card is made.
    pay(order);
    assertThat(statusOf(id), is("CANCELLED"));
    assertThat(rig().count("gift_cards", "tenant_id='" + T + "'"), is(cardsBefore));
    assertThat(rig().count("gift_card_transactions", "order_id='" + id + "'"), is(0L));
    assertThat(
        rig().count("gift_card_load_lines", "order_id='" + id + "' AND gift_card_id IS NOT NULL"),
        is(0L));
  }

  /** Rule 7: another business and a manager held to another store move nothing. */
  @Test
  @DisplayName("Another business, and a manager held to another store, cannot void or cancel")
  void isolation() {
    JsonObject order = paidCardSale("", "50.00");
    String id = order.getString("id");
    String cardId = firstLoad(id).getString("giftCardId");
    long outbox = rig().count("outbox", "tenant_id='" + T + "'");

    for (String role : ROLES) {
      Response v = voidIt(id, OTHER_T, role, Ids.newId().toString(), null, Ids.newId().toString());
      int s = v.getStatus();
      // Management reaches the order lookup and finds nothing; a role the void permission refuses
      // is refused earlier still. Either way nothing is revealed and nothing moves.
      boolean management = "OWNER".equals(role) || "MANAGER".equals(role);
      assertThat(role + " void " + s, s == 404 || (!management && s == 403), is(true));
      Response c = cancelIt(id, OTHER_T, role, Ids.newId().toString());
      int cs = c.getStatus();
      assertThat(
          role + " cancel " + cs, cs == 404 || ("CUSTOMER".equals(role) && cs == 403), is(true));
    }
    Response held = voidIt(id, T, "MANAGER", MANAGER, OTHER_STORE, Ids.newId().toString());
    String body = held.readEntity(String.class);
    assertThat(body, held.getStatus(), is(403));
    assertThat(body, containsString("STORE_ACCESS_DENIED"));

    assertThat(statusOf(id), is("FULFILLED"));
    assertThat(voidRows(id), is(0L));
    assertThat(eq(balanceOf(cardId), "50"), is(true));
    assertThat(statusOfCard(cardId), is("ACTIVE"));
    assertThat(reversals(cardId), is(0L));
    assertThat(rig().count("outbox", "tenant_id='" + T + "'"), is(outbox));
  }
}
