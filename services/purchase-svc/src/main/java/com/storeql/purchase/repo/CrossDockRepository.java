package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain.LineAllocation;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A warehouse order's cross-dock allocations (intent/cross-docking.md): which shop each part of a
 * line is for. Every query filters by {@code tenant_id} first.
 */
@ApplicationScoped
public class CrossDockRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "a.id, a.tenant_id, a.po_id, a.po_line_id, l.variant_id, a.store_id, a.qty, a.created_by,"
          + " a.created_at";

  /** Replaces a line's allocations with those given, on one transaction. */
  public List<LineAllocation> replace(
      UUID tenantId, UUID poId, UUID lineId, List<LineAllocation> allocations) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM purchase_order_line_allocations WHERE tenant_id = ? AND po_line_id"
                      + " = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, lineId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO purchase_order_line_allocations (id, tenant_id, po_id, po_line_id,"
                      + " store_id, qty, created_by, created_at) VALUES (?,?,?,?,?,?,?,now())")) {
            for (LineAllocation a : allocations) {
              ps.setObject(1, a.id());
              ps.setObject(2, tenantId);
              ps.setObject(3, poId);
              ps.setObject(4, lineId);
              ps.setObject(5, a.storeId());
              ps.setBigDecimal(6, a.qty());
              ps.setObject(7, a.createdBy());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return allocationsTx(c, tenantId, poId);
        },
        "replace line allocations");
  }

  /** The order's allocations, every line. */
  public List<LineAllocation> byOrder(UUID tenantId, UUID poId) {
    return inTx(c -> allocationsTx(c, tenantId, poId), "list order allocations");
  }

  /** The order's allocations on the caller's transaction. */
  static List<LineAllocation> allocationsTx(Connection c, UUID tenantId, UUID poId)
      throws SQLException {
    List<LineAllocation> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM purchase_order_line_allocations a JOIN purchase_order_lines l ON"
                + " l.tenant_id = a.tenant_id AND l.id = a.po_line_id WHERE a.tenant_id = ? AND"
                + " a.po_id = ? ORDER BY l.created_at, a.store_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, poId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new LineAllocation(
                  rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("po_id", UUID.class),
                  rs.getObject("po_line_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getObject("store_id", UUID.class),
                  rs.getBigDecimal("qty"),
                  rs.getObject("created_by", UUID.class),
                  rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        }
      }
    }
    return out;
  }

  /**
   * What is allocated to shops on a store's open orders, per product: on order at a warehouse, but
   * the shops', not the warehouse's.
   */
  public Map<UUID, BigDecimal> allocatedOnOrder(UUID tenantId, UUID storeId) {
    Map<UUID, BigDecimal> out = new HashMap<>();
    query(
        "SELECT l.variant_id, SUM(a.qty) AS qty FROM purchase_order_line_allocations a JOIN"
            + " purchase_order_lines l ON l.tenant_id = a.tenant_id AND l.id = a.po_line_id JOIN"
            + " purchase_orders p ON p.tenant_id = a.tenant_id AND p.id = a.po_id WHERE a.tenant_id"
            + " = ? AND p.store_id = ? AND p.status IN "
            + ProposalRepository.OPEN_STATUSES
            + " GROUP BY l.variant_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        rs -> {
          out.put(rs.getObject("variant_id", UUID.class), rs.getBigDecimal("qty"));
          return null;
        },
        "allocated on order");
    return out;
  }
}
