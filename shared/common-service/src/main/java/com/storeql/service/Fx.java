package com.storeql.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;

/**
 * Foreign exchange arithmetic (03.x), pure. A business keeps one home currency and a table of
 * rates, each the number of home units one unit of the other currency buys — {@code 0.79} for USD
 * under a GBP home means a dollar is seventy-nine pence. Converting rounds to the target currency's
 * minor units, half up, so a yen figure is whole yen and a dinar keeps its third decimal. Nothing
 * here knows where a rate came from.
 */
public final class Fx {

  /** Rates carry at most this many decimals. */
  public static final int RATE_SCALE = 10;

  /**
   * The largest rate accepted: a unit of anything buying a trillion of the home currency is a typo.
   */
  public static final BigDecimal MAX_RATE = new BigDecimal("1000000000000");

  private static final int WORKING_SCALE = 12;

  private Fx() {}

  /** One currency's rate: home units per one unit of {@code currency}, from a day. */
  public record Rate(String currency, BigDecimal rate, LocalDate effectiveFrom) {}

  /** An amount after conversion, with the rate that did it. */
  public record Converted(BigDecimal amount, String currency, BigDecimal rate) {}

  /** The minor units a currency is counted in: 0 for JPY, 3 for KWD, 2 for anything unknown. */
  public static int minorUnits(String currency) {
    if (currency == null || currency.trim().length() != 3) return 2;
    try {
      int digits = Currency.getInstance(upper(currency)).getDefaultFractionDigits();
      return digits < 0 ? 2 : digits;
    } catch (IllegalArgumentException e) {
      return 2;
    }
  }

  /** Whether the code names a currency ISO 4217 knows. */
  public static boolean isCurrency(String code) {
    if (code == null || code.trim().length() != 3) return false;
    try {
      Currency.getInstance(upper(code));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** A foreign amount into the home currency: amount × rate, rounded to the home minor units. */
  public static BigDecimal toHome(BigDecimal amount, BigDecimal rate, String homeCurrency) {
    return amount.multiply(rate).setScale(minorUnits(homeCurrency), RoundingMode.HALF_UP);
  }

  /**
   * A home amount into the other currency: amount ÷ rate, rounded to that currency's minor units.
   */
  public static BigDecimal fromHome(BigDecimal homeAmount, BigDecimal rate, String currency) {
    return homeAmount
        .divide(rate, WORKING_SCALE, RoundingMode.HALF_UP)
        .setScale(minorUnits(currency), RoundingMode.HALF_UP);
  }

  /**
   * Why a rate cannot be kept, or null when it can: absent, zero or negative, more than ten
   * decimals, or absurdly large.
   */
  public static String validateRate(BigDecimal rate) {
    if (rate == null) return "rate is required";
    if (rate.signum() <= 0) return "rate must be above zero";
    if (rate.stripTrailingZeros().scale() > RATE_SCALE) {
      return "rate has at most " + RATE_SCALE + " decimal places";
    }
    if (rate.compareTo(MAX_RATE) > 0) return "rate is implausibly large";
    return null;
  }

  static String upper(String code) {
    return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
  }
}
