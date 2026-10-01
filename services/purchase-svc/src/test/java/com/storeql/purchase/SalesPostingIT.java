package com.storeql.purchase;

import static com.storeql.purchase.PurchaseFixtures.STORE_A;
import static com.storeql.purchase.PurchaseFixtures.T;
import static com.storeql.purchase.PurchaseFixtures.T2;
import static com.storeql.purchase.PurchaseFixtures.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sales and tender posting (17.7): what a sale's events write to the ledger. Kafka is off here, so
 * the handler is driven with the payloads order-svc and payment-svc publish; the live path is
 * driven by {@code k6/sales-posting}.
 */
@HelidonTest
class SalesPostingIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  @Inject WebTarget target;
  @Inject SalesEventHandler handler;
  @Inject com.storeql.purchase.messaging.DeferredRevenueHandler cards;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncateTables() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── payloads as the producers write them ────────────────────────────────────

  private static String confirmed(String tenant, String order, String total, String tax) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
        + tenant
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

  private static String captured(
      String tenant, String payment, String order, String amount, String method) {
    return "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\""
        + tenant
        + "\",\"paymentId\":\""
        + payment
        + "\",\"orderId\":\""
        + order
        + "\",\"amount\":"
        + amount
        + (method == null ? "" : ",\"method\":\"" + method + "\"")
        + ",\"storeId\":\""
        + STORE_A
        + "\"}";
  }

  private static String refunded(String tenant, String order, String amount, String shares) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\""
        + tenant
        + "\",\"refundId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + order
        + "\",\"amount\":"
        + amount
        + (shares == null ? "" : ",\"tenders\":[" + shares + "]")
        + "}";
  }

  // ── the sale ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A sale paid in cash and card posts revenue net of VAT and nets clearing, once")
  void aSplitTenderSaleIsPostedOnce() {
    String order = Ids.newId().toString();
    String cash = captured(T, Ids.newId().toString(), order, "70.00", "CASH");
    String card = captured(T, Ids.newId().toString(), order, "50.00", "CARD");
    String sale = confirmed(T, order, "120.00", "20.00");
    handler.paymentCaptured(cash);
    handler.paymentCaptured(card);
    handler.orderConfirmed(sale);

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1210"), "70.00");
    same(row(tb, "1250"), "50.00");
    same(row(tb, "4010"), "-100.00");
    same(row(tb, "2200"), "-20.00");
    same(row(tb, "1105"), "0");
    assertThat(lines("SALE").size(), is(3));
    assertThat(lines("SALE_TENDER").size(), is(4));
    for (JsonValue v : lines("SALE")) {
      assertThat(v.asJsonObject().getString("sourceRef"), is(order));
      assertThat(v.asJsonObject().getString("storeId"), is(STORE_A));
    }
    assertThat(clearing("").size(), is(0));

    // Redelivered, and a second announcement of the same sale under a new event id: nothing twice.
    handler.paymentCaptured(cash);
    handler.paymentCaptured(card);
    handler.orderConfirmed(sale);
    handler.orderConfirmed(confirmed(T, order, "120.00", "20.00"));
    assertThat(lines("SALE").size(), is(3));
    assertThat(lines("SALE_TENDER").size(), is(4));
  }

  // ── a gift card sold in a sale ──────────────────────────────────────────────

  /** The event order-svc writes for a card issued by the capture that completes a sale. */
  private static String cardSold(String tenant, String order, String amount, String paidBy) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"GiftCardLoaded\",\"tenantId\":\""
        + tenant
        + "\",\"giftCardId\":\""
        + Ids.newId()
        + "\",\"transactionId\":\""
        + Ids.newId()
        + "\",\"kind\":\"ISSUE\",\"amount\":"
        + amount
        + ",\"currency\":\"GBP\",\"paidBy\":\""
        + paidBy
        + "\",\"storeId\":\""
        + STORE_A
        + "\",\"orderId\":\""
        + order
        + "\",\"source\":\"SALE\"}";
  }

  @Test
  @DisplayName(
      "Goods plus a card paid in cash: the card's value credits the liability once, against"
          + " clearing, and the order nets to zero")
  void goodsPlusACardNetsClearingAndPostsTheLiabilityOnce() {
    String order = Ids.newId().toString();
    String tender = captured(T, Ids.newId().toString(), order, "100.00", "CASH");
    // OrderConfirmed is net of the 40.00 card: 60.00 of goods, 10.00 of VAT inside it.
    String sale = confirmed(T, order, "60.00", "10.00");
    String card = cardSold(T, order, "40.00", "CASH");
    handler.paymentCaptured(tender);
    handler.orderConfirmed(sale);
    cards.giftCardLoaded(card);

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1210"), "100.00");
    same(row(tb, "4010"), "-50.00");
    same(row(tb, "2200"), "-10.00");
    same(row(tb, "2310"), "-40.00");
    same(row(tb, "1105"), "0");
    assertThat(clearing("").size(), is(0));
    assertThat(lines("GIFT_CARD_LOAD").size(), is(2));
    assertThat(lines("GIFT_CARD_LOAD").get(0).asJsonObject().getString("sourceRef"), is(order));

    // Replayed: the same event, and the tender and sale again, move nothing.
    cards.giftCardLoaded(card);
    handler.paymentCaptured(tender);
    handler.orderConfirmed(sale);
    same(row(trialBalance(), "2310"), "-40.00");
    same(row(trialBalance(), "1210"), "100.00");
    assertThat(lines("GIFT_CARD_LOAD").size(), is(2));
    assertThat(clearing("").size(), is(0));
  }

  @Test
  @DisplayName("A split tender pays for goods and a card; the card is not debited to either tender")
  void aSplitTenderSaleWithACardNetsClearing() {
    String order = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "70.00", "CASH"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "50.00", "CARD"));
    // 120.00 taken: 80.00 of goods (VAT 10.00) and a 40.00 card. The capture that completed the
    // sale was the card tender, and that is the paidBy the event carries.
    handler.orderConfirmed(confirmed(T, order, "80.00", "10.00"));
    cards.giftCardLoaded(cardSold(T, order, "40.00", "CARD"));

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1210"), "70.00");
    same(row(tb, "1250"), "50.00");
    same(row(tb, "2310"), "-40.00");
    same(row(tb, "4010"), "-70.00");
    same(row(tb, "1105"), "0");
    assertThat(clearing("").size(), is(0));
  }

  @Test
  @DisplayName("A sale of a card alone (a confirmation of nothing) posts the liability and nets")
  void aCardOnlySaleNetsClearing() {
    String order = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "25.00", "CARD"));
    handler.orderConfirmed(confirmed(T, order, "0", "0"));
    // The order has no goods lines: a confirmation without any is read as it always was.
    handler.orderConfirmed(
        confirmed(T, order, "0", "0").replace("\"currency\"", "\"lines\":[],\"currency\""));
    cards.giftCardLoaded(cardSold(T, order, "25.00", "CARD"));

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1250"), "25.00");
    same(row(tb, "2310"), "-25.00");
    same(row(tb, "4010"), "0");
    same(row(tb, "1105"), "0");
    assertThat(lines("SALE").size(), is(0));
    assertThat(clearing("").size(), is(0));
  }

  @Test
  @DisplayName(
      "A card loaded by hand is value given away: the goodwill expense against the liability, with"
          + " the reason on the journal, once; it never touches clearing")
  void aHandLoadedCardIsAnExpense() {
    String order = Ids.newId().toString();
    String hand =
        cardSold(T, order, "15.00", "PROMOTIONAL")
            .replace(",\"orderId\":\"" + order + "\"", "")
            .replace("\"source\":\"SALE\"", "\"source\":\"GOODWILL\",\"note\":\"late delivery\"");
    cards.giftCardLoaded(hand);
    cards.giftCardLoaded(hand);
    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "6420"), "15.00");
    same(row(tb, "2310"), "-15.00");
    same(row(tb, "1105"), "0");
    JsonArray posted = lines("GIFT_CARD_LOAD");
    assertThat(posted.size(), is(2));
    assertThat(
        posted.get(0).asJsonObject().getString("description"),
        containsString("free of charge (GOODWILL): late delivery"));
    assertThat(clearing("").size(), is(0));
  }

  @Test
  @DisplayName("A refund takes back revenue and VAT in the sale's ratio and credits the tender")
  void aRefundReversesRevenueVatAndTheTender() {
    String order = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "120.00", "CARD"));
    handler.orderConfirmed(confirmed(T, order, "120.00", "20.00"));
    String refund =
        refunded(
            T,
            order,
            "30.00",
            "{\"paymentId\":\"" + Ids.newId() + "\",\"method\":\"CARD\",\"amount\":30.00}");
    handler.paymentRefunded(refund);
    handler.paymentRefunded(refund);

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "4010"), "-75.00");
    same(row(tb, "2200"), "-15.00");
    same(row(tb, "1250"), "90.00");
    same(row(tb, "1105"), "0");
    assertThat(lines("SALE_REFUND").size(), is(3));
  }

  @Test
  @DisplayName("Store credit, gift cards and an unmapped method each post to their own account")
  void liabilitiesAndUnmappedMethodsAreKeptApart() {
    String order = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "10.00", "STORE_CREDIT"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "15.00", "GIFT_CARD"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "5.00", "BARTER"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "1.00", null));
    handler.orderConfirmed(confirmed(T, order, "31.00", "0"));
    JsonObject tb = trialBalance();
    same(row(tb, "2320"), "10.00");
    same(row(tb, "2310"), "15.00");
    same(row(tb, "1299"), "6.00");
    same(row(tb, "1105"), "0");
    // A refund from before payment-svc sent shares goes to unallocated receipts, not a guess.
    handler.paymentRefunded(refunded(T, order, "2.00", null));
    same(row(trialBalance(), "1299"), "4.00");
  }

  @Test
  @DisplayName(
      "A return refunded to store credit or a gift card credits that liability, not the tender")
  void aReturnToStoreCreditOrGiftCardCreditsItsLiability() {
    String order = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "120.00", "CARD"));
    handler.orderConfirmed(confirmed(T, order, "120.00", "20.00"));

    String credit =
        refunded(
                T,
                order,
                "30.00",
                "{\"paymentId\":\""
                    + Ids.newId()
                    + "\",\"method\":\"STORE_CREDIT\",\"amount\":30.00}")
            .replace(
                "\"amount\":30.00}]",
                "\"amount\":30.00}],\"refundMethod\":\"STORE_CREDIT\",\"returnId\":\""
                    + Ids.newId()
                    + "\",\"customerId\":\""
                    + Ids.newId()
                    + "\"");
    handler.paymentRefunded(credit);
    handler.paymentRefunded(credit);

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "4010"), "-75.00");
    same(row(tb, "2200"), "-15.00");
    same(row(tb, "2320"), "-30.00");
    same(row(tb, "1250"), "120.00");
    same(row(tb, "1105"), "0");

    handler.paymentRefunded(
        refunded(
            T,
            order,
            "12.00",
            "{\"paymentId\":\"" + Ids.newId() + "\",\"method\":\"GIFT_CARD\",\"amount\":12.00}"));
    tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "2310"), "-12.00");
    same(row(tb, "2320"), "-30.00");
    same(row(tb, "4010"), "-65.00");
    same(row(tb, "2200"), "-13.00");
  }

  private static String noReceipt(String tenant, String returnId, String method, String amount) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"NoReceiptReturnRecorded\",\"tenantId\":\""
        + tenant
        + "\",\"returnId\":\""
        + returnId
        + "\",\"storeId\":\""
        + STORE_A
        + "\",\"currency\":\"GBP\",\"amount\":"
        + amount
        + ",\"taxAmount\":2.00,\"refundMethod\":\""
        + method
        + "\",\"approvedBy\":\""
        + USER
        + "\",\"items\":[]}";
  }

  @Test
  @DisplayName("An exchange refund and the new sale's EXCHANGE tender net to zero on 1260")
  void anExchangeNetsToZero() {
    String oldOrder = Ids.newId().toString();
    String newOrder = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), oldOrder, "120.00", "CARD"));
    handler.orderConfirmed(confirmed(T, oldOrder, "120.00", "20.00"));
    handler.paymentRefunded(
        refunded(
            T,
            oldOrder,
            "30.00",
            "{\"paymentId\":\"" + Ids.newId() + "\",\"method\":\"EXCHANGE\",\"amount\":30.00}"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), newOrder, "30.00", "EXCHANGE"));
    handler.orderConfirmed(confirmed(T, newOrder, "30.00", "5.00"));

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1260"), "0");
    same(row(tb, "2310"), "0");
    same(row(tb, "4030"), "0");
    same(row(tb, "1105"), "0");
    assertThat(clearing("").size(), is(0));
  }

  @Test
  @DisplayName("A no-receipt return posts sales and VAT against the liability once, per business")
  void aNoReceiptReturnIsPostedOnce() {
    String ret = Ids.newId().toString();
    String credit = noReceipt(T, ret, "STORE_CREDIT", "12.00");
    handler.noReceiptReturn(credit);
    handler.noReceiptReturn(credit);
    handler.noReceiptReturn(noReceipt(T, Ids.newId().toString(), "GIFT_CARD", "6.00"));

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "2320"), "-12.00");
    same(row(tb, "2310"), "-6.00");
    // Each return carries 2.00 of VAT: 18.00 credited, 4.00 of it VAT, 14.00 off sales.
    same(row(tb, "4010"), "14.00");
    same(row(tb, "2200"), "4.00");
    JsonArray posted = lines("NO_RECEIPT_RETURN");
    assertThat(posted.size(), is(6));
    assertThat(posted.getJsonObject(0).getString("storeId"), is(STORE_A));
    // Another business sees nothing.
    assertThat(dataArray(get("/nominal-ledger?limit=100", T2, "OWNER")).size(), is(0));
    // Malformed and unknown methods post nothing.
    handler.noReceiptReturn(noReceipt(T, Ids.newId().toString(), "CASH", "5.00"));
    handler.noReceiptReturn("{\"eventType\":\"NoReceiptReturnRecorded\"}");
    assertThat(lines("NO_RECEIPT_RETURN").size(), is(6));
  }

  // ── the clearing report: what did not net ───────────────────────────────────

  @Test
  @DisplayName("Money taken for a sale never confirmed stays open on clearing until it is refunded")
  void anUnconfirmedSaleIsReportedUntilRefunded() {
    String order = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "40.00", "CASH"));
    JsonArray open = clearing("");
    assertThat(open.size(), is(1));
    assertThat(open.getJsonObject(0).getString("orderId"), is(order));
    same(open.getJsonObject(0), "balance", "-40.00");
    assertThat(clearing("?storeId=" + STORE_A).size(), is(1));

    handler.paymentRefunded(
        refunded(
            T,
            order,
            "40.00",
            "{\"paymentId\":\"" + Ids.newId() + "\",\"method\":\"CASH\",\"amount\":40.00}"));
    assertThat(clearing("").size(), is(0));
    same(row(trialBalance(), "1210"), "0");
    same(row(trialBalance(), "4010"), "0");
  }

  // ── abuse and refusals ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Twenty deliveries of the same sale and tender at once post each once")
  void concurrentDeliveriesPostOnce() throws Exception {
    String order = Ids.newId().toString();
    String tender = captured(T, Ids.newId().toString(), order, "12.00", "CASH");
    String sale = confirmed(T, order, "12.00", "2.00");
    var pool = Executors.newFixedThreadPool(20);
    try {
      var start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  handler.paymentCaptured(tender);
                  handler.orderConfirmed(sale);
                  return null;
                }));
      }
      start.countDown();
      for (var f : futures) f.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(lines("SALE_TENDER").size(), is(2));
    assertThat(lines("SALE").size(), is(3));
    assertThat(trialBalance().getBoolean("balanced"), is(true));
  }

  // ── chargebacks (11.9) and settlement (11.10) ───────────────────────────────

  private static String dispute(String type, String order, String extra) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + T
        + "\",\"disputeId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + STORE_A
        + "\",\"amount\":40.00,\"feeAmount\":15.00,\"currency\":\"GBP\","
        + "\"fundsWithdrawn\":true"
        + extra
        + "}";
  }

  private static String settled(String batch, String stores) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"SettlementReconciled\",\"tenantId\":\""
        + T
        + "\",\"batchId\":\""
        + batch
        + "\",\"provider\":\"WORLDPAY\",\"reference\":\"WP-1\",\"currency\":\"GBP\","
        + "\"payoutDate\":\"2026-09-15\",\"netAmount\":42.50,\"stores\":["
        + stores
        + "]}";
  }

  @Test
  @DisplayName("A payout empties card clearing into the bank; a chargeback in it is booked once")
  void aReconciledPayoutClearsCardClearing() {
    String fine = Ids.newId().toString();
    String disputed = Ids.newId().toString();
    handler.paymentCaptured(captured(T, Ids.newId().toString(), fine, "60.00", "CARD"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), disputed, "40.00", "CARD"));
    // The bank takes the 40.00 back, with a fee of 15.00 (11.9) …
    handler.disputeFundsTaken(dispute("PaymentDisputeOpened", disputed, ""));
    same(row(trialBalance(), "1250"), "45.00");
    same(row(trialBalance(), "1255"), "40.00");
    same(row(trialBalance(), "6511"), "15.00");

    // … and the payout that covers both sales: 100.00 less 1.50, less the 55.00 already taken, and
    // a terminal rental of 1.00 that is no store's.
    String batch = Ids.newId().toString();
    String payout =
        settled(
            batch,
            "{\"storeId\":\""
                + STORE_A
                + "\",\"bank\":43.5000,\"fees\":1.5000,\"clearing\":45.0000,"
                + "\"unallocated\":0.0000},"
                + "{\"bank\":-1.0000,\"fees\":1.0000,\"clearing\":0,\"unallocated\":0.0000}");
    handler.settlementReconciled(payout);
    handler.settlementReconciled(payout);

    JsonObject tb = trialBalance();
    assertThat(tb.getBoolean("balanced"), is(true));
    same(row(tb, "1250"), "0");
    same(row(tb, "1200"), "42.50");
    same(row(tb, "6500"), "2.50");
    same(row(tb, "6511"), "15.00");
    JsonArray posted = lines("CARD_SETTLEMENT");
    assertThat(
        "two journals — three lines and two — once however often told", posted.size(), is(5));
    assertThat(posted.getJsonObject(0).getString("sourceRef"), is(batch));
    assertThat(posted.toString(), containsString("Card settlement WP-1 paid 2026-09-15"));

    // Lost: the 40.00 still in dispute is written off, and card clearing is left alone.
    handler.disputeClosed(dispute("PaymentDisputeClosed", disputed, ",\"outcome\":\"LOST\""));
    same(row(trialBalance(), "1255"), "0");
    same(row(trialBalance(), "6510"), "40.00");

    // What does not balance, or is not a settlement, posts nothing.
    handler.settlementReconciled(
        settled(
            Ids.newId().toString(),
            "{\"bank\":100.0000,\"fees\":1.0000,\"clearing\":100.0000,\"unallocated\":0}"));
    handler.settlementReconciled(settled(Ids.newId().toString(), "{\"bank\":1}"));
    handler.settlementReconciled(payout.replace("SettlementReconciled", "SettlementImported"));
    handler.settlementReconciled("{not json");
    assertThat(lines("CARD_SETTLEMENT").size(), is(5));
  }

  @Test
  @DisplayName("Malformed events post nothing; the report is management-only and per tenant")
  void malformedEventsAndRefusals() {
    String order = Ids.newId().toString();
    handler.orderConfirmed("{not json");
    handler.orderConfirmed(
        confirmed(T, order, "10.00", "1.00").replace(",\"currency\":\"GBP\"", ""));
    handler.orderConfirmed(confirmed(T, order, "10.00", "1.00").replace(",\"taxAmount\":1.00", ""));
    handler.orderConfirmed(confirmed(T, "not-a-uuid", "10.00", "1.00"));
    handler.paymentCaptured(captured(T, "not-a-uuid", order, "10.00", "CASH"));
    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "-10.00", "CASH"));
    handler.paymentRefunded("{\"eventType\":\"PaymentRefunded\"}");
    handler.orderConfirmed(
        confirmed(T, order, "10.00", "1.00").replace("OrderConfirmed", "OrderPlaced"));
    assertThat(lines("SALE").size(), is(0));
    assertThat(lines("SALE_TENDER").size(), is(0));
    assertThat(lines("SALE_REFUND").size(), is(0));

    handler.paymentCaptured(captured(T, Ids.newId().toString(), order, "9.00", "CASH"));
    assertThat(get("/nominal-ledger/sales-clearing", T, "STOREKEEPER").getStatus(), is(403));
    assertThat(get("/nominal-ledger/sales-clearing", T, "CASHIER").getStatus(), is(403));
    Response bad = get("/nominal-ledger/sales-clearing?storeId=nope", T, "OWNER");
    assertThat(bad.getStatus(), is(400));
    assertThat(dataArray(get("/nominal-ledger/sales-clearing", T2, "OWNER")).size(), is(0));
    assertThat(clearing("").size(), is(1));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Response get(String pathAndQuery, String tenant, String role) {
    return com.storeql.test.WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role)
        .get();
  }

  private JsonArray clearing(String query) {
    Response r = get("/nominal-ledger/sales-clearing" + query, T, "OWNER");
    return dataArray(r);
  }

  private JsonObject trialBalance() {
    Response r = get("/nominal-ledger/trial-balance", T, "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private JsonArray lines(String sourceType) {
    var out = Json.createArrayBuilder();
    for (JsonValue v : dataArray(get("/nominal-ledger?limit=100", T, "OWNER"))) {
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

  /** A code's balance on the trial balance, zero when it has no row. */
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

  private static void same(JsonObject o, String field, String expected) {
    same(o.getJsonNumber(field).bigDecimalValue(), expected);
  }
}
