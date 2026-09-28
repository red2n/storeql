package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.test.Envelopes;
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
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Supplier lead-time tracking and scorecards (readiness review, Procurement &amp; supplier
 * management).
 *
 * <p>Every goods receipt measures the delivery against the order's promise — the date the order
 * named, or the supplier's quoted lead time — and a period's deliveries, fill, returns and invoice
 * accuracy are weighed into one scorecard per supplier, ranked. Written before the code.
 */
@HelidonTest
class SupplierScorecardIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String T = PurchaseFixtures.T;
  private static final String T2 = PurchaseFixtures.T2;
  private static final String STORE = PurchaseFixtures.STORE_A;
  private static final String VARIANT = PurchaseFixtures.VARIANT;
  private static final String USER = PurchaseFixtures.USER;

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER");
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER");
  }

  private String supplier(String name, Integer leadTimeDays) {
    return Envelopes.created(
            post(
                "/suppliers",
                "{\"name\":\""
                    + name
                    + "\",\"vatRegistered\":true,\"currency\":\"GBP\",\"countryCode\":\"GB\""
                    + (leadTimeDays == null ? "" : ",\"leadTimeDays\":" + leadTimeDays)
                    + "}"))
        .getString("id");
  }

  /** An order of {@code qty} at 20.00, submitted, its submission back-dated {@code daysAgo}. */
  private String submittedOrder(String supplierId, int qty, String expectedDelivery, int daysAgo) {
    String poId =
        Envelopes.created(
                post(
                    "/purchase-orders",
                    "{\"supplierId\":\""
                        + supplierId
                        + "\",\"storeId\":\""
                        + STORE
                        + "\",\"currency\":\"GBP\""
                        + (expectedDelivery == null
                            ? ""
                            : ",\"expectedDelivery\":\"" + expectedDelivery + "\"")
                        + "}"))
            .getString("id");
    assertThat(
        post("/purchase-orders/" + poId + "/lines", PurchaseFixtures.lineJson(qty, "20.00"))
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}").getStatus(), is(200));
    // Time passes: the order went to the supplier some days ago.
    Envelopes.exec(
        PG,
        "UPDATE purchase.purchase_orders SET submitted_at = now() - interval '"
            + daysAgo
            + " days' WHERE id = '"
            + poId
            + "'");
    return poId;
  }

  /** JSON-B leaves a null out altogether, so "unknown" is an absent member or an explicit null. */
  private static boolean absent(JsonObject o, String field) {
    return !o.containsKey(field) || o.isNull(field);
  }

  private static BigDecimal num(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private static JsonObject find(JsonArray rows, String field, String value) {
    for (JsonValue v : rows) {
      if (value.equals(v.asJsonObject().getString(field))) return v.asJsonObject();
    }
    throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
  }

  // ── measured against the promise, weighed into a score ────────────────────

  @Test
  void deliveriesAreMeasuredAgainstThePromiseAndThePeriodIsWeighedIntoAScorecard() {
    String butcher = supplier("Highland Meats", 3);
    String idle = supplier("Quiet Farm", null);
    String today = LocalDate.now().toString();

    // Ordered five days ago against a three-day quote: five days' lead, two days late, complete.
    String po1 = submittedOrder(butcher, 10, null, 5);
    assertThat(post("/goods-receipts", PurchaseFixtures.receiptJson(po1, 10)).getStatus(), is(201));
    // Ordered two days ago for tomorrow: six of ten arrive early, the rest never will.
    String po2 = submittedOrder(butcher, 10, LocalDate.now().plusDays(1).toString(), 2);
    assertThat(post("/goods-receipts", PurchaseFixtures.receiptJson(po2, 6)).getStatus(), is(201));
    assertThat(
        post("/purchase-orders/" + po2 + "/close", "{\"reason\":\"the supplier cannot complete\"}")
            .getStatus(),
        is(200));
    // Two of the first ten go back damaged; the first order is invoiced as agreed.
    assertThat(
        post(
                "/vendor-returns",
                "{\"poId\":\""
                    + po1
                    + "\",\"reason\":\"DAMAGED\",\"lines\":[{\"variantId\":\""
                    + VARIANT
                    + "\",\"qty\":2}]}")
            .getStatus(),
        is(201));
    assertThat(
        post(
                "/supplier-invoices",
                PurchaseFixtures.invoiceJson(po1, "INV-1", today, 10, "20.00", "40.00"))
            .getStatus(),
        is(201));

    // The deliveries as measured, newest first.
    JsonArray deliveries =
        Envelopes.okArray(get("/suppliers/" + butcher + "/deliveries?from=2026-01-01&to=" + today));
    assertThat(deliveries.size(), is(2));
    JsonObject first = find(deliveries, "poId", po1);
    assertThat(first.getInt("leadDays"), is(5));
    assertThat(first.getInt("lateDays"), is(2));
    assertThat(first.getBoolean("complete"), is(true));
    assertThat(num(first, "receivedQty"), comparesEqualTo(new BigDecimal("10")));
    assertThat(first.getString("promisedDate"), is(LocalDate.now().minusDays(2).toString()));
    JsonObject second = find(deliveries, "poId", po2);
    assertThat(second.getInt("leadDays"), is(2));
    assertThat(second.getInt("lateDays"), is(-1));
    assertThat(second.getBoolean("complete"), is(false));
    assertThat(deliveries.getJsonObject(0).getString("poId"), is(po2));

    // The scorecard: the period weighed.
    JsonObject card =
        Envelopes.ok(get("/suppliers/" + butcher + "/scorecard?from=2026-01-01&to=" + today));
    assertThat(card.getString("supplierName"), is("Highland Meats"));
    assertThat(card.getInt("leadTimeDays"), is(3));
    JsonObject d = card.getJsonObject("deliveries");
    assertThat(d.getInt("count"), is(2));
    assertThat(num(d, "avgLeadDays"), comparesEqualTo(new BigDecimal("3.5")));
    assertThat(num(d, "medianLeadDays"), comparesEqualTo(new BigDecimal("3.5")));
    assertThat(d.getInt("maxLeadDays"), is(5));
    assertThat(d.getInt("promised"), is(2));
    assertThat(d.getInt("onTime"), is(1));
    assertThat(d.getInt("late"), is(1));
    assertThat(num(d, "onTimePct"), comparesEqualTo(new BigDecimal("50")));
    assertThat(num(d, "avgDaysLate"), comparesEqualTo(new BigDecimal("2")));
    assertThat(num(d, "receivedQty"), comparesEqualTo(new BigDecimal("16")));
    JsonObject f = card.getJsonObject("fill");
    assertThat(f.getInt("orders"), is(2));
    assertThat(num(f, "orderedQty"), comparesEqualTo(new BigDecimal("20")));
    assertThat(num(f, "receivedQty"), comparesEqualTo(new BigDecimal("16")));
    assertThat(num(f, "fillRatePct"), comparesEqualTo(new BigDecimal("80")));
    assertThat(f.getInt("shortClosed"), is(1));
    JsonObject q = card.getJsonObject("quality");
    assertThat(q.getInt("returns"), is(1));
    assertThat(num(q, "returnedQty"), comparesEqualTo(new BigDecimal("2")));
    assertThat(num(q, "returnRatePct"), comparesEqualTo(new BigDecimal("12.5")));
    JsonObject inv = card.getJsonObject("invoices");
    assertThat(inv.getInt("invoices"), is(1));
    assertThat(inv.getInt("flagged"), is(0));
    assertThat(num(inv, "accuracyPct"), comparesEqualTo(new BigDecimal("100")));
    // 40 % of 50 on time, 30 % of 80 fill, 20 % of 87.5 quality, 10 % of 100 accuracy.
    assertThat(num(card, "score"), comparesEqualTo(new BigDecimal("71.5")));
    assertThat(card.getString("grade"), is("C"));

    // Ranked, the idle supplier last with no score; unseen by another business; not for a till.
    JsonArray ranked = Envelopes.okArray(get("/suppliers/scorecards?from=2026-01-01&to=" + today));
    assertThat(ranked.size(), is(2));
    assertThat(ranked.getJsonObject(0).getString("supplierId"), is(butcher));
    assertThat(ranked.getJsonObject(1).getString("supplierId"), is(idle));
    assertThat(absent(ranked.getJsonObject(1), "score"), is(true));
    assertThat(ranked.getJsonObject(1).getJsonObject("deliveries").getInt("count"), is(0));
    assertThat(
        Envelopes.okArray(
                call("GET", "/suppliers/scorecards?from=2026-01-01&to=" + today, null, T2, "OWNER"))
            .size(),
        is(0));
    assertThat(
        call("GET", "/suppliers/" + butcher + "/scorecard", null, T, "CASHIER").getStatus(),
        is(403));
    assertThat(
        call("GET", "/suppliers/scorecards?from=2026-02-01&to=2026-01-01", null, T, "OWNER")
            .getStatus(),
        is(400));
  }

  // ── nothing promised: measured, never judged late ──────────────────────────

  @Test
  void aDeliveryWithNoPromiseIsMeasuredButNotJudgedAndTheScoreWeighsWhatIsKnown() {
    String grower = supplier("Fen Growers", null);
    String today = LocalDate.now().toString();
    String po = submittedOrder(grower, 10, null, 0);
    assertThat(post("/goods-receipts", PurchaseFixtures.receiptJson(po, 10)).getStatus(), is(201));

    JsonArray deliveries =
        Envelopes.okArray(get("/suppliers/" + grower + "/deliveries?from=2026-01-01&to=" + today));
    assertThat(deliveries.size(), is(1));
    JsonObject only = deliveries.getJsonObject(0);
    assertThat(only.getInt("leadDays"), is(0));
    assertThat(absent(only, "promisedDate"), is(true));
    assertThat(absent(only, "lateDays"), is(true));

    JsonObject card = Envelopes.ok(get("/suppliers/" + grower + "/scorecard"));
    assertThat(absent(card, "leadTimeDays"), is(true));
    JsonObject d = card.getJsonObject("deliveries");
    assertThat(d.getInt("count"), is(1));
    assertThat(d.getInt("promised"), is(0));
    assertThat(absent(d, "onTimePct"), is(true));
    assertThat(
        num(card.getJsonObject("fill"), "fillRatePct"), comparesEqualTo(new BigDecimal("100")));
    assertThat(
        num(card.getJsonObject("quality"), "returnRatePct"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(absent(card.getJsonObject("invoices"), "accuracyPct"), is(true));
    // Fill and quality alone carry the score.
    assertThat(num(card, "score"), comparesEqualTo(new BigDecimal("100")));
    assertThat(card.getString("grade"), is("A"));
    // The quote can be set later, and reads back on the supplier.
    JsonObject updated =
        Envelopes.ok(
            call(
                "PUT",
                "/suppliers/" + grower,
                "{\"name\":\"Fen Growers\",\"vatRegistered\":true,\"leadTimeDays\":2}",
                T,
                "OWNER"));
    assertThat(updated.getInt("leadTimeDays"), is(2));
    assertThat(
        Envelopes.ok(get("/suppliers/" + grower + "/scorecard")).getInt("leadTimeDays"), is(2));
    assertThat(absent(deliveries.getJsonObject(0), "promisedDate"), is(true));
  }
}
