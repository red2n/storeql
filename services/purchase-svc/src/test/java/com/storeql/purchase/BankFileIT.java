package com.storeql.purchase;

import static com.storeql.purchase.PaymentRunSteps.TODAY;
import static com.storeql.purchase.PaymentRunSteps.USER2;
import static com.storeql.purchase.PaymentRunSteps.assertCode;
import static com.storeql.purchase.PaymentRunSteps.data;
import static com.storeql.purchase.PurchaseFixtures.T2;
import static com.storeql.purchase.PurchaseFixtures.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.purchase.domain.Bacs18;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Bank-standard payment files and the bank's answer (17.12): the paying accounts, a pain.001 for a
 * euro run and a Bacs Standard 18 for a sterling one, the pain.002 read back — a close match held
 * until a manager releases it, a payee the bank could not match not payable — and the refusals and
 * abuse around each.
 */
@HelidonTest
class BankFileIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String GBP_ACCOUNT =
      "{\"accountName\":\"Corner Shop Ltd\",\"sortCode\":\"40-28-11\",\"accountNumber\":\"12345678\","
          + "\"serviceUserNumber\":\"123456\"}";
  private static final String EUR_ACCOUNT =
      "{\"accountName\":\"Corner Shop BV\",\"iban\":\"NL91 ABNA 0417 1643 00\",\"bic\":\"ABNANL2A\"}";
  private static final String UK_BANK =
      "\"bankAccountName\":\"Acme Ltd\",\"bankSortCode\":\"12-34-56\",\"bankAccountNumber\":\"31415926\"";
  private static final String DE_BANK =
      "\"bankAccountName\":\"Muster GmbH\",\"bankIban\":\"DE89 3704 0044 0532 0130 00\",\"bankBic\":\"DEUTDEFF\"";
  private static final String FR_BANK =
      "\"bankAccountName\":\"Dupont SA\",\"bankIban\":\"FR14 2004 1010 0505 0001 3M02 606\"";

  @Inject WebTarget target;
  private PaymentRunSteps api;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    PurchaseFixtures.truncateAll(PG);
    api = new PaymentRunSteps(target);
  }

  // ── paying accounts ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Paying accounts are validated, finance's to set, masked, and the latest is in force")
  void payingAccounts() {
    assertThat(
        api.put("/payment-runs/paying-accounts/GBP", GBP_ACCOUNT, "STOREKEEPER", USER).getStatus(),
        is(403));
    assertThat(
        api.put("/payment-runs/paying-accounts/GBP", GBP_ACCOUNT, "CASHIER", USER).getStatus(),
        is(403));
    assertThat(api.get("/payment-runs/paying-accounts", "CASHIER", USER).getStatus(), is(403));
    for (String bad :
        List.of(
            GBP_ACCOUNT.replace("40-28-11", "40-28-1X"),
            GBP_ACCOUNT.replace("\"123456\"", "\"12345\""),
            "{\"accountName\":\"Corner Shop\",\"iban\":\"NL91ABNA0417164300\",\"serviceUserNumber\":\"123456\"}",
            "{\"accountName\":\"Corner Shop\",\"iban\":\"NL91ABNA0417164301\"}",
            "{\"accountName\":\"Corner Shop\"}")) {
      assertCode(
          api.put("/payment-runs/paying-accounts/GBP", bad, "OWNER", USER),
          400,
          "PURCHASE_PAYING_ACCOUNT_INVALID");
    }
    assertCode(
        api.put(
            "/payment-runs/paying-accounts/EUR",
            GBP_ACCOUNT.replace(",\"serviceUserNumber\":\"123456\"", ""),
            "OWNER",
            USER),
        400,
        "a euro paying account needs its IBAN");
    assertCode(
        api.put("/payment-runs/paying-accounts/EURO", EUR_ACCOUNT, "OWNER", USER),
        400,
        "PURCHASE_INVALID_CURRENCY");
    assertThat(
        api.put("/payment-runs/paying-accounts/GBP", "{\"sortCode\":\"402811\"}", "OWNER", USER)
            .getStatus(),
        is(400));

    Response set = api.put("/payment-runs/paying-accounts/GBP", GBP_ACCOUNT, "MANAGER", USER);
    String body = set.readEntity(String.class);
    assertThat(body, set.getStatus(), is(200));
    assertThat(body, containsString("\"accountNumberMasked\":\"****5678\""));
    assertThat(body, containsString("\"sendsBacs\":true"));
    assertThat(body, not(containsString("12345678")));
    data(api.put("/payment-runs/paying-accounts/EUR", EUR_ACCOUNT, "OWNER", USER), 200);
    data(
        api.put(
            "/payment-runs/paying-accounts/GBP",
            GBP_ACCOUNT.replace("12345678", "87654321"),
            "OWNER",
            USER),
        200);

    JsonArray accounts = dataArray(api.get("/payment-runs/paying-accounts", "MANAGER", USER));
    assertThat(accounts.size(), is(2));
    Map<String, JsonObject> byCurrency = new HashMap<>();
    for (JsonValue v : accounts)
      byCurrency.put(v.asJsonObject().getString("currency"), v.asJsonObject());
    assertThat(byCurrency.get("GBP").getString("accountNumberMasked"), is("****4321"));
    assertThat(byCurrency.get("EUR").getBoolean("sendsSepa"), is(true));
    assertThat(
        dataArray(api.as("/payment-runs/paying-accounts", T2, "OWNER", USER2).get()).size(), is(0));
  }

  // ── pain.001 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A euro run is a pain.001 from the euro account, the same file each time it is fetched")
  void aEuroRunIsAPain001() throws Exception {
    data(api.put("/payment-runs/paying-accounts/EUR", EUR_ACCOUNT, "OWNER", USER), 200);
    String muster = api.supplier("Muster GmbH", "EUR", DE_BANK);
    String dupont = api.supplier("Dupont SA", "EUR", FR_BANK);
    api.dueInvoice(muster, "INV-M1", "EUR", 4, "25.00");
    api.dueInvoice(dupont, "INV-D1", "EUR", 2, "10.50");
    JsonObject run = api.approvedRun("EUR", TODAY.plusDays(3));
    String id = run.getString("id");

    Response file = api.get("/payment-runs/" + id + "/bank-file?format=pain001", "MANAGER", USER);
    String xml = file.readEntity(String.class);
    assertThat(xml, file.getStatus(), is(200));
    assertThat(file.getHeaderString("Content-Type"), startsWith("application/xml"));
    assertThat(file.getHeaderString("Cache-Control"), is("no-store"));
    assertThat(file.getHeaderString("Content-Disposition"), containsString(".xml"));
    Document d = parse(xml);
    String ns = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";
    assertThat(
        d.getElementsByTagNameNS(ns, "MsgId").item(0).getTextContent(),
        is(run.getString("reference")));
    assertThat(d.getElementsByTagNameNS(ns, "NbOfTxs").item(0).getTextContent(), is("2"));
    assertThat(
        new BigDecimal(d.getElementsByTagNameNS(ns, "CtrlSum").item(0).getTextContent())
            .compareTo(new BigDecimal("121.00")),
        is(0));
    assertThat(
        d.getElementsByTagNameNS(ns, "Dt").item(0).getTextContent(),
        is(TODAY.plusDays(3).toString()));
    assertThat(xml, containsString("<IBAN>NL91ABNA0417164300</IBAN>"));
    assertThat(xml, containsString("<IBAN>DE89370400440532013000</IBAN>"));
    assertThat(xml, containsString("<IBAN>FR1420041010050500013M02606</IBAN>"));
    for (String e2e : endToEndIds(xml).values()) assertThat(e2e.length(), is(32));
    assertThat(
        api.get("/payment-runs/" + id + "/bank-file?format=PAIN001", "OWNER", USER)
            .readEntity(String.class),
        is(xml));

    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=BACS18", "MANAGER", USER),
        409,
        "PURCHASE_BANK_FILE_FORMAT_UNSUPPORTED");
    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=SWIFT", "MANAGER", USER),
        400,
        "PURCHASE_BANK_FILE_FORMAT_UNKNOWN");
    assertThat(
        api.as("/payment-runs/" + id + "/bank-file?format=PAIN001", T2, "OWNER", USER2)
            .get()
            .getStatus(),
        is(404));
    assertThat(
        api.get("/payment-runs/" + id + "/bank-file?format=PAIN001", "CASHIER", USER).getStatus(),
        is(403));
    // The CSV is still there for a bank that takes it.
    assertThat(
        api.get("/payment-runs/" + id + "/bank-file", "MANAGER", USER)
            .getHeaderString("Content-Type"),
        startsWith("text/csv"));
  }

  @Test
  @DisplayName("A euro run with a payee that has no IBAN, or no euro account, has no pain.001")
  void pain001Refusals() {
    String acme = api.supplier("Acme Ltd", "EUR", UK_BANK);
    api.dueInvoice(acme, "INV-A1", "EUR", 1, "10.00");
    String id = api.approvedRun("EUR", TODAY.plusDays(3)).getString("id");
    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=PAIN001", "MANAGER", USER),
        409,
        "PURCHASE_PAYING_ACCOUNT_MISSING");
    data(api.put("/payment-runs/paying-accounts/EUR", EUR_ACCOUNT, "OWNER", USER), 200);
    Response refused =
        api.get("/payment-runs/" + id + "/bank-file?format=PAIN001", "MANAGER", USER);
    String body = refused.readEntity(String.class);
    assertThat(body, refused.getStatus(), is(409));
    assertThat(body, containsString("PURCHASE_BANK_FILE_PAYEE_UNSUPPORTED"));
    assertThat(body, containsString("Acme Ltd"));
  }

  @Test
  @DisplayName("A payee name no bank can carry is refused by name, and the CSV is still there")
  void aPayeeNameNoBankCanCarryIsRefusedByName() {
    data(api.put("/payment-runs/paying-accounts/EUR", EUR_ACCOUNT, "OWNER", USER), 200);
    String tokyo =
        api.supplier(
            "Tokyo Trading",
            "EUR",
            "\"bankAccountName\":\"\u682a\u5f0f\u4f1a\u793e\",\"bankIban\":\"DE89 3704 0044 0532 0130 00\"");
    api.dueInvoice(tokyo, "INV-T1", "EUR", 1, "10.00");
    String id = api.approvedRun("EUR", TODAY.plusDays(3)).getString("id");
    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=PAIN001", "MANAGER", USER),
        409,
        "PURCHASE_BANK_FILE_REFUSED");
    // The refusal is the file's alone: the run is still approved and the CSV still reads.
    assertThat(api.get("/payment-runs/" + id + "/bank-file", "MANAGER", USER).getStatus(), is(200));
    assertThat(
        data(api.get("/payment-runs/" + id, "MANAGER", USER), 200).getString("status"),
        is("APPROVED"));
  }

  // ── Bacs Standard 18 ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A sterling run is a Bacs file from the sterling account; too soon or a changed account refused")
  void aSterlingRunIsABacsFile() {
    String acme = api.supplier("Acme Ltd", "GBP", UK_BANK);
    api.dueInvoice(acme, "INV-A1", "GBP", 7, "5.00");
    LocalDate valueDate = Bacs18.earliestValueDate(TODAY).plusDays(1);
    JsonObject run = api.approvedRun("GBP", valueDate);
    String id = run.getString("id");
    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=BACS18", "MANAGER", USER),
        409,
        "PURCHASE_PAYING_ACCOUNT_MISSING");
    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=PAIN001", "MANAGER", USER),
        409,
        "PURCHASE_BANK_FILE_FORMAT_UNSUPPORTED");
    // Set only now, after approval: a run approved before any account was set pays from the first.
    data(api.put("/payment-runs/paying-accounts/GBP", GBP_ACCOUNT, "OWNER", USER), 200);

    Response file = api.get("/payment-runs/" + id + "/bank-file?format=bacs18", "MANAGER", USER);
    String body = file.readEntity(String.class);
    assertThat(body, file.getStatus(), is(200));
    assertThat(file.getHeaderString("Content-Type"), startsWith("text/plain"));
    String[] records = body.split("\r\n");
    assertThat(records.length, is(9));
    assertThat(records[0], startsWith("VOL1"));
    assertThat(records[3].substring(4, 10), is(julian(Bacs18.processingDayFor(valueDate))));
    assertThat(records[4].substring(0, 17), is("12345631415926099"));
    assertThat(records[4].substring(17, 31), is("40281112345678"));
    assertThat(records[4].substring(35, 46), is("00000003500"));
    assertThat(records[4].substring(64, 82).trim(), is(run.getString("reference")));
    assertThat(records[5].substring(0, 17), is("40281112345678017"));
    assertThat(records[8], startsWith("UTL1"));

    // The paying account changes after approval: the run's file is no longer written.
    data(
        api.put(
            "/payment-runs/paying-accounts/GBP",
            GBP_ACCOUNT.replace("12345678", "87654321"),
            "OWNER",
            USER),
        200);
    assertCode(
        api.get("/payment-runs/" + id + "/bank-file?format=BACS18", "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_PAYING_ACCOUNT_CHANGED");

    // A run paying today is too soon for a three-day Bacs cycle.
    api.dueInvoice(acme, "INV-A2", "GBP", 1, "5.00");
    String today = api.approvedRun("GBP", TODAY).getString("id");
    assertCode(
        api.get("/payment-runs/" + today + "/bank-file?format=BACS18", "MANAGER", USER),
        409,
        "PURCHASE_BANK_FILE_TOO_LATE");

    // A payee with only an IBAN cannot be paid by Bacs, and is named.
    String global =
        api.supplier(
            "Global Ltd",
            "GBP",
            "\"bankAccountName\":\"Global Ltd\",\"bankIban\":\"GB82 WEST 1234 5698 7654 32\"");
    api.dueInvoice(global, "INV-G1", "GBP", 1, "5.00");
    String iban = api.approvedRun("GBP", valueDate).getString("id");
    assertCode(
        api.get("/payment-runs/" + iban + "/bank-file?format=BACS18", "MANAGER", USER),
        409,
        "Global Ltd");
  }

  // ── the bank's answer ───────────────────────────────────────────────────────

  @Test
  @DisplayName("A close match is held until a manager releases it once; the run is then paid")
  void aCloseMatchIsHeldUntilReleased() throws Exception {
    Fixture f = euroRun();
    String report =
        report(
            "RPT-1",
            f.reference,
            tx(f.e2e.get("Muster GmbH"), "ACCP", "CMTC", "MUSTER HANDELS GMBH"),
            tx(f.e2e.get("Dupont SA"), "ACCP", "MTCH", null));

    assertThat(
        api.postXml(f.path + "/status-report", report, "CASHIER", USER).getStatus(), is(403));
    assertCode(
        api.postXml(f.path + "/status-report", "<nope", "MANAGER", USER),
        400,
        "PURCHASE_STATUS_REPORT_INVALID");
    assertCode(
        api.postXml(
            f.path + "/status-report",
            "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><d>&x;</d>",
            "MANAGER",
            USER),
        400,
        "PURCHASE_STATUS_REPORT_INVALID");
    assertCode(
        api.postXml(
            f.path + "/status-report",
            report.replace("<OrgnlMsgId>" + f.reference, "<OrgnlMsgId>PAY-SOMEONE-ELSE"),
            "MANAGER",
            USER),
        409,
        "PURCHASE_STATUS_REPORT_NOT_FOR_RUN");
    assertCode(
        api.postXml(
            f.path + "/status-report",
            report(
                "RPT-X", f.reference, tx("0123456789abcdef0123456789abcdef", "ACCP", "MTCH", null)),
            "MANAGER",
            USER),
        409,
        "PURCHASE_STATUS_REPORT_UNKNOWN_PAYMENT");

    JsonObject view = data(api.postXml(f.path + "/status-report", report, "MANAGER", USER), 200);
    JsonObject musterCheck = supplier(view, "Muster GmbH").getJsonObject("bankCheck");
    assertThat(musterCheck.getString("payeeMatch"), is("CMTC"));
    assertThat(musterCheck.getString("matchedName"), is("MUSTER HANDELS GMBH"));
    assertThat(musterCheck.getBoolean("held"), is(true));
    assertThat(musterCheck.getBoolean("releasable"), is(true));
    assertThat(
        supplier(view, "Dupont SA").getJsonObject("bankCheck").getBoolean("held"), is(false));

    Response pay = api.post(f.path + "/pay", "{}", "MANAGER", USER);
    String payBody = pay.readEntity(String.class);
    assertThat(payBody, pay.getStatus(), is(409));
    assertThat(payBody, containsString("PURCHASE_PAYMENT_RUN_PAYEE_HELD"));
    assertThat(payBody, containsString("Muster GmbH: CLOSE_MATCH"));

    assertCode(
        api.post(
            f.path + "/payments/" + f.dupont + "/release",
            "{\"reason\":\"checked\"}",
            "MANAGER",
            USER),
        409,
        "PURCHASE_PAYEE_NOT_HELD");
    assertThat(
        api.post(f.path + "/payments/" + f.muster + "/release", "{}", "MANAGER", USER).getStatus(),
        is(400));

    // Ten releases at once release it once.
    String release = "{\"reason\":\"Rang Muster: the bank's name is their registered name\"}";
    List<Integer> statuses =
        PaymentRunSteps.inParallel(
            10,
            () ->
                api.post(f.path + "/payments/" + f.muster + "/release", release, "MANAGER", USER2)
                    .getStatus());
    assertThat(statuses.stream().filter(s -> s == 200).count(), is(1L));
    assertThat(statuses.stream().filter(s -> s == 409).count(), is(9L));

    // The same report again changes nothing: the release stands.
    JsonObject replayed =
        data(api.postXml(f.path + "/status-report", report, "MANAGER", USER), 200);
    JsonObject released = supplier(replayed, "Muster GmbH").getJsonObject("bankCheck");
    assertThat(released.getBoolean("releasable"), is(false));
    assertThat(released.getString("releaseReason"), containsString("Rang Muster"));

    assertThat(
        data(api.post(f.path + "/pay", "{}", "MANAGER", USER), 200).getString("status"),
        is("PAID"));
    assertCode(
        api.postXml(f.path + "/status-report", report.replace("RPT-1", "RPT-2"), "MANAGER", USER),
        409,
        "PURCHASE_PAYMENT_RUN_NOT_AWAITING_BANK");
  }

  @Test
  @DisplayName(
      "A payee the bank cannot match, or a rejected file, is not releasable; a later answer can clear it")
  void noMatchAndRejectionsAreNotReleasable() throws Exception {
    Fixture f = euroRun();
    data(
        api.postXml(
            f.path + "/status-report",
            report(
                "RPT-1",
                f.reference,
                tx(f.e2e.get("Muster GmbH"), "PDNG", "NMTC", null),
                tx(f.e2e.get("Dupont SA"), "ACCP", "MTCH", null)),
            "MANAGER",
            USER),
        200);
    assertCode(
        api.post(
            f.path + "/payments/" + f.muster + "/release",
            "{\"reason\":\"trust me\"}",
            "OWNER",
            USER),
        409,
        "PURCHASE_PAYEE_NOT_RELEASABLE");
    assertCode(api.post(f.path + "/pay", "{}", "OWNER", USER), 409, "Muster GmbH: NO_MATCH");

    // The whole file rejected holds every payment, and none can be released.
    data(
        api.postXml(
            f.path + "/status-report", reportRejected("RPT-2", f.reference), "MANAGER", USER),
        200);
    assertCode(
        api.post(
            f.path + "/payments/" + f.dupont + "/release",
            "{\"reason\":\"trust me\"}",
            "OWNER",
            USER),
        409,
        "PURCHASE_PAYEE_NOT_RELEASABLE");
    Response pay = api.post(f.path + "/pay", "{}", "OWNER", USER);
    String payBody = pay.readEntity(String.class);
    assertThat(payBody, pay.getStatus(), is(409));
    assertThat(payBody, containsString("Dupont SA: REJECTED FF01"));

    // The bank answers again once the supplier's details are right: the latest answer is in force.
    data(
        api.postXml(
            f.path + "/status-report",
            report(
                "RPT-3",
                f.reference,
                tx(f.e2e.get("Muster GmbH"), "ACSC", "MTCH", null),
                tx(f.e2e.get("Dupont SA"), "ACSC", "MTCH", null)),
            "MANAGER",
            USER),
        200);
    assertThat(
        data(api.post(f.path + "/pay", "{}", "OWNER", USER), 200).getString("status"), is("PAID"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private record Fixture(
      String path, String reference, String muster, String dupont, Map<String, String> e2e) {}

  /**
   * An approved euro run paying Muster and Dupont, and the end-to-end ids its pain.001 gave them.
   */
  private Fixture euroRun() throws Exception {
    data(api.put("/payment-runs/paying-accounts/EUR", EUR_ACCOUNT, "OWNER", USER), 200);
    String muster = api.supplier("Muster GmbH", "EUR", DE_BANK);
    String dupont = api.supplier("Dupont SA", "EUR", FR_BANK);
    api.dueInvoice(muster, "INV-M1", "EUR", 4, "25.00");
    api.dueInvoice(dupont, "INV-D1", "EUR", 2, "10.50");
    JsonObject run = api.approvedRun("EUR", TODAY.plusDays(3));
    String path = "/payment-runs/" + run.getString("id");
    Response file = api.get(path + "/bank-file?format=PAIN001", "MANAGER", USER);
    String xml = file.readEntity(String.class);
    assertThat(xml, file.getStatus(), is(200));
    return new Fixture(path, run.getString("reference"), muster, dupont, endToEndIds(xml));
  }

  /** Creditor name to the end-to-end id the file gave its payment. */
  private static Map<String, String> endToEndIds(String xml) throws Exception {
    Document d = parse(xml);
    String ns = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";
    NodeList txs = d.getElementsByTagNameNS(ns, "CdtTrfTxInf");
    Map<String, String> out = new HashMap<>();
    for (int i = 0; i < txs.getLength(); i++) {
      Element tx = (Element) txs.item(i);
      Element creditor = (Element) tx.getElementsByTagNameNS(ns, "Cdtr").item(0);
      out.put(
          creditor.getElementsByTagNameNS(ns, "Nm").item(0).getTextContent(),
          tx.getElementsByTagNameNS(ns, "EndToEndId").item(0).getTextContent());
    }
    return out;
  }

  private static String report(String messageId, String original, String... transactions) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.002.001.10\">"
        + "<CstmrPmtStsRpt><GrpHdr><MsgId>"
        + messageId
        + "</MsgId><CreDtTm>2026-09-16T08:00:00Z</CreDtTm></GrpHdr>"
        + "<OrgnlGrpInfAndSts><OrgnlMsgId>"
        + original
        + "</OrgnlMsgId><OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId></OrgnlGrpInfAndSts>"
        + "<OrgnlPmtInfAndSts><OrgnlPmtInfId>"
        + original
        + "</OrgnlPmtInfId>"
        + String.join("", transactions)
        + "</OrgnlPmtInfAndSts></CstmrPmtStsRpt></Document>";
  }

  private static String reportRejected(String messageId, String original) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.002.001.10\">"
        + "<CstmrPmtStsRpt><GrpHdr><MsgId>"
        + messageId
        + "</MsgId><CreDtTm>2026-09-16T08:00:00Z</CreDtTm></GrpHdr>"
        + "<OrgnlGrpInfAndSts><OrgnlMsgId>"
        + original
        + "</OrgnlMsgId><OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId>"
        + "<GrpSts>RJCT</GrpSts><StsRsnInf><Rsn><Cd>FF01</Cd></Rsn></StsRsnInf></OrgnlGrpInfAndSts></CstmrPmtStsRpt></Document>";
  }

  private static String tx(String e2e, String status, String match, String name) {
    return "<TxInfAndSts><OrgnlEndToEndId>"
        + e2e
        + "</OrgnlEndToEndId><TxSts>"
        + status
        + "</TxSts>"
        + (match == null
            ? ""
            : "<StsRsnInf><Rsn><Prtry>"
                + match
                + "</Prtry></Rsn>"
                + (name == null ? "" : "<AddtlInf>" + name + "</AddtlInf>")
                + "</StsRsnInf>")
        + "</TxInfAndSts>";
  }

  private static JsonObject supplier(JsonObject run, String name) {
    for (JsonValue v : run.getJsonArray("suppliers")) {
      if (v.asJsonObject().getString("name").equals(name)) return v.asJsonObject();
    }
    throw new AssertionError("no supplier " + name + " in " + run);
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (var reader = jakarta.json.Json.createReader(new java.io.StringReader(body))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return f.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static String julian(LocalDate d) {
    return String.format(java.util.Locale.ROOT, " %02d%03d", d.getYear() % 100, d.getDayOfYear());
  }
}
