package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The receipt of a shelf-price sale: lines at the shelf price, a VAT table that adds up. */
class ReceiptDocumentTest {

  private static final UUID BREAD = Ids.newId();
  private static final UUID JAM = Ids.newId();
  private static final UUID ORDER = Ids.parse("01a0b2c4-9999-7000-8000-0123456789ab");
  private static final BigDecimal T1 = new BigDecimal("0.20");
  private static final BigDecimal T5 = new BigDecimal("0.05");

  private static Order order(boolean inclusive, String total, String discount) {
    return new Order(
        ORDER,
        Ids.newId(),
        Ids.newId(),
        null,
        null,
        "POS",
        "INSTORE",
        "FULFILLED",
        new BigDecimal("2.97"),
        new BigDecimal("0.31"),
        new BigDecimal(discount),
        new BigDecimal(total),
        "GBP",
        null,
        null,
        Instant.parse("2026-10-09T23:30:00Z"),
        Instant.parse("2026-10-09T23:30:00Z"),
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "CARD",
        BigDecimal.ZERO,
        null,
        true,
        null,
        null,
        null,
        null,
        null,
        inclusive);
  }

  private static OrderItem item(
      UUID variant,
      String qty,
      String shelf,
      String paid,
      String vat,
      String net,
      String code,
      BigDecimal rate) {
    return new OrderItem(
        Ids.newId(),
        Ids.newId(),
        ORDER,
        variant,
        new BigDecimal(qty),
        new BigDecimal(net),
        new BigDecimal(net),
        null,
        null,
        BigDecimal.ZERO,
        new BigDecimal(vat),
        null,
        code,
        rate,
        BigDecimal.ZERO,
        null,
        new BigDecimal(paid),
        new BigDecimal(shelf));
  }

  @Test
  @DisplayName("lines print at the shelf price and the VAT table's gross adds up to the total")
  void theVatTableAddsUp() {
    ReceiptDocument.Doc d =
        ReceiptDocument.build(
            order(true, "3.28", "0.00"),
            List.of(
                item(BREAD, "1", "1.29", "1.29", "0.22", "1.07", "T1", T1),
                item(JAM, "1", "1.99", "1.99", "0.09", "1.90", "T5", T5)),
            BigDecimal.ZERO,
            Map.of(BREAD, "Bread", JAM, "Jam"),
            new ReceiptDocument.Seller("Acme Foods Ltd", "Acme", "GB123456789"),
            ZoneId.of("Europe/London"),
            List.of(new ReceiptDocument.Tender("CARD", new BigDecimal("3.28"))));

    assertEquals("Bread", d.lines().get(0).name());
    assertEquals(new BigDecimal("1.29"), d.lines().get(0).listUnitPrice());
    assertEquals(new BigDecimal("1.29"), d.lines().get(0).lineGross());
    assertEquals(2, d.vat().size());
    BigDecimal gross =
        d.vat().stream()
            .map(ReceiptDocument.VatRow::gross)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, gross.compareTo(d.total()));
    assertEquals(new BigDecimal("0.31"), d.vatTotal());
    assertEquals("GB123456789", d.seller().vatNumber());
    assertEquals("456789ab", d.reference());
  }

  @Test
  @DisplayName("it prints the date and time in the store's own zone, not UTC")
  void theStoresOwnClock() {
    // 23:30 UTC on 9 October is half past midnight on the 10th in London (BST).
    ReceiptDocument.Doc d =
        ReceiptDocument.build(
            order(true, "1.29", "0.00"),
            List.of(item(BREAD, "1", "1.29", "1.29", "0.22", "1.07", "T1", T1)),
            BigDecimal.ZERO,
            Map.of(),
            new ReceiptDocument.Seller(null, null, null),
            ZoneId.of("Europe/London"),
            List.of());

    assertEquals("2026-10-10", d.localDate());
    assertEquals("00:30", d.localTime());
    assertEquals("Europe/London", d.timeZone());
    assertNull(d.lines().get(0).name());
  }

  @Test
  @DisplayName("what an offer or a discount saved is the shelf price less what was paid")
  void whatWasSaved() {
    ReceiptDocument.Doc d =
        ReceiptDocument.build(
            order(true, "1.16", "0.00"),
            List.of(item(BREAD, "1", "1.29", "1.16", "0.19", "0.97", "T1", T1)),
            BigDecimal.ZERO,
            Map.of(),
            new ReceiptDocument.Seller(null, null, null),
            ZoneId.of("UTC"),
            List.of());

    assertEquals(new BigDecimal("0.13"), d.lines().get(0).saved());
  }

  @Test
  @DisplayName("a line closed short does not print; one part-closed prints what stands")
  void closedLines() {
    OrderItem shortAll =
        new OrderItem(
            Ids.newId(),
            Ids.newId(),
            ORDER,
            JAM,
            new BigDecimal("2"),
            new BigDecimal("0"),
            new BigDecimal("0"),
            null,
            null,
            BigDecimal.ZERO,
            new BigDecimal("0"),
            null,
            "T5",
            T5,
            new BigDecimal("2"),
            null,
            new BigDecimal("0"),
            new BigDecimal("1.99"));

    ReceiptDocument.Doc d =
        ReceiptDocument.build(
            order(true, "1.29", "0.00"),
            List.of(item(BREAD, "1", "1.29", "1.29", "0.22", "1.07", "T1", T1), shortAll),
            BigDecimal.ZERO,
            Map.of(),
            new ReceiptDocument.Seller(null, null, null),
            ZoneId.of("UTC"),
            List.of());

    assertEquals(1, d.lines().size());
    assertTrue(d.vat().size() == 1);
  }

  @Test
  @DisplayName("an order sold net has no such receipt")
  void aNetOrderIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReceiptDocument.build(
                order(false, "3.28", "0.00"),
                List.of(),
                BigDecimal.ZERO,
                Map.of(),
                new ReceiptDocument.Seller(null, null, null),
                ZoneId.of("UTC"),
                List.of()));
  }

  @Test
  @DisplayName("the emailed copy is drawn from the same document: seller, lines, VAT table, tender")
  void theTextCopy() {
    ReceiptDocument.Doc d =
        ReceiptDocument.build(
            order(true, "3.28", "0.00"),
            List.of(
                item(BREAD, "1", "1.29", "1.29", "0.22", "1.07", "T1", T1),
                item(JAM, "1", "1.99", "1.99", "0.09", "1.90", "T5", T5)),
            BigDecimal.ZERO,
            Map.of(BREAD, "Bread", JAM, "Jam"),
            new ReceiptDocument.Seller("Acme Foods Ltd", "Acme", "GB123456789"),
            ZoneId.of("Europe/London"),
            List.of(new ReceiptDocument.Tender("CARD", new BigDecimal("3.28"))));

    String text = ReceiptDocument.toText(d);

    assertTrue(text.startsWith("Acme Foods Ltd\n"));
    assertTrue(text.contains("VAT No. GB123456789"));
    assertTrue(text.contains("Bread"));
    assertTrue(text.contains("Total   3.28 GBP"));
    assertTrue(text.contains("Prices include VAT."));
    assertTrue(text.contains("T1      20%"));
    assertTrue(text.contains("T5      5%"));
    assertTrue(text.contains("Paid by CARD   3.28"));
  }
}
