package com.storeql.payment.repo;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.domain.Terminals;
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
          if (PaymentTender.METHOD_CARD.equals(t.method())) recordMatchingApprovalTx(c, t);
          return t;
        },
        "create payment tender");
  }

  /**
   * A CARD tender that names no terminal attempt records the oldest approval on its order that no
   * tender records yet, at exactly its amount, with all of it still on the card — the one a till
   * that does not name the attempt has just taken. Without it the approval would hold its terminal
   * although the sale is paid. One that matches none is a card taken on a machine StoreQL does not
   * see, as before.
   *
   * <p>On an order given up, a card tender a machine's payment could stand for is refused: what
   * that machine took is owed back to the card, so recording it would say the customer paid for a
   * sale that never happened. A card no machine of ours may have taken is not this rule's.
   *
   * @throws ApiException 409 {@code PAYMENT_ORDER_GIVEN_UP}
   */
  private static void recordMatchingApprovalTx(Connection c, PaymentTender t) throws SQLException {
    // Locked first, as the give-up locks them, so the two are taken one after the other.
    List<CardSettlement.Facts> unrecorded =
        CardSettlementSql.unrecordedSalesOfOrderTx(c, t.tenantId(), t.orderId());
    if (CardSettlementSql.givenUpTx(c, t.tenantId(), t.orderId()) != null
        && CardSettlement.machineMayHaveTaken(
            CardSettlementSql.salesOfOrderTx(c, t.tenantId(), t.orderId()))) {
      throw CardSettlementSql.givenUpRefusal(t.orderId());
    }
    for (CardSettlement.Facts f : unrecorded) {
      Terminals.Attempt sale = f.attempt();
      if (t.storeId() != null && !t.storeId().equals(sale.storeId())) continue;
      String refusal =
          CardSettlement.linkRefusal(
              new CardSettlement.LinkFacts(
                  sale.kind(),
                  sale.state(),
                  f.outcome(),
                  sale.orderId(),
                  t.orderId(),
                  sale.amount(),
                  t.amount(),
                  sale.paymentId(),
                  f.committed()));
      if (refusal == null) {
        CardSettlementSql.recordAsTx(c, t.tenantId(), sale.id(), t.id());
        return;
      }
    }
  }

  /**
   * Records a CARD tender as the terminal approval it names, on one transaction: the approval's row
   * is locked, held to the rule (its own order, exactly its amount, approved, not recorded before,
   * nothing of it put back), and then the tender, its {@code PaymentCaptured} and the link are
   * written together. A replay of the tender's key answers with the tender the first call recorded.
   *
   * <p>Nothing is recorded on an order given up (cancelled or voided): what a machine took for it
   * is owed back to the card instead. The attempt's row is locked before that is read, as the
   * give-up locks it before it writes, so the two are taken one after the other.
   *
   * <p>The same lock orders this against a refund of the approval that answers late. A refund a
   * person said was not made counts for nothing here, so the approval is recorded whole; if the
   * machine then says it did put the money back, {@code TerminalRepository.bookRefundWithoutDue}
   * takes this row, finds the sale recorded, and writes the books' refund against this tender. A
   * refund the machine approved before this row was taken refuses the recording instead ({@code
   * TERMINAL_ATTEMPT_REFUNDED}). Never a tender standing whole for money that went back.
   *
   * @throws ApiException 404 {@code TERMINAL_ATTEMPT_NOT_FOUND}; 409 {@code
   *     PAYMENT_ORDER_GIVEN_UP}, {@code TERMINAL_ATTEMPT_OTHER_ORDER}, {@code
   *     TERMINAL_NOT_APPROVED}, {@code TERMINAL_ATTEMPT_ALREADY_RECORDED}, {@code
   *     TERMINAL_ATTEMPT_REFUNDED}, {@code TERMINAL_AMOUNT_MISMATCH}, {@code TERMINAL_NOT_A_SALE}
   *     or {@code TERMINAL_WRONG_STORE}
   */
  public PaymentTender createTerminalTender(PaymentTender t, OutboxRow event, UUID attemptId) {
    return inTx(
        c -> {
          if (t.idempotencyKey() != null) {
            PaymentTender existing = findTenderByKeyTx(c, t.tenantId(), t.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          Terminals.Attempt sale = CardSettlementSql.lockAttemptTx(c, t.tenantId(), attemptId);
          if (sale == null) {
            throw com.storeql.web.ApiException.notFound(
                "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal");
          }
          if (t.storeId() != null && !t.storeId().equals(sale.storeId())) {
            throw com.storeql.web.ApiException.conflict(
                "TERMINAL_WRONG_STORE",
                "That card payment was taken at another store's card machine");
          }
          if (CardSettlementSql.givenUpTx(c, t.tenantId(), t.orderId()) != null) {
            throw CardSettlementSql.givenUpRefusal(t.orderId());
          }
          var decision = CardSettlementSql.decisionOfTx(c, t.tenantId(), sale.id());
          String refusal =
              CardSettlement.linkRefusal(
                  new CardSettlement.LinkFacts(
                      sale.kind(),
                      sale.state(),
                      decision == null ? null : decision.outcome(),
                      sale.orderId(),
                      t.orderId(),
                      sale.amount(),
                      t.amount(),
                      sale.paymentId(),
                      CardSettlementSql.committedOnSaleTx(c, t.tenantId(), sale.id(), null)));
          if (refusal != null) {
            throw com.storeql.web.ApiException.conflict(refusal, LINK_REFUSALS.get(refusal));
          }
          insertTenderTx(c, t);
          insertOutbox(c, event);
          CardSettlementSql.recordAsTx(c, t.tenantId(), sale.id(), t.id());
          return t;
        },
        "record a card machine's payment");
  }

  private static final java.util.Map<String, String> LINK_REFUSALS =
      java.util.Map.of(
          "TERMINAL_NOT_A_SALE", "Only a card machine's sale is recorded as a tender",
          "TERMINAL_ATTEMPT_OTHER_ORDER", "That card payment was taken for another sale",
          "TERMINAL_NOT_APPROVED", "That card payment took no money, so there is nothing to record",
          "TERMINAL_ATTEMPT_ALREADY_RECORDED", "That card payment is already recorded on its sale",
          "TERMINAL_ATTEMPT_REFUNDED",
              "Some of that card payment has been put back on the card, so it is not recorded",
          "TERMINAL_AMOUNT_MISMATCH",
              "The tender is not the amount the card machine took; record exactly what it took");

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
   * Whether the caller may act at the store a tender was taken at; refuses by throwing. Handed to
   * the transaction that takes the tender's row, because that row is what says which store.
   */
  @FunctionalInterface
  public interface StoreGuard {

    /**
     * @param storeId the store the tender was taken at, or null for a tender taken at none
     * @throws com.storeql.web.ApiException 403 {@code STORE_ACCESS_DENIED} when the caller may not
     */
    void requireMayActAt(UUID storeId);
  }

  /**
   * Record a refund with the cumulative cap enforced atomically: the payment row is locked ({@code
   * FOR UPDATE}) before existing refunds are summed, so two concurrent refunds cannot both pass the
   * check and together exceed the original payment. Duplicate Idempotency-Key replays the stored
   * refund.
   *
   * <p>The tender's row is taken first, and what it says decides who may go on: another business's
   * tender is not found, and then the caller must be able to act at the store it was taken at
   * ({@code mayActAt}) — before a replay is answered, before anything is counted and before
   * anything is written. A refund is that store's: it carries the store's id, lowers its expected
   * cash and its X and day reports, and its {@code PaymentRefunded} moves that store's order.
   *
   * @param mayActAt asked once, with the store the tender was taken at
   * @throws com.storeql.web.ApiException 404 {@code PAYMENT_NOT_FOUND}; what {@code mayActAt}
   *     throws; 409 {@code IDEMPOTENCY_KEY_REUSED} for a key that already made a refund of another
   *     tender or on another order; 409 {@code PAYMENT_ORDER_MISMATCH}, {@code
   *     PAYMENT_REFUND_VIA_TERMINAL} or {@code REFUND_EXCEEDS_PAYMENT}
   */
  public RefundTender createRefundGuarded(RefundTender r, OutboxRow event, StoreGuard mayActAt) {
    return inTx(
        c -> {
          PaymentTender payment = lockTenderTx(c, r.tenantId(), r.paymentId());
          if (payment == null) {
            throw com.storeql.web.ApiException.notFound(
                "PAYMENT_NOT_FOUND", "payment tender not found");
          }
          mayActAt.requireMayActAt(payment.storeId());
          // Looked for with the tender's row ours, so a same-key request that waited on the lock
          // while the first committed replays the first refund instead of colliding on the unique
          // key. A key is one refund of one tender: used again for another, it is refused rather
          // than answered with a refund the caller did not name (and may not be allowed to read).
          if (r.idempotencyKey() != null) {
            RefundTender existing = findRefundByKeyTx(c, r.tenantId(), r.idempotencyKey());
            if (existing != null) {
              if (!existing.paymentId().equals(r.paymentId())
                  || !existing.orderId().equals(r.orderId())) {
                throw com.storeql.web.ApiException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "this Idempotency-Key was used for another refund");
              }
              return existing;
            }
          }
          if (!payment.orderId().equals(r.orderId())) {
            throw com.storeql.web.ApiException.conflict(
                "PAYMENT_ORDER_MISMATCH", "payment does not belong to this order");
          }

          // A card a terminal took goes back through that terminal, on the card that paid: a refund
          // written in the books alone would say the money went back while it stayed on the card.
          if (PaymentTender.METHOD_CARD.equals(r.method())) {
            Terminals.Attempt sale =
                CardSettlementSql.saleOfTenderTx(c, r.tenantId(), payment.id());
            if (sale != null) {
              throw new com.storeql.web.ApiException(
                  409,
                  "PAYMENT_REFUND_VIA_TERMINAL",
                  "This card payment was taken on a card machine: put it back there, on the card"
                      + " that paid (POST /payments/terminal/{attemptId}/refunds)",
                  List.of("attemptId=" + sale.id()));
            }
          }

          // What is owed back to a card against this tender is not in the books yet, and is spoken
          // for all the same: read with what is refunded in one statement, so a card refund landing
          // meanwhile is counted as one or the other, never neither.
          BigDecimal alreadyRefunded =
              CardSettlementSql.spokenForOnTenderTx(c, r.tenantId(), r.paymentId());
          if (alreadyRefunded.add(r.amount()).compareTo(payment.amount()) > 0) {
            throw com.storeql.web.ApiException.conflict(
                "REFUND_EXCEEDS_PAYMENT",
                "total refunds would exceed original payment of " + payment.amount());
          }

          try (var ps =
              c.prepareStatement(
                  "INSERT INTO refund_tenders"
                      + " (id, tenant_id, order_id, payment_id, amount, method,"
                      + "  reference, idempotency_key, reason, created_at, store_id)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
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
            // The store the payment was taken at: a refund is that store's, as its Z report and
            // tender mix read it. Null only for a payment recorded with no store.
            ps.setObject(11, payment.storeId());
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

  private static PaymentTender findTenderByKeyTx(
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
   * Refund a captured order in response to an order event (return/cancel/void), idempotently on
   * {@code eventId}. The dedupe mark, the refund tender rows, and the {@code PaymentRefunded}
   * outbox event all commit in one transaction with the order's captured tenders locked {@code FOR
   * UPDATE}, so a concurrent manual refund cannot push the cumulative refund past what was
   * captured.
   *
   * <p>{@code requestedAmount == null} refunds all remaining captured (cancellation); otherwise the
   * amount is capped at the remaining. Nothing captured / already fully refunded is a clean no-op.
   * The refund is spread across the order's tenders by residual capacity so split-tender sales stay
   * within each tender's cap. The event is built from the actually-refunded total via {@code
   * eventBuilder} so downstream sees the real amount.
   *
   * <p>A refund recorded under {@code methodOverride} (a return to STORE_CREDIT or a GIFT_CARD)
   * moves no money back to the card or the till: the value goes to a liability, and the refund row
   * names that method. Otherwise, with {@code toCard} given, the share of a tender a card terminal
   * took is not written in the books here but owed back to that card ({@code card_refund_dues}): it
   * is put back through the terminal, and the books' refund and its {@code PaymentRefunded} follow
   * when the machine has done it. A whole-order refund ({@code requestedAmount == null}: the order
   * cancelled or voided) gives the order up: it is recorded once ({@code given_up_orders}), and
   * what an approval on it that was never recorded as a tender still has on its card is owed back —
   * now, and for a sale that is approved, or seen approved by a person, after ({@code
   * CardSettlementSql.owedIfGivenUpTx}).
   *
   * <p>The order's unrecorded sales on terminals are locked before anything else, so a machine's
   * answer, a person's word or a tender naming one of them is taken wholly before this (and its
   * effect is seen here) or wholly after (and it sees the order given up).
   */
  public void refundOrderOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      BigDecimal requestedAmount,
      String reason,
      String methodOverride,
      CardSettlement.OwedBack toCard,
      java.util.function.BiFunction<
              BigDecimal, List<com.storeql.payment.domain.Domain.RefundAllocation>, OutboxRow>
          eventBuilder) {
    inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return null; // this order event already produced its refund
          }
          boolean givenUp = requestedAmount == null && toCard != null;
          if (givenUp) {
            // Read for its lock alone: what settles one of these sales waits for this, or this
            // for it, so the give-up and that answer are taken one after the other.
            CardSettlementSql.unrecordedSalesOfOrderTx(c, tenantId, orderId);
            CardSettlementSql.giveUpTx(c, tenantId, orderId, eventId, reason);
          }
          refundTx(
              c,
              tenantId,
              orderId,
              requestedAmount,
              reason,
              methodOverride,
              null,
              methodOverride == null ? toCard : null,
              eventBuilder);
          if (givenUp) {
            owedBackUnrecordedTx(c, tenantId, orderId, reason, toCard);
          }
          return null;
        },
        "refund order from event");
  }

  /**
   * What an order given up owes back on approvals that were never recorded as its tenders: none of
   * it is in the books, but all of it is on a card.
   */
  private static void owedBackUnrecordedTx(
      Connection c, UUID tenantId, UUID orderId, String reason, CardSettlement.OwedBack toCard)
      throws SQLException {
    for (CardSettlement.Facts f :
        CardSettlementSql.unrecordedSalesOfOrderTx(c, tenantId, orderId)) {
      BigDecimal owed = CardSettlement.owedWhenGivenUp(f);
      if (owed.signum() <= 0) continue;
      CardSettlementSql.insertDueOnceTx(
          c, CardSettlementSql.owedBack(f.attempt(), null, owed, reason, toCard));
    }
  }

  /**
   * The refund itself, on the caller's transaction: locks the order's captured tenders, caps the
   * amount at what remains, spreads it by residual capacity, writes the refund rows and the outbox
   * event. Returns what was actually refunded or owed back (zero when nothing was captured or all
   * was refunded).
   *
   * <p>Each refund row carries the store it belongs to: {@code storeOverride} when the caller names
   * one (an exchange is the store's where it was made), else the store of the payment refunded.
   *
   * <p>With {@code toCard} given, a CARD tender a terminal took is owed back to its card instead of
   * written in the books (see {@link #refundOrderOnce}); what is already owed back counts against
   * what remains, as a refund does.
   */
  private BigDecimal refundTx(
      Connection c,
      UUID tenantId,
      UUID orderId,
      BigDecimal requestedAmount,
      String reason,
      String methodOverride,
      UUID storeOverride,
      CardSettlement.OwedBack toCard,
      java.util.function.BiFunction<
              BigDecimal, List<com.storeql.payment.domain.Domain.RefundAllocation>, OutboxRow>
          eventBuilder)
      throws SQLException {
    List<PaymentTender> captured = capturedTendersForUpdateTx(c, tenantId, orderId);
    BigDecimal capturedTotal = BigDecimal.ZERO;
    for (PaymentTender t : captured) {
      capturedTotal = capturedTotal.add(t.amount());
    }
    BigDecimal remaining =
        capturedTotal.subtract(CardSettlementSql.spokenForOnOrderTx(c, tenantId, orderId));
    if (remaining.signum() <= 0) {
      return BigDecimal.ZERO; // unpaid (e.g. pay-later cancel) or already fully refunded — no-op
    }
    BigDecimal toRefund = requestedAmount == null ? remaining : requestedAmount.min(remaining);
    if (toRefund.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal left = toRefund;
    BigDecimal booked = BigDecimal.ZERO;
    List<com.storeql.payment.domain.Domain.RefundAllocation> shares = new ArrayList<>();
    for (PaymentTender t : captured) {
      if (left.signum() <= 0) break;
      BigDecimal residual =
          t.amount().subtract(CardSettlementSql.spokenForOnTenderTx(c, tenantId, t.id()));
      if (residual.signum() <= 0) continue;
      BigDecimal alloc = left.min(residual);
      left = left.subtract(alloc);
      Terminals.Attempt sale =
          toCard != null && PaymentTender.METHOD_CARD.equals(t.method())
              ? CardSettlementSql.saleOfTenderTx(c, tenantId, t.id())
              : null;
      if (sale != null) {
        CardSettlementSql.insertDueTx(
            c, CardSettlementSql.owedBack(sale, t.id(), alloc, reason, toCard));
        continue;
      }
      String method = methodOverride != null ? methodOverride : t.method();
      insertRefundTenderTx(
          c,
          tenantId,
          orderId,
          t.id(),
          alloc,
          method,
          reason,
          storeOverride != null ? storeOverride : t.storeId());
      shares.add(
          new com.storeql.payment.domain.Domain.RefundAllocation(
              t.id(), method, alloc, t.storeId()));
      booked = booked.add(alloc);
    }
    // Announced for what the books refunded now; what is owed back to a card is announced when the
    // machine has put it back.
    if (!shares.isEmpty()) insertOutbox(c, eventBuilder.apply(booked, shares));
    return toRefund;
  }

  /**
   * A gift card was charged by order-svc's redeem: record the GIFT_CARD tender for the order and
   * announce it, once per redemption. The dedupe mark, the tender and its {@code PaymentCaptured}
   * commit together; the tender's key is derived from the redemption, so a replay under another
   * event id still finds it.
   *
   * @return true when a tender was recorded, false on a replay
   */
  public boolean captureGiftCardOnce(
      UUID eventId, String consumer, PaymentTender tender, OutboxRow event) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          if (findTenderByKeyTx(c, tender.tenantId(), tender.idempotencyKey()) != null) {
            return false;
          }
          insertTenderTx(c, tender);
          insertOutbox(c, event);
          return true;
        },
        "capture gift card tender");
  }

  /** What one exchange moved: the EXCHANGE refund, the EXCHANGE tender, and the cash-back. */
  public record ExchangeMoved(BigDecimal exchanged, BigDecimal refundedOriginal) {}

  /**
   * An exchange return, once per event and on one transaction: (1) refund up to {@code
   * exchangeAmount} of the original order's captured tenders under method EXCHANGE (no provider
   * call), (2) capture an EXCHANGE tender of what actually moved on the new order, (3) when {@code
   * extraRefund} is positive, refund it on the original order to its original tenders — a card a
   * terminal took is owed back to that card ({@code toCard}). Any of the three is skipped when
   * nothing could move. Returns null on a replay.
   */
  public ExchangeMoved exchangeOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      BigDecimal exchangeAmount,
      BigDecimal extraRefund,
      String reason,
      UUID exchangeStoreId,
      java.util.function.BiFunction<
              BigDecimal, List<com.storeql.payment.domain.Domain.RefundAllocation>, OutboxRow>
          exchangeRefundEvent,
      java.util.function.BiFunction<
              BigDecimal, List<com.storeql.payment.domain.Domain.RefundAllocation>, OutboxRow>
          originalRefundEvent,
      java.util.function.Function<BigDecimal, OutboxRow> exchangeCapturedEvent,
      java.util.function.Function<BigDecimal, PaymentTender> exchangeTender,
      CardSettlement.OwedBack toCard) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return null;
          BigDecimal exchanged =
              refundTx(
                  c,
                  tenantId,
                  orderId,
                  exchangeAmount,
                  reason,
                  "EXCHANGE",
                  exchangeStoreId,
                  null,
                  exchangeRefundEvent);
          if (exchanged.signum() > 0) {
            insertTenderTx(c, exchangeTender.apply(exchanged));
            insertOutbox(c, exchangeCapturedEvent.apply(exchanged));
          }
          BigDecimal original = BigDecimal.ZERO;
          if (extraRefund != null && extraRefund.signum() > 0) {
            original =
                refundTx(
                    c,
                    tenantId,
                    orderId,
                    extraRefund,
                    reason,
                    null,
                    exchangeStoreId,
                    toCard,
                    originalRefundEvent);
          }
          return new ExchangeMoved(exchanged, original);
        },
        "exchange return from event");
  }

  private static List<PaymentTender> capturedTendersForUpdateTx(
      Connection c, UUID tenantId, UUID orderId) throws SQLException {
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

  private static void insertRefundTenderTx(
      Connection c,
      UUID tenantId,
      UUID orderId,
      UUID paymentId,
      BigDecimal amount,
      String method,
      String reason,
      UUID storeId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO refund_tenders"
                + " (id, tenant_id, order_id, payment_id, amount, method, reason, created_at,"
                + " store_id)"
                + " VALUES (?,?,?,?,?,?,?, now(), ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, orderId);
      ps.setObject(4, paymentId);
      ps.setBigDecimal(5, amount);
      ps.setString(6, method);
      ps.setString(7, reason);
      ps.setObject(8, storeId);
      ps.executeUpdate();
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
            PaymentRepository::mapTender,
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
            PaymentRepository::mapTender,
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
        PaymentRepository::mapTender,
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

  /**
   * When payment-svc began acting on an event kind it once ignored, as a migration kept it ({@code
   * events_handled_since}, V18): older events of that kind are history. Not a business's data, so
   * no tenant to filter by.
   *
   * @param eventType the event's type, e.g. {@code OrderVoided}
   * @return the moment, or empty when no migration names that kind
   */
  public Optional<java.time.Instant> handledSince(String eventType) {
    return query(
            "SELECT handled_since FROM events_handled_since WHERE event_type = ?",
            ps -> ps.setString(1, eventType),
            rs -> rs.getObject("handled_since", OffsetDateTime.class).toInstant(),
            "when payment-svc began acting on an event kind")
        .stream()
        .findFirst();
  }

  private static PaymentTender mapTender(ResultSet rs) throws SQLException {
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
