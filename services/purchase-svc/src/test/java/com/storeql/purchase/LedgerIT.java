package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting seam (17.1, 17.3, 04.7, 07.7): what a goods receipt, a supplier invoice, a
 * decision on a flagged one and a credit note write to the nominal ledger; the manual journal and
 * the trial balance over it; and the ways each is refused or abused.
 *
 * <p>Period control and the zone-to-GL mapping read inventory-svc, which is not here: discovery is
 * off, so both fail open and the receipt posts to the default stock code. The rule is unit tested
 * in {@code PeriodControlTest} and the live behaviour is driven by {@code k6/ledger-flow}.
 */
@HelidonTest
class LedgerIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(LedgerIT.T, "GBP", "GB").with(LedgerIT.T2, "GBP", "GB");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String STORE_A = "01a090ae-611e-703c-a378-a4972ea461c8";
  private static final String VARIANT = "01a090ae-611e-705c-994c-5daee3fbd033";
  private static final String USER = "01a090ae-611e-700b-bde4-50df0324c37c";

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
              + " purchase.vendor_return_lines, purchase.vendor_returns,"
              + " purchase.goods_receipt_lines, purchase.goods_receipts,"
              + " purchase.supplier_invoice_lines, purchase.supplier_invoices,"
              + " purchase.purchase_order_lines, purchase.purchase_orders,"
              + " purchase.suppliers, purchase.outbox CASCADE");
    }
  }

  // ── goods receipt: Dr Stock / Cr GR/IR ──────────────────────────────────────

  @Test
  @DisplayName("A goods receipt recognises the stock and accrues what is owed for it")
  void aGoodsReceiptPostsStockAgainstTheAccrual() {
    String po = submittedPo("GRN Ltd", 30, 10, "2.50");
    Response r = receive(po, "4");
    assertThat(r.getStatus(), is(201));
    String grId = extractId(r.readEntity(String.class));

    JsonArray stock = ledger("?code=1001");
    assertThat(stock.size(), is(1));
    JsonObject dr = stock.getJsonObject(0);
    assertThat(dr.getJsonNumber("debit").bigDecimalValue(), is(new BigDecimal("10.00")));
    assertThat(dr.getString("sourceType"), is("GOODS_RECEIPT"));
    assertThat(dr.getString("sourceRef"), is(grId));
    assertThat(dr.getString("storeId"), is(STORE_A));
    JsonArray grir = ledger("?code=2109");
    assertThat(grir.size(), is(1));
    JsonObject cr = grir.getJsonObject(0);
    assertThat(cr.getJsonNumber("credit").bigDecimalValue(), is(new BigDecimal("10.00")));
    assertThat(cr.getString("journalId"), is(dr.getString("journalId")));
    // A person reads this in the accounting package and on the Integrations screen: the order is
    // named as the procurement screen names it ("PO #" and the last eight of its id), never by
    // the whole id, which nobody can read or match by eye.
    String handle = po.substring(po.length() - 8);
    assertThat(dr.getString("description"), is("Goods received against PO #" + handle));
    assertThat(cr.getString("description"), is("Goods received against PO #" + handle));
    assertThat(dr.getString("description"), not(containsString(po)));

    // The receipt is idempotent on its key, and so is its posting.
    String key = Ids.newId().toString();
    Response again = receiveWithKey(po, "4", key);
    assertThat(again.getStatus(), is(201));
    Response replay = receiveWithKey(po, "4", key);
    assertThat(replay.getStatus(), is(201));
    assertThat(ledger("?code=1001").size(), is(2));
  }

  // ── supplier invoice: Dr GR/IR, Dr VAT / Cr Creditors, with a due date ──────

  @Test
  @DisplayName(
      "A matched invoice posts the creditor, dated the invoice, due by the supplier's terms")
  void aMatchedInvoicePostsTheCreditor() {
    String po = submittedPo("Terms Ltd", 45, 10, "2.50");
    assertThat(receive(po, "10").getStatus(), is(201));
    Response r =
        post("/supplier-invoices", invoice(po, "INV-45", "10", "2.50", "5.00", "30.00"), "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject inv = data(body);
    assertThat(inv.getString("status"), is("MATCHED"));
    assertThat(inv.getBoolean("payable"), is(true));
    assertThat(inv.getString("dueDate"), is("2026-03-18")); // 2026-02-01 + 45
    assertThat(inv.getString("postedAt", null), notNullValue());
    assertThat(inv.getJsonArray("headerVariances").size(), is(0));
    assertThat(inv.getJsonNumber("statedGross").bigDecimalValue(), is(new BigDecimal("30.00")));

    JsonArray lines = journalOf("SUPPLIER_INVOICE");
    assertThat(lines.size(), is(3));
    assertThat(sum(lines, "debit"), is(new BigDecimal("30.00")));
    assertThat(sum(lines, "credit"), is(new BigDecimal("30.00")));
    assertThat(lines.getJsonObject(0).getString("entryDate"), is("2026-02-01"));
    assertThat(
        ledger("?code=2100").getJsonObject(0).getJsonNumber("credit").bigDecimalValue(),
        is(new BigDecimal("30.00")));
    assertThat(
        ledger("?code=2201").getJsonObject(0).getJsonNumber("debit").bigDecimalValue(),
        is(new BigDecimal("5.00")));

    // GR/IR nets to nothing once both lorry and paperwork have arrived; the trial balance agrees.
    JsonObject tb = data(get("/nominal-ledger/trial-balance", "OWNER").readEntity(String.class));
    assertThat(tb.getBoolean("balanced"), is(true));
    assertThat(tb.getJsonNumber("totalDebit").bigDecimalValue(), is(new BigDecimal("55.00")));
    assertThat(row(tb, "2109").getJsonNumber("balance").bigDecimalValue().signum(), is(0));
    assertThat(
        row(tb, "1001").getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("25.00")));
    assertThat(
        row(tb, "2100").getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("-30.00")));
  }

  @Test
  @DisplayName("A stated total that does not add up flags the invoice, which is posted anyway")
  void aStatedTotalThatDoesNotAddUpIsFlagged() {
    String po = submittedPo("Adds Up Ltd", 30, 10, "2.50");
    assertThat(receive(po, "10").getStatus(), is(201));
    JsonObject inv =
        data(
            post(
                    "/supplier-invoices",
                    invoice(po, "INV-SUM", "10", "2.50", "5.00", "31.00"),
                    "OWNER")
                .readEntity(String.class));
    assertThat(inv.getString("status"), is("FLAGGED"));
    assertThat(inv.getBoolean("payable"), is(false));
    assertThat(inv.getJsonArray("headerVariances").getString(0), is("TOTAL_MISMATCH"));
    assertThat(inv.getJsonArray("lines").getJsonObject(0).getJsonArray("variances").size(), is(0));
    assertThat(inv.getString("postedAt", null), notNullValue());
    assertThat(journalOf("SUPPLIER_INVOICE").size(), is(3));
    // The queue awaiting a decision lists it; a status that is not one is refused.
    assertThat(
        get("/supplier-invoices?status=flagged", "OWNER").readEntity(String.class),
        containsString("INV-SUM"));
    assertThat(
        get("/supplier-invoices?status=MATCHED", "OWNER").readEntity(String.class),
        not(containsString("INV-SUM")));
    Response bad = get("/supplier-invoices?status=PAID", "OWNER");
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("PURCHASE_INVOICE_STATUS_UNKNOWN"));
    // A negative stated total is refused at the boundary.
    assertThat(
        post("/supplier-invoices", invoice(po, "INV-NEG", "10", "2.50", "5.00", "-1"), "OWNER")
            .getStatus(),
        is(400));
  }

  // ── the decision on a flagged invoice ───────────────────────────────────────

  @Test
  @DisplayName("A flagged invoice is approved with a reason; the posting stands; once only")
  void aFlaggedInvoiceIsApproved() {
    String po = submittedPo("Approve Ltd", 30, 10, "2.50");
    assertThat(receive(po, "4").getStatus(), is(201));
    String id =
        extractId(
            post("/supplier-invoices", invoice(po, "INV-APP", "10", "2.50", "0", null), "OWNER")
                .readEntity(String.class));
    // The refusals first: no reason, an action that is neither, a storekeeper, a stranger.
    assertThat(resolve(id, "APPROVE", "", "OWNER").getStatus(), is(400));
    Response unknown = resolve(id, "PAY", "because", "OWNER");
    assertThat(unknown.getStatus(), is(400));
    assertThat(unknown.readEntity(String.class), containsString("PURCHASE_RESOLUTION_UNKNOWN"));
    assertThat(resolve(id, "APPROVE", "fine", "STOREKEEPER").getStatus(), is(403));
    assertThat(resolveAs(id, "APPROVE", "fine", T2).getStatus(), is(404));

    Response ok = resolve(id, "approve", "Supplier confirmed the balance ships Friday", "MANAGER");
    String body = ok.readEntity(String.class);
    assertThat(body, ok.getStatus(), is(200));
    JsonObject inv = data(body);
    assertThat(inv.getString("status"), is("APPROVED"));
    assertThat(inv.getBoolean("payable"), is(true));
    assertThat(inv.getString("resolvedBy"), is(USER));
    assertThat(inv.getString("resolutionReason"), containsString("ships Friday"));
    assertThat(inv.getString("resolvedAt", null), notNullValue());
    assertThat(journalOf("INVOICE_REVERSAL").size(), is(0));

    Response twice = resolve(id, "REJECT", "changed my mind", "OWNER");
    assertThat(twice.getStatus(), is(409));
    assertThat(twice.readEntity(String.class), containsString("PURCHASE_INVOICE_NOT_FLAGGED"));
  }

  @Test
  @DisplayName("A matched invoice has nothing to decide")
  void aMatchedInvoiceCannotBeDecided() {
    String po = submittedPo("Clean Ltd", 30, 10, "2.50");
    assertThat(receive(po, "10").getStatus(), is(201));
    String id =
        extractId(
            post("/supplier-invoices", invoice(po, "INV-OK", "10", "2.50", "0", null), "OWNER")
                .readEntity(String.class));
    Response r = resolve(id, "APPROVE", "why not", "OWNER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_INVOICE_NOT_FLAGGED"));
  }

  @Test
  @DisplayName(
      "A rejected invoice is reversed, leaves the return, and frees the order for a correct one")
  void aRejectedInvoiceIsReversedAndFreesTheOrder() throws Exception {
    String po = submittedPo("Reject Ltd", 30, 10, "2.50");
    assertThat(receive(po, "4").getStatus(), is(201));
    String id =
        extractId(
            post("/supplier-invoices", invoice(po, "INV-BAD", "10", "2.50", "5.00", null), "OWNER")
                .readEntity(String.class));
    JsonObject inv =
        data(
            resolve(id, "REJECT", "Billed for six that never came", "OWNER")
                .readEntity(String.class));
    assertThat(inv.getString("status"), is("REJECTED"));
    assertThat(inv.getBoolean("payable"), is(false));

    JsonArray reversal = journalOf("INVOICE_REVERSAL");
    assertThat(reversal.size(), is(3));
    assertThat(reversal.getJsonObject(0).getString("description"), containsString("never came"));
    assertThat(reversal.getJsonObject(0).getString("sourceRef"), is(id));
    JsonObject tb = data(get("/nominal-ledger/trial-balance", "OWNER").readEntity(String.class));
    assertThat(tb.getBoolean("balanced"), is(true));
    assertThat(row(tb, "2100").getJsonNumber("balance").bigDecimalValue().signum(), is(0));
    assertThat(row(tb, "2201").getJsonNumber("balance").bigDecimalValue().signum(), is(0));
    // GR/IR carries only the receipt's accrual again: 10.00 credit.
    assertThat(
        row(tb, "2109").getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("-10.00")));

    // pricing-svc is told, once, on the same topic the capture went out on.
    assertThat(outboxTypes(id), containsString("SupplierInvoiceRejected"));

    // The rejected quantities no longer count: the corrected invoice matches cleanly.
    JsonObject fixed =
        data(
            post("/supplier-invoices", invoice(po, "INV-FIXED", "4", "2.50", "2.00", null), "OWNER")
                .readEntity(String.class));
    assertThat(fixed.getString("status"), is("MATCHED"));
    assertThat(
        fixed.getJsonArray("lines").getJsonObject(0).getJsonNumber("qtyInvoicedBefore").intValue(),
        is(0));
    assertThat(
        get("/supplier-invoices?status=REJECTED", "OWNER").readEntity(String.class),
        containsString("INV-BAD"));
  }

  @Test
  @DisplayName("Twenty managers deciding at once produce one decision and one reversal")
  void twentyDecisionsAtOnceProduceOne() throws Exception {
    String po = submittedPo("Race Ltd", 30, 10, "2.50");
    assertThat(receive(po, "4").getStatus(), is(201));
    String id =
        extractId(
            post("/supplier-invoices", invoice(po, "INV-RACE", "10", "2.50", "5.00", null), "OWNER")
                .readEntity(String.class));
    int n = 20;
    var pool = Executors.newFixedThreadPool(n);
    var go = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return resolve(id, "REJECT", "race", "OWNER").getStatus();
              }));
    }
    go.countDown();
    int won = 0;
    int lost = 0;
    for (Future<Integer> f : results) {
      int s = f.get();
      if (s == 200) won++;
      else if (s == 409) lost++;
      else throw new AssertionError("unexpected status " + s);
    }
    pool.shutdown();
    assertThat(won, is(1));
    assertThat(lost, is(n - 1));
    assertThat(journalOf("INVOICE_REVERSAL").size(), is(3));
    JsonObject tb = data(get("/nominal-ledger/trial-balance", "OWNER").readEntity(String.class));
    assertThat(tb.getBoolean("balanced"), is(true));
  }

  // ── credit note: Dr Creditors / Cr Stock, Cr VAT ────────────────────────────

  @Test
  @DisplayName("A credit note against a return reverses the creditor for what was credited")
  void aCreditNoteReversesTheCreditor() {
    String po = submittedPo("RTV Ltd", 30, 10, "2.50");
    assertThat(receive(po, "10").getStatus(), is(201));
    Response raised =
        post(
            "/vendor-returns",
            "{\"poId\":\""
                + po
                + "\",\"reason\":\"DAMAGED\",\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qty\":4}]}",
            "OWNER");
    String raisedBody = raised.readEntity(String.class);
    assertThat(raisedBody, raised.getStatus(), is(201));
    String ret = extractId(raisedBody);
    Response credited =
        post(
            "/vendor-returns/" + ret + "/credit",
            "{\"creditNoteNumber\":\"CN-1\",\"creditNoteDate\":\"2026-02-10\"}",
            "OWNER");
    assertThat(credited.getStatus(), is(200));
    JsonArray lines = journalOf("CREDIT_NOTE");
    // 4 × 2.50 with no VAT reachable here: Dr Creditors 10.00 / Cr Stock 10.00, VAT line absent.
    assertThat(lines.size(), is(2));
    assertThat(sum(lines, "debit"), is(new BigDecimal("10.00")));
    assertThat(sum(lines, "credit"), is(new BigDecimal("10.00")));
    assertThat(lines.getJsonObject(0).getString("nominalCode"), is("2100"));
    assertThat(lines.getJsonObject(0).getString("entryDate"), is("2026-02-10"));
    assertThat(lines.getJsonObject(1).getString("nominalCode"), is("1001"));
    // Credited twice is refused, and posts nothing twice.
    assertThat(
        post(
                "/vendor-returns/" + ret + "/credit",
                "{\"creditNoteNumber\":\"CN-2\",\"creditNoteDate\":\"2026-02-11\"}",
                "OWNER")
            .getStatus(),
        is(409));
    assertThat(journalOf("CREDIT_NOTE").size(), is(2));
  }

  // ── manual journals ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("A balanced journal is posted, read back whole, and appears on the trial balance")
  void aBalancedJournalIsPosted() {
    Response r =
        post(
            "/nominal-ledger/journals",
            journal(
                "2026-02-05",
                "Opening stock",
                "[{\"nominalCode\":\"1001\",\"nominalName\":\"Stock\",\"debit\":500},"
                    + "{\"nominalCode\":\"3000\",\"nominalName\":\"Capital\",\"credit\":500}]"),
            "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject j = data(body);
    String journalId = j.getString("journalId");
    assertThat(j.getString("sourceType"), is("JOURNAL"));
    assertThat(j.getJsonNumber("totalDebit").bigDecimalValue(), is(new BigDecimal("500")));
    assertThat(j.getJsonNumber("totalCredit").bigDecimalValue(), is(new BigDecimal("500")));
    assertThat(j.getJsonArray("lines").size(), is(2));
    // JSON-B omits a null field: a tenant-level journal carries no storeId key at all.
    assertThat(j.containsKey("storeId"), is(false));

    JsonObject read =
        data(get("/nominal-ledger/journals/" + journalId, "MANAGER").readEntity(String.class));
    assertThat(read.getString("description"), is("Opening stock"));
    assertThat(read.getString("entryDate"), is("2026-02-05"));
    assertThat(read.getJsonArray("lines").getJsonObject(1).getString("nominalName"), is("Capital"));

    JsonObject tb =
        data(
            get("/nominal-ledger/trial-balance?from=2026-02-01&to=2026-02-28", "OWNER")
                .readEntity(String.class));
    assertThat(tb.getBoolean("balanced"), is(true));
    assertThat(
        row(tb, "3000").getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("-500")));
    // Outside the range, nothing.
    JsonObject empty =
        data(
            get("/nominal-ledger/trial-balance?from=2026-03-01&to=2026-03-31", "OWNER")
                .readEntity(String.class));
    assertThat(empty.getJsonArray("rows").size(), is(0));
    assertThat(empty.getBoolean("balanced"), is(true));
    // A store-scoped journal carries its store.
    Response scoped =
        post(
            "/nominal-ledger/journals",
            "{\"entryDate\":\"2026-02-06\",\"description\":\"Store adjustment\",\"storeId\":\""
                + STORE_A
                + "\",\"lines\":[{\"nominalCode\":\"1001\",\"debit\":1},{\"nominalCode\":\"5000\",\"credit\":1}]}",
            "OWNER");
    assertThat(scoped.getStatus(), is(201));
    assertThat(data(scoped.readEntity(String.class)).getString("storeId"), is(STORE_A));
    assertThat(
        data(get("/nominal-ledger/trial-balance?storeId=" + STORE_A, "OWNER")
                .readEntity(String.class))
            .getJsonNumber("totalDebit")
            .bigDecimalValue(),
        is(new BigDecimal("1")));
  }

  @Test
  @DisplayName("A journal that does not balance is refused, and so is every malformed line")
  void badJournalsAreRefused() {
    String lines2 =
        "[{\"nominalCode\":\"1001\",\"debit\":100},{\"nominalCode\":\"3000\",\"credit\":99.99}]";
    Response unbalanced =
        post("/nominal-ledger/journals", journal("2026-02-05", "x", lines2), "OWNER");
    assertThat(unbalanced.getStatus(), is(422));
    assertThat(unbalanced.readEntity(String.class), containsString("PURCHASE_JOURNAL_UNBALANCED"));

    Response both =
        post(
            "/nominal-ledger/journals",
            journal(
                "2026-02-05",
                "x",
                "[{\"nominalCode\":\"1001\",\"debit\":100,\"credit\":100},{\"nominalCode\":\"3000\",\"credit\":0}]"),
            "OWNER");
    assertThat(both.getStatus(), is(400));
    assertThat(both.readEntity(String.class), containsString("PURCHASE_JOURNAL_LINE_INVALID"));

    Response oneLine =
        post(
            "/nominal-ledger/journals",
            journal("2026-02-05", "x", "[{\"nominalCode\":\"1001\",\"debit\":100}]"),
            "OWNER");
    assertThat(oneLine.getStatus(), is(400));

    Response zeroes =
        post(
            "/nominal-ledger/journals",
            journal(
                "2026-02-05",
                "x",
                "[{\"nominalCode\":\"1001\",\"debit\":0},{\"nominalCode\":\"3000\",\"credit\":0}]"),
            "OWNER");
    assertThat(zeroes.getStatus(), is(400));

    Response negative =
        post(
            "/nominal-ledger/journals",
            journal(
                "2026-02-05",
                "x",
                "[{\"nominalCode\":\"1001\",\"debit\":-5},{\"nominalCode\":\"3000\",\"debit\":5}]"),
            "OWNER");
    assertThat(negative.getStatus(), is(400));

    Response badCode =
        post(
            "/nominal-ledger/journals",
            journal(
                "2026-02-05",
                "x",
                "[{\"nominalCode\":\"10 01\",\"debit\":5},{\"nominalCode\":\"3000\",\"credit\":5}]"),
            "OWNER");
    assertThat(badCode.getStatus(), is(400));
    assertThat(badCode.readEntity(String.class), containsString("PURCHASE_JOURNAL_LINE_INVALID"));

    Response badDate = post("/nominal-ledger/journals", journal("5 Feb", "x", lines2), "OWNER");
    assertThat(badDate.getStatus(), is(400));

    Response noDescription =
        post("/nominal-ledger/journals", journal("2026-02-05", "", lines2), "OWNER");
    assertThat(noDescription.getStatus(), is(400));

    Response badStore =
        post(
            "/nominal-ledger/journals",
            "{\"entryDate\":\"2026-02-05\",\"description\":\"x\",\"storeId\":\"here\",\"lines\":"
                + lines2
                + "}",
            "OWNER");
    assertThat(badStore.getStatus(), is(400));

    // Fifty-one lines: a data load, not a journal.
    StringBuilder many = new StringBuilder("[");
    for (int i = 0; i < 51; i++) {
      if (i > 0) many.append(',');
      many.append("{\"nominalCode\":\"1001\",\"debit\":1}");
    }
    many.append("]");
    assertThat(
        post("/nominal-ledger/journals", journal("2026-02-05", "x", many.toString()), "OWNER")
            .getStatus(),
        is(400));

    // Nothing of any of that reached the ledger.
    assertThat(ledger("").size(), is(0));
  }

  @Test
  @DisplayName("Journals and the trial balance are finance's: staff cannot post or read them")
  void financeOnly() {
    String lines2 =
        "[{\"nominalCode\":\"1001\",\"debit\":100},{\"nominalCode\":\"3000\",\"credit\":100}]";
    assertThat(
        post("/nominal-ledger/journals", journal("2026-02-05", "x", lines2), "STOREKEEPER")
            .getStatus(),
        is(403));
    assertThat(
        post("/nominal-ledger/journals", journal("2026-02-05", "x", lines2), "CASHIER").getStatus(),
        is(403));
    assertThat(get("/nominal-ledger/trial-balance", "STOREKEEPER").getStatus(), is(403));
    assertThat(get("/nominal-ledger/trial-balance", "CASHIER").getStatus(), is(403));
    String id =
        extractId(
            post("/nominal-ledger/journals", journal("2026-02-05", "x", lines2), "OWNER")
                .readEntity(String.class)
                .replace("\"journalId\"", "\"id\""));
    assertThat(get("/nominal-ledger/journals/" + id, "CASHIER").getStatus(), is(403));
    // Another tenant does not see it, and cannot guess it.
    assertThat(getAs("/nominal-ledger/journals/" + id, T2, "OWNER").getStatus(), is(404));
    assertThat(get("/nominal-ledger/journals/" + Ids.newId(), "OWNER").getStatus(), is(404));
    assertThat(
        getAs("/nominal-ledger/trial-balance", T2, "OWNER").readEntity(String.class),
        containsString("\"rows\":[]"));
    // The ledger's lines stay readable by any member of staff, as before.
    assertThat(get("/nominal-ledger", "STOREKEEPER").getStatus(), is(200));
  }

  @Test
  @DisplayName("A trial balance range that ends before it starts is refused")
  void backwardsRange() {
    Response r = get("/nominal-ledger/trial-balance?from=2026-03-01&to=2026-02-01", "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_INVALID_PERIOD"));
    assertThat(get("/nominal-ledger/trial-balance?from=March", "OWNER").getStatus(), is(400));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private String submittedPo(String supplierName, int termsDays, int qty, String unitPrice) {
    Response sup =
        post(
            "/suppliers",
            "{\"name\":\""
                + supplierName
                + "\",\"currency\":\"GBP\",\"paymentTermsDays\":"
                + termsDays
                + "}",
            "OWNER");
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
            "OWNER");
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));
    assertThat(
        post(
                "/purchase-orders/" + poId + "/lines",
                "{\"variantId\":\""
                    + VARIANT
                    + "\",\"qty\":"
                    + qty
                    + ",\"unitPrice\":"
                    + unitPrice
                    + ",\"vatCode\":\"T1\"}",
                "OWNER")
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}", "OWNER").getStatus(), is(200));
    return poId;
  }

  private Response receive(String poId, String qty) {
    return receiveWithKey(poId, qty, null);
  }

  private Response receiveWithKey(String poId, String qty, String key) {
    var req =
        target
            .path("/goods-receipts")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", USER)
            .header("X-Roles", "OWNER");
    if (key != null) req = req.header("Idempotency-Key", key);
    return req.post(
        Entity.entity(
            "{\"poId\":\""
                + poId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"lines\":[{\"variantId\":\""
                + VARIANT
                + "\",\"qtyReceived\":"
                + qty
                + "}]}",
            MediaType.APPLICATION_JSON));
  }

  private static String invoice(
      String poId, String number, String qty, String unitPrice, String vat, String statedGross) {
    return "{\"poId\":\""
        + poId
        + "\",\"invoiceNumber\":\""
        + number
        + "\",\"invoiceDate\":\"2026-02-01\",\"vatAmount\":"
        + vat
        + (statedGross == null ? "" : ",\"statedGross\":" + statedGross)
        + ",\"lines\":[{\"variantId\":\""
        + VARIANT
        + "\",\"qty\":"
        + qty
        + ",\"unitPrice\":"
        + unitPrice
        + "}]}";
  }

  private static String journal(String date, String description, String lines) {
    return "{\"entryDate\":\""
        + date
        + "\",\"description\":\""
        + description
        + "\",\"lines\":"
        + lines
        + "}";
  }

  private Response resolve(String id, String action, String reason, String role) {
    return postAs(
        "/supplier-invoices/" + id + "/resolve",
        "{\"action\":\"" + action + "\",\"reason\":\"" + reason + "\"}",
        T,
        role);
  }

  private Response resolveAs(String id, String action, String reason, String tenant) {
    return postAs(
        "/supplier-invoices/" + id + "/resolve",
        "{\"action\":\"" + action + "\",\"reason\":\"" + reason + "\"}",
        tenant,
        "OWNER");
  }

  private Response post(String path, String json, String role) {
    return postAs(path, json, T, role);
  }

  private Response postAs(String path, String json, String tenant, String role) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery, String role) {
    return getAs(pathAndQuery, T, role);
  }

  private Response getAs(String pathAndQuery, String tenant, String role) {
    WebTarget t = com.storeql.test.WebTargets.at(target, pathAndQuery);
    return t.request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role)
        .get();
  }

  private JsonArray ledger(String query) {
    Response r = get("/nominal-ledger" + query, "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  /** The lines of the ledger written by one source type, in order. */
  private JsonArray journalOf(String sourceType) {
    var out = Json.createArrayBuilder();
    for (JsonValue v : ledger("?limit=100")) {
      JsonObject o = v.asJsonObject();
      if (sourceType.equals(o.getString("sourceType", null))) out.add(o);
    }
    return out.build();
  }

  private static JsonObject data(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static JsonObject row(JsonObject trialBalance, String code) {
    for (JsonValue v : trialBalance.getJsonArray("rows")) {
      if (code.equals(v.asJsonObject().getString("nominalCode"))) return v.asJsonObject();
    }
    throw new AssertionError("no trial balance row for " + code + " in " + trialBalance);
  }

  private static BigDecimal sum(JsonArray lines, String field) {
    BigDecimal total = BigDecimal.ZERO;
    for (JsonValue v : lines)
      total = total.add(v.asJsonObject().getJsonNumber(field).bigDecimalValue());
    return total;
  }

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private String outboxTypes(String aggregateId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT event_type FROM purchase.outbox WHERE aggregate_id = ?::uuid ORDER BY created_at")) {
      ps.setString(1, aggregateId);
      var rs = ps.executeQuery();
      StringBuilder sb = new StringBuilder();
      while (rs.next()) sb.append(rs.getString(1)).append(',');
      return sb.toString();
    }
  }
}
