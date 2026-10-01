package com.storeql.order.domain;

import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.List;

/**
 * The gift cards sold on one order (till-sessions slice 8): what they add to the order's total, and
 * which of them are acceptable. Pure: no store, no clock.
 */
public final class GiftCardLoads {

  /** The most cards one sale may carry. */
  public static final int MOST_PER_ORDER = 20;

  private GiftCardLoads() {}

  /**
   * The value the lines add to the order's total, after checking each one.
   *
   * @param amounts the amount of each gift-card line, possibly none
   * @param currency the order's ISO 4217 currency, whose minor unit an amount must respect
   * @return the sum, zero for no lines
   * @throws ApiException 400 {@code GIFT_CARD_AMOUNT_INVALID} for an amount that is not positive or
   *     has more decimals than the currency has, or {@code GIFT_CARD_TOO_MANY} past the limit
   */
  public static BigDecimal total(List<BigDecimal> amounts, String currency) {
    if (amounts.size() > MOST_PER_ORDER) {
      throw ApiException.badRequest(
          "GIFT_CARD_TOO_MANY", "a sale carries at most " + MOST_PER_ORDER + " gift cards");
    }
    int digits = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal a : amounts) {
      if (a == null || a.signum() <= 0 || a.stripTrailingZeros().scale() > digits) {
        throw ApiException.badRequest(
            "GIFT_CARD_AMOUNT_INVALID",
            "a gift card amount is positive and has no more decimals than " + currency + " does");
      }
      sum = sum.add(a);
    }
    return sum;
  }
}
