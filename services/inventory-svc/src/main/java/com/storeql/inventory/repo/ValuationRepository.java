package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.ValuationGrouping;
import com.storeql.inventory.domain.Domain.ValuationRow;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Values stock on hand for the inventory valuation report.
 *
 * <p>Named in {@code docs/reporting-api-gap-analysis.md} as designed but unbuilt. It reads
 * inventory-svc's own {@code inventory_batches} and {@code costing_methods} — both tables belong to
 * this service, so the join is within one schema (golden rule #1 constrains reading *other*
 * services' tables).
 *
 * <p>Two costing bases, chosen per {@code (store, variant)} by the configured method:
 *
 * <ul>
 *   <li><b>FIFO</b> — each remaining batch valued at its own {@code cost_price}. This is the
 *       default when no costing method is configured, because it is what the deduction engine
 *       actually does: {@code deductFifo} draws stock down oldest-first, so batch cost is the
 *       honest basis.
 *   <li><b>AVERAGE</b> — the whole on-hand quantity valued at {@code costing_methods.average_cost}.
 *       Worth knowing: that figure is operator-maintained (set through {@code PUT
 *       /admin/inventory/costing-methods}), not recomputed from receipts, so it is a declared
 *       standard cost rather than a derived moving average.
 * </ul>
 *
 * <p><b>Unvalued stock is reported, never valued at zero.</b> {@code cost_price} is optional on
 * both receipt paths, and an AVERAGE row may have {@code average_cost} still at its default 0.
 * Treating either as zero would silently understate a figure that ends up on a balance sheet, so
 * those quantities come back in {@code unvaluedQty} for the caller to see.
 */
@ApplicationScoped
public class ValuationRepository extends BaseJdbcRepository {

  /**
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param storeId restrict to one store, or null for every store in the tenant
   * @param grouping VARIANT for the per-line detail, STORE for the rollup
   * @param limit maximum rows, already clamped by the caller
   * @return rows ordered by value, largest holding first
   */
  public List<ValuationRow> value(
      UUID tenantId, UUID storeId, ValuationGrouping grouping, int limit) {
    // "Can this row be valued?" -- an AVERAGE row needs a non-zero average_cost; a FIFO row needs a
    // cost_price on the batch. Written once here and reused in all three aggregates below so the
    // value and the unvalued quantity can never disagree about which rows counted.
    String usesAverage = "COALESCE(cm.method, 'FIFO') = 'AVERAGE' AND cm.average_cost > 0";
    String lineValue =
        "CASE WHEN "
            + usesAverage
            + " THEN b.remaining_qty * cm.average_cost"
            + " ELSE b.remaining_qty * COALESCE(b.cost_price, 0) END";
    String unvaluedQty =
        "CASE WHEN "
            + usesAverage
            + " THEN 0"
            + " WHEN b.cost_price IS NULL THEN b.remaining_qty ELSE 0 END";
    // Whose it is: the business's own value is what goes on its balance sheet; the supplier's
    // consignment holding is reported apart, at the cost the supplier will be owed.
    String owned = "b.ownership = 'OWNED'";
    String consigned = "b.ownership = 'CONSIGNMENT'";

    // Grouping comes from an enum, never from request text.
    String keyExpr =
        switch (grouping) {
          case STORE -> "b.store_id::text";
          case VARIANT -> "b.variant_id::text";
        };
    // A store rollup spans many variants with different methods, so naming one would be a lie;
    // MIXED says so explicitly rather than picking an arbitrary winner.
    String methodExpr =
        switch (grouping) {
          case STORE ->
              "CASE WHEN COUNT(DISTINCT COALESCE(cm.method, 'FIFO')) > 1 THEN 'MIXED'"
                  + " ELSE MIN(COALESCE(cm.method, 'FIFO')) END";
          case VARIANT -> "MIN(COALESCE(cm.method, 'FIFO'))";
        };

    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + keyExpr
                + " AS group_key,"
                + " "
                + methodExpr
                + " AS method,"
                + " SUM(b.remaining_qty)::numeric(18,3) AS on_hand_qty,"
                + " SUM(CASE WHEN "
                + owned
                + " THEN "
                + unvaluedQty
                + " ELSE 0 END)::numeric(18,3) AS unvalued_qty,"
                + " SUM(CASE WHEN "
                + owned
                + " THEN "
                + lineValue
                + " ELSE 0 END)::numeric(18,2) AS value,"
                + " SUM(CASE WHEN "
                + consigned
                + " THEN b.remaining_qty ELSE 0 END)::numeric(18,3) AS consignment_qty,"
                + " SUM(CASE WHEN "
                + consigned
                + " THEN "
                + lineValue
                + " ELSE 0 END)::numeric(18,2) AS consignment_value,"
                // In bond: valued at cost without the duty, the duty it would crystallise beside
                // it.
                + " SUM(CASE WHEN b.duty_status = 'DUTY_SUSPENDED' THEN b.remaining_qty ELSE 0"
                + " END)::numeric(18,3) AS duty_suspended_qty,"
                + " SUM(CASE WHEN b.duty_status = 'DUTY_SUSPENDED' THEN b.remaining_qty *"
                + " COALESCE(edr.duty_per_unit, 0) ELSE 0 END)::numeric(18,2) AS duty_potential"
                + " FROM inventory_batches b"
                + " LEFT JOIN costing_methods cm"
                + "   ON cm.tenant_id = b.tenant_id"
                + "  AND cm.store_id = b.store_id"
                + "  AND cm.variant_id = b.variant_id"
                + " LEFT JOIN excise_duty_rates edr"
                + "   ON edr.tenant_id = b.tenant_id AND edr.variant_id = b.variant_id"
                + " WHERE b.tenant_id = ? AND b.remaining_qty > 0");
    if (storeId != null) sql.append(" AND b.store_id = ?");
    sql.append(" GROUP BY 1 ORDER BY value DESC, group_key ASC LIMIT ?");

    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          ps.setInt(i, limit);
        },
        ValuationRepository::mapRow,
        "value inventory");
  }

  private static ValuationRow mapRow(ResultSet rs) throws SQLException {
    return new ValuationRow(
        rs.getString("group_key"),
        rs.getString("method"),
        rs.getBigDecimal("on_hand_qty"),
        rs.getBigDecimal("unvalued_qty"),
        rs.getBigDecimal("value"),
        rs.getBigDecimal("consignment_qty"),
        rs.getBigDecimal("consignment_value"),
        rs.getBigDecimal("duty_suspended_qty"),
        rs.getBigDecimal("duty_potential"));
  }
}
