package com.storeql.order.domain;

import com.storeql.web.ApiException;
import com.storeql.web.ErrorCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * How finely a quantity is counted: to three decimal places, a gram of a kilogram, a millilitre of
 * a litre — the scale of every quantity column here ({@code NUMERIC(18,3)}: order, return, layaway,
 * special-order and parked-sale lines) and of stock in inventory-svc.
 *
 * <p>Postgres rounds a value to a column's scale on write without complaint, so a line asked for
 * 0.3755 kg was charged on 0.3755 and kept, held and deducted as 0.376: the receipt, the stock and
 * the money disagreed about one line. A quantity somebody types or chooses is therefore <b>refused
 * when finer than three places, never rounded</b> ({@link #typed}).
 *
 * <p>A till's line is the exception ({@link #fromTill}), for two things a till sends that nobody
 * typed. The till adds two weighings of one product in binary floating point and posts the double:
 * 0.1 kg and 0.2 kg are {@code 0.30000000000000004}. And a pack's label carries its net weight to
 * as many as five places (GS1 AI 3105: 0.37512 kg), which the till sells the line at as read.
 * Refusing either would refuse the sale and its offline replay on every attempt. So the double's
 * noise is taken off first — a figure within a billionth of a reading of at most {@value
 * #READING_PLACES} places is that reading — and the reading is then counted at the gram
 * <b>below</b> it: the customer is never charged for weight the label does not show, and the till's
 * own total, worked on the finer reading, always covers what this service charges. Anything else
 * finer is refused at the till too, as is a reading that comes to nothing at the gram.
 */
public final class Quantities {

  private Quantities() {}

  /** Decimal places a quantity is counted to, as every quantity column keeps it. */
  public static final int SCALE = 3;

  /** Whole digits a quantity may have: what {@code NUMERIC(18,3)} holds. */
  public static final int WHOLE_DIGITS = 15;

  /**
   * The most decimal places a till's figure can arrive with: a double is written with at most
   * seventeen significant digits, so twenty places hold every quantity a till works out (as
   * payment-svc bounds a till's tender), while {@code 1E-80000000} is refused before anything
   * scales it.
   */
  public static final int TILL_PLACES = 20;

  /**
   * The most places a reading a till sells at can have: a label's net weight has five at most (GS1
   * AI 310n, n = 0..5, as product-svc decodes it), and one more is left for an instrument read
   * finer.
   */
  public static final int READING_PLACES = 6;

  /** How far a till's double may sit from the reading it stands for: past the ninth place. */
  private static final BigDecimal TILL_NOISE = new BigDecimal("1E-9");

  /**
   * A quantity somebody typed or chose, which is never rounded.
   *
   * @param field the request field, named in the refusal
   * @return the quantity as given; null in, null out
   * @throws ApiException 400 {@code VALIDATION_FAILED} for more than {@value #SCALE} decimal places
   *     or {@value #WHOLE_DIGITS} whole digits
   */
  public static BigDecimal typed(BigDecimal qty, String field) {
    if (qty == null) return null;
    requireWithinColumn(qty, field);
    if (qty.stripTrailingZeros().scale() > SCALE) throw tooFine(qty, field);
    return qty;
  }

  /**
   * A quantity a till sends: as given at three places or fewer; else the reading it stands for — a
   * double's noise taken off, at most {@value #READING_PLACES} places — counted at the gram below.
   *
   * @param field the request field, named in the refusal
   * @return the quantity as given when it has at most three places, else the reading's three-place
   *     quantity, rounded down: 0.37512 and 0.3755 are 0.375, {@code 0.30000000000000004} is 0.300
   *     and {@code 0.5749999999999998} (0.375 + 0.2 as a double adds them) is 0.575; null in, null
   *     out
   * @throws ApiException 400 {@code VALIDATION_FAILED} for a quantity finer than a reading by more
   *     than a double's noise, one under the gram, or one with more than {@value #WHOLE_DIGITS}
   *     whole digits or {@value #TILL_PLACES} places
   */
  public static BigDecimal fromTill(BigDecimal qty, String field) {
    if (qty == null) return null;
    requireWithinColumn(qty, field);
    int places = qty.stripTrailingZeros().scale();
    if (places <= SCALE) return qty;
    if (places > TILL_PLACES) throw finerThanAReading(qty, field);
    // The double's noise off first, half up to the reading it is within a billionth of, so 0.575
    // added as 0.5749999999999998 is read as 0.575 before the gram below is taken of it.
    BigDecimal reading = qty.setScale(READING_PLACES, RoundingMode.HALF_UP);
    if (qty.subtract(reading).abs().compareTo(TILL_NOISE) > 0) throw finerThanAReading(qty, field);
    BigDecimal counted = reading.setScale(SCALE, RoundingMode.DOWN);
    if (counted.signum() <= 0) {
      String why = field + " " + qty + " is less than the smallest quantity counted, 0.001";
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, why, List.of(field + ": " + why));
    }
    return counted;
  }

  private static void requireWithinColumn(BigDecimal qty, String field) {
    if ((long) qty.precision() - qty.scale() > WHOLE_DIGITS) {
      String why = field + " has more than " + WHOLE_DIGITS + " whole digits";
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, why, List.of(field + ": " + why));
    }
  }

  private static ApiException tooFine(BigDecimal qty, String field) {
    // toString, not toPlainString: 1E-80000000 written out in full is eighty million digits.
    String why =
        field
            + " "
            + qty
            + " is counted finer than "
            + SCALE
            + " decimal places; it is never rounded";
    return new ApiException(400, ErrorCodes.VALIDATION_FAILED, why, List.of(field + ": " + why));
  }

  private static ApiException finerThanAReading(BigDecimal qty, String field) {
    String why =
        field
            + " "
            + qty
            + " is finer than any reading a till sells at ("
            + READING_PLACES
            + " decimal places); it is never rounded";
    return new ApiException(400, ErrorCodes.VALIDATION_FAILED, why, List.of(field + ": " + why));
  }
}
