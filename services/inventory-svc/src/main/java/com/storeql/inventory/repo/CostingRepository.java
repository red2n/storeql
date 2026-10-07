package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.AccountingPeriod;
import com.storeql.inventory.domain.Domain.CostingMethod;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Costing methods (Gap #17) and accounting periods — bundled together as they were in the original
 * section (both are the same "closing the books" family of config, and neither is large enough
 * alone to warrant its own file). Extracted from {@code InventoryRepository}: self-contained.
 */
@ApplicationScoped
public class CostingRepository extends BaseOutboxRepository {

  /**
   * Sets the costing method, and the standard cost the AVERAGE method uses.
   *
   * <p>{@code average_cost} previously had no writer at all: this statement set only {@code
   * method}, nothing recomputed the column from receipts, and the request DTO had no field for it —
   * so it sat at its schema default of 0 forever and choosing AVERAGE silently did nothing. The
   * valuation report is what surfaced it, since an AVERAGE row with a zero cost cannot be valued.
   *
   * <p>A null {@code averageCost} leaves the stored value alone rather than resetting it to zero,
   * so changing method between FIFO and AVERAGE does not discard a cost the operator set earlier.
   * The casts are needed because Postgres cannot infer a bare parameter's type inside COALESCE.
   *
   * @param averageCost the standard unit cost for AVERAGE costing, or null to leave it unchanged
   */
  public CostingMethod upsertCostingMethod(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String method,
      BigDecimal averageCost,
      OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO costing_methods"
                  + " (id, tenant_id, store_id, variant_id, method, average_cost)"
                  + " VALUES (?,?,?,?,?,COALESCE(?::numeric,0))"
                  + " ON CONFLICT (tenant_id, store_id, variant_id)"
                  + " DO UPDATE SET method=EXCLUDED.method,"
                  + "   average_cost=COALESCE(?::numeric, costing_methods.average_cost),"
                  + "   updated_at=now()"
                  + " RETURNING id, tenant_id, store_id, variant_id, method, average_cost, updated_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, variantId);
            ps.setString(5, method);
            ps.setBigDecimal(6, averageCost);
            ps.setBigDecimal(7, averageCost);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.unprocessable(
                    "COSTING_METHOD_ERROR", "upsert costing method returned nothing");
              CostingMethod cm = mapCostingMethod(rs);
              insertOutbox(c, event);
              return cm;
            }
          }
        },
        "upsert costing method");
  }

  /**
   * Looks a costing method up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the costing method, or empty when it does not exist in this tenant
   */
  public Optional<CostingMethod> findCostingMethod(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, method, average_cost, updated_at"
                + " FROM costing_methods WHERE tenant_id=? AND store_id=? AND variant_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            CostingRepository::mapCostingMethod,
            "find costing method")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's costing methods.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @return the matching rows
   */
  public List<CostingMethod> listCostingMethods(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, method, average_cost, updated_at"
            + " FROM costing_methods WHERE tenant_id=? AND store_id=? ORDER BY updated_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        CostingRepository::mapCostingMethod,
        "list costing methods");
  }

  /**
   * Opens an accounting period and writes its event — atomically.
   *
   * @param tenantId owning tenant
   * @param storeId the store the period belongs to
   * @param periodName the period's display name
   * @param periodDate the period's date
   * @param event the outbox row to commit alongside
   * @return the opened period
   */
  public AccountingPeriod openPeriod(
      UUID tenantId, UUID storeId, String periodName, LocalDate periodDate, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO accounting_periods (id, tenant_id, store_id, period_name, period_date)"
                  + " VALUES (?,?,?,?,?)"
                  + " RETURNING id, tenant_id, store_id, period_name, period_date,"
                  + "   status, opened_at, closed_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setString(4, periodName);
            ps.setObject(5, Date.valueOf(periodDate));
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.unprocessable(
                    "PERIOD_OPEN_ERROR", "open period returned nothing");
              AccountingPeriod ap = mapPeriod(rs);
              insertOutbox(c, event);
              return ap;
            } catch (SQLException sqle) {
              if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
                throw new ApiException(
                    409,
                    "PERIOD_DUPLICATE_DATE",
                    "a period already exists for this date",
                    List.of(),
                    sqle);
              throw sqle;
            }
          }
        },
        "open accounting period");
  }

  /**
   * Closes an open accounting period and writes its event — atomically.
   *
   * <p>Guarded on {@code status='OPEN'}, so closing twice cannot re-stamp the closing time.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param periodId the period to close
   * @param event the outbox row to commit alongside
   * @return the closed period
   */
  public AccountingPeriod closePeriod(UUID tenantId, UUID periodId, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE accounting_periods SET status='CLOSED', closed_at=now()"
                  + " WHERE tenant_id=? AND id=? AND status='OPEN'"
                  + " RETURNING id, tenant_id, store_id, period_name, period_date,"
                  + "   status, opened_at, closed_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, periodId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.conflict(
                    "PERIOD_NOT_OPEN", "period not found or already closed");
              AccountingPeriod ap = mapPeriod(rs);
              insertOutbox(c, event);
              return ap;
            }
          }
        },
        "close accounting period");
  }

  /**
   * Looks a period up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param periodId the period id
   * @return the period, or empty when it does not exist in this tenant
   */
  public Optional<AccountingPeriod> findPeriod(UUID tenantId, UUID periodId) {
    return query(
            "SELECT id, tenant_id, store_id, period_name, period_date, status, opened_at, closed_at"
                + " FROM accounting_periods WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, periodId);
            },
            CostingRepository::mapPeriod,
            "find period")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's periods.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @return the matching rows
   */
  public List<AccountingPeriod> listPeriods(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, period_name, period_date, status, opened_at, closed_at"
            + " FROM accounting_periods WHERE tenant_id=? AND store_id=? ORDER BY period_date DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        CostingRepository::mapPeriod,
        "list periods");
  }

  private static CostingMethod mapCostingMethod(ResultSet rs) throws SQLException {
    return new CostingMethod(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("method"),
        rs.getBigDecimal("average_cost"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static AccountingPeriod mapPeriod(ResultSet rs) throws SQLException {
    OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new AccountingPeriod(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("period_name"),
        rs.getObject("period_date", Date.class).toLocalDate(),
        rs.getString("status"),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant());
  }
}
