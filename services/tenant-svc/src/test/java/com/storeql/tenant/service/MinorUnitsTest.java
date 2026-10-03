package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Money the platform bills in is held to its currency's own minor units (ISO 4217, through {@code
 * Fx.minorUnits}): whole yen, two places of a euro, a dinar's three — never an assumed two. A
 * figure finer than its currency is refused where somebody typed it, and rounded once where the
 * platform worked it out.
 */
class MinorUnitsTest {

  @Test
  @DisplayName("An invoice's amounts are rounded to its currency: whole yen, a dinar's third")
  void anInvoiceRoundsToItsCurrency() {
    assertEquals(new BigDecimal("1235"), BillingService.money(new BigDecimal("1234.5"), "JPY"));
    assertEquals(new BigDecimal("12.35"), BillingService.money(new BigDecimal("12.345"), "EUR"));
    assertEquals(new BigDecimal("12.346"), BillingService.money(new BigDecimal("12.3455"), "KWD"));
  }

  @Test
  @DisplayName("A payment finer than the invoice's currency is refused, never rounded")
  void aPaymentFinerThanItsCurrencyIsRefused() {
    for (String[] bad :
        List.of(
            new String[] {"100.50", "JPY"},
            new String[] {"10.001", "EUR"},
            new String[] {"1.0005", "KWD"},
            new String[] {"0", "EUR"},
            new String[] {"-1", "EUR"})) {
      ApiException refused =
          assertThrows(
              ApiException.class,
              () -> BillingService.paymentAmount(new BigDecimal(bad[0]), bad[1]));
      assertEquals(400, refused.status(), bad[0] + " " + bad[1]);
      assertEquals("BILLING_AMOUNT_INVALID", refused.code());
    }
    assertEquals(
        new BigDecimal("100"), BillingService.paymentAmount(new BigDecimal("100.00"), "JPY"));
    assertEquals(
        new BigDecimal("0.125"), BillingService.paymentAmount(new BigDecimal("0.125"), "KWD"));
    assertEquals(
        new BigDecimal("10.00"), BillingService.paymentAmount(new BigDecimal("10"), "EUR"));
  }

  @Test
  @DisplayName("A plan's price is an amount of its currency: 1000.5 yen is no price")
  void aPlanPriceIsAnAmountOfItsCurrency() {
    for (String[] bad :
        List.of(
            new String[] {"1000.5", "JPY"},
            new String[] {"9.999", "GBP"},
            new String[] {"1.2345", "KWD"},
            new String[] {"-1", "GBP"})) {
      ApiException refused =
          assertThrows(ApiException.class, () -> PlanService.price(new BigDecimal(bad[0]), bad[1]));
      assertEquals(400, refused.status());
      assertEquals("PLAN_PRICE_INVALID", refused.code(), bad[0] + " " + bad[1]);
    }
    assertEquals(new BigDecimal("1000.00"), PlanService.price(new BigDecimal("1000.00"), "JPY"));
    assertEquals(new BigDecimal("1.234"), PlanService.price(new BigDecimal("1.234"), "KWD"));
    assertEquals(new BigDecimal("0"), PlanService.price(BigDecimal.ZERO, "GBP"));
  }
}
