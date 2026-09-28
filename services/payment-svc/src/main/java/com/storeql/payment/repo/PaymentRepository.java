package com.storeql.payment.repo;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC access to payment tenders and refunds, with their outbox events.
 *
 * <p>Both tables are append-only: nothing here updates a captured tender in place. The refund paths
 * enforce the cumulative refund cap inside the same transaction that writes the refund, with the
 * payment row locked, so concurrent refunds cannot together exceed what was captured.
 */
@ApplicationScoped
public class PaymentRepository extends BaseOutboxRepository {

  /**
   * Record a captured tender. If the same Idempotency-Key was already stored for this tenant, the
   * original tender is returned unchanged (replay) — the retry must not double-charge AND must not
   * surface as an error (golden rule #11).
   */
  public PaymentTender createTender(PaymentTender t, OutboxRow event) {
    return inTx(
        c -> {
          if (t.idempotencyKey() != null) {
            PaymentTender existing = findTenderByKeyTx(c, t.tenantId(), t.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          insertTenderTx(c, t);
          insertOutbox(c, event);
          return t;
        },
        "create payment tender");
  }

  private static void insertTenderTx(java.sql.Connection c, PaymentTender t)
      throws java.sql.SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO payment_tenders"
                + " (id, tenant_id, order_id, amount, method, reference,"
                + "  idempotency_key, status, notes, created_at, store_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, t.id());
      ps.setObject(2, t.tenantId());
      ps.setObject(3, t.orderId());
      ps.setBigDecimal(4, t.amount());
      ps.setString(5, t.method());
      ps.setString(6, t.reference());
      ps.setString(7, t.idempotencyKey());
      ps.setString(8, t.status());
      ps.setString(9, t.notes());
      // pgjdbc cannot infer a SQL type for a raw java.time.Instant.
      ps.setObject(10, t.createdAt().atOffset(java.time.ZoneOffset.UTC));
      ps.setObject(11, t.storeId());
      ps.executeUpdate();
    }
  }

  /**
   * Captures several tenders on one transaction, each with its event: one payment for a split
   * checkout, a tender per part (order orchestration). A tender whose key was captured already is
   * replayed rather than taken twice, so a retried payment returns what the first took.
   */
  public List<PaymentTender> createTenders(List<PaymentTender> tenders, List<OutboxRow> events) {
    return inTx(
        c -> {
          List<PaymentTender> out = new java.util.ArrayList<>();
          for (int i = 0; i < tenders.size(); i++) {
            PaymentTender t = tenders.get(i);
            PaymentTender existing =
                t.idempotencyKey() == null
                    ? null
                    : findTenderByKeyTx(c, t.tenantId(), t.idempotencyKey());
            if (existing != null) {
              out.add(existing);
              continue;
            }
            insertTenderTx(c, t);
            insertOutbox(c, events.get(i));
            out.add(t);
          }
          return out;
        },
        "create payment tenders");
  }

  /**
   * Record a refund with the cumulative cap enforced atomically: the payment row is locked ({@code
   * FOR UPDATE}) before existing refunds are summed, so two concurrent refunds cannot both pass the
   * check and together exceed the original payment. Duplicate Idempotency-Key replays the stored
   * refund.
   */
  public RefundTender createRefundGuarded(RefundTender r, OutboxRow event) {
    return inTx(
        c -> {
          if (r.idempotencyKey() != null) {
            RefundTender existing = findRefundByKeyTx(c, r.tenantId(), r.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }

          PaymentTender payment = lockTenderTx(c, r.tenantId(), r.paymentId());
          if (payment == null) {
            throw com.storeql.web.ApiException.notFound(
                "PAYMENT_NOT_FOUND", "payment tender not found");
          }
          if (!payment.orderId().equals(r.orderId())) {
            throw com.storeql.web.ApiException.conflict(
                "PAYMENT_ORDER_MISMATCH", "payment does not belong to this order");
          }

          BigDecimal alreadyRefunded = sumRefundsTx(c, r.tenantId(), r.paymentId());
          if (alreadyRefunded.add(r.amount()).compareTo(payment.amount()) > 0) {
            throw com.storeql.web.ApiException.conflict(
                "REFUND_EXCEEDS_PAYMENT",
                "total refunds would exceed original payment of " + payment.amount());
          }

          try (var ps =
              c.prepareStatement(
                  "INSERT INTO refund_tenders"
                      + " (id, tenant_id, order_id, payment_id, amount, method,"
                      + "  reference, idempotency_key, reason, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.orderId());
            ps.setObject(4, r.paymentId());
            ps.setBigDecimal(5, r.amount());
            ps.setString(6, r.method());
            ps.setString(7, r.reference());
            ps.setString(8, r.idempotencyKey());
            ps.setString(9, r.reason());
            // pgjdbc cannot infer a SQL type for a raw java.time.Instant.
            ps.setObject(10, r.createdAt().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return r;
        },
        "create refund tender");
  }

  /**
   * Concurrent same-key requests can both miss the replay pre-check; the unique index then rejects
   * the loser. Surface that as a retryable 409 instead of a generic 500 so the client's next retry
   * hits the replay path.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new com.storeql.web.ApiException(
          409,
          "IDEMPOTENCY_CONFLICT",
          "A request with this Idempotency-Key is already being processed - retry to fetch it",
          List.of(),
          e);
    }
    return dbError(what, e);
  }

  private PaymentTender findTenderByKeyTx(
      java.sql.Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at, store_id"
                + " FROM payment_tenders WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapTender(rs) : null;
      }
    }
  }

  private RefundTender findRefundByKeyTx(
      java.sql.Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, payment_id, amount, method,"
                + " reference, idempotency_key, reason, created_at"
                + " FROM refund_tenders WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapRefund(rs) : null;
      }
    }
  }

  private PaymentTender lockTenderTx(java.sql.Connection c, UUID tenantId, UUID tenderId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at, store_id"
                + " FROM payment_tenders WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, tenderId);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapTender(rs) : null;
      }
    }
  }

  /**
   * Refund a captured order in response to an order event (return/cancel), idempotently on {@code
   * eventId}. The dedupe mark, the refund tender rows, and the {@code PaymentRefunded} outbox event
   * all commit in one transaction with the order's captured tenders locked {@code FOR UPDATE}, so a
   * concurrent manual refund cannot push the cumulative refund past what was captured.
   *
   * <p>{@code requestedAmount == null} refunds all remaining captured (cancellation); otherwise the
   * amount is capped at the remaining. Nothing captured / already fully refunded is a clean no-op.
   * The refund is spread across the order's tenders by residual capacity so split-tender sales stay
   * within each tender's cap. The event is built from the actually-refunded total via {@code
   * eventBuilder} so downstream sees the real amount.
   */
  public void refundOrderOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      BigDecimal requestedAmount,
      String reason,
      java.util.function.BiFunction<
              BigDecimal, List<com.storeql.payment.domain.Domain.RefundAllocation>, OutboxRow>
          eventBuilder) {
    inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return null; // this order event already produced its refund
          }
          List<PaymentTender> captured = capturedTendersForUpdateTx(c, tenantId, orderId);
          BigDecimal capturedTotal = BigDecimal.ZERO;
          for (PaymentTender t : captured) {
            capturedTotal = capturedTotal.add(t.amount());
          }
          BigDecimal remaining = capturedTotal.subtract(sumRefundsByOrderTx(c, tenantId, orderId));
          if (remaining.signum() <= 0) {
            return null; // unpaid (e.g. pay-later cancel) or already fully refunded — no-op
          }
          BigDecimal toRefund =
              requestedAmount == null ? remaining : requestedAmount.min(remaining);
          if (toRefund.signum() <= 0) {
            return null;
          }
          BigDecimal left = toRefund;
          List<com.storeql.payment.domain.Domain.RefundAllocation> shares = new ArrayList<>();
          for (PaymentTender t : captured) {
            if (left.signum() <= 0) break;
            BigDecimal residual = t.amount().subtract(sumRefundsTx(c, tenantId, t.id()));
            if (residual.signum() <= 0) continue;
            BigDecimal alloc = left.min(residual);
            insertRefundTenderTx(c, tenantId, orderId, t.id(), alloc, t.method(), reason);
            shares.add(
                new com.storeql.payment.domain.Domain.RefundAllocation(
                    t.id(), t.method(), alloc, t.storeId()));
            left = left.subtract(alloc);
          }
          insertOutbox(c, eventBuilder.apply(toRefund, shares));
          return null;
        },
        "refund order from event");
  }

  private List<PaymentTender> capturedTendersForUpdateTx(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    List<PaymentTender> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at, store_id"
                + " FROM payment_tenders"
                + " WHERE tenant_id=? AND order_id=? AND status='CAPTURED'"
                + " ORDER BY created_at ASC FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(mapTender(rs));
        }
      }
    }
    return out;
  }

  private static BigDecimal sumRefundsByOrderTx(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount), 0) AS total"
                + " FROM refund_tenders WHERE tenant_id=? AND order_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("total") : BigDecimal.ZERO;
      }
    }
  }

  private static void insertRefundTenderTx(
      Connection c,
      UUID tenantId,
      UUID orderId,
      UUID paymentId,
      BigDecimal amount,
      String method,
      String reason)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO refund_tenders"
                + " (id, tenant_id, order_id, payment_id, amount, method, reason, created_at)"
                + " VALUES (?,?,?,?,?,?,?, now())")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, orderId);
      ps.setObject(4, paymentId);
      ps.setBigDecimal(5, amount);
      ps.setString(6, method);
      ps.setString(7, reason);
      ps.executeUpdate();
    }
  }

  private static BigDecimal sumRefundsTx(java.sql.Connection c, UUID tenantId, UUID paymentId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount), 0) AS total"
                + " FROM refund_tenders WHERE tenant_id=? AND payment_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, paymentId);
      try (var rs = ps.executeQuery()) {
        // An aggregate without GROUP BY always yields exactly one row.
        return rs.next() ? rs.getBigDecimal("total") : BigDecimal.ZERO;
      }
    }
  }

  /**
   * Looks a tender up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param tenderId the tender to fetch
   * @return the tender, or empty when no such tender exists in this tenant
   */
  public Optional<PaymentTender> findTender(UUID tenantId, UUID tenderId) {
    var rows =
        query(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at, store_id"
                + " FROM payment_tenders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, tenderId);
            },
            this::mapTender,
            "find tender");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Find a captured tender by its idempotency key (used to make store-credit tenders idempotent).
   */
  public Optional<PaymentTender> findTenderByKey(UUID tenantId, String idempotencyKey) {
    var rows =
        query(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at, store_id"
                + " FROM payment_tenders WHERE tenant_id=? AND idempotency_key=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            this::mapTender,
            "find tender by key");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists every tender captured against one order.
   *
   * <p>A split-tender sale returns one row per tender, so callers totalling what was paid must sum
   * the rows rather than take the first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order whose tenders to list
   * @return the captured tenders, empty when nothing has been paid
   */
  public List<PaymentTender> findTendersByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, amount, method, reference,"
            + " idempotency_key, status, notes, created_at, store_id"
            + " FROM payment_tenders WHERE tenant_id=? AND order_id=?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapTender,
        "list tenders by order");
  }

  /**
   * Lists every refund recorded against one order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order whose refunds to list
   * @return the refunds, empty when nothing has been refunded
   */
  public List<RefundTender> findRefundsByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, payment_id, amount, method,"
            + " reference, idempotency_key, reason, created_at"
            + " FROM refund_tenders WHERE tenant_id=? AND order_id=?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapRefund,
        "list refunds by order");
  }

  // ── mappers ───────────────────────────────────────────────────────────────

  private PaymentTender mapTender(ResultSet rs) throws SQLException {
    return new PaymentTender(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("method"),
        rs.getString("reference"),
        rs.getString("idempotency_key"),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("store_id", UUID.class));
  }

  private RefundTender mapRefund(ResultSet rs) throws SQLException {
    return new RefundTender(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("method"),
        rs.getString("reference"),
        rs.getString("idempotency_key"),
        rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
