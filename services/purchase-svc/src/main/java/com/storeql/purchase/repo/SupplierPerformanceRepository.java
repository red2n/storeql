package com.storeql.purchase.repo;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.SupplierScorecard;
import com.storeql.purchase.domain.SupplierScorecard.Deliveries;
import com.storeql.purchase.domain.SupplierScorecard.Delivery;
import com.storeql.purchase.domain.SupplierScorecard.Fill;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The facts a supplier's scorecard is made from: each delivery as measured on its receipt, and the
 * period's fill, returns and invoice matches per supplier. Every statement filters by {@code
 * tenant_id} first; nothing here reaches another service's tables.
 */
@ApplicationScoped
public class SupplierPerformanceRepository extends BaseJdbcRepository {

  /** Returns and what went back, per supplier, before the rate is worked out. */
  public record Returns(int returns, BigDecimal returnedQty) {}

  /** Invoices captured and how many did not match, per supplier. */
  public record InvoiceMatches(int invoices, int flagged) {}

  // ── The fact, kept with the receipt ────────────────────────────────────────

  /**
   * Measures the delivery a receipt books against the order's promise and keeps it, on the
   * receipt's own transaction. An order nobody can find (impossible under the receipt's row lock)
   * leaves no fact rather than failing the receipt.
   */
  static void recordDeliveryTx(
      Connection c, GoodsReceipt gr, List<GoodsReceiptLine> lines, boolean complete)
      throws SQLException {
    UUID supplierId;
    Instant orderedAt;
    LocalDate expected;
    Integer quoted;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT p.supplier_id, COALESCE(p.submitted_at, p.created_at) AS ordered_at,"
                + " p.expected_delivery, s.lead_time_days"
                + " FROM purchase_orders p JOIN suppliers s"
                + " ON s.tenant_id = p.tenant_id AND s.id = p.supplier_id"
                + " WHERE p.tenant_id = ? AND p.id = ?")) {
      ps.setObject(1, gr.tenantId());
      ps.setObject(2, gr.poId());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return;
        supplierId = rs.getObject("supplier_id", UUID.class);
        orderedAt = rs.getObject("ordered_at", OffsetDateTime.class).toInstant();
        expected = rs.getObject("expected_delivery", LocalDate.class);
        int days = rs.getInt("lead_time_days");
        quoted = rs.wasNull() ? null : days;
      }
    }
    BigDecimal qty = BigDecimal.ZERO;
    for (GoodsReceiptLine l : lines) qty = qty.add(l.qtyReceived());
    LocalDate promised = SupplierScorecard.promise(expected, orderedAt, quoted);
    Delivery d =
        new Delivery(
            Ids.newId(),
            gr.tenantId(),
            supplierId,
            gr.poId(),
            gr.id(),
            gr.storeId(),
            orderedAt,
            promised,
            gr.receivedAt(),
            SupplierScorecard.leadDays(orderedAt, gr.receivedAt()),
            SupplierScorecard.lateDays(promised, gr.receivedAt()),
            complete,
            qty);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO supplier_deliveries (id, tenant_id, supplier_id, po_id, gr_id, store_id,"
                + " ordered_at, promised_date, received_at, lead_days, late_days, complete,"
                + " received_qty) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, d.id());
      ps.setObject(2, d.tenantId());
      ps.setObject(3, d.supplierId());
      ps.setObject(4, d.poId());
      ps.setObject(5, d.grId());
      ps.setObject(6, d.storeId());
      ps.setObject(7, d.orderedAt().atOffset(ZoneOffset.UTC));
      ps.setObject(8, d.promisedDate());
      ps.setObject(9, d.receivedAt().atOffset(ZoneOffset.UTC));
      ps.setInt(10, d.leadDays());
      if (d.lateDays() == null) ps.setNull(11, java.sql.Types.INTEGER);
      else ps.setInt(11, d.lateDays());
      ps.setBoolean(12, d.complete());
      ps.setBigDecimal(13, d.receivedQty());
      ps.executeUpdate();
    }
  }

  // ── Reads ──────────────────────────────────────────────────────────────────

  /** A supplier's deliveries in the period, newest first. */
  public List<Delivery> deliveries(UUID tenantId, UUID supplierId, LocalDate from, LocalDate to) {
    return query(
        "SELECT id, tenant_id, supplier_id, po_id, gr_id, store_id, ordered_at, promised_date,"
            + " received_at, lead_days, late_days, complete, received_qty FROM supplier_deliveries"
            + " WHERE tenant_id = ? AND supplier_id = ?"
            + " AND received_at >= ?::date AND received_at < (?::date + 1)"
            + " ORDER BY received_at DESC, id DESC LIMIT 200",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, supplierId);
          ps.setObject(3, from);
          ps.setObject(4, to);
        },
        SupplierPerformanceRepository::mapDelivery,
        "list supplier deliveries");
  }

  private static Delivery mapDelivery(ResultSet rs) throws SQLException {
    LocalDate promised = rs.getObject("promised_date", LocalDate.class);
    int lateRead = rs.getInt("late_days");
    Integer late = rs.wasNull() ? null : lateRead;
    return new Delivery(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("gr_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("ordered_at", OffsetDateTime.class).toInstant(),
        promised,
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        rs.getInt("lead_days"),
        late,
        rs.getBoolean("complete"),
        rs.getBigDecimal("received_qty"));
  }

  /**
   * The period's deliveries added up, per supplier (one supplier when {@code supplierId} is given).
   */
  public Map<UUID, Deliveries> deliveryStats(
      UUID tenantId, UUID supplierId, LocalDate from, LocalDate to) {
    Map<UUID, Deliveries> out = new HashMap<>();
    query(
        "SELECT supplier_id, COUNT(*) AS n, AVG(lead_days) AS avg_lead,"
            + " percentile_cont(0.5) WITHIN GROUP (ORDER BY lead_days) AS median_lead,"
            + " MAX(lead_days) AS max_lead, COUNT(late_days) AS promised,"
            + " COUNT(*) FILTER (WHERE late_days <= 0) AS on_time,"
            + " COUNT(*) FILTER (WHERE late_days > 0) AS late,"
            + " AVG(late_days) FILTER (WHERE late_days > 0) AS avg_late,"
            + " COALESCE(SUM(received_qty), 0) AS received_qty"
            + " FROM supplier_deliveries WHERE tenant_id = ?"
            + " AND received_at >= ?::date AND received_at < (?::date + 1)"
            + (supplierId == null ? "" : " AND supplier_id = ?")
            + " GROUP BY supplier_id",
        period(tenantId, supplierId, from, to),
        rs -> {
          int promised = rs.getInt("promised");
          int onTime = rs.getInt("on_time");
          out.put(
              rs.getObject("supplier_id", UUID.class),
              new Deliveries(
                  rs.getInt("n"),
                  oneDecimal(rs.getBigDecimal("avg_lead")),
                  oneDecimal(rs.getBigDecimal("median_lead")),
                  rs.getInt("max_lead"),
                  promised,
                  onTime,
                  rs.getInt("late"),
                  SupplierScorecard.pct(BigDecimal.valueOf(onTime), BigDecimal.valueOf(promised)),
                  oneDecimal(rs.getBigDecimal("avg_late")),
                  rs.getBigDecimal("received_qty")));
          return null;
        },
        "supplier delivery stats");
    return out;
  }

  /**
   * Ordered against received over the orders that reached their end in the period — received in
   * full, or closed short — by the day they went to the supplier. An order still awaiting the rest
   * of its delivery is not yet a fill rate.
   */
  public Map<UUID, Fill> fillStats(UUID tenantId, UUID supplierId, LocalDate from, LocalDate to) {
    Map<UUID, Fill> out = new HashMap<>();
    query(
        "SELECT p.supplier_id, COUNT(*) AS orders,"
            + " COALESCE(SUM(o.ordered), 0) AS ordered_qty,"
            + " COALESCE(SUM(r.received), 0) AS received_qty,"
            + " COUNT(*) FILTER (WHERE p.status = 'CLOSED') AS short_closed"
            + " FROM purchase_orders p"
            + " LEFT JOIN LATERAL (SELECT SUM(l.qty) AS ordered FROM purchase_order_lines l"
            + "   WHERE l.tenant_id = p.tenant_id AND l.po_id = p.id) o ON TRUE"
            + " LEFT JOIN LATERAL (SELECT SUM(gl.qty_received) AS received FROM goods_receipts g"
            + "   JOIN goods_receipt_lines gl ON gl.tenant_id = g.tenant_id AND gl.gr_id = g.id"
            + "   WHERE g.tenant_id = p.tenant_id AND g.po_id = p.id) r ON TRUE"
            + " WHERE p.tenant_id = ? AND p.status IN ('RECEIVED', 'CLOSED')"
            + " AND p.submitted_at >= ?::date AND p.submitted_at < (?::date + 1)"
            + (supplierId == null ? "" : " AND p.supplier_id = ?")
            + " GROUP BY p.supplier_id",
        period(tenantId, supplierId, from, to),
        rs -> {
          BigDecimal ordered = rs.getBigDecimal("ordered_qty");
          BigDecimal received = rs.getBigDecimal("received_qty");
          out.put(
              rs.getObject("supplier_id", UUID.class),
              new Fill(
                  rs.getInt("orders"),
                  ordered,
                  received,
                  SupplierScorecard.pct(received, ordered),
                  rs.getInt("short_closed")));
          return null;
        },
        "supplier fill stats");
    return out;
  }

  /** Returns raised in the period and what went back, per supplier. */
  public Map<UUID, Returns> returnStats(
      UUID tenantId, UUID supplierId, LocalDate from, LocalDate to) {
    Map<UUID, Returns> out = new HashMap<>();
    query(
        "SELECT v.supplier_id, COUNT(DISTINCT v.id) AS returns,"
            + " COALESCE(SUM(l.qty), 0) AS returned_qty"
            + " FROM vendor_returns v JOIN vendor_return_lines l"
            + " ON l.tenant_id = v.tenant_id AND l.return_id = v.id"
            + " WHERE v.tenant_id = ? AND v.raised_at >= ?::date AND v.raised_at < (?::date + 1)"
            + (supplierId == null ? "" : " AND v.supplier_id = ?")
            + " GROUP BY v.supplier_id",
        period(tenantId, supplierId, from, to),
        rs -> {
          out.put(
              rs.getObject("supplier_id", UUID.class),
              new Returns(rs.getInt("returns"), rs.getBigDecimal("returned_qty")));
          return null;
        },
        "supplier return stats");
    return out;
  }

  /** Invoices captured in the period and how many did not match, per supplier. */
  public Map<UUID, InvoiceMatches> invoiceStats(
      UUID tenantId, UUID supplierId, LocalDate from, LocalDate to) {
    Map<UUID, InvoiceMatches> out = new HashMap<>();
    query(
        "SELECT supplier_id, COUNT(*) AS invoices,"
            + " COUNT(*) FILTER (WHERE status <> 'MATCHED') AS flagged"
            + " FROM supplier_invoices WHERE tenant_id = ?"
            + " AND matched_at >= ?::date AND matched_at < (?::date + 1)"
            + (supplierId == null ? "" : " AND supplier_id = ?")
            + " GROUP BY supplier_id",
        period(tenantId, supplierId, from, to),
        rs -> {
          out.put(
              rs.getObject("supplier_id", UUID.class),
              new InvoiceMatches(rs.getInt("invoices"), rs.getInt("flagged")));
          return null;
        },
        "supplier invoice stats");
    return out;
  }

  /** The tenant first, the period, then the one supplier when the reading is for one. */
  private static Binder period(UUID tenantId, UUID supplierId, LocalDate from, LocalDate to) {
    return ps -> {
      ps.setObject(1, tenantId);
      ps.setObject(2, from);
      ps.setObject(3, to);
      if (supplierId != null) ps.setObject(4, supplierId);
    };
  }

  private static BigDecimal oneDecimal(BigDecimal v) {
    return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
  }
}
