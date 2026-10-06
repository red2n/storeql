package com.storeql.payment.repo;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Disputes;
import com.storeql.payment.domain.Disputes.Dispute;
import com.storeql.payment.domain.Disputes.DisputeEvent;
import com.storeql.payment.domain.Disputes.Evidence;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chargebacks and disputes (11.9). Every change to a dispute writes what happened to its
 * append-only history and, where others care, an outbox row — in the transaction that made the
 * change. The moves that must not race (one open dispute per tender, evidence once, closed once)
 * are each a statement guarded on the state it expects.
 */
@ApplicationScoped
public class DisputeRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "SELECT id, tenant_id, payment_id, order_id, store_id, provider, provider_dispute_ref, amount,"
          + " fee_amount, currency, reason, network_reason_code, status, funds_withdrawn,"
          + " evidence_due_by, opened_at, closed_at, idempotency_key, created_by,"
          // A row from an export that names no fee currency: its fee was read in the dispute's own.
          + " COALESCE(fee_currency, currency) AS fee_currency FROM disputes";

  private static final String INSERT =
      "INSERT INTO disputes (id, tenant_id, payment_id, order_id, store_id, provider,"
          + " provider_dispute_ref, amount, fee_amount, currency, reason, network_reason_code,"
          + " status, funds_withdrawn, evidence_due_by, opened_at, idempotency_key, created_by,"
          + " created_at, updated_at, fee_currency) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String INSERT_EVENT =
      "INSERT INTO dispute_events (id, tenant_id, dispute_id, kind, detail, actor_id, created_at)"
          + " VALUES (?,?,?,?,?,?,?)";

  /** What opening a dispute came to. */
  public enum Opened {
    CREATED,
    /** The same request, or the same provider dispute, seen before: the stored one is returned. */
    REPLAYED,
    /** The tender already has a dispute open. */
    ALREADY_OPEN
  }

  /** The dispute, and how it came to be returned. */
  public record OpenResult(Opened outcome, Dispute dispute) {}

  /**
   * Opens a dispute with its first history entries and its event, or returns the one already there
   * for this idempotency key or provider reference.
   */
  public OpenResult open(Dispute d, List<DisputeEvent> history, OutboxRow event) {
    return inTx(
        c -> {
          Optional<Dispute> seen = existing(c, d);
          if (seen.isPresent()) return new OpenResult(Opened.REPLAYED, seen.get());
          Optional<Dispute> open = openForPayment(c, d.tenantId(), d.paymentId());
          if (open.isPresent()) return new OpenResult(Opened.ALREADY_OPEN, open.get());
          OffsetDateTime now = d.openedAt().atOffset(ZoneOffset.UTC);
          try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, d.id());
            ps.setObject(2, d.tenantId());
            ps.setObject(3, d.paymentId());
            ps.setObject(4, d.orderId());
            ps.setObject(5, d.storeId());
            ps.setString(6, d.provider());
            ps.setString(7, d.providerDisputeRef());
            ps.setBigDecimal(8, d.amount());
            ps.setBigDecimal(9, d.feeAmount());
            ps.setString(10, d.currency());
            ps.setString(11, d.reason());
            ps.setString(12, d.networkReasonCode());
            ps.setString(13, d.status());
            ps.setBoolean(14, d.fundsWithdrawn());
            ps.setObject(
                15, d.evidenceDueBy() == null ? null : d.evidenceDueBy().atOffset(ZoneOffset.UTC));
            ps.setObject(16, now);
            ps.setString(17, d.idempotencyKey());
            ps.setObject(18, d.createdBy());
            ps.setObject(19, now);
            ps.setObject(20, now);
            ps.setString(21, d.feeCurrency());
            ps.executeUpdate();
          }
          for (DisputeEvent e : history) insertEvent(c, d.tenantId(), d.id(), e);
          insertOutbox(c, event);
          return new OpenResult(Opened.CREATED, d);
        },
        "open dispute");
  }

  private Optional<Dispute> existing(Connection c, Dispute d) throws SQLException {
    if (d.idempotencyKey() != null) {
      try (PreparedStatement ps =
          c.prepareStatement(COLUMNS + " WHERE tenant_id = ? AND idempotency_key = ?")) {
        ps.setObject(1, d.tenantId());
        ps.setString(2, d.idempotencyKey());
        Optional<Dispute> found = one(ps);
        if (found.isPresent()) return found;
      }
    }
    if (!"MANUAL".equals(d.provider()) && d.providerDisputeRef() != null) {
      try (PreparedStatement ps =
          c.prepareStatement(COLUMNS + " WHERE provider = ? AND provider_dispute_ref = ?")) {
        ps.setString(1, d.provider());
        ps.setString(2, d.providerDisputeRef());
        return one(ps);
      }
    }
    return Optional.empty();
  }

  private Optional<Dispute> openForPayment(Connection c, UUID tenantId, UUID paymentId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            COLUMNS
                + " WHERE tenant_id = ? AND payment_id = ?"
                + " AND status IN ('NEEDS_RESPONSE', 'UNDER_REVIEW')")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, paymentId);
      return one(ps);
    }
  }

  public Optional<Dispute> find(UUID tenantId, UUID id) {
    return query(
            COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            DisputeRepository::map,
            "load dispute")
        .stream()
        .findFirst();
  }

  /**
   * A provider's dispute, whichever business it belongs to: a webhook carries no tenant, and the
   * provider's reference is the only key it has.
   */
  public Optional<Dispute> findByProviderRef(String provider, String providerDisputeRef) {
    return query(
            COLUMNS + " WHERE provider = ? AND provider_dispute_ref = ?",
            ps -> {
              ps.setString(1, provider);
              ps.setString(2, providerDisputeRef);
            },
            DisputeRepository::map,
            "load dispute by provider reference")
        .stream()
        .findFirst();
  }

  /** The register: newest first, optionally one status and one store. */
  public List<Dispute> list(
      UUID tenantId, String status, UUID storeId, Cursor.CreatedAtId after, int limit) {
    return query(
        COLUMNS
            + " WHERE tenant_id = ?"
            + " AND (CAST(? AS VARCHAR) IS NULL OR status = ?)"
            + " AND (CAST(? AS UUID) IS NULL OR store_id = ?)"
            + " AND (CAST(? AS TIMESTAMPTZ) IS NULL OR (opened_at, id) < (?, ?))"
            + " ORDER BY opened_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, status);
          ps.setString(3, status);
          ps.setObject(4, storeId);
          ps.setObject(5, storeId);
          OffsetDateTime at = after == null ? null : after.createdAt().atOffset(ZoneOffset.UTC);
          ps.setObject(6, at);
          ps.setObject(7, at);
          ps.setObject(8, after == null ? null : after.id());
          ps.setInt(9, limit);
        },
        DisputeRepository::map,
        "list disputes");
  }

  public List<DisputeEvent> events(UUID tenantId, UUID disputeId) {
    return query(
        "SELECT id, kind, detail, actor_id, created_at FROM dispute_events"
            + " WHERE tenant_id = ? AND dispute_id = ? ORDER BY created_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, disputeId);
        },
        rs ->
            new DisputeEvent(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, UUID.class),
                rs.getObject(5, OffsetDateTime.class).toInstant()),
        "list dispute events");
  }

  public Optional<Evidence> evidence(UUID tenantId, UUID disputeId) {
    return query(
            "SELECT product_description, customer_name, customer_email, receipt_reference,"
                + " fulfilment_proof, customer_communication, refund_policy, notes, submitted_by,"
                + " submitted_at FROM dispute_evidence WHERE tenant_id = ? AND dispute_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, disputeId);
            },
            rs ->
                new Evidence(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getObject(9, UUID.class),
                    rs.getObject(10, OffsetDateTime.class).toInstant()),
            "load dispute evidence")
        .stream()
        .findFirst();
  }

  /**
   * Records the business's answer and moves the dispute to review, if it was still waiting for one.
   *
   * @return false when it was not: answered already, or closed
   */
  public boolean submitEvidence(UUID tenantId, UUID id, Evidence e, DisputeEvent happened) {
    return inTx(
        c -> {
          if (!move(
              c,
              tenantId,
              id,
              Disputes.NEEDS_RESPONSE,
              Disputes.UNDER_REVIEW,
              null,
              e.submittedAt())) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO dispute_evidence (tenant_id, dispute_id, product_description,"
                      + " customer_name, customer_email, receipt_reference, fulfilment_proof,"
                      + " customer_communication, refund_policy, notes, submitted_by, submitted_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            ps.setString(3, e.productDescription());
            ps.setString(4, e.customerName());
            ps.setString(5, e.customerEmail());
            ps.setString(6, e.receiptReference());
            ps.setString(7, e.fulfilmentProof());
            ps.setString(8, e.customerCommunication());
            ps.setString(9, e.refundPolicy());
            ps.setString(10, e.notes());
            ps.setObject(11, e.submittedBy());
            ps.setObject(12, e.submittedAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertEvent(c, tenantId, id, happened);
          return true;
        },
        "submit dispute evidence");
  }

  /**
   * Closes a dispute — won, lost or accepted — if it is still open, with what happened and the
   * event that tells the ledger.
   *
   * @param fromStatus the one status it may be closed from, or null for any open status
   * @return false when it was not open (or not in {@code fromStatus})
   */
  public boolean close(
      UUID tenantId,
      UUID id,
      String fromStatus,
      String toStatus,
      Instant when,
      List<DisputeEvent> happened,
      OutboxRow event) {
    return inTx(
        c -> {
          boolean moved =
              fromStatus != null
                  ? move(c, tenantId, id, fromStatus, toStatus, when, when)
                  : move(c, tenantId, id, Disputes.NEEDS_RESPONSE, toStatus, when, when)
                      || move(c, tenantId, id, Disputes.UNDER_REVIEW, toStatus, when, when);
          if (!moved) return false;
          for (DisputeEvent e : happened) insertEvent(c, tenantId, id, e);
          insertOutbox(c, event);
          return true;
        },
        "close dispute");
  }

  /**
   * The acquirer has taken the money: set once, with the fee it charged.
   *
   * @return false when it had been recorded already
   */
  public boolean fundsWithdrawn(
      UUID tenantId,
      UUID id,
      BigDecimal fee,
      String feeCurrency,
      Instant when,
      DisputeEvent happened,
      OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE disputes SET funds_withdrawn = true, fee_amount = ?, fee_currency = ?,"
                      + " updated_at = ? WHERE tenant_id = ? AND id = ? AND funds_withdrawn = false")) {
            ps.setBigDecimal(1, fee);
            ps.setString(2, feeCurrency);
            ps.setObject(3, when.atOffset(ZoneOffset.UTC));
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            if (ps.executeUpdate() == 0) return false;
          }
          insertEvent(c, tenantId, id, happened);
          insertOutbox(c, event);
          return true;
        },
        "record dispute funds withdrawn");
  }

  private static boolean move(
      Connection c, UUID tenantId, UUID id, String from, String to, Instant closedAt, Instant when)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE disputes SET status = ?, closed_at = ?, updated_at = ?"
                + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
      ps.setString(1, to);
      ps.setObject(2, closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC));
      ps.setObject(3, when.atOffset(ZoneOffset.UTC));
      ps.setObject(4, tenantId);
      ps.setObject(5, id);
      ps.setString(6, from);
      return ps.executeUpdate() == 1;
    }
  }

  private static void insertEvent(Connection c, UUID tenantId, UUID disputeId, DisputeEvent e)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_EVENT)) {
      ps.setObject(1, e.id() == null ? Ids.newId() : e.id());
      ps.setObject(2, tenantId);
      ps.setObject(3, disputeId);
      ps.setString(4, e.kind());
      ps.setString(5, e.detail());
      ps.setObject(6, e.actorId());
      ps.setObject(7, e.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /** Disputes opened in a period, by how they stand, against the card payments of that period. */
  public Disputes.Summary summary(UUID tenantId, UUID storeId, Instant from, Instant to) {
    Disputes.Summary disputes =
        query(
                "SELECT count(*),"
                    + " count(*) FILTER (WHERE status = 'NEEDS_RESPONSE'),"
                    + " count(*) FILTER (WHERE status = 'UNDER_REVIEW'),"
                    + " count(*) FILTER (WHERE status = 'WON'),"
                    + " count(*) FILTER (WHERE status IN ('LOST', 'ACCEPTED')),"
                    + " COALESCE(SUM(amount), 0),"
                    + " COALESCE(SUM(amount) FILTER (WHERE status IN ('LOST', 'ACCEPTED')), 0),"
                    + " COALESCE(SUM(fee_amount) FILTER (WHERE funds_withdrawn), 0)"
                    + " FROM disputes WHERE tenant_id = ? AND opened_at >= ? AND opened_at < ?"
                    + " AND (CAST(? AS UUID) IS NULL OR store_id = ?)",
                ps -> period(ps, tenantId, storeId, from, to),
                rs ->
                    new Disputes.Summary(
                        rs.getInt(1),
                        rs.getInt(2),
                        rs.getInt(3),
                        rs.getInt(4),
                        rs.getInt(5),
                        rs.getBigDecimal(6),
                        rs.getBigDecimal(7),
                        rs.getBigDecimal(8),
                        0,
                        null),
                "summarise disputes")
            .get(0);
    int cardPayments =
        query(
                "SELECT count(*) FROM payment_tenders WHERE tenant_id = ? AND created_at >= ?"
                    + " AND created_at < ? AND (CAST(? AS UUID) IS NULL OR store_id = ?)"
                    + " AND status = 'CAPTURED' AND method NOT IN"
                    + " ('CASH', 'GIFT_CARD', 'VOUCHER', 'STORE_CREDIT')",
                ps -> period(ps, tenantId, storeId, from, to),
                rs -> rs.getInt(1),
                "count card payments")
            .get(0);
    BigDecimal ratio =
        cardPayments == 0
            ? null
            : BigDecimal.valueOf(disputes.opened())
                .divide(BigDecimal.valueOf(cardPayments), 4, java.math.RoundingMode.HALF_UP);
    return new Disputes.Summary(
        disputes.opened(),
        disputes.needsResponse(),
        disputes.underReview(),
        disputes.won(),
        disputes.lost(),
        disputes.amountDisputed(),
        disputes.amountLost(),
        disputes.feesCharged(),
        cardPayments,
        ratio);
  }

  private static void period(
      PreparedStatement ps, UUID tenantId, UUID storeId, Instant from, Instant to)
      throws SQLException {
    ps.setObject(1, tenantId);
    ps.setObject(2, from.atOffset(ZoneOffset.UTC));
    ps.setObject(3, to.atOffset(ZoneOffset.UTC));
    ps.setObject(4, storeId);
    ps.setObject(5, storeId);
  }

  private static Optional<Dispute> one(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      return rs.next() ? Optional.of(map(rs)) : Optional.empty();
    }
  }

  private static Dispute map(ResultSet rs) throws SQLException {
    OffsetDateTime due = rs.getObject(15, OffsetDateTime.class);
    OffsetDateTime closed = rs.getObject(17, OffsetDateTime.class);
    return new Dispute(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getObject(3, UUID.class),
        rs.getObject(4, UUID.class),
        rs.getObject(5, UUID.class),
        rs.getString(6),
        rs.getString(7),
        rs.getBigDecimal(8),
        rs.getBigDecimal(9),
        rs.getString(10),
        rs.getString(11),
        rs.getString(12),
        rs.getString(13),
        rs.getBoolean(14),
        due == null ? null : due.toInstant(),
        rs.getObject(16, OffsetDateTime.class).toInstant(),
        closed == null ? null : closed.toInstant(),
        rs.getString(18),
        rs.getObject(19, UUID.class),
        rs.getString(20));
  }
}
