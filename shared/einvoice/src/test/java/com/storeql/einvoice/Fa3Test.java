package com.storeql.einvoice;

import static com.storeql.einvoice.TestInvoices.invoice;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/** Poland's FA(3), written from the model: what KSeF takes, and what it would refuse. */
class Fa3Test {

  private static final String SELLER_NIP = "5260250274";
  private static final String BUYER_NIP = "7740001454";

  private static Invoice.Party party(
      String name, String vatId, String city, String postcode, String country) {
    return new Invoice.Party(
        name,
        null,
        List.of(),
        null,
        vatId,
        null,
        null,
        null,
        new Invoice.Address("ul. Portowa 1", null, null, city, postcode, null, country),
        null);
  }

  private static Invoice.Line line(
      String id, String name, String qty, String unit, String net, String category, String rate) {
    BigDecimal quantity = new BigDecimal(qty);
    BigDecimal amount = new BigDecimal(net);
    return new Invoice.Line(
        id,
        null,
        null,
        quantity,
        unit,
        amount,
        null,
        null,
        null,
        List.of(),
        new Invoice.Price(
            amount.divide(quantity, 6, java.math.RoundingMode.HALF_UP), null, null, null, null),
        category,
        rate == null ? null : new BigDecimal(rate),
        new Invoice.Item(name, null, null, null, null, List.of(), null, List.of()));
  }

  private static Invoice.Totals totals(String net, String allowances, String vat, String gross) {
    return new Invoice.Totals(
        new BigDecimal(net).add(new BigDecimal(allowances)),
        new BigDecimal(allowances),
        null,
        new BigDecimal(net),
        new BigDecimal(vat),
        null,
        new BigDecimal(gross),
        null,
        null,
        new BigDecimal(gross));
  }

  private static Invoice polish(
      String type,
      List<Invoice.AllowanceCharge> allowances,
      List<Invoice.Line> lines,
      List<Invoice.PrecedingInvoice> preceding,
      Invoice.Totals totals) {
    return invoice(
        "FV/2026/000001",
        type,
        "PLN",
        party("Sklep Portowy sp. z o.o.", "PL" + SELLER_NIP, "Gdańsk", "80-001", "PL"),
        party("Kawiarnia Molo sp. z o.o.", "PL" + BUYER_NIP, "Gdańsk", "80-002", "PL"),
        allowances,
        lines,
        preceding,
        totals,
        List.of(
            new Invoice.VatBreakdown(
                new BigDecimal("190.00"),
                new BigDecimal("43.70"),
                "S",
                new BigDecimal("23"),
                null,
                null),
            new Invoice.VatBreakdown(
                new BigDecimal("50.00"),
                new BigDecimal("4.00"),
                "S",
                new BigDecimal("8"),
                null,
                null)));
  }

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return f.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static String text(Document d, String name) {
    NodeList n = d.getElementsByTagNameNS(Fa3.NAMESPACE, name);
    return n.getLength() == 0 ? null : n.item(0).getTextContent();
  }

  @Test
  void anInvoiceAtTwoRatesWithADiscountIsFa3WithItsBucketsAndTheDiscountOnItsLines()
      throws Exception {
    Invoice inv =
        polish(
            "380",
            List.of(
                new Invoice.AllowanceCharge(
                    false,
                    new BigDecimal("10.00"),
                    null,
                    null,
                    "S",
                    new BigDecimal("23"),
                    "Rabat",
                    "95")),
            List.of(
                line("1", "Kawa ziarnista", "2", "KGM", "200.00", "S", "23"),
                line("2", "Ciastka", "5", "C62", "50.00", "S", "8")),
            List.of(),
            totals("240.00", "10.00", "47.70", "287.70"));
    String xml = Fa3.write(inv, Instant.parse("2026-09-16T10:00:00Z"), "StoreQL");
    Document d = parse(xml);
    assertEquals("Faktura", d.getDocumentElement().getLocalName());
    assertEquals(Fa3.NAMESPACE, d.getDocumentElement().getNamespaceURI());
    assertEquals(
        "FA (3)",
        d.getElementsByTagNameNS(Fa3.NAMESPACE, "KodFormularza")
            .item(0)
            .getAttributes()
            .getNamedItem("kodSystemowy")
            .getTextContent());
    assertEquals("3", text(d, "WariantFormularza"));
    assertEquals("2026-09-16T10:00:00Z", text(d, "DataWytworzeniaFa"));
    assertEquals(
        SELLER_NIP, d.getElementsByTagNameNS(Fa3.NAMESPACE, "NIP").item(0).getTextContent());
    assertEquals(
        BUYER_NIP, d.getElementsByTagNameNS(Fa3.NAMESPACE, "NIP").item(1).getTextContent());
    assertEquals(
        "80-002 Gdańsk",
        d.getElementsByTagNameNS(Fa3.NAMESPACE, "AdresL2").item(1).getTextContent());
    assertEquals("2", text(d, "JST"));
    assertEquals("PLN", text(d, "KodWaluty"));
    assertEquals("FV/2026/000001", text(d, "P_2"));
    assertEquals("190.00", text(d, "P_13_1"));
    assertEquals("43.70", text(d, "P_14_1"));
    assertEquals("50.00", text(d, "P_13_2"));
    assertEquals("4.00", text(d, "P_14_2"));
    assertEquals("287.70", text(d, "P_15"));
    assertEquals("VAT", text(d, "RodzajFaktury"));
    assertEquals("2", text(d, "P_16"));
    assertEquals("1", text(d, "P_19N"));
    assertEquals(2, d.getElementsByTagNameNS(Fa3.NAMESPACE, "FaWiersz").getLength());
    // The 10.00 came off the 23% line: P_10 on it, and P_11 net of it; the 8% line untouched.
    assertEquals("10.00", text(d, "P_10"));
    NodeList p11 = d.getElementsByTagNameNS(Fa3.NAMESPACE, "P_11");
    assertEquals("190.00", p11.item(0).getTextContent());
    assertEquals("50.00", p11.item(1).getTextContent());
    NodeList p12 = d.getElementsByTagNameNS(Fa3.NAMESPACE, "P_12");
    assertEquals("23", p12.item(0).getTextContent());
    assertEquals("8", p12.item(1).getTextContent());
    assertEquals("kg", d.getElementsByTagNameNS(Fa3.NAMESPACE, "P_8A").item(0).getTextContent());
    assertEquals("szt.", d.getElementsByTagNameNS(Fa3.NAMESPACE, "P_8A").item(1).getTextContent());
    assertEquals("100", d.getElementsByTagNameNS(Fa3.NAMESPACE, "P_9A").item(0).getTextContent());
    assertTrue(Fa3.check(inv).isEmpty());
  }

  @Test
  void aCreditNoteIsAKorNamingTheInvoiceWithItsLinesAsDifferences() throws Exception {
    Invoice note =
        polish(
            "381",
            List.of(),
            List.of(line("1", "Kawa ziarnista", "1", "KGM", "100.00", "S", "23")),
            List.of(new Invoice.PrecedingInvoice("FV/2026/000001", LocalDate.of(2026, 9, 16))),
            totals("100.00", "0.00", "23.00", "123.00"));
    Document d = parse(Fa3.write(note, Instant.parse("2026-09-17T10:00:00Z"), null));
    assertEquals("KOR", text(d, "RodzajFaktury"));
    assertEquals("FV/2026/000001", text(d, "NrFaKorygowanej"));
    assertEquals("2026-09-16", text(d, "DataWystFaKorygowanej"));
    assertEquals("-1", text(d, "P_8B"));
    assertEquals("-100.00", text(d, "P_11"));
    assertEquals("-123.00", text(d, "P_15"));
    assertEquals("-100.00", text(d, "P_13_1"));
  }

  @Test
  void whatKsefWouldRefuseIsRefusedFirst() {
    Invoice foreign =
        invoice(
            "INV-1",
            "380",
            "GBP",
            party("Corner Shop Ltd", "GB123456789", "London", "E1 6AN", "GB"),
            party("Kawiarnia", "DE123456789", "Berlin", "10115", "DE"),
            List.of(),
            List.of(line("1", "Tea", "1", "C62", "100.00", "S", "20")),
            List.of(),
            totals("100.00", "0.00", "20.00", "120.00"),
            List.of());
    List<String> rules = Fa3.check(foreign).stream().map(Violation::rule).toList();
    assertTrue(rules.contains("FA3-SELLER-NIP"), rules.toString());
    assertTrue(rules.contains("FA3-RATE"), rules.toString());
    assertThrows(IllegalArgumentException.class, () -> Fa3.write(foreign, Instant.now(), null));
    assertTrue(Fa3.validNip(SELLER_NIP));
    assertFalse(Fa3.validNip("5260250275"));
    assertEquals(SELLER_NIP, Fa3.nipOf("pl 526-025-02-74"));
    assertEquals(null, Fa3.nipOf("DE123456789"));
    assertEquals("zw", Fa3.bucket("E", BigDecimal.ZERO).p12());
    assertEquals("oo", Fa3.bucket("AE", null).p12());
    assertEquals("0 KR", Fa3.bucket("Z", BigDecimal.ZERO).p12());
    assertEquals("P_13_3", Fa3.bucket("S", new BigDecimal("5")).net());
    assertEquals(null, Fa3.bucket("S", new BigDecimal("19")));
    assertEquals("l", Fa3.unit("LTR"));
  }

  @Test
  void aBuyerFromAnotherEuCountryIsNamedByItsVatNumberAndOneWithNoneIsSaidToHaveNone()
      throws Exception {
    Invoice eu =
        invoice(
            "FV/2026/000002",
            "380",
            "PLN",
            party("Sklep Portowy sp. z o.o.", "PL" + SELLER_NIP, "Gdańsk", "80-001", "PL"),
            party("Café Berlin GmbH", "DE123456789", "Berlin", "10115", "DE"),
            List.of(),
            List.of(line("1", "Kawa", "1", "KGM", "100.00", "AE", "0")),
            List.of(),
            totals("100.00", "0.00", "0.00", "100.00"),
            List.of(
                new Invoice.VatBreakdown(
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    "AE",
                    BigDecimal.ZERO,
                    "Reverse charge",
                    "VATEX-EU-AE")));
    Document d = parse(Fa3.write(eu, Instant.now(), null));
    assertEquals("DE", text(d, "KodUE"));
    assertEquals("123456789", text(d, "NrVatUE"));
    assertEquals("1", text(d, "P_18"));
    assertEquals("oo", text(d, "P_12"));
    assertEquals("100.00", text(d, "P_13_9"));
    Invoice nobody =
        invoice(
            "FV/2026/000003",
            "380",
            "PLN",
            party("Sklep Portowy sp. z o.o.", "PL" + SELLER_NIP, "Gdańsk", "80-001", "PL"),
            party("Jan Kowalski", null, "Gdańsk", "80-003", "PL"),
            List.of(),
            List.of(line("1", "Kawa", "1", "KGM", "100.00", "S", "23")),
            List.of(),
            totals("100.00", "0.00", "23.00", "123.00"),
            List.of());
    assertEquals("1", text(parse(Fa3.write(nobody, Instant.now(), null)), "BrakID"));
  }
}
