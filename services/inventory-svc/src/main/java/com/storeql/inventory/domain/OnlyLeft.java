package com.storeql.inventory.domain;

import java.math.BigDecimal;

/**
 * "Only N left" on the storefront — pure. A business switches this on with one number, a whole-unit
 * threshold; below it, a shopper deciding whether to buy sees how few remain. Off (no threshold) is
 * the default, and every other gate below exists so the count never says more than it should: no
 * store named on the read has no one shelf to count, a supplier-fulfilled (dropship) line has
 * nothing on this shelf at all, and a weighed line's available quantity is a weight, not a count —
 * 2.5 kg left is not "2 left". Never above the threshold either: the point is to say "hurry", not
 * to publish a stock number.
 */
public final class OnlyLeft {

  private OnlyLeft() {}

  /**
   * @param storeNamed whether the read named a store; with none named there is no single shelf to
   *     count, so the answer is always null
   * @param available the available quantity at that store (on hand less reservations and bonded
   *     stock, as {@code Domain.Level.available()} defines it); null is treated as none
   * @param threshold the business's configured threshold (1..1000), or null when the feature is off
   * @param dropship whether the supplier fulfils this line per order — nothing sits on this shelf
   *     to count
   * @return the whole units left, only when {@code storeNamed}, a threshold is set, the line is not
   *     dropship, and {@code 0 < available <= threshold} and is a whole number; null otherwise
   */
  public static Integer compute(
      boolean storeNamed, BigDecimal available, Integer threshold, boolean dropship) {
    if (!storeNamed || threshold == null || dropship || available == null) {
      return null;
    }
    if (available.signum() <= 0) {
      return null;
    }
    BigDecimal whole = available.stripTrailingZeros();
    if (whole.scale() > 0) {
      return null; // a fractional quantity: weighed goods never get a count
    }
    if (whole.compareTo(BigDecimal.valueOf(threshold)) > 0) {
      return null; // never a count above the threshold
    }
    return whole.intValueExact();
  }
}
