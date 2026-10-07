package com.storeql.inventory.service;

import com.storeql.inventory.domain.Domain.GrossMarginReport;
import com.storeql.inventory.domain.Domain.GrossMarginRow;
import com.storeql.inventory.domain.Domain.StockTurnGrouping;
import com.storeql.inventory.domain.Domain.StockTurnRow;
import com.storeql.inventory.domain.GrossMargin;
import com.storeql.inventory.repo.GrossMarginRepository;
import com.storeql.inventory.repo.StockTurnRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gross margin and GMROI per store or per product over a window (19.7): what the sales earned,
 * against the cost of the batches they drew down and the stock held to make them.
 */
@ApplicationScoped
public class GrossMarginService {

  /** Every group stock turn can see, before sorting by margin and limiting. */
  static final int REPLAY_LIMIT = 5000;

  @Inject StockTurnRepository stockTurn;
  @Inject GrossMarginRepository revenue;
  @Inject com.storeql.service.TenantProfiles profiles;

  /**
   * @return rows lowest margin first — the end of the list worth acting on — then highest revenue
   * @throws ApiException 400 {@code INVENTORY_INVALID_PERIOD} when from is not before to
   */
  public GrossMarginReport report(
      UUID tenantId,
      Set<UUID> stores,
      Instant from,
      Instant to,
      StockTurnGrouping grouping,
      int limit) {
    if (!from.isBefore(to)) {
      throw ApiException.badRequest(
          "INVENTORY_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);
    }
    int windowDays = (int) Math.max(1, Duration.between(from, to).toDays());
    Map<String, StockTurnRow> held =
        stockTurn
            .stockTurn(
                tenantId,
                stores,
                from,
                to,
                grouping,
                REPLAY_LIMIT,
                // Cost of sales and holdings in the business currency's own minor units; a
                // read-only report fails open on Fx's precision for an unknown currency.
                com.storeql.service.Fx.minorUnits(
                    profiles
                        .find(tenantId)
                        .map(com.storeql.service.TenantProfiles.Profile::currency)
                        .orElse(null)))
            .stream()
            .collect(Collectors.toMap(StockTurnRow::groupKey, Function.identity(), (a, b) -> a));
    var earned = revenue.earned(tenantId, stores, from, to, grouping);
    var unpriced = revenue.unpricedSaleQty(tenantId, stores, from, to, grouping);

    Set<String> keys = new LinkedHashSet<>(held.keySet());
    keys.addAll(earned.keySet());
    keys.addAll(unpriced.keySet());
    List<GrossMarginRow> rows =
        keys.stream()
            .map(
                k -> {
                  StockTurnRow t = held.get(k);
                  var e = earned.get(k);
                  return GrossMargin.row(
                      k,
                      e == null ? null : e.revenue(),
                      t == null ? null : t.cogs(),
                      e == null ? null : e.returnCost(),
                      t == null ? null : t.averageValue(),
                      t == null ? null : t.uncostedSaleQty(),
                      unpriced.get(k),
                      windowDays);
                })
            // A group that only held stock sold nothing: that is stock turn's finding, not a
            // margin.
            .filter(
                r ->
                    r.revenue().signum() != 0
                        || r.cogs().signum() != 0
                        || r.unpricedSaleQty().signum() != 0)
            .sorted(
                Comparator.comparing(
                        GrossMarginRow::marginPercent,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(GrossMarginRow::revenue, Comparator.reverseOrder())
                    .thenComparing(GrossMarginRow::groupKey))
            .limit(limit)
            .toList();
    return new GrossMarginReport(rows, stockTurn.historyComplete(tenantId, stores, to), windowDays);
  }
}
