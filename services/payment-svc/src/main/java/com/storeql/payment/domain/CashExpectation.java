package com.storeql.payment.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The cash a drawer (or a store's day) should hold: the one formula the X report, the close and the
 * day report all use, so they cannot disagree.
 *
 * <pre>expected = float + cash sales - cash refunds + pay-ins - pay-outs - drops</pre>
 *
 * <p>Pure: no clock, no database. Over/short is what was counted less the expectation, so a
 * positive figure is over and a negative one is short.
 *
 * @param openingFloat the cash the drawer started with
 * @param cashSales tenders taken in cash
 * @param cashRefunds cash paid back to customers
 * @param payIns cash put in for a reason that is not a sale
 * @param payOuts cash taken out for a petty expense
 * @param drops cash moved to the safe
 */
public record CashExpectation(
    BigDecimal openingFloat,
    BigDecimal cashSales,
    BigDecimal cashRefunds,
    BigDecimal payIns,
    BigDecimal payOuts,
    BigDecimal drops) {

  /** A null term counts as none, so a report with nothing of a kind reads as zero. */
  public CashExpectation {
    openingFloat = orZero(openingFloat);
    cashSales = orZero(cashSales);
    cashRefunds = orZero(cashRefunds);
    payIns = orZero(payIns);
    payOuts = orZero(payOuts);
    drops = orZero(drops);
  }

  /** The cash the drawer should hold. */
  public BigDecimal expected() {
    return openingFloat
        .add(cashSales)
        .subtract(cashRefunds)
        .add(payIns)
        .subtract(payOuts)
        .subtract(drops);
  }

  /**
   * Counted less expected.
   *
   * @param counted the cash actually counted
   * @return positive when over, negative when short, zero when right
   */
  public BigDecimal overShort(BigDecimal counted) {
    return Objects.requireNonNull(counted, "counted").subtract(expected());
  }

  private static BigDecimal orZero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
