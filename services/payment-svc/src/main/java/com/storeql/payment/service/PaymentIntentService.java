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
import java.util.logging.Level;
import java.util.logging.Logger;
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

  private static final Logger LOG = Logger.getLogger(PaymentIntentService.class.getName());

  /**
   * The {@code failure_code} of an attempt whose authorisation ended in a provider error: the
   * provider was down, refused the request or was not configured.
   */
  static final String FAILURE_PROVIDER_ERROR = "PROVIDER_ERROR";

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
   * must still have a record of the attempt to reconcile it against. Inserting afterwards would
   * leave a hold nothing in the system knows about. That record has no provider reference yet (the
   * response that carried it is the one that was lost), so a webhook is matched to it by the intent
   * id this service sent the provider as metadata, which also teaches it the reference ({@link
   * #handleWebhook}). An attempt that failed here is not revived by it: a hold the provider placed
   * all the same is said aloud, for a person to release at the provider.
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
    // One provider for the record and the call: a sandbox's is MANUAL (22.8), so its intent is
    // never authorised by the configured processor while recorded as MANUAL's.
    PaymentProvider provider = providers.forTenant(tenantId);
    // An amount this provider cannot charge exactly is the customer's to know, before anything is
    // recorded or asked: a price, not an outage.
    try {
      provider.requireChargeable(req.amount(), currency);
    } catch (PaymentProvider.AmountNotChargeable e) {
      throw notChargeable(e);
    }
    PaymentIntent pending =
        new PaymentIntent(
            intentId,
            tenantId,
            orderId,
            verified.storeId(),
            provider.name(),
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
          provider.authorize(
              new PaymentProvider.AuthorizeRequest(
                  tenantId, orderId, intentId, req.amount(), currency, returnUrl, idempotencyKey));
    } catch (PaymentProvider.AmountNotChargeable e) {
      // Refused by the driver before it asked anything (checked above, so only a driver that
      // learns it later says so here): nothing was held, and the intent says why it failed.
      repo.markTerminal(
          tenantId, intentId, PaymentIntent.STATUS_FAILED, "AMOUNT_NOT_CHARGEABLE", e.getMessage());
      throw notChargeable(e);
    } catch (PaymentProvider.ProviderException e) {
      // The intent stays as a record of the attempt rather than vanishing, so a hold the provider
      // did place despite the error can still be found: its webhooks name this intent's id (sent
      // as metadata), which is how handleWebhook matches them although the exception carries no
      // provider reference and none is stored here (provider_ref stays null until an event names
      // it). The attempt stays FAILED; a hold the provider reports for it is said aloud there.
      repo.markTerminal(
          tenantId, intentId, PaymentIntent.STATUS_FAILED, FAILURE_PROVIDER_ERROR, e.getMessage());
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
   * <p>A provider redelivers on any doubt, so the same event must reach the same end state without
   * capturing twice.
   *
   * <p>The intent is found by the provider's reference, else by the ids this service sent as
   * metadata ({@link PaymentProvider.OurIntent}) when the intent has not learnt its reference yet:
   * the provider's answer to the authorisation had not been recorded, or never came. The event is
   * then applied to it and the intent keeps the reference, so the events after it and a capture
   * find the provider's object. The pair is looked up in the business it names and in no other, and
   * an event whose reference and metadata disagree, or whose intent holds another reference or
   * belongs to another provider, is applied to nothing.
   *
   * <p>Three answers say what the provider should do next. An event that names an intent of ours
   * that the business it names does not have (yet) is refused with {@code 404
   * PAYMENT_WEBHOOK_INTENT_UNKNOWN} and is not recorded as seen, so the provider delivers it again,
   * as it does for every failure. An event that names none of ours (no metadata, or an id that is
   * malformed or not a UUIDv7), for a reference nobody holds, is not ours to act on: it is
   * acknowledged and recorded, since a retry would not make it so — and logged as a warning when it
   * is of a kind that would have moved an intent. And an event that contradicts itself is
   * acknowledged, said aloud and recorded, since delivering it again changes nothing.
   *
   * @param providerName provider named in the webhook path
   * @param rawBody the exact bytes received, for signature verification
   * @param header looks a request header up by name; the provider names the one it signs with
   * @throws ApiException 404 {@code PAYMENT_PROVIDER_UNKNOWN} if no such provider is deployed; 400
   *     {@code PAYMENT_WEBHOOK_INVALID} if the signature does not verify; 404 {@code
   *     PAYMENT_WEBHOOK_INTENT_UNKNOWN} for an intent of ours that the business the event names
   *     does not have
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

    Match match = match(provider, event);
    if (match.contradiction() != null) {
      // What the event says of itself cannot be true of any one intent, and delivering it again
      // would say the same: nothing is applied, an operator is told, and the provider is let go.
      LOG.log(
          Level.WARNING,
          "payment webhook {1} ({2}) from {0} was applied to nothing: {3}; it is recorded as seen",
          new Object[] {
            provider.name(), event.providerEventId(), event.type(), match.contradiction()
          });
      repo.markWebhookSeenIfNew(provider.name(), event.providerEventId(), event.type());
      return;
    }
    PaymentIntent intent = match.intent();
    if (intent == null && changesAnIntent(event.status())) {
      // No intent holds this reference and the event names none of ours: something this service
      // never opened, or an event the provider did not tag. Nothing can be applied, and it is not
      // an error to tell the provider about, but an event that would have moved an intent is said
      // where an operator will see it rather than dropped in silence. (An event of a kind no
      // intent acts on is not: it would change nothing if it matched. The Stripe driver gives an
      // event about another object than a PaymentIntent, a charge say, such a status, so a
      // charge.* event does not come here.)
      LOG.log(
          Level.WARNING,
          "payment webhook {1} ({2}) from {0} names provider reference {3}, which no payment"
              + " intent holds and names no intent of ours: nothing was applied and the event is"
              + " recorded as seen",
          new Object[] {
            provider.name(), event.providerEventId(), event.type(), event.providerRef()
          });
    }
    if (intent != null && holdsMoneyForAFailedAttempt(intent, event)) {
      // The attempt failed here (its authorisation ended in a provider error), and yet the
      // provider reports money held or taken under it: found by the id it was sent, and left as
      // the failed attempt it is, since this path does not capture money for a payment the shopper
      // was told could not be authorised, nor release it at the provider: somebody must release it
      // where it is held.
      LOG.log(
          Level.WARNING,
          "payment webhook {1} ({2}) from {0} says the provider holds or took money under {3} for"
              + " payment intent {4}, which this service holds as a failed attempt: nothing was"
              + " applied; find the payment at the provider and release or reconcile it by hand",
          new Object[] {
            provider.name(), event.providerEventId(), event.type(), event.providerRef(), intent.id()
          });
    }
    if (intent == null || intent.isTerminal()) {
      // Nothing to apply, but still record it: an event that matched no intent (it names none of
      // ours and no intent holds its reference), or one about an intent already finished, would
      // otherwise be reprocessed on every redelivery forever.
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

  /** Whether an event of this status is one {@code handleWebhook} applies to an intent. */
  private static boolean changesAnIntent(String status) {
    return PaymentIntent.STATUS_CAPTURED.equals(status)
        || PaymentIntent.STATUS_AUTHORIZED.equals(status)
        || PaymentIntent.STATUS_FAILED.equals(status)
        || PaymentIntent.STATUS_CANCELLED.equals(status);
  }

  /**
   * Whether an event reports money held or taken for an attempt whose authorisation failed here
   * ({@link #FAILURE_PROVIDER_ERROR}): the provider says money is held that the record says was
   * never taken, and {@code handleWebhook} does not release it at the provider.
   */
  private static boolean holdsMoneyForAFailedAttempt(
      PaymentIntent intent, PaymentProvider.WebhookEvent event) {
    return PaymentIntent.STATUS_FAILED.equals(intent.status())
        && FAILURE_PROVIDER_ERROR.equals(intent.failureCode())
        && (PaymentIntent.STATUS_AUTHORIZED.equals(event.status())
            || PaymentIntent.STATUS_CAPTURED.equals(event.status()));
  }

  /**
   * The intent a webhook is about, and a reason to apply it to none.
   *
   * @param intent the intent to apply the event to; null when the event is not about one of ours
   * @param contradiction why the event is applied to nothing though it names something; null
   *     otherwise
   */
  private record Match(PaymentIntent intent, String contradiction) {
    static Match of(PaymentIntent intent) {
      return new Match(intent, null);
    }

    static Match contradicting(String why) {
      return new Match(null, why);
    }
  }

  /**
   * Finds the intent a verified event is about: by the provider's reference, else by the intent id
   * and business the event carries as metadata.
   *
   * <p>The reference is looked up across businesses. An intent that holds it is the one, unless the
   * event's metadata names another, which is a contradiction. When none holds it, the metadata is
   * read: the intent is looked up with its id in the business the event names, so an id that
   * belongs to another business is not found (the 404 below). The row found must be the same
   * provider's and either hold no reference yet (it is given this event's) or hold this event's;
   * any other is a contradiction, applied to nothing.
   *
   * @throws ApiException 404 {@code PAYMENT_WEBHOOK_INTENT_UNKNOWN} when the event names an intent
   *     of ours that the business it names does not have: it is not recorded, and the provider is
   *     told to deliver it again
   */
  private Match match(PaymentProvider provider, PaymentProvider.WebhookEvent event) {
    String ref = event.providerRef();
    PaymentProvider.OurIntent ours = event.ours();
    PaymentIntent byRef = repo.findByProviderRefAcrossTenants(provider.name(), ref);
    if (byRef != null) {
      if (ours == null
          || (ours.intentId().equals(byRef.id()) && ours.tenantId().equals(byRef.tenantId()))) {
        return Match.of(byRef);
      }
      return Match.contradicting(
          "its reference "
              + ref
              + " belongs to payment intent "
              + byRef.id()
              + " but its metadata names intent "
              + ours.intentId()
              + " of business "
              + ours.tenantId());
    }
    if (ours == null || ref == null) {
      return Match.of(null);
    }
    PaymentIntent named = repo.findById(ours.tenantId(), ours.intentId());
    if (named == null || !ours.tenantId().equals(named.tenantId())) {
      LOG.log(
          Level.WARNING,
          "payment webhook {1} ({2}) from {0} names payment intent {3}, which this service does not"
              + " know: refused, and not recorded, so the provider delivers it again",
          new Object[] {provider.name(), event.providerEventId(), event.type(), ours.intentId()});
      throw ApiException.notFound(
          "PAYMENT_WEBHOOK_INTENT_UNKNOWN",
          "the event names a payment intent that is not known here; deliver it again");
    }
    if (!provider.name().equals(named.provider())) {
      return Match.contradicting(
          "its metadata names payment intent "
              + named.id()
              + ", which was opened with provider "
              + named.provider());
    }
    PaymentIntent holding =
        named.providerRef() == null
            ? repo.adoptProviderRef(named.tenantId(), named.id(), ref)
            : named;
    if (holding == null || !ref.equals(holding.providerRef())) {
      return Match.contradicting(
          "its metadata names payment intent "
              + named.id()
              + ", which holds provider reference "
              + (holding == null ? null : holding.providerRef())
              + ", not "
              + ref);
    }
    return Match.of(holding);
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

  /**
   * The refusal for an amount the provider cannot charge exactly: 422 {@code
   * PAYMENT_AMOUNT_NOT_CHARGEABLE}, naming the nearest amounts it can charge either side (the one
   * below left out when none is above zero), as {@code
   * currency=KWD;chargeableBelow=1.120;chargeableAbove=1.130}.
   */
  private static ApiException notChargeable(PaymentProvider.AmountNotChargeable e) {
    String below = e.below() == null ? null : e.below().toPlainString();
    String above = e.above().toPlainString();
    return new ApiException(
        422,
        "PAYMENT_AMOUNT_NOT_CHARGEABLE",
        "The card payment provider cannot charge exactly this amount in "
            + e.currency()
            + (below == null
                ? "; the nearest it can charge is "
                : "; the nearest it can charge are ")
            + (below == null ? "" : below + " and ")
            + above,
        List.of(
            "currency="
                + e.currency()
                + (below == null ? "" : ";chargeableBelow=" + below)
                + ";chargeableAbove="
                + above),
        e);
  }

  private static ApiException notFound(UUID intentId) {
    return ApiException.notFound(
        "PAYMENT_INTENT_NOT_FOUND", "payment intent " + intentId + " not found");
  }
}
