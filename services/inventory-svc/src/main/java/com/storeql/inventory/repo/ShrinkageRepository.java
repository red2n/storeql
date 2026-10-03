package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.ShrinkageGrouping;
import com.storeql.inventory.domain.Domain.ShrinkageRow;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregates stock write-offs for the shrinkage report.
 *
 * <p>Reads {@code stock_movements} directly rather than going through reporting-svc. That service
 * owns cross-domain reporting, but its {@code movement_events} projection carries only tenant,
 * store, variant, type and quantity — not the reason code or the actor, which are the two columns
 * this report exists to group by. Shrinkage is also a purely inventory-domain question, so the data
 * needed to answer it is entirely inside this service's own schema (golden rule #1 constrains
 * reading *other* services' tables, not reporting on your own).
 *
 * <p>Aggregating from the movement ledger rather than from a separate projection also means the
 * report covers every adjustment path from the day it ships — manual write-offs, cycle-count
 * variances and lot merges all write here, whereas only the manual path publishes a {@code
 * StockAdjusted} event.
 */
@ApplicationScoped
public class ShrinkageRepository extends BaseJdbcRepository {

  /**
   * Sums adjustment movements over a period, grouped as requested.
   *
   * <p>Losses and gains are reported separately rather than only as a net figure: a store that
   * wrote off 100 units and found 100 others is not the same as a store that did nothing, and
   * netting them to zero would hide exactly the pattern an investigation looks for.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param stores restrict to these stores, or null for every store in the tenant
   * @param from inclusive lower bound on movement time, or null for no lower bound
   * @param to exclusive upper bound on movement time, or null for no upper bound
   * @param grouping which column to group by — an enum, never caller-supplied SQL
   * @return one row per group, heaviest write-off first
   */
  public List<ShrinkageRow> aggregate(
      UUID tenantId, Set<UUID> stores, Instant from, Instant to, ShrinkageGrouping grouping) {
    // The grouped expression comes from the enum, never from request text, so it cannot carry
    // caller input into the statement; every value below is still bound as a parameter.
    String groupExpr =
        switch (grouping) {
          case REASON -> "COALESCE(reason_code, 'UNSPECIFIED')";
          case ACTOR -> "COALESCE(actor_id::text, 'SYSTEM')";
          case STORE -> "store_id::text";
        };

    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + groupExpr
                + " AS group_key,"
                // Cast to the column's own scale: a group whose rows all take the ELSE branch
                // sums the literal 0 and would otherwise come back as "0" where every other row
                // reports "0.000", handing clients inconsistent precision for the same field.
                + " SUM(CASE WHEN qty < 0 THEN -qty ELSE 0 END)::numeric(18,3) AS qty_written_off,"
                + " SUM(CASE WHEN qty > 0 THEN qty ELSE 0 END)::numeric(18,3) AS qty_found,"
                + " SUM(qty)::numeric(18,3) AS net_qty,"
                + " COUNT(*) AS movements"
                + " FROM stock_movements"
                + " WHERE tenant_id = ? AND type = 'ADJUST'");
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    if (from != null) sql.append(" AND created_at >= ?");
    if (to != null) sql.append(" AND created_at < ?");
    sql.append(" GROUP BY 1 ORDER BY qty_written_off DESC, group_key ASC");

    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          if (from != null) ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
          if (to != null) ps.setObject(i, to.atOffset(ZoneOffset.UTC));
        },
        ShrinkageRepository::mapRow,
        "aggregate shrinkage");
  }

  /**
   * The individual write-offs behind a summary row, so an investigation can go from "CASHIER X
   * wrote off 400 units" to the specific movements without leaving the report.
   *
   * @param reasonCode restrict to one reason code, or null for any
   * @param actorId restrict to one actor, or null for any
   * @param limit maximum rows to return, already clamped by the caller
   */
  public List<ShrinkageRow> topVariants(
      UUID tenantId,
      Set<UUID> stores,
      Instant from,
      Instant to,
      String reasonCode,
      UUID actorId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT variant_id::text AS group_key,"
                // Cast to the column's own scale: a group whose rows all take the ELSE branch
                // sums the literal 0 and would otherwise come back as "0" where every other row
                // reports "0.000", handing clients inconsistent precision for the same field.
                + " SUM(CASE WHEN qty < 0 THEN -qty ELSE 0 END)::numeric(18,3) AS qty_written_off,"
                + " SUM(CASE WHEN qty > 0 THEN qty ELSE 0 END)::numeric(18,3) AS qty_found,"
                + " SUM(qty)::numeric(18,3) AS net_qty,"
                + " COUNT(*) AS movements"
                + " FROM stock_movements"
                + " WHERE tenant_id = ? AND type = 'ADJUST'");
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    if (from != null) sql.append(" AND created_at >= ?");
    if (to != null) sql.append(" AND created_at < ?");
    if (reasonCode != null) sql.append(" AND reason_code = ?");
    if (actorId != null) sql.append(" AND actor_id = ?");
    sql.append(" GROUP BY 1 ORDER BY qty_written_off DESC, group_key ASC LIMIT ?");

    // A UUID[] marks the stores filter so the bind loop below knows to bind it as a SQL array
    // rather than a scalar parameter -- every other bind here is a plain scalar.
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    if (stores != null) binds.add(stores.toArray(new UUID[0]));
    if (from != null) binds.add(from.atOffset(ZoneOffset.UTC));
    if (to != null) binds.add(to.atOffset(ZoneOffset.UTC));
    if (reasonCode != null) binds.add(reasonCode);
    if (actorId != null) binds.add(actorId);
    binds.add(limit);

    return query(
        sql.toString(),
        ps -> {
          for (int i = 0; i < binds.size(); i++) {
            Object bind = binds.get(i);
            if (bind instanceof UUID[] ids) {
              ps.setArray(i + 1, ps.getConnection().createArrayOf("uuid", ids));
            } else {
              ps.setObject(i + 1, bind);
            }
          }
        },
        ShrinkageRepository::mapRow,
        "list shrinkage by variant");
  }

  private static ShrinkageRow mapRow(ResultSet rs) throws SQLException {
    return new ShrinkageRow(
        rs.getString("group_key"),
        rs.getBigDecimal("qty_written_off"),
        rs.getBigDecimal("qty_found"),
        rs.getBigDecimal("net_qty"),
        rs.getLong("movements"));
  }
}
