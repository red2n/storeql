package com.storeql.tenant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What a mid-period plan change costs (21.9).
 *
 * <p>A proration is <b>two amounts, not one</b>: a credit for the time the business paid for and
 * will not use at the old price, and a debit for the same time at the new one. An invoice that
 * shows only the difference cannot be checked by the person paying it — they would have to know
 * both prices and the day of the change to reproduce a single number. So both are returned, and
 * both are written as their own line.
 *
 * <p><b>By the day.</b> Providers prorate to the second; a business checks an invoice against a
 * calendar. "11 days of 30 at £20" is arithmetic somebody can repeat and argue with; a fraction of
 * a month to six places is not.
 *
 * <p><b>The credit is of what was billed, never of the list price.</b> The caller passes the amount
 * on the subscription, which is what the business was actually charged. Reading it back off the
 * plan would credit at today's price — and a business that never paid the higher rate would be
 * credited for it, which is the classic proration bug.
 *
 * <p>A pure function over dates and amounts, for the reason SJ-D20 gave: every case below is a test
 * that needs no database, no clock and no tenant.
 */
public final class Proration {

  private Proration() {}

  /**
   * What a change part-way through a period is worth.
   *
   * @param daysInPeriod the whole period, in days
   * @param daysRemaining the days from the change to the end of the period; nought when the change
   *     lands on or after the end
   * @param credit what the business paid for and will not use, at the price it was billed
   * @param debit the same days at the new price
   */
  public record Prorated(BigDecimal credit, BigDecimal debit, int daysInPeriod, int daysRemaining) {

    /** What the change adds to the invoice: positive on an upgrade, negative on a downgrade. */
    public BigDecimal net() {
      return debit.subtract(credit);
    }

    /**
     * Whether there is anything to write. A change on the last day of a period is worth nothing.
     */
    public boolean any() {
      return credit.signum() != 0 || debit.signum() != 0;
    }
  }

  /**
   * Prorates a change from one price to another, part-way through a period.
   *
   * @param billed what the business is being charged for this period — the amount on its
   *     subscription, not the plan's current price
   * @param replacement what it will be charged at from the change
   * @param periodStart the day the period began
   * @param periodEnd the day the next period begins; the period does not include it
   * @param changeOn the day the change takes effect
   * @param minorUnits the minor units of the subscription's currency, as {@code Fx.minorUnits}
   *     gives them (ISO 4217: none for yen, three for a dinar), never an assumed two
   * @return the credit and the debit, each rounded to the currency's smallest unit
   * @throws IllegalArgumentException when the period does not end after it begins
   */
  public static Prorated onChange(
      BigDecimal billed,
      BigDecimal replacement,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate changeOn,
      int minorUnits) {

    if (!periodEnd.isAfter(periodStart)) {
      throw new IllegalArgumentException("a billing period ends after it begins");
    }
    int daysInPeriod = (int) ChronoUnit.DAYS.between(periodStart, periodEnd);

    // A change before the period began is the whole period; one on or after its end is nothing.
    LocalDate from = changeOn.isBefore(periodStart) ? periodStart : changeOn;
    int daysRemaining =
        from.isAfter(periodEnd) ? 0 : (int) ChronoUnit.DAYS.between(from, periodEnd);

    return new Prorated(
        share(billed, daysRemaining, daysInPeriod, minorUnits),
        share(replacement, daysRemaining, daysInPeriod, minorUnits),
        daysInPeriod,
        daysRemaining);
  }

  /**
   * The part of a price that belongs to some days of a period.
   *
   * @return nought for a null or negative price, so a bad figure is never a credit
   */
  static BigDecimal share(BigDecimal price, int days, int daysInPeriod, int minorUnits) {
    if (price == null || price.signum() <= 0 || days <= 0) {
      return BigDecimal.ZERO.setScale(minorUnits);
    }
    if (days >= daysInPeriod) return price.setScale(minorUnits, RoundingMode.HALF_UP);
    return price
        .multiply(BigDecimal.valueOf(days))
        .divide(BigDecimal.valueOf(daysInPeriod), minorUnits, RoundingMode.HALF_UP);
  }
}
