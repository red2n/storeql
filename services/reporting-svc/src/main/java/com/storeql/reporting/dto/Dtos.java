package com.storeql.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for reporting-svc — the wire contract for the report endpoints.
 *
 * <p>Ids are carried as {@code String} rather than {@code UUID} so the JSON contract stays stable,
 * and money is {@code BigDecimal} throughout.
 */
public final class Dtos {

  private Dtos() {}

  // ── Gap #47: Cross-store on-hand ─────────────────────────────────────────

  @Schema(name = "OnHandRow", description = "On-hand quantity for one store/variant pair.")
  public record OnHandRow(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal onHand,
      @Schema(description = "Timestamp of the last projected stock event for this row.")
          Instant updatedAt) {}

  @Schema(name = "OnHandReport", description = "Cross-store on-hand snapshot.")
  public record OnHandReport(
      List<OnHandRow> rows,
      @Schema(description = "Sum of onHand across all returned rows.") BigDecimal grandTotal) {}

  // ── Gap #48: Supply/demand netting ───────────────────────────────────────

  @Schema(
      name = "NettingRow",
      description = "On-hand netted against open in-transit supply for one store/variant pair.")
  public record NettingRow(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal onHand,
      @Schema(description = "Quantity on open in-transit supply lines (e.g. transfers/POs).")
          BigDecimal supplyInTransit,
      @Schema(description = "onHand + supplyInTransit.") BigDecimal netAvailable) {}

  @Schema(name = "NettingReport", description = "Supply/demand netting report.")
  public record NettingReport(List<NettingRow> rows) {}

  // ── Gap #49: Movement statistics ─────────────────────────────────────────

  @Schema(
      name = "MovementStatRow",
      description = "Bucketed stock movement totals for one store/variant pair.")
  public record MovementStatRow(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Bucket label/start date, sized by the requested bucketDays.")
          String bucket,
      @Schema(description = "Total quantity received/adjusted in during this bucket.")
          BigDecimal totalIn,
      @Schema(description = "Total quantity deducted/adjusted out during this bucket.")
          BigDecimal totalOut,
      @Schema(description = "totalIn - totalOut.") BigDecimal net) {}

  @Schema(name = "MovementStatsReport", description = "Movement-statistics report.")
  public record MovementStatsReport(List<MovementStatRow> rows) {}

  // ── N4: Sales reporting ──────────────────────────────────────────────────

  @Schema(name = "SalesSummaryRow", description = "Revenue summary for one currency.")
  public record SalesSummaryRow(
      String currency,
      @Schema(description = "Number of confirmed orders in the range.") long orders,
      @Schema(description = "Total order revenue before refunds.") BigDecimal gross,
      @Schema(description = "Total refunded amount in the range.") BigDecimal refunded,
      @Schema(description = "gross - refunded.") BigDecimal net) {}

  @Schema(name = "SalesSummaryReport", description = "Sales revenue summary, one row per currency.")
  public record SalesSummaryReport(List<SalesSummaryRow> rows) {}

  @Schema(name = "SalesDayRow", description = "Revenue for a single calendar day and currency.")
  public record SalesDayRow(
      @Schema(description = "Calendar day, ISO yyyy-MM-dd.") String day,
      String currency,
      @Schema(description = "Number of confirmed orders on this day.") long orders,
      @Schema(description = "Total order revenue before refunds.") BigDecimal gross,
      @Schema(description = "Total refunded amount on this day.") BigDecimal refunded,
      @Schema(description = "gross - refunded.") BigDecimal net) {}

  @Schema(name = "SalesByDayReport", description = "Daily sales revenue buckets, newest day first.")
  public record SalesByDayReport(List<SalesDayRow> rows) {}

  @Schema(name = "SalesCategoryRow")
  public record SalesCategoryRow(
      @Schema(
              description =
                  "The category: the product's own (level=leaf) or its top-level ancestor"
                      + " (level=top). Absent for lines whose product has no category, or whose"
                      + " variant the catalogue has not announced.")
          String categoryId,
      String currency,
      @Schema(description = "Confirmed orders with at least one line in the category.") long orders,
      @Schema(description = "Units sold, summed over the lines.") BigDecimal units,
      @Schema(description = "Line revenue before refunds.") BigDecimal gross,
      @Schema(description = "Percent of this currency's gross in the report, two decimals.")
          BigDecimal share) {}

  @Schema(
      name = "SalesByCategoryReport",
      description =
          "What each category took over the range, largest first; names come from the catalogue.")
  public record SalesByCategoryReport(
      @Schema(description = "leaf or top") String level, List<SalesCategoryRow> rows) {}

  @Schema(
      name = "LabourDayRow",
      description = "What a day took, and what the hours that earned it cost.")
  public record LabourDayRow(
      @Schema(description = "Calendar day, ISO yyyy-MM-dd.") String day,
      String currency,
      BigDecimal gross,
      BigDecimal refunded,
      @Schema(description = "gross - refunded.") BigDecimal net,
      @Schema(description = "Hours worked, to two decimal places.") BigDecimal hours,
      @Schema(
              description =
                  "Hours that could not be costed because no pay rate was in force that day. The"
                      + " caveat travels with the figure rather than being left to be noticed.")
          BigDecimal uncostedHours,
      @Schema(description = "Null when some of the day's hours had no rate: unknown, not zero.")
          BigDecimal labourCost,
      @Schema(
              description =
                  "Labour as a percentage of net takings — the figure a shop is run on. Null when the"
                      + " cost is unknown, and null when nothing was taken: a percentage of nothing is"
                      + " no percentage, and reporting one would raise an alarm for a day the shop was"
                      + " closed.")
          BigDecimal labourPercent,
      @Schema(description = "Net takings per hour worked, the other way the same pair is read.")
          BigDecimal salesPerHour) {}

  @Schema(
      name = "LabourReport",
      description = "Labour against sales, day by day, newest day first.")
  public record LabourReport(List<LabourDayRow> rows) {}
}
