package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.CardSettlement.Decision;
import com.storeql.payment.domain.CardSettlement.Due;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundAllocation;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.domain.Terminals.Attempt;
import com.storeql.payment.domain.Terminals.Terminal;
import com.storeql.payment.provider.CardTerminal;
import com.storeql.payment.repo.TerminalRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Taking a card on a terminal (07.16).
 *
 * <p>A {@code CARD} tender used to be recorded because a cashier said so. This asks a terminal,
 * keeps what the terminal said, and records a tender only when the card was actually approved.
 *
 * <p>Three rules carry it, and each exists because of a way real money goes wrong.
 *
 * <p><b>The attempt is claimed before the card is asked.</b> A terminal payment is the canonical
 * double-charge: the screen does not change, the cashier presses the button again, and a real
 * customer is charged twice. So the idempotency key is written with the attempt, in one
 * transaction, before anything is said to the device — a retry finds the first attempt and returns
 * it. Claiming afterwards would leave the window open for as long as a cardholder takes to enter a
 * PIN, which is precisely when the second press happens.
 *
 * <p><b>A timeout is not a decline.</b> A decline took no money and a failure never started, but a
 * timeout means the card <em>may</em> have been charged. It records no tender and it is never
 * retried automatically: the cashier reads the terminal's own screen, and the attempt is reconciled
 * against the acquirer's settlement file. Treating it as a decline would lose a taking; treating it
 * as an approval would take money the platform cannot prove it has.
 *
 * <p><b>The card number never arrives.</b> The terminal runs the EMV transaction; this service
 * sends an amount and receives a verdict. {@link #refuseCardData} refuses a request carrying
 * anything card-shaped as belt to the gateway's braces — the schema has no column for a PAN, and
 * nothing should reach the point of finding that out.
 *
 * <p><b>A terminal with a card payment unsettled takes no new one</b> ({@link CardSettlement}).
 * Whatever a till remembers, the server refuses a new sale on a terminal while one is still at the
 * machine, took money that is neither recorded on its order nor put back, or timed out with
 * nobody's word on it — and while a refund on it is at the machine or timed out with nobody's word
 * on it, which also leaves the sale it would reverse unsettled. A person settles it: the till
 * records it ({@code POST /payments} naming it), a manager puts it back ({@link #refund}) or says
 * what the machine shows ({@link #decide}).
 *
 * <p><b>Only the machine, or a person, settles a card at it.</b> A cancel asks the machine to stop
 * and settles nothing ({@link #cancel}); the machine's answer to the sale settles it, and an answer
 * that comes late is never dropped. A request whose call is gone is a person's to decide once the
 * machine has had its time ({@link #decide}).
 *
 * <p><b>Money owed back to a card goes back through the terminal.</b> An order cancelled, voided or
 * returned to its card owes back what a terminal took; {@link #putBackOwed} asks the terminal, on
 * the card that paid, and the books' refund follows only when the machine has done it. One the
 * machine refuses or does not answer waits for a person, and is never asked again behind their
 * back. A retired machine's cards go back through another of its vendor at its store ({@link
 * #machineToPutBack}); when no machine can put one back, a manager says how it was given back
 * another way ({@link #refundedAnotherWay}), so nothing is owed for ever. An order given up owes
 * back what its sales take even when that is learnt afterwards — an approval that arrives late, or
 * a timeout a person sees approved — and no card is taken or recorded for it after.
 */
@ApplicationScoped
public class TerminalService {

  private static final Logger LOG = Logger.getLogger(TerminalService.class.getName());

  @Inject TerminalRepository repo;

  /**
   * Every terminal implementation deployed. Selected by vendor, never named in this class's logic.
   */
  @Inject Instance<CardTerminal> terminals;

  /**
   * How long a machine may take to answer a request before a person may say what it shows instead.
   * Each driver carries its own, shorter timeout and answers {@code TIMED_OUT} when it passes, so a
   * request still {@code REQUESTED} after this long is one whose call is gone (a crash, a deploy).
   * Until then nobody but the machine settles it: not a cancel, not a person.
   */
  @Inject
  @ConfigProperty(name = "storeql.terminal.answer-within-seconds", defaultValue = "180")
  long answerWithinSeconds;

  /** The business's own currency, which a card is taken in. */
  @Inject com.storeql.service.TenantProfiles profiles;

  // ── the register ────────────────────────────────────────────────────────────

  /**
   * Registers a terminal for a store.
   *
   * @throws ApiException 400 {@code TERMINAL_VENDOR_UNKNOWN}; 409 {@code
   *     TERMINAL_VENDOR_UNAVAILABLE} when the vendor is deployed but not configured — said here, at
   *     registration, rather than at the till with a customer waiting; 409 {@code
   *     TERMINAL_LABEL_TAKEN} / {@code TERMINAL_SERIAL_TAKEN}
   */
  public Terminal register(
      UUID tenantId, UUID storeId, String label, String vendor, String serial, UUID actorId) {
    String v = vendor == null ? "" : vendor.strip().toUpperCase(Locale.ROOT);
    if (!Terminals.VENDORS.contains(v)) {
      throw ApiException.badRequest(
          "TERMINAL_VENDOR_UNKNOWN",
          "A terminal is one of " + String.join(", ", Terminals.VENDORS));
    }
    CardTerminal device = deviceFor(v);
    if (!device.available()) {
      throw ApiException.conflict(
          "TERMINAL_VENDOR_UNAVAILABLE",
          v + " is not configured on this deployment, so a terminal cannot be paired with it yet");
    }
    Terminal t =
        new Terminal(
            Ids.newId(),
            tenantId,
            storeId,
            requireLabel(label),
            v,
            blankToNull(serial),
            Terminals.ACTIVE,
            null,
            Instant.now(),
            Instant.now());
    try {
      return repo.add(t, actorId);
    } catch (ApiException e) {
      // The unique indexes are the authority on both, so the refusal is named from what they caught
      // rather than from a read-then-write that another till could win between.
      if (e.status() == 409) {
        throw new ApiException(
            409,
            "TERMINAL_ALREADY_REGISTERED",
            "A terminal with that label or serial is already active in this store",
            List.of(),
            e);
      }
      throw e;
    }
  }

  /**
   * The vendors this deployment can actually talk to.
   *
   * <p>Asked by the register screen so a business is not offered a device the platform cannot
   * reach. A vendor is "available" when its implementation is deployed <em>and</em> its credentials
   * are configured, which is a different question from whether the code for it exists.
   */
  public List<String> availableVendors() {
    List<String> out = new java.util.ArrayList<>();
    for (CardTerminal device : terminals) {
      if (device.available()) out.add(device.vendor());
    }
    java.util.Collections.sort(out);
    return out;
  }

  public List<Terminal> list(UUID tenantId) {
    return repo.of(tenantId);
  }

  /**
   * One terminal of the business, for the caller to check where it stands before acting on it.
   *
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}, for another business's terminal exactly as
   *     for one nobody registered
   */
  public Terminal get(UUID tenantId, UUID id) {
    return require(tenantId, id);
  }

  /**
   * Retires a terminal. Never deleted: payments point at it.
   *
   * <p>Not while it holds a card payment that is not settled — the industry's rule that a terminal
   * is reconciled (its open transactions closed) before it is taken out of service. Retiring it
   * used to strand such a card: an approval nobody recorded or put back, a refund or a sale still
   * at the machine, a timeout nobody had looked at. The retired terminal took no new sale, so its
   * guard held nothing; the till moved to the replacement and charged the changed sale there, and
   * the first card could no longer be put back through the machine that took it. So it is refused
   * with the same details as the guard's own refusal, and settled the same three ways: record it on
   * its sale, put it back, or say what the machine shows — each of which works on a terminal that
   * has stopped answering, the last one there for exactly that.
   *
   * <p>Nor while it is the last machine of its vendor in service at its store and cards that
   * vendor's machines took there are owed money back: a card goes back through the machine that
   * took it or another of its vendor there ({@link #machineToPutBack}), and with the last one gone
   * nothing could put them back. Each is named in the refusal, and there are three ways out:
   * register the machine that replaces it first (it then puts them back), ask this one again, or
   * say how each was given back another way ({@link #refundedAnotherWay}).
   *
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_ALREADY_RETIRED}; 409
   *     {@code TERMINAL_UNSETTLED_APPROVAL} while it holds a card payment not settled, one detail
   *     per payment as the guard gives them ({@link #unsettledRefusal}); 409 {@code
   *     TERMINAL_REFUNDS_OWED} while retiring it would leave money owed back to cards with no
   *     machine to put it back, one detail per sum owed ({@code
   *     dueId=…;attemptId=…;orderId=…;amount=…;currency=…;state=…})
   */
  public Terminal retire(UUID tenantId, UUID id, String reason) {
    Terminal t = require(tenantId, id);
    if (!t.active()) {
      throw ApiException.conflict("TERMINAL_ALREADY_RETIRED", "That terminal is already retired");
    }
    boolean retired;
    try {
      retired = repo.retire(tenantId, id, blankToNull(reason));
    } catch (CardSettlement.MachineHeld held) {
      throw unsettledRefusal(
          held,
          "This card machine has a card payment that is not settled, so it is not retired yet."
              + " Record it on its sale, put it back on the card, or say what the machine shows"
              + " (for a refund too); then it can be retired.");
    } catch (CardSettlement.OwedOnMachine owing) {
      throw owedRefusal(owing, t);
    }
    if (!retired) {
      // Retired by another call between the read and the lock: the same answer as a second ask.
      throw ApiException.conflict("TERMINAL_ALREADY_RETIRED", "That terminal is already retired");
    }
    return require(tenantId, id);
  }

  // ── taking a card ───────────────────────────────────────────────────────────

  /**
   * Takes a sale on a terminal and returns what the terminal said.
   *
   * @param idempotencyKey the replay guard. Strongly wanted: without it a retried press is a second
   *     EMV transaction on a real card
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_RETIRED}; 409 {@code
   *     TERMINAL_UNSETTLED_APPROVAL} for a new key while the terminal has a card payment that is
   *     not settled (a replay under a key already claimed is answered as before); 409 {@code
   *     PAYMENT_ORDER_GIVEN_UP} for a new key on an order cancelled or voided; 400 {@code
   *     TERMINAL_AMOUNT_INVALID} (not positive, or finer than the currency's minor unit), {@code
   *     CURRENCY_INVALID} or {@code TERMINAL_CARD_DATA_NOT_ACCEPTED}; 409 {@code
   *     TERMINAL_CURRENCY_MISMATCH} for a currency that is not the business's own (details {@code
   *     currency=} the business's)
   */
  public Attempt sale(
      UUID tenantId,
      UUID terminalId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      UUID actorId,
      String idempotencyKey) {
    Terminal t = requireActive(tenantId, terminalId);
    BigDecimal due = requireMoney(amount, currency);
    requireBusinessCurrency(tenantId, currency);

    UUID attemptId = Ids.newId();
    Attempt claimed;
    try {
      claimed =
          repo.claim(
              Terminals.requested(
                  attemptId,
                  tenantId,
                  t.storeId(),
                  t.id(),
                  orderId,
                  due,
                  currency,
                  Terminals.SALE,
                  null,
                  actorId,
                  Instant.now()),
              idempotencyKey);
    } catch (CardSettlement.MachineHeld held) {
      throw unsettledRefusal(held, UNSETTLED_SALE);
    }
    // The retry's whole purpose: the same key finds the attempt that already went to the terminal,
    // and nothing is asked of the device a second time. That holds for a settled attempt and also
    // for one still REQUESTED, where the first press is at the device: the replay gets that attempt
    // back, still pending, and only the call that wrote the row (its id is ours) asks the device.
    if (!ownsClaim(claimed, attemptId)) return claimed;

    return run(tenantId, claimed, t, device -> device.sale(requestFor(claimed, t)));
  }

  /**
   * Puts money back on the card that paid it, for a reason a person gives.
   *
   * <p>Linked to the original attempt rather than taking a card again: an unlinked refund is how
   * card fraud is done, and most acquirers refuse them outright. Asked of the machine that took the
   * card, or once that is retired of another of its vendor at its store ({@link
   * #machineToPutBack}). Never more than is still on that card: what was put back already, what is
   * at the machine being put back or may have been, and what is owed back all count. On a sale
   * recorded as a tender, the refund is also the books': it is held to what the tender still has,
   * and once the machine has put it back the books' refund and its {@code PaymentRefunded} are
   * written with it.
   *
   * @param reason why, kept with the attempt (and who asked, and when) and never changed
   *     <p>One refund of a card at a time: while another refund of the same sale is still at the
   *     machine, or timed out with nobody's word on it, this one is refused — the first may yet put
   *     the money back, and two would put it back twice. A refund whose answer comes late (the
   *     machine's call outlived the request, and a person meanwhile said it did not go through) is
   *     still written in the books once and counted against what is left on the card ({@link
   *     #completeRefund}) — also when the sale was not recorded when the refund was asked and has
   *     been since ({@link #bookIfRecordedSince}).
   * @throws ApiException 404 {@code TERMINAL_ATTEMPT_NOT_FOUND}; 409 {@code TERMINAL_NOT_A_SALE},
   *     {@code TERMINAL_NOT_APPROVED} when the attempt being refunded took no money (or timed out
   *     with nobody's word on it), {@code TERMINAL_REFUND_TOO_LARGE}, {@code
   *     TERMINAL_REFUND_IN_FLIGHT} or {@code TERMINAL_REFUND_UNDECIDED} (another refund of it not
   *     accounted for; details name it), or {@code TERMINAL_RETIRED} when the machine that took it
   *     is retired and its store has no other of its vendor in service; 400 {@code
   *     TERMINAL_REASON_REQUIRED} or {@code TERMINAL_AMOUNT_INVALID} for an amount finer than the
   *     paying currency's minor unit
   */
  public Attempt refund(
      UUID tenantId,
      UUID originalAttemptId,
      BigDecimal amount,
      UUID actorId,
      String idempotencyKey,
      String reason) {
    Attempt original =
        repo.attempt(tenantId, originalAttemptId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
    String why = requireReason(reason);
    if (!Terminals.SALE.equals(original.kind())) {
      throw ApiException.conflict(
          "TERMINAL_NOT_A_SALE", "Only a sale is put back on the card that paid it");
    }
    String seen = repo.decisionOf(tenantId, original.id()).map(Decision::outcome).orElse(null);
    if (!CardSettlement.tookMoney(original.state(), seen)) {
      throw ApiException.conflict(
          "TERMINAL_NOT_APPROVED",
          Terminals.TIMED_OUT.equals(original.state()) && seen == null
              ? "The card machine did not answer this payment: say what it shows first"
              : "That attempt took no money, so there is nothing to put back");
    }
    // In the currency the card paid in: a refund puts back what that payment took.
    BigDecimal back = requireMoney(amount, original.currency());
    if (back.compareTo(original.amount()) > 0) {
      throw ApiException.conflict(
          "TERMINAL_REFUND_TOO_LARGE",
          "That is more than the "
              + Amounts.shown(original.amount(), original.currency()).toPlainString()
              + " taken on this card");
    }
    Terminal t = machineToPutBack(tenantId, original);

    UUID attemptId = Ids.newId();
    Attempt claimed;
    try {
      claimed =
          repo.claimRefund(
              Terminals.requested(
                  attemptId,
                  tenantId,
                  t.storeId(),
                  t.id(),
                  original.orderId(),
                  back,
                  original.currency(),
                  Terminals.REFUND,
                  original.id(),
                  actorId,
                  Instant.now(),
                  why,
                  null),
              idempotencyKey);
    } catch (CardSettlement.TooMuch e) {
      throw tooMuch(e);
    } catch (CardSettlement.RefundOutstanding e) {
      throw outstandingRefusal(e);
    }
    if (!ownsClaim(claimed, attemptId)) {
      completeRefund(claimed);
      return claimed;
    }

    Attempt answered =
        run(
            tenantId,
            claimed,
            t,
            device -> device.refund(requestFor(claimed, t), original.providerRef()));
    completeRefund(answered);
    return answered;
  }

  /**
   * Asks the machine to stop asking for a card, when the cashier abandons the tender — and settles
   * nothing.
   *
   * <p>A cancel is a question for the machine, not its answer. The cardholder may have finished a
   * moment before it arrived, and the driver cannot know that from here: what it says back to a
   * cancel is not what happened to the card. So the attempt stays {@code REQUESTED}, holding its
   * terminal, until the machine answers the sale itself — {@code CANCELLED} if the cancel took,
   * {@code APPROVED} if the card was taken first — or, if that answer never comes because the call
   * that asked it is gone, until a manager says what the machine shows ({@link #decide}). A cancel
   * used to settle the attempt on the driver's word, which freed the terminal for a second press
   * while the first card was being approved: two charges, one on record.
   *
   * @return the attempt as it now stands: still at the machine, or as the machine settled it
   * @throws ApiException 404 {@code TERMINAL_ATTEMPT_NOT_FOUND}; 409 {@code TERMINAL_NOT_A_SALE}
   *     for a refund, which is not taken at the pinpad and so is not stopped there
   */
  public Attempt cancel(UUID tenantId, UUID attemptId) {
    Attempt attempt =
        repo.attempt(tenantId, attemptId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
    if (!Terminals.SALE.equals(attempt.kind())) {
      throw ApiException.conflict(
          "TERMINAL_NOT_A_SALE",
          "A refund is put back on the card without it, so there is nothing at the machine to"
              + " stop: its answer is waited for, or a manager says what the machine shows");
    }
    if (attempt.settled()) return attempt;
    Terminal t = require(tenantId, attempt.terminalId());
    CardTerminal device = deviceFor(t.vendor());
    try {
      Terminals.Outcome said = device.cancel(attempt.providerRef());
      LOG.info(
          "terminal attempt "
              + attempt.id()
              + ": asked the machine to stop; the driver says "
              + (said == null ? "nothing" : said.state())
              + ". The attempt waits for the machine's own answer.");
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "terminal attempt "
              + attempt.id()
              + ": the cancel could not be sent ("
              + e.getMessage()
              + "); the attempt waits for the machine's own answer",
          e);
    }
    return repo.attempt(tenantId, attempt.id()).orElse(attempt);
  }

  public List<Attempt> attemptsOf(UUID tenantId, UUID orderId) {
    return repo.attemptsOf(tenantId, orderId);
  }

  public Optional<Attempt> attempt(UUID tenantId, UUID id) {
    return repo.attempt(tenantId, id);
  }

  /** Records the tender an approved attempt became, so the sale and the card agree on one id. */
  public void attachPayment(UUID tenantId, UUID attemptId, UUID paymentId) {
    repo.attachPayment(tenantId, attemptId, paymentId);
  }

  // ── where a card payment stands ─────────────────────────────────────────────

  /** An attempt with what decides where it stands: a person's word on it, and what went back. */
  public CardSettlement.Facts facts(Attempt attempt) {
    return repo.facts(attempt);
  }

  /**
   * What holds a terminal now: the card payments on it that are not settled, oldest first. Empty
   * when it may take the next one.
   *
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}
   */
  public List<CardSettlement.Facts> unsettled(UUID tenantId, UUID terminalId) {
    require(tenantId, terminalId);
    return repo.unsettled(tenantId, terminalId);
  }

  /**
   * Records what a person saw on a card machine that did not answer: it APPROVED (then the payment
   * is an approval to record on its order or put back) or NOT_TAKEN. Once, with who, when and why;
   * a replay under the same key answers with the first. A refund that timed out and is decided here
   * moves the money owed back it was for on with it.
   *
   * <p>A machine that did not answer is one that timed out, or one whose request was left at it:
   * still at the machine after {@link #answerWithinSeconds}, because the call that asked it is
   * gone. Before then it may still answer, and only it settles the card. An approval seen on a sale
   * whose order was given up is owed back, and put back through the machine at once.
   *
   * @throws ApiException 404 {@code TERMINAL_ATTEMPT_NOT_FOUND}; 400 {@code
   *     TERMINAL_OUTCOME_INVALID} or {@code TERMINAL_REASON_REQUIRED}; 409 {@code
   *     TERMINAL_REQUEST_IN_FLIGHT} (details {@code decidableFrom=}), {@code
   *     TERMINAL_NOT_TIMED_OUT}, {@code TERMINAL_ATTEMPT_ALREADY_DECIDED} or {@code
   *     IDEMPOTENCY_KEY_REUSED}
   */
  public CardSettlement.Facts decide(
      UUID tenantId,
      UUID attemptId,
      String outcome,
      String reason,
      UUID actorId,
      String idempotencyKey) {
    Attempt attempt =
        repo.attempt(tenantId, attemptId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
    String seen = outcome == null ? "" : outcome.strip().toUpperCase(Locale.ROOT);
    if (!CardSettlement.OUTCOMES.contains(seen)) {
      throw ApiException.badRequest(
          "TERMINAL_OUTCOME_INVALID",
          "outcome is what the card machine shows: APPROVED or NOT_TAKEN");
    }
    CardSettlement.Decided decided =
        repo.decide(
            new Decision(
                Ids.newId(),
                tenantId,
                attempt.storeId(),
                attempt.id(),
                seen,
                requireReason(reason),
                idempotencyKey,
                actorId,
                Instant.now()),
            Instant.now(),
            answerWithin());
    Attempt now = repo.attempt(tenantId, attemptId).orElse(attempt);
    if (Terminals.REFUND.equals(now.kind())) completeRefund(now);
    if (decided.owedBack()) putBackOwedNow(tenantId, now.orderId());
    return repo.facts(now);
  }

  private Duration answerWithin() {
    return Duration.ofSeconds(Math.max(0, answerWithinSeconds));
  }

  // ── money owed back to a card ───────────────────────────────────────────────

  /**
   * Asks the terminals to put back on their cards what an order owes back, each due once. A due the
   * machine cannot reach or refuses waits for a person ({@code NEEDS_ATTENTION}); nothing is ever
   * written in the books as refunded until the machine has put it back.
   *
   * @throws ApiException a 5xx when the database could not be read or written, so the order event
   *     is delivered again; what is owed is asked under a key of its own and never reaches a
   *     machine twice
   */
  public void putBackOwed(UUID tenantId, UUID orderId) {
    for (Due due : repo.owedOn(tenantId, orderId)) {
      try {
        ask(due, Ids.derived(due.id(), "card-refund").toString(), null);
      } catch (ApiException e) {
        if (e.status() >= 500) throw e;
        LOG.warning(
            "card refund "
                + due.id()
                + " for order "
                + orderId
                + " waits for a person: "
                + e.code()
                + " "
                + e.getMessage());
        repo.completeDue(
            tenantId,
            due.id(),
            null,
            CardSettlement.NEEDS_ATTENTION,
            "The card machine could not be asked (" + e.code() + "): " + e.getMessage(),
            null,
            null,
            null);
      }
    }
  }

  /**
   * Puts back what a sale just became owed (its order was given up before the machine, or a person,
   * said it took money), after the transaction that owed it. Never fails the answer it follows: the
   * money stays owed, listed for a manager, and the order's next event asks again.
   */
  private void putBackOwedNow(UUID tenantId, UUID orderId) {
    try {
      putBackOwed(tenantId, orderId);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "card refund owed on given-up order "
              + orderId
              + " is not put back yet ("
              + e.getMessage()
              + "); it stays owed and listed for a manager",
          e);
    }
  }

  /**
   * Money owed back to cards at the business's stores, oldest first.
   *
   * @param stores the stores to read, or null for all of them
   * @param state OWED, NEEDS_ATTENTION, REFUNDED, NOT_REFUNDED or REFUNDED_ANOTHER_WAY; null for
   *     what is still owed
   * @throws ApiException 400 {@code CARD_REFUND_DUE_STATE_INVALID}
   */
  public com.storeql.web.Cursor.Page<Due> dues(
      UUID tenantId, Set<UUID> stores, String state, String after, Integer limit) {
    String wanted =
        state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
    if (wanted != null && !DUE_STATES.contains(wanted)) {
      throw ApiException.badRequest(
          "CARD_REFUND_DUE_STATE_INVALID",
          "state is one of OWED, NEEDS_ATTENTION, REFUNDED, NOT_REFUNDED or"
              + " REFUNDED_ANOTHER_WAY");
    }
    String raw = com.storeql.web.Cursor.decode(after);
    UUID from;
    try {
      from = raw == null ? null : Ids.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
    }
    int lim = com.storeql.web.Cursor.clampLimit(limit);
    return com.storeql.web.Cursor.page(
        repo.dues(tenantId, stores, wanted, from, lim + 1), lim, d -> d.id().toString());
  }

  private static final Set<String> DUE_STATES =
      Set.of(
          CardSettlement.OWED,
          CardSettlement.NEEDS_ATTENTION,
          CardSettlement.REFUNDED,
          CardSettlement.NOT_REFUNDED,
          CardSettlement.ANOTHER_WAY);

  /**
   * @throws ApiException 404 {@code CARD_REFUND_DUE_NOT_FOUND}, for another business's exactly as
   *     for none
   */
  public Due due(UUID tenantId, UUID dueId) {
    return repo.due(tenantId, dueId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "CARD_REFUND_DUE_NOT_FOUND", "No such money owed back to a card"));
  }

  /**
   * A person asks the machine again for money owed back to a card. A refund of it that took money
   * but was never finished is finished instead of asked again; one still at the machine, or one
   * that timed out with nobody's word on it, is refused until somebody has looked. The machine
   * asked is the sale's own, or once that is retired another of its vendor in service at its store
   * ({@link #machineToPutBack}).
   *
   * @throws ApiException 404 {@code CARD_REFUND_DUE_NOT_FOUND}; 409 {@code
   *     CARD_REFUND_DUE_SETTLED}, {@code TERMINAL_REQUEST_IN_FLIGHT}, {@code
   *     TERMINAL_REFUND_UNDECIDED}, {@code TERMINAL_REFUND_TOO_LARGE}, or {@code TERMINAL_RETIRED}
   *     when the sale's machine is retired and its store has no other of its vendor in service
   *     (then it is given back another way: {@link #refundedAnotherWay})
   */
  public Due retryDue(UUID tenantId, UUID dueId, UUID actorId, String idempotencyKey) {
    Due due = due(tenantId, dueId);
    if (idempotencyKey != null) {
      Optional<Attempt> earlier = repo.byKey(tenantId, idempotencyKey);
      if (earlier.isPresent()) {
        if (!dueId.equals(earlier.get().dueId())) {
          throw ApiException.conflict(
              "IDEMPOTENCY_KEY_REUSED", "this Idempotency-Key was used for another card payment");
        }
        completeRefund(earlier.get());
        return due(tenantId, dueId);
      }
    }
    if (!CardSettlement.dueStillOwed(due.state())) {
      throw ApiException.conflict(
          "CARD_REFUND_DUE_SETTLED", "Nothing is owed back on this any more (" + due.state() + ")");
    }
    for (CardSettlement.Facts f : repo.attemptsOfDue(tenantId, dueId)) {
      Attempt a = f.attempt();
      if (CardSettlement.tookMoney(a.state(), f.outcome())) {
        completeRefund(a);
        return due(tenantId, dueId);
      }
      if (Terminals.REQUESTED.equals(a.state())) {
        throw ApiException.conflict(
            "TERMINAL_REQUEST_IN_FLIGHT", "This refund is at the card machine; ask again for it");
      }
      if (Terminals.TIMED_OUT.equals(a.state()) && f.outcome() == null) {
        throw new ApiException(
            409,
            "TERMINAL_REFUND_UNDECIDED",
            "The card machine did not answer the last time it was asked: say what it shows first",
            List.of("attemptId=" + a.id()));
      }
    }
    try {
      ask(due, idempotencyKey, actorId);
    } catch (ApiException e) {
      // No machine to ask: the due is left waiting for a person, saying why, exactly as when the
      // platform itself could not ask — so a manager can then say how it was given back another
      // way, which is only for money a machine was asked for and did not put back.
      if (NO_MACHINE.contains(e.code())) {
        repo.completeDue(
            tenantId,
            dueId,
            null,
            CardSettlement.NEEDS_ATTENTION,
            "The card machine could not be asked (" + e.code() + "): " + e.getMessage(),
            null,
            null,
            null);
      }
      throw e;
    }
    return due(tenantId, dueId);
  }

  /** The refusals that mean there is no machine to ask at all, whoever asks. */
  private static final Set<String> NO_MACHINE =
      Set.of("TERMINAL_RETIRED", "TERMINAL_NOT_FOUND", "TERMINAL_VENDOR_UNAVAILABLE");

  /**
   * A person says how money owed back to a card was given back instead, because no card machine
   * could put it back: its machine is retired with none of its vendor left at the store, or the
   * machine keeps refusing (a card closed, a machine dead). Once, with who, when, how and why; a
   * replay under the same key answers with the first.
   *
   * <p>The machine first: only a due a machine was asked for and did not put back ({@code
   * NEEDS_ATTENTION}) is closed this way, and never while a refund of it is at the machine or timed
   * out with nobody's word on it. A refund of it that went through but was never finished is
   * finished instead, and then nothing is left to give back.
   *
   * <p>When the money was in the books (the due is drawn against a recorded tender), the books'
   * refund — in the way the money left — and its {@code PaymentRefunded} are written with it, on
   * one transaction: what the due held against the tender becomes what is refunded from it. An
   * approval never recorded on a sale has nothing in the books to reverse, so it goes back on its
   * card only, through the acquirer ({@code CARD}).
   *
   * @param method how the money left: one of {@link CardSettlement#ANOTHER_WAYS}
   * @param reference what finds it again; required for {@code CARD} (the acquirer's refund
   *     reference)
   * @throws ApiException 404 {@code CARD_REFUND_DUE_NOT_FOUND}; 400 {@code
   *     CARD_REFUND_METHOD_INVALID}, {@code CARD_REFUND_REFERENCE_REQUIRED}, {@code
   *     TERMINAL_REASON_REQUIRED} or {@code TERMINAL_CARD_DATA_NOT_ACCEPTED}; 409 {@code
   *     CARD_REFUND_DUE_SETTLED}, {@code CARD_REFUND_DUE_NOT_TRIED}, {@code
   *     TERMINAL_REQUEST_IN_FLIGHT}, {@code TERMINAL_REFUND_UNDECIDED}, {@code
   *     CARD_REFUND_DUE_NOT_IN_BOOKS} or {@code IDEMPOTENCY_KEY_REUSED}
   */
  public Due refundedAnotherWay(
      UUID tenantId,
      UUID dueId,
      String method,
      String reference,
      String reason,
      UUID actorId,
      String idempotencyKey) {
    Due due = due(tenantId, dueId);
    String way = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
    String ref = blankToNull(reference);
    String invalid = CardSettlement.anotherWayInvalid(way, ref);
    if ("CARD_REFUND_METHOD_INVALID".equals(invalid)) {
      throw ApiException.badRequest(
          invalid,
          "method is how the money was given back: CASH, CARD (the acquirer's own refund, with its"
              + " reference), UPI or WALLET");
    }
    if (invalid != null) {
      throw ApiException.badRequest(
          invalid,
          "reference is required for CARD: the acquirer's refund reference, so it is found in the"
              + " settlement file");
    }
    // A reference is what the acquirer calls its refund, never the card it went to.
    refuseCardData(ref);
    String why = requireReason(reason);
    // One that went back on the card but was never finished is finished, not given back again.
    for (CardSettlement.Facts f : repo.attemptsOfDue(tenantId, dueId)) {
      if (CardSettlement.tookMoney(f.attempt().state(), f.outcome())) {
        completeRefund(f.attempt());
      }
    }
    Instant now = Instant.now();
    CardSettlement.Closure closure =
        new CardSettlement.Closure(
            Ids.newId(),
            tenantId,
            due.storeId(),
            due.id(),
            way,
            ref,
            why,
            idempotencyKey,
            actorId,
            now);
    RefundTender book = null;
    com.storeql.service.OutboxRow announced = null;
    if (due.paymentId() != null) {
      UUID refundId = Ids.newId();
      book =
          new RefundTender(
              refundId,
              tenantId,
              due.orderId(),
              due.paymentId(),
              due.amount(),
              way,
              ref,
              Ids.derived(due.id(), "card-refund-another-way").toString(),
              due.reason(),
              now);
      announced =
          Events.paymentRefunded(
              tenantId,
              refundId,
              due.orderId(),
              due.amount(),
              List.of(new RefundAllocation(due.paymentId(), way, due.amount(), due.storeId())),
              due.refundKind(),
              due.refundMethod(),
              due.returnId(),
              due.customerId(),
              due.currency());
    }
    Due closed = repo.closeAnotherWay(closure, book, announced);
    LOG.info(
        "money owed back to a card "
            + dueId
            + " ("
            + Amounts.shown(due.amount(), due.currency()).toPlainString()
            + " "
            + due.currency()
            + ") was given back another way ("
            + way
            + ") by "
            + actorId
            + "; it now stands "
            + (closed == null ? "unknown" : closed.state()));
    return closed;
  }

  /** How money owed back to a card was given back another way, if it was. */
  public Optional<CardSettlement.Closure> closureOf(UUID tenantId, UUID dueId) {
    return repo.closureOf(tenantId, dueId);
  }

  /**
   * The machine a sale's card goes back through: the one that took it while it is in service, and
   * once it is retired another of its vendor in service at its store, the newest first (the machine
   * that replaced it). A refund is linked to the sale by the vendor's own reference, so it needs
   * the vendor's channel and not the device that read the card; a retired machine used to mean the
   * money could never go back at all.
   *
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_RETIRED} when the
   *     machine is retired and its store has no other of its vendor in service (details {@code
   *     terminalId=…;vendor=…;storeId=…}): register one, or say how the money was given back
   *     another way
   */
  private Terminal machineToPutBack(UUID tenantId, Attempt sale) {
    Terminal own = require(tenantId, sale.terminalId());
    if (own.active()) return own;
    return repo.standIn(tenantId, own.storeId(), own.vendor(), own.id())
        .orElseThrow(
            () ->
                new ApiException(
                    409,
                    "TERMINAL_RETIRED",
                    "The card machine that took this payment has been retired, and its store has"
                        + " no other "
                        + own.vendor()
                        + " machine in service to put the money back through: register one, or"
                        + " record how it was given back another way",
                    List.of(
                        "terminalId="
                            + own.id()
                            + ";vendor="
                            + own.vendor()
                            + ";storeId="
                            + own.storeId())));
  }

  /**
   * Asks a machine to put a due back on the card that paid: the sale's own, or the one that stands
   * in for it.
   */
  private Attempt ask(Due due, String idempotencyKey, UUID actorId) {
    UUID tenantId = due.tenantId();
    Attempt sale =
        repo.attempt(tenantId, due.saleAttemptId())
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
    Terminal t = machineToPutBack(tenantId, sale);
    UUID attemptId = Ids.newId();
    Attempt claimed;
    try {
      claimed =
          repo.claimRefund(
              Terminals.requested(
                  attemptId,
                  tenantId,
                  t.storeId(),
                  t.id(),
                  sale.orderId(),
                  due.amount(),
                  sale.currency(),
                  Terminals.REFUND,
                  sale.id(),
                  actorId,
                  Instant.now(),
                  due.reason(),
                  due.id()),
              idempotencyKey);
    } catch (CardSettlement.TooMuch e) {
      throw tooMuch(e);
    } catch (CardSettlement.RefundOutstanding e) {
      throw outstandingRefusal(e);
    }
    if (!ownsClaim(claimed, attemptId)) {
      completeRefund(claimed);
      return claimed;
    }
    Attempt answered =
        run(
            tenantId,
            claimed,
            t,
            device -> device.refund(requestFor(claimed, t), sale.providerRef()));
    completeRefund(answered);
    return answered;
  }

  /**
   * Moves the due a refund attempt was for on, once the machine (or a person) has answered it: put
   * back, with the books' refund and its {@code PaymentRefunded} when the money was ever in the
   * books; otherwise waiting for a person, or — for a person's own refund the machine refused — not
   * owed after. A refund asked for nothing owed (of an approval no tender recorded at the time) has
   * no due to move, and is the books' all the same once its sale is recorded ({@link
   * #bookIfRecordedSince}).
   */
  private void completeRefund(Attempt refund) {
    if (!refund.settled()) return;
    if (refund.dueId() == null) {
      bookIfRecordedSince(refund);
      return;
    }
    UUID tenantId = refund.tenantId();
    Optional<Due> found = repo.due(tenantId, refund.dueId());
    if (found.isEmpty()) return;
    Due due = found.get();
    String seen = repo.decisionOf(tenantId, refund.id()).map(Decision::outcome).orElse(null);
    String next = CardSettlement.dueStateAfter(due.source(), refund.state(), seen);
    if (next == null) return;
    if (!CardSettlement.REFUNDED.equals(next)) {
      repo.completeDue(
          tenantId,
          due.id(),
          refund.id(),
          next,
          CardSettlement.attentionFor(refund.state(), seen, refund.outcomeDetail()),
          null,
          null,
          null);
      return;
    }
    CardSettlement.Landing landing =
        CardSettlement.landing(due.state(), due.refundAttemptId(), refund.id());
    if (landing == CardSettlement.Landing.ALREADY) return;
    if (landing == CardSettlement.Landing.BESIDE_THE_DUE) {
      LOG.severe(
          "card refund "
              + refund.id()
              + " (ref "
              + refund.providerRef()
              + ") put "
              + refund.amount().toPlainString()
              + " "
              + refund.currency()
              + " back on the card after money owed back "
              + due.id()
              + (CardSettlement.ANOTHER_WAY.equals(due.state())
                  ? " was already given back another way by a person: the customer has been"
                      + " refunded twice."
                  : " was already put back by refund "
                      + due.refundAttemptId()
                      + ": the card has been refunded twice.")
              + " It is written in the books as it happened; reconcile it against the acquirer's"
              + " settlement file.");
    } else if (CardSettlement.NOT_REFUNDED.equals(due.state())) {
      LOG.warning(
          "card refund "
              + refund.id()
              + " (ref "
              + refund.providerRef()
              + ") answered that it put the money back after it was taken not to have: money owed"
              + " back "
              + due.id()
              + " is put back by it after all, and written in the books once.");
    }
    RefundTender book = null;
    com.storeql.service.OutboxRow announced = null;
    if (due.paymentId() != null) {
      UUID refundId = Ids.newId();
      book =
          new RefundTender(
              refundId,
              tenantId,
              due.orderId(),
              due.paymentId(),
              refund.amount(),
              PaymentTender.METHOD_CARD,
              refund.providerRef(),
              Ids.derived(refund.id(), "card-refund-book").toString(),
              due.reason(),
              Instant.now());
      announced =
          Events.paymentRefunded(
              tenantId,
              refundId,
              due.orderId(),
              refund.amount(),
              List.of(
                  new RefundAllocation(
                      due.paymentId(), PaymentTender.METHOD_CARD, refund.amount(), due.storeId())),
              due.refundKind(),
              due.refundMethod(),
              due.returnId(),
              due.customerId(),
              due.currency());
    }
    repo.completeDue(tenantId, due.id(), refund.id(), next, null, book, due.storeId(), announced);
    if (CardSettlement.NOT_REFUNDED.equals(due.state())) sayIfRefundedTwice(refund);
  }

  /**
   * A refund asked for nothing owed that put money back, once its sale is in the books.
   *
   * <p>A manager's refund of an approval no tender had recorded is claimed with no due: nothing was
   * in the books to reverse. If the machine then goes quiet and a person says the refund was not
   * made, the sale — whole on its card as far as anybody knows — may be recorded as its order's
   * tender; and if the machine answers after that that it did put the money back, the card has it
   * while the books hold the tender whole. So whenever such a refund is known to have put money
   * back, the books' refund and its {@code PaymentRefunded} are written against the tender the sale
   * became — once, keyed by the refund itself, with the sale's row locked ({@link
   * TerminalRepository#bookRefundWithoutDue}) — and it is said aloud. While the sale is not
   * recorded there is nothing to write: the refund counts against the card from the moment the
   * machine said so, and the sale is not recorded after (some of it has gone back).
   */
  private void bookIfRecordedSince(Attempt refund) {
    if (!Terminals.REFUND.equals(refund.kind()) || refund.refundOf() == null) return;
    UUID tenantId = refund.tenantId();
    String seen = repo.decisionOf(tenantId, refund.id()).map(Decision::outcome).orElse(null);
    if (!CardSettlement.tookMoney(refund.state(), seen)) return;
    Optional<RefundTender> booked;
    try {
      booked =
          repo.bookRefundWithoutDue(
              tenantId,
              refund.id(),
              (sale, put) -> {
                UUID refundId = Ids.newId();
                return new TerminalRepository.Booking(
                    new RefundTender(
                        refundId,
                        tenantId,
                        sale.orderId(),
                        sale.paymentId(),
                        put.amount(),
                        PaymentTender.METHOD_CARD,
                        put.providerRef(),
                        Ids.derived(put.id(), "card-refund-book").toString(),
                        put.reason(),
                        Instant.now()),
                    Events.paymentRefunded(
                        tenantId,
                        refundId,
                        sale.orderId(),
                        put.amount(),
                        List.of(
                            new RefundAllocation(
                                sale.paymentId(),
                                PaymentTender.METHOD_CARD,
                                put.amount(),
                                sale.storeId())),
                        null,
                        null,
                        null,
                        null,
                        sale.currency()));
              });
    } catch (RuntimeException e) {
      // The card has the money whatever the books say: never left unsaid. The request fails, so
      // asking again under the same Idempotency-Key writes it (the books' row is keyed by the
      // refund, so it is written once).
      LOG.log(
          Level.SEVERE,
          "card refund "
              + refund.id()
              + " (ref "
              + refund.providerRef()
              + ") put "
              + Amounts.shown(refund.amount(), refund.currency()).toPlainString()
              + " "
              + refund.currency()
              + " back on the card of sale "
              + refund.refundOf()
              + ", and whether its sale is in the books could not be read or the books' refund"
              + " could not be written: "
              + e.getMessage()
              + ". Ask again under the same Idempotency-Key; until then reconcile it against the"
              + " acquirer's settlement file.",
          e);
      throw e;
    }
    if (booked.isEmpty()) return;
    LOG.warning(
        "card refund "
            + refund.id()
            + " (ref "
            + refund.providerRef()
            + ") put "
            + Amounts.shown(refund.amount(), refund.currency()).toPlainString()
            + " "
            + refund.currency()
            + " back on a card whose sale "
            + refund.refundOf()
            + " was recorded as a tender after the refund was asked (a person had said it was not"
            + " made): it is written in the books against that tender, once, as refund "
            + booked.get().id()
            + ".");
    sayIfRefundedTwice(refund);
  }

  /**
   * After a refund taken not to have been made is put back after all: when what has gone back on
   * the sale's card is now more than it paid, a person's "not made" was wrong and another refund
   * was made meanwhile — the card has been refunded twice. Both are in the books as they happened;
   * this says so, for the acquirer's settlement file to reconcile.
   */
  private void sayIfRefundedTwice(Attempt refund) {
    try {
      Optional<Attempt> sale = repo.attempt(refund.tenantId(), refund.refundOf());
      if (sale.isEmpty()) return;
      BigDecimal back = repo.facts(sale.get()).committed();
      if (back != null && back.compareTo(sale.get().amount()) > 0) {
        LOG.severe(
            "card refund "
                + refund.id()
                + " (ref "
                + refund.providerRef()
                + ") was put back after it was taken not to have been, and another refund was made"
                + " meanwhile: "
                + Amounts.shown(back, sale.get().currency()).toPlainString()
                + " "
                + sale.get().currency()
                + " has gone back on a card that paid "
                + Amounts.shown(sale.get().amount(), sale.get().currency()).toPlainString()
                + " (sale "
                + sale.get().id()
                + "): the card has been refunded twice. Both are in the books as they happened;"
                + " reconcile them against the acquirer's settlement file.");
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "could not check refund " + refund.id() + " against its sale", e);
    }
  }

  // ── refusals ────────────────────────────────────────────────────────────────

  /**
   * The refusal a till acts on: each unsettled card payment, one detail each, as {@code
   * attemptId=…;orderId=…;amount=…;currency=…;onCard=…;state=…;standing=…;kind=SALE} for a sale and
   * {@code …;kind=REFUND;refundOf=<the sale it would reverse>} for a refund. {@code onCard} is what
   * of a sale is still on the card as far as anybody knows; a refund holds nothing on it ({@code
   * 0}).
   */
  private static final String UNSETTLED_SALE =
      "This card machine has a card payment that is not settled. Record it on its sale, put it"
          + " back on the card, or say what the machine shows (for a refund too); then it takes"
          + " the next one.";

  private static ApiException unsettledRefusal(CardSettlement.MachineHeld held, String message) {
    List<String> details = new ArrayList<>();
    for (CardSettlement.Facts f : held.open()) {
      Attempt a = f.attempt();
      boolean sale = Terminals.SALE.equals(a.kind());
      BigDecimal onCard =
          sale ? CardSettlement.stillOnCard(a.amount(), f.committed()) : BigDecimal.ZERO;
      details.add(
          "attemptId="
              + a.id()
              + ";orderId="
              + a.orderId()
              + ";amount="
              + Amounts.shown(a.amount(), a.currency()).toPlainString()
              + ";currency="
              + a.currency()
              + ";onCard="
              + Amounts.shown(onCard, a.currency()).toPlainString()
              + ";state="
              + a.state()
              + ";standing="
              + f.standing()
              + ";kind="
              + a.kind()
              + (sale ? "" : ";refundOf=" + a.refundOf()));
    }
    return new ApiException(409, "TERMINAL_UNSETTLED_APPROVAL", message, details, held);
  }

  /**
   * The refusal for retiring the last machine of its vendor at a store while cards that vendor's
   * machines took there are owed money back: 409 {@code TERMINAL_REFUNDS_OWED}, one detail per sum
   * owed, oldest first, as {@code dueId=…;attemptId=<the sale>;orderId=…;amount=…;currency=…;
   * state=…}.
   */
  private static ApiException owedRefusal(CardSettlement.OwedOnMachine owing, Terminal t) {
    List<String> details = new ArrayList<>();
    for (Due d : owing.owed()) {
      details.add(
          "dueId="
              + d.id()
              + ";attemptId="
              + d.saleAttemptId()
              + ";orderId="
              + d.orderId()
              + ";amount="
              + Amounts.shown(d.amount(), d.currency()).toPlainString()
              + ";currency="
              + d.currency()
              + ";state="
              + d.state());
    }
    return new ApiException(
        409,
        "TERMINAL_REFUNDS_OWED",
        "Money is owed back to cards that "
            + t.vendor()
            + " machines took at this store, and this is the last one in service there: with it"
            + " retired nothing could put it back. Register the machine that replaces it first,"
            + " ask this one again, or record how each was given back another way; then it can be"
            + " retired.",
        details,
        owing);
  }

  /**
   * The refusal for a refund of a sale while another refund of it is not accounted for: 409 {@code
   * TERMINAL_REFUND_IN_FLIGHT} (still at the machine) or {@code TERMINAL_REFUND_UNDECIDED} (timed
   * out with nobody's word on it), naming that refund as {@code
   * attemptId=…;state=…;amount=…;currency=…}.
   */
  private static ApiException outstandingRefusal(CardSettlement.RefundOutstanding e) {
    Attempt open = e.open();
    boolean atMachine = "TERMINAL_REFUND_IN_FLIGHT".equals(e.code());
    return new ApiException(
        409,
        e.code(),
        atMachine
            ? "A refund of this card payment is at the card machine: wait for its answer before"
                + " asking another"
            : "The card machine did not answer a refund of this card payment: say what it shows"
                + " for that one (POST /payments/terminal/"
                + open.id()
                + "/settle) before asking another",
        List.of(
            "attemptId="
                + open.id()
                + ";state="
                + open.state()
                + ";amount="
                + Amounts.shown(open.amount(), open.currency()).toPlainString()
                + ";currency="
                + open.currency()),
        e);
  }

  private static ApiException tooMuch(CardSettlement.TooMuch e) {
    return new ApiException(
        409,
        "TERMINAL_REFUND_TOO_LARGE",
        "That is more than the "
            + Amounts.shown(e.left(), e.currency()).toPlainString()
            + " still on this card",
        List.of(),
        e);
  }

  /**
   * @throws ApiException 400 {@code TERMINAL_REASON_REQUIRED} for none, or one of more than 500
   *     characters
   */
  private static String requireReason(String reason) {
    String why = blankToNull(reason);
    if (why == null || why.length() > 500) {
      throw ApiException.badRequest(
          "TERMINAL_REASON_REQUIRED", "A reason is required, of at most 500 characters");
    }
    return why;
  }

  // ── the one place a terminal is actually asked ───────────────────────────────

  /** What to do with a device, once the attempt is safely claimed. */
  @FunctionalInterface
  private interface Ask {
    Terminals.Outcome of(CardTerminal device);
  }

  /**
   * Asks the device, settles the attempt with whatever came back, and never leaves it {@code
   * REQUESTED}.
   *
   * <p>An implementation that throws is settled as {@link Terminals#FAILED} rather than left open:
   * an attempt stuck in {@code REQUESTED} is indistinguishable from one where the card may have
   * been charged, and that is the state this row exists to avoid producing accidentally.
   *
   * <p>The machine's answer is never dropped. One that arrives after a person settled the attempt
   * (the machine went quiet and somebody said what it shows) is kept when it says more about the
   * money ({@link CardSettlement#answerApplies}) and said aloud either way, with its reference and
   * authorisation code. A sale that took money on an order already given up is put back at once.
   */
  private Attempt run(UUID tenantId, Attempt claimed, Terminal t, Ask ask) {
    Terminals.Outcome outcome;
    try {
      outcome = ask.of(deviceFor(t.vendor()));
      if (outcome == null) {
        outcome = failure("The terminal returned nothing");
      }
    } catch (RuntimeException e) {
      // Deliberately FAILED and not TIMED_OUT: an exception on the way out means the request did
      // not
      // reach the card. An implementation that could not get an answer is required to say TIMED_OUT
      // itself, because only it knows whether the card was engaged.
      LOG.log(Level.WARNING, "terminal " + t.id() + " failed: " + e.getMessage(), e);
      outcome = failure(e.getMessage());
    }
    if (outcome.uncertain()) {
      LOG.warning(
          "terminal attempt "
              + claimed.id()
              + " timed out: the card may have been charged. No tender recorded; reconcile against"
              + " the acquirer's settlement file.");
    }
    CardSettlement.Answered answered;
    try {
      answered = repo.settle(tenantId, claimed.id(), outcome);
    } catch (RuntimeException e) {
      // The attempt stays at the machine (the write rolled back) and holds its terminal until a
      // person says what it shows; what the machine said is kept here so they can.
      LOG.log(
          Level.SEVERE,
          "terminal attempt "
              + claimed.id()
              + ": the machine answered "
              + outcome.state()
              + " (ref "
              + outcome.providerRef()
              + ", auth "
              + outcome.authCode()
              + ") and it could not be written: "
              + e.getMessage(),
          e);
      throw e;
    }
    if (answered.late()) {
      LOG.warning(
          "terminal attempt "
              + claimed.id()
              + ": the machine answered "
              + outcome.state()
              + " (ref "
              + outcome.providerRef()
              + ", auth "
              + outcome.authCode()
              + ") after the attempt was settled as "
              + answered.before()
              + (answered.applied()
                  ? "; the machine's answer is kept: what it did is what happened to the card"
                  : "; it does not change it, and is recorded here only"));
    }
    Attempt now = repo.attempt(tenantId, claimed.id()).orElse(claimed);
    if (answered.owedBack()) putBackOwedNow(tenantId, now.orderId());
    return now;
  }

  /**
   * True only for the call whose own attempt row was written by the claim. A replay gets the
   * earlier attempt back (another id), settled or still at the device, and must not ask again.
   */
  private static boolean ownsClaim(Attempt claimed, UUID attemptId) {
    return attemptId.equals(claimed.id()) && !claimed.settled();
  }

  private static Terminals.Outcome failure(String detail) {
    return Terminals.refused(
        Terminals.FAILED, null, detail == null ? "The terminal could not be reached" : detail);
  }

  private CardTerminal.Request requestFor(Attempt attempt, Terminal t) {
    return new CardTerminal.Request(
        t.id(),
        t.serial(),
        attempt.amount(),
        attempt.currency(),
        attempt.orderId(),
        attempt.id().toString());
  }

  private CardTerminal deviceFor(String vendor) {
    for (CardTerminal candidate : terminals) {
      if (candidate.vendor().equals(vendor)) return candidate;
    }
    // A vendor in the register with no implementation deployed: the row was written by a build that
    // had one. Refusing loudly beats falling back to the simulator, which would claim money moved.
    throw ApiException.conflict(
        "TERMINAL_VENDOR_UNAVAILABLE", "This deployment cannot talk to a " + vendor + " terminal");
  }

  // ── guards ──────────────────────────────────────────────────────────────────

  private Terminal require(UUID tenantId, UUID id) {
    return repo.find(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TERMINAL_NOT_FOUND", "No such terminal"));
  }

  private Terminal requireActive(UUID tenantId, UUID id) {
    Terminal t = require(tenantId, id);
    if (!t.active()) {
      throw ApiException.conflict("TERMINAL_RETIRED", "That terminal has been retired");
    }
    return t;
  }

  /**
   * Money, positive, and no finer than its own currency's minor unit (ISO 4217, through {@link
   * com.storeql.service.Fx#minorUnits}): three places for a dinar, none for a yen.
   *
   * @return the amount at exactly the currency's minor units, as it is stored and sent to the
   *     device
   * @throws ApiException 400 {@code CURRENCY_INVALID} for a code ISO 4217 does not know, since its
   *     minor units are then unknown; 400 {@code TERMINAL_AMOUNT_INVALID} for an amount that is not
   *     positive or is finer than the currency's minor unit. Refused rather than rounded: rounding
   *     somebody else's money by a fraction of its smallest coin is not this service's decision
   */
  private static BigDecimal requireMoney(BigDecimal amount, String currency) {
    if (!com.storeql.service.Fx.isCurrency(currency)) {
      throw ApiException.badRequest(
          "CURRENCY_INVALID", "currency must be an ISO 4217 code such as EUR or JPY");
    }
    if (amount == null || amount.signum() <= 0 || !Amounts.fits(amount, currency)) {
      throw ApiException.badRequest(
          "TERMINAL_AMOUNT_INVALID",
          "An amount is positive and has no more decimal places than "
              + currency
              + " has ("
              + com.storeql.service.Fx.minorUnits(currency)
              + ")");
    }
    return Amounts.exact(amount, currency);
  }

  /**
   * A card is taken in the business's own currency: the till names it, the business trades in one
   * ({@code TenantProfiles}), and a sale in any other is not one this business makes — a terminal
   * would charge the cardholder in a currency nothing else on the sale is in. The terminal itself
   * offers a cardholder their own currency (DCC) where the acquirer allows it; the merchant's
   * request is always in the merchant's.
   *
   * <p>Fails open: when the business's currency cannot be read just now (tenant-svc unreachable,
   * nothing cached), the till is not stopped over it, and it is logged. The currency is still an
   * ISO 4217 code and the amount still held to its minor units ({@link #requireMoney}).
   *
   * @throws ApiException 409 {@code TERMINAL_CURRENCY_MISMATCH}, details {@code currency=} the
   *     business's own
   */
  private void requireBusinessCurrency(UUID tenantId, String currency) {
    Optional<String> own;
    try {
      own =
          profiles == null
              ? Optional.empty()
              : profiles.find(tenantId).map(com.storeql.service.TenantProfiles.Profile::currency);
    } catch (RuntimeException e) {
      own = Optional.empty();
    }
    if (own.isEmpty()) {
      LOG.warning(
          "card sale for business "
              + tenantId
              + " in "
              + currency
              + ": the business's own currency cannot be read just now, so it is not checked");
      return;
    }
    if (!own.get().equals(currency)) {
      throw new ApiException(
          409,
          "TERMINAL_CURRENCY_MISMATCH",
          "This business takes cards in "
              + own.get()
              + "; a sale in "
              + currency
              + " is not one it makes",
          List.of("currency=" + own.get()));
    }
  }

  /**
   * Refuses anything card-shaped, wherever it came from.
   *
   * <p>Belt to the gateway's braces. The gateway already refuses card-shaped bodies; this refuses
   * one that arrived by another route — a field renamed, a new client, a harness. Nothing
   * downstream has anywhere to put a card number, and the right place to find that out is here.
   *
   * @throws ApiException 400 {@code TERMINAL_CARD_DATA_NOT_ACCEPTED}
   */
  public static void refuseCardData(String... values) {
    for (String value : values) {
      if (Terminals.looksLikeCardNumber(value)) {
        throw ApiException.badRequest(
            "TERMINAL_CARD_DATA_NOT_ACCEPTED",
            "This platform never takes a card number. The terminal reads the card itself.");
      }
    }
  }

  private static String requireLabel(String label) {
    String trimmed = blankToNull(label);
    if (trimmed == null) {
      throw ApiException.badRequest(
          "TERMINAL_LABEL_REQUIRED", "A terminal needs a label a cashier can recognise");
    }
    return trimmed;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
