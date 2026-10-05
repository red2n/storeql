package com.storeql.payment.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where a card payment on a terminal stands with the money, decided on the server (07.16
 * follow-up).
 *
 * <p>A till used to keep a "hold" on its own device while a card was at, or already taken by, the
 * machine, so that a sale changed mid-payment was not paid twice. A hold on a device can be lost
 * (storage that fails, a sign-out, another device, a corrupt copy) and then a real customer is
 * charged twice. The rule here is the industry's: an unsettled card-present transaction on a
 * terminal is reconciled before that terminal takes the next one. Pure: no clock, no database.
 *
 * <p>A sale holds its terminal while it is {@link Standing#AT_MACHINE}, {@link
 * Standing#APPROVED_UNRECORDED} or {@link Standing#UNDECIDED}; it is {@link Standing#SETTLED} once
 * it took nothing, was recorded as its order's tender, or is owed or put back on the card in full
 * ({@link #wentBack}: a refund only asked is not back). A refund holds its terminal while it is at
 * the machine or timed out with nobody's word on it.
 */
public final class CardSettlement {

  private CardSettlement() {}

  /** What a person saw on a machine that did not answer: it approved. */
  public static final String SEEN_APPROVED = "APPROVED";

  /** What a person saw on a machine that did not answer: no money was taken. */
  public static final String NOT_TAKEN = "NOT_TAKEN";

  public static final Set<String> OUTCOMES = Set.of(SEEN_APPROVED, NOT_TAKEN);

  /** Where an attempt stands with the money. */
  public enum Standing {
    /** Nothing is waiting on it. */
    SETTLED,
    /** The card is at the machine and it has not answered yet. */
    AT_MACHINE,
    /** It took money that is neither recorded as a tender on its order nor put back in full. */
    APPROVED_UNRECORDED,
    /** The machine did not answer, and nobody has said what it shows. */
    UNDECIDED
  }

  // ── what an attempt did ─────────────────────────────────────────────────────

  /** Took money: approved by the machine, or timed out and seen approved by a person. */
  public static boolean tookMoney(String state, String decision) {
    return Terminals.APPROVED.equals(state)
        || (Terminals.TIMED_OUT.equals(state) && SEEN_APPROVED.equals(decision));
  }

  /**
   * May have moved money: what took it, what is still at the machine, and a timeout that nobody has
   * ruled out. A refund in this state still counts against what can be put back, so two refunds
   * cannot together put back more than the card paid.
   */
  public static boolean mayHaveMovedMoney(String state, String decision) {
    return tookMoney(state, decision)
        || Terminals.REQUESTED.equals(state)
        || (Terminals.TIMED_OUT.equals(state) && decision == null);
  }

  /**
   * A refund of a sale asked for nothing owed back (no due: a person's refund of a sale never
   * recorded), as the sale's card sees it.
   *
   * @param decision a person's word on it, or null
   */
  public record RefundSeen(String state, String decision, BigDecimal amount) {}

  /**
   * What has gone back on a sale's card, as the guard on its terminal counts it: what is owed back
   * through a due (owed, waiting for a person, or put back — reconciled from the list of what is
   * owed), and what refunds asked for nothing owed actually put back ({@link #tookMoney}). A refund
   * still at the machine, or one that timed out with nobody's word on it, has not gone back as far
   * as anybody knows: the sale it would reverse keeps holding the machine (and so does the refund),
   * because if it then fails the money is still on the card.
   *
   * @param dues what the sale's dues hold
   */
  public static BigDecimal wentBack(BigDecimal dues, List<RefundSeen> refunds) {
    BigDecimal back = dues == null ? BigDecimal.ZERO : dues;
    for (RefundSeen r : refunds) {
      if (tookMoney(r.state(), r.decision())) back = back.add(r.amount());
    }
    return back;
  }

  /**
   * What may have gone back on a sale's card, as the cap on another refund counts it: dues, and
   * every refund asked for nothing owed that {@link #mayHaveMovedMoney} — so two refunds can never
   * together put back more than the card paid. Never the guard's figure: what only may have gone
   * back does not settle a sale.
   *
   * @param dues what the sale's dues hold
   */
  public static BigDecimal mayHaveGoneBack(BigDecimal dues, List<RefundSeen> refunds) {
    BigDecimal back = dues == null ? BigDecimal.ZERO : dues;
    for (RefundSeen r : refunds) {
      if (mayHaveMovedMoney(r.state(), r.decision())) back = back.add(r.amount());
    }
    return back;
  }

  /** What a sale still has on the card, after what is put back or owed back; never negative. */
  public static BigDecimal stillOnCard(BigDecimal amount, BigDecimal committed) {
    BigDecimal left = amount.subtract(committed == null ? BigDecimal.ZERO : committed);
    return left.signum() < 0 ? BigDecimal.ZERO : left;
  }

  /**
   * Where an attempt stands.
   *
   * @param recorded whether a sale was recorded as a tender on its order
   * @param committed what has gone back on the card or is owed back ({@link #wentBack}); a refund
   *     still at the machine, or timed out with nobody's word on it, is not in it
   */
  public static Standing standing(
      String kind,
      String state,
      String decision,
      boolean recorded,
      BigDecimal amount,
      BigDecimal committed) {
    if (Terminals.REQUESTED.equals(state)) return Standing.AT_MACHINE;
    if (Terminals.TIMED_OUT.equals(state) && decision == null) return Standing.UNDECIDED;
    if (Terminals.SALE.equals(kind)
        && tookMoney(state, decision)
        && !recorded
        && stillOnCard(amount, committed).signum() > 0) {
      return Standing.APPROVED_UNRECORDED;
    }
    return Standing.SETTLED;
  }

  /** Whether a sale in this standing stops its terminal starting another. */
  public static boolean blocksTheMachine(Standing standing) {
    return standing != Standing.SETTLED;
  }

  // ── a machine's answer, and a request left at the machine ────────────────────

  /**
   * Whether the machine's answer is written on an attempt that is now in {@code current}.
   *
   * <p>The first answer always is: that is the attempt leaving {@link Terminals#REQUESTED}. One
   * that comes after the attempt was settled without it — a person said what a machine that had
   * gone quiet shows, and then it spoke — is kept when it says more about the money, because what
   * the machine did is what happened to the card: an approval beats anything that is not one (a
   * cancel request, a person's "not taken", a fault), and a timeout beats an answer that took
   * nothing. An answer that took nothing never replaces another, and nothing replaces an approval.
   */
  public static boolean answerApplies(String current, String answer) {
    if (Terminals.REQUESTED.equals(current)) return true;
    if (Terminals.APPROVED.equals(current)) return false;
    if (Terminals.APPROVED.equals(answer)) return true;
    return Terminals.TIMED_OUT.equals(answer) && !Terminals.TIMED_OUT.equals(current);
  }

  /**
   * Why a person may not yet say what the machine shows for an attempt, or null when they may.
   *
   * <p>A person decides what a machine did not answer: a timeout, or a request left at the machine
   * — one still {@link Terminals#REQUESTED} after the machine has had its time to answer, which
   * only happens when the call that asked it is gone (a crash, a deploy). Before then the
   * cardholder may still be at the machine and its own answer is waited for: {@code
   * TERMINAL_REQUEST_IN_FLIGHT}. One the machine answered is not a person's to decide: {@code
   * TERMINAL_NOT_TIMED_OUT}.
   *
   * @param answerWithin how long a machine may take to answer before it is taken to have gone quiet
   */
  public static String decideRefusal(
      String state, Instant requestedAt, Instant now, Duration answerWithin) {
    if (Terminals.TIMED_OUT.equals(state)) return null;
    if (Terminals.REQUESTED.equals(state)) {
      return now.isBefore(decidableFrom(requestedAt, answerWithin))
          ? "TERMINAL_REQUEST_IN_FLIGHT"
          : null;
    }
    return "TERMINAL_NOT_TIMED_OUT";
  }

  /** When a request still at the machine becomes a person's to decide. */
  public static Instant decidableFrom(Instant requestedAt, Duration answerWithin) {
    return requestedAt.plus(answerWithin);
  }

  // ── an order given up ───────────────────────────────────────────────────────

  /**
   * What an order given up (cancelled or voided) owes back on one of its sales: what the sale took
   * and still has on its card, when no tender records it. Asked whenever that becomes known — when
   * the order is given up, and again when a sale on it is approved, or a person sees it approved,
   * after. A sale recorded as a tender is owed back through its tender (the order's own refund);
   * one that took nothing, or not yet, owes nothing yet.
   */
  public static BigDecimal owedWhenGivenUp(Facts f) {
    Terminals.Attempt a = f.attempt();
    if (!Terminals.SALE.equals(a.kind())
        || a.paymentId() != null
        || !tookMoney(a.state(), f.outcome())) {
      return BigDecimal.ZERO;
    }
    return stillOnCard(a.amount(), f.committed());
  }

  /**
   * Whether a machine may have taken a card for an order: one of its sales is at the machine, took
   * money, or timed out without a person ruling it out. On an order given up, a card tender is then
   * never recorded — it would stand for that machine's card, which is owed back — named or not.
   */
  public static boolean machineMayHaveTaken(List<Facts> sales) {
    for (Facts f : sales) {
      if (Terminals.SALE.equals(f.attempt().kind())
          && mayHaveMovedMoney(f.attempt().state(), f.outcome())) {
        return true;
      }
    }
    return false;
  }

  // ── recording an approval as its order's tender ──────────────────────────────

  /**
   * What is known when a CARD tender names the terminal attempt it records.
   *
   * @param recordedAs the tender the attempt was already recorded as, or null
   * @param committed what has been put back on the card or is owed back
   */
  public record LinkFacts(
      String kind,
      String state,
      String decision,
      UUID attemptOrderId,
      UUID tenderOrderId,
      BigDecimal attemptAmount,
      BigDecimal tenderAmount,
      UUID recordedAs,
      BigDecimal committed) {}

  /**
   * Why an attempt cannot be recorded as this tender, or null when it can: an approval is recorded
   * once, on its own order, at exactly the amount the card paid, and only while all of it is still
   * on the card.
   */
  public static String linkRefusal(LinkFacts f) {
    if (!Terminals.SALE.equals(f.kind())) return "TERMINAL_NOT_A_SALE";
    if (!f.attemptOrderId().equals(f.tenderOrderId())) return "TERMINAL_ATTEMPT_OTHER_ORDER";
    if (!tookMoney(f.state(), f.decision())) return "TERMINAL_NOT_APPROVED";
    if (f.recordedAs() != null) return "TERMINAL_ATTEMPT_ALREADY_RECORDED";
    if (f.committed() != null && f.committed().signum() > 0) return "TERMINAL_ATTEMPT_REFUNDED";
    if (f.attemptAmount().compareTo(f.tenderAmount()) != 0) return "TERMINAL_AMOUNT_MISMATCH";
    return null;
  }

  // ── money owed back to a card ───────────────────────────────────────────────

  /** The platform owes it: an order cancelled, voided, or returned to the original card. */
  public static final String FROM_ORDER_EVENT = "ORDER_EVENT";

  /** A manager asked for it on a sale recorded as a tender. */
  public static final String FROM_PERSON = "PERSON";

  public static final String OWED = "OWED";
  public static final String REFUNDED = "REFUNDED";
  public static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
  public static final String NOT_REFUNDED = "NOT_REFUNDED";

  /**
   * Given back another way, by a person, because no card machine could put it back: the books say
   * how ({@code card_refund_due_closures}), and nothing more is owed.
   */
  public static final String ANOTHER_WAY = "REFUNDED_ANOTHER_WAY";

  /**
   * The other ways money owed back to a card leaves the business: cash from the drawer, the card
   * itself through the acquirer outside any machine of ours ({@code CARD}, with the acquirer's
   * reference), or a transfer. Never value issued (a gift card, store credit, a voucher): those are
   * handed out by their own routes, with their own records.
   */
  public static final Set<String> ANOTHER_WAYS = Set.of("CASH", "CARD", "UPI", "WALLET");

  /**
   * Why a way of giving money back is not one a due can be closed by, or null when it is: {@code
   * CARD_REFUND_METHOD_INVALID} for anything outside {@link #ANOTHER_WAYS}, {@code
   * CARD_REFUND_REFERENCE_REQUIRED} for the acquirer's own refund with nothing to find it by in its
   * settlement file.
   */
  public static String anotherWayInvalid(String method, String reference) {
    if (method == null || !ANOTHER_WAYS.contains(method)) return "CARD_REFUND_METHOD_INVALID";
    if ("CARD".equals(method) && (reference == null || reference.isBlank())) {
      return "CARD_REFUND_REFERENCE_REQUIRED";
    }
    return null;
  }

  /**
   * Why money owed back to a card may not be given back another way, or null when it may.
   *
   * <p>The machine first: what a card paid goes back on that card when a machine can do it, so only
   * a due the machine was asked for and did not put back ({@link #NEEDS_ATTENTION}) is closed by
   * hand — one never asked ({@link #OWED}) is {@code CARD_REFUND_DUE_NOT_TRIED}. Never while a
   * refund of it is unaccounted for — at the machine ({@code TERMINAL_REQUEST_IN_FLIGHT}) or timed
   * out with nobody's word on it ({@code TERMINAL_REFUND_UNDECIDED}) — since it may be back on the
   * card already; one that did go back, like a due no longer owed, is {@code
   * CARD_REFUND_DUE_SETTLED}. And an approval never recorded on a sale has nothing in the books to
   * give back in cash: it goes back on its card, through the acquirer ({@code
   * CARD_REFUND_DUE_NOT_IN_BOOKS} for any other way).
   *
   * @param paymentId the recorded tender the due is drawn against, or null
   * @param method one of {@link #ANOTHER_WAYS}
   * @param asked every refund asked of a machine for this due, with a person's word on each
   */
  public static String anotherWayRefusal(
      String dueState, UUID paymentId, String method, List<Facts> asked) {
    if (!dueStillOwed(dueState)) return "CARD_REFUND_DUE_SETTLED";
    for (Facts f : asked) {
      String state = f.attempt().state();
      if (tookMoney(state, f.outcome())) return "CARD_REFUND_DUE_SETTLED";
      if (Terminals.REQUESTED.equals(state)) return "TERMINAL_REQUEST_IN_FLIGHT";
      if (Terminals.TIMED_OUT.equals(state) && f.outcome() == null) {
        return "TERMINAL_REFUND_UNDECIDED";
      }
    }
    if (OWED.equals(dueState)) return "CARD_REFUND_DUE_NOT_TRIED";
    if (paymentId == null && !"CARD".equals(method)) return "CARD_REFUND_DUE_NOT_IN_BOOKS";
    return null;
  }

  /**
   * Whether retiring a machine would leave money owed back to cards with no machine to put it back:
   * a card goes back through the machine that took it or, once that is retired, another of its
   * vendor in service at its store (a refund linked to the sale's own reference needs the vendor,
   * not the device). So the last one in service is not retired while any is owed.
   *
   * @param owed how many sums are owed back (owed, or waiting for a person) on cards this vendor's
   *     machines took at the store
   * @param anotherInService whether another machine of the vendor is in service at the store
   */
  public static boolean retiringStrands(int owed, boolean anotherInService) {
    return owed > 0 && !anotherInService;
  }

  /**
   * Where a due stands after the machine answered its refund, or null while the refund is still at
   * the machine.
   *
   * <p>Put back, it is REFUNDED. Anything else waits for a person when the platform owes it — it is
   * never asked again behind anybody's back, because a refund that timed out may have gone through.
   * A person's own refund that the machine refused simply did not happen; one that may have gone
   * through waits for their word on what the machine shows.
   */
  public static String dueStateAfter(String source, String refundState, String decision) {
    if (tookMoney(refundState, decision)) return REFUNDED;
    if (Terminals.REQUESTED.equals(refundState)) return null;
    boolean undecided = Terminals.TIMED_OUT.equals(refundState) && decision == null;
    if (FROM_PERSON.equals(source) && !undecided) return NOT_REFUNDED;
    return NEEDS_ATTENTION;
  }

  /** Whether a due still holds the money it is for: owed, or waiting for a person. */
  public static boolean dueStillOwed(String dueState) {
    return OWED.equals(dueState) || NEEDS_ATTENTION.equals(dueState);
  }

  /**
   * What a due holds against its sale's card: the more of what it is for, while it counts (owed,
   * waiting for a person, or put back — not a person's refund the machine was taken to have
   * refused), and what its own refunds actually put back ({@link #tookMoney}).
   *
   * <p>The second is what a late approval adds. A person says a refund at a silent machine was not
   * made, so the due stops counting; then the machine answers that it was. What it put back is off
   * the card whatever the due says, and counts against the next refund — and when the due was asked
   * again and that went through too, both are off it.
   *
   * <p>A due given back another way ({@link #ANOTHER_WAY}) holds what it is for — the customer has
   * it, so it does not go back on the card as well — and, beside that, whatever its own refunds put
   * back after all: both left the business.
   *
   * @param putBack what the refunds asked for this due put back
   */
  public static BigDecimal dueHolds(String dueState, BigDecimal amount, BigDecimal putBack) {
    BigDecimal back = putBack == null ? BigDecimal.ZERO : putBack;
    if (ANOTHER_WAY.equals(dueState)) return amount.add(back);
    BigDecimal counts = NOT_REFUNDED.equals(dueState) ? BigDecimal.ZERO : amount;
    return back.compareTo(counts) > 0 ? back : counts;
  }

  /** Where a refund that put money back lands on the due it was asked for. */
  public enum Landing {
    /** It is the refund the due was put back by: written already, nothing more is. */
    ALREADY,
    /**
     * With the due, which it puts back: one still owed or waiting for a person, or a person's own
     * refund the machine was taken to have refused (a person said so, and the machine then said it
     * did put it back).
     */
    WITH_THE_DUE,
    /**
     * Beside the due, which another refund already put back — or a person gave back another way:
     * this one went back as well, so it is in the books on its own — the money left the business
     * twice, and the books say so.
     */
    BESIDE_THE_DUE
  }

  /**
   * Where a refund that put money back (the machine approved it, or a person saw it approved) lands
   * on its due. However many times it is told, it is written once: the books' row is keyed by the
   * refund itself.
   *
   * <p>Only a due put back by a machine ({@link #REFUNDED}) was written by the refund it names. One
   * given back another way names the last refund the machine did <em>not</em> make; if that refund
   * then answers that it did, it is beside the due, never taken for written.
   *
   * @param dueRefundAttemptId the refund the due was last answered by, or null
   */
  public static Landing landing(String dueState, UUID dueRefundAttemptId, UUID refundAttemptId) {
    if (dueStillOwed(dueState) || NOT_REFUNDED.equals(dueState)) return Landing.WITH_THE_DUE;
    return REFUNDED.equals(dueState) && refundAttemptId.equals(dueRefundAttemptId)
        ? Landing.ALREADY
        : Landing.BESIDE_THE_DUE;
  }

  /**
   * Why another refund of a sale may not be asked while one of it is not accounted for, or null
   * when it may: one still at the machine ({@code TERMINAL_REFUND_IN_FLIGHT}) may yet put money
   * back, and one that timed out with nobody's word on it ({@code TERMINAL_REFUND_UNDECIDED}) may
   * already have. One card, one reversal outstanding at a time: a second asked meanwhile is how a
   * card is refunded twice when the first answers late.
   */
  public static String anotherRefundRefusal(Standing refundStanding) {
    return switch (refundStanding) {
      case AT_MACHINE -> "TERMINAL_REFUND_IN_FLIGHT";
      case UNDECIDED -> "TERMINAL_REFUND_UNDECIDED";
      default -> null;
    };
  }

  /** What a person is told about a due the machine did not put back. */
  public static String attentionFor(String refundState, String decision, String detail) {
    String said = detail == null || detail.isBlank() ? "" : ": " + detail;
    if (Terminals.TIMED_OUT.equals(refundState) && decision == null) {
      return "The card machine did not answer"
          + said
          + ". Look at it and record what it shows before asking again.";
    }
    if (Terminals.TIMED_OUT.equals(refundState)) {
      return "A person saw that the card machine did not put it back. Ask it again.";
    }
    return "The card machine did not put it back ("
        + refundState
        + ")"
        + said
        + ". Ask it again, or deal with it another way.";
  }

  // ── records ─────────────────────────────────────────────────────────────────

  /** What a person saw on a machine that did not answer. Append-only, decided once. */
  public record Decision(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID attemptId,
      String outcome,
      String reason,
      String idempotencyKey,
      UUID decidedBy,
      Instant decidedAt) {}

  /**
   * Money owed back to a card a terminal took.
   *
   * @param paymentId the recorded tender it is drawn against; null for an approval never recorded
   * @param refundKind what the PaymentRefunded will name as its kind
   * @param refundMethod what the PaymentRefunded will name as its refund method
   */
  public record Due(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID orderId,
      UUID saleAttemptId,
      UUID paymentId,
      BigDecimal amount,
      String currency,
      String reason,
      String source,
      String idempotencyKey,
      String refundKind,
      String refundMethod,
      UUID returnId,
      UUID customerId,
      String state,
      String attention,
      UUID refundAttemptId,
      UUID refundId,
      UUID requestedBy,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * How money owed back to a card was given back when no machine could put it back. Append-only,
   * once per due, with who, when and why.
   *
   * @param method one of {@link #ANOTHER_WAYS}
   * @param reference what finds it again: the acquirer's refund reference for {@code CARD}
   *     (required), a transfer's reference, or null
   */
  public record Closure(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID dueId,
      String method,
      String reference,
      String reason,
      String idempotencyKey,
      UUID closedBy,
      Instant closedAt) {}

  /**
   * The last machine of its vendor at a store, asked to retire while cards that vendor's machines
   * took there are owed money back: with it gone, no machine could put them back. Thrown inside the
   * retirement's transaction (which it rolls back) and turned into the caller's refusal.
   */
  public static final class OwedOnMachine extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient java.util.List<Due> owed;

    public OwedOnMachine(java.util.List<Due> owed) {
      super("money is owed back to cards this machine's vendor took here", null, false, false);
      this.owed = java.util.List.copyOf(owed);
    }

    public java.util.List<Due> owed() {
      return owed;
    }
  }

  /**
   * What became of a machine's answer.
   *
   * @param before the attempt's state when the answer came: {@link Terminals#REQUESTED} for the
   *     first answer; anything else for one that came after a person settled it; null when there
   *     was no such attempt
   * @param applied whether the attempt now carries this answer ({@link #answerApplies})
   * @param owedBack whether the answer made a sale on an order already given up one that took
   *     money, so what it took is now owed back to its card
   */
  public record Answered(String before, boolean applied, boolean owedBack) {

    /** An answer that came after the attempt was settled without it. */
    public boolean late() {
      return before != null && !Terminals.REQUESTED.equals(before);
    }
  }

  /**
   * A person's word as recorded (or the one an earlier call under the same key recorded).
   *
   * @param owedBack whether it made a sale on an order already given up one that took money, so
   *     what it took is now owed back to its card
   */
  public record Decided(Decision decision, boolean owedBack) {}

  /**
   * What an order event says about where money owed back to a card goes, so the refund the machine
   * makes is announced as the books' own refund would have been.
   */
  public record OwedBack(
      UUID eventId, String refundKind, String refundMethod, UUID returnId, UUID customerId) {}

  /**
   * A terminal holding card payments that are not settled, so it starts no new one. Thrown inside
   * the claim's transaction (which it rolls back) and turned into the caller's refusal.
   */
  public static final class MachineHeld extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient java.util.List<Facts> open;

    public MachineHeld(java.util.List<Facts> open) {
      super("the card machine holds a card payment that is not settled", null, false, false);
      this.open = java.util.List.copyOf(open);
    }

    public java.util.List<Facts> open() {
      return open;
    }
  }

  /**
   * Another refund of a sale is not accounted for yet, so this one is not asked: thrown inside the
   * claim's transaction (which it rolls back) and turned into the caller's refusal.
   */
  public static final class RefundOutstanding extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Terminals.Attempt open;

    /**
     * @param code {@link #anotherRefundRefusal}'s
     * @param open the refund not yet accounted for
     */
    public RefundOutstanding(String code, Terminals.Attempt open) {
      super("another refund of the card payment is not accounted for", null, false, false);
      this.code = code;
      this.open = open;
    }

    public String code() {
      return code;
    }

    public Terminals.Attempt open() {
      return open;
    }
  }

  /** More than is still on the card (or still in the books) would go back. */
  public static final class TooMuch extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final BigDecimal left;
    private final String currency;

    public TooMuch(BigDecimal left, String currency) {
      super("more than is still on the card", null, false, false);
      this.left = left;
      this.currency = currency;
    }

    public BigDecimal left() {
      return left;
    }

    public String currency() {
      return currency;
    }
  }

  /**
   * An attempt with what decides where it stands: the person's word on it, what has been put back
   * or is owed back, and whether it was recorded.
   */
  public record Facts(Terminals.Attempt attempt, Decision decision, BigDecimal committed) {

    public String outcome() {
      return decision == null ? null : decision.outcome();
    }

    public Standing standing() {
      return CardSettlement.standing(
          attempt.kind(),
          attempt.state(),
          outcome(),
          attempt.paymentId() != null,
          attempt.amount(),
          committed);
    }
  }
}
