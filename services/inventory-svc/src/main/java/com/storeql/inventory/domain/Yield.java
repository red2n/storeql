package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of a breakdown, pure: what a primal is expected to yield, what its cost becomes on
 * the cuts, and what the loss was.
 *
 * <p>Quantities are in the primal's own unit throughout — a yield is a weight story, and a template
 * whose cuts are counted in a different unit from its primal reads a loss that means nothing; that
 * is the business's choice to make, not the platform's to check. The loss carries no cost: what the
 * bin took is absorbed by the cuts, which is why a fillet costs more per kilo than the side it came
 * from.
 */
public final class Yield {

  private Yield() {}

  /** Quantities to three decimals, the store's own precision. */
  public static final int QTY_SCALE = 3;

  /** A cost per unit to two decimals, as batches keep it. */
  public static final int COST_SCALE = 2;

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** What came out of a cut, and the share of the primal's cost it carries. */
  public record Share(BigDecimal qty, BigDecimal costShare) {}

  /** The quantity a cut is expected to be: the input times its share. */
  public static BigDecimal expectedQty(BigDecimal inputQty, BigDecimal pct) {
    return inputQty.multiply(pct).divide(HUNDRED, QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** What is expected to be lost: the input less every cut's share, in percent. */
  public static BigDecimal expectedLossPct(List<BigDecimal> pcts) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal p : pcts) sum = sum.add(p);
    return HUNDRED.subtract(sum).setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** What was lost as a share of the input, in percent; nothing in, nothing lost. */
  public static BigDecimal lossPct(BigDecimal inputQty, BigDecimal outputQty) {
    if (inputQty.signum() <= 0) return BigDecimal.ZERO.setScale(QTY_SCALE);
    return inputQty
        .subtract(outputQty)
        .multiply(HUNDRED)
        .divide(inputQty, QTY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Spreads the primal's cost over the cuts by their cost shares, each share then spread over the
   * quantity that came out of it, to a cost per unit.
   *
   * <p>A cut that came to nothing carries no cost and its share goes to the cuts that did; an
   * unvalued primal (null) makes unvalued cuts, never cuts at nothing.
   *
   * @return one unit cost per share, in order; null where there is none
   */
  public static List<BigDecimal> apportion(BigDecimal inputCost, List<Share> shares) {
    List<BigDecimal> unit = new ArrayList<>(shares.size());
    BigDecimal totalShare = BigDecimal.ZERO;
    for (Share s : shares) {
      if (s.qty().signum() > 0) totalShare = totalShare.add(s.costShare());
    }
    for (Share s : shares) {
      if (inputCost == null || s.qty().signum() <= 0 || totalShare.signum() <= 0) {
        unit.add(null);
        continue;
      }
      unit.add(
          inputCost
              .multiply(s.costShare())
              .divide(totalShare, 8, RoundingMode.HALF_UP)
              .divide(s.qty(), COST_SCALE, RoundingMode.HALF_UP));
    }
    return unit;
  }
}
