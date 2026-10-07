package com.storeql.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * EMV terminals, and what one says when a card is presented to it (07.16).
 *
 * <p>A {@code CARD} tender used to be recorded because a cashier said so. Nothing asked a terminal
 * whether the card was approved, and nothing kept what a card receipt has to carry. The dispute
 * module said so in its own comment — "a card taken on a terminal the platform does not talk to".
 *
 * <p><b>The card number never reaches this platform.</b> The terminal runs the EMV transaction
 * itself and returns a verdict; the platform sends an amount and receives an outcome. That is the
 * whole architecture, and it is what keeps payment-svc out of PCI-DSS scope. {@link Outcome}
 * carries the four digits a receipt may print and nothing else of the number — there is no field
 * here that could hold more, and the schema has no column for it either.
 */
public final class Terminals {

  private Terminals() {}

  // ── the device ──────────────────────────────────────────────────────────────

  /**
   * {@code SIMULATED} is always deployed; a real vendor appears when its credentials are
   * configured.
   */
  public static final String SIMULATED = "SIMULATED";

  public static final Set<String> VENDORS =
      Set.of(SIMULATED, "STRIPE_TERMINAL", "ADYEN", "VERIFONE");

  public static final String ACTIVE = "ACTIVE";
  public static final String RETIRED = "RETIRED";

  /**
   * A terminal on a counter.
   *
   * @param serial the vendor's identifier for the physical device, as printed on it; null until it
   *     is paired
   */
  public record Terminal(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String label,
      String vendor,
      String serial,
      String status,
      String retiredReason,
      Instant createdAt,
      Instant updatedAt) {

    public boolean active() {
      return ACTIVE.equals(status);
    }
  }

  // ── an attempt ──────────────────────────────────────────────────────────────

  public static final String SALE = "SALE";
  public static final String REFUND = "REFUND";

  /** Asked of the terminal, and nothing back yet. The only state in which nothing is settled. */
  public static final String REQUESTED = "REQUESTED";

  public static final String APPROVED = "APPROVED";

  /** The card said no. A business outcome, not a fault: the cashier asks for another tender. */
  public static final String DECLINED = "DECLINED";

  /** Somebody pressed cancel — on the pinpad or at the till. */
  public static final String CANCELLED = "CANCELLED";

  /** The terminal could not be reached, or answered something unusable. */
  public static final String FAILED = "FAILED";

  /**
   * Nothing came back in time.
   *
   * <p>Distinct from {@link #FAILED} on purpose, and the most dangerous state there is: the card
   * may have been charged. It must never be retried silently, and the platform must not record a
   * tender for it — the cashier reads the terminal's own screen and the attempt is reconciled
   * against the acquirer's settlement file.
   */
  public static final String TIMED_OUT = "TIMED_OUT";

  public static final Set<String> STATES =
      Set.of(REQUESTED, APPROVED, DECLINED, CANCELLED, FAILED, TIMED_OUT);

  /** How the card was read. */
  public static final Set<String> ENTRY_MODES = Set.of("CHIP", "CONTACTLESS", "SWIPE", "MANUAL");

  /** How the cardholder was verified. {@code DEVICE} is a phone's own biometric. */
  public static final Set<String> VERIFICATIONS = Set.of("PIN", "SIGNATURE", "NONE", "DEVICE");

  /**
   * What the terminal said.
   *
   * @param panLast4 the four digits a receipt is permitted to print. There is no field for any
   *     other part of the number, deliberately: a record that cannot hold a PAN cannot leak one
   * @param aid the EMV application the card and terminal agreed on, e.g. {@code A0000000031010}
   * @param applicationLabel what the receipt prints for it, e.g. {@code VISA DEBIT}
   * @param detail the terminal's own words when it declined or failed, so a cashier is not left
   *     with "declined" and no reason
   */
  public record Outcome(
      String state,
      String scheme,
      String panLast4,
      String authCode,
      String aid,
      String applicationLabel,
      String entryMode,
      String verification,
      String providerRef,
      String detail) {

    public boolean approved() {
      return APPROVED.equals(state);
    }

    /**
     * Whether money may have moved without the platform knowing.
     *
     * <p>True only for a timeout. A decline took nothing and a failure never started; a timeout is
     * the one case where the platform must neither claim the money nor assume it was not taken.
     */
    public boolean uncertain() {
      return TIMED_OUT.equals(state);
    }
  }

  /**
   * One attempt at a terminal.
   *
   * @param paymentId the tender this became, once approved; null for anything that took no money
   * @param requestedBy who asked; null only for a refund the platform owes (an order cancelled,
   *     voided or returned to the card), which no person asked for
   * @param reason why money went back, for a refund; written with the attempt and never changed
   * @param dueId the money owed back to a card that this refund is for, when it is for one
   */
  public record Attempt(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID terminalId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String kind,
      UUID refundOf,
      String state,
      String outcomeDetail,
      String scheme,
      String panLast4,
      String authCode,
      String aid,
      String applicationLabel,
      String entryMode,
      String verification,
      String providerRef,
      UUID paymentId,
      Instant requestedAt,
      UUID requestedBy,
      Instant settledAt,
      String reason,
      UUID dueId) {

    public boolean approved() {
      return APPROVED.equals(state);
    }

    public boolean settled() {
      return !REQUESTED.equals(state);
    }

    /** What a receipt prints for this card, e.g. {@code VISA DEBIT ****1234 (CHIP, PIN)}. */
    public String receiptLine() {
      if (!approved()) return null;
      String label = applicationLabel != null ? applicationLabel : scheme;
      StringBuilder out = new StringBuilder(label).append(" ****").append(panLast4);
      if (entryMode != null) {
        out.append(" (").append(entryMode);
        if (verification != null) out.append(", ").append(verification);
        out.append(')');
      }
      return out.toString();
    }
  }

  /**
   * An outcome that carries no card data: a decline, a cancellation, a failure or a timeout.
   *
   * <p>{@link Outcome} has ten components and eight of them are the card's. Every refusal therefore
   * writes the same run of nulls, and it was written three times over — in the service's failure
   * path and twice in the simulator. One factory instead, because a refusal has exactly three
   * facts: what happened, the vendor's reference if it gave one, and what it said.
   *
   * <p>The reference is kept even for a timeout, deliberately: it is the only way to find the
   * attempt in the acquirer's settlement file, which is the only way to learn whether the card was
   * charged.
   */
  public static Outcome refused(String state, String providerRef, String detail) {
    return new Outcome(state, null, null, null, null, null, null, null, providerRef, detail);
  }

  /**
   * A new attempt, before the terminal has said anything.
   *
   * <p>A factory rather than the canonical constructor, because {@link Attempt} has twenty-three
   * components and everything the terminal will later fill in is null at this point. Written out,
   * that is a call with ten consecutive nulls in it — and in a list that long, two transposed nulls
   * compile, pass every type check and put the scheme where the authorisation code belongs. This
   * takes only what a request actually knows.
   */
  public static Attempt requested(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID terminalId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String kind,
      UUID refundOf,
      UUID requestedBy,
      Instant at) {
    return requested(
        id,
        tenantId,
        storeId,
        terminalId,
        orderId,
        amount,
        currency,
        kind,
        refundOf,
        requestedBy,
        at,
        null,
        null);
  }

  /**
   * As above, for a refund: why the money goes back, and the money owed back to a card it is for
   * (null when it is for none).
   */
  public static Attempt requested(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID terminalId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String kind,
      UUID refundOf,
      UUID requestedBy,
      Instant at,
      String reason,
      UUID dueId) {
    return new Attempt(
        id,
        tenantId,
        storeId,
        terminalId,
        orderId,
        amount,
        currency,
        kind,
        refundOf,
        REQUESTED,
        // Everything from here to paymentId is the terminal's to say, and is named so that a reader
        // can check the order against the record rather than counting nulls.
        null, // outcomeDetail
        null, // scheme
        null, // panLast4
        null, // authCode
        null, // aid
        null, // applicationLabel
        null, // entryMode
        null, // verification
        null, // providerRef
        null, // paymentId — set only once an approval becomes a tender
        at,
        requestedBy,
        null, // settledAt: an unsettled attempt has none, which the schema's CHECK enforces
        reason,
        dueId);
  }

  /** The same attempt, for the money owed back to a card it was claimed for. */
  public static Attempt forDue(Attempt a, UUID dueId) {
    return new Attempt(
        a.id(),
        a.tenantId(),
        a.storeId(),
        a.terminalId(),
        a.orderId(),
        a.amount(),
        a.currency(),
        a.kind(),
        a.refundOf(),
        a.state(),
        a.outcomeDetail(),
        a.scheme(),
        a.panLast4(),
        a.authCode(),
        a.aid(),
        a.applicationLabel(),
        a.entryMode(),
        a.verification(),
        a.providerRef(),
        a.paymentId(),
        a.requestedAt(),
        a.requestedBy(),
        a.settledAt(),
        a.reason(),
        dueId);
  }

  /**
   * Whether a string could be a card number, so a caller cannot smuggle one in.
   *
   * <p>Belt to the gateway's braces. The gateway already refuses card-shaped request bodies, and
   * this refuses one that reached the service by any other route — a field renamed, a new client, a
   * test harness. The check is deliberately broad: thirteen or more digits, ignoring spaces and
   * dashes, that satisfy Luhn. A false positive costs a caller a clear refusal; a false negative
   * puts a PAN in a database that has nowhere to put it.
   */
  public static boolean looksLikeCardNumber(String value) {
    if (value == null) return false;
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') digits.append(c);
      else if (c != ' ' && c != '-') return false;
    }
    if (digits.length() < 13 || digits.length() > 19) return false;
    return luhnHolds(digits.toString());
  }

  private static boolean luhnHolds(String digits) {
    int sum = 0;
    boolean doubling = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubling) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      doubling = !doubling;
    }
    return sum % 10 == 0;
  }
}
