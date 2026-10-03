package com.storeql.order.repo;

import com.storeql.order.domain.Domain.SalesByHourRow;
import com.storeql.order.domain.Domain.SalesByStaffRow;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sales by hour of day and by member of staff.
 *
 * <p>Separate from {@link OrderRepository} rather than added to it: that class is past 1,800 lines
 * and owns the checkout saga's writes, and these are two read-only aggregates that share nothing
 * with it but the schema.
 *
 * <p>Neither could come from reporting-svc. {@code sales_facts} has no cashier at all, and while it
 * does carry a timestamp, an hour-of-day report needs the order rows themselves to be bucketed in a
 * chosen timezone — see {@link #salesByHour}.
 */
@ApplicationScoped
public class SalesAnalyticsRepository extends BaseJdbcRepository {

  /**
   * Only these two statuses are revenue. A PENDING order has not been paid for, and a CANCELLED or
   * VOIDED one has been unmade; counting either would put a trading peak where none happened.
   */
  private static final String REVENUE_STATUSES =
      " AND status IN ('CONFIRMED','PARTIALLY_FULFILLED','FULFILLED')";

  /**
   * Takings bucketed by hour of the trading day.
   *
   * <p><b>The timezone is the whole report.</b> Everything is stored in UTC (golden rule #14), and
   * bucketing by UTC hour would put a London shop's 9am rush at 09:00 in winter and 08:00 in
   * summer, and an Indian shop's at 03:30 — which is not an hour at all. So the caller names the
   * zone, Postgres does the conversion with {@code AT TIME ZONE}, and the daylight-saving
   * arithmetic is the database's rather than something reimplemented here. The zone is validated as
   * a {@link ZoneId} by the service before it reaches this query, and bound as a parameter
   * regardless.
   *
   * <p><b>A fixed offset is bound as an interval, never as a string, and the difference is not
   * cosmetic.</b> {@code AT TIME ZONE '+04:00'} is read by Postgres under the POSIX convention,
   * where the sign is <em>inverted</em> — it means four hours west of UTC. {@code
   * ZoneId.of("+04:00")} means four hours east, so a fixed offset passed through as text validates
   * cleanly and then buckets every hour eight hours away from where it belongs, with nothing to
   * show for it but a shop being told it is busiest at midnight. {@code AT TIME ZONE <interval>}
   * uses the ISO sign, so the offset is converted to seconds and rebuilt as an interval here.
   * Region names have no such ambiguity and are bound as text.
   *
   * <p>Hours with no trade are absent rather than zero-filled: the caller knows there are 24 of
   * them, and a row of zeroes claims the shop was open and empty, which it may not have been.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param stores the stores a report reads (SJ-D74's {@code reportStores}): {@code null} for every
   *     store in the tenant, else these stores added together — never a store the caller has not
   *     already been checked against
   * @param channel ONLINE or POS to compare the two, or null for both together
   * @param from inclusive lower bound on order time, or null for no lower bound
   * @param to exclusive upper bound, or null for no upper bound
   * @param zone the timezone whose clock the hours are counted on
   * @return one row per hour that traded, earliest hour first
   */
  public List<SalesByHourRow> salesByHour(
      UUID tenantId, Set<UUID> stores, String channel, Instant from, Instant to, ZoneId zone) {
    // A fixed offset (ZoneOffset) goes in as an interval; a region name goes in as text. Both
    // are still bound parameters -- the branch decides the cast, never the value.
    boolean fixedOffset = zone instanceof ZoneOffset;
    String tzExpr = fixedOffset ? "make_interval(secs => ?)" : "?";
    StringBuilder sql =
        new StringBuilder(
            "SELECT EXTRACT(HOUR FROM created_at AT TIME ZONE "
                + tzExpr
                + ")::int AS hour_of_day,"
                + " COUNT(*) AS orders,"
                // Unrounded: the service rounds to the business currency's own minor units.
                + " COALESCE(SUM(total),0) AS gross_amount,"
                + " COALESCE(SUM(discount_amount),0) AS discount_amount"
                + " FROM orders WHERE tenant_id = ?"
                + REVENUE_STATUSES);
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    if (channel != null) sql.append(" AND channel = ?");
    if (from != null) sql.append(" AND created_at >= ?");
    if (to != null) sql.append(" AND created_at < ?");
    sql.append(" GROUP BY 1 ORDER BY 1");

    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          if (fixedOffset) ps.setInt(i++, ((ZoneOffset) zone).getTotalSeconds());
          else ps.setString(i++, zone.getId());
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          if (channel != null) ps.setString(i++, channel);
          bindPeriod(ps, i, from, to);
        },
        SalesAnalyticsRepository::mapHourRow,
        "aggregate sales by hour");
  }

  /**
   * Takings by the cashier who rang them up.
   *
   * <p>Read from {@code pos_log_entries}, the POS transaction journal, and not from {@code orders}:
   * an order row records the store but never the person, so the journal is the only place in this
   * service that knows who served a customer. That makes this a POS-only report by construction —
   * an online order has no cashier — and the resource says so rather than leaving a manager to
   * wonder why the totals do not match the sales summary.
   *
   * <p>It also means the report is only as complete as the journal, which was written by nobody at
   * all until SJ-D17 unlocked it for the one role that completes a sale.
   *
   * @param stores the stores a report reads (SJ-D74's {@code reportStores}): {@code null} for every
   *     store in the tenant, else these stores added together — never a store the caller has not
   *     already been checked against
   * @return one row per cashier, biggest taker first
   */
  public List<SalesByStaffRow> salesByStaff(
      UUID tenantId, Set<UUID> stores, Instant from, Instant to, int limit) {
    StringBuilder sql =
        new StringBuilder(
            // A journal entry with no cashier is bucketed, never dropped: an unattributed sale is
            // a gap in the audit trail, and hiding it would make the report look tidier than the
            // data is.
            "SELECT COALESCE(cashier_id::text, 'UNATTRIBUTED') AS group_key,"
                + " COUNT(*) AS sales,"
                // Unrounded: the service rounds to the business currency's own minor units.
                + " COALESCE(SUM(total),0) AS gross_amount,"
                + " COALESCE(SUM(discount_amount),0) AS discount_amount"
                + " FROM pos_log_entries WHERE tenant_id = ?");
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    if (from != null) sql.append(" AND transaction_ts >= ?");
    if (to != null) sql.append(" AND transaction_ts < ?");
    sql.append(" GROUP BY 1 ORDER BY gross_amount DESC, group_key ASC LIMIT ?");

    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          i = bindPeriod(ps, i, from, to);
          ps.setInt(i, limit);
        },
        SalesAnalyticsRepository::mapStaffRow,
        "aggregate sales by staff");
  }

  /**
   * Binds whichever period bounds were appended, in the order they were, and returns the next
   * index.
   */
  private static int bindPeriod(PreparedStatement ps, int index, Instant from, Instant to)
      throws SQLException {
    int i = index;
    if (from != null) ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
    if (to != null) ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
    return i;
  }

  private static SalesByHourRow mapHourRow(ResultSet rs) throws SQLException {
    return new SalesByHourRow(
        rs.getInt("hour_of_day"),
        rs.getLong("orders"),
        rs.getBigDecimal("gross_amount"),
        rs.getBigDecimal("discount_amount"),
        null); // averageBasket is derived in the service.
  }

  private static SalesByStaffRow mapStaffRow(ResultSet rs) throws SQLException {
    return new SalesByStaffRow(
        rs.getString("group_key"),
        rs.getLong("sales"),
        rs.getBigDecimal("gross_amount"),
        rs.getBigDecimal("discount_amount"),
        null,
        null);
  }
}
