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

  private static String code(BigDecimal amount, String currency) {
    var list = Collections.singletonList(amount);
    return assertThrows(ApiException.class, () -> GiftCardLoads.total(list, currency)).code();
  }
}
