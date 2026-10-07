package com.storeql.payment.provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.provider.PaymentProvider.WebhookEvent;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.payment.service.DisputeService;
import com.storeql.payment.service.PaymentIntentService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What a Stripe event about another object than a PaymentIntent (a charge, whose id is {@code
 * ch_…}) does to a payment intent, and whether the service warns about it.
 *
 * <p>The driver gives such an event no intent of ours ({@code ours} is null) and no intent status,
 * and its reference is not an intent's, so nothing is applied to anything and it is recorded as
 * seen. That is ordinary traffic, so it is not logged as an event that should have moved an intent;
 * an event about a PaymentIntent that no intent holds still is.
 */
@ExtendWith(MockitoExtension.class)
class StripeEventsOfOtherObjectsTest {

  private static final String EVENT_ID = "evt_1";

  @Mock PaymentIntentRepository repo;
  @Mock PaymentProviders providers;
  @Mock PaymentProvider provider;
  @Mock DisputeService disputes;
  @InjectMocks PaymentIntentService service;

  private final UnaryOperator<String> header = name -> "sig";

  @BeforeEach
  void wireProvider() {
    lenient().when(providers.forName("stripe")).thenReturn(provider);
    lenient().when(provider.name()).thenReturn("stripe");
    lenient().when(provider.signatureHeaderName()).thenReturn("Stripe-Signature");
    lenient().when(repo.hasSeenWebhook(anyString(), anyString())).thenReturn(false);
  }

  private static byte[] body(String type, String objectJson) {
    return ("{\"id\":\""
            + EVENT_ID
            + "\",\"type\":\""
            + type
            + "\",\"data\":{\"object\":"
            + objectJson
            + "}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** A charge as Stripe sends it, carrying the metadata of the intent it paid. */
  private static String charge(String status) {
    return "{\"id\":\"ch_1\",\"status\":\""
        + status
        + "\",\"currency\":\"gbp\",\"payment_intent\":\"pi_1\",\"metadata\":{\"intentId\":\""
        + Ids.newId()
        + "\",\"tenantId\":\""
        + Ids.newId()
        + "\"}}";
  }

  private static String paymentIntent(String status) {
    return "{\"id\":\"pi_1\",\"status\":\"" + status + "\",\"currency\":\"gbp\"}";
  }

  /** The service handling what the real driver makes of {@code rawBody}, and what it logged. */
  private List<LogRecord> deliver(byte[] rawBody) {
    WebhookEvent event = StripePaymentProvider.parseEvent(rawBody);
    when(provider.verifyWebhook(any(), anyString())).thenReturn(event);
    Logger log = Logger.getLogger(PaymentIntentService.class.getName());
    List<LogRecord> seen = new CopyOnWriteArrayList<>();
    Handler h =
        new Handler() {
          @Override
          public void publish(LogRecord r) {
            seen.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    log.addHandler(h);
    try {
      service.handleWebhook("stripe", rawBody, header);
    } finally {
      log.removeHandler(h);
    }
    return seen;
  }

  private static List<String> warnings(List<LogRecord> logs) {
    return logs.stream()
        .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
        .map(r -> new SimpleFormatter().formatMessage(r))
        .toList();
  }

  @Test
  @DisplayName("An event about a charge implies no status for an intent, whatever its own status")
  void aChargeEventImpliesNoIntentStatus() {
    for (String type : List.of("charge.succeeded", "charge.refunded", "charge.captured")) {
      assertEquals(
          PaymentIntent.STATUS_REQUIRES_ACTION,
          StripePaymentProvider.parseEvent(body(type, charge("succeeded"))).status(),
          type);
    }
    assertEquals(
        PaymentIntent.STATUS_REQUIRES_ACTION,
        StripePaymentProvider.parseEvent(body("charge.failed", charge("failed"))).status());
  }

  @Test
  @DisplayName("An event about a PaymentIntent still implies its status")
  void aPaymentIntentEventStillImpliesItsStatus() {
    assertEquals(
        PaymentIntent.STATUS_CAPTURED,
        StripePaymentProvider.parseEvent(
                body("payment_intent.succeeded", paymentIntent("succeeded")))
            .status());
  }

  @Test
  @DisplayName("A charge event of our own payment is recorded and not warned about")
  void aChargeEventIsRecordedWithoutAWarning() {
    for (String type : List.of("charge.succeeded", "charge.refunded")) {
      List<LogRecord> logs = deliver(body(type, charge("succeeded")));

      assertThat(type + " logged " + warnings(logs), warnings(logs).isEmpty(), is(true));
    }
    verify(repo, times(2)).markWebhookSeenIfNew(eq("stripe"), eq(EVENT_ID), anyString());
    verify(repo, never()).findById(any(), any());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  @Test
  @DisplayName("A PaymentIntent event no intent holds, naming none of ours, is still a warning")
  void anUnmatchedPaymentIntentEventStillWarns() {
    List<LogRecord> logs = deliver(body("payment_intent.succeeded", paymentIntent("succeeded")));

    assertThat(
        "a warning naming the reference: " + warnings(logs),
        warnings(logs).stream().anyMatch(w -> w.contains("pi_1") && w.contains(EVENT_ID)),
        is(true));
    verify(repo, times(1)).markWebhookSeenIfNew("stripe", EVENT_ID, "payment_intent.succeeded");
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }
}
