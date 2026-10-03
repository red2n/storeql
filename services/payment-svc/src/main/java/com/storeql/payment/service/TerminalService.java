package com.storeql.payment.service;

import com.storeql.ids.Ids;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 */
@ApplicationScoped
public class TerminalService {

  private static final Logger LOG = Logger.getLogger(TerminalService.class.getName());

  @Inject TerminalRepository repo;

  /**
   * Every terminal implementation deployed. Selected by vendor, never named in this class's logic.
   */
  @Inject Instance<CardTerminal> terminals;

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
   * Retires a terminal. Never deleted: payments point at it.
   *
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_ALREADY_RETIRED}
   */
  public Terminal retire(UUID tenantId, UUID id, String reason) {
    Terminal t = require(tenantId, id);
    if (!t.active()) {
      throw ApiException.conflict("TERMINAL_ALREADY_RETIRED", "That terminal is already retired");
    }
    repo.retire(tenantId, id, blankToNull(reason));
    return require(tenantId, id);
  }

  // ── taking a card ───────────────────────────────────────────────────────────

  /**
   * Takes a sale on a terminal and returns what the terminal said.
   *
   * @param idempotencyKey the replay guard. Strongly wanted: without it a retried press is a second
   *     EMV transaction on a real card
   * @throws ApiException 404 {@code TERMINAL_NOT_FOUND}; 409 {@code TERMINAL_RETIRED} or {@code
   *     TERMINAL_WRONG_STORE}; 400 {@code TERMINAL_AMOUNT_INVALID} or {@code
   *     TERMINAL_CARD_DATA_NOT_ACCEPTED}
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
    BigDecimal due = requireMoney(amount);

    UUID attemptId = Ids.newId();
    Attempt claimed =
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
    // The retry's whole purpose: the same key finds the attempt that already went to the terminal,
    // and nothing is asked of the device a second time. That holds for a settled attempt and also
    // for one still REQUESTED, where the first press is at the device: the replay gets that attempt
    // back, still pending, and only the call that wrote the row (its id is ours) asks the device.
    if (!ownsClaim(claimed, attemptId)) return claimed;

    return run(tenantId, claimed, t, device -> device.sale(requestFor(claimed, t)));
  }

  /**
   * Puts money back on the card that paid it.
   *
   * <p>Linked to the original attempt rather than taking a card again: an unlinked refund is how
   * card fraud is done, and most acquirers refuse them outright.
   *
   * @throws ApiException 404 {@code TERMINAL_ATTEMPT_NOT_FOUND}; 409 {@code TERMINAL_NOT_APPROVED}
   *     when the attempt being refunded took no money, or {@code TERMINAL_REFUND_TOO_LARGE}
   */
  public Attempt refund(
      UUID tenantId,
      UUID originalAttemptId,
      BigDecimal amount,
      UUID actorId,
      String idempotencyKey) {
    Attempt original =
        repo.attempt(tenantId, originalAttemptId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
    if (!original.approved()) {
      throw ApiException.conflict(
          "TERMINAL_NOT_APPROVED", "That attempt took no money, so there is nothing to put back");
    }
    BigDecimal back = requireMoney(amount);
    if (back.compareTo(original.amount()) > 0) {
      throw ApiException.conflict(
          "TERMINAL_REFUND_TOO_LARGE",
          "That is more than the " + original.amount() + " taken on this card");
    }
    Terminal t = requireActive(tenantId, original.terminalId());

    UUID attemptId = Ids.newId();
    Attempt claimed =
        repo.claim(
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
                Instant.now()),
            idempotencyKey);
    if (!ownsClaim(claimed, attemptId)) return claimed;

    return run(
        tenantId,
        claimed,
        t,
        device -> device.refund(requestFor(claimed, t), original.providerRef()));
  }

  /**
   * Tells the device to stop asking for a card, when the cashier abandons the tender.
   *
   * <p>Best effort by nature: the cardholder may have completed it in the meantime. The attempt is
   * settled as cancelled only if nothing else settled it first, so a cancel racing an approval
   * loses.
   */
  public Attempt cancel(UUID tenantId, UUID attemptId) {
    Attempt attempt =
        repo.attempt(tenantId, attemptId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
    if (attempt.settled()) return attempt;
    Terminal t = require(tenantId, attempt.terminalId());
    Terminals.Outcome outcome = deviceFor(t.vendor()).cancel(attempt.providerRef());
    repo.settle(tenantId, attempt.id(), outcome);
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
    repo.settle(tenantId, claimed.id(), outcome);
    return repo.attempt(tenantId, claimed.id()).orElse(claimed);
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
   * Money, to two places, and positive.
   *
   * @throws ApiException 400 {@code TERMINAL_AMOUNT_INVALID}. A third decimal place is refused
   *     rather than rounded: rounding somebody else's money by a tenth of a penny is not this
   *     service's decision to make
   */
  private static BigDecimal requireMoney(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
      throw ApiException.badRequest(
          "TERMINAL_AMOUNT_INVALID", "An amount is positive and has at most two decimal places");
    }
    return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
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
