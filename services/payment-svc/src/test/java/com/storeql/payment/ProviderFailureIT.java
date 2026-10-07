package com.storeql.payment;

import static com.storeql.payment.ItCalls.call;
import static com.storeql.payment.ItCalls.post;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.provider.PaymentProvider.DisputeNotice;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.DisputeService;
import com.storeql.service.OutboxRow;
import com.storeql.test.DriverStub;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A payment provider that cannot be reached, that refuses, or that is not deployed here, against
 * real Postgres: Stripe is a stand-in for its API that answers as the test tells it to, and the
 * order service is a stub. Each answer is read back as what moved: the attempt is kept where the
 * design keeps it (an authorisation that failed stays on record as FAILED, with no provider
 * reference, and the provider was sent its id, so an event for a hold it did place all the same is
 * matched to it: WebhookOrderingIT), and nothing else is taken, recorded or announced.
 *
 * <p>The provider here is Stripe, not the MANUAL default the other classes run with, so this one
 * sets it for its own deployment and clears it after.
 */
@HelidonTest
class ProviderFailureIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");
  private static final JsonStub ORDERS = JsonStub.start("order-svc");
  private static final TenantSvcStub TENANTS = TenantSvcStub.start();

  /** Stripe's API: it answers as it was last told to, 500 until a test says otherwise. */
  private static final DriverStub STRIPE =
      DriverStub.start(
          r -> DriverStub.Reply.json(500, "{\"error\":{\"message\":\"unavailable\"}}"));

  static {
    System.setProperty("storeql.payment.provider", "STRIPE");
    System.setProperty("storeql.payment.stripe.api-base", STRIPE.url());
    // A key made for this run: the stand-in checks nothing about it.
    System.setProperty("storeql.payment.stripe.secret-key", "key-" + Ids.newId());
  }

  @Inject WebTarget target;
  @Inject PaymentIntentRepository intents;
  @Inject PaymentRepository payments;
  @Inject DisputeService disputes;

  @AfterAll
  static void stop() {
    STRIPE.close();
    TENANTS.close();
    ORDERS.close();
    System.clearProperty("storeql.clients.order-svc.url");
    System.clearProperty("storeql.clients.tenant-svc.url");
    System.clearProperty("storeql.payment.provider");
    System.clearProperty("storeql.payment.stripe.api-base");
    System.clearProperty("storeql.payment.stripe.secret-key");
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Stripe answers every request with this status and an error body (or an empty object). */
  private static void stripeAnswers(int status) {
    STRIPE.answerWith(
        r ->
            DriverStub.Reply.json(
                status, status >= 400 ? "{\"error\":{\"message\":\"no\"}}" : "{}"));
  }

  /** The HTTP status a provider's failure is told as: 503 when it may be tried again, else 502. */
  private static int told(int providerStatus) {
    return providerStatus >= 500 ? 503 : 502;
  }

  private PaymentIntent intent(UUID tenant, UUID order, String provider, String ref) {
    Instant now = Instant.now();
    return intents.create(
        new PaymentIntent(
            Ids.newId(),
            tenant,
            order,
            null,
            provider,
            ref,
            new BigDecimal("3.37"),
            BigDecimal.ZERO,
            "GBP",
            PaymentIntent.STATUS_AUTHORIZED,
            null,
            null,
            null,
            null,
            null,
            now,
            now));
  }

  private static String tenders(UUID order) {
    return scalar(
        PG, "SELECT count(*) FROM payment.payment_tenders WHERE order_id = '" + order + "'");
  }

  private static String captures(UUID order) {
    return scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentCaptured'"
            + " AND payload LIKE '%\"orderId\":\""
            + order
            + "\"%'");
  }

  private static String intentState(UUID order) {
    return scalar(
        PG,
        "SELECT status || '/' || coalesce(failure_code, 'none') FROM payment.payment_intents"
            + " WHERE order_id = '"
            + order
            + "'");
  }

  private static String intentStatus(UUID intentId) {
    return scalar(PG, "SELECT status FROM payment.payment_intents WHERE id = '" + intentId + "'");
  }

  private static String tail() {
    String id = Ids.newId().toString();
    return id.substring(id.length() - 12);
  }

  // ── authorising ────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A provider that is down, or refuses, when a payment is authorised is 503 or 502"
          + " PAYMENT_PROVIDER_UNAVAILABLE: the attempt stays on record as FAILED and nothing is taken")
  void aProviderThatCannotAuthoriseIsToldSoAndTakesNothing() {
    for (int providerStatus : new int[] {500, 402}) {
      UUID tenant = Ids.newId();
      UUID shopper = Ids.newId();
      UUID order = Ids.newId();
      ORDERS.on(
          "GET",
          "/orders/" + order,
          200,
          "{\"data\":{\"id\":\""
              + order
              + "\",\"loginId\":\""
              + shopper
              + "\",\"channel\":\"ONLINE\",\"status\":\"PENDING\",\"total\":3.37,"
              + "\"currency\":\"GBP\"}}");
      stripeAnswers(providerStatus);
      int asked = STRIPE.count();

      Answer a =
          call(
              target,
              "POST",
              "/payments/intents",
              new Caller(tenant, shopper, "CUSTOMER"),
              "{\"orderId\":\"" + order + "\",\"amount\":3.37}",
              Ids.newId().toString());

      assertThat(providerStatus + " " + a.body(), a.status(), is(told(providerStatus)));
      assertThat(a.code(), is("PAYMENT_PROVIDER_UNAVAILABLE"));
      assertThat("the provider was asked once, not retried here", STRIPE.count(), is(asked + 1));
      assertThat(
          "the attempt is kept, failed, for the reconciliation",
          intentState(order),
          is("FAILED/PROVIDER_ERROR"));
      assertThat("no money was recorded", tenders(order), is("0"));
      assertThat("nothing was announced", captures(order), is("0"));
    }
  }

  @Test
  @DisplayName(
      "A refused authorisation leaves the intent FAILED with no provider reference, and Stripe"
          + " was told the intent's id, so an event for a hold it placed all the same is matched"
          + " to it")
  void aFailedAuthorisationIsFoundAtTheProviderByItsIntentId() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID order = Ids.newId();
    ORDERS.on(
        "GET",
        "/orders/" + order,
        200,
        "{\"data\":{\"id\":\""
            + order
            + "\",\"loginId\":\""
            + shopper
            + "\",\"channel\":\"ONLINE\",\"status\":\"PENDING\",\"total\":3.37,"
            + "\"currency\":\"GBP\"}}");
    stripeAnswers(500);
    int asked = STRIPE.count();

    Answer a =
        call(
            target,
            "POST",
            "/payments/intents",
            new Caller(tenant, shopper, "CUSTOMER"),
            "{\"orderId\":\"" + order + "\",\"amount\":3.37}",
            Ids.newId().toString());

    assertThat(a.body().toString(), a.status(), is(503));
    assertThat(STRIPE.count(), is(asked + 1));
    String intentId =
        scalar(PG, "SELECT id FROM payment.payment_intents WHERE order_id = '" + order + "'");
    assertThat(
        "the provider's error carries no reference, so none is stored: a webhook for a hold it"
            + " placed is matched by the intent's id instead (WebhookOrderingIT)",
        scalar(
            PG,
            "SELECT coalesce(provider_ref, 'none') FROM payment.payment_intents WHERE id = '"
                + intentId
                + "'"),
        is("none"));
    DriverStub.Recorded sent = STRIPE.last();
    assertThat(sent.route(), is("/v1/payment_intents"));
    assertThat(
        "what a webhook names the intent by, and a person looks a hold up by at the provider",
        sent.form().get("metadata[intentId]").get(0),
        is(intentId));
    assertThat(sent.form().get("metadata[orderId]").get(0), is(order.toString()));
    assertThat(sent.form().get("metadata[tenantId]").get(0), is(tenant.toString()));
  }

  @Test
  @DisplayName(
      "Stripe is asked in the order currency's own units (1.120 KWD is 1120, 5000 XOF and 1250"
          + " JPY whole), and a dinar amount it cannot charge exactly is 422 before it is asked")
  void stripeIsAskedInTheCurrencysOwnUnits() {
    STRIPE.answerWith(
        r ->
            DriverStub.Reply.json(
                200, "{\"id\":\"pi_" + tail() + "\",\"status\":\"requires_capture\"}"));
    for (String[] c :
        new String[][] {
          {"KWD", "1.120", "1120"}, {"XOF", "5000", "5000"}, {"JPY", "1250", "1250"}
        }) {
      UUID tenant = Ids.newId();
      UUID shopper = Ids.newId();
      UUID order = Ids.newId();
      ORDERS.on(
          "GET",
          "/orders/" + order,
          200,
          "{\"data\":{\"id\":\""
              + order
              + "\",\"loginId\":\""
              + shopper
              + "\",\"channel\":\"ONLINE\",\"status\":\"PENDING\",\"total\":"
              + c[1]
              + ",\"currency\":\""
              + c[0]
              + "\"}}");
      Answer a =
          call(
              target,
              "POST",
              "/payments/intents",
              new Caller(tenant, shopper, "CUSTOMER"),
              "{\"orderId\":\"" + order + "\",\"amount\":" + c[1] + "}",
              Ids.newId().toString());
      assertThat(c[0] + " " + a.body(), a.status(), is(201));
      DriverStub.Recorded sent = STRIPE.last();
      assertThat(sent.route(), is("/v1/payment_intents"));
      assertThat(c[0], sent.form().get("amount").get(0), is(c[2]));
      assertThat(sent.form().get("currency").get(0), is(c[0].toLowerCase(java.util.Locale.ROOT)));
    }

    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID order = Ids.newId();
    ORDERS.on(
        "GET",
        "/orders/" + order,
        200,
        "{\"data\":{\"id\":\""
            + order
            + "\",\"loginId\":\""
            + shopper
            + "\",\"channel\":\"ONLINE\",\"status\":\"PENDING\",\"total\":1.125,"
            + "\"currency\":\"KWD\"}}");
    int asked = STRIPE.count();
    Answer a =
        call(
            target,
            "POST",
            "/payments/intents",
            new Caller(tenant, shopper, "CUSTOMER"),
            "{\"orderId\":\"" + order + "\",\"amount\":1.125}",
            Ids.newId().toString());
    // A price Stripe cannot take is the shopper's to know, not an outage: 422 naming the nearest
    // amounts it can charge, before an intent is recorded or Stripe is asked.
    assertThat(a.body().toString(), a.status(), is(422));
    assertThat(a.code(), is("PAYMENT_AMOUNT_NOT_CHARGEABLE"));
    assertThat(
        a.body().toString(),
        String.valueOf(a.body().get("details")),
        org.hamcrest.Matchers.containsString(
            "currency=KWD;chargeableBelow=1.120;chargeableAbove=1.130"));
    assertThat("Stripe was never asked", STRIPE.count(), is(asked));
    assertThat("nothing was recorded for it", intentState(order), is(nullValue()));
    assertThat("no money was recorded", tenders(order), is("0"));
    // Another shopper's order of the same amount is not opened by naming it either.
    Answer stranger =
        call(
            target,
            "POST",
            "/payments/intents",
            new Caller(Ids.newId(), Ids.newId(), "CUSTOMER"),
            "{\"orderId\":\"" + order + "\",\"amount\":1.125}",
            Ids.newId().toString());
    assertThat(stranger.body().toString(), stranger.status() >= 400, is(true));
    assertThat(STRIPE.count(), is(asked));
    assertThat(intentState(order), is(nullValue()));
  }

  // ── capturing ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A provider that is down, or refuses, when a payment is captured is 503 or 502"
          + " PAYMENT_PROVIDER_UNAVAILABLE, the intent stays capturable, and the retry goes through once")
  void aProviderThatCannotCaptureLeavesTheIntentCapturable() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    String ref = "pi_" + tail();
    PaymentIntent held = intent(tenant, order, "STRIPE", ref);
    String path = "/payments/intents/" + held.id() + "/capture";
    Caller cashier = new Caller(tenant, Ids.newId(), "CASHIER");

    for (int providerStatus : new int[] {500, 402}) {
      stripeAnswers(providerStatus);
      Answer a = post(target, path, cashier, null);

      assertThat(providerStatus + " " + a.body(), a.status(), is(told(providerStatus)));
      assertThat(a.code(), is("PAYMENT_PROVIDER_UNAVAILABLE"));
      assertThat("still authorised", intentStatus(held.id()), is("AUTHORIZED"));
      assertThat("no money was recorded", tenders(order), is("0"));
      assertThat("nothing was announced", captures(order), is("0"));
    }

    // The provider is back: the same capture goes through, and takes the money once.
    STRIPE.answerWith(
        r ->
            DriverStub.Reply.json(
                200, "{\"id\":\"" + ref + "\",\"currency\":\"gbp\",\"amount_received\":337}"));
    Answer ok = post(target, path, cashier, null);
    assertThat(ok.body().toString(), ok.status(), is(200));
    assertThat("captured", intentStatus(held.id()), is("CAPTURED"));
    assertThat(tenders(order), is("1"));
    assertThat(captures(order), is("1"));
    assertThat(
        "every try carried the same key, so the provider can never take it twice",
        STRIPE.requests().stream()
            .filter(r -> r.route().contains(ref))
            .map(r -> r.header("Idempotency-Key"))
            .distinct()
            .count(),
        is(1L));
  }

  @Test
  @DisplayName(
      "An intent opened with a provider that is not deployed here cannot be captured: 502"
          + " PAYMENT_PROVIDER_UNAVAILABLE, and the provider that is deployed is not asked")
  void anIntentOfAProviderNotDeployedIsNotCaptured() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    PaymentIntent held = intent(tenant, order, "PAYPAL", "pp_" + tail());
    int asked = STRIPE.count();

    Answer a =
        post(
            target,
            "/payments/intents/" + held.id() + "/capture",
            new Caller(tenant, Ids.newId(), "CASHIER"),
            null);

    assertThat(a.body().toString(), a.status(), is(502));
    assertThat(a.code(), is("PAYMENT_PROVIDER_UNAVAILABLE"));
    assertThat("still authorised", intentStatus(held.id()), is("AUTHORIZED"));
    assertThat("no money was recorded", tenders(order), is("0"));
    assertThat("another provider was not asked in its place", STRIPE.count(), is(asked));
  }

  // ── disputes ───────────────────────────────────────────────────────────────

  /** A dispute the provider opened against a captured card payment, waiting for an answer. */
  private UUID providerDispute(UUID tenant, String provider) {
    UUID order = Ids.newId();
    UUID tender = Ids.newId();
    payments.createTender(
        new PaymentTender(
            tender,
            tenant,
            order,
            new BigDecimal("80.00"),
            PaymentTender.METHOD_CARD,
            "auth-" + tail(),
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            null),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenant, tender, "{}"));
    String ref = "dp_" + tail();
    PaymentIntent paid =
        new PaymentIntent(
            Ids.newId(),
            tenant,
            order,
            null,
            provider,
            "pi_" + tail(),
            new BigDecimal("80.00"),
            new BigDecimal("80.00"),
            "GBP",
            PaymentIntent.STATUS_CAPTURED,
            null,
            null,
            null,
            tender,
            null,
            Instant.now(),
            Instant.now());
    disputes.fromProvider(
        provider,
        paid,
        new DisputeNotice(
            ref,
            DisputeNotice.PHASE_OPENED,
            null,
            new BigDecimal("80.00"),
            BigDecimal.ZERO,
            "GBP",
            "GENERAL",
            null,
            Instant.now().plusSeconds(600_000)));
    return Ids.parse(
        scalar(
            PG,
            "SELECT id FROM payment.disputes WHERE tenant_id = '"
                + tenant
                + "' AND provider_dispute_ref = '"
                + ref
                + "'"));
  }

  private static String disputeStatus(UUID id) {
    return scalar(PG, "SELECT status FROM payment.disputes WHERE id = '" + id + "'");
  }

  /**
   * What a dispute has on record: its events and its evidence, so a refusal can be read as nothing.
   */
  private static String disputeRecord(UUID id) {
    return scalar(
        PG,
        "SELECT (SELECT count(*) FROM payment.dispute_events WHERE dispute_id = '"
            + id
            + "') || '/' || (SELECT count(*) FROM payment.dispute_evidence WHERE dispute_id = '"
            + id
            + "') || '/' || (SELECT count(*) FROM payment.outbox WHERE aggregate_id = '"
            + id
            + "')");
  }

  @Test
  @DisplayName(
      "A provider that is down, or refuses, when a dispute is answered or accepted is 502"
          + " PAYMENT_PROVIDER_REFUSED, and the dispute is left as it was")
  void aProviderThatRefusesADisputeAnswerLeavesTheDisputeOpen() {
    UUID tenant = Ids.newId();
    Caller me = Caller.owner(tenant);
    UUID dispute = providerDispute(tenant, "STRIPE");
    String evidence = "/admin/disputes/" + dispute + "/evidence";
    String accept = "/admin/disputes/" + dispute + "/accept";
    String before = disputeRecord(dispute);

    for (int providerStatus : new int[] {500, 400}) {
      stripeAnswers(providerStatus);
      Answer answered = post(target, evidence, me, "{\"notes\":\"Here is the receipt\"}");
      assertThat(providerStatus + " " + answered.body(), answered.status(), is(502));
      assertThat(answered.code(), is("PAYMENT_PROVIDER_REFUSED"));
      Answer accepted = post(target, accept, me, null);
      assertThat(providerStatus + " " + accepted.body(), accepted.status(), is(502));
      assertThat(accepted.code(), is("PAYMENT_PROVIDER_REFUSED"));
    }
    assertThat("still waiting for an answer", disputeStatus(dispute), is("NEEDS_RESPONSE"));
    assertThat("no event, no evidence, nothing announced", disputeRecord(dispute), is(before));

    // Another business finds no such dispute, and the provider is not asked on its behalf.
    int asked = STRIPE.count();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Caller theirs = new Caller(Ids.newId(), Ids.newId(), role);
      Answer a = post(target, evidence, theirs, "{\"notes\":\"not ours\"}");
      assertThat(role + " " + a.body(), a.status(), is(404));
      assertThat(role, a.code(), is("DISPUTE_NOT_FOUND"));
      assertThat(role, post(target, accept, theirs, null).status(), is(404));
    }
    assertThat("the provider was not asked", STRIPE.count(), is(asked));

    // The provider is back: the same answer is taken, once, and the dispute moves.
    stripeAnswers(200);
    Answer ok = post(target, evidence, me, "{\"notes\":\"Here is the receipt\"}");
    assertThat(ok.body().toString(), ok.status(), is(200));
    assertThat("with the acquirer now", disputeStatus(dispute), is("UNDER_REVIEW"));
  }

  @Test
  @DisplayName(
      "A dispute of a provider that is not deployed here cannot be answered or accepted: 502"
          + " PAYMENT_PROVIDER_UNAVAILABLE, and the dispute is left as it was")
  void aDisputeOfAProviderNotDeployedIsNotAnswered() {
    UUID tenant = Ids.newId();
    Caller me = Caller.owner(tenant);
    UUID dispute = providerDispute(tenant, "PAYPAL");
    String before = disputeRecord(dispute);
    int asked = STRIPE.count();

    Answer answered =
        post(target, "/admin/disputes/" + dispute + "/evidence", me, "{\"notes\":\"A receipt\"}");
    assertThat(answered.body().toString(), answered.status(), is(502));
    assertThat(answered.code(), is("PAYMENT_PROVIDER_UNAVAILABLE"));
    Answer accepted = post(target, "/admin/disputes/" + dispute + "/accept", me, null);
    assertThat(accepted.body().toString(), accepted.status(), is(502));
    assertThat(accepted.code(), is("PAYMENT_PROVIDER_UNAVAILABLE"));

    assertThat("still waiting for an answer", disputeStatus(dispute), is("NEEDS_RESPONSE"));
    assertThat("no event, no evidence, nothing announced", disputeRecord(dispute), is(before));
    assertThat("another provider was not asked in its place", STRIPE.count(), is(asked));
  }
}
