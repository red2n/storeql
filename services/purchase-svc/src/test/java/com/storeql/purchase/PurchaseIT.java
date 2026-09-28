package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for purchase-svc: suppliers, purchase orders, GRN, intercompany invoicing (Gap
 * #20), FRS 102 nominal ledger, and BACS 30-day payment terms. Kafka/Consul disabled.
 */
@HelidonTest
class PurchaseIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(PurchaseIT.T, "GBP", "GB").with(PurchaseIT.T2, "USD", "US");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // Approval OFF, stated rather than assumed. PurchaseApprovalIT sets this property in its own
    // static block and both suites share a JVM, so leaving it unset would make this suite's
    // behaviour depend on which class surefire happened to load first.
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String STORE_A = "01a090ae-611e-703c-a378-a4972ea461c8";
  private static final String STORE_B = "01a090ae-611e-7056-8f30-ecdbb48160eb";
  private static final String VARIANT = "01a090ae-611e-705c-994c-5daee3fbd033";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncateTables() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE purchase.nominal_ledger_entries, purchase.intercompany_invoices,"
              + " purchase.goods_receipt_lines, purchase.goods_receipts,"
              + " purchase.supplier_invoice_lines, purchase.supplier_invoices,"
              + " purchase.purchase_order_lines, purchase.purchase_orders,"
              + " purchase.suppliers, purchase.outbox CASCADE");
    }
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /**
   * Reads carry a staff role because every read here is staff work — suppliers, purchase orders,
   * goods receipts and the nominal ledger are all back-office data. Before SJ-D10 this helper sent
   * no role and the requests still succeeded, which is precisely what was wrong.
   */
  private Response get(String pathAndQuery, String tenant) {
    return getAs(pathAndQuery, tenant, "OWNER");
  }

  private Response getAs(String pathAndQuery, String tenant, String roles) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    var req = t.request().header("X-Tenant-Id", tenant);
    if (roles != null) req = req.header("X-Roles", roles);
    return req.get();
  }

  // ── Gap #20 Test 1: Supplier CRUD + tenant isolation ─────────────────────────

  @Test
  void supplierCrudAndTenantIsolation() {
    // Create supplier (UK, BACS 30-day default)
    Response r1 =
        post(
            "/suppliers",
            "{\"name\":\"ACME Supplies Ltd\",\"vatRegistered\":true,"
                + "\"vatNumber\":\"GB123456789\",\"countryCode\":\"GB\","
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String body = r1.readEntity(String.class);
    assertThat(body, containsString("ACME Supplies Ltd"));
    assertThat(body, containsString("GB"));
    assertThat(body, containsString("30")); // BACS default

    // Duplicate supplier name → 409
    Response rDup =
        post("/suppliers", "{\"name\":\"ACME Supplies Ltd\",\"vatRegistered\":false}", T);
    assertThat(rDup.getStatus(), is(409));

    // Tenant isolation — T2 cannot see T's suppliers
    Response rList = get("/suppliers", T2);
    assertThat(rList.getStatus(), is(200));
    assertThat(rList.readEntity(String.class), containsString("[]"));
  }

  // ── Gap #20 Test 2: Purchase Order lifecycle ──────────────────────────────────

  @Test
  void purchaseOrderLifecycle() {
    // Create supplier
    Response supRes =
        post("/suppliers", "{\"name\":\"Fresh Foods Ltd\",\"vatRegistered\":true}", T);
    assertThat(supRes.getStatus(), is(201));
    String supId = extractId(supRes.readEntity(String.class));

    // Create PO (DRAFT)
    Response poRes =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\","
                + "\"storeId\":\""
                + STORE_A
                + "\","
                + "\"currency\":\"GBP\","
                + "\"expectedDelivery\":\"2026-12-31\"}",
            T);
    assertThat(poRes.getStatus(), is(201));
    String poId = extractId(poRes.readEntity(String.class));

    // Add line
    Response lineRes =
        post(
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\""
                + VARIANT
                + "\","
                + "\"qty\":100,\"unitPrice\":9.99,\"vatCode\":\"T1\"}",
            T);
    assertThat(lineRes.getStatus(), is(201));

    // List lines
    Response linesRes = get("/purchase-orders/" + poId + "/lines", T);
    assertThat(linesRes.getStatus(), is(200));
    assertThat(linesRes.readEntity(String.class), containsString(VARIANT));

    // Submit PO
    Response submitRes = post("/purchase-orders/" + poId + "/submit", "{}", T);
    assertThat(submitRes.getStatus(), is(200));
    assertThat(submitRes.readEntity(String.class), containsString("SUBMITTED"));

    // Receive GRN
    Response grnRes =
        post(
            "/goods-receipts",
            "{\"poId\":\""
                + poId
                + "\","
                + "\"storeId\":\""
                + STORE_A
                + "\","
                + "\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qtyReceived\":100}]}",
            T);
    assertThat(grnRes.getStatus(), is(201));

    // PO should now be RECEIVED
    Response poGet = get("/purchase-orders/" + poId, T);
    assertThat(poGet.getStatus(), is(200));
    assertThat(poGet.readEntity(String.class), containsString("RECEIVED"));
  }

  // ── Partial receipt (horizon 2 item 5) ───────────────────────────────────────

  /** A submitted PO with one line for {@code qty}, ready to receive against. */
  private String submittedPo(String supplierName, int qty) {
    Response sup =
        post("/suppliers", "{\"name\":\"" + supplierName + "\",\"currency\":\"GBP\"}", T);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\","
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));
    assertThat(
        post(
                "/purchase-orders/" + poId + "/lines",
                "{\"variantId\":\""
                    + VARIANT
                    + "\",\"qty\":"
                    + qty
                    + ",\"unitPrice\":10.00,\"vatCode\":\"T1\"}",
                T)
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}", T).getStatus(), is(200));
    return poId;
  }

  private Response receive(String poId, String qty) {
    return post(
        "/goods-receipts",
        "{\"poId\":\""
            + poId
            + "\",\"storeId\":\""
            + STORE_A
            + "\","
            + "\"lines\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qtyReceived\":"
            + qty
            + "}]}",
        T);
  }

  private String status(String poId) {
    return get("/purchase-orders/" + poId, T).readEntity(String.class);
  }

  /**
   * The defect, and the half of it that was worse. A receipt used to set the order RECEIVED with no
   * reference to quantity — so 6 of 10 closed it, and the second delivery of the remaining 4 was
   * then refused because the order was no longer SUBMITTED. A split delivery stranded its own
   * balance with no purchase order left to receive it against.
   */
  @Test
  void aSplitDeliveryIsReceivedInPartsAndClosesOnlyWhenComplete() {
    String poId = submittedPo("Split Delivery Ltd", 10);

    assertThat(receive(poId, "6").getStatus(), is(201));
    assertThat(status(poId), containsString("PARTIALLY_RECEIVED"));

    // The balance. This is the call that used to fail.
    assertThat(receive(poId, "4").getStatus(), is(201));
    String body = status(poId);
    assertThat(body, containsString("\"status\":\"RECEIVED\""));
    assertThat(body, not(containsString("PARTIALLY_RECEIVED")));
  }

  /** The status alone does not say what is missing; the progress view does. */
  @Test
  void progressReportsWhatIsStillOutstanding() {
    String poId = submittedPo("Progress Ltd", 10);
    assertThat(receive(poId, "6").getStatus(), is(201));

    String body = get("/purchase-orders/" + poId + "/progress", T).readEntity(String.class);
    assertThat(body, containsString("\"qtyOrdered\":10.000"));
    assertThat(body, containsString("\"qtyReceived\":6.000"));
    assertThat(body, containsString("\"qtyOutstanding\":4.000"));
  }

  /**
   * Accepting more than was ordered would book stock nobody asked for against an order that cannot
   * account for it, and a mistyped 60 for 6 would do it silently.
   */
  @Test
  void overReceiptIsRefused() {
    String poId = submittedPo("Over Delivery Ltd", 10);
    Response r = receive(poId, "11");
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_OVER_RECEIPT"));
    // And it is refused as a whole: the order is untouched, not left half-updated.
    assertThat(status(poId), containsString("SUBMITTED"));
  }

  /** Over-receipt across two deliveries is the same fault, and the second one is where it shows. */
  @Test
  void overReceiptIsRefusedCumulativelyNotOnlyPerDelivery() {
    String poId = submittedPo("Cumulative Ltd", 10);
    assertThat(receive(poId, "6").getStatus(), is(201));
    Response second = receive(poId, "6"); // 12 against an order of 10
    assertThat(second.getStatus(), is(422));
    assertThat(second.readEntity(String.class), containsString("PURCHASE_OVER_RECEIPT"));
    // The first delivery still stands.
    assertThat(status(poId), containsString("PARTIALLY_RECEIVED"));
  }

  /**
   * Without a short close, a partially received order the supplier never completes sits in
   * PARTIALLY_RECEIVED for good — the same dead end SJ-D3 fixed for DRAFT and SUBMITTED.
   */
  @Test
  void aPartiallyReceivedOrderCanBeShortClosed() {
    String poId = submittedPo("Short Close Ltd", 10);
    assertThat(receive(poId, "6").getStatus(), is(201));

    Response closed =
        post("/purchase-orders/" + poId + "/close", "{\"reason\":\"supplier discontinued\"}", T);
    assertThat(closed.getStatus(), is(200));
    String body = closed.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"CLOSED\""));
    assertThat(body, containsString("supplier discontinued"));

    // A closed order is no longer receivable — the balance was abandoned deliberately.
    assertThat(receive(poId, "4").getStatus(), is(400));
  }

  /** CLOSED is for a partly delivered order. The other two states have their own answers. */
  @Test
  void onlyAPartiallyReceivedOrderCanBeShortClosed() {
    String submitted = submittedPo("Nothing Yet Ltd", 10);
    Response r = post("/purchase-orders/" + submitted + "/close", "{\"reason\":\"n/a\"}", T);
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_PO_NOT_CLOSEABLE"));

    String full = submittedPo("All Arrived Ltd", 10);
    assertThat(receive(full, "10").getStatus(), is(201));
    assertThat(
        post("/purchase-orders/" + full + "/close", "{\"reason\":\"n/a\"}", T).getStatus(),
        is(409));
  }

  /** A replayed receipt must not count its quantity twice — the SJ-D15 question, asked here. */
  @Test
  void aReplayedReceiptDoesNotCountTwice() {
    String poId = submittedPo("Replay Ltd", 10);
    String key = com.storeql.ids.Ids.newId().toString();
    for (int i = 0; i < 3; i++) {
      Response r =
          target
              .path("/goods-receipts")
              .request()
              .header("X-Tenant-Id", T)
              .header("X-Roles", "OWNER")
              .header("Idempotency-Key", key)
              .post(
                  Entity.entity(
                      "{\"poId\":\""
                          + poId
                          + "\",\"storeId\":\""
                          + STORE_A
                          + "\","
                          + "\"lines\":[{\"variantId\":\""
                          + VARIANT
                          + "\",\"qtyReceived\":6}]}",
                      MediaType.APPLICATION_JSON));
      assertThat(r.getStatus(), is(201));
    }
    String body = get("/purchase-orders/" + poId + "/progress", T).readEntity(String.class);
    assertThat(body, containsString("\"qtyReceived\":6.000"));
    assertThat(body, containsString("\"qtyOutstanding\":4.000"));
  }

  // ── Gap #20 Test 3: Intercompany invoicing + FRS 102 nominal ledger ───────────

  @Test
  void intercompanyInvoicingAndNominalLedger() {
    // Raise intercompany invoice pair (AR + AP) with UK VAT T1 20%
    // net = £1000, VAT = £200, gross = £1200
    Response r1 =
        post(
            "/intercompany-invoices",
            "{\"fromStoreId\":\""
                + STORE_A
                + "\","
                + "\"toStoreId\":\""
                + STORE_B
                + "\","
                + "\"netAmount\":1000.00,"
                + "\"vatAmount\":200.00,"
                + "\"grossAmount\":1200.00,"
                + "\"vatCode\":\"T1\","
                + "\"vatDisregarded\":false,"
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String pairBody = r1.readEntity(String.class);
    assertThat(pairBody, containsString("arInvoice"));
    assertThat(pairBody, containsString("apInvoice"));
    assertThat(pairBody, containsString("RAISED"));
    // BACS 30-day payment due date
    assertThat(pairBody, containsString("paymentDueDate"));

    String arId = extractNestedId(pairBody, "arInvoice");
    String apId = extractNestedId(pairBody, "apInvoice");

    // List invoices
    Response rList = get("/intercompany-invoices", T);
    assertThat(rList.getStatus(), is(200));
    assertThat(rList.readEntity(String.class), containsString("AR"));

    // Get specific invoice
    Response rGet = get("/intercompany-invoices/" + arId, T);
    assertThat(rGet.getStatus(), is(200));
    assertThat(rGet.readEntity(String.class), containsString("1000.00"));

    // Nominal ledger should have AR entries: 1100 Debtors DR, 2200 VAT Output CR, 4000 IC Sales CR
    Response ledger = get("/nominal-ledger", T);
    assertThat(ledger.getStatus(), is(200));
    String ledgerBody = ledger.readEntity(String.class);
    assertThat(ledgerBody, containsString("1100"));
    assertThat(ledgerBody, containsString("2200"));
    assertThat(ledgerBody, containsString("2100"));
    // Read in the accounting package and on the Integrations screen: each invoice is named by "#"
    // and the last eight of its id, as every screen names a document — never by the whole id.
    String arHandle = "#" + arId.substring(arId.length() - 8);
    String apHandle = "#" + apId.substring(apId.length() - 8);
    assertThat(
        ledgerBody, containsString("\"description\":\"Intercompany AR invoice " + arHandle + "\""));
    assertThat(
        ledgerBody, containsString("\"description\":\"Intercompany AP invoice " + apHandle + "\""));
    assertThat(ledgerBody, not(containsString("invoice " + arId)));
    assertThat(ledgerBody, not(containsString("invoice " + apId)));

    // Settle AR invoice → DR 1200 Bank / CR 1100 Debtors
    Response settleRes = post("/intercompany-invoices/" + arId + "/settle", "{}", T);
    assertThat(settleRes.getStatus(), is(200));
    String settled = get("/nominal-ledger", T).readEntity(String.class);
    assertThat(
        settled,
        containsString("\"description\":\"Settlement of intercompany invoice " + arHandle + "\""));
    assertThat(settled, not(containsString("invoice " + arId)));

    // Settle again → 409 (already settled)
    Response settle2 = post("/intercompany-invoices/" + arId + "/settle", "{}", T);
    assertThat(settle2.getStatus(), is(409));

    // Tenant isolation — T2 cannot see T's invoices
    Response rIso = get("/intercompany-invoices/" + arId, T2);
    assertThat(rIso.getStatus(), is(404));
  }

  // ── Gap #20 Test 4: Group VAT disregard ──────────────────────────────────────

  @Test
  void intercompanyInvoiceGroupVatDisregarded() {
    // With vatDisregarded=true, no VAT nominal entries should be posted
    Response r1 =
        post(
            "/intercompany-invoices",
            "{\"fromStoreId\":\""
                + STORE_A
                + "\","
                + "\"toStoreId\":\""
                + STORE_B
                + "\","
                + "\"netAmount\":500.00,"
                + "\"vatAmount\":0.00,"
                + "\"grossAmount\":500.00,"
                + "\"vatCode\":\"T1\","
                + "\"vatDisregarded\":true,"
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    assertThat(r1.readEntity(String.class), containsString("\"vatDisregarded\":true"));

    // Ledger should NOT contain 2200 VAT Output or 2201 VAT Input
    Response ledger = get("/nominal-ledger?code=2200", T);
    assertThat(ledger.getStatus(), is(200));
    assertThat(ledger.readEntity(String.class), containsString("[]"));

    // Negative: same fromStore and toStore → 400
    Response rBad =
        post(
            "/intercompany-invoices",
            "{\"fromStoreId\":\""
                + STORE_A
                + "\","
                + "\"toStoreId\":\""
                + STORE_A
                + "\","
                + "\"netAmount\":100.00,"
                + "\"vatAmount\":20.00,"
                + "\"grossAmount\":120.00,"
                + "\"currency\":\"GBP\"}",
            T);
    assertThat(rBad.getStatus(), is(400));
  }

  // ── SJ-D3: CANCELLED was an unreachable state ────────────────────────────────

  /** A DRAFT purchase order raised in error can be cancelled, with its reason recorded. */
  @Test
  void draftPurchaseOrderCanBeCancelled() {
    String poId = draftPurchaseOrder("Cancel Me Ltd");

    Response cancelled =
        post(
            "/purchase-orders/" + poId + "/cancel",
            "{\"reason\":\"raised against wrong store\"}",
            T);
    assertThat(cancelled.getStatus(), is(200));
    String body = cancelled.readEntity(String.class);
    assertThat(body, containsString("CANCELLED"));
    assertThat(body, containsString("raised against wrong store"));

    // The cancellation survives a re-read, and the reason is on the order itself.
    String reread = get("/purchase-orders/" + poId, T).readEntity(String.class);
    assertThat(reread, containsString("CANCELLED"));
    assertThat(reread, containsString("raised against wrong store"));
  }

  /** A SUBMITTED order is still cancellable, and cancelling it closes it to further receipts. */
  @Test
  void submittedPurchaseOrderCanBeCancelledAndIsThenUnreceivable() {
    String poId = draftPurchaseOrder("Submitted Then Cancelled Ltd");
    assertThat(post("/purchase-orders/" + poId + "/lines", line(), T).getStatus(), is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}", T).getStatus(), is(200));

    assertThat(
        post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"supplier out of stock\"}", T)
            .getStatus(),
        is(200));

    // Receiving against a cancelled order must fail -- otherwise stock would be booked against a
    // commitment that no longer exists.
    Response received =
        post(
            "/goods-receipts",
            "{\"poId\":\""
                + poId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qtyReceived\":5}]}",
            T);
    assertThat(received.getStatus(), is(400));
  }

  /**
   * A received order holds stock booked against it, so cancelling would orphan that stock; and a
   * second cancel is refused rather than silently discarding the new caller's reason.
   */
  @Test
  void receivedOrCancelledPurchaseOrdersCannotBeCancelled() {
    // RECEIVED → 409
    String receivedPo = draftPurchaseOrder("Already Received Ltd");
    assertThat(post("/purchase-orders/" + receivedPo + "/lines", line(), T).getStatus(), is(201));
    assertThat(post("/purchase-orders/" + receivedPo + "/submit", "{}", T).getStatus(), is(200));
    assertThat(
        post(
                "/goods-receipts",
                "{\"poId\":\""
                    + receivedPo
                    + "\",\"storeId\":\""
                    + STORE_A
                    + "\",\"lines\":[{\"variantId\":\""
                    + VARIANT
                    + "\",\"qtyReceived\":10}]}",
                T)
            .getStatus(),
        is(201));
    Response afterReceipt =
        post("/purchase-orders/" + receivedPo + "/cancel", "{\"reason\":\"too late\"}", T);
    assertThat(afterReceipt.getStatus(), is(409));
    assertThat(
        afterReceipt.readEntity(String.class), containsString("PURCHASE_PO_NOT_CANCELLABLE"));

    // Already CANCELLED → 409, so the first reason recorded is the one that stands.
    String cancelledPo = draftPurchaseOrder("Double Cancel Ltd");
    assertThat(
        post("/purchase-orders/" + cancelledPo + "/cancel", "{\"reason\":\"first\"}", T)
            .getStatus(),
        is(200));
    Response second =
        post("/purchase-orders/" + cancelledPo + "/cancel", "{\"reason\":\"second\"}", T);
    assertThat(second.getStatus(), is(409));
    assertThat(
        get("/purchase-orders/" + cancelledPo, T).readEntity(String.class),
        containsString("first"));
  }

  /** A cancellation with no stated reason is unauditable, so it is rejected. */
  @Test
  void cancellationRequiresAReason() {
    String poId = draftPurchaseOrder("No Reason Ltd");
    assertThat(
        post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"  \"}", T).getStatus(),
        is(400));
    assertThat(post("/purchase-orders/" + poId + "/cancel", "{}", T).getStatus(), is(400));
    // Still cancellable afterwards -- a rejected request must not have moved the state.
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class), containsString("DRAFT"));
  }

  /** Cancelling is tenant-scoped: another tenant cannot reach this order at all. */
  @Test
  void cancellationIsTenantScoped() {
    String poId = draftPurchaseOrder("Isolated Ltd");
    assertThat(
        post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"not yours\"}", T2).getStatus(),
        is(404));
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class), containsString("DRAFT"));
  }

  // ── SJ-D9: a mistyped date is the caller's mistake, not a server fault ───────

  /**
   * A malformed date must come back 400 naming the field it came from, never 500. These three
   * inputs reached {@code LocalDate.parse} raw, so a caller's typo threw out of business code and
   * fell through to GenericExceptionMapper as INTERNAL_ERROR — wrong per golden rule #15, and it
   * makes a client error look like an outage to alerting.
   */
  @Test
  void malformedDatesAreRejectedAsBadRequestNamingTheField() {
    Response sup = post("/suppliers", "{\"name\":\"Typo Traders Ltd\"}", T);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));

    // A full instant where the field takes yyyy-MM-dd: the plausible wrong guess, and the shape
    // that produced the 500 in pricing-svc when its own @Schema said only "ISO-8601 date".
    Response bad =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"expectedDelivery\":\"2026-12-31T00:00:00Z\"}",
            T);
    assertThat(bad.getStatus(), is(400));
    String body = bad.readEntity(String.class);
    assertThat(body, containsString("INVALID_DATE"));
    assertThat(body, containsString("expectedDelivery must be yyyy-MM-dd"));

    // The nominal-ledger range is the same class of input and names itself the same way.
    Response badRange = get("/nominal-ledger?from=last-tuesday", T);
    assertThat(badRange.getStatus(), is(400));
    assertThat(badRange.readEntity(String.class), containsString("from must be yyyy-MM-dd"));

    // ...and the documented form still works, so the guard did not simply reject everything.
    Response ok =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"expectedDelivery\":\"2026-12-31\"}",
            T);
    assertThat(ok.getStatus(), is(201));
  }

  // ── SJ-D10: procurement is back-office data, not storefront data ────────────

  /**
   * Every read in this service used to answer any caller holding a token for the tenant, because
   * none of these paths sits under /admin/ and nothing in purchase-svc called requireAnyRole. A
   * signed-in storefront shopper could list the tenant's suppliers and their payment terms, its
   * purchase orders, and its nominal ledger.
   */
  @Test
  void procurementReadsRequireAStaffRole() {
    for (String path :
        new String[] {
          "/suppliers",
          "/purchase-orders",
          "/goods-receipts?purchaseOrderId=" + STORE_A,
          "/intercompany-invoices",
          "/nominal-ledger"
        }) {
      assertThat("no role: " + path, getAs(path, T, null).getStatus(), is(403));
      assertThat("customer: " + path, getAs(path, T, "CUSTOMER").getStatus(), is(403));
    }
    // Staff still work, or the gate would just be an outage.
    assertThat(getAs("/suppliers", T, "STOREKEEPER").getStatus(), is(200));
    assertThat(getAs("/purchase-orders", T, "OWNER").getStatus(), is(200));
  }

  /** Creates a supplier and a DRAFT purchase order against it, returning the PO id. */
  private String draftPurchaseOrder(String supplierName) {
    Response sup = post("/suppliers", "{\"name\":\"" + supplierName + "\"}", T);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"currency\":\"GBP\"}",
            T);
    assertThat(po.getStatus(), is(201));
    return extractId(po.readEntity(String.class));
  }

  private static String line() {
    return "{\"variantId\":\"" + VARIANT + "\",\"qty\":10,\"unitPrice\":2.50}";
  }

  // ── SJ-D22 / SJ-D23 / SJ-D24: totals and currency, across markets ────────────

  /**
   * The defect itself, through the API. Before this change the assertion below read {@code
   * "totalGross":0.00} on an order committing £999 — and that is what the procurement screen
   * rendered, in two places, for every purchase order ever raised.
   *
   * <p>No VAT rates are reachable in this harness (Consul is disabled, so the pricing lookup falls
   * back to an empty table), which makes gross equal net here. That is the correct answer for a
   * tenant with no VAT configured, and it is the same code path a fresh tenant takes in production.
   */
  @Test
  void purchaseOrderTotalsAreComputedFromItsLines() {
    String supId = supplier("Totals Test Ltd", "GBP");
    String poId = poFor(supId);

    // An order with no lines is genuinely zero — and at sterling's own scale.
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class),
        containsString("\"totalNet\":0.00"));

    addLine(poId, "100", "9.99");
    String afterOne = get("/purchase-orders/" + poId, T).readEntity(String.class);
    assertThat(afterOne, containsString("\"totalNet\":999.00"));
    assertThat(afterOne, containsString("\"totalGross\":999.00"));
    assertThat(afterOne, not(containsString("\"totalNet\":0.00")));

    // A second line restates the order rather than replacing the figure.
    addLine(poId, "3", "0.50");
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class),
        containsString("\"totalNet\":1000.50"));
  }

  /**
   * Japan. The yen has no minor unit, so a JPY order must total in whole yen — the one currency of
   * the five that a hardcoded {@code setScale(2)} would silently get wrong.
   */
  @Test
  void japaneseSupplierOrdersInWholeYen() {
    String supId = supplier("Tokyo Trading KK", "JPY");
    String poId = poFor(supId);

    // The order inherits the supplier's currency without being told it (SJ-D24).
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class),
        containsString("\"currency\":\"JPY\""));

    addLine(poId, "3", "1234");
    String body = get("/purchase-orders/" + poId, T).readEntity(String.class);
    assertThat(body, containsString("\"totalNet\":3702"));
    assertThat(body, not(containsString("3702.00")));
  }

  /** The four two-minor-unit markets, each an independent tenant's supplier. */
  @Test
  void suppliersInEveryMarketKeepTheirOwnCurrency() {
    for (String[] market :
        new String[][] {
          {"US Wholesale Inc", "USD"}, {"Mumbai Supplies Pvt", "INR"},
          {"Shenzhen Goods Co", "CNY"}, {"Brighton Provisions", "GBP"}
        }) {
      String supId = supplier(market[0], market[1]);
      String poId = poFor(supId);
      addLine(poId, "2", "10.00");

      String body = get("/purchase-orders/" + poId, T).readEntity(String.class);
      assertThat(market[1], body, containsString("\"currency\":\"" + market[1] + "\""));
      assertThat(market[1], body, containsString("\"totalNet\":20.00"));
    }
  }

  /**
   * A purchase order cannot be denominated in a currency its supplier does not invoice in. Refused
   * rather than silently overridden, on the SJ-D2 precedent.
   */
  @Test
  void anOrderCannotContradictItsSuppliersCurrency() {
    String supId = supplier("Osaka Parts KK", "JPY");

    Response r =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"currency\":\"GBP\"}",
            T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_CURRENCY_MISMATCH"));

    // Naming the supplier's own currency is fine — it agrees rather than contradicts.
    Response ok =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"currency\":\"JPY\"}",
            T);
    assertThat(ok.getStatus(), is(201));
  }

  /** Currency is validated at the boundary rather than reaching the database (golden rule #15). */
  @Test
  void anInvalidCurrencyCodeIsRejected() {
    Response r = post("/suppliers", "{\"name\":\"Bad Currency Ltd\",\"currency\":\"POUNDS\"}", T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_INVALID_CURRENCY"));
  }

  /**
   * Two tenants trading in different currencies do not contaminate each other, and neither can see
   * the other's order — the same store id is deliberately used for both, because a shared store id
   * is exactly the case where a missing tenant filter would show.
   */
  @Test
  void twoTenantsInDifferentCurrenciesStaySeparate() {
    String jpSupplier = supplier("Kyoto Imports KK", "JPY");
    String jpPo = poFor(jpSupplier);
    addLine(jpPo, "5", "500");

    String usSupplier = supplierFor(T2, "Chicago Wholesale Inc", "USD");
    Response usPoRes =
        post(
            "/purchase-orders",
            "{\"supplierId\":\"" + usSupplier + "\",\"storeId\":\"" + STORE_A + "\"}",
            T2);
    assertThat(usPoRes.getStatus(), is(201));
    String usPo = extractId(usPoRes.readEntity(String.class));
    Response usLine =
        post(
            "/purchase-orders/" + usPo + "/lines",
            "{\"variantId\":\"" + VARIANT + "\",\"qty\":5,\"unitPrice\":500}",
            T2);
    assertThat(usLine.getStatus(), is(201));

    assertThat(
        get("/purchase-orders/" + jpPo, T).readEntity(String.class),
        containsString("\"totalNet\":2500"));
    assertThat(
        get("/purchase-orders/" + usPo, T2).readEntity(String.class),
        containsString("\"totalNet\":2500.00"));

    // Neither tenant can read the other's order at all.
    assertThat(get("/purchase-orders/" + usPo, T).getStatus(), is(404));
    assertThat(get("/purchase-orders/" + jpPo, T2).getStatus(), is(404));
  }

  /**
   * SJ-D25, and the reason it is a defect rather than a display quirk. The dinar has THREE minor
   * units, and every money column in this service was NUMERIC(14,2) — so Postgres rounded the third
   * decimal away on write, without an error, on every line and every total.
   *
   * <p>2 × 1.234 KWD is 2.468. Under the old column type it stored as 2.47: money gone, silently,
   * on a tenant nobody had thought to test.
   */
  @Test
  void aThreeMinorUnitCurrencyKeepsItsThirdDecimal() {
    String supId = supplier("Kuwait Trading WLL", "KWD");
    String poId = poFor(supId);
    addLine(poId, "2", "1.234");

    String body = get("/purchase-orders/" + poId, T).readEntity(String.class);
    assertThat(body, containsString("\"currency\":\"KWD\""));
    assertThat(body, containsString("\"totalNet\":2.468"));
    // On the totals, not the whole body: a timestamp such as "…42.470778Z" contains "2.47" too.
    assertThat(body, not(containsString("\"totalNet\":2.47")));
    assertThat(body, not(containsString("\"totalGross\":2.47")));
  }

  /**
   * A unit price may legitimately carry more precision than the currency it is priced in — 1,000
   * screws at £0.0125 each is an ordinary trade price. NUMERIC(14,2) rounded it to £0.01, a 25%
   * error on the line before any currency question arises.
   */
  @Test
  void aSubPennyUnitPriceSurvives() {
    String supId = supplier("Fastener Wholesale Ltd", "GBP");
    String poId = poFor(supId);
    addLine(poId, "1000", "0.0125");

    // The line keeps its true price, and the order total is still rounded to the penny it is
    // actually invoiced in: 1000 × 0.0125 = £12.50 exactly.
    assertThat(
        get("/purchase-orders/" + poId + "/lines", T).readEntity(String.class),
        containsString("0.0125"));
    assertThat(
        get("/purchase-orders/" + poId, T).readEntity(String.class),
        containsString("\"totalNet\":12.50"));
  }

  // ── three-way match: ordered vs received vs invoiced ─────────────────────────

  /**
   * The whole control, end to end: order 100, receive 60, invoice 60 at the agreed price.
   *
   * <p>Quantity is matched against what was RECEIVED. Matching against what was ORDERED would flag
   * this — and a part-delivered order billed for the part is the commonest legitimate case there
   * is.
   */
  @Test
  void anInvoiceForWhatActuallyArrivedMatchesCleanly() {
    String po = receivedOrder("Match Clean Ltd", "100", "60", "2.50");

    Response r = post("/supplier-invoices", invoice(po, "INV-CLEAN-1", "60", "2.50"), T);
    assertThat(r.getStatus(), is(201));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"MATCHED\""));
    assertThat(body, containsString("\"qtyOrdered\":100"));
    assertThat(body, containsString("\"qtyReceived\":60"));
    assertThat(body, containsString("\"variances\":[]"));
  }

  /** Billed for the whole order when only part of it turned up. */
  @Test
  void anInvoiceForMoreThanArrivedIsFlagged() {
    String po = receivedOrder("Over Invoice Ltd", "100", "60", "2.50");

    Response r = post("/supplier-invoices", invoice(po, "INV-OVER-1", "100", "2.50"), T);
    assertThat(r.getStatus(), is(201));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"FLAGGED\""));
    assertThat(body, containsString("INVOICED_ABOVE_RECEIVED"));
    // Captured anyway: flagging never blocks, because the invoice arriving is a fact and the
    // disagreement is the thing somebody needs in order to argue with the supplier.
    assertThat(
        get("/supplier-invoices?poId=" + po, T).readEntity(String.class),
        containsString("INV-OVER-1"));
  }

  /** Charged more per unit than the order agreed. */
  @Test
  void aPriceAboveTheOrderIsFlagged() {
    String po = receivedOrder("Price Creep Ltd", "100", "60", "2.50");

    String body =
        post("/supplier-invoices", invoice(po, "INV-PRICE-1", "60", "2.75"), T)
            .readEntity(String.class);
    assertThat(body, containsString("\"status\":\"FLAGGED\""));
    assertThat(body, containsString("PRICE_ABOVE_ORDER"));
    assertThat(body, containsString("\"orderedUnitPrice\":2.50"));
    assertThat(body, containsString("\"invoicedUnitPrice\":2.75"));
  }

  /**
   * A supplier who delivers and bills in two parts must not be flagged on the second.
   *
   * <p>The invoiced leg is cumulative across every invoice on the order — the same shape partial
   * receipt established for the received leg, and for the same reason.
   */
  @Test
  void invoicingInTwoPartsIsNotOverInvoicing() {
    String po = receivedOrder("Two Invoices Ltd", "100", "60", "2.50");

    assertThat(
        post("/supplier-invoices", invoice(po, "INV-SPLIT-1", "40", "2.50"), T)
            .readEntity(String.class),
        containsString("\"status\":\"MATCHED\""));
    assertThat(
        post("/supplier-invoices", invoice(po, "INV-SPLIT-2", "20", "2.50"), T)
            .readEntity(String.class),
        containsString("\"status\":\"MATCHED\""));
    // 40 + 20 = 60, all of what arrived. One more is not.
    assertThat(
        post("/supplier-invoices", invoice(po, "INV-SPLIT-3", "20", "2.50"), T)
            .readEntity(String.class),
        containsString("INVOICED_ABOVE_RECEIVED"));
  }

  /** The duplicate-payment guard: two people typing the same paper reference. */
  @Test
  void theSameInvoiceNumberCannotBeCapturedTwice() {
    String po = receivedOrder("Duplicate Ltd", "100", "60", "2.50");

    assertThat(
        post("/supplier-invoices", invoice(po, "INV-DUP-1", "60", "2.50"), T).getStatus(), is(201));
    Response again = post("/supplier-invoices", invoice(po, "inv-dup-1", "60", "2.50"), T);
    assertThat(again.getStatus(), is(409));
    // Case-insensitively, because the reference is printed on paper and typed by a human.
    assertThat(again.readEntity(String.class), containsString("PURCHASE_INVOICE_DUPLICATE"));
  }

  /**
   * An invoice in a currency the order was not placed in is a different document, not a variance.
   */
  @Test
  void anInvoiceCannotBeInAnotherCurrency() {
    String po = receivedOrder("Currency Ltd", "10", "10", "2.50");

    Response r =
        post(
            "/supplier-invoices",
            "{\"poId\":\""
                + po
                + "\",\"invoiceNumber\":\"INV-CUR-1\",\"invoiceDate\":\"2026-02-01\","
                + "\"currency\":\"JPY\",\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qty\":10,\"unitPrice\":2.50}]}",
            T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_CURRENCY_MISMATCH"));
  }

  /** Japan: a whole-yen invoice matches a whole-yen order with no invented precision. */
  @Test
  void aYenInvoiceMatchesInWholeYen() {
    String supId = supplier("Tokyo Invoice KK", "JPY");
    String po = poFor(supId);
    addLine(po, "3", "1234");
    post("/purchase-orders/" + po + "/submit", "{}", T);
    post(
        "/goods-receipts",
        "{\"poId\":\""
            + po
            + "\",\"storeId\":\""
            + STORE_A
            + "\",\"lines\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qtyReceived\":3}]}",
        T);

    String body =
        post("/supplier-invoices", invoice(po, "INV-JPY-1", "3", "1234"), T)
            .readEntity(String.class);
    assertThat(body, containsString("\"status\":\"MATCHED\""));
    assertThat(body, containsString("\"currency\":\"JPY\""));
    assertThat(body, containsString("\"netAmount\":3702"));
    assertThat(body, not(containsString("3702.00")));
  }

  /** Tenant isolation: an invoice on another tenant's order is not found, not forbidden. */
  @Test
  void anInvoiceCannotBeCapturedAgainstAnotherTenantsOrder() {
    String po = receivedOrder("Isolation Ltd", "10", "10", "2.50");
    Response r = post("/supplier-invoices", invoice(po, "INV-ISO-1", "10", "2.50"), T2);
    assertThat(r.getStatus(), is(404));
  }

  /** An order, submitted, and part-received — the two legs an invoice is matched against. */
  private String receivedOrder(String supplierName, String ordered, String received, String price) {
    String supId = supplier(supplierName, "GBP");
    String po = poFor(supId);
    addLine(po, ordered, price);
    assertThat(post("/purchase-orders/" + po + "/submit", "{}", T).getStatus(), is(200));
    assertThat(
        post(
                "/goods-receipts",
                "{\"poId\":\""
                    + po
                    + "\",\"storeId\":\""
                    + STORE_A
                    + "\",\"lines\":[{\"variantId\":\""
                    + VARIANT
                    + "\",\"qtyReceived\":"
                    + received
                    + "}]}",
                T)
            .getStatus(),
        is(201));
    return po;
  }

  private String invoice(String poId, String number, String qty, String unitPrice) {
    return "{\"poId\":\""
        + poId
        + "\",\"invoiceNumber\":\""
        + number
        + "\",\"invoiceDate\":\"2026-02-01\",\"lines\":[{\"variantId\":\""
        + VARIANT
        + "\",\"qty\":"
        + qty
        + ",\"unitPrice\":"
        + unitPrice
        + "}]}";
  }

  private String supplier(String name, String currency) {
    return supplierFor(T, name, currency);
  }

  private String supplierFor(String tenant, String name, String currency) {
    Response r =
        post(
            "/suppliers",
            "{\"name\":\"" + name + "\",\"vatRegistered\":true,\"currency\":\"" + currency + "\"}",
            tenant);
    assertThat(r.getStatus(), is(201));
    return extractId(r.readEntity(String.class));
  }

  private String poFor(String supplierId) {
    Response r =
        post(
            "/purchase-orders",
            "{\"supplierId\":\"" + supplierId + "\",\"storeId\":\"" + STORE_A + "\"}",
            T);
    assertThat(r.getStatus(), is(201));
    return extractId(r.readEntity(String.class));
  }

  private void addLine(String poId, String qty, String unitPrice) {
    Response r =
        post(
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\""
                + VARIANT
                + "\",\"qty\":"
                + qty
                + ",\"unitPrice\":"
                + unitPrice
                + ",\"vatCode\":\"T1\"}",
            T);
    assertThat(r.getStatus(), is(201));
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static String extractNestedId(String json, String field) {
    int fieldPos = json.indexOf("\"" + field + "\"");
    int start = json.indexOf("\"id\":\"", fieldPos) + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  // ── SJ-D34: a supplier can be corrected after it is created ─────────────────

  private Response putAs(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void supplierTermsVatCountryAndCurrencyCanBeCorrected() {
    String id = supplier("Typo Trading " + Ids.newId(), "JPY");
    Response r =
        putAs(
            "/suppliers/" + id,
            "{\"name\":\"Typo Trading Ltd\",\"vatRegistered\":true,\"vatNumber\":\"GB999999973\","
                + "\"countryCode\":\"gb\",\"currency\":\"eur\",\"paymentTermsDays\":45}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"name\":\"Typo Trading Ltd\""));
    assertThat(body, containsString("\"vatNumber\":\"GB999999973\""));
    assertThat(body, containsString("\"countryCode\":\"GB\""));
    assertThat(body, containsString("\"currency\":\"EUR\""));
    assertThat(body, containsString("\"paymentTermsDays\":45"));
    // And a fresh read agrees — the correction is stored, not just echoed.
    assertThat(
        get("/suppliers/" + id, T).readEntity(String.class),
        containsString("\"currency\":\"EUR\""));
    // Omitted fields stay as they were: only the name is required.
    Response partial =
        putAs(
            "/suppliers/" + id,
            "{\"name\":\"Typo Trading Ltd\",\"vatRegistered\":true}",
            T,
            "OWNER");
    assertThat(partial.getStatus(), is(200));
    String kept = partial.readEntity(String.class);
    assertThat(kept, containsString("\"currency\":\"EUR\""));
    assertThat(kept, containsString("\"paymentTermsDays\":45"));
    assertThat(kept, containsString("\"countryCode\":\"GB\""));
  }

  @Test
  void currencyCannotChangeUnderAnOpenOrderAndOrdersKeepTheirs() {
    // The wrong currency at creation was permanent once purchase orders inherited it (SJ-D24 made
    // the field load-bearing, SJ-D34 found it uncorrectable). Now: fix it once nothing is open.
    String id = supplier("Yen By Mistake " + Ids.newId(), "JPY");
    String po = poFor(id);
    assertThat(
        get("/purchase-orders/" + po, T).readEntity(String.class),
        containsString("\"currency\":\"JPY\""));
    Response blocked =
        putAs("/suppliers/" + id, "{\"name\":\"Yen By Mistake\",\"currency\":\"GBP\"}", T, "OWNER");
    assertThat(blocked.getStatus(), is(409));
    String why = blocked.readEntity(String.class);
    assertThat(why, containsString("PURCHASE_SUPPLIER_CURRENCY_IN_USE"));
    assertThat(why, containsString("1 open purchase order"));
    // Anything but the currency can still change under an open order.
    assertThat(
        putAs(
                "/suppliers/" + id,
                "{\"name\":\"Yen By Mistake\",\"paymentTermsDays\":60}",
                T,
                "OWNER")
            .getStatus(),
        is(200));
    assertThat(
        post("/purchase-orders/" + po + "/cancel", "{\"reason\":\"wrong currency\"}", T)
            .getStatus(),
        is(200));
    Response fixed =
        putAs("/suppliers/" + id, "{\"name\":\"Yen By Mistake\",\"currency\":\"GBP\"}", T, "OWNER");
    assertThat(fixed.getStatus(), is(200));
    assertThat(fixed.readEntity(String.class), containsString("\"currency\":\"GBP\""));
    // The cancelled order keeps the currency it was raised in; the next one inherits the fix.
    assertThat(
        get("/purchase-orders/" + po, T).readEntity(String.class),
        containsString("\"currency\":\"JPY\""));
    String next = poFor(id);
    assertThat(
        get("/purchase-orders/" + next, T).readEntity(String.class),
        containsString("\"currency\":\"GBP\""));
  }

  @Test
  void supplierCorrectionIsRefusedForADuplicateNameABadCurrencyOrBadTerms() {
    String a = supplier("First Supplier " + Ids.newId(), "GBP");
    String bName = "Second Supplier " + Ids.newId();
    String b = supplier(bName, "GBP");
    String aName =
        get("/suppliers/" + a, T)
            .readEntity(String.class)
            .replaceAll(".*\"name\":\"([^\"]+)\".*", "$1");
    Response dup = putAs("/suppliers/" + b, "{\"name\":\"" + aName + "\"}", T, "OWNER");
    assertThat(dup.getStatus(), is(409));
    assertThat(dup.readEntity(String.class), containsString("PURCHASE_SUPPLIER_DUPLICATE"));
    assertThat(
        putAs("/suppliers/" + b, "{\"name\":\"" + bName + "\",\"currency\":\"ZZZ\"}", T, "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        putAs("/suppliers/" + b, "{\"name\":\"" + bName + "\",\"paymentTermsDays\":0}", T, "OWNER")
            .getStatus(),
        is(400));
    assertThat(putAs("/suppliers/" + b, "{\"name\":\"  \"}", T, "OWNER").getStatus(), is(400));
    // None of the refusals changed anything.
    assertThat(
        get("/suppliers/" + b, T).readEntity(String.class),
        containsString("\"name\":\"" + bName + "\""));
  }

  @Test
  void supplierCorrectionIsManagementOnlyAndTenantBound() {
    String id = supplier("Guarded Supplier " + Ids.newId(), "GBP");
    String body = "{\"name\":\"Guarded Supplier\",\"paymentTermsDays\":7}";
    assertThat(putAs("/suppliers/" + id, body, T, "CASHIER").getStatus(), is(403));
    assertThat(putAs("/suppliers/" + id, body, T, "STOREKEEPER").getStatus(), is(403));
    assertThat(putAs("/suppliers/" + id, body, T2, "OWNER").getStatus(), is(404));
    assertThat(putAs("/suppliers/" + Ids.newId(), body, T, "OWNER").getStatus(), is(404));
    assertThat(putAs("/suppliers/" + id, body, T, "MANAGER").getStatus(), is(200));
  }

  // ── SJ-D39: the invoice tells pricing-svc what box 4 is made of ─────────────

  private static java.util.List<String> outboxPayloads(String eventType) {
    var out = new java.util.ArrayList<String>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM purchase.outbox WHERE event_type = ? ORDER BY created_at")) {
      ps.setString(1, eventType);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  @Test
  void capturingAnInvoicePublishesItsVatForTheReturn() {
    String po = draftPurchaseOrder("Box Four Ltd");
    addLine(po, "60", "2.50");
    Response r =
        post(
            "/supplier-invoices",
            invoice(po, "INV-VAT-1", "60", "2.50")
                .replace("\"lines\"", "\"vatAmount\":30.00,\"lines\""),
            T);
    assertThat(r.getStatus(), is(201));
    String invoiceId = extractId(r.readEntity(String.class));
    var events = outboxPayloads("SupplierInvoiceCaptured");
    assertThat(events.size(), is(1));
    String e = events.get(0);
    // The event id is the invoice id: a redelivery is the same event, recorded once downstream.
    assertThat(e, containsString("\"eventId\":\"" + invoiceId + "\""));
    assertThat(e, containsString("\"tenantId\":\"" + T + "\""));
    assertThat(e, containsString("\"netAmount\":150.00"));
    assertThat(e, containsString("\"vatAmount\":30.00"));
    assertThat(e, containsString("\"grossAmount\":180.00"));
    assertThat(e, containsString("\"invoiceDate\":\"2026-02-01\""));
    assertThat(e, containsString("\"invoiceNumber\":\"INV-VAT-1\""));
    // An invoice that is refused publishes nothing: no lines, no invoice, no VAT.
    Response refused =
        post(
            "/supplier-invoices",
            "{\"poId\":\""
                + po
                + "\",\"invoiceNumber\":\"INV-VAT-2\",\"invoiceDate\":\"2026-02-01\",\"lines\":[]}",
            T);
    assertThat(refused.getStatus(), is(400));
    assertThat(outboxPayloads("SupplierInvoiceCaptured").size(), is(1));
  }

  // ── SJ-D53: a supplier set up in the tenant's own currency and country ──────

  @Test
  @org.junit.jupiter.api.DisplayName(
      "A supplier without a currency or country takes the tenant's; an undescribed tenant is refused")
  void aSupplierDefaultsToTheTenantsOwnCurrencyAndCountry() {
    Response r = post("/suppliers", "{\"name\":\"Chicago Imports\",\"vatRegistered\":false}", T2);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    assertThat(body, containsString("\"currency\":\"USD\""));
    assertThat(body, containsString("\"countryCode\":\"US\""));

    String nobody = "01a090ae-611e-70f0-8a00-0000000000d9";
    Response refused =
        post("/suppliers", "{\"name\":\"Nowhere Ltd\",\"vatRegistered\":false}", nobody);
    String refusedBody = refused.readEntity(String.class);
    assertThat(refusedBody, refused.getStatus(), is(503));
    assertThat(refusedBody, containsString("TENANT_PROFILE_UNAVAILABLE"));
    assertThat(get("/suppliers", nobody).readEntity(String.class), not(containsString("Nowhere")));
    // Naming both needs no profile.
    assertThat(
        post(
                "/suppliers",
                "{\"name\":\"Named Ltd\",\"vatRegistered\":false,\"currency\":\"EUR\",\"countryCode\":\"IE\"}",
                nobody)
            .getStatus(),
        is(201));
  }
}
