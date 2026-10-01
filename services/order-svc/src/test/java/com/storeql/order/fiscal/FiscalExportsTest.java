package com.storeql.order.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.FiscalReceipt;
import com.storeql.order.domain.Domain.FiscalStoreSettings;
import com.storeql.order.domain.Domain.PtStamp;
import com.storeql.order.domain.Domain.TseDevice;
import com.storeql.order.domain.Domain.TseStamp;
import com.storeql.order.repo.FiscalReceiptRepository.RegisterLine;
import com.storeql.order.repo.FiscalReceiptRepository.RegisterOrder;
import com.storeql.order.repo.FiscalReceiptRepository.RegisterTender;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * The two inspector files, written from one fixture register: two documents on two days, one of
 * them voided, a mixed-rate basket, a split tender. What the tests pin is the shape an auditor's
 * import reads — the table names and columns, the decimal comma, the date format, the XML elements
 * — because a file in the wrong shape is a file that is not accepted.
 */
class FiscalExportsTest {

  static final UUID TENANT = Ids.parse("01a090ae-611e-702a-9bdf-bcc7032115c4");
  static final UUID STORE = Ids.parse("01a090ae-611e-7035-a4da-400bf673cfe8");
  static final UUID V1 = Ids.parse("01a090ae-611e-7055-9838-5de027ce9e0a");
  static final UUID V2 = Ids.parse("01a090ae-611e-7055-9838-5de027ce9e0b");

  static FiscalReceipt doc(
      long n,
      String day,
      BigDecimal gross,
      BigDecimal tax,
      TseStamp tse,
      PtStamp pt,
      boolean voided) {
    Instant at = Instant.parse(day);
    return new FiscalReceipt(
        Ids.newId(),
        TENANT,
        STORE,
        "MAIN",
        "2026",
        n,
        "DE-B-2026-00000" + n,
        Ids.newId(),
        at,
        Ids.parse("01a090ae-611e-7055-9838-5de027ce9e0c"),
        "EUR",
        gross,
        tax,
        voided ? at.plusSeconds(60) : null,
        voided ? "wrong item" : null,
        n == 1 ? "GENESIS" : "h" + (n - 1),
        "h" + n,
        tse == null ? (pt == null ? "NONE" : "PT_SAFT") : "DE_KASSENSICHV",
        tse,
        pt);
  }

  @Test
  void aDocumentsLinesAndTendersComeFromTheIndexNotAScanOfTheYear() {
    RegisterSnapshot snap = snapshot(true);
    for (var d : snap.documents()) {
      assertEquals(
          snap.lines().stream().filter(l -> l.number() == d.number()).toList(),
          snap.linesOf(d.number()));
      assertEquals(
          snap.tenders().stream().filter(t -> t.number() == d.number()).toList(),
          snap.tendersOf(d.number()));
    }
    assertTrue(snap.linesOf(9999).isEmpty());
    assertTrue(snap.tendersOf(9999).isEmpty());
  }

  static RegisterSnapshot snapshot(boolean german) {
    TseDevice device =
        german
            ? new TseDevice(
                Ids.newId(),
                TENANT,
                STORE,
                "SIMULATED",
                "till-1",
                "serial-abc",
                "PUBKEY",
                "ecdsa-plain-SHA256",
                "unixTime",
                null,
                null,
                2,
                2,
                Instant.parse("2026-01-01T00:00:00Z"),
                null)
            : null;
    TseStamp t1 =
        german
            ? new TseStamp(
                "serial-abc",
                "till-1",
                1L,
                1L,
                "SIG1",
                "ecdsa-plain-SHA256",
                "PUBKEY",
                "unixTime",
                Instant.parse("2026-09-11T09:59:50Z"),
                Instant.parse("2026-09-11T10:00:00Z"),
                "Kassenbeleg-V1",
                "Beleg^11.90_2.14_0.00_0.00_0.00^10.00:Bar_4.04:Unbar",
                "V0;till-1;...",
                null)
            : null;
    TseStamp t2 = german ? TseStamp.failed("till-1", "cloud TSE unreachable") : null;
    PtStamp p1 =
        german ? null : new PtStamp("FS DE-B-2026/1", "HASH1", "1", "ABC-1", "1234", "HAH1");
    PtStamp p2 =
        german ? null : new PtStamp("FS DE-B-2026/2", "HASH2", "1", "ABC-2", "1234", "HAH2");
    List<FiscalReceipt> docs =
        List.of(
            doc(
                1,
                "2026-09-11T10:00:00Z",
                new BigDecimal("14.04"),
                new BigDecimal("2.04"),
                t1,
                p1,
                false),
            doc(
                2,
                "2026-09-12T15:30:00Z",
                new BigDecimal("5.95"),
                new BigDecimal("0.95"),
                t2,
                p2,
                true));
    List<RegisterLine> lines =
        List.of(
            new RegisterLine(
                1,
                V1,
                new BigDecimal("1.000"),
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                new BigDecimal("1.90")),
            new RegisterLine(
                1,
                V2,
                new BigDecimal("2.000"),
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                new BigDecimal("0.14")),
            new RegisterLine(
                2,
                V1,
                new BigDecimal("0.500"),
                new BigDecimal("10.00"),
                new BigDecimal("5.00"),
                null));
    List<RegisterTender> tenders =
        List.of(
            new RegisterTender(1, "CASH", new BigDecimal("10.00")),
            new RegisterTender(1, "CARD", new BigDecimal("4.04")),
            new RegisterTender(2, "CARD", new BigDecimal("5.95")));
    Map<Long, RegisterOrder> orders = new LinkedHashMap<>();
    orders.put(
        1L,
        new RegisterOrder(
            1,
            "POS",
            null,
            new BigDecimal("12.00"),
            new BigDecimal("2.04"),
            new BigDecimal("14.04"),
            null));
    orders.put(
        2L,
        new RegisterOrder(
            2,
            "POS",
            "CARD",
            new BigDecimal("5.00"),
            new BigDecimal("0.95"),
            new BigDecimal("5.95"),
            null));
    return new RegisterSnapshot(
        new FiscalStoreSettings(
            TENANT,
            STORE,
            german ? "DE_KASSENSICHV" : "PT_SAFT",
            german ? "DE123456789" : "500000000",
            german ? null : "1234",
            german ? null : "ABC",
            null,
            null),
        device,
        new RegisterSnapshot.Business(
            "Beispiel GmbH", german ? "DE123456789" : "500000000", german ? "DE" : "PT"),
        new RegisterSnapshot.Store(
            STORE,
            "Berlin Mitte",
            "B-01",
            "Unter den Linden 1",
            null,
            "Berlin",
            null,
            german ? "DE" : "PT",
            "10117"),
        "MAIN",
        "2026",
        "EUR",
        Instant.parse("2026-09-12T16:00:00Z"),
        docs,
        lines,
        tenders,
        orders,
        Map.of(
            V1,
            new RegisterSnapshot.ProductName("Wein; rot", "SKU-WINE", "Stk"),
            V2,
            new RegisterSnapshot.ProductName("Brot", "SKU-BREAD", "Stk")));
  }

  static Map<String, String> unzip(byte[] bytes) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = z.getNextEntry()) != null) {
        out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  @Test
  void theGermanFileCarriesEveryTableTheAuditReadsFirstAndAnIndexDescribingThem() throws Exception {
    Map<String, String> files = unzip(DsfinvkExport.write(snapshot(true)));
    for (String t :
        List.of(
            "cashpointclosing",
            "location",
            "cashregister",
            "tse",
            "vat",
            "businesscases",
            "payment",
            "transactions",
            "transactions_tse",
            "transactions_vat",
            "lines",
            "lines_vat",
            "datapayment")) {
      assertTrue(files.containsKey(t + ".csv"), t);
      assertTrue(files.get("index.xml").contains("<URL>" + t + ".csv</URL>"), t);
    }
    assertTrue(files.containsKey("gdpdu-01-09-2004.dtd"));
    assertTrue(files.get("index.xml").contains("<DecimalSymbol>,</DecimalSymbol>"));
    assertTrue(files.get("index.xml").contains("<Version>2.3</Version>"));
  }

  @Test
  void twoDaysAreTwoClosingsAndTheClosingSumsCashApart() throws Exception {
    Map<String, String> files = unzip(DsfinvkExport.write(snapshot(true)));
    String[] closings = files.get("cashpointclosing.csv").split("\r\n");
    assertEquals(
        "Z_KASSE_ID;Z_ERSTELLUNG;Z_NR;Z_BUCHUNGSTAG;TAXONOMIE_VERSION;Z_START_ID;Z_ENDE_ID;NAME;STRASSE;PLZ;ORT;LAND;STNR;USTID;Z_SE_ZAHLUNGEN;Z_SE_BARZAHLUNGEN",
        closings[0]);
    assertEquals(3, closings.length);
    assertTrue(
        closings[1].startsWith(
            "till-1;2026-09-11T10:00:00.000Z;1;2026-09-11;2.3;DE-B-2026-000001;DE-B-2026-000001;Beispiel GmbH;Unter den Linden 1;10117;Berlin;DE;DE123456789;;14,04;10,00"),
        closings[1]);
    assertTrue(
        closings[2].startsWith(
            "till-1;2026-09-12T15:30:00.000Z;2;2026-09-12;2.3;DE-B-2026-000002;DE-B-2026-000002;"),
        closings[2]);
    assertTrue(closings[2].endsWith(";5,95;0,00"), closings[2]);
  }

  @Test
  void theTseTableCarriesTheStampAndTheOutageInItsOwnColumn() throws Exception {
    Map<String, String> files = unzip(DsfinvkExport.write(snapshot(true)));
    String[] rows = files.get("transactions_tse.csv").split("\r\n");
    assertEquals(
        "Z_KASSE_ID;Z_ERSTELLUNG;Z_NR;BON_ID;TSE_ID;TSE_TANR;TSE_TA_START;TSE_TA_ENDE;TSE_TA_VORGANGSART;TSE_TA_SIGZ;TSE_TA_SIG;TSE_TA_FEHLER;TSE_TA_VORGANGSDATEN",
        rows[0]);
    assertEquals(
        "till-1;2026-09-11T10:00:00.000Z;1;DE-B-2026-000001;1;1;2026-09-11T09:59:50.000Z;2026-09-11T10:00:00.000Z;Kassenbeleg-V1;1;SIG1;;Beleg^11.90_2.14_0.00_0.00_0.00^10.00:Bar_4.04:Unbar",
        rows[1]);
    assertEquals(
        "till-1;2026-09-12T15:30:00.000Z;2;DE-B-2026-000002;1;;;;;;;cloud TSE unreachable;",
        rows[2]);
    String tse = files.get("tse.csv");
    assertTrue(tse.contains(";1;serial-abc;ecdsa-plain-SHA256;unixTime;UTF-8;PUBKEY;;"), tse);
  }

  @Test
  void linesAreListedByVatKeyWithDecimalCommasAndTextQuotedWhenItCarriesTheDelimiter()
      throws Exception {
    Map<String, String> files = unzip(DsfinvkExport.write(snapshot(true)));
    String lines = files.get("lines.csv");
    assertTrue(
        lines.contains(
            ";DE-B-2026-000001;1;;\"Wein; rot\";till-1;Umsatz;;1;0;;SKU-WINE;;;;1,000;1,000;Stk;11,90000"),
        lines);
    String vat = files.get("lines_vat.csv");
    assertTrue(vat.contains(";DE-B-2026-000001;1;1;11,90000;10,00000;1,90000"), vat);
    assertTrue(vat.contains(";DE-B-2026-000001;2;2;2,14000;2,00000;0,14000"), vat);
    // The unpriced line on document 2 gets the order's tax apportioned: 0.95 on 5.00 is 19%.
    assertTrue(vat.contains(";DE-B-2026-000002;1;1;5,95000;5,00000;0,95000"), vat);
    String tvat = files.get("transactions_vat.csv");
    assertTrue(tvat.contains(";DE-B-2026-000001;1;11,90;10,00;1,90"), tvat);
    assertTrue(tvat.contains(";DE-B-2026-000001;2;2,14;2,00;0,14"), tvat);
    String pay = files.get("datapayment.csv");
    assertTrue(pay.contains(";DE-B-2026-000001;Bar;CASH;EUR;10,00;10,00"), pay);
    assertTrue(pay.contains(";DE-B-2026-000001;Unbar;CARD;EUR;4,04;4,04"), pay);
    String tx = files.get("transactions.csv");
    assertTrue(tx.contains(";DE-B-2026-000002;2;Beleg;;till-1;1;"), tx);
    assertTrue(tx.contains(";wrong item"), tx);
  }

  @Test
  void thePortugueseFileIsWellFormedSafTWithEveryDocumentSignedAndTheVoidMarked() throws Exception {
    String xml = SaftPtExport.write(snapshot(false), "999999990", "1.0");
    Document d =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("AuditFile", d.getDocumentElement().getNodeName());
    assertEquals(
        SaftPtExport.NAMESPACE,
        d.getDocumentElement().getNamespaceURI() == null
            ? d.getDocumentElement().getAttribute("xmlns")
            : d.getDocumentElement().getNamespaceURI());
    assertEquals(2, d.getElementsByTagName("Invoice").getLength());
    assertEquals("1.04_01", d.getElementsByTagName("AuditFileVersion").item(0).getTextContent());
    assertEquals(
        "500000000", d.getElementsByTagName("TaxRegistrationNumber").item(0).getTextContent());
    assertEquals(
        "1234", d.getElementsByTagName("SoftwareCertificateNumber").item(0).getTextContent());
    assertEquals(
        "999999990", d.getElementsByTagName("ProductCompanyTaxID").item(0).getTextContent());
    assertEquals("FS DE-B-2026/1", d.getElementsByTagName("InvoiceNo").item(0).getTextContent());
    assertEquals("ABC-1", d.getElementsByTagName("ATCUD").item(0).getTextContent());
    assertEquals("HASH1", d.getElementsByTagName("Hash").item(0).getTextContent());
    assertEquals("N", d.getElementsByTagName("InvoiceStatus").item(0).getTextContent());
    assertEquals("A", d.getElementsByTagName("InvoiceStatus").item(1).getTextContent());
    assertEquals("wrong item", d.getElementsByTagName("Reason").item(0).getTextContent());
    assertEquals("2", d.getElementsByTagName("NumberOfEntries").item(0).getTextContent());
    // Only the live document counts toward the credit total: 14.04 - 2.04.
    assertEquals("12.00", d.getElementsByTagName("TotalCredit").item(0).getTextContent());
    assertEquals(
        "Consumidor final", d.getElementsByTagName("CompanyName").item(1).getTextContent());
    assertEquals("999999990", d.getElementsByTagName("CustomerTaxID").item(0).getTextContent());
    // 19% is nearest Portugal's 23% NOR; 7% nearest 6% RED — both entries appear in the table.
    assertEquals(2, d.getElementsByTagName("TaxTableEntry").getLength());
    assertEquals("NU", d.getElementsByTagName("PaymentMechanism").item(0).getTextContent());
    assertEquals("CC", d.getElementsByTagName("PaymentMechanism").item(1).getTextContent());
    assertTrue(xml.contains("<ProductDescription>Wein; rot</ProductDescription>"));
  }

  @Test
  void taxCodesAndPaymentMechanismsMapByNearestRateAndByMethod() {
    assertEquals("NOR", SaftPtExport.taxCode(new BigDecimal("23.00")));
    assertEquals("INT", SaftPtExport.taxCode(new BigDecimal("13.00")));
    assertEquals("RED", SaftPtExport.taxCode(new BigDecimal("6.00")));
    assertEquals("ISE", SaftPtExport.taxCode(BigDecimal.ZERO));
    assertEquals("NU", SaftPtExport.paymentMechanism("cash"));
    assertEquals("CC", SaftPtExport.paymentMechanism("CARD"));
    assertEquals("OU", SaftPtExport.paymentMechanism("UPI"));
    assertEquals("OU", SaftPtExport.paymentMechanism(null));
  }
}
