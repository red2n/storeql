package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.LowStockRow;
import com.storeql.inventory.domain.Expiry;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The live low-stock report: everything currently below a reorder level someone configured for it.
 *
 * <p>Named in {@code docs/reporting-api-gap-analysis.md} as designed but unbuilt. Three things
 * nearby already exist and none of them answers this:
 *
 * <ul>
 *   <li>{@code GET /admin/inventory/planning/suggestions} returns the min/max engine's output, but
 *       only after {@code POST /planning/run} has been executed, and only from {@code
 *       reorder_thresholds} — it ignores safety stock and reorder points entirely.
 *   <li>{@code levelsSummary} counts SKUs under a flat, caller-supplied number for a dashboard
 *       tile. It compares every item against the same figure, not against its own level.
 *   <li>notification-svc's shortage alerts are an event history of past breaches, not current
 *       state.
 * </ul>
 *
 * <p>This reads live and honours all three configured signals — the manual min/max threshold, the
 * computed safety stock, and the computed reorder point — taking whichever is highest as the
 * binding floor and naming it. Each was configured deliberately by somebody; quietly picking a
 * lower one would under-order against a level a planner had set.
 */
@ApplicationScoped
public class LowStockRepository extends BaseJdbcRepository {

  @Inject ExpiryDay expiryDay;

  /**
   * Availability, defined exactly as {@code InventoryRepository.LEVELS_CORE} defines it — on hand
   * minus held reservations, counting only AVAILABLE material. Reserved stock is spoken for, so
   * treating it as on hand would under-report shortages; and a report that disagreed with the
   * levels list about how much stock there is would be worse than no report.
   */
  private static String available(Expiry expiry) {
    // Stock past its date is on hand but never available (Expiry), so it is no cover for a
    // shortage.
    return AVAILABLE_HEAD.replace("{SELLABLE}", expiry.sellableSql("b"));
  }

  private static final String AVAILABLE_HEAD =
      """
      SELECT b.store_id, b.variant_id,
             COALESCE(SUM(b.remaining_qty) FILTER (WHERE {SELLABLE}),0)
               - COALESCE(MAX(res.reserved),0) AS available
      FROM inventory_batches b
      LEFT JOIN (
          SELECT store_id, variant_id, SUM(qty) AS reserved
          FROM reservations WHERE tenant_id = ? AND status = 'HELD'
          GROUP BY store_id, variant_id
      ) res ON res.store_id = b.store_id AND res.variant_id = b.variant_id
      WHERE b.tenant_id = ? AND b.material_status = 'AVAILABLE'
      GROUP BY b.store_id, b.variant_id""";

  /**
   * Every configured reorder level, from all three sources, as one list to pick the highest from.
   * Rows with a NULL level are excluded rather than treated as zero: safety stock and reorder point
   * are both null until their compute job has run, and a not-yet-computed plan is not a floor of
   * nothing.
   */
  private static final String SIGNALS =
      """
      SELECT store_id, variant_id, threshold AS level, 'THRESHOLD' AS source
        FROM reorder_thresholds WHERE tenant_id = ?
      UNION ALL
      SELECT store_id, variant_id, safety_stock_qty, 'SAFETY_STOCK'
        FROM safety_stock_params WHERE tenant_id = ? AND safety_stock_qty IS NOT NULL
      UNION ALL
      SELECT store_id, variant_id, rop, 'REORDER_POINT'
        FROM reorder_point_plans WHERE tenant_id = ? AND rop IS NOT NULL""";

  /**
   * @param tenantId the owning tenant; the first filter on every branch (golden rule #3)
   * @param stores restrict to these stores, or null for every store in the tenant
   * @param limit maximum rows, already clamped by the caller
   * @return items below their reorder level, deepest shortfall first
   */
  public List<LowStockRow> lowStock(UUID tenantId, Set<UUID> stores, int limit) {
    // LEFT JOIN from the signals, not from the batches: an item that has run out has no batch rows
    // at all, and an inner join would silently drop exactly the most urgent case -- zero on hand
    // against a configured level.
    String sql =
        "WITH avail AS ("
            + available(expiryDay.of(tenantId))
            + "), signals AS ("
            + SIGNALS
            + "), binding AS ("
            + " SELECT DISTINCT ON (store_id, variant_id) store_id, variant_id, level, source"
            + "   FROM signals ORDER BY store_id, variant_id, level DESC, source"
            + ")"
            + " SELECT b.store_id::text AS store_id, b.variant_id::text AS variant_id,"
            + "        b.source AS signal,"
            + "        b.level::numeric(18,3) AS reorder_level,"
            + "        COALESCE(a.available, 0)::numeric(18,3) AS available_qty,"
            + "        (b.level - COALESCE(a.available, 0))::numeric(18,3) AS shortfall"
            + "   FROM binding b"
            + "   LEFT JOIN avail a"
            + "     ON a.store_id = b.store_id AND a.variant_id = b.variant_id"
            + "  WHERE COALESCE(a.available, 0) < b.level"
            // Item lifecycle: a line discontinued or delisted is never reordered, so it is no
            // shortage however low it runs.
            + "  AND NOT EXISTS (SELECT 1 FROM catalog_lines_out o"
            + "                  WHERE o.tenant_id = ? AND o.variant_id = b.variant_id)"
            + (stores != null ? " AND b.store_id = ANY(?)" : "")
            + "  ORDER BY shortfall DESC, b.store_id, b.variant_id"
            + "  LIMIT ?";

    return query(
        sql,
        ps -> {
          int i = 1;
          // avail: reservations tenant, then batches tenant
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          // signals: one tenant filter per UNION branch
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          // lines out: the tenant again
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          ps.setInt(i, limit);
        },
        LowStockRepository::mapRow,
        "list low stock");
  }

  private static LowStockRow mapRow(ResultSet rs) throws SQLException {
    return new LowStockRow(
        rs.getString("store_id"),
        rs.getString("variant_id"),
        rs.getString("signal"),
        rs.getBigDecimal("reorder_level"),
        rs.getBigDecimal("available_qty"),
        rs.getBigDecimal("shortfall"));
  }
}
