package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Domain.FxRate;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** The business's exchange rates (03.x): append-only rows, the current one per currency. */
@ApplicationScoped
public class FxRateRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, currency, rate, effective_from, reason, set_by, set_at";

  /**
   * The rate in force per currency on {@code today}: the latest effective date that has arrived,
   * and the latest set when a day was set twice.
   */
  public List<FxRate> current(UUID tenantId, LocalDate today) {
    return query(
        "SELECT DISTINCT ON (currency) "
            + COLUMNS
            + " FROM fx_rates WHERE tenant_id = ? AND effective_from <= ?"
            + " ORDER BY currency, effective_from DESC, set_at DESC, id DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, today);
        },
        FxRateRepository::map,
        "current exchange rates");
  }

  /** Every rate ever set for one currency, newest first. */
  public List<FxRate> history(UUID tenantId, String currency) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM fx_rates WHERE tenant_id = ? AND currency = ?"
            + " ORDER BY effective_from DESC, set_at DESC, id DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, currency);
        },
        FxRateRepository::map,
        "exchange rate history");
  }

  public FxRate insert(FxRate r) {
    exec(
        "INSERT INTO fx_rates (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, r.id());
          ps.setObject(2, r.tenantId());
          ps.setString(3, r.currency());
          ps.setBigDecimal(4, r.rate());
          ps.setObject(5, r.effectiveFrom());
          ps.setString(6, r.reason());
          ps.setObject(7, r.setBy());
          ps.setObject(8, r.setAt().atOffset(ZoneOffset.UTC));
        },
        "set exchange rate");
    return r;
  }

  private static FxRate map(ResultSet rs) throws SQLException {
    return new FxRate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("currency"),
        rs.getBigDecimal("rate"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("reason"),
        rs.getObject("set_by", UUID.class),
        rs.getObject("set_at", OffsetDateTime.class).toInstant());
  }
}
