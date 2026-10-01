package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_TAX;
import static com.storeql.order.support.ReturnsRig.data;
import static com.storeql.order.support.ReturnsRig.nullish;
import static com.storeql.test.Envelopes.parse;
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
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
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
 * Returns with no receipt (intent/return-controls.md): off until a business turns them on, a
 * manager's alone, for store credit or a gift card and never cash, at today's price, capped, and
 * announced by order-svc itself because there is no sale for anyone else to refund against.
 */
@HelidonTest
class NoReceiptReturnIT {

  /** A business that has set nothing: no-receipt returns are off. */
  private static final String T_OFF = "01a0a1c6-1111-7000-8000-000000000001";

  /** A business that allows them, up to 30.00. */
  private static final String T_ON = "01a0a1c6-1111-7000-8000-000000000002";

  /** Another business that allows them, which must never reach the first. */
  private static final String OTHER_T = "01a0a1c6-1111-7000-8000-000000000003";

  private static final String STORE = "01a0a1c6-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c6-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1c6-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c6-4444-7000-8000-000000000002";
  private static final String CUSTOMER = "01a0a1c6-5555-7000-8000-000000000001";
  private static final String CONTACT = "+1 555 0100 (Ada)";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;
  private static final AtomicBoolean PRICING_DOWN = new AtomicBoolean(false);

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T_OFF, "USD", "US")
        .with(T_ON, "USD", "US")
        .with(OTHER_T, "USD", "US");
    PRICING = ReturnsRig.pricing(PRICING_DOWN);
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
  private static boolean configured;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.order.pricing.enforce");
    PRICING.close();
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    if (!configured) {
      configured = true;
      String on = "{\"windowDays\":30,\"noReceiptAllowed\":true,\"noReceiptCeiling\":30.00}";
      assertThat(rig.put("/admin/return-policy", on, T_ON, "OWNER", MANAGER).getStatus(), is(200));
      assertThat(
          rig.put("/admin/return-policy", on, OTHER_T, "OWNER", MANAGER).getStatus(), is(200));
    }
    return rig;
  }

  private static String body(String method, int qty, String condition, String extra) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"reason\":\"no receipt\",\"refundMethod\":\""
        + method
        + "\",\"customerContact\":\""
        + CONTACT
        + "\",\"items\":[{\"variantId\":\""
        + V_TAX
        + "\",\"qty\":"
        + qty
        + (condition == null ? "" : ",\"condition\":\"" + condition + "\"")
        + "}]"
        + extra
        + "}";
  }

  private Response noReceipt(String json, String tenant, String roles, String user, String key) {
    return rig().post("/returns/no-receipt", json, tenant, roles, user, key);
  }

  private long returnsOf(String tenant) {
    return rig().count("returns", "tenant_id='" + tenant + "'");
  }

  private long cardsOf(String tenant) {
    return rig().count("gift_cards", "tenant_id='" + tenant + "'");
  }

  private static String creditExtra() {
    return ",\"customerId\":\"" + CUSTOMER + "\"";
  }

  // ── off, and whose it is ──────────────────────────────────────────────────

  @Test
  @DisplayName("A business that has set nothing takes no return without a receipt")
  void offByDefault() {
    long before = returnsOf(T_OFF);
    Response r =
        noReceipt(
            body("STORE_CREDIT", 1, "SEALED", creditExtra()),
            T_OFF,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("ORDER_NO_RECEIPT_RETURNS_OFF"));
    assertThat(returnsOf(T_OFF), is(before));
    assertThat(cardsOf(T_OFF), is(0L));
  }

  @Test
  @DisplayName("A cashier is refused, naming NO_RECEIPT, and nothing is written")
  void aCashierNeedsAManager() {
    long before = returnsOf(T_ON);
    Response r =
        noReceipt(
            body("STORE_CREDIT", 1, "SEALED", creditExtra()),
            T_ON,
            "CASHIER",
            CASHIER,
            Ids.newId().toString());
    assertThat(r.getStatus(), is(403));
    String text = r.readEntity(String.class);
    assertThat(text, containsString("ORDER_RETURN_NEEDS_MANAGER"));
    assertThat(text, containsString("NO_RECEIPT"));
    assertThat(returnsOf(T_ON), is(before));
  }

  // ── store credit ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("A manager returns for store credit at today's price, and the event says so")
  void storeCreditAtTodaysPrice() {
    JsonObject ret =
        data(
            noReceipt(
                body("STORE_CREDIT", 2, "OPENED", creditExtra()),
                T_ON,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
            201);

    String id = ret.getString("id");
    assertThat(nullish(ret, "orderId"), is(true));
    assertThat(ret.getBoolean("noReceipt"), is(true));
    assertThat(ret.getString("refundMethod"), is("STORE_CREDIT"));
    assertThat(ret.getString("customerId"), is(CUSTOMER));
    assertThat(ret.getString("approvedBy"), is(MANAGER));
    assertThat(ret.getString("createdBy"), is(MANAGER));
    assertThat(ret.getJsonArray("outsidePolicy").getString(0), is("NO_RECEIPT"));
    // Twelve at the till (ten and its two of VAT), two of them.
    assertThat(ret.getJsonNumber("refundAmount").bigDecimalValue(), is(new BigDecimal("24.00")));
    JsonObject line = ret.getJsonArray("items").getJsonObject(0);
    assertThat(line.getString("condition"), is("OPENED"));
    assertThat(
        line.getJsonNumber("unitPrice").bigDecimalValue().compareTo(new BigDecimal("12")), is(0));
    assertThat(
        line.getJsonNumber("taxAmount").bigDecimalValue().compareTo(new BigDecimal("4")), is(0));

    // Written with no order, and the customer's contact kept.
    assertThat(
        rig()
            .one(
                "SELECT (order_id IS NULL)::text || '/' || no_receipt::text || '/'"
                    + " || customer_contact FROM \"order\".returns WHERE id='"
                    + id
                    + "'"),
        is("true/true/" + CONTACT));

    // Announced by order-svc itself: there is no order, so no OrderReturned.
    JsonObject ev = rig().event(id, "NoReceiptReturnRecorded");
    assertThat(ev.getString("eventType"), is("NoReceiptReturnRecorded"));
    assertThat(ev.containsKey("eventId"), is(true));
    assertThat(ev.getString("tenantId"), is(T_ON));
    assertThat(ev.getString("returnId"), is(id));
    assertThat(ev.getString("storeId"), is(STORE));
    assertThat(ev.getString("currency"), is("USD"));
    assertThat(ev.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("24.00")));
    assertThat(
        ev.getJsonNumber("taxAmount").bigDecimalValue().compareTo(new BigDecimal("4")), is(0));
    assertThat(ev.getString("refundMethod"), is("STORE_CREDIT"));
    assertThat(ev.getString("customerId"), is(CUSTOMER));
    assertThat(nullish(ev, "giftCardId"), is(true));
    assertThat(ev.getString("approvedBy"), is(MANAGER));
    JsonObject evLine = ev.getJsonArray("items").getJsonObject(0);
    assertThat(evLine.getString("variantId"), is(V_TAX));
    assertThat(evLine.getJsonNumber("qty").bigDecimalValue().compareTo(new BigDecimal("2")), is(0));
    assertThat(
        evLine.getJsonNumber("unitPrice").bigDecimalValue().compareTo(new BigDecimal("12")), is(0));
    assertThat(
        evLine.getJsonNumber("taxAmount").bigDecimalValue().compareTo(new BigDecimal("4")), is(0));
    assertThat(evLine.getString("condition"), is("OPENED"));
    assertThat(
        rig().count("outbox", "payload LIKE '%" + id + "%' AND event_type='OrderReturned'"),
        is(0L));
    // The customer's contact is kept for the record and goes nowhere else.
    String announced =
        rig().one("SELECT payload FROM \"order\".outbox WHERE aggregate_id='" + id + "' LIMIT 1");
    assertThat(announced, not(containsString("555 0100")));
    assertThat(announced, not(containsString("Ada")));

    // The audit trail shows it as a return with no receipt, naming the manager.
    Response trail = rig().get("/admin/audit/events?type=RETURN&limit=100", T_ON, "OWNER", MANAGER);
    String text = trail.readEntity(String.class);
    assertThat(text, trail.getStatus(), is(200));
    JsonArray rows = parse(text).getJsonArray("data");
    JsonObject entry = null;
    for (JsonValue v : rows)
      if (id.equals(v.asJsonObject().getString("id"))) entry = v.asJsonObject();
    assertThat("the return is on the trail", entry != null, is(true));
    assertThat(entry.getString("type"), is("RETURN"));
    assertThat(entry.getBoolean("noReceipt"), is(true));
    assertThat(nullish(entry, "orderId"), is(true));
    assertThat(entry.getString("approvedBy"), is(MANAGER));
    assertThat(entry.getString("storeId"), is(STORE));
    assertThat(entry.getJsonArray("lines").getJsonObject(0).getString("condition"), is("OPENED"));
  }

  @Test
  @DisplayName("Store credit needs a customer to credit")
  void storeCreditNeedsACustomer() {
    long before = returnsOf(T_ON);
    Response r =
        noReceipt(
            body("STORE_CREDIT", 1, "SEALED", ""),
            T_ON,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    assertThat(r.getStatus(), is(409));
    assertThat(
        r.readEntity(String.class), containsString("ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER"));
    assertThat(returnsOf(T_ON), is(before));
  }

  // ── gift cards ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A gift card refund issues a new card holding the value, without an order")
  void giftCardIssuesANewCard() {
    long cards = cardsOf(T_ON);
    JsonObject ret =
        data(
            noReceipt(
                body("GIFT_CARD", 1, "SEALED", ""),
                T_ON,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
            201);
    JsonObject card = ret.getJsonObject("giftCard");
    assertThat(card.getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("12.00")));
    assertThat(cardsOf(T_ON), is(cards + 1));
    assertThat(
        rig()
            .one(
                "SELECT currency || '/' || store_id::text FROM \"order\".gift_cards WHERE id='"
                    + card.getString("id")
                    + "'"),
        is("USD/" + STORE));

    // A ledger row names the return, not an order.
    assertThat(
        rig()
            .one(
                "SELECT (order_id IS NULL)::text || '/' || tx_type || '/' || reference"
                    + " FROM \"order\".gift_card_transactions WHERE gift_card_id='"
                    + card.getString("id")
                    + "'"),
        is("true/ISSUE/" + ret.getString("id")));
    JsonObject loaded = rig().event(card.getString("id"), "GiftCardLoaded");
    assertThat(loaded.getString("paidBy"), is("RETURN"));
    assertThat(loaded.getString("returnId"), is(ret.getString("id")));
    JsonObject ev = rig().event(ret.getString("id"), "NoReceiptReturnRecorded");
    assertThat(ev.getString("giftCardId"), is(card.getString("id")));
    assertThat(ev.getString("refundMethod"), is("GIFT_CARD"));
    assertThat(nullish(ev, "customerId"), is(true));
  }

  @Test
  @DisplayName("A gift card refund tops up a named card of this business, and no other")
  void giftCardTopsUpANamedCard() {
    JsonObject mine =
        data(
            rig()
                .post(
                    "/gift-cards",
                    "{\"storeId\":\"" + STORE + "\",\"amount\":5.00,\"reason\":\"GOODWILL\"}",
                    T_ON,
                    "MANAGER",
                    MANAGER,
                    Ids.newId().toString()),
            201);
    JsonObject ret =
        data(
            noReceipt(
                body(
                    "GIFT_CARD",
                    1,
                    "SEALED",
                    ",\"giftCardCode\":\"" + mine.getString("code") + "\""),
                T_ON,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
            201);
    assertThat(ret.getJsonObject("giftCard").getString("id"), is(mine.getString("id")));
    assertThat(
        ret.getJsonObject("giftCard").getJsonNumber("balance").bigDecimalValue(),
        is(new BigDecimal("17.00")));

    // Another business's card is not found, and nothing is returned.
    JsonObject theirs =
        data(
            rig()
                .post(
                    "/gift-cards",
                    "{\"storeId\":\"" + STORE + "\",\"amount\":5.00,\"reason\":\"GOODWILL\"}",
                    OTHER_T,
                    "MANAGER",
                    MANAGER,
                    Ids.newId().toString()),
            201);
    long before = returnsOf(T_ON);
    Response foreign =
        noReceipt(
            body(
                "GIFT_CARD", 1, "SEALED", ",\"giftCardCode\":\"" + theirs.getString("code") + "\""),
            T_ON,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    assertThat(foreign.getStatus(), is(404));
    assertThat(returnsOf(T_ON), is(before));
  }

  // ── the ceiling, the method, the input ───────────────────────────────────

  @Test
  @DisplayName("Over the business's no-receipt ceiling is refused and nothing is written")
  void overTheCeiling() {
    long returns = returnsOf(T_ON);
    long cards = cardsOf(T_ON);
    // Three at twelve is 36.00 against 30.00.
    Response r =
        noReceipt(
            body("GIFT_CARD", 3, "SEALED", ""), T_ON, "MANAGER", MANAGER, Ids.newId().toString());
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("ORDER_NO_RECEIPT_OVER_CEILING"));
    assertThat(returnsOf(T_ON), is(returns));
    assertThat(cardsOf(T_ON), is(cards));
  }

  @Test
  @DisplayName("Never to how the sale was paid, never cash, and never without a condition")
  void methodAndInputAreChecked() {
    long returns = returnsOf(T_ON);
    for (String method : new String[] {"ORIGINAL", "CASH", "EXCHANGE", "nonsense"}) {
      Response r =
          noReceipt(
              body(method, 1, "SEALED", creditExtra()),
              T_ON,
              "MANAGER",
              MANAGER,
              Ids.newId().toString());
      assertThat(method, r.getStatus(), is(400));
      assertThat(
          method, r.readEntity(String.class), containsString("ORDER_NO_RECEIPT_METHOD_INVALID"));
    }
    Response noCondition =
        noReceipt(
            body("STORE_CREDIT", 1, null, creditExtra()),
            T_ON,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    assertThat(noCondition.getStatus(), is(400));
    assertThat(
        noCondition.readEntity(String.class), containsString("ORDER_RETURN_CONDITION_REQUIRED"));
    Response noKey =
        noReceipt(body("STORE_CREDIT", 1, "SEALED", creditExtra()), T_ON, "MANAGER", MANAGER, null);
    assertThat(noKey.getStatus(), is(400));
    assertThat(noKey.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    // No way to reach the customer.
    Response noContact =
        noReceipt(
            body("STORE_CREDIT", 1, "SEALED", creditExtra()).replace(CONTACT, "   "),
            T_ON,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    assertThat(noContact.getStatus(), is(400));
    // A shopper is no member of staff.
    assertThat(
        noReceipt(
                body("STORE_CREDIT", 1, "SEALED", creditExtra()),
                T_ON,
                "CUSTOMER",
                CUSTOMER,
                Ids.newId().toString())
            .getStatus(),
        is(403));
    // A manager held to another store cannot take one here.
    Response elsewhere =
        rig()
            .as("/returns/no-receipt", T_ON, "MANAGER", MANAGER)
            .header("X-Store-Ids", OTHER_STORE)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    body("STORE_CREDIT", 1, "SEALED", creditExtra()), MediaType.APPLICATION_JSON));
    assertThat(elsewhere.getStatus(), is(403));
    assertThat(returnsOf(T_ON), is(returns));
  }

  // ── pricing, a retry, another business ────────────────────────────────────

  @Test
  @DisplayName("With pricing unreachable nothing is guessed and nothing is written")
  void pricingDownIsRefused() {
    long returns = returnsOf(T_ON);
    long cards = cardsOf(T_ON);
    PRICING_DOWN.set(true);
    try {
      Response r =
          noReceipt(
              body("GIFT_CARD", 1, "SEALED", ""), T_ON, "MANAGER", MANAGER, Ids.newId().toString());
      assertThat(r.getStatus(), is(503));
      assertThat(r.readEntity(String.class), containsString("ORDER_PRICING_UNAVAILABLE"));
    } finally {
      PRICING_DOWN.set(false);
    }
    assertThat(returnsOf(T_ON), is(returns));
    assertThat(cardsOf(T_ON), is(cards));
  }

  @Test
  @DisplayName("A retry with the same key answers with the first return and writes nothing")
  void aRetryAnswersWithTheFirst() {
    String key = Ids.newId().toString();
    String json = body("GIFT_CARD", 1, "SEALED", "");
    long returns = returnsOf(T_ON);
    long cards = cardsOf(T_ON);

    JsonObject first = data(noReceipt(json, T_ON, "MANAGER", MANAGER, key), 201);
    JsonObject again = data(noReceipt(json, T_ON, "MANAGER", MANAGER, key), 201);

    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(
        again.getJsonNumber("refundAmount").bigDecimalValue(),
        is(first.getJsonNumber("refundAmount").bigDecimalValue()));
    assertThat(returnsOf(T_ON), is(returns + 1));
    assertThat(cardsOf(T_ON), is(cards + 1));
    assertThat(rig().events(first.getString("id"), "NoReceiptReturnRecorded"), is(1L));

    // Even after the business turns the option off, the first answer stands.
    // (Set back straight after: other tests share this business.)
    rig()
        .put(
            "/admin/return-policy",
            "{\"windowDays\":30,\"noReceiptAllowed\":false}",
            T_ON,
            "OWNER",
            MANAGER);
    try {
      JsonObject late = data(noReceipt(json, T_ON, "MANAGER", MANAGER, key), 201);
      assertThat(late.getString("id"), is(first.getString("id")));
    } finally {
      rig()
          .put(
              "/admin/return-policy",
              "{\"windowDays\":30,\"noReceiptAllowed\":true,\"noReceiptCeiling\":30.00}",
              T_ON,
              "OWNER",
              MANAGER);
    }
    // The key is not for another store's return.
    Response other = noReceipt(json.replace(STORE, OTHER_STORE), T_ON, "MANAGER", MANAGER, key);
    assertThat(other.getStatus(), is(409));
  }

  @Test
  @DisplayName("Another business cannot see our return, and its own is its own")
  void anotherBusinessCannotSeeIt() {
    JsonObject ours =
        data(
            noReceipt(
                body("STORE_CREDIT", 1, "SEALED", creditExtra()),
                T_ON,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
            201);
    String id = ours.getString("id");

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Response trail =
          rig().get("/admin/audit/events?type=RETURN&limit=100", OTHER_T, role, MANAGER);
      String text = trail.readEntity(String.class);
      assertThat(role, trail.getStatus(), is(200));
      assertThat(role, text, not(containsString(id)));
    }
    assertThat(rig().count("returns", "id='" + id + "' AND tenant_id='" + OTHER_T + "'"), is(0L));

    // Their manager's own return, naming our store id, is recorded under their business alone.
    long theirs = returnsOf(OTHER_T);
    JsonObject mine =
        data(
            noReceipt(
                body("STORE_CREDIT", 1, "SEALED", creditExtra()),
                OTHER_T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
            201);
    assertThat(returnsOf(OTHER_T), is(theirs + 1));
    assertThat(
        rig().count("returns", "id='" + mine.getString("id") + "' AND tenant_id='" + T_ON + "'"),
        is(0L));
  }
}
