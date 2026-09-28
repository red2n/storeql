package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.client.OrderClient;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.dto.Dtos.CreatePaymentIntentRequest;
import com.storeql.payment.provider.PaymentProvider;
import com.storeql.payment.provider.PaymentProviders;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Opening, capturing and reconciling payment intents — the path on which money actually moves.
 *
 * <p>Split from {@link PaymentService}, which records tenders that already happened (cash at a
 * till, store credit, a gift card). This one talks to a provider and waits for it. The two share
 * {@link OrderPaymentGuard} because they answer the same question about the order first.
 */
@ApplicationScoped
public class PaymentIntentService {

  @Inject PaymentIntentRepository repo;
  @Inject PaymentProviders providers;
  @Inject DisputeService disputes;
  @Inject OrderPaymentGuard guard;

  // Optional, not defaultValue = "": MicroProfile Config treats an empty default as no default at
  // all and fails deployment when the key is absent.

  /** Where the provider returns the customer after SCA when the client names no URL of its own. */
  @Inject
  @ConfigProperty(name = "storeql.payment.return-url")
  Optional<String> returnUrlConfig;

  /**
   * Return URLs a client may ask for, as comma-separated prefixes.
   *
   * <p>An allowlist rather than free choice, because this value is handed to the provider and the
   * provider sends the customer there. Accepting whatever the client asks for turns checkout into
   * an open redirect with the payment provider's own domain as the referrer.
   */
  @Inject
  @ConfigProperty(name = "storeql.payment.return-url.allowed")
  Optional<String> allowedReturnUrlsConfig;

  /** Resolved from config at startup; package-private so tests can set them directly. */
  String defaultReturnUrl = "";

  String allowedReturnUrls = "";

  @jakarta.annotation.PostConstruct
  void init() {
    defaultReturnUrl = returnUrlConfig.orElse("");
    allowedReturnUrls = allowedReturnUrlsConfig.orElse("");
  }

  /** The tenant's currency, for an order that names none — only pre-SJ-D2 data (SJ-D53). */
  @Inject com.storeql.service.TenantProfiles profiles;

  /**
   * Opens an intent with the configured provider for an ONLINE order.
   *
   * <p>The row is inserted before the provider is called, not after. If the provider call succeeds
   * but its response is lost — a timeout on the way back — the money may be held and this service
   * must still have a record to reconcile against when the webhook arrives. Inserting afterwards
   * would leave a hold nothing in the system knows about.
   *
   * @param req the order and amount to authorise
   * @param ctx caller identity, for the ownership check
   * @param idempotencyKey replay guard; a repeat returns the original intent untouched
   * @return the intent, including any SCA step the customer must complete
   */
  public PaymentIntent create(
      CreatePaymentIntentRequest req, TenantContext ctx, String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    UUID orderId = Ids.parse(req.orderId());
    OrderPaymentGuard.VerifiedOrder verified =
        guard.verifyOnlineClaim(tenantId, orderId, req.amount(), ctx);
    OrderClient.OrderInfo order = verified.order();

    String returnUrl = resolveReturnUrl(req.returnUrl());
    String currency =
        order.currency() == null || order.currency().isBlank()
            ? profiles.requireCurrency(tenantId)
            : order.currency();

    UUID intentId = Ids.newId();
    Instant now = Instant.now();
    PaymentIntent pending =
        new PaymentIntent(
            intentId,
            tenantId,
            orderId,
            verified.storeId(),
            providers.forTenant(tenantId).name(),
            null,
            req.amount(),
            BigDecimal.ZERO,
            currency,
            PaymentIntent.STATUS_REQUIRES_ACTION,
            null,
            null,
            null,
            null,
            idempotencyKey,
            now,
            now);

    PaymentIntent stored = repo.create(pending);
    if (!stored.id().equals(intentId)) {
      // Idempotent replay: this key already opened an intent. Returning it is the whole point —
      // a retried checkout must not place a second hold on the customer's card.
      return stored;
    }

    PaymentProvider.Authorization auth;
    try {
      auth =
          providers
              .active()
              .authorize(
                  new PaymentProvider.AuthorizeRequest(
                      tenantId,
                      orderId,
                      intentId,
                      req.amount(),
                      currency,
                      returnUrl,
                      idempotencyKey));
    } catch (PaymentProvider.ProviderException e) {
      // The intent stays as a record of the attempt rather than vanishing, so a hold the provider
      // did place despite the error is still reconcilable when its webhook arrives.
      repo.markTerminal(
          tenantId, intentId, PaymentIntent.STATUS_FAILED, "PROVIDER_ERROR", e.getMessage());
      throw new ApiException(
          e.retryable() ? 503 : 502,
          "PAYMENT_PROVIDER_UNAVAILABLE",
          "payment provider could not authorise this payment: " + e.getMessage(),
          List.of(),
          e);
    }

    repo.markAuthorized(
        tenantId, intentId, auth.providerRef(), auth.status(), auth.nextActionUrl());
    return repo.findById(tenantId, intentId);
  }

  /**
   * @param tenantId owning tenant
   * @param intentId intent to read
   * @param ctx caller identity
   * @return the intent
   * @throws ApiException 404 if this tenant has no such intent, or the caller does not own the
   *     order it is against
   */
  public PaymentIntent get(UUID tenantId, UUID intentId, TenantContext ctx) {
    PaymentIntent intent = repo.findById(tenantId, intentId);
    if (intent == null) {
      throw notFound(intentId);
    }
    // Object-level check, matching the tender reads: staff see anything in the tenant, a customer
    // sees only intents against their own order, and a stranger gets 404 rather than 403 so intent
    // ids cannot be probed.
    guard.requireOrderReadAccess(tenantId, intent.orderId(), ctx, () -> notFound(intentId));
    return intent;
  }

  /**
   * Captures an authorised intent: takes the money at the provider, then writes the tender and
   * publishes {@code PaymentCaptured} atomically.
   *
   * @param tenantId owning tenant
   * @param intentId intent to capture
   * @return the tender recording the captured money
   * @throws ApiException 404 if unknown, 409 if the intent is not in a capturable state
   */
  public PaymentTender capture(UUID tenantId, UUID intentId) {
    PaymentIntent intent = repo.findById(tenantId, intentId);
    if (intent == null) {
      throw notFound(intentId);
    }
    if (PaymentIntent.STATUS_CAPTURED.equals(intent.status())) {
      // Already captured. Return the tender it points at rather than treating this as an error:
      // capture is retryable by design and a second call must be a no-op, not a 409.
      return repo.findCapturedTender(tenantId, intentId);
    }
    if (!PaymentIntent.STATUS_AUTHORIZED.equals(intent.status())) {
      throw ApiException.conflict(
          "PAYMENT_INTENT_NOT_CAPTURABLE",
          "intent " + intentId + " is " + intent.status() + ", not AUTHORIZED");
    }

    PaymentProvider provider = providers.forName(intent.provider());
    if (provider == null) {
      throw new ApiException(
          502,
          "PAYMENT_PROVIDER_UNAVAILABLE",
          "intent "
              + intentId
              + " was opened with provider "
              + intent.provider()
              + ", which is not deployed",
          List.of(),
          null);
    }

    PaymentProvider.Capture captured;
    try {
      captured =
          provider.capture(
              intent.providerRef(), intent.amount(), Ids.derived(intentId, "capture").toString());
    } catch (PaymentProvider.ProviderException e) {
      throw new ApiException(
          e.retryable() ? 503 : 502,
          "PAYMENT_PROVIDER_UNAVAILABLE",
          "payment provider could not capture this payment: " + e.getMessage(),
          List.of(),
          e);
    }
    return writeCapture(intent, captured.capturedAmount(), captured.reference());
  }

  /**
   * Applies a verified provider webhook.
   *
   * <p>Everything here is deliberately forgiving of unknowns and unforgiving of replays: a provider
   * redelivers on any doubt, so the same event must reach the same end state without capturing
   * twice, and an event about an intent this service has never heard of is not an error worth
   * telling the provider about — it would only make the provider retry forever.
   *
   * @param providerName provider named in the webhook path
   * @param rawBody the exact bytes received, for signature verification
   * @param header looks a request header up by name; the provider names the one it signs with
   * @throws ApiException 404 if no such provider is deployed, 400 if the signature does not verify
   */
  public void handleWebhook(
      String providerName, byte[] rawBody, java.util.function.UnaryOperator<String> header) {
    PaymentProvider provider = providers.forName(providerName);
    if (provider == null) {
      throw ApiException.notFound("PAYMENT_PROVIDER_UNKNOWN", "no such payment provider");
    }

    PaymentProvider.WebhookEvent event;
    try {
      event = provider.verifyWebhook(rawBody, header.apply(provider.signatureHeaderName()));
    } catch (PaymentProvider.ProviderException e) {
      // 400, never 500: a bad signature is a rejected request, and this endpoint is public. The
      // response stays deliberately vague — telling an attacker which part of the check failed is
      // free information — but the cause is preserved so the logs say what actually happened.
      throw new ApiException(
          400, "PAYMENT_WEBHOOK_INVALID", "webhook signature did not verify", List.of(), e);
    }

    // Seen before? Skip. This is an optimisation, not the correctness mechanism — see the note on
    // recording it below.
    if (repo.hasSeenWebhook(provider.name(), event.providerEventId())) {
      return; // Already applied. Redelivery is normal, not an error.
    }

    // A dispute (11.9) is about a payment that finished long ago, so it is judged before the
    // "already finished" short-cut below. Applying it is idempotent — the provider's dispute
    // reference is the key and every move is guarded on its state — and, as for every event, it is
    // recorded as seen only after it has been applied.
    if (event.dispute() != null) {
      PaymentIntent disputed =
          event.providerRef() == null
              ? null
              : repo.findByProviderRefAcrossTenants(provider.name(), event.providerRef());
      disputes.fromProvider(provider.name(), disputed, event.dispute());
      repo.markWebhookSeenIfNew(provider.name(), event.providerEventId(), event.type());
      return;
    }

    PaymentIntent intent =
        repo.findByProviderRefAcrossTenants(provider.name(), event.providerRef());
    if (intent == null || intent.isTerminal()) {
      // Nothing to apply, but still record it: an event about an intent this service does not know
      // about, or one already finished, would otherwise be reprocessed on every redelivery forever.
      repo.markWebhookSeenIfNew(provider.name(), event.providerEventId(), event.type());
      return;
    }

    switch (event.status()) {
      case PaymentIntent.STATUS_CAPTURED -> {
        BigDecimal amount =
            event.capturedAmount() == null ? intent.amount() : event.capturedAmount();
        writeCapture(intent, amount, event.providerRef());
      }
      case PaymentIntent.STATUS_AUTHORIZED ->
          repo.markAuthorized(
              intent.tenantId(),
              intent.id(),
              intent.providerRef(),
              PaymentIntent.STATUS_AUTHORIZED,
              null);
      case PaymentIntent.STATUS_FAILED, PaymentIntent.STATUS_CANCELLED ->
          repo.markTerminal(
              intent.tenantId(),
              intent.id(),
              event.status(),
              event.failureCode(),
              event.failureMessage());
      default -> {
        // A status this service does not model. Nothing to apply, but it is still recorded below so
        // the provider stops redelivering it.
      }
    }

    // Recorded LAST, and that ordering is the whole point.
    //
    // It used to be written first, in its own transaction, before the effect was applied. A failure
    // in writeCapture then left the dedupe row committed and the capture never made: the provider's
    // redelivery — the one mechanism designed to recover exactly this — was swallowed as
    // "already applied", and the money was captured at Stripe and recorded nowhere. Silently, and
    // permanently.
    //
    // Recording it afterwards is safe because every branch above is idempotent, which golden rule
    // #7
    // requires of consumers anyway: captureGuarded locks the intent and returns the existing tender
    // rather than writing a second, markTerminal is guarded on its source state, and markAuthorized
    // is an assignment. So the failure mode this ordering creates — a crash between the effect and
    // this line, or two concurrent deliveries both passing the check above — is a replay that
    // reaches the same state. The failure mode the old ordering created was lost money.
    repo.markWebhookSeenIfNew(provider.name(), event.providerEventId(), event.type());
  }

  /**
   * Writes the tender, links the intent to it and emits PaymentCaptured, all in one transaction.
   */
  private PaymentTender writeCapture(PaymentIntent intent, BigDecimal amount, String reference) {
    UUID tenderId = Ids.newId();
    PaymentTender tender =
        new PaymentTender(
            tenderId,
            intent.tenantId(),
            intent.orderId(),
            amount,
            PaymentTender.METHOD_CARD,
            reference,
            // Keyed on the intent, so a webhook and a manual capture racing each other cannot both
            // write a tender even if they get past the row lock in different transactions.
            Ids.derived(intent.id(), "tender").toString(),
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            intent.storeId());
    return repo.captureGuarded(
        intent.tenantId(),
        intent.id(),
        tender,
        Events.paymentCaptured(
            intent.tenantId(),
            tenderId,
            intent.orderId(),
            amount,
            PaymentTender.METHOD_CARD,
            intent.storeId()));
  }

  /**
   * Validates a client-supplied return URL against the configured allowlist.
   *
   * @param requested what the client asked for, or null
   * @return the URL to hand the provider
   * @throws ApiException 400 if the client asked for a URL that is not allowed
   */
  private String resolveReturnUrl(String requested) {
    if (requested == null || requested.isBlank()) {
      return defaultReturnUrl;
    }
    boolean allowed =
        Arrays.stream(allowedReturnUrls.split(","))
            .map(String::trim)
            .filter(prefix -> !prefix.isEmpty())
            .anyMatch(requested::startsWith);
    if (!allowed) {
      throw ApiException.badRequest(
          "PAYMENT_RETURN_URL_NOT_ALLOWED",
          "returnUrl is not on the configured allowlist for this deployment");
    }
    return requested;
  }

  private static ApiException notFound(UUID intentId) {
    return ApiException.notFound(
        "PAYMENT_INTENT_NOT_FOUND", "payment intent " + intentId + " not found");
  }
}
