package com.storeql.customer.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Taking points back when a sale comes back (return controls). Pure arithmetic: a return takes back
 * the share of what the sale earned that the refund is of the sale, a void takes back everything
 * still held, and neither takes more than was earned less what was already taken back.
 */
public final class LoyaltyReversal {

  private LoyaltyReversal() {}

  /** Points still to take back for a whole order: earned less what was already taken back. */
  public static BigDecimal remaining(BigDecimal earned, BigDecimal alreadyReversed) {
    return earned.subtract(alreadyReversed).max(BigDecimal.ZERO);
  }

  /**
   * The points a return takes back, rounded down as earning rounds.
   *
   * @param earned points the order earned
   * @param alreadyReversed points already taken back for it (positive)
   * @param orderTotal what the earning was based on, or null for an earning that did not record it
   * @param refundAmount what this return refunded
   * @param pointsPerUnit the programme's points per unit, the fallback rate without an order total
   */
  public static BigDecimal forReturn(
      BigDecimal earned,
      BigDecimal alreadyReversed,
      BigDecimal orderTotal,
      BigDecimal refundAmount,
      BigDecimal pointsPerUnit) {
    BigDecimal left = remaining(earned, alreadyReversed);
    if (left.signum() <= 0 || refundAmount == null || refundAmount.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal share;
    if (orderTotal != null && orderTotal.signum() > 0) {
      if (refundAmount.compareTo(orderTotal) >= 0) {
        return left;
      }
      share = earned.multiply(refundAmount).divide(orderTotal, 2, RoundingMode.DOWN);
    } else {
      share = refundAmount.multiply(pointsPerUnit).setScale(2, RoundingMode.DOWN);
    }
    return share.min(left);
  }
}
