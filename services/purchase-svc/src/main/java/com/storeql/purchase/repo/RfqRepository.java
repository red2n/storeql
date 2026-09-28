package com.storeql.purchase.repo;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Rfq;
import com.storeql.purchase.domain.Rfq.Award;
import com.storeql.purchase.domain.Rfq.Bid;
import com.storeql.purchase.domain.Rfq.Header;
import com.storeql.purchase.domain.Rfq.Line;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests for quotation: the request, its lines, the suppliers asked and what they said, and the
 * awards. Every statement filters by {@code tenant_id} first; a supplier's name is this service's
 * own, the store's id is tenant-svc's, referenced never joined.
 */
@ApplicationScoped
public class RfqRepository extends BaseJdbcRepository {

  /** A request as listed: its head and how far the asking has got. */
  public record Summary(Header header, int lines, int suppliers, int quotes) {}

  // ── Raise ──────────────────────────────────────────────────────────────────

  /** Inserts the request, its lines and its invitations; the reference comes from the series. */
  public Header create(Header h, List<Line> lines, List<UUID> supplierIds) {
    return inTx(
        c -> {
          String reference = nextReferenceTx(c, h.tenantId());
          Header numbered =
              new Header(
                  h.id(),
                  h.tenantId(),
                  reference,
                  h.title(),
                  h.storeId(),
                  h.status(),
                  h.neededBy(),
                  h.closesOn(),
                  h.notes(),
                  h.createdBy(),
                  h.createdAt(),
                  null,
                  null,
                  null,
                  null,
                  null);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO rfqs (id, tenant_id, reference, title, store_id, status, needed_by,"
                      + " closes_on, notes, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, numbered.id());
            ps.setObject(2, numbered.tenantId());
            ps.setString(3, numbered.reference());
            ps.setString(4, numbered.title());
            ps.setObject(5, numbered.storeId());
            ps.setString(6, numbered.status());
            ps.setObject(7, numbered.neededBy());
            ps.setObject(8, numbered.closesOn());
            ps.setString(9, numbered.notes());
            ps.setObject(10, numbered.createdBy());
            ps.setObject(11, numbered.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO rfq_lines (id, tenant_id, rfq_id, variant_id, qty, sort_order, notes)"
                      + " VALUES (?,?,?,?,?,?,?)")) {
            for (Line l : lines) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.rfqId());
              ps.setObject(4, l.variantId());
              ps.setBigDecimal(5, l.qty());
              ps.setInt(6, l.sortOrder());
              ps.setString(7, l.notes());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO rfq_suppliers (id, tenant_id, rfq_id, supplier_id, status)"
                      + " VALUES (?,?,?,?,'INVITED')")) {
            for (UUID s : supplierIds) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, h.tenantId());
              ps.setObject(3, h.id());
              ps.setObject(4, s);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return numbered;
        },
        "create rfq");
  }

  private static String nextReferenceTx(Connection c, UUID tenantId) throws SQLException {
    try (PreparedStatement open =
        c.prepareStatement(
            "INSERT INTO rfq_series (tenant_id, next_number) VALUES (?, 1) ON CONFLICT DO NOTHING")) {
      open.setObject(1, tenantId);
      open.executeUpdate();
    }
    try (PreparedStatement take =
        c.prepareStatement(
            "UPDATE rfq_series SET next_number = next_number + 1 WHERE tenant_id = ?"
                + " RETURNING next_number - 1")) {
      take.setObject(1, tenantId);
      try (ResultSet rs = take.executeQuery()) {
        if (!rs.next()) throw new SQLException("rfq series vanished between open and take");
        return Rfq.reference(rs.getLong(1));
      }
    }
  }

  // ── Read ───────────────────────────────────────────────────────────────────

  private static final String HEADER_COLUMNS =
      "id, tenant_id, reference, title, store_id, status, needed_by, closes_on, notes, created_by,"
          + " created_at, issued_at, awarded_at, awarded_by, cancelled_at, cancelled_reason";

  public Optional<Header> find(UUID tenantId, UUID id) {
    List<Header> rows =
        query(
            "SELECT " + HEADER_COLUMNS + " FROM rfqs WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            RfqRepository::mapHeader,
            "find rfq");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** The tenant's requests, newest first, at one status or all of them. */
  public List<Summary> list(UUID tenantId, String status, int limit) {
    return query(
        "SELECT "
            + HEADER_COLUMNS
            + ", (SELECT COUNT(*) FROM rfq_lines l WHERE l.tenant_id = r.tenant_id AND l.rfq_id ="
            + " r.id) AS line_count, (SELECT COUNT(*) FROM rfq_suppliers s WHERE s.tenant_id ="
            + " r.tenant_id AND s.rfq_id = r.id) AS supplier_count, (SELECT COUNT(*) FROM"
            + " rfq_suppliers s WHERE s.tenant_id = r.tenant_id AND s.rfq_id = r.id AND s.status ="
            + " 'QUOTED') AS quote_count FROM rfqs r WHERE tenant_id = ?"
            + (status == null ? "" : " AND status = ?")
            + " ORDER BY created_at DESC, id DESC LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        rs ->
            new Summary(
                mapHeader(rs),
                rs.getInt("line_count"),
                rs.getInt("supplier_count"),
                rs.getInt("quote_count")),
        "list rfqs");
  }

  public List<Line> lines(UUID tenantId, UUID rfqId) {
    return query(
        "SELECT id, tenant_id, rfq_id, variant_id, qty, sort_order, notes FROM rfq_lines"
            + " WHERE tenant_id = ? AND rfq_id = ? ORDER BY sort_order, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, rfqId);
        },
        rs ->
            new Line(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("rfq_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty"),
                rs.getInt("sort_order"),
                rs.getString("notes")),
        "list rfq lines");
  }

  /** The suppliers asked, with their terms and their prices by line. */
  public List<Bid> bids(UUID tenantId, UUID rfqId) {
    Map<UUID, Map<UUID, BigDecimal>> prices = new HashMap<>();
    query(
        "SELECT q.rfq_supplier_id, q.rfq_line_id, q.unit_price FROM rfq_quote_lines q"
            + " JOIN rfq_suppliers s ON s.tenant_id = q.tenant_id AND s.id = q.rfq_supplier_id"
            + " WHERE q.tenant_id = ? AND s.rfq_id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, rfqId);
        },
        rs -> {
          prices
              .computeIfAbsent(rs.getObject("rfq_supplier_id", UUID.class), k -> new HashMap<>())
              .put(rs.getObject("rfq_line_id", UUID.class), rs.getBigDecimal("unit_price"));
          return null;
        },
        "load rfq prices");
    return query(
        "SELECT b.id, b.tenant_id, b.rfq_id, b.supplier_id, s.name AS supplier_name, b.status,"
            + " b.currency, b.lead_time_days, b.valid_until, b.notes, b.quoted_at"
            + " FROM rfq_suppliers b JOIN suppliers s ON s.tenant_id = b.tenant_id AND s.id ="
            + " b.supplier_id WHERE b.tenant_id = ? AND b.rfq_id = ? ORDER BY s.name, b.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, rfqId);
        },
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          int lead = rs.getInt("lead_time_days");
          Integer leadTime = rs.wasNull() ? null : lead;
          OffsetDateTime quoted = rs.getObject("quoted_at", OffsetDateTime.class);
          return new Bid(
              id,
              rs.getObject("tenant_id", UUID.class),
              rs.getObject("rfq_id", UUID.class),
              rs.getObject("supplier_id", UUID.class),
              rs.getString("supplier_name"),
              rs.getString("status"),
              rs.getString("currency"),
              leadTime,
              rs.getObject("valid_until", LocalDate.class),
              rs.getString("notes"),
              quoted == null ? null : quoted.toInstant(),
              Map.copyOf(prices.getOrDefault(id, Map.of())));
        },
        "list rfq bids");
  }

  public List<Award> awards(UUID tenantId, UUID rfqId) {
    return query(
        "SELECT id, tenant_id, rfq_id, rfq_line_id, supplier_id, po_id, unit_price, currency,"
            + " awarded_at FROM rfq_awards WHERE tenant_id = ? AND rfq_id = ? ORDER BY awarded_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, rfqId);
        },
        rs ->
            new Award(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("rfq_id", UUID.class),
                rs.getObject("rfq_line_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("po_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                rs.getString("currency"),
                rs.getObject("awarded_at", OffsetDateTime.class).toInstant()),
        "list rfq awards");
  }

  private static Header mapHeader(ResultSet rs) throws SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("reference"),
        rs.getString("title"),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getObject("needed_by", LocalDate.class),
        rs.getObject("closes_on", LocalDate.class),
        rs.getString("notes"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        instant(rs.getObject("issued_at", OffsetDateTime.class)),
        instant(rs.getObject("awarded_at", OffsetDateTime.class)),
        rs.getObject("awarded_by", UUID.class),
        instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
        rs.getString("cancelled_reason"));
  }

  private static Instant instant(OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }

  // ── Move ───────────────────────────────────────────────────────────────────

  /** DRAFT to ISSUED; false when it was not DRAFT. */
  public boolean issue(UUID tenantId, UUID id) {
    return update(
        "UPDATE rfqs SET status = 'ISSUED', issued_at = now() WHERE tenant_id = ? AND id = ?"
            + " AND status = 'DRAFT'",
        tenantId,
        id,
        "issue rfq");
  }

  /** DRAFT or ISSUED to CANCELLED with the reason; false when it was neither. */
  public boolean cancel(UUID tenantId, UUID id, String reason) {
    int[] rows = new int[1];
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE rfqs SET status = 'CANCELLED', cancelled_at = now(), cancelled_reason = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status IN ('DRAFT', 'ISSUED')")) {
            ps.setString(1, reason);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            rows[0] = ps.executeUpdate();
          }
          return null;
        },
        "cancel rfq");
    return rows[0] > 0;
  }

  private boolean update(String sql, UUID tenantId, UUID id, String what) {
    int[] rows = new int[1];
    inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            rows[0] = ps.executeUpdate();
          }
          return null;
        },
        what);
    return rows[0] > 0;
  }

  /**
   * Records a supplier's quote, replacing any earlier one: the terms on the invitation, the prices
   * per line. False when the supplier was not asked.
   */
  public boolean quote(
      UUID tenantId,
      UUID rfqId,
      UUID supplierId,
      String currency,
      Integer leadTimeDays,
      LocalDate validUntil,
      String notes,
      Map<UUID, BigDecimal> pricesByLine) {
    return inTx(
        c -> {
          UUID bidId = lockBidTx(c, tenantId, rfqId, supplierId);
          if (bidId == null) return false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE rfq_suppliers SET status = 'QUOTED', currency = ?, lead_time_days = ?,"
                      + " valid_until = ?, notes = ?, quoted_at = now() WHERE tenant_id = ? AND id"
                      + " = ?")) {
            ps.setString(1, currency);
            if (leadTimeDays == null) ps.setNull(2, java.sql.Types.INTEGER);
            else ps.setInt(2, leadTimeDays);
            ps.setObject(3, validUntil);
            ps.setString(4, notes);
            ps.setObject(5, tenantId);
            ps.setObject(6, bidId);
            ps.executeUpdate();
          }
          deletePricesTx(c, tenantId, bidId);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO rfq_quote_lines (id, tenant_id, rfq_supplier_id, rfq_line_id,"
                      + " unit_price) VALUES (?,?,?,?,?)")) {
            for (Map.Entry<UUID, BigDecimal> e : pricesByLine.entrySet()) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, bidId);
              ps.setObject(4, e.getKey());
              ps.setBigDecimal(5, e.getValue());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "record rfq quote");
  }

  /** The supplier will not be bidding; their earlier prices, if any, go. False when not asked. */
  public boolean decline(UUID tenantId, UUID rfqId, UUID supplierId) {
    return inTx(
        c -> {
          UUID bidId = lockBidTx(c, tenantId, rfqId, supplierId);
          if (bidId == null) return false;
          deletePricesTx(c, tenantId, bidId);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE rfq_suppliers SET status = 'DECLINED', quoted_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bidId);
            ps.executeUpdate();
          }
          return true;
        },
        "decline rfq");
  }

  /**
   * The invitation row, locked for the change about to be made; null when the supplier was not
   * asked.
   */
  private static UUID lockBidTx(Connection c, UUID tenantId, UUID rfqId, UUID supplierId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id FROM rfq_suppliers WHERE tenant_id = ? AND rfq_id = ? AND supplier_id = ?"
                + " FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, rfqId);
      ps.setObject(3, supplierId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject("id", UUID.class) : null;
      }
    }
  }

  /** A quote's prices go as a set: a new quote rewrites them, a decline removes them. */
  private static void deletePricesTx(Connection c, UUID tenantId, UUID bidId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "DELETE FROM rfq_quote_lines WHERE tenant_id = ? AND rfq_supplier_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, bidId);
      ps.executeUpdate();
    }
  }

  /** ISSUED to AWARDED with the awards, atomically; false when it was not ISSUED. */
  public boolean award(UUID tenantId, UUID rfqId, UUID by, List<Award> awards) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE rfqs SET status = 'AWARDED', awarded_at = now(), awarded_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'ISSUED'")) {
            ps.setObject(1, by);
            ps.setObject(2, tenantId);
            ps.setObject(3, rfqId);
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO rfq_awards (id, tenant_id, rfq_id, rfq_line_id, supplier_id, po_id,"
                      + " unit_price, currency, awarded_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (Award a : awards) {
              ps.setObject(1, a.id());
              ps.setObject(2, a.tenantId());
              ps.setObject(3, a.rfqId());
              ps.setObject(4, a.lineId());
              ps.setObject(5, a.supplierId());
              ps.setObject(6, a.poId());
              ps.setBigDecimal(7, a.unitPrice());
              ps.setString(8, a.currency());
              ps.setObject(9, a.awardedAt().atOffset(ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "award rfq");
  }

  /** Keeps insertion order for callers that build maps of lines. */
  static <K, V> Map<K, V> ordered() {
    return new LinkedHashMap<>();
  }

  /** A list the callers can add to. */
  static <T> List<T> mutable() {
    return new ArrayList<>();
  }
}
