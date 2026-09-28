package com.storeql.purchase;

import static com.storeql.purchase.PurchaseFixtures.STORE_A;
import static com.storeql.purchase.PurchaseFixtures.T;
import static com.storeql.purchase.PurchaseFixtures.T2;
import static com.storeql.purchase.PurchaseFixtures.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.purchase.messaging.DeferredRevenueHandler;
import com.storeql.purchase.messaging.SalesEventHandler;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
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
 * Deferred revenue for loyalty points and gift card breakage (17.11). Kafka is off here, so the
 * handlers are driven with the payloads customer-svc, order-svc and payment-svc publish; the live
 * path is driven by {@code k6/deferred-revenue}. A point is worth 0.05, a fifth of points and a
 * tenth of gift card value are expected never to be used.
 */
@HelidonTest
class DeferredRevenueIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String ESTIMATES =
      "{\"pointValue\":0.05,\"pointsBreakagePct\":20,\"giftCardBreakagePct\":10,"
          + "\"reason\":\"Two years of scheme data\"}";

  @Inject WebTarget target;
  @Inject DeferredRevenueHandler handler;
  @Inject SalesEventHandler sales;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncateTables() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── payloads as the producers write them ────────────────────────────────────

  /** {@code LoyaltyEarned} for a sale of 120.00 with 20.00 VAT. */
  private static String earnedOnSale(String points, String total, String tax) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"LoyaltyEarned\",\"customerId\":\""
        + Ids.newId()
        + "\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + Ids.newId()
        + "\",\"points\":"
        + points
        + ",\"orderTotal\":"
        + total
        + ",\"orderTaxAmount\":"
        + tax
        + "}";
  }

  private static String loyalty(String type, String points) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\",\"customerId\":\""
        + Ids.newId()
        + "\",\"tenantId\":\""
        + T
        + "\",\"points\":"
        + points
        + "}";
  }

  private static String loaded(String kind, String paidBy, String amount) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"GiftCardLoaded\",\"tenantId\":\""
        + T
        + "\",\"giftCardId\":\""
        + Ids.newId()
        + "\",\"transactionId\":\""
        + Ids.newId()
        + "\",\"kind\":\""
        + kind
        + "\",\"amount\":"
        + amount
        + ",\"currency\":\"GBP\",\"paidBy\":\""
        + paidBy
        + "\",\"storeId\":\""
        + STORE_A
        + "\"}";
  }

  private static String tender(String method, String amount) {
    return "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\""
        + T
        + "\",\"paymentId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + Ids.newId()
        + "\",\"amount\":"
        + amount
        + ",\"method\":\""
        + method
        + "\",\"storeId\":\""
        + STORE_A
        + "\"}";
  }

  // ── the estimates ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("The estimates are management's, refused by name when out of range, and kept")
  void estimatesAreManagementsAndRefusedByName() {
    assertThat(put(ESTIMATES, T, "CASHIER").getStatus(), is(403));
    assertThat(put(ESTIMATES, T, "STOREKEEPER").getStatus(), is(403));
    assertThat(get(T, "CASHIER").getStatus(), is(403));
    assertRefused(ESTIMATES.replace("0.05", "0"), "PURCHASE_POINT_VALUE_INVALID");
    assertRefused(ESTIMATES.replace("0.05", "1000.5"), "PURCHASE_POINT_VALUE_INVALID");
    assertRefused(ESTIMATES.replace("0.05", "0.00001"), "PURCHASE_POINT_VALUE_INVALID");
    assertRefused(ESTIMATES.replace(":20,", ":96,"), "PURCHASE_BREAKAGE_OUT_OF_RANGE");
    assertRefused(ESTIMATES.replace(":10,", ":-1,"), "PURCHASE_BREAKAGE_OUT_OF_RANGE");
    assertThat(
        put(ESTIMATES.replace("Two years of scheme data", " "), T, "OWNER").getStatus(), is(400));
    assertThat(put(ESTIMATES.replace("0.05", "\"lots\""), T, "OWNER").getStatus(), is(400));
    assertThat(data(get(T, "OWNER")).containsKey("settings"), is(false));

    JsonObject first = data(put(ESTIMATES, T, "OWNER"));
    assertThat(first.getJsonObject("settings").getString("currency"), is("GBP"));
    JsonObject second = data(put(ESTIMATES.replace("0.05", "0.04"), T, "MANAGER"));
    assertThat(second.getJsonArray("history").size(), is(2));
    assertThat(
        second.getJsonObject("settings").getJsonNumber("pointValue").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("0.04")));
    JsonObject rival = data(get(T2, "OWNER"));
    assertThat(rival.containsKey("settings"), is(false));
    assertThat(rival.getJsonArray("history").size(), is(0));
  }

  // ── loyalty points ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("Points earned before any estimates wait, and are posted the moment they are set")
  void pointsWaitForEstimatesThenPost() {
    handler.loyalty(earnedOnSale("200", "120.00", "20.00"));
    assertThat(lines("LOYALTY_DEFERRAL").size(), is(0));
    assertThat(data(get(T, "OWNER")).getJsonNumber("eventsAwaitingEstimates").longValue(), is(1L));

    JsonObject after = data(put(ESTIMATES, T, "OWNER"));
    assertThat(after.getJsonNumber("eventsAwaitingEstimates").longValue(), is(0L));
    // 100.00 net × 8.00 / 108.00 = 7.41 out of sales into deferred income.
    assertThat(
        after.getJsonNumber("deferredIncome").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("7.41")));
    assertThat(net("LOYALTY_DEFERRAL", "4010"), comparesEqualTo(new BigDecimal("7.41")));
    assertThat(net("LOYALTY_DEFERRAL", "2330"), comparesEqualTo(new BigDecimal("-7.41")));
    assertThat(trialBalance().getBoolean("balanced"), is(true));
  }

  @Test
  @DisplayName("A loyalty event delivered twice is posted once")
  void aRedeliveredEventPostsOnce() {
    data(put(ESTIMATES, T, "OWNER"));
    String event = earnedOnSale("200", "120.00", "20.00");
    handler.loyalty(event);
    handler.loyalty(event);
    assertThat(lines("LOYALTY_DEFERRAL").size(), is(2));
    assertThat(net("LOYALTY_DEFERRAL", "2330"), comparesEqualTo(new BigDecimal("-7.41")));
  }

  @Test
  @DisplayName(
      "Spending releases income, the last lapse is breakage, a redemption read early is settled")
  void spendingLapsingAndOutOfOrder() {
    data(put(ESTIMATES, T, "OWNER"));
    handler.loyalty(loyalty("LoyaltyRedeemed", "50"));
    assertThat(lines("LOYALTY_RELEASE").size(), is(0));

    handler.loyalty(earnedOnSale("200", "120.00", "20.00"));
    // The 50 already spent release 7.41 × 50 / 200 = 1.85 in the earning's journal.
    assertThat(net("LOYALTY_DEFERRAL", "4020"), comparesEqualTo(new BigDecimal("-1.85")));

    // 60 of the 120 expected: 5.56 × 60 / 120 = 2.78.
    handler.loyalty(loyalty("LoyaltyRedeemed", "60"));
    assertThat(net("LOYALTY_RELEASE", "4020"), comparesEqualTo(new BigDecimal("-2.78")));

    // The rest lapse: nothing remains, so the 2.78 left is breakage.
    handler.loyalty(loyalty("LoyaltyAdjusted", "-90"));
    assertThat(net("LOYALTY_RELEASE", "4030"), comparesEqualTo(new BigDecimal("-2.78")));
    JsonObject view = data(get(T, "OWNER"));
    assertThat(
        view.getJsonNumber("deferredIncome").bigDecimalValue(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(
        view.getJsonNumber("pointsOutstanding").bigDecimalValue(),
        comparesEqualTo(BigDecimal.ZERO));

    // Points given away are a cost at their expected value: 100 × 0.05 × 80% = 4.00.
    handler.loyalty(loyalty("LoyaltyAdjusted", "100"));
    assertThat(net("LOYALTY_DEFERRAL", "6410"), comparesEqualTo(new BigDecimal("4.00")));
    assertThat(trialBalance().getBoolean("balanced"), is(true));
  }

  @Test
  @DisplayName(
      "Points that expire leave the pool as a lapse, and the income they carried is breakage once nothing is outstanding")
  void expiredPointsAreBreakage() {
    data(put(ESTIMATES, T, "OWNER"));
    handler.loyalty(earnedOnSale("200", "120.00", "20.00"));
    BigDecimal deferred = data(get(T, "OWNER")).getJsonNumber("deferredIncome").bigDecimalValue();
    assertThat(deferred.signum(), is(1));
    // Sixty spent: released as redeemed income, the pool keeping its share for the rest.
    handler.loyalty(loyalty("LoyaltyRedeemed", "60"));
    BigDecimal afterSpend = data(get(T, "OWNER")).getJsonNumber("deferredIncome").bigDecimalValue();
    // The other 140 die under the expiry rule: nothing outstanding, so what is left is breakage,
    // and none of it is redeemed income.
    handler.loyalty(loyalty("LoyaltyExpired", "140"));
    assertThat(net("LOYALTY_RELEASE", "4030"), comparesEqualTo(afterSpend.negate()));
    JsonObject view = data(get(T, "OWNER"));
    assertThat(
        view.getJsonNumber("deferredIncome").bigDecimalValue(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(
        view.getJsonNumber("pointsOutstanding").bigDecimalValue(),
        comparesEqualTo(BigDecimal.ZERO));
    assertThat(trialBalance().getBoolean("balanced"), is(true));
    // Redelivered, it posts nothing twice.
    int lines = lines("LOYALTY_RELEASE").size();
    handler.loyalty(loyalty("LoyaltyExpired", "140"));
    assertThat(lines("LOYALTY_RELEASE").size(), is(lines));
  }

  @Test
  @DisplayName(
      "Twenty redemptions at once release what their shares say, and never the same income twice")
  void concurrentRedemptionsKeepThePoolWhole() throws Exception {
    data(put(ESTIMATES, T, "OWNER"));
    // 1000.00 net, 1000 points × 0.05 × 80% = 40.00: 1000 × 40 / 1040 = 38.46 deferred.
    handler.loyalty(earnedOnSale("1000", "1200.00", "200.00"));

    var pool = Executors.newFixedThreadPool(20);
    var start = new CountDownLatch(1);
    List<Future<?>> done = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      String event = loyalty("LoyaltyRedeemed", "10");
      done.add(
          pool.submit(
              () -> {
                start.await();
                handler.loyalty(event);
                return null;
              }));
    }
    start.countDown();
    for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
    pool.shutdown();

    JsonObject view = data(get(T, "OWNER"));
    BigDecimal released = net("LOYALTY_RELEASE", "4020").negate();
    assertThat(
        view.getJsonNumber("pointsOutstanding").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("800")));
    assertThat(
        view.getJsonNumber("deferredIncome").bigDecimalValue().add(released),
        comparesEqualTo(new BigDecimal("38.46")));
    assertThat(lines("LOYALTY_RELEASE").size(), is(40));
    assertThat(trialBalance().getBoolean("balanced"), is(true));
  }

  // ── gift cards ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A gift card sold is a liability against the tender; spending it recognises breakage once")
  void giftCardsAreALiabilityWithBreakage() {
    String issue = loaded("ISSUE", "CARD", "100.00");
    handler.giftCardLoaded(issue);
    handler.giftCardLoaded(issue);
    handler.giftCardLoaded(loaded("ISSUE", "PROMOTIONAL", "20.00"));
    assertThat(net("GIFT_CARD_LOAD", "1250"), comparesEqualTo(new BigDecimal("100.00")));
    assertThat(net("GIFT_CARD_LOAD", "6420"), comparesEqualTo(new BigDecimal("20.00")));
    assertThat(net("GIFT_CARD_LOAD", "2310"), comparesEqualTo(new BigDecimal("-120.00")));

    // Spent before any estimates: the tender posts and no breakage is recognised.
    sales.paymentCaptured(tender("GIFT_CARD", "10.00"));
    assertThat(lines("GIFT_CARD_BREAKAGE").size(), is(0));

    data(put(ESTIMATES, T, "OWNER"));
    String spend = tender("GIFT_CARD", "45.00");
    sales.paymentCaptured(spend);
    sales.paymentCaptured(spend);
    // 45 × 10% / 90% = 5.00, within a tenth of the 120 loaded.
    assertThat(net("GIFT_CARD_BREAKAGE", "4031"), comparesEqualTo(new BigDecimal("-5.00")));
    assertThat(net("SALE_TENDER", "2310"), comparesEqualTo(new BigDecimal("55.00")));
    JsonObject view = data(get(T, "OWNER"));
    assertThat(
        view.getJsonNumber("giftCardsLoaded").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("120.00")));
    assertThat(
        view.getJsonNumber("giftCardsRedeemed").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("55.00")));
    assertThat(
        view.getJsonNumber("giftCardLiability").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("60.00")));

    // A card tender is not a gift card spent.
    sales.paymentCaptured(tender("CARD", "30.00"));
    assertThat(lines("GIFT_CARD_BREAKAGE").size(), is(2));
    assertThat(trialBalance().getBoolean("balanced"), is(true));
  }

  @Test
  @DisplayName(
      "Payloads that are not what the producers send, and events from before 17.11, post nothing")
  void malformedAndOldEventsPostNothing() {
    data(put(ESTIMATES, T, "OWNER"));
    handler.loyalty("{not json");
    handler.loyalty(
        "{\"customerId\":\"" + Ids.newId() + "\",\"tenantId\":\"" + T + "\",\"points\":10}");
    handler.loyalty(
        loyalty("LoyaltyEarned", "10")
            .replaceFirst("\"eventId\":\"[^\"]+\"", "\"eventId\":\"not-a-uuid\""));
    handler.loyalty(loyalty("LoyaltyRedeemed", "\"ten\""));
    handler.giftCardLoaded("{\"eventType\":\"GiftCardLoaded\",\"tenantId\":\"" + T + "\"}");
    handler.giftCardLoaded(loaded("ISSUE", "CARD", "0"));
    assertThat(data(get(T, "OWNER")).getJsonNumber("eventsAwaitingEstimates").longValue(), is(0L));
    assertThat(lines(null).size(), is(0));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Invocation.Builder request(String pathAndQuery, String tenant, String role) {
    return com.storeql.test.WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role);
  }

  private Response get(String tenant, String role) {
    return request("/nominal-ledger/deferred-revenue", tenant, role).get();
  }

  private Response put(String body, String tenant, String role) {
    return request("/nominal-ledger/deferred-revenue/settings", tenant, role)
        .put(Entity.json(body));
  }

  private void assertRefused(String body, String code) {
    Response r = put(body, T, "OWNER");
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(400));
    assertThat(text, containsString(code));
  }

  private static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data");
  }

  private JsonObject trialBalance() {
    return data(request("/nominal-ledger/trial-balance", T, "OWNER").get());
  }

  /** The ledger's lines for {@link PurchaseFixtures#T}, of one source type or all. */
  private List<JsonObject> lines(String sourceType) {
    String body = request("/nominal-ledger?limit=100", T, "OWNER").get().readEntity(String.class);
    List<JsonObject> out = new ArrayList<>();
    for (JsonValue v : parse(body).getJsonArray("data")) {
      JsonObject line = v.asJsonObject();
      if (sourceType == null || sourceType.equals(line.getString("sourceType", null))) {
        out.add(line);
      }
    }
    return out;
  }

  private BigDecimal net(String sourceType, String code) {
    return lines(sourceType).stream()
        .filter(l -> code.equals(l.getString("nominalCode")))
        .map(
            l ->
                l.getJsonNumber("debit")
                    .bigDecimalValue()
                    .subtract(l.getJsonNumber("credit").bigDecimalValue()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
