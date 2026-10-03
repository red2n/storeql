package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.StockTurnGrouping;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Revenue for the gross-margin report (19.7). Cost and the average holding come from {@link
 * StockTurnRepository}, which already replays them; this adds what the sales earned and what the
 * returns took back, grouped the same way, with voided sales excluded by the same rule.
 */
@ApplicationScoped
public class GrossMarginRepository extends BaseJdbcRepository {

  /** Net revenue and returned cost for one group. */
  public record Earned(BigDecimal revenue, BigDecimal returnCost) {}

  // A voided till sale is not a sale (SJ-D40): its stock comes back as a VOID receipt for the
  // order.
  private static final String NOT_VOIDED =
      " NOT EXISTS (SELECT 1 FROM stock_movements v WHERE v.tenant_id = %1$s.tenant_id"
          + " AND v.type = 'RECEIVE' AND v.ref_type = 'VOID' AND v.ref_id = %1$s.%2$s"
          + " AND v.variant_id = %1$s.variant_id)";

  public Map<String, Earned> earned(
      UUID tenantId, Set<UUID> stores, Instant from, Instant to, StockTurnGrouping grouping) {
    String key = grouping == StockTurnGrouping.VARIANT ? "r.variant_id::text" : "r.store_id::text";
    String sql =
        "SELECT "
            + key
            + " AS group_key, SUM(r.net_amount) AS revenue,"
            + " COALESCE(SUM(r.cost_amount) FILTER (WHERE r.kind = 'RETURN'), 0) AS return_cost"
            + " FROM sale_revenue r"
            + " WHERE r.tenant_id = ? AND r.recorded_at >= ? AND r.recorded_at < ?"
            + (stores != null ? " AND r.store_id = ANY(?)" : "")
            + " AND"
            + String.format(NOT_VOIDED, "r", "order_id")
            + " GROUP BY 1";
    return grouped(
        sql,
        tenantId,
        stores,
        from,
        to,
        rs -> new Earned(rs.getBigDecimal("revenue"), rs.getBigDecimal("return_cost")),
        "compute sale revenue");
  }

  /** Quantity sold in the window with no revenue recorded, per group. */
  public Map<String, BigDecimal> unpricedSaleQty(
      UUID tenantId, Set<UUID> stores, Instant from, Instant to, StockTurnGrouping grouping) {
    String key = grouping == StockTurnGrouping.VARIANT ? "m.variant_id::text" : "m.store_id::text";
    String sql =
        "SELECT "
            + key
            + " AS group_key, SUM(-m.qty) AS qty FROM stock_movements m"
            + " WHERE m.tenant_id = ? AND m.type = 'SALE' AND m.ref_type = 'ORDER'"
            + " AND m.created_at >= ? AND m.created_at < ?"
            + (stores != null ? " AND m.store_id = ANY(?)" : "")
            + " AND"
            + String.format(NOT_VOIDED, "m", "ref_id")
            + " AND NOT EXISTS (SELECT 1 FROM sale_revenue r WHERE r.tenant_id = m.tenant_id"
            + " AND r.kind = 'SALE' AND r.order_id = m.ref_id AND r.variant_id = m.variant_id)"
            + " GROUP BY 1";
    return grouped(
        sql, tenantId, stores, from, to, rs -> rs.getBigDecimal("qty"), "count unpriced sales");
  }

  /**
   * Runs a query keyed on {@code group_key} over the window, binding the tenant, the window's two
   * ends and then the store when there is one — the order both queries above write their filters
   * in.
   */
  private <V> Map<String, V> grouped(
      String sql,
      UUID tenantId,
      Set<UUID> stores,
      Instant from,
      Instant to,
      RowMapper<V> value,
      String what) {
    Map<String, V> out = new HashMap<>();
    for (Map.Entry<String, V> row :
        query(
            sql,
            ps -> {
              int i = 1;
              ps.setObject(i++, tenantId);
              ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
              ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
              if (stores != null) {
                ps.setArray(i, ps.getConnection().createArrayOf("uuid", stores.toArray()));
              }
            },
            rs -> Map.entry(rs.getString("group_key"), value.map(rs)),
            what)) {
      out.put(row.getKey(), row.getValue());
    }
    return out;
  }
}
