package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The money of a shelf-price order: the identities that hold on every order, tried widely. */
class InclusiveOrderTest {

  private static final BigDecimal T1 = new BigDecimal("0.20");
  private static final BigDecimal T5 = new BigDecimal("0.05");

  private static InclusiveOrder.LineIn line(String gross, BigDecimal rate) {
    return new InclusiveOrder.LineIn(new BigDecimal(gross), rate);
  }

  @Test
  @DisplayName("without a staff discount a line is its quoted gross, with the VAT inside it")
  void aLineIsItsQuotedGross() {
    InclusiveOrder.Priced p =
        InclusiveOrder.price(
            List.of(line("1.29", T1), line("1.99", T5), line("2.49", BigDecimal.ZERO)),
            BigDecimal.ZERO,
            2);

    assertEquals(new BigDecimal("1.29"), p.lines().get(0).paidGross());
    assertEquals(new BigDecimal("0.22"), p.lines().get(0).vat());
    assertEquals(new BigDecimal("1.07"), p.lines().get(0).net());
    assertEquals(new BigDecimal("0.09"), p.lines().get(1).vat());
    assertEquals(new BigDecimal("0.00"), p.lines().get(2).vat());
    assertEquals(new BigDecimal("5.77"), p.paidGross());
    assertEquals(new BigDecimal("0.31"), p.tax());
    assertEquals(new BigDecimal("5.46"), p.subtotal());
  }

  @Test
  @DisplayName(
      "a staff discount is shared by gross and lowers the VAT; the lines add up to the total")
  void aStaffDiscountLowersTheVat() {
    InclusiveOrder.Priced p =
        InclusiveOrder.price(
            List.of(line("12.00", T1), line("6.00", T5)), new BigDecimal("3.00"), 2);

    assertEquals(new BigDecimal("15.00"), p.paidGross());
    // two thirds and one third of the three pounds
    assertEquals(new BigDecimal("10.00"), p.lines().get(0).paidGross());
    assertEquals(new BigDecimal("5.00"), p.lines().get(1).paidGross());
    assertEquals(new BigDecimal("1.67"), p.lines().get(0).vat());
    assertEquals(new BigDecimal("0.24"), p.lines().get(1).vat());
    assertEquals(0, p.subtotal().add(p.tax()).compareTo(p.paidGross()));
  }

  @Test
  @DisplayName(
      "every discount from nothing to the whole basket keeps net + VAT = paid, to the penny")
  void everyDiscountKeepsTheIdentities() {
    List<InclusiveOrder.LineIn> lines =
        List.of(line("1.29", T1), line("1.99", T5), line("0.99", T1), line("12.35", T1));
    BigDecimal gross = new BigDecimal("16.62");
    for (int pence = 0; pence <= 1662; pence++) {
      BigDecimal discount = BigDecimal.valueOf(pence, 2);
      InclusiveOrder.Priced p = InclusiveOrder.price(lines, discount, 2);
      assertEquals(0, gross.subtract(discount).compareTo(p.paidGross()), "discount " + discount);
      assertEquals(0, p.subtotal().add(p.tax()).compareTo(p.paidGross()), "discount " + discount);
      for (InclusiveOrder.LineOut l : p.lines()) {
        assertEquals(0, l.net().add(l.vat()).compareTo(l.paidGross()));
        assertTrue(l.paidGross().signum() >= 0 && l.vat().signum() >= 0 && l.net().signum() >= 0);
      }
    }
  }

  @Test
  @DisplayName("whole-yen and three-decimal currencies keep their own minor units")
  void otherCurrencies() {
    InclusiveOrder.Priced yen =
        InclusiveOrder.price(List.of(line("129", new BigDecimal("0.10"))), new BigDecimal("9"), 0);
    assertEquals(new BigDecimal("120"), yen.paidGross());
    assertEquals(new BigDecimal("11"), yen.tax());
    assertEquals(new BigDecimal("109"), yen.subtotal());

    InclusiveOrder.Priced dinar =
        InclusiveOrder.price(List.of(line("1.295", T5)), new BigDecimal("0.095"), 3);
    assertEquals(new BigDecimal("1.200"), dinar.paidGross());
    assertEquals(new BigDecimal("0.057"), dinar.tax());
  }

  @Test
  @DisplayName("a discount larger than the order is refused")
  void aDiscountLargerThanTheOrderIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> InclusiveOrder.price(List.of(line("1.00", T1)), new BigDecimal("1.01"), 2));
  }
}
