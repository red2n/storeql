package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.DeadStockGrouping;
import com.storeql.inventory.domain.Domain.DeadStockRow;
import com.storeql.inventory.domain.Domain.StockTurnGrouping;
import com.storeql.inventory.domain.Domain.StockTurnRow;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stock turn and dead-stock ageing — the last two inventory reports named in {@code
 * docs/reporting-api-gap-analysis.md}.
 *
 * <p>Both read this service's own {@code stock_movements} and {@code inventory_batches}. Neither
 * could be built in reporting-svc: its {@code movement_events} projection carries no cost, and
 * {@code inventory_projection} carries no batch, so there is nothing there to value a sale with
 * (golden rule #1 constrains reading *other* services' tables, not reporting on your own).
 *
 * <p><b>Why the movement ledger and not a stored snapshot.</b> A batch's {@code cost_price} is set
 * at receipt and never changes, and every mutation of a batch writes a movement naming it. Those
 * two facts together make the cost of any past sale, and the value of the holding at any past
 * instant, exactly recoverable by replay — {@code qty(b, T) = SUM(movements on b before T)}. A
 * nightly valuation snapshot would have been cheaper to query and would have made every window that
 * predates the first snapshot unanswerable.
 *
 * <p><b>The one thing replay cannot see</b> is a movement that has been purged into {@code
 * stock_movements_archive}. {@link #historyComplete} asks that table directly, so the caller can
 * say the figures are a floor rather than quietly reporting an opening value of zero for a store
 * that has been trading for years.
 */
@ApplicationScoped
public class StockTurnRepository extends BaseJdbcRepository {

  /**
   * Cost of goods sold over a window, against the average value of the holding that produced it.
   *
   * <p>Opening and closing values are both reconstructed, rather than closing being read live from
   * {@code remaining_qty}. Reading it live would be marginally cheaper and would silently answer a
   * question about last quarter with today's stock level.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param stores restrict to these stores, or null for every store in the tenant
   * @param from inclusive start of the window; required — a turnover ratio has no meaning without
   *     one
   * @param to exclusive end of the window; required
   * @param grouping STORE for the rollup, VARIANT for the per-item detail
   * @param limit maximum rows, already clamped by the caller
   * @return one row per group, slowest-turning first — the end of the list a manager acts on
   */
  public List<StockTurnRow> stockTurn(
      UUID tenantId,
      Set<UUID> stores,
      Instant from,
      Instant to,
      StockTurnGrouping grouping,
      int limit) {
    // Grouping comes from an enum, never from request text.
    String keyExpr =
        switch (grouping) {
          case STORE -> "b.store_id::text";
          case VARIANT -> "b.variant_id::text";
        };

    // One pass over the ledger per batch, producing the three figures the report needs:
    // quantity held at each end of the window, and quantity sold inside it. Doing it in a CTE
    // keyed on batch_id means the ledger is scanned once rather than once per aggregate.
    //
    // GREATEST(...,0) on the two positions: a purged ledger can leave a batch with only its
    // later, negative movements retained, which replays to a negative holding. Zero is the
    // honest floor -- and historyComplete tells the caller the figure is a floor.
    String sql =
        "WITH mv AS ("
            + "  SELECT m.batch_id,"
            + "         GREATEST(COALESCE(SUM(m.qty) FILTER (WHERE m.created_at < ?), 0), 0)"
            + "           AS qty_open,"
            + "         GREATEST(COALESCE(SUM(m.qty) FILTER (WHERE m.created_at < ?), 0), 0)"
            + "           AS qty_close,"
            // A voided till sale is excluded, not netted (SJ-D40). Its stock comes back as a
            // RECEIVE with reference type VOID into a new return batch, and this sum is taken per
            // batch, so the receipt and the sale it cancels never meet in the same group.
            + "         COALESCE(SUM(-m.qty) FILTER ("
            + "           WHERE m.type = 'SALE'"
            + "             AND NOT EXISTS (SELECT 1 FROM stock_movements v"
            + "                              WHERE v.tenant_id = m.tenant_id AND v.type = 'RECEIVE'"
            + "                                AND v.ref_type = 'VOID' AND v.ref_id = m.ref_id"
            + "                                AND v.variant_id = m.variant_id)"
            + "             AND m.created_at >= ? AND m.created_at < ?), 0)"
            + "           AS sold_qty"
            + "    FROM stock_movements m"
            + "   WHERE m.tenant_id = ? AND m.batch_id IS NOT NULL"
            + "   GROUP BY m.batch_id"
            + ")"
            + " SELECT "
            + keyExpr
            + " AS group_key,"
            // A sale out of an uncosted batch contributes nothing to cogs and is reported
            // separately instead -- costing it at zero would flatter both margin and turns.
            + " SUM(COALESCE(mv.sold_qty, 0) * COALESCE(b.cost_price, 0))::numeric(18,2) AS cogs,"
            + " SUM(CASE WHEN b.cost_price IS NULL THEN COALESCE(mv.sold_qty, 0) ELSE 0 END)"
            + "   ::numeric(18,3) AS uncosted_sale_qty,"
            + " SUM(COALESCE(mv.qty_open, 0) * COALESCE(b.cost_price, 0))::numeric(18,2)"
            + "   AS opening_value,"
            + " SUM(COALESCE(mv.qty_close, 0) * COALESCE(b.cost_price, 0))::numeric(18,2)"
            + "   AS closing_value"
            + " FROM inventory_batches b"
            + " LEFT JOIN mv ON mv.batch_id = b.id"
            + " WHERE b.tenant_id = ?"
            + (stores != null ? " AND b.store_id = ANY(?)" : "")
            // A group that neither held nor sold anything in the window is not a stock-turn
            // finding, it is noise; every variant ever received would otherwise appear forever.
            + " GROUP BY 1"
            + " HAVING SUM(COALESCE(mv.sold_qty, 0)) <> 0"
            + "     OR SUM(COALESCE(mv.qty_open, 0)) <> 0"
            + "     OR SUM(COALESCE(mv.qty_close, 0)) <> 0"
            // Slowest first: the ratio is computed in Java, so order by the two inputs that
            // decide it -- most stock held, least sold.
            + " ORDER BY cogs ASC, closing_value DESC, group_key ASC"
            + " LIMIT ?";

    var fromTs = from.atOffset(ZoneOffset.UTC);
    var toTs = to.atOffset(ZoneOffset.UTC);
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, fromTs);
          ps.setObject(i++, toTs);
          ps.setObject(i++, fromTs);
          ps.setObject(i++, toTs);
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          ps.setInt(i, limit);
        },
        StockTurnRepository::mapTurnRow,
        "compute stock turn");
  }

  /**
   * Whether any movement the window's replay depends on has been purged out of {@code
   * stock_movements} and into the archive.
   *
   * <p>The obvious test — "is the oldest retained movement later than the window opens?" — was the
   * first version of this, and it was wrong in a way that would have made the flag useless: it
   * reads true for every tenant young enough that its first movement falls inside the window, which
   * is most of them, and a warning that fires on healthy data is a warning people learn to ignore.
   * There is a difference between history that is missing and history that never happened, and only
   * the archive can tell them apart.
   *
   * <p>Any archived row dated before the window's end matters: one before {@code from} understates
   * opening value, one inside the window understates closing value and COGS as well.
   *
   * @param to the window's exclusive end — nothing archived after it could affect the replay
   * @return true when the archive holds nothing the replay needed
   */
  public boolean historyComplete(UUID tenantId, Set<UUID> stores, Instant to) {
    String sql =
        "SELECT EXISTS (SELECT 1 FROM stock_movements_archive"
            + " WHERE tenant_id = ? AND created_at < ?"
            + (stores != null ? " AND store_id = ANY(?)" : "")
            + ") AS purged";
    List<Boolean> found =
        query(
            sql,
            ps -> {
              int i = 1;
              ps.setObject(i++, tenantId);
              ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
              if (stores != null) {
                ps.setArray(i, ps.getConnection().createArrayOf("uuid", stores.toArray()));
              }
            },
            rs -> rs.getBoolean("purged"),
            "check movement history completeness");
    return found.isEmpty() || !found.get(0);
  }

  /**
   * Stock still on hand, aged by how long it has been since it last sold.
   *
   * <p>Ageing from the last sale rather than from receipt is the whole point: a line that arrived
   * two years ago and sold this morning is not dead stock, and a line received last week that has
   * never sold at all might be. Stock that has never sold has no last-sale date, so it ages from
   * the receipt of its oldest remaining batch — the only date it has — and {@code neverSold} says
   * that is what happened rather than leaving the caller to infer it.
   *
   * <p>The unit of ageing is {@code (store, variant)}, not the batch: a batch is a receipt, and
   * FIFO means a newly received batch of a briskly selling line would otherwise read as untouched.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param stores restrict to these stores, or null for every store in the tenant
   * @param asOf the instant ages are measured back from — the caller's clock, so a report run
   *     against a fixed date is reproducible
   * @param grouping BUCKET for the ageing summary, STORE or VARIANT for the detail
   * @param limit maximum rows, already clamped by the caller
   * @return rows ordered by value at risk, largest first
   */
  public List<DeadStockRow> deadStock(
      UUID tenantId, Set<UUID> stores, Instant asOf, DeadStockGrouping grouping, int limit) {
    // Per (store, variant): what is left, what it is worth, and when it last sold.
    String perItem =
        "WITH holding AS ("
            + "  SELECT b.store_id, b.variant_id,"
            + "         SUM(b.remaining_qty)::numeric(18,3) AS on_hand_qty,"
            + "         SUM(b.remaining_qty * COALESCE(b.cost_price, 0))::numeric(18,2) AS value,"
            + "         SUM(CASE WHEN b.cost_price IS NULL THEN b.remaining_qty ELSE 0 END)"
            + "           ::numeric(18,3) AS uncosted_qty,"
            + "         MIN(b.created_at) AS oldest_receipt"
            + "    FROM inventory_batches b"
            + "   WHERE b.tenant_id = ? AND b.remaining_qty > 0"
            + (stores != null ? "     AND b.store_id = ANY(?)" : "")
            + "   GROUP BY b.store_id, b.variant_id"
            + "), last_sale AS ("
            + "  SELECT m.store_id, m.variant_id, MAX(m.created_at) AS sold_at"
            + "    FROM stock_movements m"
            + "   WHERE m.tenant_id = ? AND m.type = 'SALE'"
            // A voided sale did not happen, so it must not reset how long an item has sat unsold.
            + "     AND NOT EXISTS (SELECT 1 FROM stock_movements v"
            + "                      WHERE v.tenant_id = m.tenant_id AND v.type = 'RECEIVE' AND v.ref_type = 'VOID'"
            + "                        AND v.ref_id = m.ref_id AND v.variant_id = m.variant_id)"
            + (stores != null ? "     AND m.store_id = ANY(?)" : "")
            + "   GROUP BY m.store_id, m.variant_id"
            + "), aged AS ("
            + "  SELECT h.store_id, h.variant_id, h.on_hand_qty, h.value, h.uncosted_qty,"
            + "         (s.sold_at IS NULL) AS never_sold,"
            // date_trunc('day', ...) on both sides so an age is a whole number of days rather
            // than a fraction that rounds differently depending on the hour the report was run.
            + "         GREATEST(EXTRACT(DAY FROM (date_trunc('day', ?::timestamptz)"
            + "           - date_trunc('day', COALESCE(s.sold_at, h.oldest_receipt))))::int, 0)"
            + "           AS days_idle"
            + "    FROM holding h"
            + "    LEFT JOIN last_sale s"
            + "      ON s.store_id = h.store_id AND s.variant_id = h.variant_id"
            + ")";

    // Buckets are a fixed retail ladder rather than a caller-supplied one: the point of the
    // report is that two stores can be compared, which they cannot be on different ladders.
    String keyExpr =
        switch (grouping) {
          case BUCKET ->
              "CASE WHEN days_idle <= 30 THEN '0-30'"
                  + " WHEN days_idle <= 60 THEN '31-60'"
                  + " WHEN days_idle <= 90 THEN '61-90'"
                  + " WHEN days_idle <= 180 THEN '91-180'"
                  + " ELSE '180+' END";
          case STORE -> "store_id::text";
          case VARIANT -> "variant_id::text";
        };

    String sql =
        perItem
            + " SELECT "
            + keyExpr
            + " AS group_key,"
            + " SUM(on_hand_qty)::numeric(18,3) AS on_hand_qty,"
            + " SUM(value)::numeric(18,2) AS value,"
            + " SUM(uncosted_qty)::numeric(18,3) AS uncosted_qty,"
            // The oldest thing in the group, not the average: an average age hides the one line
            // that has not moved in two years behind twenty that moved yesterday.
            + " MAX(days_idle) AS days_idle,"
            // Only "never sold" when nothing in the group ever has -- a mixed group is not.
            + " bool_and(never_sold) AS never_sold"
            + " FROM aged"
            + " GROUP BY 1"
            + " ORDER BY value DESC, days_idle DESC, group_key ASC"
            + " LIMIT ?";

    var asOfTs = asOf.atOffset(ZoneOffset.UTC);
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          ps.setObject(i++, asOfTs);
          ps.setInt(i, limit);
        },
        StockTurnRepository::mapDeadStockRow,
        "age dead stock");
  }

  private static StockTurnRow mapTurnRow(ResultSet rs) throws SQLException {
    BigDecimal opening = rs.getBigDecimal("opening_value");
    BigDecimal closing = rs.getBigDecimal("closing_value");
    BigDecimal cogs = rs.getBigDecimal("cogs");
    // The ratio and days-on-hand are derived in Java rather than in SQL so the divide-by-zero
    // case can return null. Zero turns and "there was nothing to turn" are different findings,
    // and SQL's NULLIF would collapse both into the same empty cell.
    BigDecimal average =
        opening.add(closing).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
    BigDecimal ratio =
        average.signum() == 0 ? null : cogs.divide(average, 4, java.math.RoundingMode.HALF_UP);
    return new StockTurnRow(
        rs.getString("group_key"),
        cogs,
        rs.getBigDecimal("uncosted_sale_qty"),
        opening,
        closing,
        average,
        ratio,
        null); // daysOnHand needs the window length; the service fills it in.
  }

  private static DeadStockRow mapDeadStockRow(ResultSet rs) throws SQLException {
    // wasNull() reports on the *last* column read, so the age has to be resolved before any
    // other getter runs -- inlining it into the constructor call would have tested the column
    // read immediately before it instead.
    int days = rs.getInt("days_idle");
    Integer daysIdle = rs.wasNull() ? null : days;
    return new DeadStockRow(
        rs.getString("group_key"),
        rs.getBigDecimal("on_hand_qty"),
        rs.getBigDecimal("value"),
        rs.getBigDecimal("uncosted_qty"),
        daysIdle,
        rs.getBoolean("never_sold"));
  }
}
