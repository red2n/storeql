package com.storeql.payment.repo;

import com.storeql.payment.domain.Domain.TenderMixRow;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * How customers actually paid — the tender-mix report.
 *
 * <p>Reads {@code payment_tenders} and {@code refund_tenders}, both owned by this service. It could
 * not have been built in reporting-svc: {@code sales_facts} carries one gross amount per order and
 * no tender at all, so a split payment of £20 cash and £30 card is a single £50 row there.
 *
 * <p><b>Refunds are subtracted per method, not netted globally.</b> A £100 card sale refunded to
 * store credit is not a £0 card day — the card processor still settled £100 and the store still
 * carries £100 of credit. Keeping the two sides visible per method is the difference between a
 * report you can reconcile a merchant statement against and one you cannot.
 *
 * <p><b>Failures are counted, not hidden.</b> A method whose captures are fine but whose failure
 * count is climbing is a terminal or an acquirer problem, and this is the only report where that
 * shows up next to the volume it is costing.
 */
@ApplicationScoped
public class TenderMixRepository extends BaseJdbcRepository {

  /**
   * Captured and refunded amounts per tender method over a window.
   *
   * <p>The two tables are combined with UNION ALL into one pass rather than joined: a method with
   * refunds but no captures in the window, and one with captures but no refunds, both have to
   * appear, and neither an inner nor an outer join gives that symmetrically.
   *
   * <p>Amounts are summed across whatever currencies the rows carry, because they carry none —
   * neither table has a currency column, and since SJ-D2 a tenant trades in exactly one declared
   * currency. If per-tenant multi-currency ever arrives, this is one of the places that has to
   * learn about it.
   *
   * <p>{@code refund_tenders.store_id} is written by every refund path (the payment's store, an
   * exchange's own, or for a card put back through a terminal the store of the sale it reverses)
   * and back-filled for older rows (V12, and once more by V20 when the fallback went), so a
   * refund's store is its own and is read from the refund alone — as the settlement matcher reads
   * it. A refund of a payment taken with no store has none, and is counted only when every store is
   * being read.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param stores restrict to these stores, or {@code null} for every store in the tenant (a caller
   *     held to no store, or an OWNER/PLATFORM_ADMIN) — never a store the caller cannot act in,
   *     which {@link com.storeql.web.TenantContext#reportStores} has already checked
   * @param from inclusive lower bound on tender time, or null for no lower bound
   * @param to exclusive upper bound, or null for no upper bound
   * @return one row per method, largest net first
   */
  public List<TenderMixRow> tenderMix(UUID tenantId, Set<UUID> stores, Instant from, Instant to) {
    // Each side contributes signed columns so the outer aggregate is a plain SUM. Written this
    // way rather than as two subqueries joined on method so that a method appearing on only one
    // side still produces a row.
    //
    // The store filter is appended only when there is one to apply: pgjdbc has no SQL type to
    // infer for a bound null array, and "CAST(? AS uuid[]) IS NULL OR ..." with a setNull throws
    // rather than matching every row, so a caller held to no store gets the plain, unfiltered
    // clause instead — the same shape the window clauses already use below.
    String storeFilter = stores == null ? "" : " AND store_id = ANY(?)";
    String refundStoreFilter = stores == null ? "" : " AND r.store_id = ANY(?)";
    String window =
        (from != null ? " AND created_at >= ?" : "") + (to != null ? " AND created_at < ?" : "");
    String refundWindow =
        (from != null ? " AND r.created_at >= ?" : "")
            + (to != null ? " AND r.created_at < ?" : "");

    String sql =
        "SELECT method,"
            + " SUM(captured)::numeric(14,4) AS captured_amount,"
            + " SUM(captured_n) AS captured_count,"
            + " SUM(refunded)::numeric(14,4) AS refunded_amount,"
            + " SUM(refunded_n) AS refunded_count,"
            + " SUM(failed_n) AS failed_count"
            + " FROM ("
            + "   SELECT method,"
            + "          CASE WHEN status = 'CAPTURED' THEN amount ELSE 0 END AS captured,"
            + "          CASE WHEN status = 'CAPTURED' THEN 1 ELSE 0 END AS captured_n,"
            + "          0 AS refunded, 0 AS refunded_n,"
            + "          CASE WHEN status <> 'CAPTURED' THEN 1 ELSE 0 END AS failed_n"
            + "     FROM payment_tenders"
            + "    WHERE tenant_id = ?"
            // Absent (a caller held to no store) matches every row; otherwise only the caller's
            // own stores — never a store named that access has not already checked.
            + storeFilter
            + window
            + "   UNION ALL"
            // refund_tenders has no status column: a row here is a refund that happened. Its own
            // store_id is the store (see the javadoc).
            + "   SELECT r.method, 0, 0, r.amount, 1, 0"
            + "     FROM refund_tenders r"
            + "    WHERE r.tenant_id = ?"
            + refundStoreFilter
            + refundWindow
            + " ) agg"
            + " GROUP BY method"
            + " ORDER BY (SUM(captured) - SUM(refunded)) DESC, method ASC";

    var fromTs = from == null ? null : from.atOffset(ZoneOffset.UTC);
    var toTs = to == null ? null : to.atOffset(ZoneOffset.UTC);
    return query(
        sql,
        ps -> {
          int i = 1;
          // The same tenant, store filter and window bind twice, once per side of the UNION.
          for (int side = 0; side < 2; side++) {
            ps.setObject(i++, tenantId);
            if (stores != null) {
              ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
            }
            if (fromTs != null) ps.setObject(i++, fromTs);
            if (toTs != null) ps.setObject(i++, toTs);
          }
        },
        TenderMixRepository::mapRow,
        "aggregate tender mix");
  }

  private static TenderMixRow mapRow(ResultSet rs) throws SQLException {
    return new TenderMixRow(
        rs.getString("method"),
        rs.getBigDecimal("captured_amount"),
        rs.getLong("captured_count"),
        rs.getBigDecimal("refunded_amount"),
        rs.getLong("refunded_count"),
        rs.getLong("failed_count"),
        // Net and share are filled in by the service: share needs every row's total, which no
        // single row can see.
        null,
        null);
  }
}
