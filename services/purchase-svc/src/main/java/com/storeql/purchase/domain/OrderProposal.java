package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The automatic order proposal's policy (06.x) as pure arithmetic over one item at one store: a
 * reorder-point rule, the quantity, the supplier's order modifiers, and the words a buyer reads
 * beside the line.
 *
 * <p>The rule is the (s, Q) policy the reorder point was computed for: when the <b>stock
 * position</b> — what is on hand plus what is already on order — has fallen to the reorder point or
 * below, order. The quantity is the economic order quantity when the plan has one; otherwise enough
 * to get back to the reorder point and cover the period ahead, from the forecast when there is one
 * and from the average daily demand when there is not; and never less than it takes to get back
 * above the reorder point. The supplier's modifiers then bend it — a minimum raises it, a lot size
 * rounds it up, a maximum caps it at the largest lot that fits. Every step it took is in the
 * reason, because a proposal a buyer cannot check is one they will not trust.
 */
public final class OrderProposal {

  private static final int QTY_SCALE = 3;

  private OrderProposal() {}

  /**
   * What the reorder plan at inventory-svc says about the item; {@code rop} null = not computed.
   */
  public record Plan(
      UUID variantId,
      BigDecimal rop,
      BigDecimal eoq,
      BigDecimal minOrderQty,
      BigDecimal maxOrderQty,
      BigDecimal lotMultiplier,
      BigDecimal avgDailyDemand,
      int leadTimeDays) {}

  /**
   * Where the item stands: on hand, on order, what the cover period is expected to sell, and how
   * long the item lives. {@code expectedOverCover} is for the cover the caller will actually get —
   * the cover asked for, or the shelf life when that is shorter — because ordering more of a fresh
   * item than sells before it expires is ordering waste. {@code shelfLifeDays} null = keeps.
   */
  public record Position(
      BigDecimal available,
      BigDecimal onOrder,
      BigDecimal expectedOverCover,
      Integer shelfLifeDays) {}

  /** The policy's answer for one item. */
  public sealed interface Result permits Order, Nothing, Skipped {}

  /** Order this much, for this reason. */
  public record Order(BigDecimal qty, String reason) implements Result {}

  /** The position is above the reorder point: nothing to do. */
  public record Nothing() implements Result {}

  /** The item could not be judged, and this is why. */
  public record Skipped(String reason) implements Result {}

  /**
   * Applies the policy to one item.
   *
   * @param plan the reorder plan
   * @param position the stock position
   * @param coverDays the period an order without an EOQ should cover
   * @return an order with its reason, nothing, or why the item was skipped
   */
  public static Result propose(Plan plan, Position position, int coverDays) {
    if (plan.rop() == null) {
      return new Skipped(
          "no reorder point computed for this item yet — set a reorder plan and compute it");
    }
    BigDecimal available = zeroIfNull(position.available());
    BigDecimal onOrder = zeroIfNull(position.onOrder());
    BigDecimal stockPosition = available.add(onOrder);
    if (stockPosition.compareTo(plan.rop()) > 0) {
      return new Nothing();
    }
    StringBuilder reason =
        new StringBuilder()
            .append("on hand ")
            .append(plain(available))
            .append(" + on order ")
            .append(plain(onOrder))
            .append(" = ")
            .append(plain(stockPosition))
            .append(" ≤ reorder point ")
            .append(plain(plan.rop()))
            .append("; ");
    BigDecimal shortfall = plan.rop().subtract(stockPosition);
    // A fresh item is ordered for no longer than it lives.
    int cover = coverDays;
    boolean capped = false;
    if (position.shelfLifeDays() != null && position.shelfLifeDays() < coverDays) {
      cover = position.shelfLifeDays();
      capped = true;
    }
    String cappedNote = capped ? " (capped to the " + cover + "-day shelf life)" : "";
    BigDecimal avg = zeroIfNull(plan.avgDailyDemand());
    BigDecimal expected =
        position.expectedOverCover() != null
            ? position.expectedOverCover()
            : avg.multiply(BigDecimal.valueOf(cover));
    BigDecimal qty;
    if (plan.eoq() != null && plan.eoq().signum() > 0) {
      qty = plan.eoq();
      reason.append("order EOQ ").append(plain(plan.eoq()));
      BigDecimal sellsWithinLife = shortfall.add(expected);
      if (position.shelfLifeDays() != null && qty.compareTo(sellsWithinLife) > 0) {
        qty = sellsWithinLife;
        reason
            .append(" cut to what sells within the ")
            .append(position.shelfLifeDays())
            .append("-day shelf life (")
            .append(plain(sellsWithinLife))
            .append(")");
      }
    } else if (position.expectedOverCover() != null) {
      qty = shortfall.add(expected);
      reason
          .append("order back to the reorder point plus forecast ")
          .append(plain(expected))
          .append(" over ")
          .append(cover)
          .append(" days")
          .append(cappedNote);
    } else {
      qty = shortfall.add(expected);
      reason
          .append("order back to the reorder point plus ")
          .append(plain(avg))
          .append("/day over ")
          .append(cover)
          .append(" days (no forecast)")
          .append(cappedNote);
    }
    if (qty.compareTo(shortfall) < 0) {
      qty = shortfall;
      reason.append(", raised to restore the reorder point");
    }
    List<String> bends = new ArrayList<>();
    if (plan.minOrderQty() != null && qty.compareTo(plan.minOrderQty()) < 0) {
      qty = plan.minOrderQty();
      bends.add("minimum order " + plain(plan.minOrderQty()));
    }
    BigDecimal lot = plan.lotMultiplier();
    boolean lots = lot != null && lot.signum() > 0;
    if (lots) {
      qty = ceilToLot(qty, lot);
      bends.add("lots of " + plain(lot));
    }
    if (plan.maxOrderQty() != null && qty.compareTo(plan.maxOrderQty()) > 0) {
      qty = lots ? floorToLot(plan.maxOrderQty(), lot) : plan.maxOrderQty();
      bends.add("maximum order " + plain(plan.maxOrderQty()));
    }
    if (qty.signum() <= 0) {
      return new Skipped(
          "the supplier's order modifiers leave nothing to order ("
              + String.join(", ", bends)
              + ")");
    }
    if (!bends.isEmpty()) {
      reason.append("; ").append(String.join(", ", bends));
    }
    return new Order(qty.setScale(QTY_SCALE, RoundingMode.HALF_UP), reason.toString());
  }

  private static BigDecimal ceilToLot(BigDecimal qty, BigDecimal lot) {
    return qty.divide(lot, 0, RoundingMode.CEILING).multiply(lot);
  }

  private static BigDecimal floorToLot(BigDecimal qty, BigDecimal lot) {
    return qty.divide(lot, 0, RoundingMode.FLOOR).multiply(lot);
  }

  private static BigDecimal zeroIfNull(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** A number as a buyer would write it: no trailing zeros, never exponent form. */
  public static String plain(BigDecimal v) {
    BigDecimal stripped = v.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
  }
}
