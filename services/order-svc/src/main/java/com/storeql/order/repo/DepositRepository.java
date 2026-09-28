package com.storeql.order.repo;

import com.storeql.order.domain.Domain.ContainerRefund;
import com.storeql.order.domain.Domain.ContainerRefundLine;
import com.storeql.order.domain.Domain.DepositReportRow;
import com.storeql.order.domain.Domain.OrderDeposit;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The deposits a return scheme put on drinks containers at the sale, and the refunds paid at the
 * till when containers come back (09.16). Deposit lines are written inside the order's transaction
 * by {@link OrderRepository}; refunds are their own record with their own event.
 */
@ApplicationScoped
public class DepositRepository extends BaseOutboxRepository {

  private static final String UNIQUE_VIOLATION = "23505";

  private static final String INSERT_DEPOSIT =
      "INSERT INTO order_deposits (id, tenant_id, order_id, order_item_id, variant_id, material,"
          + " volume_ml, qty, deposit_each, amount, currency, vat_treatment, vat_rate, vat_amount,"
          + " scheme_scope, citation, created_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_DEPOSITS =
      "SELECT id, tenant_id, order_id, order_item_id, variant_id, material, volume_ml, qty,"
          + " deposit_each, amount, currency, vat_treatment, vat_rate, vat_amount, scheme_scope,"
          + " citation, created_at"
          + " FROM order_deposits WHERE tenant_id = ? AND order_id = ? ORDER BY created_at, id";

  private static final String INSERT_REFUND =
      "INSERT INTO container_refunds (id, tenant_id, store_id, till_session_id, currency,"
          + " containers, amount, scheme_scope, idempotency_key, refunded_by, created_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String INSERT_REFUND_LINE =
      "INSERT INTO container_refund_lines (id, tenant_id, refund_id, material, volume_ml, count,"
          + " deposit_each, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_REFUND_COLUMNS =
      "SELECT id, tenant_id, store_id, till_session_id, currency, containers, amount,"
          + " scheme_scope, idempotency_key, refunded_by, created_at FROM container_refunds";

  private static final String SELECT_REFUND_BY_KEY =
      SELECT_REFUND_COLUMNS + " WHERE tenant_id = ? AND idempotency_key = ?";

  private static final String SELECT_REFUND_BY_ID =
      SELECT_REFUND_COLUMNS + " WHERE tenant_id = ? AND id = ?";

  private static final String SELECT_REFUND_LINES =
      "SELECT material, volume_ml, count, deposit_each, amount FROM container_refund_lines"
          + " WHERE tenant_id = ? AND refund_id = ? ORDER BY material, volume_ml";

  private static final String REPORT_CHARGED =
      "SELECT d.material, COALESCE(SUM(d.qty), 0), COALESCE(SUM(d.amount), 0),"
          + " COALESCE(SUM(d.vat_amount), 0)"
          + " FROM order_deposits d JOIN orders o ON o.tenant_id = d.tenant_id AND o.id = d.order_id"
          + " WHERE d.tenant_id = ? AND d.created_at >= ? AND d.created_at < ?"
          + " AND (CAST(? AS uuid) IS NULL OR o.store_id = CAST(? AS uuid))"
          + " AND o.status <> 'CANCELLED' AND o.status <> 'VOIDED'"
          + " GROUP BY d.material ORDER BY d.material";

  private static final String REPORT_REFUNDED =
      "SELECT l.material, COALESCE(SUM(l.count), 0), COALESCE(SUM(l.amount), 0)"
          + " FROM container_refund_lines l"
          + " JOIN container_refunds r ON r.tenant_id = l.tenant_id AND r.id = l.refund_id"
          + " WHERE l.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?"
          + " AND (CAST(? AS uuid) IS NULL OR r.store_id = CAST(? AS uuid))"
          + " GROUP BY l.material ORDER BY l.material";

  /** Writes one deposit line inside the order's transaction. */
  /**
   * A line closed short or substituted (substitutions for out-of-stock online lines) carries a
   * smaller deposit: the container deposit on the line shrinks to what stands of it, the qty first
   * and the amount from the qty, so the deposit stays qty × deposit_each as charged.
   */
  static void shrinkDepositTx(
      Connection c,
      UUID tenantId,
      UUID orderItemId,
      BigDecimal standingAfter,
      BigDecimal standingBefore)
      throws SQLException {
    if (standingBefore.signum() <= 0) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE order_deposits SET"
                + " qty = ROUND(qty * ? / ?, 3),"
                + " amount = ROUND(ROUND(qty * ? / ?, 3) * deposit_each, 2),"
                + " vat_amount = ROUND(vat_amount * ? / ?, 2)"
                + " WHERE tenant_id = ? AND order_item_id = ?")) {
      ps.setBigDecimal(1, standingAfter);
      ps.setBigDecimal(2, standingBefore);
      ps.setBigDecimal(3, standingAfter);
      ps.setBigDecimal(4, standingBefore);
      ps.setBigDecimal(5, standingAfter);
      ps.setBigDecimal(6, standingBefore);
      ps.setObject(7, tenantId);
      ps.setObject(8, orderItemId);
      ps.executeUpdate();
    }
  }

  /** The deposits an order carries, added up, inside a transaction. */
  static BigDecimal depositTotalTx(Connection c, UUID tenantId, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount), 0) FROM order_deposits"
                + " WHERE tenant_id = ? AND order_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  static void insertDeposit(Connection c, OrderDeposit d) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_DEPOSIT)) {
      ps.setObject(1, d.id());
      ps.setObject(2, d.tenantId());
      ps.setObject(3, d.orderId());
      ps.setObject(4, d.orderItemId());
      ps.setObject(5, d.variantId());
      ps.setString(6, d.material());
      ps.setInt(7, d.volumeMl());
      ps.setBigDecimal(8, d.qty());
      ps.setBigDecimal(9, d.depositEach());
      ps.setBigDecimal(10, d.amount());
      ps.setString(11, d.currency());
      ps.setString(12, d.vatTreatment());
      ps.setBigDecimal(13, d.vatRate());
      ps.setBigDecimal(14, d.vatAmount());
      ps.setString(15, d.schemeScope());
      ps.setString(16, d.citation());
      ps.setObject(17, d.createdAt().atOffset(java.time.ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /** The deposit lines of an order, oldest first; empty for a sale with no container in scope. */
  public List<OrderDeposit> depositsOf(UUID tenantId, UUID orderId) {
    return query(
        SELECT_DEPOSITS,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        DepositRepository::mapDeposit,
        "order deposits");
  }

  /**
   * Records a refund with its lines and its event in one transaction. A second call with the same
   * key returns the first refund: a till that retries pays out once.
   */
  public ContainerRefund insertRefund(ContainerRefund r, OutboxRow event) {
    try {
      return inTx(
          c -> {
            try (PreparedStatement ps = c.prepareStatement(INSERT_REFUND)) {
              ps.setObject(1, r.id());
              ps.setObject(2, r.tenantId());
              ps.setObject(3, r.storeId());
              ps.setObject(4, r.tillSessionId());
              ps.setString(5, r.currency());
              ps.setInt(6, r.containers());
              ps.setBigDecimal(7, r.amount());
              ps.setString(8, r.schemeScope());
              ps.setString(9, r.idempotencyKey());
              ps.setObject(10, r.refundedBy());
              ps.setObject(11, r.createdAt().atOffset(java.time.ZoneOffset.UTC));
              ps.executeUpdate();
            }
            for (ContainerRefundLine line : r.lines()) {
              try (PreparedStatement ps = c.prepareStatement(INSERT_REFUND_LINE)) {
                ps.setObject(1, com.storeql.ids.Ids.newId());
                ps.setObject(2, r.tenantId());
                ps.setObject(3, r.id());
                ps.setString(4, line.material());
                ps.setInt(5, line.volumeMl());
                ps.setInt(6, line.count());
                ps.setBigDecimal(7, line.depositEach());
                ps.setBigDecimal(8, line.amount());
                ps.executeUpdate();
              }
            }
            insertOutbox(c, event);
            return r;
          },
          "container refund");
    } catch (RuntimeException e) {
      if (e.getCause() instanceof SQLException sqle
          && UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
        return refundByKey(r.tenantId(), r.idempotencyKey())
            .orElseThrow(
                () ->
                    new ApiException(
                        409, "ORDER_DUPLICATE_KEY", "duplicate idempotency key", List.of(), e));
      }
      throw e;
    }
  }

  /** The refund a till already recorded under this key, with its lines. */
  public Optional<ContainerRefund> refundByKey(UUID tenantId, String key) {
    return first(
        SELECT_REFUND_BY_KEY,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, key);
        });
  }

  /** A refund by id, with its lines. */
  public Optional<ContainerRefund> refund(UUID tenantId, UUID id) {
    return first(
        SELECT_REFUND_BY_ID,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        });
  }

  private Optional<ContainerRefund> first(String sql, Binder binder) {
    List<ContainerRefund> heads = query(sql, binder, DepositRepository::mapRefund, "refund");
    if (heads.isEmpty()) return Optional.empty();
    ContainerRefund head = heads.get(0);
    List<ContainerRefundLine> lines =
        query(
            SELECT_REFUND_LINES,
            ps -> {
              ps.setObject(1, head.tenantId());
              ps.setObject(2, head.id());
            },
            rs ->
                new ContainerRefundLine(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getInt(3),
                    rs.getBigDecimal(4),
                    rs.getBigDecimal(5)),
            "refund lines");
    return Optional.of(
        new ContainerRefund(
            head.id(),
            head.tenantId(),
            head.storeId(),
            head.tillSessionId(),
            head.currency(),
            head.containers(),
            head.amount(),
            head.schemeScope(),
            head.idempotencyKey(),
            head.refundedBy(),
            head.createdAt(),
            lines));
  }

  /**
   * Deposits charged on sales that stand and refunded at the till, by material, over [from, to).
   *
   * @param storeId one store, or null for the whole business
   */
  public List<DepositReportRow> report(UUID tenantId, UUID storeId, Instant from, Instant to) {
    Map<String, DepositReportRow> rows = new LinkedHashMap<>();
    for (var r :
        query(
            REPORT_CHARGED,
            ps -> bindPeriod(ps, tenantId, storeId, from, to),
            rs ->
                new DepositReportRow(
                    rs.getString(1),
                    rs.getBigDecimal(2).longValue(),
                    rs.getBigDecimal(3),
                    rs.getBigDecimal(4),
                    0,
                    BigDecimal.ZERO),
            "deposits charged")) {
      rows.put(r.material(), r);
    }
    for (var r :
        query(
            REPORT_REFUNDED,
            ps -> bindPeriod(ps, tenantId, storeId, from, to),
            rs ->
                new DepositReportRow(
                    rs.getString(1),
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    rs.getLong(2),
                    rs.getBigDecimal(3)),
            "deposits refunded")) {
      DepositReportRow charged = rows.get(r.material());
      rows.put(
          r.material(),
          charged == null
              ? r
              : new DepositReportRow(
                  r.material(),
                  charged.chargedContainers(),
                  charged.chargedAmount(),
                  charged.chargedVat(),
                  r.refundedContainers(),
                  r.refundedAmount()));
    }
    return new ArrayList<>(rows.values());
  }

  private static void bindPeriod(
      PreparedStatement ps, UUID tenantId, UUID storeId, Instant from, Instant to)
      throws SQLException {
    ps.setObject(1, tenantId);
    ps.setObject(2, from.atOffset(java.time.ZoneOffset.UTC));
    ps.setObject(3, to.atOffset(java.time.ZoneOffset.UTC));
    ps.setObject(4, storeId);
    ps.setObject(5, storeId);
  }

  private static OrderDeposit mapDeposit(ResultSet rs) throws SQLException {
    return new OrderDeposit(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        (UUID) rs.getObject(3),
        (UUID) rs.getObject(4),
        (UUID) rs.getObject(5),
        rs.getString(6),
        rs.getInt(7),
        rs.getBigDecimal(8),
        rs.getBigDecimal(9),
        rs.getBigDecimal(10),
        rs.getString(11),
        rs.getString(12),
        rs.getBigDecimal(13),
        rs.getBigDecimal(14),
        rs.getString(15),
        rs.getString(16),
        rs.getObject(17, java.time.OffsetDateTime.class).toInstant());
  }

  private static ContainerRefund mapRefund(ResultSet rs) throws SQLException {
    return new ContainerRefund(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        (UUID) rs.getObject(3),
        (UUID) rs.getObject(4),
        rs.getString(5),
        rs.getInt(6),
        rs.getBigDecimal(7),
        rs.getString(8),
        rs.getString(9),
        (UUID) rs.getObject(10),
        rs.getObject(11, java.time.OffsetDateTime.class).toInstant(),
        List.of());
  }
}
