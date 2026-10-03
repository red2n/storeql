package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Currency-aware rounding for purchase-order money (golden rule #13: {@code BigDecimal} throughout,
 * never floating point).
 *
 * <p>The reason this exists rather than a bare {@code setScale(2)}: StoreQL is multi-currency, and
 * two decimal places is a property of sterling and the dollar, not of money. ISO 4217 gives JPY and
 * KRW <b>zero</b> minor units, so a Japanese supplier's order line of ¥1,234 rounded to two places
 * reads ¥1,234.00 — a figure that cannot be invoiced, paid or reconciled, and which quietly implies
 * a precision the currency does not have. {@link Currency#getDefaultFractionDigits()} is the JDK's
 * own copy of that table, so the scale comes from the currency rather than from an assumption.
 *
 * <p>The platform's reading of ISO 4217 is common-service's {@code Fx.minorUnits} (2 for a code the
 * JDK does not know or a pseudo-currency). This copy stays because {@code domain/} may not depend
 * on a {@code ..service..} package (ArchUnit {@code DOMAIN_IS_PURE}); {@code MoneyTest} pins the
 * two to the same answer for every currency, so they cannot diverge.
 */
public final class Money {

  private Money() {}

  /**
   * Used when a currency code is not in the JDK's ISO 4217 table. Two places is the commonest minor
   * unit and the least surprising fallback, but reaching it means the code was never validated —
   * {@link #requireIso4217} is how client input is kept out of here.
   */
  private static final int FALLBACK_SCALE = 2;

  /**
   * The number of minor units the currency actually has: 2 for GBP, USD, CNY and INR; 0 for JPY.
   *
   * @param currencyCode ISO 4217 alpha-3 code, case-insensitive
   * @return the currency's minor-unit count, or 2 for an unrecognised code
   */
  public static int scaleOf(String currencyCode) {
    if (currencyCode == null) return FALLBACK_SCALE;
    try {
      int digits =
          Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT))
              .getDefaultFractionDigits();
      // -1 marks a pseudo-currency (XXX "no currency", XAU gold). Not spendable, but not worth
      // failing a purchase order over either — round it like ordinary money.
      return digits < 0 ? FALLBACK_SCALE : digits;
    } catch (IllegalArgumentException e) {
      return FALLBACK_SCALE;
    }
  }

  /**
   * Rounds to the currency's own minor units, HALF_UP — the commercial convention, and the one
   * every UK and EU VAT authority specifies for invoice rounding.
   *
   * @param amount the unrounded amount
   * @param currencyCode ISO 4217 alpha-3 code
   * @return {@code amount} at the currency's scale; null in, null out
   */
  public static BigDecimal round(BigDecimal amount, String currencyCode) {
    return amount == null ? null : amount.setScale(scaleOf(currencyCode), RoundingMode.HALF_UP);
  }

  /**
   * Validates an amount a person keyed against its currency's own minor units: whole yen, cents of
   * a pound, thousandths of a dinar. An amount finer than that is not money in that currency — no
   * invoice, ledger or bank can carry it — so it is refused rather than silently rounded. A unit
   * price is not an amount and is never held to this: it may carry more precision.
   *
   * @param amount the amount as keyed; null passes (a missing amount is Bean Validation's)
   * @param currencyCode ISO 4217 alpha-3 code
   * @param field the request field, named in the refusal
   *     <p>The size is judged first, at constant cost and before anything is rescaled, stripped or
   *     written out: more than {@link #MOST_WHOLE_DIGITS} whole digits, or an unscaled value longer
   *     than {@link #MOST_UNSCALED_BITS} bits, is no amount at all. {@code 1E+80000000} is eleven
   *     characters on the wire and eighty million digits once at pence; the whole digits are
   *     counted in {@code long}, since in {@code int} {@code 1E+2147483647} wraps round and passes.
   *     Not every caller is a request body common-web has already walked: an e-invoice's figures
   *     arrive here from the document.
   * @return {@code amount} at exactly the currency's scale; null in, null out
   * @throws com.storeql.web.ApiException 400 {@code VALIDATION_FAILED} for a figure beyond any
   *     amount; 400 {@code PURCHASE_AMOUNT_TOO_PRECISE} when it has more decimals than the currency
   *     has minor units
   */
  public static BigDecimal requireMinorUnits(BigDecimal amount, String currencyCode, String field) {
    if (amount == null) return null;
    if (beyondAnyAmount(amount)) {
      String why =
          field
              + " is beyond any amount: more than "
              + MOST_WHOLE_DIGITS
              + " whole digits, or more digits than money is written with";
      throw new com.storeql.web.ApiException(
          400, com.storeql.web.ErrorCodes.VALIDATION_FAILED, why, java.util.List.of(why));
    }
    int scale = scaleOf(currencyCode);
    if (amount.stripTrailingZeros().scale() > scale) {
      throw com.storeql.web.ApiException.badRequest(
          "PURCHASE_AMOUNT_TOO_PRECISE",
          field
              + " "
              + shown(amount)
              + " is finer than "
              + currencyCode
              + " is counted in ("
              + scale
              + (scale == 1 ? " decimal place)" : " decimal places)"));
    }
    return amount.setScale(scale, RoundingMode.UNNECESSARY);
  }

  /**
   * The most whole digits an amount may have: more than any money this service holds (a quintillion
   * less one in any currency), and the bound order-svc's typed money keeps.
   */
  public static final int MOST_WHOLE_DIGITS = 18;

  /**
   * An unscaled value longer than this has more than 77 digits: more than any amount is written
   * with, refused on its length so its digits are never counted or stripped.
   */
  static final int MOST_UNSCALED_BITS = 256;

  /**
   * Whether a figure is beyond any amount, at constant cost: its unscaled length read from its bit
   * count, its whole digits as {@code precision - scale} in {@code long}.
   */
  static boolean beyondAnyAmount(BigDecimal amount) {
    return amount.unscaledValue().bitLength() > MOST_UNSCALED_BITS
        || (long) amount.precision() - amount.scale() > MOST_WHOLE_DIGITS;
  }

  /** As a person reads it, unless written out it would be longer than any amount is. */
  private static String shown(BigDecimal amount) {
    return amount.scale() > MOST_UNSCALED_BITS ? amount.toString() : amount.toPlainString();
  }

  /**
   * Whether the JDK recognises this as an ISO 4217 currency.
   *
   * @param currencyCode the code to test
   * @return {@code true} if {@link Currency#getInstance(String)} accepts it
   */
  public static boolean isIso4217(String currencyCode) {
    if (currencyCode == null || currencyCode.trim().length() != 3) return false;
    try {
      Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Normalises and validates a client-supplied currency code at the boundary (golden rule #15).
   *
   * @param currencyCode the code as the caller sent it
   * @return the upper-cased, trimmed code
   * @throws com.storeql.web.ApiException 400 {@code PURCHASE_INVALID_CURRENCY} if it is not ISO
   *     4217
   */
  public static String requireIso4217(String currencyCode) {
    if (!isIso4217(currencyCode)) {
      throw com.storeql.web.ApiException.badRequest(
          "PURCHASE_INVALID_CURRENCY",
          "currency must be an ISO 4217 alpha-3 code (e.g. GBP, USD, JPY, INR, CNY): "
              + currencyCode);
    }
    return currencyCode.trim().toUpperCase(Locale.ROOT);
  }
}
