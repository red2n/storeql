package com.storeql.payment.service;

import com.storeql.service.Fx;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Money at its own currency's minor units — ISO 4217's, through common-service {@link
 * Fx#minorUnits} — never an assumed two places. A yen has none, a pound two, a Kuwaiti dinar three.
 *
 * <p>Money a person types is checked, never rounded: an amount finer than its currency's smallest
 * unit is not one somebody can pay, and rounding somebody's money is not this service's decision.
 * The one figure that is rounded is a till's tender ({@link #tendered}), which the till works out
 * in binary floating point rather than types; and a figure said back in words, such as a refusal's
 * message.
 */
public final class Amounts {

  /** The most whole digits an amount may have before it is a typo, not money. */
  private static final int MOST_WHOLE_DIGITS = 14;

  /** The places the money columns hold: four, enough for any ISO 4217 currency's minor units. */
  static final int COLUMN_PLACES = 4;

  /**
   * An unscaled value longer than this has more than 77 digits: more than any amount written with
   * its trailing zeros could need (fourteen whole digits, four places and sixty zeros besides), so
   * it is refused on its length, read from its bit count, before a digit is counted or stripped.
   */
  private static final int MOST_UNSCALED_BITS = 256;

  private Amounts() {}

  /**
   * Whether an amount is written no finer than its currency's minor unit. Trailing zeros do not
   * count, so {@code 1250.00} yen fits and {@code 1.1250} dinars fit.
   *
   * <p>Judged at a cost that does not grow with the number: its length is read from the unscaled
   * value's bit count first, and every sum of a precision and a scale is a {@code long}. In an
   * {@code int}, {@code 1E+2147483647} (one digit, a scale of -2147483647) has 1 + 2147483647 whole
   * digits, which wraps to a negative and passed as none at all; {@link #exact} would then build a
   * number two thousand million digits long.
   */
  public static boolean fits(BigDecimal amount, String currency) {
    if (amount == null) return false;
    if (amount.unscaledValue().bitLength() > MOST_UNSCALED_BITS) return false;
    // Whole digits as written; stripping trailing zeros takes as many from the precision as from
    // the scale, so this is the stripped figure's too (a zero aside, which strips to one digit).
    if ((long) amount.precision() - amount.scale() > MOST_WHOLE_DIGITS) return false;
    BigDecimal plain;
    try {
      plain = amount.stripTrailingZeros();
    } catch (ArithmeticException e) {
      // A scale that would pass Integer.MIN_VALUE as the zeros come off: not money.
      return false;
    }
    long scale = plain.scale();
    return scale <= Fx.minorUnits(currency) && plain.precision() - scale <= MOST_WHOLE_DIGITS;
  }

  /**
   * The amount at exactly its currency's minor units, which is how it is stored and sent.
   *
   * @throws ArithmeticException for an amount that does not {@link #fits fit}: a caller checks
   *     first
   */
  public static BigDecimal exact(BigDecimal amount, String currency) {
    return amount.setScale(Fx.minorUnits(currency), RoundingMode.UNNECESSARY);
  }

  /**
   * An amount as it is shown: at its currency's minor units, or as it is held when that is finer,
   * so showing it never rounds. {@code 12.5000} pounds is {@code 12.50}, {@code 1250.0000} yen is
   * {@code 1250}. Null in, null out.
   */
  public static BigDecimal shown(BigDecimal amount, String currency) {
    if (amount == null) return null;
    int units = Fx.minorUnits(currency);
    return amount.stripTrailingZeros().scale() <= units
        ? amount.setScale(units, RoundingMode.UNNECESSARY)
        : amount.stripTrailingZeros();
  }

  /**
   * Refuses money finer than its currency's minor unit: half a yen, a fourth place of a dinar, a
   * third of a pound. Nothing is checked when the currency is not known (null), which is the
   * caller's fail-open choice; the four-place columns still hold the amount exactly.
   *
   * @param code the refusal's code, the one the route already uses for a bad amount
   * @throws ApiException 400 {@code code}
   */
  public static void requireFits(BigDecimal amount, String currency, String code) {
    if (currency != null && !fits(amount, currency)) {
      throw ApiException.badRequest(
          code,
          "An amount has no more decimal places than "
              + currency
              + " has ("
              + Fx.minorUnits(currency)
              + ")");
    }
  }

  /**
   * The currency money a person typed is counted in: the one the request names, else the business's
   * own. Null only when none is named and the business's cannot be read right now; the caller then
   * holds the amount to the columns' four places rather than stop a till over a briefly unreachable
   * tenant-svc.
   *
   * @throws ApiException 400 {@code CURRENCY_INVALID} for a named code that is not ISO 4217
   */
  public static String currencyOrNull(TenantProfiles profiles, UUID tenantId, String named) {
    if (named != null && !named.isBlank()) return profiles.currencyOr(tenantId, named);
    try {
      return profiles.find(tenantId).map(TenantProfiles.Profile::currency).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * A tender as a till sends it, at its currency's own minor units. The till adds a sale up in
   * binary floating point and posts the double it has: three items at 1.10 are {@code
   * 3.3000000000000003}, the back office's "collect outstanding" of 25.99 less 10.00 is {@code
   * 15.989999999999998}, and a weighed 0.375 kg at 12.99 is {@code 4.87125}, a line the till never
   * rounds. What the cashier was shown, and what order-svc totals each line to, is that figure
   * rounded half up at the currency's units ({@code 3.30}, {@code 15.99}, {@code 4.87}); half up
   * because order-svc rounds a sale's lines so, which keeps a tender that covers the total covering
   * it. With no currency to go by (the business's cannot be read right now), the columns' four
   * places, which is what the database would keep anyway. Null in, null out.
   *
   * <p>An amount below a tenth of the currency's smallest unit is nothing, said at once: rounding
   * it the long way builds a power of ten as long as its exponent, and {@code 1E-80000000} is
   * twelve characters on the wire (the body's bound on places refuses it first; this keeps the
   * rounding's cost to the digits actually written, whoever calls it).
   */
  public static BigDecimal tendered(BigDecimal amount, String currency) {
    if (amount == null) return null;
    int units = currency == null ? COLUMN_PLACES : Fx.minorUnits(currency);
    // |amount| < 10^(precision - scale), so at -units - 1 or below it is under a tenth of the
    // smallest unit, and half up makes it zero. Otherwise its scale is at most precision + units.
    if ((long) amount.precision() - amount.scale() < -units) {
      return BigDecimal.ZERO.setScale(units);
    }
    return amount.setScale(units, RoundingMode.HALF_UP);
  }

  /** Rounded half up to the currency's minor units, for a figure said back in a message. */
  public static BigDecimal rounded(BigDecimal amount, String currency) {
    return amount.setScale(Fx.minorUnits(currency), RoundingMode.HALF_UP);
  }
}
