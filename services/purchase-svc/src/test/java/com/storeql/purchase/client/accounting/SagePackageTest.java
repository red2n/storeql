package com.storeql.purchase.client.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Sage Business Cloud Accounting's API v3.1 (17.9): a journal is {@code POST /journals} for the
 * business named by {@code X-Business}, a line per ledger line with the ledger account's id and a
 * debit or a credit — and the chart is {@code GET /ledger_accounts}. Sage has no idempotency key,
 * so a try that got no answer is not repeated by the clock.
 */
class SagePackageTest {

  private static PackageStub stub;
  private static SagePackage sage;

  @BeforeAll
  static void start() {
    stub = PackageStub.start(r -> new PackageStub.Answer(200, "{}"));
    sage = SagePackage.forTest(stub.url());
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  @Test
  @DisplayName("A journal is one Sage journal with a line per ledger line, debit or credit")
  void pushesAJournal() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                201,
                "{\"id\":\"9a8b7c6d\",\"displayed_as\":\"Goods received: PO-42\",\"date\":\"2026-09-23\"}"));
    AccountingPackage.Pushed pushed =
        sage.push(
            Journals.connection("SAGE", Map.of("businessId", "biz-1")),
            Journals.bearer("tok-s"),
            Journals.rent(),
            Journals.mapping());
    assertEquals("9a8b7c6d", pushed.externalId());

    PackageStub.Request sent = stub.last();
    assertEquals("POST", sent.method());
    assertEquals("/v3.1/journals", sent.path());
    assertEquals("Bearer tok-s", sent.header("Authorization"));
    assertEquals("biz-1", sent.header("X-Business"));
    JsonObject journal = Envelopes.parse(sent.body()).getJsonObject("journal");
    assertEquals("2026-09-23", journal.getString("date"));
    assertEquals("Goods received: PO-42", journal.getString("description"));
    assertEquals(Journals.JOURNAL.toString(), journal.getString("reference"));
    JsonArray lines = journal.getJsonArray("journal_lines");
    assertEquals(3, lines.size());
    assertEquals("STOCK", lines.getJsonObject(0).getString("ledger_account_id"));
    assertEquals(
        "120.00", lines.getJsonObject(0).getJsonNumber("debit").bigDecimalValue().toPlainString());
    assertEquals(
        "0", lines.getJsonObject(0).getJsonNumber("credit").bigDecimalValue().toPlainString());
    assertEquals(
        "100.00", lines.getJsonObject(1).getJsonNumber("credit").bigDecimalValue().toPlainString());
    assertEquals("Goods Received Not Invoiced", lines.getJsonObject(1).getString("details"));
    assertFalse(sage.idempotentWrites(), "Sage offers no idempotency key");
  }

  @Test
  @DisplayName("A refusal carries Sage's errors; a company that no longer allows the token is 401")
  void refusals() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                422,
                "{\"$severity\":\"error\",\"$dataCode\":\"RecordInvalid\",\"$message\":\"Journal lines ledger account can't be blank\",\"$source\":\"journal_lines[1].ledger_account_id\"}"));
    AccountingPackage.Refused refused =
        assertThrows(
            AccountingPackage.Refused.class,
            () ->
                sage.push(
                    Journals.connection("SAGE", Map.of("businessId", "biz-1")),
                    Journals.bearer("t"),
                    Journals.rent(),
                    Journals.mapping()));
    assertEquals(422, refused.status());
    assertTrue(
        refused.getMessage().contains("ledger account can't be blank"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("journal_lines[1].ledger_account_id"), refused.getMessage());
  }

  @Test
  @DisplayName("The chart of accounts is read a page at a time, with nominal codes and types")
  void readsTheChart() {
    stub.answerWith(
        r ->
            r.path().endsWith("&page=2")
                ? new PackageStub.Answer(
                    200,
                    "{\"$total\":3,\"$page\":2,\"$next\":null,\"$items\":[{\"id\":\"la3\",\"displayed_as\":\"Sales (4000)\",\"name\":\"Sales\",\"nominal_code\":4000,\"ledger_account_type\":{\"id\":\"SALES\"}}]}")
                : new PackageStub.Answer(
                    200,
                    "{\"$total\":3,\"$page\":1,\"$next\":\"/ledger_accounts?page=2\",\"$items\":[{\"id\":\"la1\",\"displayed_as\":\"Bank (1200)\",\"name\":\"Bank\",\"nominal_code\":1200,\"ledger_account_type\":{\"id\":\"BANK\"}},"
                        + "{\"id\":\"la2\",\"displayed_as\":\"Creditors (2100)\",\"name\":\"Creditors\",\"nominal_code\":2100,\"ledger_account_type\":{\"id\":\"CURRENT_LIABILITIES\"}}]}"));
    List<AccountingPackage.ExternalAccount> chart =
        sage.accounts(
            Journals.connection("SAGE", Map.of("businessId", "biz-1")), Journals.bearer("tok-s"));
    assertEquals(3, chart.size());
    assertEquals("la1", chart.get(0).id(), "Sage names an account by its id");
    assertEquals("1200", chart.get(0).code());
    assertEquals("Bank", chart.get(0).name());
    assertEquals("BANK", chart.get(0).type());
    assertEquals("la3", chart.get(2).id());
    assertEquals(
        2,
        stub.all().stream().filter(q -> q.path().startsWith("/v3.1/ledger_accounts")).count(),
        "both pages read");
    assertEquals("biz-1", stub.last().header("X-Business"));
  }
}
