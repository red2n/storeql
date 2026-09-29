package com.storeql.pricing.repo;

import com.storeql.pricing.domain.Domain.TaxGrouping;
import com.storeql.pricing.domain.Domain.TaxSummaryRow;
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
 * Aggregates {@code tax_transactions} for the tax summary report.
 *
 * <p>Kept apart from {@link PricingRepository}, which is already 680 lines of pricing, VAT-rate and
 * promotion CRUD and has no reason to change when a report does (SRP). Reads this service's own
 * table: the tax journal is written here on every priced line, so the question is entirely inside
 * pricing-svc's schema.
 *
 * <p>The same table backs the MTD VAT return, which is the point — the summary is the working
 * behind boxes 1 and 6, computed from the identical rows over the identical period, so an
 * accountant can tie the two together instead of taking a single number on trust.
 */
@ApplicationScoped
public class TaxReportRepository extends BaseJdbcRepository {

  /**
   * Sums tax transactions over a period, grouped as requested.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param stores restrict to these stores, or {@code null} for every store in the tenant (a caller
   *     held to no store, or an OWNER/PLATFORM_ADMIN) — never a store the caller cannot act in,
   *     which {@link com.storeql.web.TenantContext#reportStores} has already checked
   * @param from inclusive lower bound on the tax point
   * @param to exclusive upper bound on the tax point
   * @param grouping which column to group by — an enum, never caller-supplied SQL
   * @return one row per (group, exempt) pair, largest VAT first
   */
  public List<TaxSummaryRow> aggregate(
      UUID tenantId, Set<UUID> stores, Instant from, Instant to, TaxGrouping grouping) {
    // The grouped expression comes from the enum, never from request text, so it cannot carry
    // caller input into the statement; every value below is still bound as a parameter.
    String groupExpr =
        switch (grouping) {
          case CODE -> "vat_code";
          case STORE -> "store_id::text";
          // UTC, like every other timestamp the platform reports on (golden rule #14). A VAT
          // period that closes at local midnight is a business decision nothing here has made,
          // and quietly picking a server timezone would move supplies between months.
          case MONTH -> "to_char(tax_point_date AT TIME ZONE 'UTC', 'YYYY-MM')";
        };

    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + groupExpr
                + " AS group_key, exempt,"
                // Cast to the column's own scale so every row reports the same precision,
                // whatever the group summed.
                + " SUM(net_amount)::numeric(18,2) AS net_amount,"
                + " SUM(vat_amount)::numeric(18,2) AS vat_amount,"
                + " SUM(gross_amount)::numeric(18,2) AS gross_amount,"
                + " COUNT(*) AS transactions"
                + " FROM tax_transactions"
                + " WHERE tenant_id = ? AND tax_point_date >= ? AND tax_point_date < ?");
    // No set (a caller held to no store) reads every store; otherwise only the caller's own.
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    // No LIMIT: the grouping is bounded by design — a tenant's VAT codes, its stores, or the
    // months in the requested period. Unlike a by-variant drill-down, this cannot run away.
    sql.append(" GROUP BY 1, exempt ORDER BY vat_amount DESC, group_key ASC");

    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
          ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
          if (stores != null) {
            ps.setArray(i, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
        },
        TaxReportRepository::mapRow,
        "aggregate tax summary");
  }

  private static TaxSummaryRow mapRow(ResultSet rs) throws SQLException {
    return new TaxSummaryRow(
        rs.getString("group_key"),
        rs.getBoolean("exempt"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getLong("transactions"));
  }
}
