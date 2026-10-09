package com.storeql.payment.repo;

import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.CardSettlement.Decision;
import com.storeql.payment.domain.CardSettlement.Due;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.domain.Terminals.Attempt;
import com.storeql.payment.domain.Terminals.Terminal;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The terminals a business has, and every attempt made at one (07.16).
 *
 * <p><b>The claim happens before the card does.</b> {@link #claim} writes the attempt and its
 * idempotency key in one transaction, <em>before</em> the terminal is asked for anything — so a
 * retried "take card" finds the first attempt and returns it rather than starting a second EMV
 * transaction. A terminal payment is the canonical double-charge: the cashier presses the button
 * again because the screen did not change, and a real customer is charged twice. Claiming
 * afterwards would leave the window open for exactly as long as a cardholder takes to enter a PIN.
 *
 * <p><b>And a terminal with a card payment still unsettled takes no new one.</b> A new key used to
 * be a new payment, which is how a sale changed mid-payment was charged twice when the till lost
 * what it remembered. The claim of a sale, and of a refund, locks its terminal's row, so two first
 * presses on one terminal are taken one after the other and the second sees the first. A refund not
 * known to have gone through holds the machine as a sale does, and does not settle the sale.
 */
@ApplicationScoped
public class TerminalRepository extends BaseOutboxRepository {

  // ── the devices ─────────────────────────────────────────────────────────────

  private static final String TERMINAL_COLUMNS =
      "SELECT id, tenant_id, store_id, label, vendor, serial, status, retired_reason, created_at,"
          + " updated_at FROM card_terminals";

  private static final String INSERT_TERMINAL =
      "INSERT INTO card_terminals (id, tenant_id, store_id, label, vendor, serial, status,"
          + " created_at, created_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";

  private static final String RETIRE_TERMINAL =
      "UPDATE card_terminals SET status = 'RETIRED', retired_reason = ?, updated_at = ?"
          + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'";

  public Terminal add(Terminal t, UUID actorId) {
    exec(
        INSERT_TERMINAL,
        ps -> {
          ps.setObject(1, t.id());
          ps.setObject(2, t.tenantId());
          ps.setObject(3, t.storeId());
          ps.setString(4, t.label());
          ps.setString(5, t.vendor());
          ps.setString(6, t.serial());
          ps.setString(7, Terminals.ACTIVE);
          ps.setObject(8, t.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(9, actorId);
          ps.setObject(10, t.createdAt().atOffset(ZoneOffset.UTC));
        },
        "register a card terminal");
    return t;
  }

  /** Every terminal a business has, newest first, retired ones included so history reads. */
  public List<Terminal> of(UUID tenantId) {
    return query(
        TERMINAL_COLUMNS + " WHERE tenant_id = ? ORDER BY created_at DESC",
        ps -> ps.setObject(1, tenantId),
        TerminalRepository::readTerminal,
        "card terminals");
  }

  /** Whether the store has at least one ACTIVE card machine (a retired one is no machine). */
  public boolean hasActiveAt(UUID tenantId, UUID storeId) {
    return !query(
            "SELECT 1 FROM card_terminals WHERE tenant_id = ? AND store_id = ? AND status = 'ACTIVE'"
                + " LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> 1,
            "store has an active card machine")
        .isEmpty();
  }

  public Optional<Terminal> find(UUID tenantId, UUID id) {
    return query(
            TERMINAL_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            TerminalRepository::readTerminal,
            "a card terminal")
        .stream()
        .findFirst();
  }

  /**
   * Another machine of a vendor in service at a store, the newest first (a replacement is the one
   * registered last): the one a card goes back through once the machine that took it is retired. A
   * refund is linked to the sale by the vendor's own reference, so it needs the vendor, not the
   * device.
   *
   * @param excluding the machine it stands in for
   */
  public Optional<Terminal> standIn(UUID tenantId, UUID storeId, String vendor, UUID excluding) {
    return Optional.ofNullable(
        inTx(
            c -> standInTx(c, tenantId, storeId, vendor, excluding),
            "another card machine of the vendor at the store"));
  }

  private static Terminal standInTx(
      Connection c, UUID tenantId, UUID storeId, String vendor, UUID excluding)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            TERMINAL_COLUMNS
                + " WHERE tenant_id = ? AND store_id = ? AND vendor = ? AND status = 'ACTIVE'"
                + " AND id <> ? ORDER BY created_at DESC, id DESC LIMIT 1")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, vendor);
      ps.setObject(4, excluding);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readTerminal(rs) : null;
      }
    }
  }

  /** How many sums owed back a retirement's refusal names; the oldest, as a manager works them. */
  private static final int OWED_NAMED = 50;

  /**
   * What is owed back (owed, or waiting for a person) to cards a vendor's machines took at a store,
   * oldest first: what would have no machine to go back through once the vendor's last one there is
   * retired. The sale and its machine are payment-svc's own tables.
   */
  private static List<Due> owedOnVendorAtStoreTx(
      Connection c, UUID tenantId, UUID storeId, String vendor) throws SQLException {
    List<Due> owed = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT d.id, d.tenant_id, d.store_id, d.order_id, d.sale_attempt_id, d.payment_id,"
                + " d.amount, d.currency, d.reason, d.source, d.idempotency_key, d.refund_kind,"
                + " d.refund_method, d.return_id, d.customer_id, d.state, d.attention,"
                + " d.refund_attempt_id, d.refund_id, d.requested_by, d.created_at, d.updated_at"
                + " FROM card_refund_dues d"
                + " JOIN terminal_payments s ON s.tenant_id = d.tenant_id"
                + " AND s.id = d.sale_attempt_id"
                + " JOIN card_terminals t ON t.tenant_id = s.tenant_id AND t.id = s.terminal_id"
                + " WHERE d.tenant_id = ? AND d.store_id = ?"
                + " AND d.state IN ('OWED', 'NEEDS_ATTENTION')"
                + " AND t.vendor = ? ORDER BY d.id LIMIT ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, vendor);
      ps.setInt(4, OWED_NAMED);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) owed.add(CardSettlementSql.readDue(rs));
      }
    }
    return owed;
  }

  /**
   * Locks every machine of a vendor at a store, in id order.
   *
   * @return how many there are
   */
  private static int lockVendorAtStoreTx(Connection c, UUID tenantId, UUID storeId, String vendor)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id FROM card_terminals WHERE tenant_id = ? AND store_id = ? AND vendor = ?"
                + " ORDER BY id FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, vendor);
      try (ResultSet rs = ps.executeQuery()) {
        // Read to the end: each row is locked as it is fetched.
        int locked = 0;
        while (rs.next()) locked++;
        return locked;
      }
    }
  }

  private static Terminal terminalTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(TERMINAL_COLUMNS + " WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readTerminal(rs) : null;
      }
    }
  }

  /**
   * Retires a terminal that holds no card payment unsettled, on one transaction with its row
   * locked: the same lock a sale's or a refund's claim takes first, so a card claimed meanwhile is
   * seen here and refuses the retirement, and a claim that waits for this one finds the terminal
   * retired.
   *
   * <p>Nor while it is the last machine of its vendor in service at its store and cards that
   * vendor's machines took there are owed money back ({@link CardSettlement#retiringStrands}): a
   * card goes back through the machine that took it or another of its vendor there, and with the
   * last one gone nothing could put them back. A sum that comes to be owed after — a return of a
   * sale a retired machine took — goes through another machine of the vendor at the store when one
   * is registered, or is given back another way by a manager ({@link #closeAnotherWay}).
   *
   * @return true when this call retired it; false when it was already retired or is not theirs
   * @throws CardSettlement.MachineHeld when it holds a card payment that is not settled: what is
   *     still at it, took money nobody recorded or put back, or timed out with nobody's word on it
   * @throws CardSettlement.OwedOnMachine when retiring it would leave money owed back to cards with
   *     no machine to put it back
   */
  public boolean retire(UUID tenantId, UUID id, String reason) {
    return inTx(
        c -> {
          // Where it stands and whose it is never change, so they are read before any lock.
          Terminal t = terminalTx(c, tenantId, id);
          if (t == null) return false;
          // Every machine of its vendor at its store, in one order, this one among them: two
          // retirements there are taken one after the other, so the last two in service cannot
          // each find the other still there. A claim takes one machine's row only, so the order
          // never turns.
          lockVendorAtStoreTx(c, tenantId, t.storeId(), t.vendor());
          if (!Terminals.ACTIVE.equals(lockTerminalTx(c, tenantId, id))) return false;
          List<CardSettlement.Facts> open = unsettledTx(c, tenantId, id);
          if (!open.isEmpty()) throw new CardSettlement.MachineHeld(open);
          boolean another = standInTx(c, tenantId, t.storeId(), t.vendor(), id) != null;
          if (!another) {
            List<Due> owed = owedOnVendorAtStoreTx(c, tenantId, t.storeId(), t.vendor());
            if (CardSettlement.retiringStrands(owed.size(), another)) {
              throw new CardSettlement.OwedOnMachine(owed);
            }
          }
          try (PreparedStatement ps = c.prepareStatement(RETIRE_TERMINAL)) {
            ps.setString(1, reason);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps.executeUpdate() == 1;
          }
        },
        "retire a card terminal");
  }

  // ── the attempts ────────────────────────────────────────────────────────────

  private static final String ATTEMPT_FIELDS =
      "id, tenant_id, store_id, terminal_id, order_id, amount, currency, kind, refund_of, state,"
          + " outcome_detail, scheme, pan_last4, auth_code, aid, application_label, entry_mode,"
          + " verification, provider_ref, payment_id, requested_at, requested_by, settled_at,"
          + " reason, due_id";

  static final String ATTEMPT_COLUMNS = "SELECT " + ATTEMPT_FIELDS + " FROM terminal_payments";

  private static final String INSERT_ATTEMPT =
      "INSERT INTO terminal_payments (id, tenant_id, store_id, terminal_id, order_id, amount,"
          + " currency, kind, refund_of, state, requested_at, requested_by, reason, due_id)"
          + " VALUES (?,?,?,?,?,?,?,?,?,'REQUESTED',?,?,?,?)";

  private static final String CLAIM_KEY =
      "INSERT INTO terminal_payment_keys (tenant_id, idempotency_key, terminal_payment_id,"
          + " created_at) VALUES (?,?,?,?)";

  private static final String BY_KEY =
      ATTEMPT_COLUMNS
          + " WHERE tenant_id = ? AND id = (SELECT terminal_payment_id FROM terminal_payment_keys"
          + " WHERE tenant_id = ? AND idempotency_key = ?)";

  /**
   * Writes a machine's answer on an attempt that is still in the state it was read in, with its row
   * locked: {@code AND state = ?} makes it one move from what was seen, never a blind overwrite.
   */
  private static final String ANSWER =
      "UPDATE terminal_payments SET state = ?, outcome_detail = ?, scheme = ?, pan_last4 = ?,"
          + " auth_code = ?, aid = ?, application_label = ?, entry_mode = ?, verification = ?,"
          + " provider_ref = ?, settled_at = ? WHERE tenant_id = ? AND id = ? AND state = ?";

  /** What the row says of a request a person settled because the machine never answered it. */
  static final String NEVER_ANSWERED =
      "The card machine never answered: the request was left at it (the call that asked it is"
          + " gone). A person said what it shows.";

  private static final String ATTACH_PAYMENT =
      "UPDATE terminal_payments SET payment_id = ? WHERE tenant_id = ? AND id = ?"
          + " AND payment_id IS NULL";

  /**
   * Writes the attempt and claims the key, in one transaction, before the terminal is touched.
   *
   * <p>For a sale, the terminal's row is locked first and its unsettled sales read: a replay under
   * a key already claimed is answered as before, and anything else is refused while the terminal
   * holds a card payment that is not settled. The lock is what makes two first presses on one
   * terminal unable to both pass.
   *
   * <p>No new card is taken for an order given up (cancelled or voided): what a machine takes for
   * one is owed straight back.
   *
   * @return the attempt as claimed, or the one an earlier call with this key already claimed
   * @throws CardSettlement.MachineHeld when the terminal has a sale still unsettled
   * @throws ApiException 409 {@code PAYMENT_ORDER_GIVEN_UP} for a sale on an order given up
   */
  public Attempt claim(Attempt attempt, String idempotencyKey) {
    return inTx(
        c -> {
          boolean sale = Terminals.SALE.equals(attempt.kind());
          if (sale) requireActiveTx(c, attempt.tenantId(), attempt.terminalId());
          if (idempotencyKey != null) {
            Attempt existing = byKeyTx(c, attempt.tenantId(), idempotencyKey);
            if (existing != null) return existing;
          }
          if (sale
              && CardSettlementSql.givenUpTx(c, attempt.tenantId(), attempt.orderId()) != null) {
            throw CardSettlementSql.givenUpRefusal(attempt.orderId());
          }
          if (sale) {
            List<CardSettlement.Facts> open =
                unsettledTx(c, attempt.tenantId(), attempt.terminalId());
            if (!open.isEmpty()) throw new CardSettlement.MachineHeld(open);
          }
          insertAttemptTx(c, attempt, idempotencyKey);
          return attempt;
        },
        "claim a terminal payment");
  }

  /**
   * Claims a refund of a sale, before the terminal is asked: never more than is still on the card.
   *
   * <p>The terminal's row and then the sale's are locked, so two refunds of one sale are taken one
   * after the other and cannot together put back more than the card paid, and a sale claimed on the
   * same machine meanwhile sees this one at the machine. A refund that may have moved money (at the
   * machine, approved, or timed out with nobody's word on it) counts against the cap; so does what
   * is owed back.
   *
   * <p>A refund a person asks on a sale recorded as a tender is money owed back against that
   * tender, so it is written as a due on the same transaction, held to what the books still have on
   * that tender: once the machine puts it back, the books do too.
   *
   * @param refund the refund attempt; its {@code dueId} names the due it is for, or is null
   * @return the attempt as claimed (with its due), or the one an earlier call with this key claimed
   * @throws CardSettlement.TooMuch when it is more than is still on the card, or in the books
   * @throws CardSettlement.RefundOutstanding when another refund of the sale is still at the
   *     machine, or timed out with nobody's word on it
   * @throws ApiException 409 {@code TERMINAL_REQUEST_IN_FLIGHT} when the due already has a refund
   *     at the machine, or one that may have gone through; 409 {@code CARD_REFUND_DUE_SETTLED} when
   *     the due it is for is no longer owed (put back, or given back another way, meanwhile); 404
   *     {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_RETIRED}
   */
  public Attempt claimRefund(Attempt refund, String idempotencyKey) {
    return inTx(
        c -> {
          UUID tenantId = refund.tenantId();
          // The terminal's row first, as a sale's claim takes it: a refund and a sale on one
          // machine are claimed one after the other, so the sale's guard sees the refund at the
          // machine. Nothing takes a sale's row and then its terminal's, so the order never turns.
          requireActiveTx(c, tenantId, refund.terminalId());
          Attempt sale = CardSettlementSql.lockAttemptTx(c, tenantId, refund.refundOf());
          if (idempotencyKey != null) {
            Attempt existing = byKeyTx(c, tenantId, idempotencyKey);
            if (existing != null) return existing;
          }
          if (sale == null) {
            throw ApiException.notFound(
                "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal");
          }
          if (refund.dueId() != null) {
            // The due's row after the sale's, as giving it back another way takes them: the two
            // are taken one after the other, so a machine is never asked for money a person has
            // just given back by hand, nor the other way about.
            Due due = CardSettlementSql.lockDueTx(c, tenantId, refund.dueId());
            if (due == null || !CardSettlement.dueStillOwed(due.state())) {
              throw ApiException.conflict(
                  "CARD_REFUND_DUE_SETTLED",
                  "Nothing is owed back on this any more"
                      + (due == null ? "" : " (" + due.state() + ")"));
            }
            for (CardSettlement.Facts f :
                CardSettlementSql.attemptsOfDueTx(c, tenantId, refund.dueId())) {
              if (CardSettlement.mayHaveMovedMoney(f.attempt().state(), f.outcome())) {
                throw ApiException.conflict(
                    "TERMINAL_REQUEST_IN_FLIGHT",
                    "This refund is already at the card machine, or may have gone through; say"
                        + " what the machine shows for it first");
              }
            }
          }
          // One reversal of a card outstanding at a time: while a refund of this sale is at the
          // machine, or timed out with nobody's word on it, another is not asked — if the first
          // then answers that it went through, the card would have been refunded twice.
          for (CardSettlement.Facts f :
              CardSettlementSql.openRefundsOfSaleTx(c, tenantId, sale.id())) {
            String refusal = CardSettlement.anotherRefundRefusal(f.standing());
            if (refusal != null) throw new CardSettlement.RefundOutstanding(refusal, f.attempt());
          }
          BigDecimal committed =
              CardSettlementSql.committedOnSaleTx(c, tenantId, sale.id(), refund.dueId());
          BigDecimal left = CardSettlement.stillOnCard(sale.amount(), committed);
          if (refund.amount().compareTo(left) > 0) {
            throw new CardSettlement.TooMuch(left, sale.currency());
          }
          Attempt claimed = refund;
          if (refund.dueId() == null && sale.paymentId() != null) {
            Due due = personDueTx(c, sale, refund, idempotencyKey);
            claimed = Terminals.forDue(refund, due.id());
          }
          insertAttemptTx(c, claimed, idempotencyKey);
          return claimed;
        },
        "claim a terminal refund");
  }

  /**
   * A person's refund of a recorded sale, as money owed back against its tender: the tender row is
   * locked and the books asked what is still theirs to put back.
   */
  private static Due personDueTx(Connection c, Attempt sale, Attempt refund, String key)
      throws SQLException {
    UUID tenantId = sale.tenantId();
    BigDecimal tender;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT amount FROM payment_tenders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, sale.paymentId());
      try (ResultSet rs = ps.executeQuery()) {
        tender = BigDecimal.ZERO;
        if (rs.next()) tender = rs.getBigDecimal("amount");
      }
    }
    BigDecimal inBooks =
        CardSettlement.stillOnCard(
            tender, CardSettlementSql.spokenForOnTenderTx(c, tenantId, sale.paymentId()));
    if (refund.amount().compareTo(inBooks) > 0) {
      throw new CardSettlement.TooMuch(inBooks, sale.currency());
    }
    Instant now = Instant.now();
    Due due =
        new Due(
            com.storeql.ids.Ids.newId(),
            tenantId,
            sale.storeId(),
            sale.orderId(),
            sale.id(),
            sale.paymentId(),
            refund.amount(),
            sale.currency(),
            refund.reason(),
            CardSettlement.FROM_PERSON,
            key != null ? key : com.storeql.ids.Ids.derived(refund.id(), "due").toString(),
            null,
            null,
            null,
            null,
            CardSettlement.OWED,
            null,
            null,
            null,
            refund.requestedBy(),
            now,
            now);
    CardSettlementSql.insertDueTx(c, due);
    return due;
  }

  private static void insertAttemptTx(Connection c, Attempt attempt, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_ATTEMPT)) {
      ps.setObject(1, attempt.id());
      ps.setObject(2, attempt.tenantId());
      ps.setObject(3, attempt.storeId());
      ps.setObject(4, attempt.terminalId());
      ps.setObject(5, attempt.orderId());
      ps.setBigDecimal(6, attempt.amount());
      ps.setString(7, attempt.currency());
      ps.setString(8, attempt.kind());
      ps.setObject(9, attempt.refundOf());
      ps.setObject(10, attempt.requestedAt().atOffset(ZoneOffset.UTC));
      ps.setObject(11, attempt.requestedBy());
      ps.setString(12, attempt.reason());
      ps.setObject(13, attempt.dueId());
      ps.executeUpdate();
    }
    if (idempotencyKey != null) {
      try (PreparedStatement ps = c.prepareStatement(CLAIM_KEY)) {
        ps.setObject(1, attempt.tenantId());
        ps.setString(2, idempotencyKey);
        ps.setObject(3, attempt.id());
        ps.setObject(4, attempt.requestedAt().atOffset(ZoneOffset.UTC));
        ps.executeUpdate();
      }
    }
  }

  /**
   * Locks a terminal's row, so its sales, its refunds and its retirement are taken one after the
   * other.
   *
   * @return its status as the lock found it, or null when the business has no such terminal
   */
  private static String lockTerminalTx(Connection c, UUID tenantId, UUID terminalId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT status FROM card_terminals WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, terminalId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("status") : null;
      }
    }
  }

  /**
   * Locks a terminal's row and refuses one that is not in service: read again under the lock, so a
   * card is never claimed on a terminal retired a moment before.
   *
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_RETIRED}
   */
  private static void requireActiveTx(Connection c, UUID tenantId, UUID terminalId)
      throws SQLException {
    String status = lockTerminalTx(c, tenantId, terminalId);
    if (status == null) throw ApiException.notFound("TERMINAL_NOT_FOUND", "No such terminal");
    if (!Terminals.ACTIVE.equals(status)) {
      throw ApiException.conflict("TERMINAL_RETIRED", "That terminal has been retired");
    }
  }

  /**
   * What holds a terminal, oldest first: its sales at the machine, approved and neither recorded
   * nor owed or put back in full, or timed out with nobody's word on them; and its refunds at the
   * machine, or timed out with nobody's word on them.
   *
   * <p>A sale's money counts as back on its card only for what a refund actually put back (or what
   * is owed back): a refund still at the machine, or one that timed out with nobody's word on it,
   * leaves the sale holding the machine — if it then turns out not to have gone through, the money
   * is still on the card, and a changed sale charged meanwhile would be a second charge. The refund
   * holds the machine itself until a person says what it shows ({@code POST …/settle}).
   */
  private static List<CardSettlement.Facts> unsettledTx(
      Connection c, UUID tenantId, UUID terminalId) throws SQLException {
    List<Attempt> candidates = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            ATTEMPT_COLUMNS
                + " WHERE tenant_id = ? AND terminal_id = ? AND ((kind = 'SALE' AND payment_id IS"
                + " NULL AND state IN ('REQUESTED', 'APPROVED', 'TIMED_OUT')) OR (kind = 'REFUND'"
                + " AND state IN ('REQUESTED', 'TIMED_OUT'))) ORDER BY requested_at, id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, terminalId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) candidates.add(readAttempt(rs));
      }
    }
    List<CardSettlement.Facts> open = new ArrayList<>();
    for (Attempt a : candidates) {
      CardSettlement.Facts f = factsTx(c, a);
      if (CardSettlement.blocksTheMachine(f.standing())) open.add(f);
    }
    return open;
  }

  /**
   * An attempt with what decides where it stands, on the caller's transaction: a person's word on
   * it and, for a sale, what has gone back on its card as the guard counts it.
   */
  private static CardSettlement.Facts factsTx(Connection c, Attempt a) throws SQLException {
    return new CardSettlement.Facts(
        a,
        CardSettlementSql.decisionOfTx(c, a.tenantId(), a.id()),
        Terminals.SALE.equals(a.kind())
            ? CardSettlementSql.wentBackOnSaleTx(c, a.tenantId(), a.id())
            : BigDecimal.ZERO);
  }

  /** What holds a terminal now, read without taking its lock. */
  public List<CardSettlement.Facts> unsettled(UUID tenantId, UUID terminalId) {
    return inTx(c -> unsettledTx(c, tenantId, terminalId), "unsettled terminal payments");
  }

  /** An attempt with what decides where it stands, as the guard on its terminal reads it. */
  public CardSettlement.Facts facts(Attempt attempt) {
    return inTx(c -> factsTx(c, attempt), "where a terminal payment stands");
  }

  public Optional<Decision> decisionOf(UUID tenantId, UUID attemptId) {
    return Optional.ofNullable(
        inTx(
            c -> CardSettlementSql.decisionOfTx(c, tenantId, attemptId),
            "a decision on a terminal payment"));
  }

  /**
   * Records what a person saw on a machine that did not answer: once, append-only, on one
   * transaction with the attempt's row locked.
   *
   * <p>A machine that did not answer is one that timed out, or one whose request was left at it —
   * still {@code REQUESTED} once the machine has had its time to answer, because the call that
   * asked it is gone. That one is written as having timed out ({@link #NEVER_ANSWERED}) on the same
   * transaction, since the machine said nothing; should it answer after all, its answer is weighed
   * then ({@link #settle}). An approval a person sees on a sale whose order was given up is owed
   * back on the same transaction.
   *
   * @param now the moment of the decision, against which a request's time to answer is measured
   * @param answerWithin how long a machine may take before a request left at it is a person's
   * @return the decision recorded (or the one an earlier call with this key recorded), and whether
   *     money is now owed back to a card for it
   * @throws ApiException 409 {@code IDEMPOTENCY_KEY_REUSED} for a key used on another attempt;
   *     {@code TERMINAL_REQUEST_IN_FLIGHT} for a request the machine may still answer (details:
   *     {@code decidableFrom=}); {@code TERMINAL_NOT_TIMED_OUT} for an attempt the machine did
   *     answer; {@code TERMINAL_ATTEMPT_ALREADY_DECIDED} for one somebody already decided
   */
  public CardSettlement.Decided decide(Decision d, Instant now, Duration answerWithin) {
    return inTx(
        c -> {
          Attempt attempt = CardSettlementSql.lockAttemptTx(c, d.tenantId(), d.attemptId());
          Decision earlier = CardSettlementSql.decisionByKeyTx(c, d.tenantId(), d.idempotencyKey());
          if (earlier != null) {
            if (!earlier.attemptId().equals(d.attemptId())) {
              throw ApiException.conflict(
                  "IDEMPOTENCY_KEY_REUSED",
                  "this Idempotency-Key was used for another card payment");
            }
            return new CardSettlement.Decided(earlier, false);
          }
          if (attempt == null) {
            throw ApiException.notFound(
                "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal");
          }
          String refusal =
              CardSettlement.decideRefusal(
                  attempt.state(), attempt.requestedAt(), now, answerWithin);
          if ("TERMINAL_REQUEST_IN_FLIGHT".equals(refusal)) {
            throw new ApiException(
                409,
                refusal,
                "The card is still at the machine and it may yet answer; wait for its answer",
                List.of(
                    "decidableFrom="
                        + CardSettlement.decidableFrom(attempt.requestedAt(), answerWithin)));
          }
          if (refusal != null) {
            throw ApiException.conflict(
                refusal,
                "The card machine answered this payment ("
                    + attempt.state()
                    + "); only one it did not answer is decided by a person");
          }
          if (Terminals.REQUESTED.equals(attempt.state())) {
            answerTx(
                c,
                d.tenantId(),
                d.attemptId(),
                Terminals.refused(Terminals.TIMED_OUT, null, NEVER_ANSWERED),
                Terminals.REQUESTED);
          }
          Decision already = CardSettlementSql.decisionOfTx(c, d.tenantId(), d.attemptId());
          if (already != null) {
            throw new ApiException(
                409,
                "TERMINAL_ATTEMPT_ALREADY_DECIDED",
                "Somebody already recorded what the machine showed for this payment",
                List.of(
                    "outcome="
                        + already.outcome()
                        + ";decidedBy="
                        + already.decidedBy()
                        + ";decidedAt="
                        + already.decidedAt()));
          }
          CardSettlementSql.insertDecisionTx(c, d);
          boolean owed =
              CardSettlement.SEEN_APPROVED.equals(d.outcome())
                  && CardSettlementSql.owedIfGivenUpTx(
                      c, CardSettlementSql.lockAttemptTx(c, d.tenantId(), d.attemptId()));
          return new CardSettlement.Decided(d, owed);
        },
        "decide a terminal payment");
  }

  /**
   * Writes the machine's answer on its attempt, on one transaction with the attempt's row locked,
   * and never drops it.
   *
   * <p>The first answer settles the attempt. One that comes after a person settled it — the machine
   * went quiet, somebody said what it shows, and then it spoke — is kept when it says more about
   * the money ({@link CardSettlement#answerApplies}: an approval beats a person's "not taken", a
   * timeout beats "took nothing"); either way the caller learns it was late and says so. A sale
   * that took money on an order already given up is owed back on the same transaction.
   *
   * @return what became of the answer
   */
  public CardSettlement.Answered settle(UUID tenantId, UUID attemptId, Terminals.Outcome outcome) {
    return inTx(
        c -> {
          Attempt before = CardSettlementSql.lockAttemptTx(c, tenantId, attemptId);
          if (before == null) return new CardSettlement.Answered(null, false, false);
          if (!CardSettlement.answerApplies(before.state(), outcome.state())) {
            return new CardSettlement.Answered(before.state(), false, false);
          }
          answerTx(c, tenantId, attemptId, outcome, before.state());
          boolean owed =
              CardSettlementSql.owedIfGivenUpTx(
                  c, CardSettlementSql.lockAttemptTx(c, tenantId, attemptId));
          return new CardSettlement.Answered(before.state(), true, owed);
        },
        "settle a terminal payment");
  }

  private static void answerTx(
      Connection c, UUID tenantId, UUID attemptId, Terminals.Outcome outcome, String seen)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(ANSWER)) {
      ps.setString(1, outcome.state());
      ps.setString(2, outcome.detail());
      ps.setString(3, outcome.scheme());
      ps.setString(4, outcome.panLast4());
      ps.setString(5, outcome.authCode());
      ps.setString(6, outcome.aid());
      ps.setString(7, outcome.applicationLabel());
      ps.setString(8, outcome.entryMode());
      ps.setString(9, outcome.verification());
      ps.setString(10, outcome.providerRef());
      ps.setObject(11, Instant.now().atOffset(ZoneOffset.UTC));
      ps.setObject(12, tenantId);
      ps.setObject(13, attemptId);
      ps.setString(14, seen);
      ps.executeUpdate();
    }
  }

  public void attachPayment(UUID tenantId, UUID attemptId, UUID paymentId) {
    exec(
        ATTACH_PAYMENT,
        ps -> {
          ps.setObject(1, paymentId);
          ps.setObject(2, tenantId);
          ps.setObject(3, attemptId);
        },
        "attach a payment to a terminal attempt");
  }

  public Optional<Attempt> attempt(UUID tenantId, UUID id) {
    return query(
            ATTEMPT_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            TerminalRepository::readAttempt,
            "a terminal attempt")
        .stream()
        .findFirst();
  }

  /** The attempt an Idempotency-Key claimed, if it claimed one. */
  public Optional<Attempt> byKey(UUID tenantId, String key) {
    return Optional.ofNullable(inTx(c -> byKeyTx(c, tenantId, key), "a terminal attempt by key"));
  }

  /**
   * Every attempt against one order, oldest first: a declined card then a cash tender reads in
   * order.
   */
  public List<Attempt> attemptsOf(UUID tenantId, UUID orderId) {
    return query(
        ATTEMPT_COLUMNS + " WHERE tenant_id = ? AND order_id = ? ORDER BY requested_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        TerminalRepository::readAttempt,
        "terminal attempts for an order");
  }

  // ── money owed back to a card ───────────────────────────────────────────────

  public Optional<Due> due(UUID tenantId, UUID dueId) {
    return query(
            CardSettlementSql.DUE_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, dueId);
            },
            CardSettlementSql::readDue,
            "money owed back to a card")
        .stream()
        .findFirst();
  }

  /** What an order still owes back to cards, oldest first. */
  public List<Due> owedOn(UUID tenantId, UUID orderId) {
    return query(
        CardSettlementSql.DUE_COLUMNS
            + " WHERE tenant_id = ? AND order_id = ? AND state = 'OWED' ORDER BY created_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        CardSettlementSql::readDue,
        "money an order owes back to cards");
  }

  /**
   * Money owed back to cards at a business's stores, oldest first, a page at a time.
   *
   * @param stores the stores to read, or null for every store
   * @param state one state, or null for those still owed (OWED and NEEDS_ATTENTION)
   * @param after the last id of the page before, or null for the first page
   */
  public List<Due> dues(UUID tenantId, Set<UUID> stores, String state, UUID after, int limit) {
    StringBuilder sql =
        new StringBuilder(CardSettlementSql.DUE_COLUMNS).append(" WHERE tenant_id = ?");
    if (stores != null) {
      sql.append(" AND store_id = ANY (?)");
    }
    sql.append(state == null ? " AND state IN ('OWED', 'NEEDS_ATTENTION')" : " AND state = ?");
    if (after != null) sql.append(" AND id > ?");
    sql.append(" ORDER BY id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          if (state != null) ps.setString(i++, state);
          if (after != null) ps.setObject(i++, after);
          ps.setInt(i, limit);
        },
        CardSettlementSql::readDue,
        "money owed back to cards");
  }

  /** The refund attempts asked for one due, oldest first, with a person's word on each. */
  public List<CardSettlement.Facts> attemptsOfDue(UUID tenantId, UUID dueId) {
    return inTx(
        c -> CardSettlementSql.attemptsOfDueTx(c, tenantId, dueId), "refunds asked for a due");
  }

  /**
   * Moves a due on once the machine has answered its refund, on one transaction: put back, the
   * books' refund row and its {@code PaymentRefunded} are written with it (when the money was ever
   * in the books); otherwise it says why it waits. Never twice: a due already put back is left as
   * it is, and the books' row is keyed by the refund attempt.
   *
   * <p>The due's row is locked, then — when money went back on a card whose sale is a tender — the
   * tender's, before the books are read or written: the lock {@link
   * PaymentRepository#createRefundGuarded}, an order event's refund and a manager's refund on a
   * machine each hold while they count what is left on that tender. Without it a refund counted
   * between this transaction's commit and the reader's second read saw the sum neither refunded nor
   * owed, and a 30.00 tender gave back 60.00.
   *
   * @param book the refund the books record, or null when nothing was in the books or it is not put
   *     back
   * @return the due as it now stands
   */
  public Due completeDue(
      UUID tenantId,
      UUID dueId,
      UUID refundAttemptId,
      String state,
      String attention,
      RefundTender book,
      UUID storeId,
      OutboxRow event) {
    return inTx(
        c -> {
          Due due = CardSettlementSql.lockDueTx(c, tenantId, dueId);
          if (due == null) return null;
          if (!CardSettlement.REFUNDED.equals(state)) {
            if (!CardSettlement.dueStillOwed(due.state())) return due;
            CardSettlementSql.moveDueTx(
                c, tenantId, dueId, state, attention, refundAttemptId, null);
            return CardSettlementSql.lockDueTx(c, tenantId, dueId);
          }
          // Money went back on the card: it is in the books exactly once, whenever the machine
          // (or a person) says so — with its due, or, when another refund already put the due
          // back, beside it.
          CardSettlement.Landing landing =
              CardSettlement.landing(due.state(), due.refundAttemptId(), refundAttemptId);
          if (landing == CardSettlement.Landing.ALREADY) return due;
          // The tender's row, after the due's (the order giving it back another way takes them),
          // as every refund written in the books takes it. What the due held against the tender
          // as owed becomes what is refunded from it on this transaction; whoever counts what is
          // left on that tender holds its row while they read the two, so they are taken wholly
          // before this (the sum still owed) or wholly after (the sum refunded) — never between,
          // where it would be in neither and could be given back a second time.
          if (due.paymentId() != null) {
            CardSettlementSql.lockTenderTx(c, tenantId, due.paymentId());
          }
          UUID refundId = null;
          if (book != null && !bookedTx(c, tenantId, book.idempotencyKey())) {
            insertBookRefundTx(c, book, storeId, null);
            insertOutbox(c, event);
            refundId = book.id();
          }
          if (landing == CardSettlement.Landing.WITH_THE_DUE) {
            if (CardSettlement.dueStillOwed(due.state())) {
              CardSettlementSql.moveDueTx(
                  c, tenantId, dueId, state, attention, refundAttemptId, refundId);
            } else {
              CardSettlementSql.putBackAfterAllTx(c, tenantId, dueId, refundAttemptId, refundId);
            }
          }
          return CardSettlementSql.lockDueTx(c, tenantId, dueId);
        },
        "settle money owed back to a card");
  }

  /** What the books record for money that went back to a customer: the refund and its event. */
  public record Booking(RefundTender row, OutboxRow event) {}

  /**
   * Writes in the books a refund that put money back on a card and was asked for nothing owed — a
   * person's refund of an approval no tender recorded at the time — when its sale has been recorded
   * since. Once, on one transaction with the sale's row locked.
   *
   * <p>That is one sequence: the machine was asked, went quiet, and a person said the refund was
   * not made; the sale, whole on its card as far as anybody knew, was then recorded as its order's
   * tender; and the machine answered that it had put the money back after all. The card has it, so
   * the books give it back too — against the tender the sale became, keyed by the refund itself.
   * The cap on the card counts the refund from the moment the machine said so.
   *
   * <p>The sale's row is what orders this against the recording: a tender naming the sale locks
   * that row before it reads what has gone back on the card, so it is taken wholly before this
   * (which then finds the sale recorded, and books the refund) or wholly after the machine's answer
   * (and is refused, {@code TERMINAL_ATTEMPT_REFUNDED}: nothing was in the books to reverse).
   *
   * @param books what the books record, from the sale as the lock found it and the refund
   * @return the books' refund when this call wrote it; empty when there is nothing to write (the
   *     sale is not recorded, the refund took no money or is for a due) or it was written before
   */
  public Optional<RefundTender> bookRefundWithoutDue(
      UUID tenantId,
      UUID refundAttemptId,
      java.util.function.BiFunction<Attempt, Attempt, Booking> books) {
    return Optional.ofNullable(
        inTx(
            c -> {
              Attempt refund = CardSettlementSql.attemptTx(c, tenantId, refundAttemptId);
              if (refund == null
                  || !Terminals.REFUND.equals(refund.kind())
                  || refund.dueId() != null
                  || refund.refundOf() == null) {
                return null;
              }
              Attempt sale = CardSettlementSql.lockAttemptTx(c, tenantId, refund.refundOf());
              if (sale == null || sale.paymentId() == null) return null;
              // Read again now the sale is ours: what the machine (or a person) said of the
              // refund only ever moves towards "it took money", never back.
              refund = CardSettlementSql.attemptTx(c, tenantId, refundAttemptId);
              Decision seen = CardSettlementSql.decisionOfTx(c, tenantId, refundAttemptId);
              if (refund == null
                  || !CardSettlement.tookMoney(
                      refund.state(), seen == null ? null : seen.outcome())) {
                return null;
              }
              Booking booking = books.apply(sale, refund);
              // The tender's row, as every refund in the books takes it, so a refund counted
              // against the tender meanwhile sees this one.
              CardSettlementSql.lockTenderTx(c, tenantId, sale.paymentId());
              if (bookedTx(c, tenantId, booking.row().idempotencyKey())) return null;
              insertBookRefundTx(c, booking.row(), sale.storeId(), null);
              insertOutbox(c, booking.event());
              return booking.row();
            },
            "book a card refund of a sale recorded since"));
  }

  /** How money owed back to a card was given back another way, if it was. */
  public Optional<CardSettlement.Closure> closureOf(UUID tenantId, UUID dueId) {
    return Optional.ofNullable(
        inTx(
            c -> CardSettlementSql.closureOfTx(c, tenantId, dueId),
            "how money owed back to a card was given back"));
  }

  /**
   * Records that money owed back to a card was given back another way, because no machine could put
   * it back: once, append-only, on one transaction with the books' refund (when the money was ever
   * in the books) and its {@code PaymentRefunded}.
   *
   * <p>The sale's row is locked first, then the due's — the order a refund's claim takes them — so
   * a machine asked for this due meanwhile is seen here (and refuses this), and one asked after
   * finds the due no longer owed. The tender's row is locked before the books' refund is written,
   * as every refund in the books takes it: what was held against the tender as owed becomes what is
   * refunded from it, and a refund counted meanwhile sees one or the other, never neither.
   *
   * @param book the books' refund, in the way the money left; null when the due is for an approval
   *     never recorded (nothing in the books to reverse)
   * @return the due as it now stands (or as the first call under this key left it)
   * @throws ApiException 404 {@code CARD_REFUND_DUE_NOT_FOUND}; 409 {@code IDEMPOTENCY_KEY_REUSED}
   *     for a key used on another due; 409 with {@link CardSettlement#anotherWayRefusal}'s code
   */
  public Due closeAnotherWay(CardSettlement.Closure k, RefundTender book, OutboxRow event) {
    return closeAnotherWay(k, book, event, null);
  }

  /**
   * As above, for money handed over from a drawer the manager names: the books' refund is that
   * drawer's, so its expected cash falls by the cash that left it. The drawer is judged here, on
   * this transaction, after a retry is answered and the due's own refusals are made, and before
   * anything is written: this business's, open (the row is held shared, so a close waits for this
   * refund or finds it counted), and at the store the due is at. A refund naming no drawer is as it
   * always was, counted at none. An approval never recorded on a sale has nothing in the books, so
   * a drawer named for it is left out.
   *
   * @throws ApiException additionally 404 {@code TILL_SESSION_NOT_FOUND}; 409 {@code
   *     TILL_SESSION_NOT_OPEN} or {@code TILL_SESSION_OTHER_STORE}
   */
  public Due closeAnotherWay(
      CardSettlement.Closure k, RefundTender book, OutboxRow event, UUID tillSessionId) {
    return inTx(
        c -> {
          UUID tenantId = k.tenantId();
          Due seen = CardSettlementSql.dueTx(c, tenantId, k.dueId());
          if (seen == null) {
            throw ApiException.notFound(
                "CARD_REFUND_DUE_NOT_FOUND", "No such money owed back to a card");
          }
          CardSettlementSql.lockAttemptTx(c, tenantId, seen.saleAttemptId());
          Due due = CardSettlementSql.lockDueTx(c, tenantId, k.dueId());
          CardSettlement.Closure earlier =
              CardSettlementSql.closureByKeyTx(c, tenantId, k.idempotencyKey());
          if (earlier != null) {
            if (!earlier.dueId().equals(k.dueId())) {
              throw ApiException.conflict(
                  "IDEMPOTENCY_KEY_REUSED",
                  "this Idempotency-Key was used for other money owed back to a card");
            }
            return due;
          }
          String refusal =
              CardSettlement.anotherWayRefusal(
                  due.state(),
                  due.paymentId(),
                  k.method(),
                  CardSettlementSql.attemptsOfDueTx(c, tenantId, due.id()));
          if (refusal != null) {
            throw new ApiException(
                409, refusal, ANOTHER_WAY_REFUSALS.get(refusal), List.of("state=" + due.state()));
          }
          UUID refundId = null;
          if (due.paymentId() != null) {
            if (book == null || !due.paymentId().equals(book.paymentId())) {
              throw new IllegalStateException(
                  "money owed back against a tender is given back in the books, against it");
            }
            if (tillSessionId != null) {
              UUID drawerStore = TillSessionLocks.requireOpenTx(c, tenantId, tillSessionId);
              if (!drawerStore.equals(due.storeId())) {
                throw ApiException.conflict(
                    "TILL_SESSION_OTHER_STORE",
                    "that till session is at another store than the one this card payment was"
                        + " taken at");
              }
            }
            CardSettlementSql.lockTenderTx(c, tenantId, due.paymentId());
            if (!bookedTx(c, tenantId, book.idempotencyKey())) {
              insertBookRefundTx(c, book, due.storeId(), tillSessionId);
              insertOutbox(c, event);
              refundId = book.id();
            }
          }
          CardSettlementSql.insertClosureTx(c, k);
          CardSettlementSql.closeAnotherWayTx(c, tenantId, due.id(), refundId);
          return CardSettlementSql.lockDueTx(c, tenantId, due.id());
        },
        "give money owed back to a card back another way");
  }

  private static final java.util.Map<String, String> ANOTHER_WAY_REFUSALS =
      java.util.Map.of(
          "CARD_REFUND_DUE_SETTLED",
          "Nothing is owed back on this any more",
          "CARD_REFUND_DUE_NOT_TRIED",
          "The card machine has not been asked yet: money a card paid goes back on that card when"
              + " a machine can do it. Ask it first (POST …/refund-dues/{id}/retry)",
          "TERMINAL_REQUEST_IN_FLIGHT",
          "A refund of this is at the card machine and may yet put it back; wait for its answer",
          "TERMINAL_REFUND_UNDECIDED",
          "The card machine did not answer a refund of this: say what it shows first, since it"
              + " may already be back on the card",
          "CARD_REFUND_DUE_NOT_IN_BOOKS",
          "This card payment was never recorded on a sale, so the books have nothing to give back"
              + " in cash or by transfer: it goes back on the card, through the acquirer (method"
              + " CARD, with the acquirer's refund reference)");

  private static boolean bookedTx(Connection c, UUID tenantId, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id FROM refund_tenders WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void insertBookRefundTx(
      Connection c, RefundTender r, UUID storeId, UUID tillSessionId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO refund_tenders (id, tenant_id, order_id, payment_id, amount, method,"
                + " reference, idempotency_key, reason, created_at, store_id, till_session_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, r.id());
      ps.setObject(2, r.tenantId());
      ps.setObject(3, r.orderId());
      ps.setObject(4, r.paymentId());
      ps.setBigDecimal(5, r.amount());
      ps.setString(6, r.method());
      ps.setString(7, r.reference());
      ps.setString(8, r.idempotencyKey());
      ps.setString(9, r.reason());
      ps.setObject(10, r.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(11, storeId);
      ps.setObject(12, tillSessionId);
      ps.executeUpdate();
    }
  }

  private Attempt byKeyTx(Connection c, UUID tenantId, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(BY_KEY)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, tenantId);
      ps.setString(3, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readAttempt(rs) : null;
      }
    }
  }

  /**
   * A collision on the idempotency key is a concurrent identical request, not a server fault.
   *
   * <p>{@link #claim} checks for the key inside its own transaction, which handles the ordinary
   * retry. A true race — two presses landing at once — reaches the primary key instead, and the
   * default mapping would turn it into a 500. It is a 409 with a name the caller can act on: ask
   * again and the first attempt comes back, which is exactly what the guard is for.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return ApiException.conflict(
          "TERMINAL_REQUEST_IN_FLIGHT",
          "An identical request is already going to the terminal; ask again for its outcome");
    }
    return super.handleTxSqlException(what, e);
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Terminal readTerminal(ResultSet rs) throws SQLException {
    return new Terminal(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("label"),
        rs.getString("vendor"),
        rs.getString("serial"),
        rs.getString("status"),
        rs.getString("retired_reason"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  static Attempt readAttempt(ResultSet rs) throws SQLException {
    return new Attempt(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("terminal_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("kind"),
        rs.getObject("refund_of", UUID.class),
        rs.getString("state"),
        rs.getString("outcome_detail"),
        rs.getString("scheme"),
        rs.getString("pan_last4"),
        rs.getString("auth_code"),
        rs.getString("aid"),
        rs.getString("application_label"),
        rs.getString("entry_mode"),
        rs.getString("verification"),
        rs.getString("provider_ref"),
        rs.getObject("payment_id", UUID.class),
        instant(rs, "requested_at"),
        rs.getObject("requested_by", UUID.class),
        instant(rs, "settled_at"),
        rs.getString("reason"),
        rs.getObject("due_id", UUID.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
