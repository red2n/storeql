package com.storeql.payment;

import static com.storeql.payment.ItCalls.call;
import static com.storeql.payment.ItCalls.get;
import static com.storeql.payment.ItCalls.post;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
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
 * The refusals of the payment-intent endpoints and of the provider webhook, against real Postgres
 * with order-svc a stub: an intent nobody opened, an intent that cannot be captured, a return URL
 * off the allowlist, and a webhook from a provider that is not deployed or that does not verify.
 * Each refusal is read back as nothing moved.
 */
@HelidonTest
class PaymentIntentIT {

  private static final PostgresSupport PG;
  private static final JsonStub ORDERS;

  static {
    PG = PostgresSupport.start();
    ORDERS = JsonStub.start("order-svc");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "payment");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.payment.return-url.allowed", "https://shop.example/");
  }

  @Inject WebTarget target;
  @Inject PaymentIntentRepository intents;

  @AfterAll
  static void stop() {
    ORDERS.close();
    System.clearProperty("storeql.clients.order-svc.url");
    System.clearProperty("storeql.payment.return-url.allowed");
    PG.stop();
  }

  private PaymentIntent intent(UUID tenant, UUID order, String status) {
    Instant now = Instant.now();
    return intents.create(
        new PaymentIntent(
            Ids.newId(),
            tenant,
            order,
            null,
            "MANUAL",
            null,
            new BigDecimal("3.37"),
            BigDecimal.ZERO,
            "GBP",
            status,
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

  private static String intentRows(UUID order) {
    return scalar(
        PG, "SELECT count(*) FROM payment.payment_intents WHERE order_id = '" + order + "'");
  }

  private static String statusOf(UUID intentId) {
    return scalar(PG, "SELECT status FROM payment.payment_intents WHERE id = '" + intentId + "'");
  }

  @Test
  @DisplayName("An intent nobody opened, or another business's, is 404 for read and capture")
  void anIntentNobodyOpenedIsNotFound() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    Caller owner = Caller.owner(tenant);

    UUID nobody = Ids.newId();
    Answer read = get(target, "/payments/intents/" + nobody, owner);
    assertThat(read.status(), is(404));
    assertThat(read.code(), is("PAYMENT_INTENT_NOT_FOUND"));
    Answer capture =
        post(target, "/payments/intents/" + nobody + "/capture", owner.as("CASHIER"), null);
    assertThat(capture.status(), is(404));
    assertThat(capture.code(), is("PAYMENT_INTENT_NOT_FOUND"));

    // The intent exists, but in another business: neither its staff of any role reads or takes it.
    PaymentIntent ours = intent(tenant, order, PaymentIntent.STATUS_AUTHORIZED);
    UUID rival = Ids.newId();
    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      Caller theirs = new Caller(rival, Ids.newId(), role);
      Answer r = get(target, "/payments/intents/" + ours.id(), theirs);
      assertThat(role, r.status(), is(404));
      assertThat(role, r.code(), is("PAYMENT_INTENT_NOT_FOUND"));
      Answer c = post(target, "/payments/intents/" + ours.id() + "/capture", theirs, null);
      assertThat(role, c.status(), is(404));
      assertThat(role, c.code(), is("PAYMENT_INTENT_NOT_FOUND"));
    }
    // A shopper never captures: taking money is staff's.
    Answer shopper =
        post(
            target,
            "/payments/intents/" + ours.id() + "/capture",
            new Caller(tenant, Ids.newId(), "CUSTOMER"),
            null);
    assertThat(shopper.status(), is(403));

    assertThat(statusOf(ours.id()), is(PaymentIntent.STATUS_AUTHORIZED));
    assertThat(tenders(order), is("0"));
    assertThat(captures(order), is("0"));
  }

  @Test
  @DisplayName("An intent that is not authorised is not captured, whatever state it is stuck in")
  void anIntentNotAuthorisedIsNotCaptured() {
    UUID tenant = Ids.newId();
    Caller cashier = new Caller(tenant, Ids.newId(), "CASHIER");
    for (String status :
        new String[] {
          PaymentIntent.STATUS_FAILED,
          PaymentIntent.STATUS_REQUIRES_ACTION,
          PaymentIntent.STATUS_CANCELLED
        }) {
      UUID order = Ids.newId();
      PaymentIntent stuck = intent(tenant, order, status);

      Answer a = post(target, "/payments/intents/" + stuck.id() + "/capture", cashier, null);

      assertThat(status, a.status(), is(409));
      assertThat(status, a.code(), is("PAYMENT_INTENT_NOT_CAPTURABLE"));
      assertThat(status, statusOf(stuck.id()), is(status));
      assertThat(status, tenders(order), is("0"));
      assertThat(status, captures(order), is("0"));
    }
  }

  @Test
  @DisplayName("A return URL off the allowlist is refused and no intent is opened")
  void aReturnUrlOffTheAllowlistIsRefused() {
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

    Answer a =
        call(
            target,
            "POST",
            "/payments/intents",
            new Caller(tenant, shopper, "CUSTOMER"),
            "{\"orderId\":\""
                + order
                + "\",\"amount\":3.37,\"returnUrl\":\"https://evil.example/x\"}",
            Ids.newId().toString());

    assertThat(a.body().toString(), a.status(), is(400));
    assertThat(a.code(), is("PAYMENT_RETURN_URL_NOT_ALLOWED"));
    assertThat(intentRows(order), is("0"));
    assertThat(tenders(order), is("0"));
  }

  @Test
  @DisplayName(
      "An order service that is down, or answers nonsense, is 503 PAYMENT_ORDER_LOOKUP_UNAVAILABLE,"
          + " and no intent is opened")
  void aDownOrderServiceIsRefusedAndOpensNothing() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID down = Ids.newId();
    UUID garbled = Ids.newId();
    // The order service errors on one order, and answers another with something that is not an
    // order. (Two failing reads in all: the lookup's breaker trips on a run of them, and a refusal
    // here must not take the next test's reads with it.)
    ORDERS.on("GET", "/orders/" + down, 500, "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}");
    ORDERS.on("GET", "/orders/" + garbled, 200, "{\"data\":{\"channel\":\"ONLINE\"}}");

    for (UUID order : new UUID[] {down, garbled}) {
      Answer a =
          call(
              target,
              "POST",
              "/payments/intents",
              new Caller(tenant, shopper, "CUSTOMER"),
              "{\"orderId\":\"" + order + "\",\"amount\":3.37}",
              Ids.newId().toString());

      assertThat(a.body().toString(), a.status(), is(503));
      assertThat(a.code(), is("PAYMENT_ORDER_LOOKUP_UNAVAILABLE"));
      assertThat("no intent was opened", intentRows(order), is("0"));
      assertThat("no money was recorded", tenders(order), is("0"));
      assertThat("nothing was announced", captures(order), is("0"));
    }
  }

  @Test
  @DisplayName("A webhook for a provider that is not deployed is 404 and records nothing")
  void aWebhookForAProviderNotDeployedIsRefused() {
    String before = scalar(PG, "SELECT count(*) FROM payment.payment_webhook_events");

    Answer a = post(target, "/payments/webhooks/paypal", Caller.owner(Ids.newId()), "{}");

    assertThat(a.status(), is(404));
    assertThat(a.code(), is("PAYMENT_PROVIDER_UNKNOWN"));
    assertThat(scalar(PG, "SELECT count(*) FROM payment.payment_webhook_events"), is(before));
  }

  @Test
  @DisplayName("A webhook that does not verify is 400, with no dispute and no event recorded")
  void aWebhookThatDoesNotVerifyIsRefused() {
    String seen = scalar(PG, "SELECT count(*) FROM payment.payment_webhook_events");
    String disputes =
        scalar(PG, "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentDisputeOpened'");

    Answer a =
        post(
            target,
            "/payments/webhooks/manual",
            Caller.owner(Ids.newId()),
            "{\"id\":\"evt_forged\",\"type\":\"charge.dispute.created\"}");

    assertThat(a.status(), is(400));
    assertThat(a.code(), is("PAYMENT_WEBHOOK_INVALID"));
    assertThat(scalar(PG, "SELECT count(*) FROM payment.payment_webhook_events"), is(seen));
    assertThat(
        scalar(PG, "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentDisputeOpened'"),
        is(disputes));
  }
}
