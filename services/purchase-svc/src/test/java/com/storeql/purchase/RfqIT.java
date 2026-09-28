package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

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
 * RFQ and sourcing (readiness review, Procurement &amp; supplier management).
 *
 * <p>A buyer asks several suppliers to quote for the same lines, records what each says, compares
 * the quotes in the business's own money with each supplier's record beside them, and awards the
 * lines — which raises a draft order per supplier at the quoted prices. Written before the code.
 */
@HelidonTest
class RfqIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    // Pounds at home; a euro is worth 0.85 of them.
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB")
        .withFxRate(PurchaseFixtures.T, "EUR", "0.85");
  }

  private static final String T = PurchaseFixtures.T;
  private static final String T2 = PurchaseFixtures.T2;
  private static final String STORE = PurchaseFixtures.STORE_A;
  private static final String VARIANT_A = PurchaseFixtures.VARIANT;
  private static final String VARIANT_B = "01a090ae-611e-705c-994c-5daee3fbd034";
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

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static BigDecimal num(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private static JsonObject find(JsonArray rows, String field, String value) {
    for (JsonValue v : rows) {
      if (value.equals(v.asJsonObject().getString(field, null))) return v.asJsonObject();
    }
    throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
  }

  private String supplier(String name, String currency) {
    return Envelopes.created(
            post(
                "/suppliers",
                "{\"name\":\""
                    + name
                    + "\",\"vatRegistered\":true,\"currency\":\""
                    + currency
                    + "\",\"countryCode\":\"GB\",\"leadTimeDays\":3}"))
        .getString("id");
  }

  private JsonObject raise(String... supplierIds) {
    StringBuilder ids = new StringBuilder();
    for (String s : supplierIds)
      ids.append(ids.length() == 0 ? "" : ",").append('"').append(s).append('"');
    return Envelopes.created(
        post(
            "/rfqs",
            "{\"title\":\"Autumn beef\",\"storeId\":\""
                + STORE
                + "\",\"neededBy\":\""
                + LocalDate.now().plusDays(14)
                + "\",\"closesOn\":\""
                + LocalDate.now().plusDays(7)
                + "\",\"lines\":[{\"variantId\":\""
                + VARIANT_A
                + "\",\"qty\":10},{\"variantId\":\""
                + VARIANT_B
                + "\",\"qty\":5,\"notes\":\"vacuum packed\"}],\"supplierIds\":["
                + ids
                + "]}"));
  }

  private static String quote(String currency, String a, String b) {
    return "{\"currency\":\""
        + currency
        + "\",\"leadTimeDays\":4,\"validUntil\":\""
        + LocalDate.now().plusDays(30)
        + "\",\"lines\":[{\"variantId\":\""
        + VARIANT_A
        + "\",\"unitPrice\":"
        + a
        + "}"
        + (b == null ? "" : ",{\"variantId\":\"" + VARIANT_B + "\",\"unitPrice\":" + b + "}")
        + "]}";
  }

  // ── raised, issued, quoted, compared ───────────────────────────────────────

  @Test
  void anRfqIsRaisedIssuedQuotedAndComparedInTheBusinessesOwnMoney() {
    String s1 = supplier("Highland Meats", "GBP");
    String s2 = supplier("Boucherie Nord", "EUR");
    String s3 = supplier("Quiet Farm", "GBP");
    JsonObject rfq = raise(s1, s2, s3);
    String id = rfq.getString("id");
    assertThat(rfq.getString("reference"), is("RFQ-000001"));
    assertThat(rfq.getString("status"), is("DRAFT"));
    assertThat(rfq.getJsonArray("lines").size(), is(2));
    assertThat(rfq.getJsonArray("bids").size(), is(3));
    assertThat(find(rfq.getJsonArray("bids"), "supplierId", s2).getString("status"), is("INVITED"));
    assertThat(
        find(rfq.getJsonArray("bids"), "supplierId", s2).getString("supplierName"),
        is("Boucherie Nord"));
    // Refused by name: nothing to quote for, nobody to ask, a line twice, a supplier nobody has.
    assertThat(
        code(
            post(
                "/rfqs",
                "{\"title\":\"Empty\",\"storeId\":\""
                    + STORE
                    + "\",\"lines\":[],\"supplierIds\":[\""
                    + s1
                    + "\"]}"),
            400),
        is("PURCHASE_RFQ_LINES_REQUIRED"));
    assertThat(
        code(
            post(
                "/rfqs",
                "{\"title\":\"Nobody\",\"storeId\":\""
                    + STORE
                    + "\",\"lines\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"qty\":1}],\"supplierIds\":[]}"),
            400),
        is("PURCHASE_RFQ_SUPPLIERS_REQUIRED"));
    assertThat(
        code(
            post(
                "/rfqs",
                "{\"title\":\"Twice\",\"storeId\":\""
                    + STORE
                    + "\",\"lines\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"qty\":1},{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"qty\":2}],\"supplierIds\":[\""
                    + s1
                    + "\"]}"),
            400),
        is("PURCHASE_RFQ_LINE_DUPLICATE"));
    assertThat(
        code(
            post(
                "/rfqs",
                "{\"title\":\"Ghost\",\"storeId\":\""
                    + STORE
                    + "\",\"lines\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"qty\":1}],\"supplierIds\":[\""
                    + VARIANT_B
                    + "\"]}"),
            404),
        is("PURCHASE_SUPPLIER_NOT_FOUND"));
    // A quote before the request went out is refused; then it goes out.
    assertThat(
        code(
            call("PUT", "/rfqs/" + id + "/quotes/" + s1, quote("GBP", "10.00", "4.00"), T, "OWNER"),
            409),
        is("PURCHASE_RFQ_NOT_ISSUED"));
    JsonObject issued = Envelopes.ok(post("/rfqs/" + id + "/issue", "{}"));
    assertThat(issued.getString("status"), is("ISSUED"));
    assertThat(issued.getString("issuedAt", null), is(notNullValue()));
    assertThat(code(post("/rfqs/" + id + "/issue", "{}"), 409), is("PURCHASE_RFQ_NOT_DRAFT"));

    // Two quotes and a decline.
    Envelopes.ok(
        call("PUT", "/rfqs/" + id + "/quotes/" + s1, quote("GBP", "10.00", "4.00"), T, "OWNER"));
    Envelopes.ok(
        call("PUT", "/rfqs/" + id + "/quotes/" + s2, quote("EUR", "11.00", "5.00"), T, "OWNER"));
    JsonObject after = Envelopes.ok(post("/rfqs/" + id + "/quotes/" + s3 + "/decline", "{}"));
    assertThat(
        find(after.getJsonArray("bids"), "supplierId", s3).getString("status"), is("DECLINED"));
    JsonObject s1Bid = find(after.getJsonArray("bids"), "supplierId", s1);
    assertThat(s1Bid.getString("status"), is("QUOTED"));
    assertThat(s1Bid.getInt("leadTimeDays"), is(4));
    assertThat(s1Bid.getJsonArray("prices").size(), is(2));
    // Every bid carries the supplier's scorecard grade, so price is read against the record; a
    // supplier with no deliveries yet has nothing to judge and so no grade.
    assertThat(!s1Bid.containsKey("grade") || s1Bid.isNull("grade"), is(true));

    // Compared in pounds: the euro quote wins the first line at 9.35, the pound quote the second.
    JsonObject cmp = after.getJsonObject("comparison");
    assertThat(cmp.getString("homeCurrency"), is("GBP"));
    JsonArray lines = cmp.getJsonArray("lines");
    JsonObject lineA = find(lines, "variantId", VARIANT_A);
    JsonObject s2a = find(lineA.getJsonArray("prices"), "supplierId", s2);
    assertThat(num(s2a, "unitPrice"), comparesEqualTo(new BigDecimal("11.00")));
    assertThat(s2a.getString("currency"), is("EUR"));
    assertThat(num(s2a, "homeUnitPrice"), comparesEqualTo(new BigDecimal("9.35")));
    assertThat(num(s2a, "homeLineTotal"), comparesEqualTo(new BigDecimal("93.50")));
    assertThat(s2a.getBoolean("lowest"), is(true));
    assertThat(
        find(lineA.getJsonArray("prices"), "supplierId", s1).getBoolean("lowest"), is(false));
    JsonObject lineB = find(lines, "variantId", VARIANT_B);
    assertThat(find(lineB.getJsonArray("prices"), "supplierId", s1).getBoolean("lowest"), is(true));
    JsonArray bids = cmp.getJsonArray("bids");
    JsonObject s2Sum = find(bids, "supplierId", s2);
    assertThat(num(s2Sum, "total"), comparesEqualTo(new BigDecimal("135.00")));
    assertThat(num(s2Sum, "homeTotal"), comparesEqualTo(new BigDecimal("114.75")));
    assertThat(s2Sum.getInt("rank"), is(1));
    assertThat(s2Sum.getBoolean("complete"), is(true));
    JsonObject s1Sum = find(bids, "supplierId", s1);
    assertThat(num(s1Sum, "homeTotal"), comparesEqualTo(new BigDecimal("120.00")));
    assertThat(s1Sum.getInt("rank"), is(2));
    assertThat(find(bids, "supplierId", s3).containsKey("rank"), is(false));

    // Refused by name: a supplier nobody invited, a line nobody asked for, a quote with nothing on
    // it,
    // a cashier, another business.
    String stranger = supplier("Stranger", "GBP");
    assertThat(
        code(
            call(
                "PUT",
                "/rfqs/" + id + "/quotes/" + stranger,
                quote("GBP", "1.00", null),
                T,
                "OWNER"),
            400),
        is("PURCHASE_RFQ_SUPPLIER_NOT_INVITED"));
    assertThat(
        code(
            call(
                "PUT",
                "/rfqs/" + id + "/quotes/" + s1,
                "{\"currency\":\"GBP\",\"lines\":[{\"variantId\":\""
                    + stranger
                    + "\",\"unitPrice\":1}]}",
                T,
                "OWNER"),
            400),
        is("PURCHASE_RFQ_LINE_UNKNOWN"));
    assertThat(
        code(
            call(
                "PUT",
                "/rfqs/" + id + "/quotes/" + s1,
                "{\"currency\":\"GBP\",\"lines\":[]}",
                T,
                "OWNER"),
            400),
        is("PURCHASE_RFQ_QUOTE_EMPTY"));
    assertThat(call("POST", "/rfqs/" + id + "/issue", "{}", T, "CASHIER").getStatus(), is(403));
    assertThat(call("GET", "/rfqs/" + id, null, T2, "OWNER").getStatus(), is(404));
    assertThat(Envelopes.okArray(call("GET", "/rfqs", null, T2, "OWNER")).size(), is(0));
    JsonArray listed = Envelopes.okArray(get("/rfqs?status=ISSUED"));
    assertThat(listed.size(), is(1));
    assertThat(listed.getJsonObject(0).getInt("quotes"), is(2));
    assertThat(listed.getJsonObject(0).getInt("suppliers"), is(3));
  }

  // ── awarded: a draft order per supplier at the quoted prices ───────────────

  @Test
  void anAwardRaisesADraftOrderPerSupplierAtTheQuotedPricesInTheirMoney() {
    String s1 = supplier("Highland Meats", "GBP");
    String s2 = supplier("Boucherie Nord", "EUR");
    String s3 = supplier("Quiet Farm", "GBP");
    String id = raise(s1, s2, s3).getString("id");
    Envelopes.ok(post("/rfqs/" + id + "/issue", "{}"));
    Envelopes.ok(
        call("PUT", "/rfqs/" + id + "/quotes/" + s1, quote("GBP", "10.00", "4.00"), T, "OWNER"));
    Envelopes.ok(
        call("PUT", "/rfqs/" + id + "/quotes/" + s2, quote("EUR", "11.00", "5.00"), T, "OWNER"));

    // A line can only go to a supplier who priced it; a line goes once.
    assertThat(
        code(
            post(
                "/rfqs/" + id + "/award",
                "{\"awards\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"supplierId\":\""
                    + s3
                    + "\"}]}"),
            409),
        is("PURCHASE_RFQ_NOT_QUOTED"));
    assertThat(
        code(
            post(
                "/rfqs/" + id + "/award",
                "{\"awards\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"supplierId\":\""
                    + s1
                    + "\"},{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"supplierId\":\""
                    + s2
                    + "\"}]}"),
            400),
        is("PURCHASE_RFQ_AWARD_DUPLICATE"));
    assertThat(
        code(post("/rfqs/" + id + "/award", "{\"awards\":[]}"), 400),
        is("PURCHASE_RFQ_AWARDS_REQUIRED"));

    JsonObject awarded =
        Envelopes.ok(
            call(
                "POST",
                "/rfqs/" + id + "/award",
                "{\"awards\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"supplierId\":\""
                    + s2
                    + "\"},{\"variantId\":\""
                    + VARIANT_B
                    + "\",\"supplierId\":\""
                    + s1
                    + "\"}]}",
                T,
                "STOREKEEPER"));
    assertThat(awarded.getString("status"), is("AWARDED"));
    JsonArray awards = awarded.getJsonArray("awards");
    assertThat(awards.size(), is(2));
    JsonObject a = find(awards, "variantId", VARIANT_A);
    assertThat(a.getString("supplierId"), is(s2));
    assertThat(num(a, "unitPrice"), comparesEqualTo(new BigDecimal("11.00")));
    assertThat(a.getString("currency"), is("EUR"));
    JsonObject b = find(awards, "variantId", VARIANT_B);
    assertThat(b.getString("supplierId"), is(s1));
    assertThat(awarded.getJsonArray("purchaseOrderIds").size(), is(2));

    // One DRAFT order per supplier, in their money, at their price, for the day the goods are
    // needed.
    JsonObject po2 = Envelopes.ok(get("/purchase-orders/" + a.getString("poId")));
    assertThat(po2.getString("status"), is("DRAFT"));
    assertThat(po2.getString("supplierId"), is(s2));
    assertThat(po2.getString("currency"), is("EUR"));
    assertThat(po2.getString("source"), is("RFQ"));
    assertThat(po2.getString("expectedDelivery"), is(LocalDate.now().plusDays(14).toString()));
    assertThat(num(po2, "totalNet"), comparesEqualTo(new BigDecimal("110.00")));
    JsonArray lines2 = Envelopes.okArray(get("/purchase-orders/" + a.getString("poId") + "/lines"));
    assertThat(lines2.size(), is(1));
    assertThat(lines2.getJsonObject(0).getString("variantId"), is(VARIANT_A));
    assertThat(num(lines2.getJsonObject(0), "qty"), comparesEqualTo(new BigDecimal("10")));
    assertThat(num(lines2.getJsonObject(0), "unitPrice"), comparesEqualTo(new BigDecimal("11.00")));
    JsonObject po1 = Envelopes.ok(get("/purchase-orders/" + b.getString("poId")));
    assertThat(po1.getString("currency"), is("GBP"));
    assertThat(num(po1, "totalNet"), comparesEqualTo(new BigDecimal("20.00")));

    // Awarded once: no second award, no late quote.
    assertThat(
        code(
            post(
                "/rfqs/" + id + "/award",
                "{\"awards\":[{\"variantId\":\""
                    + VARIANT_A
                    + "\",\"supplierId\":\""
                    + s1
                    + "\"}]}"),
            409),
        is("PURCHASE_RFQ_NOT_ISSUED"));
    assertThat(
        code(
            call("PUT", "/rfqs/" + id + "/quotes/" + s1, quote("GBP", "9.00", "3.00"), T, "OWNER"),
            409),
        is("PURCHASE_RFQ_NOT_ISSUED"));
    assertThat(
        code(post("/rfqs/" + id + "/cancel", "{\"reason\":\"too late\"}"), 409),
        is("PURCHASE_RFQ_CLOSED"));
  }

  // ── cancelled with a reason ────────────────────────────────────────────────

  @Test
  void anRfqIsCancelledWithAReasonAndTakesNoMoreQuotes() {
    String s1 = supplier("Highland Meats", "GBP");
    String id = raise(s1).getString("id");
    Envelopes.ok(post("/rfqs/" + id + "/issue", "{}"));
    JsonObject cancelled =
        Envelopes.ok(post("/rfqs/" + id + "/cancel", "{\"reason\":\"the range was dropped\"}"));
    assertThat(cancelled.getString("status"), is("CANCELLED"));
    assertThat(cancelled.getString("cancelledReason"), is("the range was dropped"));
    assertThat(
        code(
            call("PUT", "/rfqs/" + id + "/quotes/" + s1, quote("GBP", "10.00", "4.00"), T, "OWNER"),
            409),
        is("PURCHASE_RFQ_NOT_ISSUED"));
    assertThat(
        code(post("/rfqs/" + id + "/cancel", "{\"reason\":\"again\"}"), 409),
        is("PURCHASE_RFQ_CLOSED"));
    assertThat(Envelopes.okArray(get("/rfqs?status=CANCELLED")).size(), is(1));
    assertThat(Envelopes.okArray(get("/rfqs?status=ISSUED")).size(), is(0));
    // The next request takes the next number.
    assertThat(raise(s1).getString("reference"), is("RFQ-000002"));
  }
}
