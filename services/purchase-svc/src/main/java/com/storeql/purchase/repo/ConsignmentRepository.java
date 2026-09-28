package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain.ConsignmentSale;
import com.storeql.purchase.domain.Domain.ConsignmentSettlement;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consignment sales as inventory-svc announced them, and the settlements that gather them. Every
 * statement filters by {@code tenant_id} first. A sale row is written once and touched once more,
 * by the settlement that took it.
 */
@ApplicationScoped
public class ConsignmentRepository extends BaseJdbcRepository {

  private static final String SALE_COLUMNS =
      "SELECT id, tenant_id, event_id, supplier_id, store_id, variant_id, batch_id, order_id, qty,"
          + " unit_cost, amount, currency, sold_on, settlement_id, recorded_at"
          + " FROM consignment_sales WHERE tenant_id = ?";

  private static final String SETTLEMENT_COLUMNS =
      "SELECT id, tenant_id, supplier_id, reference, period_from, period_to, currency, total,"
          + " sales_count, created_by, created_at FROM consignment_settlements WHERE tenant_id = ?";

  /**
   * Records a sale inventory-svc announced, with the journal that owes the supplier for it, once:
   * the event is marked processed, the row is keyed by the event, and both commit together.
   *
   * @return whether the sale was recorded now; false when it already was
   */
  public boolean recordSaleOnce(
      UUID eventId, String consumer, ConsignmentSale sale, List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO consignment_sales (id, tenant_id, event_id, supplier_id, store_id,"
                      + " variant_id, batch_id, order_id, qty, unit_cost, amount, currency,"
                      + " sold_on, recorded_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, event_id) DO NOTHING")) {
            ps.setObject(1, sale.id());
            ps.setObject(2, sale.tenantId());
            ps.setObject(3, sale.eventId());
            ps.setObject(4, sale.supplierId());
            ps.setObject(5, sale.storeId());
            ps.setObject(6, sale.variantId());
            ps.setObject(7, sale.batchId());
            ps.setObject(8, sale.orderId());
            ps.setBigDecimal(9, sale.qty());
            ps.setBigDecimal(10, sale.unitCost());
            ps.setBigDecimal(11, sale.amount());
            ps.setString(12, sale.currency());
            ps.setObject(13, sale.soldOn());
            ps.setObject(14, sale.recordedAt().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() == 0) return false;
          }
          LedgerWriter.insert(c, posting);
          return true;
        },
        "record consignment sale");
  }

  public List<ConsignmentSale> findSales(
      UUID tenantId, UUID supplierId, Boolean settled, int limit) {
    StringBuilder sql = new StringBuilder(SALE_COLUMNS);
    if (supplierId != null) sql.append(" AND supplier_id = ?");
    if (settled != null)
      sql.append(settled ? " AND settlement_id IS NOT NULL" : " AND settlement_id IS NULL");
    sql.append(" ORDER BY sold_on DESC, recorded_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (supplierId != null) ps.setObject(i++, supplierId);
          ps.setInt(i, limit);
        },
        ConsignmentRepository::mapSale,
        "list consignment sales");
  }

  public List<ConsignmentSale> findSalesOfSettlement(UUID tenantId, UUID settlementId) {
    return query(
        SALE_COLUMNS + " AND settlement_id = ? ORDER BY sold_on, recorded_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, settlementId);
        },
        ConsignmentRepository::mapSale,
        "list a settlement's sales");
  }

  /**
   * Opens a settlement and takes into it every unsettled sale of the supplier sold within the
   * period, on one transaction; the statement's total is what those sales add up to.
   *
   * @throws ApiException 409 {@code PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE} when no sale is left to
   *     settle in the period, in which case nothing is written
   */
  public ConsignmentSettlement settle(ConsignmentSettlement s) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO consignment_settlements (id, tenant_id, supplier_id, reference,"
                      + " period_from, period_to, currency, total, sales_count, created_by,"
                      + " created_at) VALUES (?,?,?,?,?,?,?,0,0,?,?)")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.supplierId());
            ps.setString(4, s.reference());
            ps.setObject(5, s.periodFrom());
            ps.setObject(6, s.periodTo());
            ps.setString(7, s.currency());
            ps.setObject(8, s.createdBy());
            ps.setObject(9, s.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          BigDecimal total = BigDecimal.ZERO;
          int count = 0;
          try (var ps =
              c.prepareStatement(
                  "UPDATE consignment_sales SET settlement_id = ?"
                      + " WHERE tenant_id = ? AND supplier_id = ? AND settlement_id IS NULL"
                      + " AND sold_on >= ? AND sold_on <= ? AND currency = ?"
                      + " RETURNING amount")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.supplierId());
            ps.setObject(4, s.periodFrom());
            ps.setObject(5, s.periodTo());
            ps.setString(6, s.currency());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                total = total.add(rs.getBigDecimal("amount"));
                count++;
              }
            }
          }
          if (count == 0) {
            throw ApiException.conflict(
                "PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE",
                "no unsettled consignment sale of this supplier between "
                    + s.periodFrom()
                    + " and "
                    + s.periodTo());
          }
          try (var ps =
              c.prepareStatement(
                  "UPDATE consignment_settlements SET total = ?, sales_count = ?"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setBigDecimal(1, total);
            ps.setInt(2, count);
            ps.setObject(3, s.tenantId());
            ps.setObject(4, s.id());
            ps.executeUpdate();
          }
          return new ConsignmentSettlement(
              s.id(),
              s.tenantId(),
              s.supplierId(),
              s.reference(),
              s.periodFrom(),
              s.periodTo(),
              s.currency(),
              total,
              count,
              s.createdBy(),
              s.createdAt());
        },
        "settle consignment sales");
  }

  public List<ConsignmentSettlement> findSettlements(UUID tenantId, UUID supplierId, int limit) {
    StringBuilder sql = new StringBuilder(SETTLEMENT_COLUMNS);
    if (supplierId != null) sql.append(" AND supplier_id = ?");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (supplierId != null) ps.setObject(i++, supplierId);
          ps.setInt(i, limit);
        },
        ConsignmentRepository::mapSettlement,
        "list consignment settlements");
  }

  public Optional<ConsignmentSettlement> findSettlement(UUID tenantId, UUID id) {
    var rows =
        query(
            SETTLEMENT_COLUMNS + " AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ConsignmentRepository::mapSettlement,
            "find consignment settlement");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static ConsignmentSale mapSale(ResultSet rs) throws SQLException {
    return new ConsignmentSale(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("unit_cost"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getObject("sold_on", LocalDate.class),
        rs.getObject("settlement_id", UUID.class),
        instant(rs.getObject("recorded_at", OffsetDateTime.class)));
  }

  private static ConsignmentSettlement mapSettlement(ResultSet rs) throws SQLException {
    return new ConsignmentSettlement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getString("reference"),
        rs.getObject("period_from", LocalDate.class),
        rs.getObject("period_to", LocalDate.class),
        rs.getString("currency"),
        rs.getBigDecimal("total"),
        rs.getInt("sales_count"),
        rs.getObject("created_by", UUID.class),
        instant(rs.getObject("created_at", OffsetDateTime.class)));
  }

  private static Instant instant(OffsetDateTime at) {
    return at == null ? null : at.toInstant();
  }
}
