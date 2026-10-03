package com.storeql.purchase;

import static com.storeql.purchase.PurchaseFixtures.STORE_A;
import static com.storeql.purchase.PurchaseFixtures.T;
import static com.storeql.purchase.PurchaseFixtures.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.events.contract.GiftCardLoadReversed;
import com.storeql.ids.Ids;
import com.storeql.purchase.messaging.DeferredRevenueHandler;
import com.storeql.purchase.messaging.SalesEventHandler;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A gift card sold in a sale and taken back when the sale is voided or cancelled (till-sessions
 * slice 8): {@code GiftCardLoadReversed} posts the opposite of the sale-loaded posting, once per
 * event, and with the refund the sale nets to nothing on every account. Kafka is off, so the
 * handlers are driven with the payloads the producers write (the reversal through its contract).
 */
@HelidonTest
class GiftCardReversalIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  @Inject WebTarget target;
  @Inject SalesEventHandler sales;
  @Inject DeferredRevenueHandler cards;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncateTables() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  private static String confirmed(String order, String total, String tax) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + STORE_A
        + "\",\"channel\":\"POS\",\"customerId\":null,\"total\":"
        + total
        + ",\"taxAmount\":"
        + tax
        + ",\"currency\":\"GBP\"}";
  }

  private static String captured(String order, String amount) {
    return "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\""
        + T
        + "\",\"paymentId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + order
        + "\",\"amount\":"
        + amount
        + ",\"method\":\"CASH\",\"storeId\":\""
        + STORE_A
        + "\"}";
  }

  private static String refunded(String order, String amount) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\""
        + T
        + "\",\"refundId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + order
        + "\",\"amount\":"
        + amount
        + ",\"tenders\":[{\"paymentId\":\""
        + Ids.newId()
        + "\",\"method\":\"CASH\",\"amount\":"
        + amount
        + "}]}";
  }

  private static String cardSold(String card, String order, String amount) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"GiftCardLoaded\",\"tenantId\":\""
        + T
        + "\",\"giftCardId\":\""
        + card
        + "\",\"transactionId\":\""
        + Ids.newId()
        + "\",\"kind\":\"ISSUE\",\"amount\":"
        + amount
        + ",\"currency\":\"GBP\",\"paidBy\":\"CASH\",\"storeId\":\""
        + STORE_A
        + "\",\"orderId\":\""
        + order
        + "\",\"source\":\"SALE\"}";
  }

  private static String reversal(String card, String order, String amount, String at) {
    return GiftCardLoadReversed.payload(
        Ids.parse(T),
        Ids.parse(card),
        Ids.parse(order),
        new BigDecimal(amount),
        "GBP",
        GiftCardLoadReversed.SOURCE_SALE,
        Instant.parse(at),
        Ids.parse(STORE_A));
  }

  @Test
  @DisplayName("A card-only cash sale, paid then voided, leaves 1105, 2310 and cash where it began")
  void aCardOnlySaleVoidedNetsToNothing() {
    String order = Ids.newId().toString();
    String card = Ids.newId().toString();
    sales.paymentCaptured(captured(order, "25.00"));
    sales.orderConfirmed(confirmed(order, "0", "0"));
    cards.giftCardLoaded(cardSold(card, order, "25.00"));
    same(row(trialBalance(), "2310"), "-25.00");
    same(row(trialBalance(), "1210"), "25.00");

    String reversed = reversal(card, order, "25.00", "2026-10-01T09:30:00Z");
    cards.giftCardLoadReversed(reversed);
    sales.paymentRefunded(refunded(order, "25.00"));

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1105"), "0");
    same(row(tb, "2310"), "0");
    same(row(tb, "1210"), "0");
    same(row(tb, "4010"), "0");
    assertThat(clearing().size(), is(0));
    int reversalLines = 0;
    for (JsonValue v : lines("GIFT_CARD_LOAD")) {
      JsonObject line = v.asJsonObject();
      if (line.getString("description").startsWith("Gift card load reversed")) {
        reversalLines++;
        // Dated the reversal, not the day the event was read.
        assertThat(line.getString("entryDate"), is("2026-10-01"));
        assertThat(line.getString("sourceRef"), is(order));
      }
    }
    assertThat(reversalLines, is(2));
  }

  @Test
  @DisplayName("A sale of goods and a card voided: revenue, VAT, clearing, liability and cash net")
  void aMixedSaleVoidedNetsToNothing() {
    String order = Ids.newId().toString();
    String card = Ids.newId().toString();
    sales.paymentCaptured(captured(order, "100.00"));
    sales.orderConfirmed(confirmed(order, "60.00", "10.00"));
    cards.giftCardLoaded(cardSold(card, order, "40.00"));
    cards.giftCardLoadReversed(reversal(card, order, "40.00", "2026-10-01T09:30:00Z"));
    sales.paymentRefunded(refunded(order, "100.00"));

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    for (String code : new String[] {"1105", "1210", "2310", "4010", "2200"}) {
      same(row(tb, code), "0");
    }
    assertThat(clearing().size(), is(0));
  }

  @Test
  @DisplayName("The same reversal twice posts once; a malformed one posts nothing")
  void aRedeliveredReversalPostsOnce() {
    String order = Ids.newId().toString();
    String card = Ids.newId().toString();
    cards.giftCardLoaded(cardSold(card, order, "25.00"));
    String reversed = reversal(card, order, "25.00", "2026-10-01T09:30:00Z");
    cards.giftCardLoadReversed(reversed);
    cards.giftCardLoadReversed(reversed);
    assertThat(lines("GIFT_CARD_LOAD").size(), is(4));
    same(row(trialBalance(), "2310"), "0");
    same(row(trialBalance(), "1105"), "0");

    cards.giftCardLoadReversed("{not json");
    cards.giftCardLoadReversed(reversed.replace("\"amount\":25.00", "\"amount\":\"x\""));
    cards.giftCardLoadReversed(reversed.replace("\"giftCardId\"", "\"other\""));
    assertThat(lines("GIFT_CARD_LOAD").size(), is(4));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Response get(String pathAndQuery) {
    return com.storeql.test.WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .get();
  }

  private JsonArray clearing() {
    return dataArray(get("/nominal-ledger/sales-clearing"));
  }

  private JsonObject trialBalance() {
    Response r = get("/nominal-ledger/trial-balance");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private JsonArray lines(String sourceType) {
    var out = Json.createArrayBuilder();
    for (JsonValue v : dataArray(get("/nominal-ledger?limit=100"))) {
      if (sourceType.equals(v.asJsonObject().getString("sourceType", null))) out.add(v);
    }
    return out.build();
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  private static BigDecimal row(JsonObject trialBalance, String code) {
    for (JsonValue v : trialBalance.getJsonArray("rows")) {
      if (code.equals(v.asJsonObject().getString("nominalCode"))) {
        return v.asJsonObject().getJsonNumber("balance").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  private static void same(BigDecimal actual, String expected) {
    assertThat(actual + " vs " + expected, actual.compareTo(new BigDecimal(expected)), is(0));
  }
}
