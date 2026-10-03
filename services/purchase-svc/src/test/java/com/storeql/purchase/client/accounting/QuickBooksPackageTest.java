package com.storeql.purchase.client.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.purchase.domain.Accounting;
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
 * QuickBooks Online's Accounting API (17.9): a journal is a JournalEntry — {@code POST
 * /v3/company/{realmId}/journalentry}, a line per ledger line with a PostingType and the account's
 * id, made idempotent by {@code requestid} — and the chart is a query over Account.
 */
class QuickBooksPackageTest {

  private static PackageStub stub;
  private static QuickBooksPackage qbo;

  @BeforeAll
  static void start() {
    stub = PackageStub.start(r -> new PackageStub.Answer(200, "{}"));
    qbo = QuickBooksPackage.forTest(stub.url(), stub.url());
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  @Test
  @DisplayName(
      "A journal is one JournalEntry, each line posted as a debit or a credit to the account's id")
  void pushesAJournalEntry() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                200,
                "{\"JournalEntry\":{\"Id\":\"145\",\"SyncToken\":\"0\",\"TxnDate\":\"2026-09-23\"},\"time\":\"2026-09-23T09:00:00Z\"}"));
    AccountingPackage.Pushed pushed =
        qbo.push(
            Journals.connection(
                "QUICKBOOKS", Map.of("realmId", "9130", "environment", "PRODUCTION")),
            Journals.bearer("tok-q"),
            Journals.rent(),
            Journals.mapping());
    assertEquals("145", pushed.externalId());

    PackageStub.Request sent = stub.last();
    assertEquals("POST", sent.method());
    assertTrue(sent.path().startsWith("/v3/company/9130/journalentry?"), sent.path());
    assertTrue(sent.path().contains("minorversion="), sent.path());
    assertTrue(sent.path().contains("requestid=" + Journals.JOURNAL), "idempotent on the journal");
    assertEquals("Bearer tok-q", sent.header("Authorization"));
    JsonObject body = Envelopes.parse(sent.body());
    assertEquals("2026-09-23", body.getString("TxnDate"));
    assertEquals("Goods received: PO-42", body.getString("PrivateNote"));
    JsonArray lines = body.getJsonArray("Line");
    assertEquals(3, lines.size());
    JsonObject dr = lines.getJsonObject(0);
    assertEquals("JournalEntryLineDetail", dr.getString("DetailType"));
    assertEquals("120.00", dr.getJsonNumber("Amount").bigDecimalValue().toPlainString());
    assertEquals("Debit", dr.getJsonObject("JournalEntryLineDetail").getString("PostingType"));
    assertEquals(
        "STOCK",
        dr.getJsonObject("JournalEntryLineDetail").getJsonObject("AccountRef").getString("value"));
    JsonObject cr = lines.getJsonObject(1);
    assertEquals(
        "100.00",
        cr.getJsonNumber("Amount").bigDecimalValue().toPlainString(),
        "amounts are unsigned; the side says which");
    assertEquals("Credit", cr.getJsonObject("JournalEntryLineDetail").getString("PostingType"));
    assertEquals(
        "2109",
        cr.getJsonObject("JournalEntryLineDetail").getJsonObject("AccountRef").getString("value"));
    assertEquals("Goods Received Not Invoiced", cr.getString("Description"));
  }

  @Test
  @DisplayName(
      "The sandbox company lives at Intuit's sandbox host; a refusal carries the Fault's detail")
  void sandboxAndRefusals() {
    assertEquals(
        "https://sandbox-quickbooks.api.intuit.com", QuickBooksPackage.baseUrlFor("SANDBOX"));
    assertEquals("https://quickbooks.api.intuit.com", QuickBooksPackage.baseUrlFor("PRODUCTION"));
    assertEquals("https://quickbooks.api.intuit.com", QuickBooksPackage.baseUrlFor(null));

    stub.answerWith(
        r ->
            new PackageStub.Answer(
                400,
                "{\"Fault\":{\"Error\":[{\"Message\":\"Invalid Reference Id\",\"Detail\":\"Invalid Reference Id : Accounts element id 2109 not found\",\"code\":\"2500\"}],\"type\":\"ValidationFault\"},\"time\":\"2026-09-23T09:00:00Z\"}"));
    AccountingPackage.Refused refused =
        assertThrows(
            AccountingPackage.Refused.class,
            () ->
                qbo.push(
                    Journals.connection("QUICKBOOKS", Map.of("realmId", "9130")),
                    Journals.bearer("t"),
                    Journals.rent(),
                    Journals.mapping()));
    assertEquals(400, refused.status());
    assertTrue(
        refused.getMessage().contains("Accounts element id 2109 not found"), refused.getMessage());
    assertTrue(qbo.idempotentWrites());
  }

  @Test
  @DisplayName(
      "A realm id no address can be made from is refused before anything is sent, for a person to"
          + " put right: never retried, never a raw failure")
  void aRealmIdNoAddressCanBeMadeFromIsRefusedUnsent() {
    stub.answerWith(r -> new PackageStub.Answer(200, "{\"JournalEntry\":{\"Id\":\"1\"}}"));
    int before = stub.all().size();
    for (String realm : new String[] {"91 30", "9130%", "9130%zz"}) {
      Accounting.Connection connection =
          Journals.connection("QUICKBOOKS", Map.of("realmId", realm));
      AccountingPackage.Refused pushed =
          assertThrows(
              AccountingPackage.Refused.class,
              () -> qbo.push(connection, Journals.bearer("t"), Journals.rent(), Journals.mapping()),
              realm);
      assertFalse(pushed.sent(), realm);
      assertFalse(pushed.retryable(), realm);
      assertEquals(AccountingPackage.Refused.UNSENT, pushed.status(), realm);
      AccountingPackage.Refused chart =
          assertThrows(
              AccountingPackage.Refused.class,
              () -> qbo.accounts(connection, Journals.bearer("t")),
              realm);
      assertFalse(chart.sent(), realm);
      assertFalse(chart.retryable(), realm);
    }
    assertEquals(before, stub.all().size(), "QuickBooks was never asked");
  }

  @Test
  @DisplayName(
      "An environment the driver does not know is refused before anything is sent — never read as"
          + " production — and the two it knows are read in any case")
  void anEnvironmentTheDriverDoesNotKnowIsRefusedUnsent() {
    stub.answerWith(r -> new PackageStub.Answer(200, "{\"JournalEntry\":{\"Id\":\"1\"}}"));
    int before = stub.all().size();
    for (String environment : new String[] {"PROD", "Sandbx", "live", "production2"}) {
      Accounting.Connection connection =
          Journals.connection("QUICKBOOKS", Map.of("realmId", "9130", "environment", environment));
      AccountingPackage.Refused pushed =
          assertThrows(
              AccountingPackage.Refused.class,
              () -> qbo.push(connection, Journals.bearer("t"), Journals.rent(), Journals.mapping()),
              environment);
      assertFalse(pushed.sent(), environment);
      assertFalse(pushed.retryable(), environment);
      assertTrue(pushed.getMessage().contains("environment"), pushed.getMessage());
      AccountingPackage.Refused chart =
          assertThrows(
              AccountingPackage.Refused.class,
              () -> qbo.accounts(connection, Journals.bearer("t")),
              environment);
      assertFalse(chart.sent(), environment);
    }
    assertEquals(before, stub.all().size(), "QuickBooks was never asked");

    assertEquals(
        "https://sandbox-quickbooks.api.intuit.com", QuickBooksPackage.baseUrlFor("sandbox"));
    assertEquals("https://quickbooks.api.intuit.com", QuickBooksPackage.baseUrlFor(" Production "));
    assertEquals(
        "1",
        qbo.push(
                Journals.connection(
                    "QUICKBOOKS", Map.of("realmId", "9130", "environment", "sandbox")),
                Journals.bearer("t"),
                Journals.rent(),
                Journals.mapping())
            .externalId(),
        "a known environment, in lower case, is sent");
    assertEquals(before + 1, stub.all().size());
  }

  @Test
  @DisplayName("The chart of accounts is read by query, with ids, names and types")
  void readsTheChart() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                200,
                "{\"QueryResponse\":{\"Account\":[{\"Id\":\"35\",\"Name\":\"Checking\",\"AccountType\":\"Bank\",\"AcctNum\":\"1200\",\"Active\":true},"
                    + "{\"Id\":\"79\",\"Name\":\"Sales of Product Income\",\"AccountType\":\"Income\",\"Active\":true},"
                    + "{\"Id\":\"80\",\"Name\":\"Closed\",\"AccountType\":\"Expense\",\"Active\":false}],\"maxResults\":3},\"time\":\"2026-09-23T09:00:00Z\"}"));
    List<AccountingPackage.ExternalAccount> chart =
        qbo.accounts(
            Journals.connection("QUICKBOOKS", Map.of("realmId", "9130")), Journals.bearer("tok-q"));
    assertEquals("GET", stub.last().method());
    assertTrue(stub.last().path().startsWith("/v3/company/9130/query?"), stub.last().path());
    assertTrue(
        stub.last().path().toLowerCase().contains("from%20account")
            || stub.last().path().toLowerCase().contains("from+account"),
        stub.last().path());
    assertEquals(2, chart.size(), "inactive accounts are not offered");
    assertEquals("35", chart.get(0).id(), "QuickBooks names an account by its id");
    assertEquals("1200", chart.get(0).code());
    assertEquals("Checking", chart.get(0).name());
    assertEquals("Bank", chart.get(0).type());
    assertEquals("", chart.get(1).code(), "an account with no number has none");
  }
}
