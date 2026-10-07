package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Disputes;
import com.storeql.payment.domain.Disputes.Dispute;
import com.storeql.payment.domain.Disputes.DisputeEvent;
import com.storeql.payment.domain.Disputes.DisputeFile;
import com.storeql.payment.domain.Disputes.Evidence;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.dto.DisputeDtos;
import com.storeql.payment.provider.PaymentProvider;
import com.storeql.payment.provider.PaymentProvider.DisputeNotice;
import com.storeql.payment.provider.PaymentProviders;
import com.storeql.payment.repo.DisputeRepository;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Chargebacks and disputes (11.9). A dispute reaches this service one of two ways: the payment
 * provider's webhook, for money the provider took; or a member of staff recording the acquirer's
 * letter, for a card taken on a terminal the platform does not talk to. Either way it has a date by
 * which the business must answer, an answer that can be given once, and an end — won, lost or
 * accepted — and each step is announced, because the ledger moves money on it: out of card clearing
 * into disputed receipts when the acquirer takes it, back on a win, to losses otherwise.
 */
@ApplicationScoped
public class DisputeService {

  private static final Logger LOG = System.getLogger(DisputeService.class.getName());
  private static final String MANUAL = PaymentIntent.PROVIDER_MANUAL;

  /** Tenders a cardholder's bank can take back: never cash, a gift card or store credit. */
  private static final Set<String> DISPUTABLE =
      Set.of(PaymentTender.METHOD_CARD, PaymentTender.METHOD_UPI, PaymentTender.METHOD_WALLET);

  /** The level (0.9%) at which the card schemes start monitoring a merchant's disputes. */
  static final BigDecimal MONITORING_THRESHOLD = new BigDecimal("0.0090");

  @Inject DisputeRepository repo;
  @Inject PaymentRepository payments;
  @Inject PaymentProviders providers;
  @Inject TenantProfiles profiles;

  // ── recorded by staff ───────────────────────────────────────────────────────

  /**
   * Records a chargeback the acquirer has told the business about.
   *
   * @throws ApiException 404 {@code PAYMENT_NOT_FOUND}; 409 {@code DISPUTE_NOT_DISPUTABLE} for a
   *     tender no bank can take back or one a provider would tell us about itself, {@code
   *     DISPUTE_ALREADY_OPEN}; 400 for an amount over the tender, an unknown reason or a date that
   *     has already passed; 400 {@code DISPUTE_AMOUNT_INVALID} for an amount or fee finer than the
   *     dispute's currency's minor unit
   */
  public Dispute record(
      UUID tenantId, UUID actorId, DisputeDtos.RecordDisputeRequest req, String idempotencyKey) {
    PaymentTender tender =
        payments
            .findTender(tenantId, req.paymentId())
            .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "No such payment"));
    String method = tender.method() == null ? "" : tender.method().toUpperCase(Locale.ROOT);
    if (!DISPUTABLE.contains(method) || !PaymentTender.STATUS_CAPTURED.equals(tender.status())) {
      throw ApiException.conflict(
          "DISPUTE_NOT_DISPUTABLE", "Only a captured card payment can be charged back");
    }
    String reason = req.reason().trim().toUpperCase(Locale.ROOT);
    if (!Disputes.REASONS.contains(reason)) {
      throw ApiException.badRequest("DISPUTE_REASON_UNKNOWN", "Not a reason a card scheme gives");
    }
    BigDecimal amount = req.amount() == null ? tender.amount() : req.amount();
    BigDecimal fee = req.feeAmount() == null ? BigDecimal.ZERO : req.feeAmount();
    String currency = profiles.currencyOr(tenantId, req.currency());
    // The acquirer's letter is money in its currency: no finer than its minor unit, never rounded.
    Amounts.requireFits(amount, currency, "DISPUTE_AMOUNT_INVALID");
    Amounts.requireFits(fee, currency, "DISPUTE_AMOUNT_INVALID");
    if (amount.compareTo(tender.amount()) > 0) {
      throw ApiException.badRequest(
          "DISPUTE_AMOUNT_EXCEEDS_PAYMENT", "A dispute cannot be for more than was paid");
    }
    Instant now = Instant.now();
    if (!req.evidenceDueBy().isAfter(now)) {
      throw ApiException.badRequest(
          "DISPUTE_DUE_DATE_PAST", "The date the answer is due by has already passed");
    }
    Dispute d =
        new Dispute(
            Ids.newId(),
            tenantId,
            tender.id(),
            tender.orderId(),
            tender.storeId(),
            MANUAL,
            req.caseReference().trim(),
            amount,
            fee,
            currency,
            reason,
            blankToNull(req.networkReasonCode()),
            Disputes.NEEDS_RESPONSE,
            req.fundsWithdrawn() == null || req.fundsWithdrawn(),
            req.evidenceDueBy(),
            now,
            null,
            blankToNull(idempotencyKey),
            actorId,
            // The acquirer's letter is in one currency, the fee with the amount.
            currency);
    return open(d, actorId, "Recorded from the acquirer's notice, case " + d.providerDisputeRef());
  }

  private Dispute open(Dispute d, UUID actorId, String detail) {
    List<DisputeEvent> history = new ArrayList<>();
    history.add(
        new DisputeEvent(Ids.newId(), Disputes.EVENT_OPENED, detail, actorId, d.openedAt()));
    if (d.fundsWithdrawn()) {
      history.add(
          new DisputeEvent(
              Ids.newId(),
              Disputes.EVENT_FUNDS_WITHDRAWN,
              d.amount().toPlainString()
                  + " "
                  + d.currency()
                  + " and a fee of "
                  + d.feeAmount().toPlainString()
                  + " "
                  + d.feeCurrency(),
              actorId,
              d.openedAt()));
    }
    DisputeRepository.OpenResult result = repo.open(d, history, Events.disputeOpened(d));
    if (result.outcome() == DisputeRepository.Opened.ALREADY_OPEN) {
      throw ApiException.conflict(
          "DISPUTE_ALREADY_OPEN",
          "This payment already has a dispute open: " + result.dispute().id());
    }
    return result.dispute();
  }

  // ── told by the provider ────────────────────────────────────────────────────

  /**
   * Applies what a provider's verified webhook says about a dispute. Idempotent: the provider's
   * reference is the key, and every move is guarded on the state it expects.
   *
   * @param intent the payment intent the disputed charge belongs to, or null when this service does
   *     not know it — nothing to apply then, and the caller still records the event as seen
   */
  public void fromProvider(String provider, PaymentIntent intent, DisputeNotice notice) {
    Dispute known = repo.findByProviderRef(provider, notice.disputeRef()).orElse(null);
    if (known == null) {
      if (intent == null || intent.paymentId() == null) {
        LOG.log(
            Level.WARNING,
            "dispute {0} is about a payment this service does not hold",
            notice.disputeRef());
        return;
      }
      boolean withdrawn = DisputeNotice.PHASE_FUNDS_WITHDRAWN.equals(notice.phase());
      Instant now = Instant.now();
      known =
          open(
              new Dispute(
                  Ids.newId(),
                  intent.tenantId(),
                  intent.paymentId(),
                  intent.orderId(),
                  intent.storeId(),
                  provider,
                  notice.disputeRef(),
                  notice.amount(),
                  notice.fee() == null || notice.feeCurrency() == null
                      ? BigDecimal.ZERO
                      : notice.fee(),
                  notice.currency(),
                  notice.reason(),
                  notice.networkReasonCode(),
                  Disputes.NEEDS_RESPONSE,
                  withdrawn,
                  notice.evidenceDueBy(),
                  now,
                  null,
                  null,
                  null,
                  // The fee in the currency the provider charged it in; one it said nothing
                  // readable about is none yet, which is zero in the charge's own currency.
                  notice.fee() == null || notice.feeCurrency() == null
                      ? notice.currency()
                      : notice.feeCurrency()),
              null,
              "Opened by " + provider);
      if (!DisputeNotice.PHASE_CLOSED.equals(notice.phase())) return;
    }
    Instant now = Instant.now();
    switch (notice.phase()) {
      case DisputeNotice.PHASE_FUNDS_WITHDRAWN -> {
        // The fee and its currency together, or what was known of both: never a figure in one
        // currency and a code from another.
        boolean said = notice.fee() != null && notice.feeCurrency() != null;
        BigDecimal fee = said ? notice.fee() : known.feeAmount();
        String feeCurrency = said ? notice.feeCurrency() : known.feeCurrency();
        Dispute after = withFunds(known, fee, feeCurrency);
        repo.fundsWithdrawn(
            known.tenantId(),
            known.id(),
            fee,
            feeCurrency,
            now,
            new DisputeEvent(
                Ids.newId(), Disputes.EVENT_FUNDS_WITHDRAWN, "By " + provider, null, now),
            Events.disputeFundsWithdrawn(after));
      }
      case DisputeNotice.PHASE_CLOSED ->
          close(
              known,
              "LOST".equals(notice.outcome()) ? Disputes.LOST : Disputes.WON,
              null,
              "Decided by the card scheme",
              null);
      default -> {
        // UPDATED, FUNDS_REINSTATED: nothing of ours changes until the dispute is closed, which
        // the provider also tells us.
      }
    }
  }

  // ── the business's answer ───────────────────────────────────────────────────

  /**
   * Records the business's evidence and, for a provider's dispute, sends it. A scheme takes
   * evidence once, and not after its date.
   *
   * @throws ApiException 404; 409 {@code DISPUTE_NOT_AWAITING_RESPONSE}, {@code
   *     DISPUTE_EVIDENCE_LATE}; 400 {@code DISPUTE_EVIDENCE_EMPTY}; 502 when the provider refused
   */
  public DisputeFile submitEvidence(
      UUID tenantId, UUID actorId, UUID id, DisputeDtos.EvidenceRequest req) {
    Dispute d = require(tenantId, id);
    Instant now = Instant.now();
    if (!Disputes.NEEDS_RESPONSE.equals(d.status())) {
      throw ApiException.conflict(
          "DISPUTE_NOT_AWAITING_RESPONSE", "This dispute is not waiting for an answer");
    }
    if (d.evidenceDueBy() != null && now.isAfter(d.evidenceDueBy())) {
      throw ApiException.conflict(
          "DISPUTE_EVIDENCE_LATE", "The date for answering this dispute has passed");
    }
    Evidence e =
        new Evidence(
            blankToNull(req.productDescription()),
            blankToNull(req.customerName()),
            blankToNull(req.customerEmail()),
            blankToNull(req.receiptReference()),
            blankToNull(req.fulfilmentProof()),
            blankToNull(req.customerCommunication()),
            blankToNull(req.refundPolicy()),
            blankToNull(req.notes()),
            actorId,
            now);
    if (uncategorized(e).isBlank() && e.productDescription() == null && e.customerName() == null) {
      throw ApiException.badRequest("DISPUTE_EVIDENCE_EMPTY", "An answer has to say something");
    }
    if (!MANUAL.equals(d.provider())) {
      send(d, p -> p.submitDisputeEvidence(d.providerDisputeRef(), answer(e)));
    }
    boolean recorded =
        repo.submitEvidence(
            tenantId,
            id,
            e,
            new DisputeEvent(
                Ids.newId(),
                Disputes.EVENT_EVIDENCE_SUBMITTED,
                MANUAL.equals(d.provider())
                    ? "Kept here; sent to the acquirer by the business"
                    : "Sent to " + d.provider(),
                actorId,
                now));
    if (!recorded) {
      throw ApiException.conflict(
          "DISPUTE_NOT_AWAITING_RESPONSE", "This dispute is not waiting for an answer");
    }
    return file(tenantId, id);
  }

  /**
   * The business will not contest: the dispute is lost by its own decision.
   *
   * @throws ApiException 404; 409 {@code DISPUTE_CLOSED}; 502 when the provider refused
   */
  public DisputeFile accept(UUID tenantId, UUID actorId, UUID id) {
    Dispute d = require(tenantId, id);
    if (!d.open()) throw ApiException.conflict("DISPUTE_CLOSED", "This dispute is already closed");
    if (!MANUAL.equals(d.provider())) send(d, p -> p.acceptDispute(d.providerDisputeRef()));
    close(d, Disputes.ACCEPTED, null, "Accepted: not contested", actorId);
    return file(tenantId, id);
  }

  /**
   * How a dispute the acquirer told the business about ended. A provider's dispute is decided by
   * its webhook, never here.
   *
   * @throws ApiException 404; 409 {@code DISPUTE_DECIDED_BY_PROVIDER}, {@code DISPUTE_CLOSED}; 400
   *     {@code DISPUTE_OUTCOME_UNKNOWN}
   */
  public DisputeFile resolve(UUID tenantId, UUID actorId, UUID id, DisputeDtos.ResolveRequest req) {
    Dispute d = require(tenantId, id);
    if (!MANUAL.equals(d.provider())) {
      throw ApiException.conflict(
          "DISPUTE_DECIDED_BY_PROVIDER", d.provider() + " tells this service how its disputes end");
    }
    String outcome = req.outcome().trim().toUpperCase(Locale.ROOT);
    if (!Disputes.WON.equals(outcome) && !Disputes.LOST.equals(outcome)) {
      throw ApiException.badRequest("DISPUTE_OUTCOME_UNKNOWN", "An outcome is WON or LOST");
    }
    if (!d.open()) throw ApiException.conflict("DISPUTE_CLOSED", "This dispute is already closed");
    close(d, outcome, null, blankToNull(req.note()), actorId);
    return file(tenantId, id);
  }

  private void close(Dispute d, String status, String fromStatus, String detail, UUID actorId) {
    Instant now = Instant.now();
    List<DisputeEvent> happened = new ArrayList<>();
    happened.add(
        new DisputeEvent(
            Ids.newId(),
            Disputes.WON.equals(status)
                ? Disputes.EVENT_WON
                : Disputes.ACCEPTED.equals(status) ? Disputes.EVENT_ACCEPTED : Disputes.EVENT_LOST,
            detail,
            actorId,
            now));
    if (Disputes.WON.equals(status) && d.fundsWithdrawn()) {
      happened.add(
          new DisputeEvent(
              Ids.newId(),
              Disputes.EVENT_FUNDS_REINSTATED,
              d.amount().toPlainString() + " " + d.currency(),
              actorId,
              now));
    }
    if (!repo.close(
            d.tenantId(),
            d.id(),
            fromStatus,
            status,
            now,
            happened,
            Events.disputeClosed(d, status))
        && actorId != null) {
      throw ApiException.conflict("DISPUTE_CLOSED", "This dispute is already closed");
    }
  }

  // ── reading ─────────────────────────────────────────────────────────────────

  public DisputeFile file(UUID tenantId, UUID id) {
    Dispute d = require(tenantId, id);
    return new DisputeFile(d, repo.events(tenantId, id), repo.evidence(tenantId, id).orElse(null));
  }

  public Cursor.Page<Dispute> list(
      UUID tenantId, String status, UUID storeId, String after, Integer limit) {
    String wanted = blankToNull(status);
    if (wanted != null) {
      wanted = wanted.toUpperCase(Locale.ROOT);
      if (!Disputes.OPEN.contains(wanted)
          && !Set.of(Disputes.WON, Disputes.LOST, Disputes.ACCEPTED).contains(wanted)) {
        throw ApiException.badRequest("DISPUTE_STATUS_UNKNOWN", "Not a dispute status");
      }
    }
    int lim = Cursor.clampLimit(limit);
    List<Dispute> rows =
        repo.list(tenantId, wanted, storeId, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, d -> d.openedAt() + "|" + d.id());
  }

  public Disputes.Summary summary(UUID tenantId, UUID storeId, Instant from, Instant to) {
    if (!to.isAfter(from)) {
      throw ApiException.badRequest("DISPUTE_PERIOD_INVALID", "The period ends before it begins");
    }
    return repo.summary(tenantId, storeId, from, to);
  }

  /** Whether a ratio is at or past the level the card schemes start monitoring at. */
  public static boolean aboveMonitoringThreshold(BigDecimal ratio) {
    return ratio != null && ratio.compareTo(MONITORING_THRESHOLD) >= 0;
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Dispute require(UUID tenantId, UUID id) {
    return repo.find(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("DISPUTE_NOT_FOUND", "No such dispute"));
  }

  private void send(Dispute d, java.util.function.Consumer<PaymentProvider> call) {
    PaymentProvider provider = providers.forName(d.provider());
    if (provider == null) {
      throw new ApiException(
          502, "PAYMENT_PROVIDER_UNAVAILABLE", d.provider() + " is not configured here", List.of());
    }
    try {
      call.accept(provider);
    } catch (PaymentProvider.ProviderException e) {
      throw new ApiException(
          502, "PAYMENT_PROVIDER_REFUSED", "The payment provider did not take it", List.of(), e);
    }
  }

  private static Dispute withFunds(Dispute d, BigDecimal fee, String feeCurrency) {
    return new Dispute(
        d.id(),
        d.tenantId(),
        d.paymentId(),
        d.orderId(),
        d.storeId(),
        d.provider(),
        d.providerDisputeRef(),
        d.amount(),
        fee,
        d.currency(),
        d.reason(),
        d.networkReasonCode(),
        d.status(),
        true,
        d.evidenceDueBy(),
        d.openedAt(),
        d.closedAt(),
        d.idempotencyKey(),
        d.createdBy(),
        feeCurrency);
  }

  private static PaymentProvider.DisputeAnswer answer(Evidence e) {
    return new PaymentProvider.DisputeAnswer(
        e.productDescription(),
        e.customerName(),
        e.customerEmail(),
        e.refundPolicy(),
        uncategorized(e));
  }

  /** What has no field of its own at a provider, as one text. */
  private static String uncategorized(Evidence e) {
    StringBuilder out = new StringBuilder();
    line(out, "Receipt", e.receiptReference());
    line(out, "Collected or delivered", e.fulfilmentProof());
    line(out, "What was said to the customer", e.customerCommunication());
    line(out, "Notes", e.notes());
    if (e.refundPolicy() != null && out.length() == 0) line(out, "Refund policy", e.refundPolicy());
    return out.toString();
  }

  private static void line(StringBuilder out, String label, String value) {
    if (value == null) return;
    if (out.length() > 0) out.append('\n');
    out.append(label).append(": ").append(value);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
