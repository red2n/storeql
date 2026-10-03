package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class GiftCardLoadsTest {

  @Test
  void noLinesAddNothing() {
    assertEquals(0, GiftCardLoads.total(List.of(), "USD").signum());
  }

  @Test
  void linesAddUp() {
    var sum = GiftCardLoads.total(List.of(new BigDecimal("25"), new BigDecimal("10.50")), "USD");
    assertEquals(0, new BigDecimal("35.50").compareTo(sum));
  }

  @Test
  void anAmountRespectsTheCurrencysMinorUnit() {
    // Two decimals in a currency with none, three in one with two: refused whatever the country.
    assertEquals("GIFT_CARD_AMOUNT_INVALID", code(new BigDecimal("10.5"), "JPY"));
    assertEquals("GIFT_CARD_AMOUNT_INVALID", code(new BigDecimal("10.005"), "EUR"));
    // A trailing zero is not a decimal.
    assertEquals(
        0, GiftCardLoads.total(List.of(new BigDecimal("10.00")), "JPY").compareTo(BigDecimal.TEN));
  }

  @Test
  void aZeroOrNegativeOrMissingAmountIsRefused() {
    assertEquals("GIFT_CARD_AMOUNT_INVALID", code(BigDecimal.ZERO, "USD"));
    assertEquals("GIFT_CARD_AMOUNT_INVALID", code(new BigDecimal("-5"), "USD"));
    assertEquals("GIFT_CARD_AMOUNT_INVALID", code(null, "USD"));
  }

  @Test
  void aSaleCarriesAtMostTwentyCards() {
    var many = Collections.nCopies(GiftCardLoads.MOST_PER_ORDER + 1, BigDecimal.TEN);
    var e = assertThrows(ApiException.class, () -> GiftCardLoads.total(many, "USD"));
    assertEquals("GIFT_CARD_TOO_MANY", e.code());
  }

  @Test
  void anAbsurdlyLargeAmountIsRefusedBeforeItIsScaled() {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () ->
            assertEquals(
                "GIFT_CARD_AMOUNT_INVALID",
                assertThrows(
                        ApiException.class,
                        () -> GiftCardLoads.amount(new BigDecimal("1E+80000000"), "GBP"))
                    .code()));
  }

  private static String code(BigDecimal amount, String currency) {
    var list = Collections.singletonList(amount);
    return assertThrows(ApiException.class, () -> GiftCardLoads.total(list, currency)).code();
  }

  /**
   * A card issued, reloaded or charged by hand is held to the same rule as one sold on a sale: no
   * finer than the currency, and kept at the currency's own scale — whole yen, fils for dinars.
   */
  @Test
  void anAmountByHandIsCheckedAndKeptInTheCurrencysOwnUnits() {
    assertEquals(new BigDecimal("1.235"), GiftCardLoads.amount(new BigDecimal("1.235"), "KWD"));
    assertEquals(new BigDecimal("1.230"), GiftCardLoads.amount(new BigDecimal("1.23"), "KWD"));
    assertEquals(new BigDecimal("500"), GiftCardLoads.amount(new BigDecimal("500.00"), "JPY"));
    assertEquals(new BigDecimal("10.00"), GiftCardLoads.amount(new BigDecimal("10"), "GBP"));
    assertEquals(
        "GIFT_CARD_AMOUNT_INVALID",
        assertThrows(
                ApiException.class, () -> GiftCardLoads.amount(new BigDecimal("1.2345"), "KWD"))
            .code());
    assertEquals(
        "GIFT_CARD_AMOUNT_INVALID",
        assertThrows(ApiException.class, () -> GiftCardLoads.amount(new BigDecimal("500.5"), "JPY"))
            .code());
    assertEquals(
        "GIFT_CARD_AMOUNT_INVALID",
        assertThrows(
                ApiException.class, () -> GiftCardLoads.amount(new BigDecimal("10.001"), "GBP"))
            .code());
  }
}
