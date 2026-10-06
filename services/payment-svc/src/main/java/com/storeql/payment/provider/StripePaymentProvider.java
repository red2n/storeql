package com.storeql.payment.provider;

import com.storeql.ids.Ids;
import com.storeql.payment.config.Jsons;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.service.Fx;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stripe, over its REST API.
 *
 * <p>Uses PaymentIntents with {@code capture_method=manual}, which is what gives the auth/capture
 * split: checkout authorises, fulfilment captures, and a cancelled order releases a hold instead of
 * owing a refund.
 *
 * <p>No card details pass through this service. The intent is created server-side and the customer
 * completes it against Stripe directly, so this code never sees a PAN and payment-svc stays out of
 * PCI-DSS scope beyond SAQ-A.
 */
@ApplicationScoped
public class StripePaymentProvider implements PaymentProvider {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(StripePaymentProvider.class.getName());

  /** Stripe rejects a signature older than this; so do we, to bound replay. */
  private static final long TOLERANCE_SECONDS = 300;

  @Inject
  @ConfigProperty(name = "storeql.payment.stripe.api-base", defaultValue = "https://api.stripe.com")
  String apiBase;

  // Optional, not defaultValue = "": MicroProfile Config treats an empty default as no default at
  // all and fails deployment when the key is absent, which is every environment that has not
  // configured Stripe — including the tests.
  @Inject
  @ConfigProperty(name = "storeql.payment.stripe.secret-key")
  Optional<String> secretKeyConfig;

  @Inject
  @ConfigProperty(name = "storeql.payment.stripe.webhook-secret")
  Optional<String> webhookSecretConfig;

  /** Resolved from config at startup; package-private so tests can set them directly. */
  String secretKey = "";

  String webhookSecret = "";

  private WebClient webClient;

  @PostConstruct
  void init() {
    secretKey = secretKeyConfig.orElse("");
    webhookSecret = webhookSecretConfig.orElse("");
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15))
            .build();
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code STRIPE}
   */
  @Override
  public String name() {
    return PaymentIntent.PROVIDER_STRIPE;
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code Stripe-Signature}
   */
  @Override
  public String signatureHeaderName() {
    return "Stripe-Signature";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Creates the intent with {@code capture_method=manual}, so the funds are held now and taken
   * at fulfilment. Our own ids travel in Stripe metadata, so a webhook can be traced back even if
   * the provider reference is lost on our side.
   *
   * @throws ProviderException non-retryable when Stripe rejects the request or no secret key is
   *     configured; retryable when Stripe is unreachable or returns a 5xx
   */
  /**
   * {@inheritDoc}
   *
   * <p>Stripe charges each currency in its own units, with its documented exceptions ({@link
   * #minorUnits}): an amount it cannot charge exactly — a dinar's last fils not 0, a fraction of a
   * krona — is said here, before an intent is recorded or Stripe is asked.
   */
  @Override
  public void requireChargeable(BigDecimal amount, String currency) {
    minorUnits(amount, currency);
  }

  @Override
  public Authorization authorize(AuthorizeRequest request) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("amount", String.valueOf(minorUnits(request.amount(), request.currency())));
    form.put("currency", request.currency().toLowerCase(Locale.ROOT));
    // The whole point: hold the funds now, take them at fulfilment.
    form.put("capture_method", "manual");
    form.put("automatic_payment_methods[enabled]", "true");
    // Carried back on every webhook, so an event can be traced to our own record even if the
    // provider reference is somehow lost on our side.
    form.put("metadata[intentId]", request.intentId().toString());
    form.put("metadata[orderId]", request.orderId().toString());
    form.put("metadata[tenantId]", request.tenantId().toString());
    if (request.returnUrl() != null && !request.returnUrl().isBlank()) {
      form.put("return_url", request.returnUrl());
    }

    JsonObject body = post("/v1/payment_intents", form, request.idempotencyKey());
    return new Authorization(
        body.getString("id"), statusFrom(body.getString("status", "")), nextActionUrl(body));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Takes the funds already held by {@link #authorize}. The {@code amount} argument is not sent
   * to Stripe — the full authorised amount is captured, and the figure returned is what Stripe
   * reports as actually received.
   *
   * @throws ProviderException non-retryable when Stripe rejects the capture; retryable when Stripe
   *     is unreachable or returns a 5xx
   */
  @Override
  public Capture capture(String providerRef, BigDecimal amount, String idempotencyKey) {
    JsonObject body =
        post("/v1/payment_intents/" + providerRef + "/capture", Map.of(), idempotencyKey);
    // No currency is ever assumed: an answer that names none is read as the full hold taken,
    // which is what a manual capture with no amount_to_capture takes.
    String currency = body.getString("currency", null);
    if (currency == null) {
      return new Capture(providerRef, amount, providerRef);
    }
    long captured =
        body.containsKey("amount_received")
            ? body.getJsonNumber("amount_received").longValue()
            : 0L;
    return new Capture(providerRef, majorUnits(captured, currency), providerRef);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Releases the hold rather than refunding: nothing was taken, so nothing is owed back.
   *
   * @throws ProviderException non-retryable when Stripe rejects the cancellation; retryable when
   *     Stripe is unreachable or returns a 5xx
   */
  @Override
  public void cancel(String providerRef, String idempotencyKey) {
    post("/v1/payment_intents/" + providerRef + "/cancel", Map.of(), idempotencyKey);
  }

  /**
   * Verifies Stripe's {@code Stripe-Signature} header and parses the event.
   *
   * <p>Stripe signs {@code "<timestamp>.<raw body>"} with HMAC-SHA256 under the endpoint's webhook
   * secret. Three things matter and each is a way this goes wrong:
   *
   * <ul>
   *   <li>the signature covers the <em>raw</em> bytes, so the body must not be parsed and
   *       re-serialised first — key order and whitespace would change and nothing would ever
   *       verify;
   *   <li>the comparison must be constant-time, or the check leaks the expected signature a byte at
   *       a time to anyone willing to measure;
   *   <li>the timestamp must be inside a tolerance, or a captured delivery can be replayed forever.
   * </ul>
   *
   * @throws ProviderException always non-retryable — a body that does not verify will not verify on
   *     a second attempt — when no webhook secret is configured, the header is missing or
   *     malformed, the timestamp is outside {@value #TOLERANCE_SECONDS} seconds, or the signature
   *     does not match
   */
  @Override
  public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      // Refusing is the only safe answer: with no secret there is nothing to verify against, and
      // accepting would mean anyone could mark any order paid.
      throw new ProviderException("no Stripe webhook secret is configured", false, null);
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new ProviderException("missing Stripe-Signature header", false, null);
    }

    String timestamp = null;
    String provided = null;
    for (String part : signatureHeader.split(",")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length != 2) {
        continue;
      }
      if ("t".equals(kv[0])) {
        timestamp = kv[1];
      } else if ("v1".equals(kv[0])) {
        provided = kv[1];
      }
    }
    if (timestamp == null || provided == null) {
      throw new ProviderException("malformed Stripe-Signature header", false, null);
    }

    long age;
    try {
      age = Math.abs(java.time.Instant.now().getEpochSecond() - Long.parseLong(timestamp));
    } catch (NumberFormatException e) {
      throw new ProviderException("malformed Stripe-Signature timestamp", false, e);
    }
    if (age > TOLERANCE_SECONDS) {
      throw new ProviderException("Stripe-Signature timestamp outside tolerance", false, null);
    }

    String payload = timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
    String expected = hmacSha256Hex(webhookSecret, payload);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
      throw new ProviderException("Stripe-Signature did not verify", false, null);
    }

    return parseEvent(rawBody);
  }

  /**
   * Parses a verified Stripe event body into the provider-neutral shape.
   *
   * @param rawBody the verified bytes
   * @return the event
   */
  static WebhookEvent parseEvent(byte[] rawBody) {
    try (JsonReader reader =
        Jsons.PROVIDER.createReader(
            new StringReader(new String(rawBody, StandardCharsets.UTF_8)))) {
      JsonObject root = reader.readObject();
      String type = root.getString("type", "");
      JsonObject intent = root.getJsonObject("data").getJsonObject("object");
      if (type.startsWith("charge.dispute.")) {
        return disputeEvent(root.getString("id"), type, intent);
      }
      // No currency is ever assumed: with none named the amount is left unread, and the intent's
      // own amount (in its own currency) is what the service applies.
      String currency = intent.getString("currency", null);

      BigDecimal captured = null;
      if (currency != null
          && intent.containsKey("amount_received")
          && !intent.isNull("amount_received")) {
        captured = majorUnits(intent.getJsonNumber("amount_received").longValue(), currency);
      }

      String failureCode = null;
      String failureMessage = null;
      if (intent.containsKey("last_payment_error") && !intent.isNull("last_payment_error")) {
        JsonObject err = intent.getJsonObject("last_payment_error");
        failureCode = err.getString("code", null);
        failureMessage = err.getString("message", null);
      }

      return new WebhookEvent(
          root.getString("id"),
          type,
          intent.getString("id"),
          statusForEvent(type, intent.getString("status", "")),
          captured,
          failureCode,
          failureMessage,
          null,
          type.startsWith("payment_intent.") ? ourIntent(intent) : null);
    } catch (RuntimeException e) {
      throw new ProviderException("could not parse Stripe event", false, e);
    }
  }

  /**
   * The intent of ours a PaymentIntent names in the metadata {@link #authorize} sent with it:
   * {@code intentId} and {@code tenantId}, which are part of the PaymentIntent object the event
   * carries. Only the pair is a correlation, and each is read as every id is ({@link Ids#parse}:
   * canonical, UUIDv7). Anything else — no metadata, one id of the two, text that is not an id, an
   * id of another version, a value that is not a string — names no intent of ours (null), and costs
   * the event nothing else: such an event is one this service did not tag, which is a fact about
   * the event and never a failure to read it.
   *
   * <p>Called only for {@code payment_intent.*} events, whose object id is the intent's reference:
   * the metadata of another kind of object (a charge, whose id is {@code ch_…}) says nothing about
   * the reference the event carries.
   */
  private static OurIntent ourIntent(JsonObject paymentIntent) {
    if (!(paymentIntent.get("metadata") instanceof JsonObject metadata)) return null;
    UUID tenantId = metadataId(metadata, "tenantId");
    UUID intentId = metadataId(metadata, "intentId");
    return tenantId == null || intentId == null ? null : new OurIntent(tenantId, intentId);
  }

  private static UUID metadataId(JsonObject metadata, String key) {
    if (!(metadata.get(key) instanceof JsonString text)) return null;
    try {
      return Ids.parse(text.getString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * A {@code charge.dispute.*} event (11.9): the object is the dispute, which names the payment
   * intent its charge belongs to. The fee is the sum of the fees on the dispute's balance
   * transactions, as Stripe reports it once it has debited the account.
   */
  private static WebhookEvent disputeEvent(String eventId, String type, JsonObject dispute) {
    String currency = dispute.getString("currency").toUpperCase(Locale.ROOT);
    String status = dispute.getString("status", "");
    String phase =
        switch (type) {
          case "charge.dispute.created" -> DisputeNotice.PHASE_OPENED;
          case "charge.dispute.funds_withdrawn" -> DisputeNotice.PHASE_FUNDS_WITHDRAWN;
          case "charge.dispute.funds_reinstated" -> DisputeNotice.PHASE_FUNDS_REINSTATED;
          case "charge.dispute.closed" -> DisputeNotice.PHASE_CLOSED;
          default -> DisputeNotice.PHASE_UPDATED;
        };
    String outcome = null;
    if (DisputeNotice.PHASE_CLOSED.equals(phase)) {
      // warning_closed is an inquiry that never became a chargeback: the money never left.
      outcome = "lost".equals(status) ? "LOST" : "WON";
    }
    Fee fee = disputeFee(dispute, currency);
    java.time.Instant dueBy = null;
    if (dispute.containsKey("evidence_details") && !dispute.isNull("evidence_details")) {
      JsonObject details = dispute.getJsonObject("evidence_details");
      if (details.containsKey("due_by") && !details.isNull("due_by")) {
        dueBy = java.time.Instant.ofEpochSecond(details.getJsonNumber("due_by").longValue());
      }
    }
    String intentRef =
        dispute.containsKey("payment_intent") && !dispute.isNull("payment_intent")
            ? dispute.getString("payment_intent")
            : null;
    return new WebhookEvent(
        eventId,
        type,
        intentRef,
        null,
        null,
        null,
        null,
        new DisputeNotice(
            dispute.getString("id"),
            phase,
            outcome,
            majorUnits(dispute.getJsonNumber("amount").longValue(), currency),
            fee.amount(),
            currency,
            disputeReason(dispute.getString("reason", "")),
            dispute.getString("network_reason_code", null),
            dueBy,
            fee.currency()));
  }

  /** A dispute's fee as Stripe charged it: in major units of its own currency, or not known. */
  private record Fee(BigDecimal amount, String currency) {}

  /**
   * The fee Stripe charged for a dispute: the sum of the fees on its balance transactions, each in
   * that balance transaction's own currency — the account's settlement currency, which is not the
   * disputed charge's when the account settles in another (a yen charge on an account paid out in
   * pounds: the fee is 1500 pence, never 1500 yen). Read in that currency's units.
   *
   * <p>No balance transaction yet is no fee yet: zero, in the dispute's currency. One that names no
   * currency, or fees in two currencies, are not guessed at or summed: the fee is not known (null),
   * said in the log, and what was known before is kept.
   */
  private static Fee disputeFee(JsonObject dispute, String disputeCurrency) {
    if (!dispute.containsKey("balance_transactions") || dispute.isNull("balance_transactions")) {
      return new Fee(BigDecimal.ZERO, disputeCurrency);
    }
    long minor = 0;
    String in = null;
    for (JsonObject t :
        dispute.getJsonArray("balance_transactions").getValuesAs(JsonObject.class)) {
      if (!t.containsKey("fee") || t.isNull("fee")) continue;
      long fee = t.getJsonNumber("fee").longValueExact();
      String code =
          t.containsKey("currency") && !t.isNull("currency")
              ? t.getString("currency").toUpperCase(Locale.ROOT)
              : null;
      if (code == null || (in != null && !in.equals(code))) {
        LOG.warning(
            "Stripe dispute "
                + dispute.getString("id", "?")
                + ": a fee "
                + (code == null ? "in no currency" : "in " + code + " beside one in " + in)
                + "; the fee is left as it was known");
        return new Fee(null, null);
      }
      in = code;
      minor = Math.addExact(minor, fee);
    }
    return in == null
        ? new Fee(BigDecimal.ZERO, disputeCurrency)
        : new Fee(majorUnits(minor, in), in);
  }

  /** Stripe's dispute reasons, in the categories this service keeps. */
  static String disputeReason(String stripeReason) {
    return switch (stripeReason) {
      case "fraudulent" -> "FRAUDULENT";
      case "product_not_received" -> "PRODUCT_NOT_RECEIVED";
      case "product_unacceptable" -> "PRODUCT_UNACCEPTABLE";
      case "duplicate" -> "DUPLICATE";
      case "credit_not_processed" -> "CREDIT_NOT_PROCESSED";
      case "subscription_canceled" -> "SUBSCRIPTION_CANCELLED";
      case "unrecognized" -> "UNRECOGNIZED";
      default -> "GENERAL";
    };
  }

  /**
   * {@inheritDoc}
   *
   * <p>Stripe takes the text fields it has names for and one free-text field for the rest, and
   * {@code submit=true} sends them to the bank: after that the evidence cannot be changed.
   */
  @Override
  public void submitDisputeEvidence(String disputeRef, DisputeAnswer answer) {
    Map<String, String> form = new java.util.LinkedHashMap<>();
    put(form, "evidence[product_description]", answer.productDescription());
    put(form, "evidence[customer_name]", answer.customerName());
    put(form, "evidence[customer_email_address]", answer.customerEmail());
    put(form, "evidence[refund_policy_disclosure]", answer.refundPolicy());
    put(form, "evidence[uncategorized_text]", answer.uncategorized());
    form.put("submit", "true");
    post("/v1/disputes/" + disputeRef, form, "dispute-evidence-" + disputeRef);
  }

  /** {@inheritDoc} */
  @Override
  public void acceptDispute(String disputeRef) {
    post("/v1/disputes/" + disputeRef + "/close", Map.of(), "dispute-accept-" + disputeRef);
  }

  private static void put(Map<String, String> form, String key, String value) {
    if (value != null && !value.isBlank()) form.put(key, value);
  }

  /**
   * Maps a Stripe event to the status it implies.
   *
   * <p>Driven by the event type first and the intent status second, because the two disagree in the
   * case that matters: {@code payment_intent.amount_capturable_updated} carries status {@code
   * requires_capture}, which is Stripe's way of saying authorised.
   *
   * <p>An event about another kind of object than a PaymentIntent (a charge, say) implies none:
   * {@link PaymentIntent#STATUS_REQUIRES_ACTION}, which {@code handleWebhook} applies as nothing.
   * The status such an object carries is its own ({@code succeeded} for a charge), not the
   * intent's, and the reference the event carries is not an intent's.
   *
   * @param type the event type
   * @param intentStatus the status of the object the event is about
   * @return the corresponding {@code Domain.PaymentIntent} status
   */
  static String statusForEvent(String type, String intentStatus) {
    if (!type.startsWith("payment_intent.")) {
      return PaymentIntent.STATUS_REQUIRES_ACTION;
    }
    return switch (type) {
      case "payment_intent.succeeded" -> PaymentIntent.STATUS_CAPTURED;
      case "payment_intent.payment_failed" -> PaymentIntent.STATUS_FAILED;
      case "payment_intent.canceled" -> PaymentIntent.STATUS_CANCELLED;
      case "payment_intent.amount_capturable_updated" -> PaymentIntent.STATUS_AUTHORIZED;
      default -> statusFrom(intentStatus);
    };
  }

  /**
   * @param stripeStatus a Stripe PaymentIntent status
   * @return the corresponding {@code Domain.PaymentIntent} status
   */
  static String statusFrom(String stripeStatus) {
    return switch (stripeStatus) {
      case "requires_capture" -> PaymentIntent.STATUS_AUTHORIZED;
      case "succeeded" -> PaymentIntent.STATUS_CAPTURED;
      case "canceled" -> PaymentIntent.STATUS_CANCELLED;
      default -> PaymentIntent.STATUS_REQUIRES_ACTION;
    };
  }

  private static String nextActionUrl(JsonObject body) {
    if (!body.containsKey("next_action") || body.isNull("next_action")) {
      return null;
    }
    JsonObject action = body.getJsonObject("next_action");
    if (action.containsKey("redirect_to_url") && !action.isNull("redirect_to_url")) {
      return action.getJsonObject("redirect_to_url").getString("url", null);
    }
    return null;
  }

  /**
   * Currencies Stripe quotes in whole units although ISO 4217 gives them minor units: MGA (two in
   * ISO). Every other Stripe zero-decimal currency (BIF, CLP, DJF, GNF, JPY, KMF, KRW, PYG, RWF,
   * VND, VUV, XAF, XOF, XPF) has none in ISO 4217 too, so {@link Fx#minorUnits} already says so.
   */
  private static final Set<String> WHOLE_THOUGH_ISO_HAS_UNITS = Set.of("MGA");

  /**
   * Currencies with no minor unit that Stripe still quotes as hundredths, the hundredths always
   * {@code 00}: 5 ISK is {@code amount=500}, and so is 5 UGX (Stripe's "special cases", kept for
   * backward compatibility). A fraction of either cannot be charged.
   */
  private static final Set<String> WHOLE_AS_HUNDREDTHS = Set.of("ISK", "UGX");

  /**
   * Stripe's three-decimal currencies (BHD, JOD, KWD, OMR, TND): quoted in thousandths, as ISO 4217
   * has them, but the last of the three must be 0, so 5.120 KWD is {@code amount=5120} and 5.124
   * cannot be charged.
   */
  private static final Set<String> THREE_DECIMAL_IN_TENS =
      Set.of("BHD", "JOD", "KWD", "OMR", "TND");

  /**
   * The power of ten Stripe's {@code amount} is in for a currency: its ISO 4217 minor units
   * (through common-service {@link Fx#minorUnits}: none for yen or CFA francs, three for a dinar,
   * two for most), with Stripe's documented exceptions (docs.stripe.com/currencies): MGA whole, ISK
   * and UGX as hundredths. Getting it wrong charges a hundred times the price (XOF as hundredths)
   * or a tenth of it (KWD as hundredths).
   *
   * @param currency ISO-4217 code, any case
   * @return the exponent to scale by
   */
  static int exponent(String currency) {
    String code = currency.toUpperCase(Locale.ROOT);
    if (WHOLE_AS_HUNDREDTHS.contains(code)) return 2;
    if (WHOLE_THOUGH_ISO_HAS_UNITS.contains(code)) return 0;
    return Fx.minorUnits(code);
  }

  /**
   * The decimal places Stripe can charge a currency to: its exponent, except whole units for ISK
   * and UGX and two places (tens of fils) for the three-decimal currencies.
   */
  private static int chargeablePlaces(String code) {
    if (WHOLE_AS_HUNDREDTHS.contains(code)) return 0;
    if (THREE_DECIMAL_IN_TENS.contains(code)) return 2;
    return exponent(code);
  }

  /** An amount longer than this (77 digits) is not money Stripe's twelve-digit amount can hold. */
  private static final int MOST_UNSCALED_BITS = 256;

  /**
   * Converts a major-unit amount to the minor units Stripe quotes in. Never rounds: an amount finer
   * than Stripe can charge in that currency (half a yen, a fifth fils, a fraction of a krona) is
   * refused before Stripe is asked, since charging another figure than the order's total is not
   * this driver's decision.
   *
   * @param amount the amount in major units
   * @param currency ISO-4217 code, which decides the exponent
   * @return the amount in minor units, exactly
   * @throws AmountNotChargeable for an amount Stripe cannot charge exactly, naming the nearest
   *     amounts it can either side of it
   * @throws ProviderException non-retryable, for an amount that is not money at all
   */
  static long minorUnits(BigDecimal amount, String currency) {
    String code = currency.toUpperCase(Locale.ROOT);
    int places = chargeablePlaces(code);
    // Judged on the digits written, before any rescaling: never a power of ten as long as an
    // exponent. Stripe's amount has at most twelve digits; fifteen whole ones is not money.
    if ((long) amount.precision() - amount.scale() > 15
        || amount.unscaledValue().bitLength() > MOST_UNSCALED_BITS) {
      throw new ProviderException(
          "Stripe cannot charge " + code + " " + amount.toEngineeringString(), false, null);
    }
    if (amount.scale() > places && amount.stripTrailingZeros().scale() > places) {
      // Shown at the currency's own units, as a price is: 1.120 dinars, not 1.12.
      int units = Math.max(places, Fx.minorUnits(code));
      BigDecimal step = BigDecimal.ONE.movePointLeft(places);
      BigDecimal below;
      BigDecimal above;
      if ((long) amount.precision() - amount.scale() < -places) {
        // Under the smallest amount Stripe charges (1E-80000000 among them): worked out without
        // rescaling, which would build a power of ten as long as the exponent.
        below = BigDecimal.ZERO;
        above = step;
      } else {
        below = amount.setScale(places, RoundingMode.FLOOR);
        above = amount.setScale(places, RoundingMode.CEILING);
      }
      throw new AmountNotChargeable(
          "Stripe charges "
              + code
              + " to "
              + places
              + " decimal place"
              + (places == 1 ? "" : "s")
              + ", so "
              + amount
              + " cannot be charged exactly",
          amount,
          code,
          below.signum() > 0 ? below.setScale(units) : null,
          above.setScale(units));
    }
    try {
      return amount
          .setScale(places, RoundingMode.UNNECESSARY)
          .movePointRight(exponent(code))
          .longValueExact();
    } catch (ArithmeticException e) {
      throw new ProviderException(
          "Stripe cannot charge " + code + " " + amount.toPlainString(), false, e);
    }
  }

  /**
   * Converts a minor-unit amount reported by Stripe back to major units, at the currency's own
   * minor units (1120 fils is 1.120 dinars, 500 krónur-hundredths is 5 krónur).
   *
   * @param minor the amount in minor units
   * @param currency ISO-4217 code, which decides the exponent
   * @return the amount in major units
   */
  static BigDecimal majorUnits(long minor, String currency) {
    String code = currency.toUpperCase(Locale.ROOT);
    BigDecimal major = BigDecimal.valueOf(minor).movePointLeft(exponent(code));
    int units = Fx.minorUnits(code);
    // ISK and UGX come back as hundredths that are always 00: said at the currency's own units.
    return major.scale() > units && major.stripTrailingZeros().scale() <= units
        ? major.setScale(units, RoundingMode.UNNECESSARY)
        : major;
  }

  /**
   * Computes the lowercase hex HMAC-SHA256 used to verify a webhook signature.
   *
   * @param secret the endpoint's webhook secret
   * @param payload the signed string, {@code "<timestamp>.<raw body>"}
   * @return the digest as lowercase hex
   * @throws ProviderException non-retryable when the JVM cannot provide HMAC-SHA256
   */
  static String hmacSha256Hex(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.GeneralSecurityException e) {
      throw new ProviderException("could not compute webhook signature", false, e);
    }
  }

  private JsonObject post(String path, Map<String, String> form, String idempotencyKey) {
    if (secretKey == null || secretKey.isBlank()) {
      throw new ProviderException("no Stripe secret key is configured", false, null);
    }
    StringBuilder encoded = new StringBuilder();
    form.forEach(
        (k, v) -> {
          if (!encoded.isEmpty()) {
            encoded.append('&');
          }
          encoded
              .append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });

    try {
      var request =
          webClient
              .post(apiBase + path)
              .header(HeaderNames.AUTHORIZATION, "Bearer " + secretKey)
              .header(HeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");
      if (idempotencyKey != null && !idempotencyKey.isBlank()) {
        // Stripe's own replay guard, so a retry of ours cannot become a second authorisation.
        request = request.header(HeaderNames.create("Idempotency-Key"), idempotencyKey);
      }
      try (HttpClientResponse res = request.submit(encoded.toString())) {
        String body = res.as(String.class);
        int status = res.status().code();
        if (status >= 500) {
          throw new ProviderException("Stripe returned HTTP " + status, true, null);
        }
        if (status >= 400) {
          throw new ProviderException("Stripe rejected the request: " + body, false, null);
        }
        try (JsonReader reader = Jsons.PROVIDER.createReader(new StringReader(body))) {
          return reader.readObject();
        }
      }
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      // Reached Stripe or not, we cannot tell — retryable, because a timeout on the way back may
      // still have created the intent.
      throw new ProviderException("Stripe unreachable: " + e.getMessage(), true, e);
    }
  }
}
