package com.storeql.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The reduced-price sticker's barcode (05.4): an EAN-13 in the in-store range, prefix {@code 21}, a
 * five-digit item code from the tenant's series, the reduced price in the currency's own minor
 * units to five digits (pence, whole yen, fils — as GS1 price-embedded store codes carry a price),
 * and the EAN check digit — the same variable-measure shape a labelling scale prints, with a price
 * instead of a weight, so a till that reads scale labels reads these.
 *
 * <p>Pure functions; the item code comes from a counter row the repository moves under its lock.
 */
public final class MarkdownLabel {

  private MarkdownLabel() {}

  /** The in-store prefix reduced-price stickers carry. */
  public static final String PREFIX = "21";

  /** The largest number the five price digits hold, in minor units. */
  private static final BigDecimal MAX_MINOR = new BigDecimal("99999");

  /**
   * The most a five-digit minor-unit field can say: 999.99 in a two-decimal currency, ¥99,999 in a
   * currency without minor units, 99.999 in a three-decimal one.
   *
   * @param minorUnits the currency's minor units ({@code Fx.minorUnits})
   */
  public static BigDecimal maxPrice(int minorUnits) {
    return MAX_MINOR.movePointLeft(minorUnits);
  }

  /**
   * The thirteen digits for an item code and a price.
   *
   * @param itemNumber the tenant's series number; taken modulo 100000 to five digits
   * @param price the reduced price, in the currency's own minor units
   * @param minorUnits the currency's minor units ({@code Fx.minorUnits})
   * @return the sticker's code
   * @throws IllegalArgumentException when the price does not fit the label
   */
  public static String encode(long itemNumber, BigDecimal price, int minorUnits) {
    if (price.signum() < 0 || price.compareTo(maxPrice(minorUnits)) > 0) {
      throw new IllegalArgumentException(
          "a sticker price must be between 0 and " + maxPrice(minorUnits).toPlainString());
    }
    long minor =
        price
            .setScale(minorUnits, RoundingMode.HALF_UP)
            .movePointRight(minorUnits)
            .longValueExact();
    String twelve =
        PREFIX + String.format("%05d", itemNumber % 100000) + String.format("%05d", minor);
    return twelve + checkDigit(twelve);
  }

  /**
   * The price a sticker's code carries, or null when the code is not a reduced-price sticker.
   *
   * @param minorUnits the minor units of the currency the sticker was printed in
   */
  public static BigDecimal priceOf(String code, int minorUnits) {
    if (!isLabel(code)) {
      return null;
    }
    return new BigDecimal(code.substring(7, 12)).movePointLeft(minorUnits);
  }

  /** Whether a scanned code is a reduced-price sticker: thirteen digits, the prefix, the check. */
  public static boolean isLabel(String code) {
    return code != null
        && code.length() == 13
        && code.chars().allMatch(Character::isDigit)
        && code.startsWith(PREFIX)
        && checkDigit(code.substring(0, 12)) == code.charAt(12);
  }

  /** EAN-13: weights 1 and 3 from the left over twelve digits, the complement to ten. */
  public static char checkDigit(String twelve) {
    int sum = 0;
    for (int i = 0; i < 12; i++) {
      int d = twelve.charAt(i) - '0';
      sum += (i % 2 == 0) ? d : d * 3;
    }
    return (char) ('0' + (10 - (sum % 10)) % 10);
  }
}
