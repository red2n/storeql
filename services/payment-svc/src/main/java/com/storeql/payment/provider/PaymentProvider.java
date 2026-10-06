package com.storeql.payment.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment service provider, behind one interface.
 *
 * <p>Everything the rest of payment-svc knows about taking money lives here. {@code PaymentService}
 * never names Stripe or Razorpay, so adding the second provider means adding a class and a config
 * value — not editing the checkout path. PRD §11 settles the v1 set as Razorpay (India) + Stripe;
 * {@link ManualPaymentProvider} is the third, and the default, because a stack with no PSP
 * credentials must still start and still sell.
 *
 * <p><b>Nothing here takes a card number.</b> Every implementation hands the customer to the
 * provider's own hosted flow and learns the outcome from a redirect and a webhook. That is what
 * keeps card data out of this service entirely, and it is the difference between a PCI-DSS scope
 * that is a paragraph and one that is a project.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans, selected by {@link
 * PaymentProviders#forName} on the configured name.
 */
public interface PaymentProvider {

  /**
   * @return the {@code Domain.PaymentIntent} provider constant this implementation serves, e.g.
   *     {@code "STRIPE"}
   */
  String name();

  /**
   * The header this provider signs its webhooks with — {@code Stripe-Signature} for Stripe, {@code
   * X-Razorpay-Signature} for Razorpay. Named by the provider rather than fixed by us, because the
   * provider chooses it and reading the wrong one means every delivery is rejected.
   *
   * @return the header name to pass to {@link #verifyWebhook}
   */
  String signatureHeaderName();

  /**
   * Asks the provider to authorise {@code amount} for an order.
   *
   * <p>Called once per intent, inside the request that creates it. Implementations must pass {@code
   * idempotencyKey} to the provider's own idempotency mechanism where it has one, so a retried
   * checkout cannot place two holds on the customer's card.
   *
   * @param request what to authorise, and for whom
   * @return the provider's reference plus the state it left the intent in
   * @throws ProviderException if the provider could not be reached or refused the request outright
   */
  Authorization authorize(AuthorizeRequest request);

  /**
   * Takes money previously authorised. Separate from {@link #authorize} because that split is the
   * point: an online order is authorised at checkout and captured when it is actually fulfilled, so
   * a cancelled order releases a hold rather than owing a refund.
   *
   * @param providerRef the provider's id for the intent, from {@link Authorization#providerRef()}
   * @param amount the amount to capture; must not exceed what was authorised
   * @param idempotencyKey replay guard for the capture call itself
   * @return the captured state
   * @throws ProviderException if the provider could not be reached or refused the capture
   */
  Capture capture(String providerRef, BigDecimal amount, String idempotencyKey);

  /**
   * Releases an authorisation without taking the money.
   *
   * @param providerRef the provider's id for the intent
   * @param idempotencyKey replay guard
   * @throws ProviderException if the provider could not be reached
   */
  void cancel(String providerRef, String idempotencyKey);

  /**
   * Verifies that a webhook body genuinely came from the provider, and parses it.
   *
   * <p>This is the security boundary of the whole feature. The webhook endpoint is necessarily
   * public — the provider has to reach it — so anything that skips or weakens this check lets
   * anyone on the internet mark any order paid by POSTing a plausible body. Implementations must
   * verify the provider's signature over the <em>raw</em> bytes, before any parsing.
   *
   * @param rawBody the exact bytes received, unparsed and unmodified
   * @param signatureHeader the provider's signature header value, or null if absent
   * @return the parsed event
   * @throws ProviderException if the signature is absent, malformed, or does not verify
   */
  WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader);

  /**
   * @param tenantId owning tenant
   * @param orderId the order being paid for
   * @param intentId this service's own intent id, sent to the provider as metadata so a webhook can
   *     be traced back even if the provider reference is lost
   * @param amount amount to authorise
   * @param currency ISO-4217 code, resolved from the tenant
   * @param returnUrl where the provider should send the customer after SCA
   * @param idempotencyKey replay guard
   */
  record AuthorizeRequest(
      UUID tenantId,
      UUID orderId,
      UUID intentId,
      BigDecimal amount,
      String currency,
      String returnUrl,
      String idempotencyKey) {}

  /**
   * @param providerRef the provider's id for the intent
   * @param status one of the {@code Domain.PaymentIntent} status constants
   * @param nextActionUrl where to send the customer for SCA, or null if none is required
   */
  record Authorization(String providerRef, String status, String nextActionUrl) {}

  /**
   * @param providerRef the provider's id for the intent
   * @param capturedAmount how much was actually taken
   * @param reference the provider's reference for the captured payment, recorded on the tender
   */
  record Capture(String providerRef, BigDecimal capturedAmount, String reference) {}

  /**
   * A verified provider webhook.
   *
   * @param providerEventId the provider's own event id, used to dedupe redelivery
   * @param type provider-specific event type, for logging and for the audit trail
   * @param providerRef the intent this event concerns
   * @param status the {@code Domain.PaymentIntent} status this event implies
   * @param capturedAmount amount captured, when the event reports one; null otherwise
   * @param failureCode provider failure code, when the event reports a failure
   * @param failureMessage human-readable failure reason, when the event reports one
   * @param ours the intent of ours the event names in the metadata this service sent with the
   *     request, or null when it names none: absent, malformed, not a UUIDv7, or an event about
   *     something other than a payment intent
   */
  record WebhookEvent(
      String providerEventId,
      String type,
      String providerRef,
      String status,
      BigDecimal capturedAmount,
      String failureCode,
      String failureMessage,
      DisputeNotice dispute,
      OurIntent ours) {

    /** An event about a payment intent, as every event was before disputes (11.9). */
    public WebhookEvent(
        String providerEventId,
        String type,
        String providerRef,
        String status,
        BigDecimal capturedAmount,
        String failureCode,
        String failureMessage) {
      this(
          providerEventId,
          type,
          providerRef,
          status,
          capturedAmount,
          failureCode,
          failureMessage,
          null,
          null);
    }

    /** An event that names no intent of ours: a dispute, or one the provider did not tag. */
    public WebhookEvent(
        String providerEventId,
        String type,
        String providerRef,
        String status,
        BigDecimal capturedAmount,
        String failureCode,
        String failureMessage,
        DisputeNotice dispute) {
      this(
          providerEventId,
          type,
          providerRef,
          status,
          capturedAmount,
          failureCode,
          failureMessage,
          dispute,
          null);
    }
  }

  /**
   * The intent of ours a provider event names: the ids this service sent as metadata when it asked
   * the provider to authorise, which come back with the provider's own object. Both ids travel
   * together, because an intent is keyed by its business and its id: the pair is looked up in the
   * business it names, so an id that belongs to another business is not found there. (The other way
   * to find an intent, by the provider's reference, is looked up across businesses.)
   *
   * @param tenantId the business the intent was opened for
   * @param intentId this service's id for the intent
   */
  record OurIntent(UUID tenantId, UUID intentId) {}

  /**
   * What a provider says about a dispute (11.9), in the provider-neutral shape. {@link
   * WebhookEvent#providerRef()} names the payment intent the disputed charge belongs to.
   *
   * @param disputeRef the provider's id for the dispute
   * @param phase OPENED, FUNDS_WITHDRAWN, FUNDS_REINSTATED, UPDATED or CLOSED
   * @param outcome WON or LOST when {@code phase} is CLOSED, else null
   * @param amount what is disputed, in major units
   * @param fee what the provider charged for the dispute, in major units of {@code feeCurrency};
   *     zero when it has charged none yet, null when what it said cannot be read without a guess
   * @param currency the disputed charge's currency, which {@code amount} is in
   * @param reason one of {@code Disputes.REASONS}
   * @param networkReasonCode the card scheme's own code, when the provider passes it on
   * @param feeCurrency the currency the provider charged the fee in — the account's settlement
   *     currency, which need not be the charge's; null exactly when {@code fee} is
   */
  record DisputeNotice(
      String disputeRef,
      String phase,
      String outcome,
      BigDecimal amount,
      BigDecimal fee,
      String currency,
      String reason,
      String networkReasonCode,
      java.time.Instant evidenceDueBy,
      String feeCurrency) {

    /** A notice whose fee is in the disputed charge's own currency. */
    public DisputeNotice(
        String disputeRef,
        String phase,
        String outcome,
        BigDecimal amount,
        BigDecimal fee,
        String currency,
        String reason,
        String networkReasonCode,
        java.time.Instant evidenceDueBy) {
      this(
          disputeRef,
          phase,
          outcome,
          amount,
          fee,
          currency,
          reason,
          networkReasonCode,
          evidenceDueBy,
          fee == null ? null : currency);
    }

    public static final String PHASE_OPENED = "OPENED";
    public static final String PHASE_FUNDS_WITHDRAWN = "FUNDS_WITHDRAWN";
    public static final String PHASE_FUNDS_REINSTATED = "FUNDS_REINSTATED";
    public static final String PHASE_UPDATED = "UPDATED";
    public static final String PHASE_CLOSED = "CLOSED";
  }

  /**
   * What the business answers a dispute with, as far as a provider takes text.
   *
   * @param uncategorized everything that has no field of its own: the receipt reference, the proof
   *     of collection or delivery, what was said to the customer, the business's own notes
   */
  record DisputeAnswer(
      String productDescription,
      String customerName,
      String customerEmail,
      String refundPolicy,
      String uncategorized) {}

  /**
   * Sends the business's evidence to the provider and submits it: a scheme takes evidence once. The
   * default is for a provider that has no disputes of its own (MANUAL): nothing to send — the
   * business answers its acquirer directly, and this service keeps what it said.
   *
   * @throws ProviderException if the provider could not be reached or refused it
   */
  default void submitDisputeEvidence(String disputeRef, DisputeAnswer answer) {}

  /**
   * Tells the provider the business will not contest a dispute. The default is for a provider that
   * has no disputes of its own.
   *
   * @throws ProviderException if the provider could not be reached or refused it
   */
  default void acceptDispute(String disputeRef) {}

  /**
   * Refuses, before anything is recorded or asked, an amount this provider cannot charge exactly in
   * its currency — a pricing fact about the provider, not an outage. The default is for a provider
   * that charges whatever the currency's own minor units say (MANUAL): nothing to refuse.
   *
   * @throws AmountNotChargeable naming the nearest amounts it can charge
   */
  default void requireChargeable(BigDecimal amount, String currency) {}

  /** A provider call failed. Mapped to 502/503 by the service, never to a 500. */
  class ProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    /**
     * @param message what went wrong
     * @param retryable whether trying again could plausibly succeed (a timeout, not a decline)
     * @param cause underlying failure, or null
     */
    public ProviderException(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }

    /**
     * @return whether retrying could plausibly succeed
     */
    public boolean retryable() {
      return retryable;
    }
  }

  /**
   * An amount the provider cannot charge exactly in its currency (Stripe charges a dinar in tens of
   * fils, a krona whole): the order's total is a price the provider cannot take, which asking again
   * never changes. The service answers it as the customer's 422, never as an outage, and names what
   * the provider could charge either side of it.
   */
  final class AmountNotChargeable extends ProviderException {
    private static final long serialVersionUID = 1L;

    private final BigDecimal amount;
    private final String currency;
    private final BigDecimal below;
    private final BigDecimal above;

    /**
     * @param below the nearest chargeable amount under it, or null when there is none above zero
     * @param above the nearest chargeable amount over it
     */
    public AmountNotChargeable(
        String message, BigDecimal amount, String currency, BigDecimal below, BigDecimal above) {
      super(message, false, null);
      this.amount = amount;
      this.currency = currency;
      this.below = below;
      this.above = above;
    }

    public BigDecimal amount() {
      return amount;
    }

    public String currency() {
      return currency;
    }

    /** The nearest amount under it the provider can charge, or null when none is above zero. */
    public BigDecimal below() {
      return below;
    }

    /** The nearest amount over it the provider can charge. */
    public BigDecimal above() {
      return above;
    }
  }
}
