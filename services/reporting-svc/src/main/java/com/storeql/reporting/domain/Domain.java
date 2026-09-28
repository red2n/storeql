package com.storeql.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain records for reporting-svc — the shapes its projections are read back as.
 *
 * <p>These are read models folded from other services' events, not source-of-truth entities, and
 * they never cross the HTTP boundary: {@link com.storeql.reporting.mapper.Mappers} converts them to
 * the DTOs in {@link com.storeql.reporting.dto.Dtos} first.
 */
public final class Domain {

  private Domain() {}

  /** Current on-hand projection per (tenant, store, variant). */
  public record InventoryProjection(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal onHand, Instant updatedAt) {}

  /** Single movement event used to aggregate demand stats. */
  public record MovementEvent(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String eventType,
      BigDecimal qtyChange,
      Instant occurredAt) {}

  /** Open transfer order line in transit (supply side of netting). */
  public record OpenSupplyLine(
      UUID id,
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      UUID variantId,
      BigDecimal qty,
      UUID eventId) {}

  /** Aggregated movement stat for one (store, variant, bucket). */
  public record MovementStat(
      UUID storeId, UUID variantId, String bucket, BigDecimal totalIn, BigDecimal totalOut) {}

  /** Sales totals for one currency over the queried window/filters. net = gross − refunded. */
  public record SalesSummary(String currency, long orders, BigDecimal gross, BigDecimal refunded) {
    /**
     * Net takings for the window.
     *
     * @return gross less refunded
     */
    public BigDecimal net() {
      return gross.subtract(refunded);
    }
  }

  /** Sales totals bucketed by day (and currency). net = gross − refunded. */
  /**
   * One line of a confirmed sale, as OrderConfirmed carries it. Unit price is null when unknown.
   */
  public record SaleLine(
      UUID variantId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}

  /**
   * What one category took: the leaf category, or its top-level ancestor when the report rolls up.
   * A null category is the lines whose product has no category, or whose variant the catalogue has
   * not announced.
   */
  public record SalesCategoryStat(
      UUID categoryId, String currency, long orders, BigDecimal units, BigDecimal gross) {}

  public record SalesDayStat(
      String day, String currency, long orders, BigDecimal gross, BigDecimal refunded) {
    /**
     * Net takings for the day.
     *
     * @return gross less refunded
     */
    public BigDecimal net() {
      return gross.subtract(refunded);
    }
  }

  /**
   * One day of a store: what it took, and what its hours cost.
   *
   * <p>The two numbers a manager actually puts side by side. Both are needed for either to mean
   * anything — takings without the cost of the hours that earned them is half a story, and labour
   * cost without takings is a number to worry about for no reason.
   *
   * @param labourCost null when some of the day's hours had no pay rate in force, which is reported
   *     as unknown rather than as zero: a Saturday shown as free labour is worse than one that says
   *     it does not know
   * @param uncostedMinutes minutes worked that could not be costed, so the caveat travels with the
   *     figure rather than being left to be noticed
   */
  public record LabourDayStat(
      String day,
      String currency,
      BigDecimal gross,
      BigDecimal refunded,
      long minutes,
      long uncostedMinutes,
      BigDecimal labourCost) {

    /** Net takings for the day. */
    public BigDecimal net() {
      return gross.subtract(refunded);
    }

    public BigDecimal hours() {
      return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /**
     * Labour as a percentage of net takings — the figure a shop is run on.
     *
     * <p>Null when the cost is unknown, and null when nothing was taken: a percentage of nothing is
     * not a large percentage, it is no percentage, and reporting it as one would put a shop on an
     * alarm for a day it was closed.
     */
    public BigDecimal labourPercent() {
      if (labourCost == null || net().signum() <= 0) return null;
      return labourCost.multiply(BigDecimal.valueOf(100)).divide(net(), 2, RoundingMode.HALF_UP);
    }

    /** Net takings per hour worked, the other way the same pair is read. */
    public BigDecimal salesPerHour() {
      if (minutes <= 0) return null;
      return net().divide(hours(), 2, RoundingMode.HALF_UP);
    }
  }
}
