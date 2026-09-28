package com.storeql.reporting.mapper;

import com.storeql.reporting.domain.Domain.InventoryProjection;
import com.storeql.reporting.domain.Domain.MovementStat;
import com.storeql.reporting.domain.Domain.OpenSupplyLine;
import com.storeql.reporting.domain.Domain.SalesCategoryStat;
import com.storeql.reporting.domain.Domain.SalesDayStat;
import com.storeql.reporting.domain.Domain.SalesSummary;
import com.storeql.reporting.dto.Dtos.MovementStatRow;
import com.storeql.reporting.dto.Dtos.MovementStatsReport;
import com.storeql.reporting.dto.Dtos.NettingReport;
import com.storeql.reporting.dto.Dtos.NettingRow;
import com.storeql.reporting.dto.Dtos.OnHandReport;
import com.storeql.reporting.dto.Dtos.OnHandRow;
import com.storeql.reporting.dto.Dtos.SalesByCategoryReport;
import com.storeql.reporting.dto.Dtos.SalesByDayReport;
import com.storeql.reporting.dto.Dtos.SalesCategoryRow;
import com.storeql.reporting.dto.Dtos.SalesDayRow;
import com.storeql.reporting.dto.Dtos.SalesSummaryReport;
import com.storeql.reporting.dto.Dtos.SalesSummaryRow;
import com.storeql.reporting.service.ReportingService.NettingResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Maps reporting-svc domain projections to the response DTOs served by the report endpoints. */
public final class Mappers {

  private Mappers() {}

  /**
   * Builds the cross-store on-hand report, summing a grand total across every row.
   *
   * @param rows on-hand projections, one per store/variant
   * @return the report rows plus the grand total
   */
  public static OnHandReport toOnHandReport(List<InventoryProjection> rows) {
    BigDecimal total =
        rows.stream().map(InventoryProjection::onHand).reduce(BigDecimal.ZERO, BigDecimal::add);
    var dtoRows =
        rows.stream()
            .map(
                p ->
                    new OnHandRow(
                        p.storeId().toString(),
                        p.variantId().toString(),
                        p.onHand(),
                        p.updatedAt()))
            .toList();
    return new OnHandReport(dtoRows, total);
  }

  /**
   * Builds the supply/demand netting report, adding in-transit supply to on-hand stock.
   *
   * <p>Supply lines are grouped by their <em>destination</em> store, so stock still in transit
   * counts towards the store expecting it rather than the one that shipped it. On-hand rows drive
   * the output: a variant with inbound supply but no on-hand row does not appear.
   *
   * @param result on-hand projections paired with the open supply lines
   * @return one row per on-hand store/variant with on-hand, in-transit and net available
   */
  public static NettingReport toNettingReport(NettingResult result) {
    // group supply lines by (storeId=toStoreId, variantId) to sum in-transit qty
    Map<String, BigDecimal> supplyTotals =
        result.supplyLines().stream()
            .collect(
                Collectors.toMap(
                    l -> key(l.toStoreId(), l.variantId()), OpenSupplyLine::qty, BigDecimal::add));

    var rows =
        result.onHand().stream()
            .map(
                p -> {
                  BigDecimal supply =
                      supplyTotals.getOrDefault(key(p.storeId(), p.variantId()), BigDecimal.ZERO);
                  return new NettingRow(
                      p.storeId().toString(),
                      p.variantId().toString(),
                      p.onHand(),
                      supply,
                      p.onHand().add(supply));
                })
            .toList();
    return new NettingReport(rows);
  }

  /**
   * Builds the movement-statistics report, deriving net movement per bucket.
   *
   * @param stats per-bucket in/out totals
   * @return the report rows, each carrying in, out and their difference
   */
  public static MovementStatsReport toMovementStatsReport(List<MovementStat> stats) {
    var rows =
        stats.stream()
            .map(
                s ->
                    new MovementStatRow(
                        s.storeId().toString(),
                        s.variantId().toString(),
                        s.bucket(),
                        s.totalIn(),
                        s.totalOut(),
                        s.totalIn().subtract(s.totalOut())))
            .toList();
    return new MovementStatsReport(rows);
  }

  /**
   * Builds the aggregate sales-summary report.
   *
   * @param rows sales totals grouped by currency
   * @return the report rows, one per currency
   */
  public static SalesSummaryReport toSalesSummaryReport(List<SalesSummary> rows) {
    var dtoRows =
        rows.stream()
            .map(
                s ->
                    new SalesSummaryRow(s.currency(), s.orders(), s.gross(), s.refunded(), s.net()))
            .toList();
    return new SalesSummaryReport(dtoRows);
  }

  /**
   * Builds the day-by-day sales report.
   *
   * @param rows sales totals bucketed by day and currency
   * @return the report rows, one per day/currency pair
   */
  /**
   * Sales by category on the wire, each row with its share of the currency's total: the figure a
   * buyer reads first, and one the client should not have to compute from a page of rows.
   */
  public static SalesByCategoryReport toSalesByCategoryReport(
      String level, List<SalesCategoryStat> rows) {
    Map<String, BigDecimal> totals = new HashMap<>();
    for (SalesCategoryStat r : rows) {
      totals.merge(r.currency(), r.gross(), BigDecimal::add);
    }
    var dtoRows =
        rows.stream()
            .map(
                r ->
                    new SalesCategoryRow(
                        r.categoryId() == null ? null : r.categoryId().toString(),
                        r.currency(),
                        r.orders(),
                        r.units(),
                        r.gross(),
                        share(r.gross(), totals.get(r.currency()))))
            .toList();
    return new SalesByCategoryReport(level, dtoRows);
  }

  private static BigDecimal share(BigDecimal part, BigDecimal total) {
    if (total == null || total.signum() == 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
  }

  public static SalesByDayReport toSalesByDayReport(List<SalesDayStat> rows) {
    var dtoRows =
        rows.stream()
            .map(
                s ->
                    new SalesDayRow(
                        s.day(), s.currency(), s.orders(), s.gross(), s.refunded(), s.net()))
            .toList();
    return new SalesByDayReport(dtoRows);
  }

  /**
   * Labour against sales on the wire. Hours rather than minutes, because that is how a rota reads.
   */
  public static com.storeql.reporting.dto.Dtos.LabourReport toLabourReport(
      List<com.storeql.reporting.domain.Domain.LabourDayStat> rows) {
    return new com.storeql.reporting.dto.Dtos.LabourReport(
        rows.stream()
            .map(
                r ->
                    new com.storeql.reporting.dto.Dtos.LabourDayRow(
                        r.day(),
                        r.currency(),
                        r.gross(),
                        r.refunded(),
                        r.net(),
                        r.hours(),
                        java.math.BigDecimal.valueOf(r.uncostedMinutes())
                            .divide(
                                java.math.BigDecimal.valueOf(60),
                                2,
                                java.math.RoundingMode.HALF_UP),
                        r.labourCost(),
                        r.labourPercent(),
                        r.salesPerHour()))
            .toList());
  }

  private static String key(UUID storeId, UUID variantId) {
    return storeId + ":" + variantId;
  }
}
