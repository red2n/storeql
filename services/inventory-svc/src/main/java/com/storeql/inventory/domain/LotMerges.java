package com.storeql.inventory.domain;

import com.storeql.inventory.domain.Domain.Batch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The rules of merging one batch's stock into another's, kept pure: what two batches must share,
 * the unit cost the merged batch carries, and the use-by date it is sold by.
 */
public final class LotMerges {

  private LotMerges() {}

  /** Why two batches cannot be merged. */
  public enum Mismatch {
    /** They are at different stores. */
    STORE,
    /** They are of different variants. */
    VARIANT,
    /** Their material status, grade, ownership, supplier or duty status differ. */
    CONDITION,
    /** One has a unit cost and the other has none. */
    COST_UNKNOWN
  }

  /**
   * What stops {@code source}'s stock being merged into {@code target}, or null when nothing does.
   *
   * <p>Stock keeps the condition it is in: held stock merged into stock on sale would sell it,
   * rejected stock merged into a better grade would pass as it, and the supplier's stock merged
   * into the business's own would make it the business's. So the two must be at the same store, of
   * the same variant, in the same material status and grade, with the same ownership (and supplier)
   * and duty status; the checks run in that order.
   *
   * @return the first thing that differs
   */
  public static Mismatch mismatch(Batch source, Batch target) {
    if (!source.storeId().equals(target.storeId())) return Mismatch.STORE;
    if (!source.variantId().equals(target.variantId())) return Mismatch.VARIANT;
    if (!Objects.equals(source.materialStatus(), target.materialStatus())
        || !Objects.equals(source.grade(), target.grade())
        || !Objects.equals(source.ownership(), target.ownership())
        || !Objects.equals(source.ownerSupplierId(), target.ownerSupplierId())
        || !Objects.equals(source.dutyStatus(), target.dutyStatus())) {
      return Mismatch.CONDITION;
    }
    if ((source.costPrice() == null) != (target.costPrice() == null)) return Mismatch.COST_UNKNOWN;
    return null;
  }

  /**
   * Whether the merged batch's cost has to be worked out, which is when both costs are known and
   * differ (by value, not by scale). Only then does the business's currency matter.
   */
  public static boolean needsBlend(BigDecimal sourceCost, BigDecimal targetCost) {
    return sourceCost != null && targetCost != null && sourceCost.compareTo(targetCost) != 0;
  }

  /**
   * The unit cost of the target after a merge: the average of the two costs weighted by the
   * quantities, rounded half up to the currency's minor units. Equal costs are returned as the
   * target has them, unrounded; a target holding none takes the source's cost as it is. Both costs
   * unknown stays unknown.
   *
   * @param targetQty what the target holds before the merge
   * @param movedQty what moves into it
   * @param minorUnits the business currency's minor units, as {@code Fx.minorUnits} gives them
   */
  public static BigDecimal blendedCost(
      BigDecimal targetQty,
      BigDecimal targetCost,
      BigDecimal movedQty,
      BigDecimal sourceCost,
      int minorUnits) {
    if (targetCost == null || sourceCost == null || targetCost.compareTo(sourceCost) == 0) {
      return targetCost;
    }
    if (targetQty.signum() == 0) return sourceCost;
    BigDecimal value = targetQty.multiply(targetCost).add(movedQty.multiply(sourceCost));
    return value.divide(targetQty.add(movedQty), minorUnits, RoundingMode.HALF_UP);
  }

  /**
   * The use-by date of the merged batch: the earlier of the two, so none of its stock is sold past
   * the date it came with. A date beats none.
   */
  public static LocalDate earlierExpiry(LocalDate a, LocalDate b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }
}
