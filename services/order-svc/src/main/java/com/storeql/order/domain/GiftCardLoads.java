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

  /** The most whole digits a card amount may have: more than any money column holds is a typo. */
  static final int MOST_WHOLE_DIGITS = 18;

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
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal a : amounts) {
      sum = sum.add(amount(a, currency));
    }
    return sum;
  }

  /**
   * One gift-card amount — loaded on a sale, issued, reloaded or charged by hand — checked and kept
   * at the currency's own minor units (ISO 4217, as {@code Fx.minorUnits} reads them): stored value
   * finer than the currency could never be paid out.
   *
   * @param amount the amount as the caller sent it
   * @param currency the card's or order's ISO 4217 currency
   * @return the amount at the currency's scale: {@code 10.00} for pounds, {@code 500} for yen
   * @throws ApiException 400 {@code GIFT_CARD_AMOUNT_INVALID} for an amount that is missing, not
   *     positive, has more decimals than the currency has, or more than eighteen whole digits
   */
  public static BigDecimal amount(BigDecimal amount, String currency) {
    int digits = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
    // More whole digits than any money column holds is no amount, refused before it is scaled:
    // scaling 1E+80000000 to pence builds an eighty-million-digit number first.
    if (amount == null
        || amount.signum() <= 0
        || (long) amount.precision() - amount.scale() > MOST_WHOLE_DIGITS
        || amount.stripTrailingZeros().scale() > digits) {
      throw ApiException.badRequest(
          "GIFT_CARD_AMOUNT_INVALID",
          "a gift card amount is positive and has no more decimals than " + currency + " does");
    }
    return amount.setScale(digits, java.math.RoundingMode.UNNECESSARY);
  }
}
