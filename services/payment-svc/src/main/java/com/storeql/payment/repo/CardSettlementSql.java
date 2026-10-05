package com.storeql.payment.repo;

import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.CardSettlement.Decision;
import com.storeql.payment.domain.CardSettlement.Due;
import com.storeql.payment.domain.Terminals;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Card settlement on the caller's own transaction (07.16 follow-up): a person's word on a timed-out
 * attempt, the money owed back to a card, and what is still on a card. Used by {@link
 * TerminalRepository} and {@link PaymentRepository} alike, because a tender recorded or refunded
 * and the terminal attempt it belongs to are one fact and are written on one transaction.
 */
final class CardSettlementSql {

  private CardSettlementSql() {}

  // ── decisions ───────────────────────────────────────────────────────────────

  private static final String DECISION_COLUMNS =
      "SELECT id, tenant_id, store_id, attempt_id, outcome, reason, idempotency_key, decided_by,"
          + " decided_at FROM terminal_attempt_decisions";

  static Decision decisionOfTx(Connection c, UUID tenantId, UUID attemptId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(DECISION_COLUMNS + " WHERE tenant_id = ? AND attempt_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, attemptId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readDecision(rs) : null;
      }
    }
  }

  static Decision decisionByKeyTx(Connection c, UUID tenantId, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(DECISION_COLUMNS + " WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readDecision(rs) : null;
      }
    }
  }

  static void insertDecisionTx(Connection c, Decision d) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO terminal_attempt_decisions (id, tenant_id, store_id, attempt_id, outcome,"
                + " reason, idempotency_key, decided_by, decided_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, d.id());
      ps.setObject(2, d.tenantId());
      ps.setObject(3, d.storeId());
      ps.setObject(4, d.attemptId());
      ps.setString(5, d.outcome());
      ps.setString(6, d.reason());
      ps.setString(7, d.idempotencyKey());
      ps.setObject(8, d.decidedBy());
      ps.setObject(9, d.decidedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  static Decision readDecision(ResultSet rs) throws SQLException {
    return new Decision(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("attempt_id", UUID.class),
        rs.getString("outcome"),
        rs.getString("reason"),
        rs.getString("idempotency_key"),
        rs.getObject("decided_by", UUID.class),
        instant(rs, "decided_at"));
  }

  // ── what is still on a card ─────────────────────────────────────────────────

  /**
   * What has left a sale's card, or may have, or is owed back — the cap on another refund of it,
   * and on what an order given up owes back: every due for it that is not a person's refund the
   * machine refused, and every refund asked for nothing owed (a person's on a sale never recorded)
   * that may have moved money ({@link CardSettlement#mayHaveGoneBack}).
   *
   * @param excludingDue a due left out, when the question is whether that due may be asked
   */
  static BigDecimal committedOnSaleTx(
      Connection c, UUID tenantId, UUID saleAttemptId, UUID excludingDue) throws SQLException {
    OnSale on = onSaleTx(c, tenantId, saleAttemptId, excludingDue);
    return CardSettlement.mayHaveGoneBack(on.dues(), on.refunds());
  }

  /**
   * What has gone back on a sale's card, as the guard on its terminal counts it: its dues, and the
   * refunds asked for nothing owed that took money ({@link CardSettlement#wentBack}). A refund
   * still at the machine, or timed out with nobody's word on it, is not back: the sale keeps its
   * machine held until it is known.
   */
  static BigDecimal wentBackOnSaleTx(Connection c, UUID tenantId, UUID saleAttemptId)
      throws SQLException {
    OnSale on = onSaleTx(c, tenantId, saleAttemptId, null);
    return CardSettlement.wentBack(on.dues(), on.refunds());
  }

  /** A sale's dues (summed) and its refunds asked for nothing owed, each with a person's word. */
  private record OnSale(BigDecimal dues, List<CardSettlement.RefundSeen> refunds) {}

  /**
   * A sale's dues, each with what its own refunds put back: one row per due and refund asked for it
   * (a due no refund was asked for yet has one row, with no refund).
   */
  private static final String DUES_OF_SALE =
      "SELECT d.id, d.amount, d.state, r.amount AS refund_amount, r.state AS refund_state,"
          + " x.outcome FROM card_refund_dues d"
          + " LEFT JOIN terminal_payments r ON r.tenant_id = d.tenant_id AND r.due_id = d.id"
          + " AND r.kind = 'REFUND'"
          + " LEFT JOIN terminal_attempt_decisions x ON x.tenant_id = r.tenant_id"
          + " AND x.attempt_id = r.id"
          + " WHERE d.tenant_id = ? AND d.sale_attempt_id = ?";

  private static OnSale onSaleTx(Connection c, UUID tenantId, UUID saleAttemptId, UUID excludingDue)
      throws SQLException {
    // Each due holds what it is for while it counts, or what its own refunds put back when that
    // is more (CardSettlement.dueHolds): a refund the machine approved after a person said it was
    // not made is off the card, whatever its due says.
    java.util.Map<UUID, BigDecimal> amounts = new java.util.LinkedHashMap<>();
    java.util.Map<UUID, String> states = new java.util.HashMap<>();
    java.util.Map<UUID, BigDecimal> putBack = new java.util.HashMap<>();
    try (PreparedStatement ps = c.prepareStatement(DUES_OF_SALE)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, saleAttemptId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID due = rs.getObject("id", UUID.class);
          if (due.equals(excludingDue)) continue;
          amounts.put(due, rs.getBigDecimal("amount"));
          states.put(due, rs.getString("state"));
          BigDecimal refund = rs.getBigDecimal("refund_amount");
          if (refund != null
              && CardSettlement.tookMoney(rs.getString("refund_state"), rs.getString("outcome"))) {
            putBack.merge(due, refund, BigDecimal::add);
          }
        }
      }
    }
    BigDecimal dues = BigDecimal.ZERO;
    for (java.util.Map.Entry<UUID, BigDecimal> due : amounts.entrySet()) {
      dues =
          dues.add(
              CardSettlement.dueHolds(
                  states.get(due.getKey()), due.getValue(), putBack.get(due.getKey())));
    }
    List<CardSettlement.RefundSeen> refunds = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT r.amount, r.state, x.outcome FROM terminal_payments r"
                + " LEFT JOIN terminal_attempt_decisions x"
                + " ON x.tenant_id = r.tenant_id AND x.attempt_id = r.id"
                + " WHERE r.tenant_id = ? AND r.refund_of = ? AND r.kind = 'REFUND'"
                + " AND r.due_id IS NULL")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, saleAttemptId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          refunds.add(
              new CardSettlement.RefundSeen(
                  rs.getString("state"), rs.getString("outcome"), rs.getBigDecimal("amount")));
        }
      }
    }
    return new OnSale(dues, refunds);
  }

  /**
   * A sale's refunds that are not accounted for yet — still at the machine, or timed out (whether
   * anybody has said what it shows is read beside it) — oldest first, each with a person's word on
   * it. Read with the sale's row locked by the caller, so a refund claimed meanwhile is seen.
   */
  static List<CardSettlement.Facts> openRefundsOfSaleTx(
      Connection c, UUID tenantId, UUID saleAttemptId) throws SQLException {
    List<Terminals.Attempt> asked = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            TerminalRepository.ATTEMPT_COLUMNS
                + " WHERE tenant_id = ? AND refund_of = ? AND kind = 'REFUND'"
                + " AND state IN ('REQUESTED', 'TIMED_OUT') ORDER BY requested_at, id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, saleAttemptId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) asked.add(TerminalRepository.readAttempt(rs));
      }
    }
    List<CardSettlement.Facts> out = new ArrayList<>();
    for (Terminals.Attempt a : asked) {
      out.add(new CardSettlement.Facts(a, decisionOfTx(c, tenantId, a.id()), BigDecimal.ZERO));
    }
    return out;
  }

  /** The refund attempts asked for one due, oldest first, each with a person's word on it. */
  static List<CardSettlement.Facts> attemptsOfDueTx(Connection c, UUID tenantId, UUID dueId)
      throws SQLException {
    List<Terminals.Attempt> asked = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            TerminalRepository.ATTEMPT_COLUMNS
                + " WHERE tenant_id = ? AND due_id = ? ORDER BY requested_at, id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, dueId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) asked.add(TerminalRepository.readAttempt(rs));
      }
    }
    List<CardSettlement.Facts> out = new ArrayList<>();
    for (Terminals.Attempt a : asked) {
      out.add(new CardSettlement.Facts(a, decisionOfTx(c, tenantId, a.id()), BigDecimal.ZERO));
    }
    return out;
  }

  // ── a sale and the tender it became ─────────────────────────────────────────

  /** The terminal sale a tender records, or null for a tender no terminal took. */
  static Terminals.Attempt saleOfTenderTx(Connection c, UUID tenantId, UUID paymentId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            TerminalRepository.ATTEMPT_COLUMNS
                + " WHERE tenant_id = ? AND payment_id = ? AND kind = 'SALE'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? TerminalRepository.readAttempt(rs) : null;
      }
    }
  }

  /** An attempt as it stands, read on the caller's transaction without taking its row. */
  static Terminals.Attempt attemptTx(Connection c, UUID tenantId, UUID attemptId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            TerminalRepository.ATTEMPT_COLUMNS + " WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, attemptId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? TerminalRepository.readAttempt(rs) : null;
      }
    }
  }

  /**
   * Locks a recorded tender's row, as every refund written in the books against it does first, so
   * refunds of one tender are counted one after the other.
   *
   * @return whether the business has such a tender
   */
  static boolean lockTenderTx(Connection c, UUID tenantId, UUID paymentId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id FROM payment_tenders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  static Terminals.Attempt lockAttemptTx(Connection c, UUID tenantId, UUID attemptId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            TerminalRepository.ATTEMPT_COLUMNS + " WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, attemptId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? TerminalRepository.readAttempt(rs) : null;
      }
    }
  }

  /**
   * Records an approval as the tender it became. One move from null, and never back: the unique
   * index on the tender makes a second tender for the same approval impossible as well.
   */
  static void recordAsTx(Connection c, UUID tenantId, UUID attemptId, UUID paymentId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE terminal_payments SET payment_id = ? WHERE tenant_id = ? AND id = ?"
                + " AND payment_id IS NULL")) {
      ps.setObject(1, paymentId);
      ps.setObject(2, tenantId);
      ps.setObject(3, attemptId);
      ps.executeUpdate();
    }
  }

  /**
   * An order's sales on a terminal that no tender records and that may yet hold money — at the
   * machine, approved, or timed out — locked, oldest first: the ones a CARD tender with no attempt
   * named may be recording, and the ones an order given up owes back.
   *
   * <p>The lock is what orders an order given up against everything that settles one of its sales.
   * The give-up takes these rows first; a machine's answer, a person's word and a tender naming one
   * each lock its row before they read whether the order was given up. So each is taken wholly
   * before the give-up (which then sees what it did) or wholly after (and sees the order given up),
   * and an approval is never owed back by neither.
   */
  static List<CardSettlement.Facts> unrecordedSalesOfOrderTx(
      Connection c, UUID tenantId, UUID orderId) throws SQLException {
    return facts(
        c,
        tenantId,
        TerminalRepository.ATTEMPT_COLUMNS
            + " WHERE tenant_id = ? AND order_id = ? AND kind = 'SALE' AND payment_id IS NULL"
            + " AND state IN ('REQUESTED', 'APPROVED', 'TIMED_OUT') ORDER BY requested_at, id"
            + " FOR UPDATE",
        orderId);
  }

  /** Every sale on a terminal for an order, recorded or not, with a person's word on each. */
  static List<CardSettlement.Facts> salesOfOrderTx(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    return facts(
        c,
        tenantId,
        TerminalRepository.ATTEMPT_COLUMNS
            + " WHERE tenant_id = ? AND order_id = ? AND kind = 'SALE' ORDER BY requested_at, id",
        orderId);
  }

  private static List<CardSettlement.Facts> facts(
      Connection c, UUID tenantId, String sql, UUID orderId) throws SQLException {
    List<Terminals.Attempt> sales = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) sales.add(TerminalRepository.readAttempt(rs));
      }
    }
    List<CardSettlement.Facts> out = new ArrayList<>();
    for (Terminals.Attempt sale : sales) {
      out.add(
          new CardSettlement.Facts(
              sale,
              decisionOfTx(c, tenantId, sale.id()),
              committedOnSaleTx(c, tenantId, sale.id(), null)));
    }
    return out;
  }

  // ── an order given up ───────────────────────────────────────────────────────

  /** The order event that gave an order up, and why. */
  record GivenUp(UUID eventId, String reason) {}

  /** The event that gave an order up, or null while it stands (as far as payment-svc knows). */
  static GivenUp givenUpTx(Connection c, UUID tenantId, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT event_id, reason FROM given_up_orders WHERE tenant_id = ? AND order_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? new GivenUp(rs.getObject("event_id", UUID.class), rs.getString("reason"))
            : null;
      }
    }
  }

  /** Records that an order was given up, once: a second event that gives it up changes nothing. */
  static void giveUpTx(Connection c, UUID tenantId, UUID orderId, UUID eventId, String reason)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO given_up_orders (id, tenant_id, order_id, event_id, reason, given_up_at)"
                + " VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id, order_id) DO NOTHING")) {
      ps.setObject(1, com.storeql.ids.Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, orderId);
      ps.setObject(4, eventId);
      ps.setString(5, reason == null || reason.isBlank() ? "Order given up" : reason);
      ps.setObject(6, Instant.now().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /**
   * Owes back what a sale took when its order was given up, on the caller's transaction, with the
   * sale's row locked by the caller: the moment a machine's answer or a person's word makes it a
   * sale that took money. Under the key the give-up itself would have used, so it is owed once
   * whichever learns of it first.
   *
   * @param sale the attempt as it now stands
   * @return whether money is now owed back that was not before
   */
  static boolean owedIfGivenUpTx(Connection c, Terminals.Attempt sale) throws SQLException {
    if (!Terminals.SALE.equals(sale.kind()) || sale.paymentId() != null) return false;
    UUID tenantId = sale.tenantId();
    GivenUp givenUp = givenUpTx(c, tenantId, sale.orderId());
    if (givenUp == null) return false;
    BigDecimal owed =
        CardSettlement.owedWhenGivenUp(
            new CardSettlement.Facts(
                sale,
                decisionOfTx(c, tenantId, sale.id()),
                committedOnSaleTx(c, tenantId, sale.id(), null)));
    if (owed.signum() <= 0) return false;
    return insertDueOnceTx(
        c,
        owedBack(
            sale,
            null,
            owed,
            givenUp.reason(),
            new CardSettlement.OwedBack(givenUp.eventId(), null, null, null, null)));
  }

  /**
   * Money owed back to a card for an order event: by the platform, under a key derived from the
   * event and the sale, so the same event never owes the same sale twice.
   *
   * @param paymentId the recorded tender it is drawn against, or null for an approval never
   *     recorded
   */
  static Due owedBack(
      Terminals.Attempt sale,
      UUID paymentId,
      BigDecimal amount,
      String reason,
      CardSettlement.OwedBack toCard) {
    Instant now = Instant.now();
    return new Due(
        com.storeql.ids.Ids.newId(),
        sale.tenantId(),
        sale.storeId(),
        sale.orderId(),
        sale.id(),
        paymentId,
        amount,
        sale.currency(),
        reason,
        CardSettlement.FROM_ORDER_EVENT,
        com.storeql.ids.Ids.derived(toCard.eventId(), "card-refund:" + sale.id()).toString(),
        toCard.refundKind(),
        toCard.refundMethod(),
        toCard.returnId(),
        toCard.customerId(),
        CardSettlement.OWED,
        null,
        null,
        null,
        null,
        now,
        now);
  }

  /**
   * The refusal for anything that would take or record a card for an order given up.
   *
   * @return 409 {@code PAYMENT_ORDER_GIVEN_UP}, naming the order
   */
  static com.storeql.web.ApiException givenUpRefusal(UUID orderId) {
    return new com.storeql.web.ApiException(
        409,
        "PAYMENT_ORDER_GIVEN_UP",
        "That sale was cancelled or voided: no card is taken or recorded for it, and what a card"
            + " machine took for it goes back to the card",
        List.of("orderId=" + orderId));
  }

  // ── money owed back to a card ───────────────────────────────────────────────

  static final String DUE_COLUMNS =
      "SELECT id, tenant_id, store_id, order_id, sale_attempt_id, payment_id, amount, currency,"
          + " reason, source, idempotency_key, refund_kind, refund_method, return_id, customer_id,"
          + " state, attention, refund_attempt_id, refund_id, requested_by, created_at, updated_at"
          + " FROM card_refund_dues";

  static void insertDueTx(Connection c, Due d) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO card_refund_dues (id, tenant_id, store_id, order_id, sale_attempt_id,"
                + " payment_id, amount, currency, reason, source, idempotency_key, refund_kind,"
                + " refund_method, return_id, customer_id, state, requested_by, created_at,"
                + " updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, d.id());
      ps.setObject(2, d.tenantId());
      ps.setObject(3, d.storeId());
      ps.setObject(4, d.orderId());
      ps.setObject(5, d.saleAttemptId());
      ps.setObject(6, d.paymentId());
      ps.setBigDecimal(7, d.amount());
      ps.setString(8, d.currency());
      ps.setString(9, d.reason());
      ps.setString(10, d.source());
      ps.setString(11, d.idempotencyKey());
      ps.setString(12, d.refundKind());
      ps.setString(13, d.refundMethod());
      ps.setObject(14, d.returnId());
      ps.setObject(15, d.customerId());
      ps.setString(16, d.state());
      ps.setObject(17, d.requestedBy());
      ps.setObject(18, d.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(19, d.updatedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /**
   * Writes money owed back unless the same key already owes it: the give-up and a sale's own answer
   * may both learn of one approval, and it is owed once.
   *
   * @return whether this call wrote it
   */
  static boolean insertDueOnceTx(Connection c, Due d) throws SQLException {
    if (dueByKeyTx(c, d.tenantId(), d.idempotencyKey()) != null) return false;
    insertDueTx(c, d);
    return true;
  }

  static Due dueByKeyTx(Connection c, UUID tenantId, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(DUE_COLUMNS + " WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readDue(rs) : null;
      }
    }
  }

  static Due lockDueTx(Connection c, UUID tenantId, UUID dueId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(DUE_COLUMNS + " WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, dueId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readDue(rs) : null;
      }
    }
  }

  /**
   * What a recorded tender has already given back or is holding to give back: what the books have
   * refunded from it, and what is still owed back to its card (not yet in the books, and spoken for
   * all the same). The cap on another refund of it.
   *
   * <p>Read in <b>one statement</b>, so in one snapshot. A card refund the machine approves moves a
   * sum from owed to refunded on one transaction ({@code TerminalRepository.completeDue}); read as
   * two statements, refunded first, a commit between them showed the sum in neither and a tender of
   * 30.00 gave back 60.00. The caller holds the tender's row (as that transaction now does), so the
   * two are also taken one after the other; one snapshot is what keeps the count whole even for a
   * writer that does not.
   */
  static BigDecimal spokenForOnTenderTx(Connection c, UUID tenantId, UUID paymentId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT (SELECT COALESCE(SUM(amount), 0) FROM refund_tenders"
                + " WHERE tenant_id = ? AND payment_id = ?)"
                + " + (SELECT COALESCE(SUM(amount), 0) FROM card_refund_dues"
                + " WHERE tenant_id = ? AND payment_id = ?"
                + " AND state IN ('OWED', 'NEEDS_ATTENTION')) AS spoken_for")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, paymentId);
      ps.setObject(3, tenantId);
      ps.setObject(4, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        // Two aggregates with no GROUP BY, each COALESCEd: exactly one row, never null.
        return rs.next() ? rs.getBigDecimal("spoken_for") : BigDecimal.ZERO;
      }
    }
  }

  /**
   * The same for every recorded tender of an order together: what the books have refunded on the
   * order, and what is still owed back to cards against its tenders. One statement, one snapshot,
   * for the reason {@link #spokenForOnTenderTx} gives.
   */
  static BigDecimal spokenForOnOrderTx(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT (SELECT COALESCE(SUM(amount), 0) FROM refund_tenders"
                + " WHERE tenant_id = ? AND order_id = ?)"
                + " + (SELECT COALESCE(SUM(amount), 0) FROM card_refund_dues"
                + " WHERE tenant_id = ? AND order_id = ? AND payment_id IS NOT NULL"
                + " AND state IN ('OWED', 'NEEDS_ATTENTION')) AS spoken_for")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, tenantId);
      ps.setObject(4, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        // Two aggregates with no GROUP BY, each COALESCEd: exactly one row, never null.
        return rs.next() ? rs.getBigDecimal("spoken_for") : BigDecimal.ZERO;
      }
    }
  }

  /**
   * Moves a due on after its refund was answered. A due put back stays put back: an answer that
   * arrives late for an earlier attempt never undoes it.
   *
   * @return whether the due moved
   */
  static boolean moveDueTx(
      Connection c,
      UUID tenantId,
      UUID dueId,
      String state,
      String attention,
      UUID refundAttemptId,
      UUID refundId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE card_refund_dues SET state = ?, attention = ?,"
                + " refund_attempt_id = COALESCE(?, refund_attempt_id),"
                + " refund_id = COALESCE(?, refund_id), updated_at = ?"
                + " WHERE tenant_id = ? AND id = ? AND state IN ('OWED', 'NEEDS_ATTENTION')")) {
      ps.setString(1, state);
      ps.setString(2, attention);
      ps.setObject(3, refundAttemptId);
      ps.setObject(4, refundId);
      ps.setObject(5, Instant.now().atOffset(ZoneOffset.UTC));
      ps.setObject(6, tenantId);
      ps.setObject(7, dueId);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * A person's refund the machine was taken to have refused, put back after all: the machine
   * answered late that it did (or it is the books' only record of what went back). Moves only from
   * NOT_REFUNDED, and only to REFUNDED by that refund.
   *
   * @return whether the due moved
   */
  static boolean putBackAfterAllTx(
      Connection c, UUID tenantId, UUID dueId, UUID refundAttemptId, UUID refundId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE card_refund_dues SET state = 'REFUNDED', attention = NULL,"
                + " refund_attempt_id = ?, refund_id = COALESCE(?, refund_id), updated_at = ?"
                + " WHERE tenant_id = ? AND id = ? AND state = 'NOT_REFUNDED'")) {
      ps.setObject(1, refundAttemptId);
      ps.setObject(2, refundId);
      ps.setObject(3, Instant.now().atOffset(ZoneOffset.UTC));
      ps.setObject(4, tenantId);
      ps.setObject(5, dueId);
      return ps.executeUpdate() == 1;
    }
  }

  /** A due as it stands, read on the caller's transaction without taking its row. */
  static Due dueTx(Connection c, UUID tenantId, UUID dueId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(DUE_COLUMNS + " WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, dueId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readDue(rs) : null;
      }
    }
  }

  // ── money owed back, given back another way ─────────────────────────────────

  private static final String CLOSURE_COLUMNS =
      "SELECT id, tenant_id, store_id, due_id, method, reference, reason, idempotency_key,"
          + " closed_by, closed_at FROM card_refund_due_closures";

  static CardSettlement.Closure closureOfTx(Connection c, UUID tenantId, UUID dueId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(CLOSURE_COLUMNS + " WHERE tenant_id = ? AND due_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, dueId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readClosure(rs) : null;
      }
    }
  }

  static CardSettlement.Closure closureByKeyTx(Connection c, UUID tenantId, String key)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(CLOSURE_COLUMNS + " WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readClosure(rs) : null;
      }
    }
  }

  static void insertClosureTx(Connection c, CardSettlement.Closure k) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO card_refund_due_closures (id, tenant_id, store_id, due_id, method,"
                + " reference, reason, idempotency_key, closed_by, closed_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, k.id());
      ps.setObject(2, k.tenantId());
      ps.setObject(3, k.storeId());
      ps.setObject(4, k.dueId());
      ps.setString(5, k.method());
      ps.setString(6, k.reference());
      ps.setString(7, k.reason());
      ps.setString(8, k.idempotencyKey());
      ps.setObject(9, k.closedBy());
      ps.setObject(10, k.closedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /**
   * Moves a due a machine did not put back to given back another way. Only from waiting for a
   * person, and never back: what a person gave back by hand stays given.
   *
   * @param refundId the books' refund written with it, or null when nothing was in the books
   * @return whether the due moved
   */
  static boolean closeAnotherWayTx(Connection c, UUID tenantId, UUID dueId, UUID refundId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE card_refund_dues SET state = 'REFUNDED_ANOTHER_WAY', attention = NULL,"
                + " refund_id = COALESCE(?, refund_id), updated_at = ?"
                + " WHERE tenant_id = ? AND id = ? AND state = 'NEEDS_ATTENTION'")) {
      ps.setObject(1, refundId);
      ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
      ps.setObject(3, tenantId);
      ps.setObject(4, dueId);
      return ps.executeUpdate() == 1;
    }
  }

  static CardSettlement.Closure readClosure(ResultSet rs) throws SQLException {
    return new CardSettlement.Closure(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("due_id", UUID.class),
        rs.getString("method"),
        rs.getString("reference"),
        rs.getString("reason"),
        rs.getString("idempotency_key"),
        rs.getObject("closed_by", UUID.class),
        instant(rs, "closed_at"));
  }

  static Due readDue(ResultSet rs) throws SQLException {
    return new Due(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("sale_attempt_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("reason"),
        rs.getString("source"),
        rs.getString("idempotency_key"),
        rs.getString("refund_kind"),
        rs.getString("refund_method"),
        rs.getObject("return_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("state"),
        rs.getString("attention"),
        rs.getObject("refund_attempt_id", UUID.class),
        rs.getObject("refund_id", UUID.class),
        rs.getObject("requested_by", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
