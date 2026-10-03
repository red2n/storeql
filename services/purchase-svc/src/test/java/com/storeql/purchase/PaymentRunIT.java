package com.storeql.purchase;

import static com.storeql.purchase.PurchaseFixtures.T;
import static com.storeql.purchase.PurchaseFixtures.T2;
import static com.storeql.purchase.PurchaseFixtures.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Supplier payment runs and remittance (17.10): proposed from what is due, approved by a second
 * manager, filed with the bank, paid once with a posting that clears the creditor, advised to each
 * supplier — and every way that is refused or abused: the proposer approving their own run, twenty
 * payments at once, proposals racing for the same invoices, bank details changed after approval,
 * another tenant, and bad input.
 *
 * <p>Period control reads inventory-svc, which is not here, so it fails open; the rule is unit
 * tested in {@code PeriodControlTest}. The remittance email itself is sent by notification-svc and
 * is tested there; here the advice is read from the outbox.
 */
@HelidonTest
class PaymentRunIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String USER2 = "01a090ae-611e-7a2b-8c3d-4e5f60718293";
  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  private static final String UK_BANK =
      "\"bankAccountName\":\"Acme Ltd\",\"bankSortCode\":\"12-34-56\",\"bankAccountNumber\":\"31415926\"";
  private static final String DE_BANK =
      "\"bankAccountName\":\"Muster GmbH\",\"bankIban\":\"DE89 3704 0044 0532 0130 00\",\"bankBic\":\"deutdeff\"";

  private record Inv(String poId, String id) {}

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncateTables() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── the run, end to end ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A run is proposed from what is due, approved by a second manager, and paid once")
  void proposeApproveAndPay() throws Exception {
    String acme = supplier("Acme Ltd", 30, UK_BANK, "accounts@acme.example");
    String muster = supplier("Muster GmbH", 30, DE_BANK, null);
    String noBank = supplier("No Bank Ltd", 30, null, null);
    Inv a1 = payableInvoice(acme, "INV-A1", 10, "2.50", "5.00", TODAY.minusDays(60));
    payableInvoice(acme, "INV-A2", 4, "2.50", "0", TODAY.minusDays(45));
    Inv a3 = payableInvoice(acme, "INV-A3", 12, "2.50", "0", TODAY);
    payableInvoice(muster, "INV-B1", 8, "2.50", "0", TODAY.minusDays(40));
    payableInvoice(noBank, "INV-C1", 4, "3.00", "0", TODAY.minusDays(40));
    creditedReturn(a1.poId(), 2, "CN-A");

    Response r = propose(TODAY.toString(), TODAY.toString(), null, "MANAGER", USER);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject run = data(body);
    String runId = run.getString("id");
    String ref = run.getString("reference");
    assertThat(ref, ref.matches("PAY\\d{6}-[0-9A-F]{6}"), is(true));
    assertThat(run.getString("status"), is("PROPOSED"));
    // 30.00 + 10.00 - 5.00 credit for Acme; 20.00 for Muster; INV-A3 is not due; No Bank is not
    // paid.
    assertMoney(run, "total", "55");
    JsonArray suppliers = run.getJsonArray("suppliers");
    assertThat(suppliers.size(), is(2));
    JsonObject acmePayment = suppliers.getJsonObject(0);
    assertThat(acmePayment.getString("name"), is("Acme Ltd"));
    assertMoney(acmePayment, "net", "35");
    JsonArray acmeDocs = acmePayment.getJsonArray("documents");
    assertThat(acmeDocs.size(), is(3));
    assertThat(acmeDocs.getJsonObject(2).getString("type"), is("CREDIT_NOTE"));
    assertThat(acmePayment.getBoolean("remittanceEmailOnFile"), is(true));
    // Both suppliers' bank details were keyed minutes ago: paid, but flagged for a phone call.
    assertThat(
        acmePayment.getJsonArray("warnings").getString(0), is("BANK_DETAILS_CHANGED_RECENTLY"));
    assertThat(suppliers.getJsonObject(1).getBoolean("remittanceEmailOnFile"), is(false));
    JsonArray excluded = run.getJsonArray("excluded");
    assertThat(excluded.size(), is(1));
    assertThat(excluded.getJsonObject(0).getString("name"), is("No Bank Ltd"));
    assertThat(excluded.getJsonObject(0).getString("reason"), is("NO_BANK_DETAILS"));
    assertThat(body, not(containsString("31415926")));

    // What a run holds is reserved: proposing again finds only the supplier it cannot pay.
    Response again = propose(TODAY.toString(), TODAY.toString(), null, "MANAGER", USER);
    String againBody = again.readEntity(String.class);
    assertThat(againBody, again.getStatus(), is(409));
    assertThat(againBody, containsString("PURCHASE_PAYMENT_RUN_NOTHING_DUE"));
    assertThat(againBody, containsString("No Bank Ltd: NO_BANK_DETAILS"));

    // Nothing is filed or paid before approval, and the proposer does not approve their own run.
    assertCode(
        get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_NOT_APPROVED");
    assertCode(
        post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER2),
        409,
        "PURCHASE_PAYMENT_RUN_NOT_APPROVED");
    assertCode(
        post("/payment-runs/" + runId + "/approve", "{}", "MANAGER", USER),
        403,
        "PURCHASE_PAYMENT_RUN_SELF_APPROVAL");
    Response approved = post("/payment-runs/" + runId + "/approve", "{}", "MANAGER", USER2);
    String approvedBody = approved.readEntity(String.class);
    assertThat(approvedBody, approved.getStatus(), is(200));
    assertThat(data(approvedBody).getString("status"), is("APPROVED"));
    assertCode(
        post("/payment-runs/" + runId + "/approve", "{}", "MANAGER", USER2),
        409,
        "PURCHASE_PAYMENT_RUN_ALREADY_APPROVED");

    // The bank file: one payment per supplier with the account in full, never cached.
    Response file = get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER2);
    assertThat(file.getStatus(), is(200));
    assertThat(file.getHeaderString("Content-Type"), startsWith("text/csv"));
    assertThat(file.getHeaderString("Cache-Control"), is("no-store"));
    assertThat(
        file.getHeaderString("Content-Disposition"),
        containsString(ref.toLowerCase(Locale.ROOT) + ".csv"));
    String[] rows = file.readEntity(String.class).split("\r\n");
    assertThat(rows.length, is(3));
    assertThat(
        rows[0], is("payee_name,sort_code,account_number,iban,bic,amount,currency,reference"));
    assertThat(rows[1], startsWith("Acme Ltd,123456,31415926,,,"));
    assertThat(rows[1], containsString(",GBP," + ref));
    assertThat(new BigDecimal(rows[1].split(",")[5]).compareTo(new BigDecimal("35")), is(0));
    assertThat(rows[2], startsWith("Muster GmbH,,,DE89370400440532013000,DEUTDEFF,"));

    // Paid: the creditor cleared against the bank, dated the payment date, per supplier.
    Response paid = post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER);
    String paidBody = paid.readEntity(String.class);
    assertThat(paidBody, paid.getStatus(), is(200));
    assertThat(data(paidBody).getString("status"), is("PAID"));
    JsonArray lines = journalOf("SUPPLIER_PAYMENT");
    assertThat(lines.size(), is(4));
    assertThat(sum(lines, "debit").compareTo(new BigDecimal("55")), is(0));
    assertThat(sum(lines, "credit").compareTo(new BigDecimal("55")), is(0));
    for (JsonValue v : lines) {
      JsonObject line = v.asJsonObject();
      assertThat(line.getString("entryDate"), is(TODAY.toString()));
      assertThat(line.getString("sourceRef"), is(runId));
      boolean debit = line.getJsonNumber("debit").bigDecimalValue().signum() > 0;
      assertThat(line.getString("nominalCode"), is(debit ? "2100" : "1200"));
    }
    JsonObject settled =
        data(get("/supplier-invoices/" + a1.id(), "OWNER", USER).readEntity(String.class));
    assertThat(settled.getBoolean("paid"), is(true));
    assertThat(settled.getString("paymentRunId"), is(runId));
    assertThat(
        data(get("/supplier-invoices/" + a3.id(), "OWNER", USER).readEntity(String.class))
            .getBoolean("paid"),
        is(false));

    // One remittance advice per supplier, carrying what the payment settles.
    List<JsonObject> advices = outboxPayloads("SupplierRemittanceIssued");
    assertThat(advices.size(), is(2));
    JsonObject acmeAdvice =
        advices.stream()
            .filter(o -> "Acme Ltd".equals(o.getString("supplierName")))
            .findFirst()
            .orElseThrow();
    assertThat(acmeAdvice.getString("remittanceEmail"), is("accounts@acme.example"));
    assertThat(acmeAdvice.getString("runReference"), is(ref));
    assertThat(acmeAdvice.getJsonArray("items").size(), is(3));
    assertMoney(acmeAdvice, "total", "35");

    // Once only; a paid run is not cancelled; its bank file can still be read.
    assertCode(
        post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_ALREADY_PAID");
    assertCode(
        post("/payment-runs/" + runId + "/cancel", "{\"reason\":\"too late\"}", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_ALREADY_PAID");
    assertThat(journalOf("SUPPLIER_PAYMENT").size(), is(4));
    assertThat(get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER).getStatus(), is(200));

    // The next run pays what has fallen due since, and does not offset the credit note twice.
    Response next = propose(TODAY.plusDays(40).toString(), TODAY.toString(), null, "MANAGER", USER);
    String nextBody = next.readEntity(String.class);
    assertThat(nextBody, next.getStatus(), is(201));
    JsonObject nextRun = data(nextBody);
    assertMoney(nextRun, "total", "30");
    assertThat(
        nextRun.getJsonArray("suppliers").getJsonObject(0).getJsonArray("documents").size(), is(1));

    // Listed newest first, by the proposal time the service stored — compared as stored, because
    // two runs a few milliseconds apart can swap under a wall clock that steps backwards; a status
    // narrows the list and an unknown one is refused.
    JsonArray all = dataArray(get("/payment-runs", "MANAGER", USER).readEntity(String.class));
    assertThat(all.size(), is(2));
    assertThat(
        all.toString(),
        all.toString().contains(nextRun.getString("id")) && all.toString().contains(runId),
        is(true));
    java.time.Instant newer = java.time.Instant.parse(all.getJsonObject(0).getString("proposedAt"));
    java.time.Instant older = java.time.Instant.parse(all.getJsonObject(1).getString("proposedAt"));
    assertThat(all.toString(), newer.isBefore(older), is(false));
    assertThat(
        dataArray(get("/payment-runs?status=paid", "MANAGER", USER).readEntity(String.class))
            .size(),
        is(1));
    assertCode(
        get("/payment-runs?status=SENT", "MANAGER", USER),
        400,
        "PURCHASE_PAYMENT_RUN_STATUS_UNKNOWN");
  }

  // ── abuse: concurrency ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Twenty payments of the same run at once pay it once")
  void concurrentPaymentsPayOnce() throws Exception {
    // The bank file pays the account holder as the bank has them, so that is where a formula hides.
    String sup =
        supplier(
            "Formula Ltd",
            30,
            "\"bankAccountName\":\"=SUM(A1) Ltd\",\"bankSortCode\":\"123456\",\"bankAccountNumber\":\"31415926\"",
            null);
    payableInvoice(sup, "INV-RACE", 4, "5.00", "0", TODAY.minusDays(40));
    String runId = proposedAndApproved();

    List<Integer> statuses =
        PaymentRunSteps.inParallel(
            20,
            () -> {
              try (Response r = post("/payment-runs/" + runId + "/pay", "{}", "OWNER", USER)) {
                return r.getStatus();
              }
            });
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 200).count(), is(1L));
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 409).count(), is(19L));
    assertThat(journalOf("SUPPLIER_PAYMENT").size(), is(2));
    assertThat(outboxPayloads("SupplierRemittanceIssued").size(), is(1));
    // A payee named like a formula reaches the bank file as text, not as a formula.
    String[] rows =
        get("/payment-runs/" + runId + "/bank-file", "OWNER", USER)
            .readEntity(String.class)
            .split("\r\n");
    assertThat(rows[1], startsWith("'=SUM(A1) Ltd,"));
  }

  @Test
  @DisplayName("Ten proposals racing for the same invoices produce one run that holds each once")
  void racingProposalsHoldEachDocumentOnce() throws Exception {
    String sup = supplier("Race Ltd", 30, UK_BANK, null);
    for (int i = 1; i <= 3; i++) {
      payableInvoice(sup, "INV-R" + i, 2, "5.00", "0", TODAY.minusDays(40));
    }
    List<String> outcomes =
        PaymentRunSteps.inParallel(
            10,
            () -> {
              try (Response r = propose(TODAY.toString(), TODAY.toString(), null, "OWNER", USER)) {
                return r.getStatus() + " " + r.readEntity(String.class);
              }
            });
    assertThat(
        outcomes.toString(), outcomes.stream().filter(o -> o.startsWith("201")).count(), is(1L));
    assertThat(
        outcomes.toString(), outcomes.stream().filter(o -> o.startsWith("409")).count(), is(9L));
    assertThat(
        outcomes.stream()
            .filter(o -> o.startsWith("409"))
            .allMatch(
                o ->
                    o.contains("PURCHASE_PAYMENT_RUN_NOTHING_DUE")
                        || o.contains("PURCHASE_PAYMENT_RUN_CONFLICT")),
        is(true));
    assertThat(dbCount("SELECT count(*) FROM purchase.payment_runs"), is(1L));
    assertThat(dbCount("SELECT count(*) FROM purchase.payment_run_items WHERE open"), is(3L));
  }

  // ── cancellation ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A cancelled run keeps its reason and frees its invoices for the next run")
  void cancelReleasesDocuments() {
    String sup = supplier("Cancel Ltd", 30, UK_BANK, null);
    payableInvoice(sup, "INV-X1", 2, "5.00", "0", TODAY.minusDays(40));
    String runId = proposedAndApproved();

    assertThat(
        post("/payment-runs/" + runId + "/cancel", "{\"reason\":\"  \"}", "MANAGER", USER)
            .getStatus(),
        is(400));
    assertThat(
        post("/payment-runs/" + runId + "/cancel", "{}", "MANAGER", USER).getStatus(), is(400));
    Response cancelled =
        post(
            "/payment-runs/" + runId + "/cancel",
            "{\"reason\":\"Supplier disputes INV-X1\"}",
            "MANAGER",
            USER);
    String body = cancelled.readEntity(String.class);
    assertThat(body, cancelled.getStatus(), is(200));
    assertThat(data(body).getString("status"), is("CANCELLED"));
    assertThat(data(body).getString("cancelReason"), is("Supplier disputes INV-X1"));
    // Shown as it was: its supplier, nothing excluded after the fact.
    assertThat(data(body).getJsonArray("suppliers").size(), is(1));

    assertCode(
        post("/payment-runs/" + runId + "/cancel", "{\"reason\":\"again\"}", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_CANCELLED");
    assertCode(
        post("/payment-runs/" + runId + "/approve", "{}", "MANAGER", USER2),
        409,
        "PURCHASE_PAYMENT_RUN_CANCELLED");
    assertCode(
        post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER2),
        409,
        "PURCHASE_PAYMENT_RUN_CANCELLED");
    assertCode(
        get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER2),
        409,
        "PURCHASE_PAYMENT_RUN_CANCELLED");

    Response again = propose(TODAY.toString(), TODAY.toString(), null, "MANAGER", USER);
    String againBody = again.readEntity(String.class);
    assertThat(againBody, again.getStatus(), is(201));
    assertThat(
        data(againBody).getJsonArray("suppliers").getJsonObject(0).getJsonArray("documents").size(),
        is(1));
  }

  // ── abuse: payment diversion ────────────────────────────────────────────────

  /**
   * The change stamp is the service's clock and the approval the database's. Two clocks can
   * disagree, so a change is never judged by comparing them: a change made after approval but
   * stamped before it (the service's clock behind) still stops the run.
   */
  @Test
  @DisplayName("A change after approval stops the run even when its stamp reads earlier")
  void aChangeAfterApprovalIsCaughtWhateverItsStampSays() throws Exception {
    String sup = supplier("Skewed Ltd", 30, UK_BANK, null);
    payableInvoice(sup, "INV-S1", 2, "5.00", "0", TODAY.minusDays(40));
    String runId = proposedAndApproved();
    Response changed =
        put(
            "/suppliers/" + sup,
            "{\"name\":\"Skewed Ltd\",\"bankAccountName\":\"Skewed Ltd\","
                + "\"bankSortCode\":\"654321\",\"bankAccountNumber\":\"12345678\"}",
            "OWNER",
            USER);
    assertThat(changed.readEntity(String.class), changed.getStatus(), is(200));
    dbExec(
        "UPDATE purchase.suppliers SET bank_details_changed_at = r.approved_at - interval '1 hour'"
            + " FROM purchase.payment_runs r WHERE r.id = '"
            + runId
            + "' AND suppliers.id = '"
            + sup
            + "'");

    assertCode(
        get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED");
    Response pay = post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER);
    String payBody = pay.readEntity(String.class);
    assertThat(payBody, pay.getStatus(), is(409));
    assertThat(payBody, containsString("PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED"));
    assertThat(journalOf("SUPPLIER_PAYMENT").size(), is(0));
  }

  /** And details settled before approval never stop it, however late their stamp reads. */
  @Test
  @DisplayName("Details settled before approval never stop the run, whatever their stamp says")
  void detailsSettledBeforeApprovalNeverStopTheRun() throws Exception {
    String sup = supplier("Early Ltd", 30, UK_BANK, null);
    payableInvoice(sup, "INV-E1", 2, "5.00", "0", TODAY.minusDays(40));
    String runId = proposedAndApproved();
    dbExec(
        "UPDATE purchase.suppliers SET bank_details_changed_at = r.approved_at + interval '1 hour'"
            + " FROM purchase.payment_runs r WHERE r.id = '"
            + runId
            + "' AND suppliers.id = '"
            + sup
            + "'");

    Response file = get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER);
    assertThat(file.readEntity(String.class), file.getStatus(), is(200));
    Response pay = post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER);
    assertThat(pay.readEntity(String.class), pay.getStatus(), is(200));
  }

  @Test
  @DisplayName("Bank details changed after approval stop the payment and the bank file")
  void bankDetailsChangedAfterApprovalAreRefused() throws Exception {
    String sup = supplier("Diverted Ltd", 30, UK_BANK, null);
    payableInvoice(sup, "INV-D1", 2, "5.00", "0", TODAY.minusDays(40));
    String runId = proposedAndApproved();

    Response changed =
        put(
            "/suppliers/" + sup,
            "{\"name\":\"Diverted Ltd\",\"bankAccountName\":\"Diverted Ltd\","
                + "\"bankSortCode\":\"654321\",\"bankAccountNumber\":\"12345678\"}",
            "OWNER",
            USER);
    assertThat(changed.readEntity(String.class), changed.getStatus(), is(200));

    Response pay = post("/payment-runs/" + runId + "/pay", "{}", "MANAGER", USER);
    String payBody = pay.readEntity(String.class);
    assertThat(payBody, pay.getStatus(), is(409));
    assertThat(payBody, containsString("PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED"));
    assertThat(payBody, containsString("Diverted Ltd"));
    assertCode(
        get("/payment-runs/" + runId + "/bank-file", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED");
    assertThat(journalOf("SUPPLIER_PAYMENT").size(), is(0));
    assertThat(outboxPayloads("SupplierRemittanceIssued").size(), is(0));

    // An approved run can still be cancelled, and the new one flags the change.
    assertThat(
        post("/payment-runs/" + runId + "/cancel", "{\"reason\":\"bank change\"}", "MANAGER", USER)
            .getStatus(),
        is(200));
    Response reproposed = propose(TODAY.toString(), TODAY.toString(), null, "MANAGER", USER);
    String reproposedBody = reproposed.readEntity(String.class);
    assertThat(reproposedBody, reproposed.getStatus(), is(201));
    JsonObject run2 = data(reproposedBody);
    assertThat(reproposedBody, containsString("BANK_DETAILS_CHANGED_RECENTLY"));

    // A supplier whose details are removed from a proposed run cannot be approved.
    assertThat(
        put(
                "/suppliers/" + sup,
                "{\"name\":\"Diverted Ltd\",\"clearBankDetails\":true}",
                "OWNER",
                USER)
            .getStatus(),
        is(200));
    Response stale =
        post("/payment-runs/" + run2.getString("id") + "/approve", "{}", "MANAGER", USER2);
    String staleBody = stale.readEntity(String.class);
    assertThat(staleBody, stale.getStatus(), is(409));
    assertThat(staleBody, containsString("PURCHASE_PAYMENT_RUN_STALE"));
    assertThat(staleBody, containsString("Diverted Ltd: NO_BANK_DETAILS"));
    JsonObject view =
        data(
            get("/payment-runs/" + run2.getString("id"), "MANAGER", USER).readEntity(String.class));
    assertThat(view.getJsonArray("suppliers").size(), is(0));
    assertThat(
        view.getJsonArray("excluded").getJsonObject(0).getString("reason"), is("NO_BANK_DETAILS"));
  }

  // ── refusals ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Payment runs refuse the wrong roles, other tenants and bad input")
  void refusals() {
    String sup = supplier("Guarded Ltd", 30, UK_BANK, null);
    payableInvoice(sup, "INV-G1", 2, "5.00", "0", TODAY.minusDays(40));

    assertThat(
        propose(TODAY.toString(), TODAY.toString(), null, "STOREKEEPER", USER).getStatus(),
        is(403));
    assertThat(
        propose(TODAY.toString(), TODAY.toString(), null, "CASHIER", USER).getStatus(), is(403));
    assertThat(get("/payment-runs", "CASHIER", USER).getStatus(), is(403));
    assertThat(get("/payment-runs", "STOREKEEPER", USER).getStatus(), is(403));

    assertCode(
        propose(TODAY.toString(), TODAY.minusDays(1).toString(), null, "MANAGER", USER),
        400,
        "PURCHASE_PAYMENT_DATE_INVALID");
    assertCode(
        propose(TODAY.plusYears(2).toString(), TODAY.toString(), null, "MANAGER", USER),
        400,
        "PURCHASE_PAYMENT_DATE_INVALID");
    assertThat(propose("not-a-date", TODAY.toString(), null, "MANAGER", USER).getStatus(), is(400));
    assertThat(propose(TODAY.toString(), null, null, "MANAGER", USER).getStatus(), is(400));
    assertThat(
        propose(TODAY.toString(), TODAY.toString(), "ZZ", "MANAGER", USER).getStatus(), is(400));
    assertThat(
        propose(TODAY.toString(), TODAY.toString(), "';-", "MANAGER", USER).getStatus(), is(400));
    // A run pays one currency: nothing here is invoiced in euros.
    assertCode(
        propose(TODAY.toString(), TODAY.toString(), "EUR", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_NOTHING_DUE");

    Response proposed = propose(TODAY.toString(), TODAY.toString(), null, "MANAGER", USER);
    String runId = data(proposed.readEntity(String.class)).getString("id");
    // Another tenant cannot see it, approve it, pay it or file it.
    assertThat(getAs("/payment-runs/" + runId, T2, "OWNER", USER2).getStatus(), is(404));
    assertThat(
        postAs("/payment-runs/" + runId + "/approve", "{}", T2, "OWNER", USER2).getStatus(),
        is(404));
    assertThat(
        postAs("/payment-runs/" + runId + "/pay", "{}", T2, "OWNER", USER2).getStatus(), is(404));
    assertThat(
        postAs("/payment-runs/" + runId + "/cancel", "{\"reason\":\"x\"}", T2, "OWNER", USER2)
            .getStatus(),
        is(404));
    assertThat(
        getAs("/payment-runs/" + runId + "/bank-file", T2, "OWNER", USER2).getStatus(), is(404));
    assertThat(
        dataArray(getAs("/payment-runs", T2, "OWNER", USER2).readEntity(String.class)).size(),
        is(0));
    assertCode(
        get("/payment-runs/01a090ae-611e-7000-8000-000000000000", "MANAGER", USER),
        404,
        "PURCHASE_PAYMENT_RUN_NOT_FOUND");
    int malformed = get("/payment-runs/not-a-uuid", "MANAGER", USER).getStatus();
    assertThat(String.valueOf(malformed), malformed == 400 || malformed == 404, is(true));

    // The owner may approve a run they proposed: a one-person business still pays its suppliers.
    assertThat(
        post("/payment-runs/" + runId + "/approve", "{}", "OWNER", USER).getStatus(), is(200));
  }

  // ── supplier bank details ───────────────────────────────────────────────────

  @Test
  @DisplayName("Bank details are validated, gated, masked, and only a real change moves the stamp")
  void supplierBankDetails() {
    assertCode(
        post(
            "/suppliers",
            "{\"name\":\"Bad\",\"bankAccountName\":\"Bad\",\"bankSortCode\":\"12-AB-56\",\"bankAccountNumber\":\"31415926\"}",
            "OWNER",
            USER),
        400,
        "PURCHASE_BANK_DETAILS_INVALID");
    assertCode(
        post(
            "/suppliers",
            "{\"name\":\"Bad\",\"bankAccountName\":\"Bad\",\"bankIban\":\"GB82WEST12345698765433\"}",
            "OWNER",
            USER),
        400,
        "PURCHASE_BANK_DETAILS_INVALID");
    assertCode(
        post(
            "/suppliers",
            "{\"name\":\"Bad\",\"bankAccountName\":\"Bad\",\"bankSortCode\":\"123456\"}",
            "OWNER",
            USER),
        400,
        "PURCHASE_BANK_DETAILS_INVALID");
    assertCode(
        post(
            "/suppliers",
            "{\"name\":\"Bad\",\"bankAccountName\":\"Bad\",\"bankBic\":\"DEUTDEFF\"}",
            "OWNER",
            USER),
        400,
        "PURCHASE_BANK_DETAILS_INVALID");
    assertThat(
        post("/suppliers", "{\"name\":\"Bad\",\"remittanceEmail\":\"not-an-email\"}", "OWNER", USER)
            .getStatus(),
        is(400));

    // A storekeeper may add a supplier, not the account its money goes to.
    assertThat(
        post("/suppliers", "{\"name\":\"Store Added\"}", "STOREKEEPER", USER).getStatus(), is(201));
    assertCode(
        post("/suppliers", supplierJson("Store Banked", 30, UK_BANK, null), "STOREKEEPER", USER),
        403,
        "PERMISSION_DENIED");

    Response created =
        post(
            "/suppliers",
            supplierJson("Masked Ltd", 30, UK_BANK, "ap@masked.example"),
            "MANAGER",
            USER);
    String createdBody = created.readEntity(String.class);
    assertThat(createdBody, created.getStatus(), is(201));
    assertThat(createdBody, not(containsString("31415926")));
    String id = data(createdBody).getString("id");
    JsonObject stored = data(get("/suppliers/" + id, "MANAGER", USER).readEntity(String.class));
    assertThat(stored.getString("bankAccountNumberMasked"), is("****5926"));
    assertThat(stored.getString("bankSortCode"), is("123456"));
    assertThat(stored.getBoolean("hasBankDetails"), is(true));
    String stamp = stored.getString("bankDetailsChangedAt");

    // Editing something else, or keying the same details again, leaves the stamp where it was.
    Response renamed =
        put(
            "/suppliers/" + id,
            "{\"name\":\"Masked Ltd.\",\"paymentTermsDays\":45}",
            "MANAGER",
            USER);
    String renamedBody = renamed.readEntity(String.class);
    assertThat(renamedBody, renamed.getStatus(), is(200));
    assertThat(data(renamedBody).getString("bankDetailsChangedAt"), is(stamp));
    assertThat(data(renamedBody).getString("remittanceEmail"), is("ap@masked.example"));
    Response same =
        put("/suppliers/" + id, "{\"name\":\"Masked Ltd.\"," + UK_BANK + "}", "MANAGER", USER2);
    assertThat(data(same.readEntity(String.class)).getString("bankDetailsChangedAt"), is(stamp));

    Response moved =
        put(
            "/suppliers/" + id,
            "{\"name\":\"Masked Ltd.\",\"bankAccountName\":\"Masked Ltd\",\"bankSortCode\":\"654321\",\"bankAccountNumber\":\"12345678\"}",
            "MANAGER",
            USER);
    JsonObject movedData = data(moved.readEntity(String.class));
    assertThat(movedData.getString("bankDetailsChangedAt"), not(stamp));
    assertThat(movedData.getString("bankAccountNumberMasked"), is("****5678"));

    // An email that is not one is refused; empty clears it; clearing bank details is explicit.
    assertCode(
        put(
            "/suppliers/" + id,
            "{\"name\":\"Masked Ltd.\",\"remittanceEmail\":\"nope\"}",
            "MANAGER",
            USER),
        400,
        "PURCHASE_REMITTANCE_EMAIL_INVALID");
    Response cleared =
        put(
            "/suppliers/" + id,
            "{\"name\":\"Masked Ltd.\",\"remittanceEmail\":\"\",\"clearBankDetails\":true}",
            "MANAGER",
            USER);
    JsonObject clearedData = data(cleared.readEntity(String.class));
    assertThat(clearedData.getBoolean("hasBankDetails"), is(false));
    assertThat(
        clearedData.containsKey("remittanceEmail") && !clearedData.isNull("remittanceEmail"),
        is(false));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private String proposedAndApproved() {
    Response proposed = propose(TODAY.toString(), TODAY.toString(), null, "MANAGER", USER);
    String body = proposed.readEntity(String.class);
    assertThat(body, proposed.getStatus(), is(201));
    String runId = data(body).getString("id");
    Response approved = post("/payment-runs/" + runId + "/approve", "{}", "MANAGER", USER2);
    assertThat(approved.readEntity(String.class), approved.getStatus(), is(200));
    return runId;
  }

  private String supplier(String name, int terms, String bank, String email) {
    Response r = post("/suppliers", supplierJson(name, terms, bank, email), "OWNER", USER);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return data(body).getString("id");
  }

  private static String supplierJson(String name, int terms, String bank, String email) {
    return "{\"name\":\""
        + name
        + "\",\"currency\":\"GBP\",\"paymentTermsDays\":"
        + terms
        + (email == null ? "" : ",\"remittanceEmail\":\"" + email + "\"")
        + (bank == null ? "" : "," + bank)
        + "}";
  }

  /** An order for the supplier, received in full and invoiced as ordered: a MATCHED invoice. */
  private Inv payableInvoice(
      String supplierId, String number, int qty, String unitPrice, String vat, LocalDate date) {
    Response po = post("/purchase-orders", PurchaseFixtures.orderJson(supplierId), "OWNER", USER);
    String poBody = po.readEntity(String.class);
    assertThat(poBody, po.getStatus(), is(201));
    String poId = data(poBody).getString("id");
    String line = PurchaseFixtures.lineJson(qty, unitPrice);
    assertThat(
        post("/purchase-orders/" + poId + "/lines", line, "OWNER", USER).getStatus(), is(201));
    assertThat(
        post("/purchase-orders/" + poId + "/submit", "{}", "OWNER", USER).getStatus(), is(200));
    String receipt = PurchaseFixtures.receiptJson(poId, qty);
    assertThat(post("/goods-receipts", receipt, "OWNER", USER).getStatus(), is(201));
    String invoice = PurchaseFixtures.invoiceJson(poId, number, date, qty, unitPrice, vat);
    Response inv = post("/supplier-invoices", invoice, "OWNER", USER);
    String invBody = inv.readEntity(String.class);
    assertThat(invBody, inv.getStatus(), is(201));
    assertThat(data(invBody).getString("status"), is("MATCHED"));
    return new Inv(poId, data(invBody).getString("id"));
  }

  /** Goods sent back against an order, and the supplier's credit note recorded for them. */
  private void creditedReturn(String poId, int qty, String creditNote) {
    Response raised =
        post(
            "/vendor-returns",
            "{\"poId\":\""
                + poId
                + "\",\"reason\":\"DAMAGED\",\"lines\":[{\"variantId\":\""
                + PurchaseFixtures.VARIANT
                + "\",\"qty\":"
                + qty
                + "}]}",
            "OWNER",
            USER);
    String body = raised.readEntity(String.class);
    assertThat(body, raised.getStatus(), is(201));
    Response credited =
        post(
            "/vendor-returns/" + data(body).getString("id") + "/credit",
            "{\"creditNoteNumber\":\"" + creditNote + "\",\"creditNoteDate\":\"" + TODAY + "\"}",
            "OWNER",
            USER);
    assertThat(credited.readEntity(String.class), credited.getStatus(), is(200));
  }

  private Response propose(
      String payUpTo, String paymentDate, String currency, String role, String user) {
    StringBuilder json = new StringBuilder("{");
    if (payUpTo != null) json.append("\"payUpTo\":\"").append(payUpTo).append('"');
    if (paymentDate != null) {
      if (json.length() > 1) json.append(',');
      json.append("\"paymentDate\":\"").append(paymentDate).append('"');
    }
    if (currency != null) json.append(",\"currency\":\"").append(currency).append('"');
    return post("/payment-runs", json.append('}').toString(), role, user);
  }

  private Response post(String path, String json, String role, String user) {
    return postAs(path, json, T, role, user);
  }

  private Response postAs(String path, String json, String tenant, String role, String user) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", user)
        .header("X-Roles", role)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, String role, String user) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", user)
        .header("X-Roles", role)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery, String role, String user) {
    return getAs(pathAndQuery, T, role, user);
  }

  private Response getAs(String pathAndQuery, String tenant, String role, String user) {
    return com.storeql.test.WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", user)
        .header("X-Roles", role)
        .get();
  }

  private static void assertCode(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString(code));
  }

  private static void assertMoney(JsonObject o, String field, String expected) {
    BigDecimal actual = o.getJsonNumber(field).bigDecimalValue();
    assertThat(field + "=" + actual, actual.compareTo(new BigDecimal(expected)), is(0));
  }

  private JsonArray journalOf(String sourceType) {
    Response r = get("/nominal-ledger?limit=100", "OWNER", USER);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    var out = Json.createArrayBuilder();
    for (JsonValue v : dataArray(body)) {
      JsonObject o = v.asJsonObject();
      if (sourceType.equals(o.getString("sourceType", null))) out.add(o);
    }
    return out.build();
  }

  private static BigDecimal sum(JsonArray lines, String field) {
    BigDecimal total = BigDecimal.ZERO;
    for (JsonValue v : lines) {
      total = total.add(v.asJsonObject().getJsonNumber(field).bigDecimalValue());
    }
    return total;
  }

  private static JsonObject data(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static JsonArray dataArray(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  private static List<JsonObject> outboxPayloads(String eventType) throws Exception {
    List<JsonObject> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM purchase.outbox WHERE event_type = ? ORDER BY created_at")) {
      ps.setString(1, eventType);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          try (var reader = Json.createReader(new StringReader(rs.getString(1)))) {
            out.add(reader.readObject());
          }
        }
      }
    }
    return out;
  }

  private static void dbExec(String sql) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement()) {
      st.executeUpdate(sql);
    }
  }

  private static long dbCount(String sql) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
