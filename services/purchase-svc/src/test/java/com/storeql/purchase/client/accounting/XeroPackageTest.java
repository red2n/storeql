package com.storeql.purchase.client.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.test.Envelopes;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Xero's Accounting API (17.9): a journal is a manual journal — {@code PUT
 * /api.xro/2.0/ManualJournals} under the organisation's {@code xero-tenant-id}, one line per ledger
 * line with a debit positive and a credit negative, posted, made idempotent by the journal's id —
 * and the chart is {@code GET /api.xro/2.0/Accounts}.
 */
class XeroPackageTest {

  private static PackageStub stub;
  private static XeroPackage xero;

  @BeforeAll
  static void start() {
    stub = PackageStub.start(r -> new PackageStub.Answer(200, "{}"));
    xero = XeroPackage.forTest(stub.url());
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  @Test
  @DisplayName("A journal is one manual journal, POSTED, its lines signed the way Xero reads them")
  void pushesAManualJournal() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                200,
                "{\"Id\":\"x\",\"Status\":\"OK\",\"ManualJournals\":[{\"ManualJournalID\":\"7d0f1a2b-0000-4000-8000-000000000001\",\"Status\":\"POSTED\"}]}"));
    AccountingPackage.Pushed pushed =
        xero.push(
            Journals.connection("XERO", Map.of("tenantId", "org-77")),
            Journals.bearer("tok-1"),
            Journals.rent(),
            Journals.mapping());
    assertEquals("7d0f1a2b-0000-4000-8000-000000000001", pushed.externalId());

    PackageStub.Request sent = stub.last();
    assertEquals("PUT", sent.method());
    assertEquals("/api.xro/2.0/ManualJournals", sent.path());
    assertEquals("Bearer tok-1", sent.header("Authorization"));
    assertEquals("org-77", sent.header("xero-tenant-id"));
    assertEquals(Journals.JOURNAL.toString(), sent.header("Idempotency-Key"));
    assertTrue(sent.header("Accept").contains("application/json"));
    JsonObject body = Envelopes.parse(sent.body());
    JsonArray journals = body.getJsonArray("ManualJournals");
    assertEquals(1, journals.size());
    JsonObject mj = journals.getJsonObject(0);
    assertEquals("Goods received: PO-42", mj.getString("Narration"));
    assertEquals("2026-09-23", mj.getString("Date"));
    assertEquals("POSTED", mj.getString("Status"));
    JsonArray lines = mj.getJsonArray("JournalLines");
    assertEquals(3, lines.size());
    assertEquals("STOCK", lines.getJsonObject(0).getString("AccountCode"), "the mapped code");
    assertEquals(
        "120.00",
        lines.getJsonObject(0).getJsonNumber("LineAmount").bigDecimalValue().toPlainString());
    assertEquals(
        "2109", lines.getJsonObject(1).getString("AccountCode"), "unmapped passes through");
    assertEquals(
        "-100.00",
        lines.getJsonObject(1).getJsonNumber("LineAmount").bigDecimalValue().toPlainString());
    assertEquals(
        "-20.00",
        lines.getJsonObject(2).getJsonNumber("LineAmount").bigDecimalValue().toPlainString());
    assertEquals(
        "NONE",
        lines.getJsonObject(2).getString("TaxType"),
        "amounts are posted as they are; the ledger already split the VAT");
    assertEquals("Stock", lines.getJsonObject(0).getString("Description"));
  }

  @Test
  @DisplayName("A refusal carries Xero's own words; an unreadable answer is unreachable")
  void refusalsAndOutages() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                400,
                "{\"ErrorNumber\":10,\"Type\":\"ValidationException\",\"Message\":\"A validation exception occurred\","
                    + "\"Elements\":[{\"ValidationErrors\":[{\"Message\":\"Account code '2109' is not a valid code for this document.\"}]}]}"));
    AccountingPackage.Refused refused =
        assertThrows(
            AccountingPackage.Refused.class,
            () ->
                xero.push(
                    Journals.connection("XERO", Map.of("tenantId", "org-77")),
                    Journals.bearer("t"),
                    Journals.rent(),
                    Journals.mapping()));
    assertEquals(400, refused.status());
    assertTrue(refused.getMessage().contains("Account code '2109'"), refused.getMessage());
    assertTrue(refused.retryable(), "a validation refusal is retried once the mapping is fixed");

    stub.answerWith(
        r ->
            new PackageStub.Answer(
                401, "{\"Title\":\"Unauthorized\",\"Detail\":\"TokenExpired\"}"));
    AccountingPackage.Refused expired =
        assertThrows(
            AccountingPackage.Refused.class,
            () ->
                xero.push(
                    Journals.connection("XERO", Map.of("tenantId", "org-77")),
                    Journals.bearer("t"),
                    Journals.rent(),
                    Journals.mapping()));
    assertEquals(401, expired.status());
    assertTrue(expired.getMessage().contains("TokenExpired"));

    stub.answerWith(r -> new PackageStub.Answer(200, "not json at all"));
    assertThrows(
        AccountingPackage.Unreachable.class,
        () ->
            xero.push(
                Journals.connection("XERO", Map.of("tenantId", "org-77")),
                Journals.bearer("t"),
                Journals.rent(),
                Journals.mapping()));
    // Xero's write is idempotent on the key, so a try that got no answer is safe to try again.
    assertTrue(xero.idempotentWrites());
  }

  @Test
  @DisplayName("The chart of accounts is read with its codes, names and types")
  void readsTheChart() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                200,
                "{\"Accounts\":[{\"AccountID\":\"a1\",\"Code\":\"200\",\"Name\":\"Sales\",\"Type\":\"REVENUE\",\"Status\":\"ACTIVE\"},"
                    + "{\"AccountID\":\"a2\",\"Code\":\"090\",\"Name\":\"Business Bank Account\",\"Type\":\"BANK\",\"Status\":\"ACTIVE\"},"
                    + "{\"AccountID\":\"a3\",\"Code\":\"999\",\"Name\":\"Old\",\"Type\":\"EXPENSE\",\"Status\":\"ARCHIVED\"}]}"));
    List<AccountingPackage.ExternalAccount> chart =
        xero.accounts(
            Journals.connection("XERO", Map.of("tenantId", "org-77")), Journals.bearer("tok-2"));
    assertEquals("GET", stub.last().method());
    assertEquals("/api.xro/2.0/Accounts", stub.last().path());
    assertEquals("org-77", stub.last().header("xero-tenant-id"));
    assertEquals(2, chart.size(), "archived accounts are not offered");
    assertEquals("200", chart.get(0).code());
    assertEquals("200", chart.get(0).id(), "Xero journals name an account by its code");
    assertEquals("Sales", chart.get(0).name());
    assertEquals("REVENUE", chart.get(0).type());
    assertNull(stub.last().header("Idempotency-Key"));
  }
}
