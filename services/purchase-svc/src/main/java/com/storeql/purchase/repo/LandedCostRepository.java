package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.LandedCost;
import com.storeql.purchase.domain.LandedCost.Charge;
import com.storeql.purchase.domain.LandedCost.Line;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Landed costs (07.x): a charge, its lines, its posting and its announcement commit together, or
 * none of them does. A reversal is decided under the charge's row lock, so two reversals of the
 * same charge cannot both post.
 */
@ApplicationScoped
public class LandedCostRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "id, tenant_id, gr_id, po_id, store_id, charge_type, basis, currency, amount, reference,"
          + " charged_by, notes, status, applied_at, applied_by, reversed_at, reversed_by,"
          + " reversed_reason, idempotency_key";

  private static final String LINE_COLUMNS =
      "id, tenant_id, landed_cost_id, gr_line_id, variant_id, qty, line_value, amount, per_unit";

  /** The receipt a charge lands on, in this tenant. */
  public Optional<GoodsReceipt> findGoodsReceipt(UUID tenantId, UUID grId) {
    var rows =
        query(
            "SELECT id, tenant_id, po_id, store_id, received_at, created_at, idempotency_key"
                + " FROM goods_receipts WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, grId);
            },
            rs ->
                new GoodsReceipt(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("po_id", UUID.class),
                    rs.getObject("store_id", UUID.class),
                    instant(rs, "received_at"),
                    instant(rs, "created_at"),
                    rs.getString("idempotency_key")),
            "find goods receipt");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Applies a charge: the charge, its lines, the posting and the announcement, in one go. */
  public Charge apply(
      Charge c, List<Line> lines, List<NominalLedgerEntry> posting, OutboxRow event) {
    return inTx(
        conn -> {
          if (c.idempotencyKey() != null) {
            Charge existing = findByKeyTx(conn, c.tenantId(), c.idempotencyKey());
            if (existing != null) return existing;
          }
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO landed_costs ("
                      + COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setObject(3, c.grId());
            ps.setObject(4, c.poId());
            ps.setObject(5, c.storeId());
            ps.setString(6, c.chargeType());
            ps.setString(7, c.basis());
            ps.setString(8, c.currency());
            ps.setBigDecimal(9, c.amount());
            ps.setString(10, c.reference());
            ps.setObject(11, c.chargedBy());
            ps.setString(12, c.notes());
            ps.setString(13, c.status());
            ps.setObject(14, odt(c.appliedAt()));
            ps.setObject(15, c.appliedBy());
            ps.setObject(16, odt(c.reversedAt()));
            ps.setObject(17, c.reversedBy());
            ps.setString(18, c.reversedReason());
            ps.setString(19, c.idempotencyKey());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO landed_cost_lines ("
                      + LINE_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (Line l : lines) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.landedCostId());
              ps.setObject(4, l.grLineId());
              ps.setObject(5, l.variantId());
              ps.setBigDecimal(6, l.qty());
              ps.setBigDecimal(7, l.lineValue());
              ps.setBigDecimal(8, l.amount());
              ps.setBigDecimal(9, l.perUnit());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          LedgerWriter.insert(conn, posting);
          insertOutbox(conn, event);
          return c;
        },
        "apply landed cost");
  }

  /**
   * Reverses an applied charge under its row lock, posting the mirror and announcing it.
   *
   * @throws ApiException 404 {@code PURCHASE_LANDED_NOT_FOUND}; 409 {@code
   *     PURCHASE_LANDED_REVERSED} when it already was
   */
  public Charge reverse(
      UUID tenantId,
      UUID id,
      UUID by,
      String reason,
      Instant at,
      List<NominalLedgerEntry> posting,
      OutboxRow event) {
    return inTx(
        conn -> {
          String status;
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT status FROM landed_costs WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
              status = rs.next() ? rs.getString("status") : null;
            }
          }
          if (status == null) {
            throw ApiException.notFound("PURCHASE_LANDED_NOT_FOUND", "No such landed cost");
          }
          if (LandedCost.STATUS_REVERSED.equals(status)) {
            throw ApiException.conflict(
                "PURCHASE_LANDED_REVERSED", "this charge was already reversed; apply a new one");
          }
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE landed_costs SET status = ?, reversed_at = ?, reversed_by = ?,"
                      + " reversed_reason = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, LandedCost.STATUS_REVERSED);
            ps.setObject(2, odt(at));
            ps.setObject(3, by);
            ps.setString(4, reason);
            ps.setObject(5, tenantId);
            ps.setObject(6, id);
            ps.executeUpdate();
          }
          LedgerWriter.insert(conn, posting);
          insertOutbox(conn, event);
          return findTx(conn, tenantId, id);
        },
        "reverse landed cost");
  }

  public Optional<Charge> find(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT " + COLUMNS + " FROM landed_costs WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            LandedCostRepository::map,
            "find landed cost");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * The charges on a receipt, on an order, or in the tenant, newest first.
   *
   * @param grId a receipt, or null
   * @param poId an order, or null
   * @param stores the stores the charges landed at, or null for every store in the business
   */
  public List<Charge> list(UUID tenantId, UUID grId, UUID poId, Set<UUID> stores, int limit) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM landed_costs WHERE tenant_id = ?"
            + " AND (CAST(? AS uuid) IS NULL OR gr_id = CAST(? AS uuid))"
            + " AND (CAST(? AS uuid) IS NULL OR po_id = CAST(? AS uuid))"
            + (stores != null ? " AND store_id = ANY(?)" : "")
            + " ORDER BY applied_at DESC, id DESC LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, grId);
          ps.setObject(i++, grId);
          ps.setObject(i++, poId);
          ps.setObject(i++, poId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          ps.setInt(i, limit);
        },
        LandedCostRepository::map,
        "list landed costs");
  }

  public List<Line> lines(UUID tenantId, UUID landedCostId) {
    return query(
        "SELECT "
            + LINE_COLUMNS
            + " FROM landed_cost_lines WHERE tenant_id = ? AND landed_cost_id = ? ORDER BY id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, landedCostId);
        },
        LandedCostRepository::mapLine,
        "landed cost lines");
  }

  /** Every applied charge's lines on one receipt: what a reversal or a report adds up. */
  public List<Line> appliedLinesOnReceipt(UUID tenantId, UUID grId) {
    return query(
        "SELECT l.id, l.tenant_id, l.landed_cost_id, l.gr_line_id, l.variant_id, l.qty,"
            + " l.line_value, l.amount, l.per_unit FROM landed_cost_lines l"
            + " JOIN landed_costs c ON c.id = l.landed_cost_id AND c.tenant_id = l.tenant_id"
            + " WHERE l.tenant_id = ? AND c.gr_id = ? AND c.status = 'APPLIED' ORDER BY l.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, grId);
        },
        LandedCostRepository::mapLine,
        "applied landed cost lines on receipt");
  }

  private static Charge findByKeyTx(Connection conn, UUID tenantId, String key)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM landed_costs WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  private static Charge findTx(Connection conn, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT " + COLUMNS + " FROM landed_costs WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.notFound("PURCHASE_LANDED_NOT_FOUND", "No such landed cost");
        }
        return map(rs);
      }
    }
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if ("23505".equals(e.getSQLState())) {
      return ApiException.conflict(
          "PURCHASE_LANDED_IN_FLIGHT",
          "a charge with this Idempotency-Key is being applied by another request — read it back");
    }
    return super.handleTxSqlException(what, e);
  }

  private static Charge map(ResultSet rs) throws SQLException {
    return new Charge(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("gr_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("charge_type"),
        rs.getString("basis"),
        rs.getString("currency"),
        rs.getBigDecimal("amount"),
        rs.getString("reference"),
        rs.getObject("charged_by", UUID.class),
        rs.getString("notes"),
        rs.getString("status"),
        instant(rs, "applied_at"),
        rs.getObject("applied_by", UUID.class),
        instant(rs, "reversed_at"),
        rs.getObject("reversed_by", UUID.class),
        rs.getString("reversed_reason"),
        rs.getString("idempotency_key"));
  }

  private static Line mapLine(ResultSet rs) throws SQLException {
    return new Line(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("landed_cost_id", UUID.class),
        rs.getObject("gr_line_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("line_value"),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("per_unit"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime odt(Instant t) {
    return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
  }
}
