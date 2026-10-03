package com.storeql.order.service;

import com.storeql.service.Fx;
import com.storeql.web.ApiException;
import com.storeql.web.ErrorCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Money a person typed — a till price, a staff discount, a layaway deposit — checked against the
 * business's currency and kept at its own minor units (ISO 4217 through {@link Fx#minorUnits}):
 * three places for a dinar, whole yen, pence. A figure finer than the currency could never be paid
 * or given back, so it is refused by name before anything is written, never rounded behind the
 * typist's back (the currency minor-units sweep, as payment-svc's till cash and pricing-svc's list
 * prices are).
 */
final class TypedMoney {

  /**
   * The most whole digits an amount may have before it is a typo or an attack, not money: more than
   * any money column holds. Checked before any scaling, whose cost grows with the exponent ({@code
   * 1E+80000000} is eleven characters on the wire and eighty million digits once at pence).
   */
  static final int MOST_WHOLE_DIGITS = 18;

  private TypedMoney() {}

  /** Whether the amount has more whole digits than {@link #MOST_WHOLE_DIGITS}. */
  static boolean tooLarge(BigDecimal amount) {
    return (long) amount.precision() - amount.scale() > MOST_WHOLE_DIGITS;
  }

  /**
   * The amount at the currency's scale.
   *
   * @param field the request field, named in the refusal
   * @return {@code amount} at the currency's minor units; null in, null out
   * @throws ApiException 400 {@code VALIDATION_FAILED} for an amount with more decimals than the
   *     currency has, or more than {@link #MOST_WHOLE_DIGITS} whole digits
   */
  static BigDecimal require(BigDecimal amount, String currency, String field) {
    return require(amount, currency, field, ErrorCodes.VALIDATION_FAILED);
  }

  /**
   * As {@link #require(BigDecimal, String, String)}, refused under a route's own code.
   *
   * @throws ApiException 400 {@code code} for an amount with more decimals than the currency has,
   *     or more than {@link #MOST_WHOLE_DIGITS} whole digits
   */
  static BigDecimal require(BigDecimal amount, String currency, String field, String code) {
    if (amount == null) return null;
    if (tooLarge(amount)) {
      String why = field + " has more than " + MOST_WHOLE_DIGITS + " whole digits";
      throw new ApiException(400, code, why, List.of(field + ": " + why));
    }
    int units = Fx.minorUnits(currency);
    if (amount.stripTrailingZeros().scale() > units) {
      // toString, not toPlainString: 1E-80000000 written out in full is eighty million digits.
      String why =
          field + " " + amount + " has more decimals than " + currency + " has (" + units + ")";
      throw new ApiException(400, code, why, List.of(field + ": " + why));
    }
    return amount.setScale(units, RoundingMode.UNNECESSARY);
  }
}
