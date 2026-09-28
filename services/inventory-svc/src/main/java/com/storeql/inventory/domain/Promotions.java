package com.storeql.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The days a promotion ran, or will run, for one item at one store (06.x) — read off the windows
 * pricing-svc, which owns promotions, answers for a store. Pure: nothing here knows where a window
 * came from.
 */
public final class Promotions {

  private Promotions() {}

  /**
   * One promotion as a window in time over some items at some store.
   *
   * @param promotionId the promotion
   * @param storeId the store it is scoped to, or null for every store of the business
   * @param startsAt when it started
   * @param endsAt when it ended or ends — its end date, or the moment it was switched off — or null
   *     when it is open-ended
   * @param variantIds the variants it is scoped to; empty with {@code allVariants} for everything
   * @param allVariants true when it applies to every item
   */
  public record PromotionWindow(
      UUID promotionId,
      UUID storeId,
      Instant startsAt,
      Instant endsAt,
      Set<UUID> variantIds,
      boolean allVariants) {

    public PromotionWindow {
      variantIds = Set.copyOf(variantIds);
    }

    /** Whether this window is about {@code variantId} at {@code storeId}. */
    public boolean covers(UUID variantId, UUID storeId) {
      boolean here = this.storeId == null || this.storeId.equals(storeId);
      return here && (allVariants || variantIds.contains(variantId));
    }

    /** Whether any part of the UTC day falls inside the window. */
    public boolean touches(LocalDate day) {
      Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
      Instant dayEnd = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
      return startsAt.isBefore(dayEnd) && (endsAt == null || endsAt.isAfter(dayStart));
    }
  }

  /**
   * One flag per day from {@code from} to {@code to}, true where a window covering the item at the
   * store touches the day.
   *
   * @param windows the store's promotion windows
   * @param variantId the item
   * @param storeId the store
   * @param from the first day
   * @param to the last day, inclusive
   * @return the flags, oldest first
   */
  public static boolean[] days(
      List<PromotionWindow> windows, UUID variantId, UUID storeId, LocalDate from, LocalDate to) {
    int n = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    boolean[] out = new boolean[Math.max(0, n)];
    List<PromotionWindow> mine =
        windows.stream().filter(w -> w.covers(variantId, storeId)).toList();
    if (mine.isEmpty()) {
      return out;
    }
    for (int i = 0; i < out.length; i++) {
      LocalDate day = from.plusDays(i);
      for (PromotionWindow w : mine) {
        if (w.touches(day)) {
          out[i] = true;
          break;
        }
      }
    }
    return out;
  }
}
