package com.storeql.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Points as lots (13.x): each earning is a lot with its own expiry, and points are spent from the
 * lot that dies first, then the oldest, so a customer never loses points they could have spent.
 * Pure arithmetic over what the repository holds.
 */
public final class PointLots {

  private PointLots() {}

  /** An open lot: what is left of one earning and when it dies (null for never). */
  public record PointLot(UUID id, BigDecimal remaining, Instant earnedAt, Instant expiresAt) {}

  /** Points taken from one lot. */
  public record Take(UUID lotId, BigDecimal points) {}

  /** Lots in spending order: soonest to expire first (never-expiring last), then the oldest. */
  static final Comparator<PointLot> SPENDING_ORDER =
      Comparator.comparing(
              (PointLot l) -> l.expiresAt(), Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(PointLot::earnedAt);

  /**
   * Which lots {@code points} come out of, in spending order. Takes what the lots hold when asked
   * for more; the balance check belongs to the caller.
   */
  public static List<Take> consume(List<PointLot> lots, BigDecimal points) {
    List<Take> out = new ArrayList<>();
    BigDecimal left = points;
    for (PointLot lot : lots.stream().sorted(SPENDING_ORDER).toList()) {
      if (left.signum() <= 0) {
        break;
      }
      if (lot.remaining().signum() <= 0) {
        continue;
      }
      BigDecimal take = lot.remaining().min(left);
      out.add(new Take(lot.id(), take));
      left = left.subtract(take);
    }
    return out;
  }
}
