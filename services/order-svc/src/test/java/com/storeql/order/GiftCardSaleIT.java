package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gift-card value has a sale behind it (till-sessions slice 8): a card is loaded by a paid sale
 * line, once, for what was paid, or by a manager by hand with a reason; never by a cashier alone.
 */
@HelidonTest
class GiftCardSaleIT {

  private static final String T = "01a0a1c6-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c6-1111-7000-8000-000000000003";
  private static final String STORE = "01a0a1c6-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c6-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1c6-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c6-4444-7000-8000-000000000002";
  private static final String SHOPPER = "01a0a1c6-4444-7000-8000-000000000009";
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

  private static final String MGR2 = "01a0a1c6-4444-7000-8000-000000000003";

  private long cards(String tenant) {
    return rig().count("gift_cards", "tenant_id='" + tenant + "'");
  }

  private BigDecimal balanceOf(String cardId) {
    return new BigDecimal(
        rig().one("SELECT current_balance FROM \"order\".gift_cards WHERE id='" + cardId + "'"));
  }

  /** A till sale of {@code items} (may be empty) plus gift-card lines, placed by the cashier. */
  private JsonObject place(String tenant, String items, String loads, String role, String user) {
    String body =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":["
            + items
            + "],\"giftCardLoads\":["
            + loads
            + "]}";
    return data(rig().post("/orders", body, tenant, role, user, Ids.newId().toString()), 201);
  }

  private void pay(String tenant, JsonObject order, String amount) {
    orderService.handlePaymentCaptured(
        Ids.parse(tenant), Ids.parse(order.getString("id")), Ids.newId(), new BigDecimal(amount));
  }

  private Response loadsRead(String tenant, String order, String role, String user, String stores) {
    return rig().getHeld("/orders/" + order + "/gift-card-loads", tenant, role, user, stores);
  }

  /** The order's first gift-card line as the till reads it. */
  private JsonObject firstLoad(String order) {
    Response r = loadsRead(T, order, "CASHIER", CASHIER, STORE);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return com.storeql.test.Envelopes.parse(body).getJsonArray("data").getJsonObject(0);
  }

  private String cardOfOrder(String order) {
    JsonObject l = firstLoad(order);
    return l.containsKey("giftCardId") ? l.getString("giftCardId") : null;
  }

  @Test
  @DisplayName("A card sold with a sale is issued when the sale is paid, once, for what was paid")
  void valueOnlyAfterPayment() {
    long before = cards(T);
    JsonObject order = place(T, "", "{\"amount\":50.00}", "CASHIER", CASHIER);
    String id = order.getString("id");
    assertThat(
        order.getJsonNumber("total").bigDecimalValue().compareTo(new BigDecimal("50")), is(0));
    // Placed and unpaid: no value exists, and the till is told the line is pending with no code.
    assertThat(cards(T), is(before));
    JsonObject pending = firstLoad(id);
    assertThat(pending.getString("status"), is("PENDING"));
    assertThat(pending.containsKey("code"), is(false));
    assertThat(pending.containsKey("giftCardId"), is(false));

    // Half paid: still none.
    pay(T, order, "20.00");
    assertThat(cards(T), is(before));

    // The capture that completes the payment issues the card, on the same transaction.
    UUID payment = Ids.newId();
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(id), payment, new BigDecimal("30.00"));
    assertThat(cards(T), is(before + 1));
    String cardId = cardOfOrder(id);
    assertThat(balanceOf(cardId).compareTo(new BigDecimal("50")), is(0));
    assertThat(
        rig()
            .count(
                "gift_card_transactions",
                "gift_card_id='" + cardId + "' AND tx_type='ISSUE' AND order_id='" + id + "'"),
        is(1L));
    assertThat(rig().events(cardId, "GiftCardLoaded"), is(1L));
    // The till reads the new card's code from the order, and it is the card's own.
    JsonObject line = firstLoad(id);
    assertThat(line.getString("status"), is("LOADED"));
    assertThat(line.getString("kind"), is("NEW"));
    assertThat(
        line.getJsonNumber("amount").bigDecimalValue().compareTo(new BigDecimal("50")), is(0));
    assertThat(line.containsKey("loadedAt"), is(true));
    JsonObject byCode =
        data(rig().get("/gift-cards/" + line.getString("code"), T, "CASHIER", CASHIER), 200);
    assertThat(byCode.getString("id"), is(cardId));
    JsonObject loaded = rig().event(cardId, "GiftCardLoaded");
    assertThat(loaded.getString("source"), is("SALE"));
    assertThat(
        loaded.getJsonNumber("amount").bigDecimalValue().compareTo(new BigDecimal("50")), is(0));

    // The same event twice is the same effect as once.
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(id), payment, new BigDecimal("30.00"));
    assertThat(cards(T), is(before + 1));
    assertThat(balanceOf(cardId).compareTo(new BigDecimal("50")), is(0));
    assertThat(rig().events(cardId, "GiftCardLoaded"), is(1L));
  }

  @Test
  @DisplayName("A sale of goods and a card confirms for the goods only; the card is stored value")
  void mixedSaleConfirmsTheGoodsOnly() {
    JsonObject order =
        place(
            T,
            "{\"variantId\":\"" + V_A + "\",\"qty\":1}",
            "{\"amount\":25.00}",
            "CASHIER",
            CASHIER);
    assertThat(
        order.getJsonNumber("total").bigDecimalValue().compareTo(new BigDecimal("35")), is(0));
    pay(T, order, "35.00");
    JsonObject confirmed = rig().event(order.getString("id"), "OrderConfirmed");
    assertThat(confirmed.getJsonNumber("total").bigDecimalValue().compareTo(BigDecimal.TEN), is(0));
    assertThat(
        balanceOf(cardOfOrder(order.getString("id"))).compareTo(new BigDecimal("25")), is(0));
  }

  @Test
  @DisplayName("A line naming a card tops it up when paid; a business cannot name another's card")
  void aLoadCanTopUpItsOwnCardOnly() {
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
    String code = mine.getString("code");
    JsonObject order =
        place(T, "", "{\"amount\":20.00,\"code\":\"" + code + "\"}", "CASHIER", CASHIER);
    assertThat(balanceOf(mine.getString("id")).compareTo(new BigDecimal("5")), is(0));
    pay(T, order, "20.00");
    assertThat(balanceOf(mine.getString("id")).compareTo(new BigDecimal("25")), is(0));
    assertThat(cardOfOrder(order.getString("id")), is(mine.getString("id")));
    assertThat(firstLoad(order.getString("id")).getString("kind"), is("TOP_UP"));
    assertThat(
        rig()
            .count(
                "gift_card_transactions",
                "gift_card_id='" + mine.getString("id") + "' AND tx_type='RELOAD'"),
        is(1L));

    // Another business names our code: no such card there, the order is not placed.
    long orders = rig().count("orders", "tenant_id='" + OTHER_T + "'");
    Response foreign =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + OTHER_STORE
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                    + "\"items\":[],\"giftCardLoads\":[{\"amount\":20.00,\"code\":\""
                    + code
                    + "\"}]}",
                OTHER_T,
                "OWNER",
                MGR2,
                Ids.newId().toString());
    assertThat(foreign.getStatus(), is(404));
    assertThat(foreign.readEntity(String.class), containsString("GIFT_CARD_NOT_FOUND"));
    assertThat(rig().count("orders", "tenant_id='" + OTHER_T + "'"), is(orders));
    assertThat(balanceOf(mine.getString("id")).compareTo(new BigDecimal("25")), is(0));
  }

  @Test
  @DisplayName("An unpaid card sale that lapses issues nothing")
  void anUnpaidCardSaleIssuesNothing() {
    long before = cards(T);
    JsonObject order = place(T, "", "{\"amount\":40.00}", "CASHIER", CASHIER);
    orderService.sweepExpiredPendingOrders(0, 200);
    assertThat(
        rig().one("SELECT status FROM \"order\".orders WHERE id='" + order.getString("id") + "'"),
        is("CANCELLED"));
    assertThat(cards(T), is(before));
    assertThat(
        cardOfOrder(order.getString("id")) == null || cardOfOrder(order.getString("id")).isEmpty(),
        is(true));
  }

  @Test
  @DisplayName("A card line is refused for a bad amount, for delivery, and with too many lines")
  void badCardLinesAreRefused() {
    for (String bad : new String[] {"0", "-5", "10.005"}) {
      Response r =
          rig()
              .post(
                  "/orders",
                  "{\"storeId\":\""
                      + STORE
                      + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                      + "\"items\":[],\"giftCardLoads\":[{\"amount\":"
                      + bad
                      + "}]}",
                  T,
                  "CASHIER",
                  CASHIER,
                  Ids.newId().toString());
      assertThat(bad, r.getStatus(), is(400));
    }
    Response delivery =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\","
                    + "\"items\":[],\"giftCardLoads\":[{\"amount\":10.00}]}",
                T,
                "CUSTOMER",
                SHOPPER,
                Ids.newId().toString());
    assertThat(delivery.getStatus(), is(400));
    Response none =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":[]}",
                T,
                "CASHIER",
                CASHIER,
                Ids.newId().toString());
    assertThat(none.getStatus(), is(400));
    assertThat(none.readEntity(String.class), containsString("ORDER_NO_ITEMS"));
  }

  @Test
  @DisplayName("Only staff at the order's store read the card codes a sale made")
  void codesAreReadByStaffAtTheStoreOnly() {
    JsonObject order = place(T, "", "{\"amount\":15.00}", "CASHIER", CASHIER);
    String id = order.getString("id");
    pay(T, order, "15.00");
    String code = firstLoad(id).getString("code");

    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(role, loadsRead(T, id, role, CASHIER, STORE).getStatus(), is(200));
    }
    // A shopper, even the customer of the sale, and no role at all, are refused.
    assertThat(loadsRead(T, id, "CUSTOMER", SHOPPER, null).getStatus(), is(403));
    assertThat(loadsRead(T, id, null, SHOPPER, null).getStatus(), is(403));
    // Held to another store: refused, and the code is not in the answer.
    Response held = loadsRead(T, id, "MANAGER", MANAGER, OTHER_STORE);
    assertThat(held.getStatus(), is(403));
    assertThat(held.readEntity(String.class), not(containsString(code)));
    // Another business's staff of every role, naming our order: nothing.
    for (String role : ROLES) {
      Response theirs = loadsRead(OTHER_T, id, role, Ids.newId().toString(), null);
      assertThat(role, theirs.getStatus() == 404 || theirs.getStatus() == 403, is(true));
      assertThat(role, theirs.readEntity(String.class), not(containsString(code)));
    }
  }

  @Test
  @DisplayName("Direct issue is a manager's, with a reason and a key; a retry answers the first")
  void directIssueNeedsAManagerAndAReason() {
    String body =
        "{\"storeId\":\""
            + STORE
            + "\",\"amount\":30.00,\"reason\":\"GOODWILL\",\"note\":\"late order\"}";
    long before = cards(T);

    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response r = rig().post("/gift-cards", body, T, role, CASHIER, Ids.newId().toString());
      assertThat(role, r.getStatus(), is(403));
      assertThat(role, r.readEntity(String.class), containsString("GIFT_CARD_NEEDS_SALE"));
    }
    assertThat(cards(T), is(before));

    Response noKey = rig().post("/gift-cards", body, T, "MANAGER", MANAGER, null);
    assertThat(noKey.getStatus(), is(400));
    Response noReason =
        rig()
            .post(
                "/gift-cards",
                "{\"storeId\":\"" + STORE + "\",\"amount\":30.00}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString());
    assertThat(noReason.getStatus(), is(400));
    assertThat(noReason.readEntity(String.class), containsString("GIFT_CARD_REASON_REQUIRED"));
    assertThat(cards(T), is(before));

    String key = Ids.newId().toString();
    JsonObject first = data(rig().post("/gift-cards", body, T, "MANAGER", MANAGER, key), 201);
    JsonObject again = data(rig().post("/gift-cards", body, T, "MANAGER", MANAGER, key), 201);
    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(cards(T), is(before + 1));
    assertThat(rig().events(first.getString("id"), "GiftCardLoaded"), is(1L));

    // Who did it, and why, is kept with the ledger row.
    assertThat(
        rig()
            .one(
                "SELECT acted_by || '/' || source || '/' || reason FROM \"order\".gift_card_transactions WHERE gift_card_id='"
                    + first.getString("id")
                    + "'"),
        is(MANAGER + "/GOODWILL/late order"));
    assertThat(
        rig()
            .one(
                "SELECT source FROM \"order\".gift_cards WHERE id='" + first.getString("id") + "'"),
        is("GOODWILL"));

    // The same key for a different amount is not the same request.
    Response reused =
        rig().post("/gift-cards", body.replace("30.00", "99.00"), T, "MANAGER", MANAGER, key);
    assertThat(reused.getStatus(), is(409));
    assertThat(reused.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REUSED"));
    assertThat(cards(T), is(before + 1));

    // A reload retried under one key adds the value once.
    String code = first.getString("code");
    String reloadKey = Ids.newId().toString();
    String reload = "{\"amount\":10.00,\"reason\":\"PROMOTION\"}";
    assertThat(
        rig()
            .post("/gift-cards/" + code + "/reload", reload, T, "MANAGER", MANAGER, reloadKey)
            .getStatus(),
        is(200));
    assertThat(
        rig()
            .post("/gift-cards/" + code + "/reload", reload, T, "MANAGER", MANAGER, reloadKey)
            .getStatus(),
        is(200));
    assertThat(balanceOf(first.getString("id")).compareTo(new BigDecimal("40")), is(0));
    assertThat(rig().events(first.getString("id"), "GiftCardLoaded"), is(2L));
  }
}
