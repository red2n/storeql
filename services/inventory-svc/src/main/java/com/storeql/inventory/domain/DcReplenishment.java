package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Depot / DC replenishment, pure: what a shop served by a warehouse needs from it, and how a
 * warehouse that holds less than its shops need shares what it has.
 *
 * <p><b>The need</b> is the reorder-point rule the purchase proposal uses, without a supplier's
 * order economics: when the shop's position — on hand plus what is already on its way from the
 * warehouse — has fallen to its reorder point, it needs enough to get back there and to cover what
 * it is expected to sell over the warehouse's lead time and the cover asked for. An internal
 * transfer has no minimum order, no lot and no EOQ; those are the supplier's, and the warehouse's
 * own purchase order is where they bend a quantity.
 *
 * <p><b>The share</b>, when the warehouse is short, is fair: each shop gets what it needs in
 * proportion to everyone's need, rounded down to whole units, and what is left over goes to the
 * shop with the least cover first — the one closest to running out — never above its need.
 */
public final class DcReplenishment {

  private static final int QTY_SCALE = 3;
  private static final int COVER_SCALE = 6;

  private DcReplenishment() {}

  /**
   * One item at one shop.
   *
   * @param rop the shop's reorder point; null when none has been computed
   * @param available on hand, less holds
   * @param inbound on its way from the warehouse: released and shipped transfers not yet received
   * @param expected the forecast over lead time and cover, or null when there is no forecast
   * @param avgDailyDemand the plan's average daily demand, for when there is no forecast
   * @param leadTimeDays the warehouse's lead time to the shop
   * @param coverDays the cover asked for
   */
  public record Shop(
      UUID storeId,
      BigDecimal rop,
      BigDecimal available,
      BigDecimal inbound,
      BigDecimal expected,
      BigDecimal avgDailyDemand,
      int leadTimeDays,
      int coverDays) {}

  /** The rule's answer for one item at one shop. */
  public sealed interface Result permits Need, Nothing, Skipped {}

  /**
   * The shop needs this much.
   *
   * @param position on hand plus inbound, for the least-cover order when the warehouse is short
   * @param avgDailyDemand how fast the shop sells it, or null when unknown
   */
  public record Need(
      UUID storeId, BigDecimal qty, BigDecimal position, BigDecimal avgDailyDemand, String reason)
      implements Result {}

  /** The position is above the reorder point. */
  public record Nothing() implements Result {}

  /** The item could not be judged, and why. */
  public record Skipped(String reason) implements Result {}

  /** What a shop is given, and the words beside it. */
  public record Allocation(UUID storeId, BigDecimal qty, BigDecimal need, String reason) {}

  /** Applies the rule to one item at one shop. */
  public static Result need(Shop s) {
    if (s.rop() == null) {
      return new Skipped(
          "no reorder point computed at the shop for this item — set a reorder plan and compute it");
    }
    BigDecimal available = zero(s.available());
    BigDecimal inbound = zero(s.inbound());
    BigDecimal position = available.add(inbound);
    if (position.compareTo(s.rop()) > 0) return new Nothing();
    int days = s.leadTimeDays() + s.coverDays();
    BigDecimal avg = s.avgDailyDemand();
    BigDecimal expected =
        s.expected() != null ? s.expected() : zero(avg).multiply(BigDecimal.valueOf(days));
    BigDecimal qty = s.rop().subtract(position).add(expected).setScale(QTY_SCALE, RoundingMode.UP);
    String over =
        s.expected() != null
            ? "forecast " + plain(expected) + " over " + days + " days"
            : plain(zero(avg)) + "/day over " + days + " days (no forecast)";
    String reason =
        "on hand "
            + plain(available)
            + " + inbound "
            + plain(inbound)
            + " = "
            + plain(position)
            + " ≤ reorder point "
            + plain(s.rop())
            + "; back to it plus "
            + over
            + " ("
            + s.leadTimeDays()
            + " days from the warehouse, "
            + s.coverDays()
            + " days' cover)";
    if (qty.signum() <= 0) return new Nothing();
    return new Need(s.storeId(), qty, position, avg, reason);
  }

  /**
   * Shares what the warehouse holds among the shops that need it.
   *
   * @param available what the warehouse can give: on hand, less holds and what it has already
   *     committed to shops
   * @param needs each shop's need for one item
   * @return one allocation per need, in the order given; a shop may be given nothing
   */
  public static List<Allocation> share(BigDecimal available, List<Need> needs) {
    BigDecimal have = zero(available).max(BigDecimal.ZERO);
    BigDecimal total = needs.stream().map(Need::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    List<Allocation> out = new ArrayList<>(needs.size());
    if (have.compareTo(total) >= 0) {
      for (Need n : needs) out.add(new Allocation(n.storeId(), n.qty(), n.qty(), n.reason()));
      return out;
    }
    Map<UUID, BigDecimal> given = new HashMap<>();
    BigDecimal handed = BigDecimal.ZERO;
    for (Need n : needs) {
      BigDecimal part =
          total.signum() == 0
              ? BigDecimal.ZERO
              : have.multiply(n.qty()).divide(total, 0, RoundingMode.FLOOR);
      given.put(n.storeId(), part);
      handed = handed.add(part);
    }
    BigDecimal left = have.subtract(handed);
    List<Need> byCover = new ArrayList<>(needs);
    byCover.sort(
        Comparator.comparing(
                DcReplenishment::cover, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Need::storeId));
    for (Need n : byCover) {
      if (left.signum() <= 0) break;
      BigDecimal room = n.qty().subtract(given.get(n.storeId()));
      BigDecimal more = room.min(left);
      if (more.signum() > 0) {
        given.put(n.storeId(), given.get(n.storeId()).add(more));
        left = left.subtract(more);
      }
    }
    for (Need n : needs) {
      BigDecimal qty = given.get(n.storeId());
      String reason =
          qty.compareTo(n.qty()) >= 0
              ? n.reason()
              : n.reason()
                  + "; cut to "
                  + plain(qty)
                  + " of "
                  + plain(n.qty())
                  + ": the warehouse is short, shared in proportion to need, the rest to the least"
                  + " cover";
      out.add(new Allocation(n.storeId(), qty, n.qty(), reason));
    }
    return out;
  }

  /** Days of cover at the shop: null (endless) when it has no demand to speak of. */
  private static BigDecimal cover(Need n) {
    if (n.avgDailyDemand() == null || n.avgDailyDemand().signum() <= 0) return null;
    return n.position().divide(n.avgDailyDemand(), COVER_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** A number as a person writes it: no trailing zeros, never exponent form. */
  static String plain(BigDecimal v) {
    BigDecimal stripped = v.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
  }
}
