package com.storeql.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading Poland's FA(3) back into the EN 16931 model.
 *
 * <p>The strongest test available is the round trip: write a document with {@link Fa3}, read it
 * with {@link Fa3Reader}, and require the invoice to come back. It is strong because the writer was
 * built against the schema and the reader was built against the writer — a disagreement between
 * them is a disagreement about the structure, which is exactly the bug worth catching.
 *
 * <p>What the round trip cannot prove is asserted separately: a correction's negative amounts
 * coming back positive, an address line that is not written the Polish way staying whole, and a
 * rate read from the bucket it was totalled into.
 */
class Fa3ReaderTest {

  private static Invoice.Party seller() {
    return new Invoice.Party(
        "Sklep Portowy sp. z o.o.",
        null,
        List.of(),
        null,
        "PL5260250274",
        null,
        null,
        null,
        new Invoice.Address("ul. Portowa 1", null, null, "Gdańsk", "80-001", null, "PL"),
        null);
  }

  private static Invoice.Party buyer(String vatId, String name) {
    return new Invoice.Party(
        name,
        null,
        List.of(),
        null,
        vatId,
        null,
        null,
        null,
        new Invoice.Address("ul. Długa 2", null, null, "Gdańsk", "80-002", null, "PL"),
        null);
  }

  private static Invoice.Line line(
      String name, String qty, String net, String rate, String category) {
    return new Invoice.Line(
        "1",
        null,
        null,
        new BigDecimal(qty),
        "EA",
        new BigDecimal(net),
        null,
        null,
        null,
        List.of(),
        null,
        category,
        new BigDecimal(rate),
        new Invoice.Item(name, null, null, null, null, List.of(), null, List.of()));
  }

  private static Invoice invoice(
      String number,
      String typeCode,
      List<Invoice.Line> lines,
      List<Invoice.VatBreakdown> vat,
      String payable,
      List<Invoice.PrecedingInvoice> preceding,
      Invoice.Party buyer) {
    BigDecimal net =
        lines.stream().map(Invoice.Line::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal tax =
        vat.stream().map(Invoice.VatBreakdown::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Invoice(
        Invoice.EN16931,
        null,
        number,
        LocalDate.of(2026, 9, 14),
        typeCode,
        "PLN",
        null,
        null,
        null,
        LocalDate.of(2026, 10, 14),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        preceding,
        seller(),
        buyer,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        new Invoice.Totals(
            net, null, null, net, tax, null, net.add(tax), null, null, new BigDecimal(payable)),
        vat,
        List.of(),
        lines);
  }

  private static Invoice.VatBreakdown vat(
      String taxable, String tax, String category, String rate) {
    return new Invoice.VatBreakdown(
        new BigDecimal(taxable), new BigDecimal(tax), category, new BigDecimal(rate), null, null);
  }

  private static EInvoices.Received readBack(String xml) {
    return EInvoices.read(xml.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("What FA(3) writes, FA(3) reads: the invoice comes back")
  void roundTrip() {
    Invoice out =
        invoice(
            "FV/2026/0001",
            "380",
            List.of(line("Kawa 1kg", "2", "100.00", "23", "S")),
            List.of(vat("100.00", "23.00", "S", "23")),
            "123.00",
            List.of(),
            buyer("PL7740001454", "Kawiarnia Molo sp. z o.o."));
    String xml = Fa3.write(out, Instant.parse("2026-09-14T09:00:00Z"), "storeql");

    EInvoices.Received received = readBack(xml);
    assertEquals(EInvoices.Syntax.FA3, received.syntax(), "read as Poland's own structure");
    Invoice back = received.invoice();
    assertEquals("FV/2026/0001", back.number());
    assertEquals(LocalDate.of(2026, 9, 14), back.issueDate());
    assertEquals("PLN", back.currency());
    assertEquals("380", back.typeCode());
    assertEquals("PL5260250274", back.seller().vatId());
    assertEquals("Sklep Portowy sp. z o.o.", back.seller().name());
    assertEquals("PL7740001454", back.buyer().vatId());
    assertEquals("80-001", back.seller().address().postcode(), "a Polish postcode is read out");
    assertEquals("Gdańsk", back.seller().address().city());

    assertEquals(1, back.lines().size());
    Invoice.Line l = back.lines().get(0);
    assertEquals("Kawa 1kg", l.item().name());
    assertEquals(new BigDecimal("2"), l.quantity());
    assertEquals(new BigDecimal("100.00"), l.netAmount());
    assertEquals("S", l.vatCategory());
    assertEquals(new BigDecimal("23"), l.vatRate());

    assertEquals(1, back.vatBreakdown().size());
    assertEquals(new BigDecimal("100.00"), back.vatBreakdown().get(0).taxableAmount());
    assertEquals(new BigDecimal("23.00"), back.vatBreakdown().get(0).taxAmount());
    assertEquals(new BigDecimal("123.00"), back.totals().payable());
    assertEquals(new BigDecimal("123.00"), back.totals().withVat());
  }

  @Test
  @DisplayName("A correction's negative amounts come back positive, as a credit note")
  void aCorrection() {
    // FA(3) writes a KOR with negative money. The model and UBL both hold a credit note as positive
    // amounts under a credit-note type code, so reading it any other way would double the sign
    // somewhere downstream.
    Invoice out =
        invoice(
            "KOR/2026/0002",
            "381",
            List.of(line("Kawa 1kg", "1", "50.00", "23", "S")),
            List.of(vat("50.00", "11.50", "S", "23")),
            "61.50",
            List.of(new Invoice.PrecedingInvoice("FV/2026/0001", LocalDate.of(2026, 9, 14))),
            buyer("PL7740001454", "Kawiarnia Molo sp. z o.o."));
    String xml = Fa3.write(out, Instant.parse("2026-09-20T09:00:00Z"), "storeql");
    assertTrue(xml.contains("<P_11>-50.00</P_11>"), "the document itself is negative");

    Invoice back = readBack(xml).invoice();
    assertEquals("381", back.typeCode());
    assertTrue(back.isCreditNote());
    assertEquals(new BigDecimal("50.00"), back.lines().get(0).netAmount());
    assertEquals(new BigDecimal("61.50"), back.totals().payable());
    assertEquals("FV/2026/0001", back.precedingInvoices().get(0).number());
    assertEquals(LocalDate.of(2026, 9, 14), back.precedingInvoices().get(0).issueDate());
  }

  @Test
  @DisplayName("An exempt supply comes back as a category with its reason, and no VAT bucket")
  void exemptAndZeroRates() {
    Invoice out =
        invoice(
            "FV/2026/0003",
            "380",
            List.of(line("Usługa zwolniona", "1", "200.00", "0", "E")),
            List.of(
                new Invoice.VatBreakdown(
                    new BigDecimal("200.00"),
                    BigDecimal.ZERO,
                    "E",
                    BigDecimal.ZERO,
                    "usługi medyczne",
                    null)),
            "200.00",
            List.of(),
            buyer("PL7740001454", "Przychodnia sp. z o.o."));
    String xml = Fa3.write(out, Instant.parse("2026-09-14T09:00:00Z"), "storeql");
    assertTrue(xml.contains("<P_12>zw</P_12>"));

    Invoice back = readBack(xml).invoice();
    assertEquals("E", back.lines().get(0).vatCategory());
    assertEquals("E", back.vatBreakdown().get(0).category());
    assertEquals(BigDecimal.ZERO, back.vatBreakdown().get(0).taxAmount());
    assertEquals("usługi medyczne", back.vatBreakdown().get(0).exemptionReason());
    assertEquals(new BigDecimal("200.00"), back.totals().withoutVat());
  }

  @Test
  @DisplayName("A buyer with no Polish number keeps the EU one it was written with")
  void anEuBuyer() {
    Invoice out =
        invoice(
            "FV/2026/0004",
            "380",
            List.of(line("Kawa 1kg", "1", "80.00", "23", "S")),
            List.of(vat("80.00", "18.40", "S", "23")),
            "98.40",
            List.of(),
            buyer("DE811569869", "Kaffeehaus GmbH"));
    Invoice back =
        readBack(Fa3.write(out, Instant.parse("2026-09-14T09:00:00Z"), "storeql")).invoice();
    assertEquals("DE811569869", back.buyer().vatId());
  }

  @Test
  @DisplayName("An address that is not written the Polish way stays whole rather than being split")
  void anAddressThatIsNotPolish() {
    // A wrong postcode on a supplier is worse than none: it reaches the supplier record, the
    // remittance advice and the ledger.
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Faktura xmlns=\""
            + Fa3.NAMESPACE
            + "\"><Naglowek><KodFormularza kodSystemowy=\"FA (3)\" wersjaSchemy=\"1-0E\">FA"
            + "</KodFormularza><WariantFormularza>3</WariantFormularza></Naglowek>"
            + "<Podmiot1><DaneIdentyfikacyjne><NIP>5260250274</NIP><Nazwa>Sklep</Nazwa>"
            + "</DaneIdentyfikacyjne><Adres><KodKraju>PL</KodKraju>"
            + "<AdresL1>ul. Portowa 1</AdresL1><AdresL2>Warszawa Śródmieście</AdresL2></Adres>"
            + "</Podmiot1><Podmiot2><DaneIdentyfikacyjne><BrakID>1</BrakID><Nazwa>Klient</Nazwa>"
            + "</DaneIdentyfikacyjne><Adres><KodKraju>PL</KodKraju><AdresL1>ul. Inna 2</AdresL1>"
            + "</Adres></Podmiot2><Fa><KodWaluty>PLN</KodWaluty><P_1>2026-09-14</P_1>"
            + "<P_2>FV/2026/0005</P_2><P_13_1>10.00</P_13_1><P_14_1>2.30</P_14_1>"
            + "<P_15>12.30</P_15><RodzajFaktury>VAT</RodzajFaktury>"
            + "<FaWiersz><NrWierszaFa>1</NrWierszaFa><P_7>Coś</P_7><P_8A>szt</P_8A>"
            + "<P_8B>1</P_8B><P_9A>10.00</P_9A><P_11>10.00</P_11><P_12>23</P_12></FaWiersz>"
            + "</Fa></Faktura>";
    Invoice back = readBack(xml).invoice();
    assertNull(back.seller().address().postcode());
    assertEquals("Warszawa Śródmieście", back.seller().address().city());
    // A buyer with no identifier at all is still a buyer, with its name.
    assertNotNull(back.buyer());
    assertNull(back.buyer().vatId());
    assertEquals("Klient", back.buyer().name());
  }

  @Test
  @DisplayName("A document with no Fa element is refused rather than read as empty")
  void noInvoiceInside() {
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Faktura xmlns=\""
            + Fa3.NAMESPACE
            + "\"><Naglowek/></Faktura>";
    EInvoiceFormatException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            EInvoiceFormatException.class, () -> readBack(xml));
    assertTrue(e.getMessage().contains("no Fa element"), e.getMessage());
  }
}
