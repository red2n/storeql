package com.storeql.payment.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.provider.PaymentProvider;
import com.storeql.payment.provider.PaymentProvider.DisputeNotice;
import com.storeql.payment.provider.PaymentProvider.OurIntent;
import com.storeql.payment.provider.PaymentProviders;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The order in which a provider webhook is deduped and applied.
 *
 * <p>The dedupe row used to be committed, in its own transaction, <em>before</em> the effect was
 * applied. A failure in {@code writeCapture} then left that row standing and the capture never made
 * — so the provider's redelivery, which is the one mechanism designed to recover exactly this, was
 * swallowed as "already applied". The money was captured at Stripe and recorded nowhere, silently
 * and permanently.
 *
 * <p>Recording it afterwards is safe because every branch is idempotent, which golden rule #7
 * requires of consumers anyway. These tests pin the ordering rather than the idempotency, because
 * the ordering is the part that was wrong and the part a future edit would most easily undo.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDedupeOrderingTest {

  private static final String PROVIDER = "stripe";
  private static final String EVENT_ID = "evt_test_1";
  private static final String PROVIDER_REF = "pi_test_1";

  @Mock PaymentIntentRepository repo;
  @Mock PaymentProviders providers;
  @Mock PaymentProvider provider;
  @Mock DisputeService disputes;
  @InjectMocks PaymentIntentService service;

  private final UnaryOperator<String> header = name -> "sig";

  @BeforeEach
  void wireProvider() {
    lenient().when(providers.forName(PROVIDER)).thenReturn(provider);
    lenient().when(provider.name()).thenReturn(PROVIDER);
    lenient().when(provider.signatureHeaderName()).thenReturn("Stripe-Signature");
  }

  private void deliver(String status) {
    when(provider.verifyWebhook(any(), anyString()))
        .thenReturn(
            new PaymentProvider.WebhookEvent(
                EVENT_ID, "type", PROVIDER_REF, status, new BigDecimal("10.00"), null, null));
    service.handleWebhook(PROVIDER, "{}".getBytes(), header);
  }

  private PaymentIntent authorizedIntent() {
    return new PaymentIntent(
        Ids.newId(), // id
        Ids.newId(), // tenantId
        Ids.newId(), // orderId
        Ids.newId(), // storeId
        PROVIDER,
        PROVIDER_REF,
        new BigDecimal("10.00"), // amount
        null, // capturedAmount
        "GBP",
        PaymentIntent.STATUS_AUTHORIZED,
        null, // nextActionUrl
        null, // failureCode
        null, // failureMessage
        null, // paymentId
        null, // idempotencyKey
        Instant.now(),
        Instant.now());
  }

  @Test
  @DisplayName("A failing capture leaves NO dedupe row, so the provider's redelivery still works")
  void aFailedEffectIsNotRecordedAsSeen() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF))
        .thenReturn(authorizedIntent());
    when(repo.captureGuarded(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("database went away mid-capture"));

    assertThrows(RuntimeException.class, () -> deliver(PaymentIntent.STATUS_CAPTURED));

    // The whole finding, in one line: had this been called, the money would be gone for good.
    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("A successful capture IS recorded, so the next redelivery is skipped")
  void aSucceededEffectIsRecorded() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF))
        .thenReturn(authorizedIntent());

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, times(1)).captureGuarded(any(), any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName("An event already seen applies nothing at all")
  void alreadySeenIsSkipped() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(true);

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, never()).findByProviderRefAcrossTenants(anyString(), anyString());
  }

  @Test
  @DisplayName(
      "An event that matched no intent, naming none of ours, is recorded, or it is reprocessed"
          + " forever")
  void unknownIntentIsStillRecorded() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    assertThat(true, is(true));
  }

  /** What the service said, formatted as a log line reads, while {@code run} ran. */
  private static List<LogRecord> logged(Runnable run) {
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
      run.run();
    } finally {
      log.removeHandler(h);
    }
    return seen;
  }

  private static String line(LogRecord r) {
    return new SimpleFormatter().formatMessage(r);
  }

  @Test
  @DisplayName(
      "An event about a reference no intent holds, that names no intent of ours, is said aloud and"
          + " recorded, never dropped in silence: it is about something this service never opened")
  void anEventForAReferenceNoIntentHoldsIsSaidAloud() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);

    List<LogRecord> logs = logged(() -> deliver(PaymentIntent.STATUS_CAPTURED));

    assertThat(
        "a warning an operator will see, naming the provider, the event and the reference: "
            + logs.stream().map(WebhookDedupeOrderingTest::line).toList(),
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel().intValue() >= Level.WARNING.intValue()
                        && line(r).contains(PROVIDER)
                        && line(r).contains(EVENT_ID)
                        && line(r).contains(PROVIDER_REF)),
        is(true));
    // Said aloud and still recorded: the provider must stop redelivering it.
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "An event of a kind no intent acts on, about a reference nobody holds, is only recorded")
  void anEventNoIntentWouldActOnIsNotAWarning() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);

    List<LogRecord> logs = logged(() -> deliver(PaymentIntent.STATUS_REQUIRES_ACTION));

    assertThat(
        logs.stream().map(WebhookDedupeOrderingTest::line).toList().toString(),
        logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
        is(true));
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName("An event about an intent that has already finished is ordinary, and not a warning")
  void anEventForAFinishedIntentIsNotAWarning() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(capturedIntent());

    List<LogRecord> logs = logged(() -> deliver(PaymentIntent.STATUS_CAPTURED));

    assertThat(
        logs.stream().map(WebhookDedupeOrderingTest::line).toList().toString(),
        logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
        is(true));
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  // ── an event is matched by our own intent id when no reference holds it ────

  private void deliverNamed(String status, OurIntent ours) {
    when(provider.verifyWebhook(any(), anyString()))
        .thenReturn(
            new PaymentProvider.WebhookEvent(
                EVENT_ID,
                "type",
                PROVIDER_REF,
                status,
                new BigDecimal("10.00"),
                null,
                null,
                null,
                ours));
    service.handleWebhook(PROVIDER, "{}".getBytes(), header);
  }

  /** An intent as it stands before the provider's answer to its authorisation is recorded. */
  private static PaymentIntent unreferenced(UUID tenant, UUID id) {
    return intentOf(tenant, id, PROVIDER, null, PaymentIntent.STATUS_REQUIRES_ACTION, null, null);
  }

  private static PaymentIntent intentOf(
      UUID tenant,
      UUID id,
      String providerName,
      String ref,
      String status,
      String failureCode,
      UUID paymentId) {
    return new PaymentIntent(
        id,
        tenant,
        Ids.newId(),
        Ids.newId(),
        providerName,
        ref,
        new BigDecimal("10.00"),
        PaymentIntent.STATUS_CAPTURED.equals(status) ? new BigDecimal("10.00") : null,
        "GBP",
        status,
        null,
        failureCode,
        null,
        paymentId,
        null,
        Instant.now(),
        Instant.now());
  }

  private static PaymentIntent holding(PaymentIntent a, String ref) {
    return intentOf(
        a.tenantId(), a.id(), a.provider(), ref, a.status(), a.failureCode(), a.paymentId());
  }

  @Test
  @DisplayName(
      "A capture that arrives before the intent has its provider reference is applied through our"
          + " own id, and the intent learns the reference")
  void aCaptureBeforeTheReferenceIsAppliedThroughOurId() {
    UUID tenant = Ids.newId();
    UUID id = Ids.newId();
    PaymentIntent before = unreferenced(tenant, id);
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, id)).thenReturn(before);
    when(repo.adoptProviderRef(tenant, id, PROVIDER_REF)).thenReturn(holding(before, PROVIDER_REF));

    deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(tenant, id));

    ArgumentCaptor<com.storeql.payment.domain.Domain.PaymentTender> tender =
        ArgumentCaptor.forClass(com.storeql.payment.domain.Domain.PaymentTender.class);
    InOrder order = inOrder(repo);
    order.verify(repo).adoptProviderRef(tenant, id, PROVIDER_REF);
    order.verify(repo).captureGuarded(eq(tenant), eq(id), tender.capture(), any());
    order.verify(repo).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    assertThat(
        "the charge is the tender's reference", tender.getValue().reference(), is(PROVIDER_REF));
    assertThat(tender.getValue().tenantId(), is(tenant));
  }

  @Test
  @DisplayName("An authorisation that arrives before the reference is applied, with the reference")
  void anAuthorisationBeforeTheReferenceIsAppliedThroughOurId() {
    UUID tenant = Ids.newId();
    UUID id = Ids.newId();
    PaymentIntent before = unreferenced(tenant, id);
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, id)).thenReturn(before);
    when(repo.adoptProviderRef(tenant, id, PROVIDER_REF)).thenReturn(holding(before, PROVIDER_REF));

    deliverNamed(PaymentIntent.STATUS_AUTHORIZED, new OurIntent(tenant, id));

    verify(repo).markAuthorized(tenant, id, PROVIDER_REF, PaymentIntent.STATUS_AUTHORIZED, null);
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName(
      "An event of a kind that changes nothing still teaches the intent its reference: the next"
          + " event, and a capture, find the provider's object by it")
  void anEventThatChangesNothingStillTeachesTheReference() {
    UUID tenant = Ids.newId();
    UUID id = Ids.newId();
    PaymentIntent before = unreferenced(tenant, id);
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, id)).thenReturn(before);
    when(repo.adoptProviderRef(tenant, id, PROVIDER_REF)).thenReturn(holding(before, PROVIDER_REF));

    deliverNamed(PaymentIntent.STATUS_REQUIRES_ACTION, new OurIntent(tenant, id));

    verify(repo).adoptProviderRef(tenant, id, PROVIDER_REF);
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, never()).markAuthorized(any(), any(), any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName(
      "An event for an intent of ours that is not known yet is answered non-2xx and NOT recorded as"
          + " seen, so the provider delivers it again")
  void anUnknownIntentOfOursIsNotRecordedAndIsRefused() {
    UUID tenant = Ids.newId();
    UUID id = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, id)).thenReturn(null);

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(tenant, id)));

    assertThat(e.status(), is(404));
    assertThat(e.code(), is("PAYMENT_WEBHOOK_INTENT_UNKNOWN"));
    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "An event naming another business's intent applies to nothing of this one: it is looked up in"
          + " the business the event names, found nowhere, and refused")
  void anotherBusinessesIntentIsNeverApplied() {
    UUID theirs = Ids.newId();
    UUID ours = Ids.newId();
    UUID theirIntent = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    // The intent exists, in business `theirs` (so a lookup there would find it, and the test fails
    // by it); the event claims it for business `ours`, where there is none.
    lenient()
        .when(repo.findById(theirs, theirIntent))
        .thenReturn(unreferenced(theirs, theirIntent));
    when(repo.findById(ours, theirIntent)).thenReturn(null);

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(ours, theirIntent)));

    assertThat(e.code(), is("PAYMENT_WEBHOOK_INTENT_UNKNOWN"));
    verify(repo).findById(ours, theirIntent);
    verify(repo, never()).findById(theirs, theirIntent);
    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("A row that comes back for another business than the one asked is refused too")
  void aRowOfAnotherBusinessIsRefusedEvenIfTheRepositoryReturnsIt() {
    UUID asked = Ids.newId();
    UUID id = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(asked, id)).thenReturn(unreferenced(Ids.newId(), id));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(asked, id)));

    assertThat(e.code(), is("PAYMENT_WEBHOOK_INTENT_UNKNOWN"));
    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "An event whose reference belongs to one intent and whose metadata names another applies to"
          + " neither: it is said aloud and recorded, since delivering it again changes nothing")
  void aContradictionBetweenReferenceAndMetadataAppliesToNeither() {
    PaymentIntent holder = authorizedIntent();
    UUID otherBusiness = Ids.newId();
    UUID otherIntent = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(holder);

    List<LogRecord> logs =
        logged(
            () ->
                deliverNamed(
                    PaymentIntent.STATUS_CAPTURED, new OurIntent(otherBusiness, otherIntent)));

    assertThat(
        "a warning an operator will see: "
            + logs.stream().map(WebhookDedupeOrderingTest::line).toList(),
        logs.stream().anyMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
        is(true));
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, never()).markAuthorized(any(), any(), any(), any(), any());
    verify(repo, never()).markTerminal(any(), any(), any(), any(), any());
    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName(
      "A contradiction inside one business names that business and the other intent, and does not"
          + " say it is another business's")
  void aContradictionInOneBusinessIsSaidAsItIs() {
    PaymentIntent holder = authorizedIntent();
    UUID otherIntent = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(holder);

    List<LogRecord> logs =
        logged(
            () ->
                deliverNamed(
                    PaymentIntent.STATUS_CAPTURED, new OurIntent(holder.tenantId(), otherIntent)));

    String said = logs.stream().map(WebhookDedupeOrderingTest::line).toList().toString();
    assertThat(
        said,
        said.contains(otherIntent.toString()) && said.contains(holder.tenantId().toString()),
        is(true));
    assertThat(said, said.contains("another business"), is(false));
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName(
      "Metadata that agrees with the reference changes nothing: the intent the reference names is"
          + " the one applied")
  void metadataThatAgreesWithTheReferenceIsTheOrdinaryPath() {
    PaymentIntent holder = authorizedIntent();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(holder);

    deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(holder.tenantId(), holder.id()));

    verify(repo, never()).findById(any(), any());
    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, times(1)).captureGuarded(eq(holder.tenantId()), eq(holder.id()), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName(
      "An intent that already holds another reference, or was opened with another provider, is not"
          + " the one the event is about: nothing is applied or adopted, and it is recorded")
  void anIntentOfAnotherReferenceOrProviderIsNotApplied() {
    UUID tenant = Ids.newId();
    UUID withOther = Ids.newId();
    UUID withProvider = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, withOther))
        .thenReturn(
            intentOf(
                tenant,
                withOther,
                PROVIDER,
                "pi_another",
                PaymentIntent.STATUS_AUTHORIZED,
                null,
                null));
    when(repo.findById(tenant, withProvider))
        .thenReturn(
            intentOf(
                tenant,
                withProvider,
                "MANUAL",
                null,
                PaymentIntent.STATUS_REQUIRES_ACTION,
                null,
                null));

    deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(tenant, withOther));
    deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(tenant, withProvider));

    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, times(2)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName("An event naming no intent of ours, with a reference nobody holds, is as it was")
  void anEventNamingNoIntentOfOursIsAsItWas() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);

    deliverNamed(PaymentIntent.STATUS_CAPTURED, null);

    verify(repo, never()).findById(any(), any());
    verify(repo, never()).adoptProviderRef(any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "A hold the provider placed for an authorisation that failed here is found by our id, left"
          + " as the failed attempt it is, and said aloud: nobody's money is accounted for")
  void aHoldForAFailedAttemptIsFoundAndSaidAloud() {
    UUID tenant = Ids.newId();
    UUID id = Ids.newId();
    PaymentIntent failed =
        intentOf(tenant, id, PROVIDER, null, PaymentIntent.STATUS_FAILED, "PROVIDER_ERROR", null);
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, id)).thenReturn(failed);
    when(repo.adoptProviderRef(tenant, id, PROVIDER_REF)).thenReturn(holding(failed, PROVIDER_REF));

    List<LogRecord> logs =
        logged(() -> deliverNamed(PaymentIntent.STATUS_AUTHORIZED, new OurIntent(tenant, id)));

    assertThat(
        logs.stream().map(WebhookDedupeOrderingTest::line).toList().toString(),
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel().intValue() >= Level.WARNING.intValue()
                        && line(r).contains(id.toString())
                        && line(r).contains(PROVIDER_REF)),
        is(true));
    verify(repo).adoptProviderRef(tenant, id, PROVIDER_REF);
    verify(repo, never()).markAuthorized(any(), any(), any(), any(), any());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName("A failure of the repository while matching leaves the event unrecorded")
  void aFailureWhileMatchingIsNotRecorded() {
    UUID tenant = Ids.newId();
    UUID id = Ids.newId();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);
    when(repo.findById(tenant, id)).thenReturn(unreferenced(tenant, id));
    when(repo.adoptProviderRef(tenant, id, PROVIDER_REF))
        .thenThrow(new RuntimeException("database went away"));

    assertThrows(
        RuntimeException.class,
        () -> deliverNamed(PaymentIntent.STATUS_CAPTURED, new OurIntent(tenant, id)));

    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
  }

  private PaymentIntent capturedIntent() {
    PaymentIntent a = authorizedIntent();
    return new PaymentIntent(
        a.id(),
        a.tenantId(),
        a.orderId(),
        a.storeId(),
        PROVIDER,
        PROVIDER_REF,
        a.amount(),
        a.amount(),
        a.currency(),
        PaymentIntent.STATUS_CAPTURED,
        null,
        null,
        null,
        Ids.newId(),
        null,
        a.createdAt(),
        a.updatedAt());
  }

  private DisputeNotice deliverDispute() {
    DisputeNotice notice =
        new DisputeNotice(
            "dp_test_1",
            DisputeNotice.PHASE_OPENED,
            null,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            "GBP",
            "FRAUDULENT",
            "10.4",
            Instant.now().plusSeconds(864_000));
    when(provider.verifyWebhook(any(), anyString()))
        .thenReturn(
            new PaymentProvider.WebhookEvent(
                EVENT_ID, "charge.dispute.created", PROVIDER_REF, null, null, null, null, notice));
    service.handleWebhook(PROVIDER, "{}".getBytes(), header);
    return notice;
  }

  @Test
  @DisplayName("A dispute is about a payment long finished: it is applied all the same, then seen")
  void aDisputeReachesTheCaseFileThoughThePaymentIsFinished() {
    PaymentIntent captured = capturedIntent();
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(captured);

    DisputeNotice notice = deliverDispute();

    // The "already finished" short-cut would have swallowed every chargeback there will ever be.
    InOrder order = inOrder(disputes, repo);
    order.verify(disputes).fromProvider(PROVIDER, captured, notice);
    order.verify(repo).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "charge.dispute.created");
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
  }

  @Test
  @DisplayName("A dispute that could not be filed is NOT recorded as seen: the redelivery files it")
  void aDisputeThatFailedIsDeliveredAgain() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(capturedIntent());
    doThrow(new RuntimeException("database went away mid-dispute"))
        .when(disputes)
        .fromProvider(eq(PROVIDER), any(), any());

    assertThrows(RuntimeException.class, this::deliverDispute);

    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("A dispute already seen opens nothing twice")
  void aDisputeAlreadySeenIsSkipped() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(true);

    deliverDispute();

    verify(disputes, never()).fromProvider(anyString(), any(), any());
  }

  @Test
  @DisplayName("A webhook for a provider that is not deployed is refused and records nothing")
  void aWebhookForAProviderNotDeployedIsRefused() {
    when(providers.forName("paypal")).thenReturn(null);

    ApiException e =
        assertThrows(
            ApiException.class, () -> service.handleWebhook("paypal", "{}".getBytes(), header));

    assertThat(e.status(), is(404));
    assertThat(e.code(), is("PAYMENT_PROVIDER_UNKNOWN"));
    verify(repo, never()).hasSeenWebhook(anyString(), anyString());
    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
    verify(disputes, never()).fromProvider(anyString(), any(), any());
  }

  @Test
  @DisplayName("A webhook whose signature does not verify is a 400 and touches nothing")
  void aWebhookThatDoesNotVerifyIsRefused() {
    when(provider.verifyWebhook(any(), any()))
        .thenThrow(new PaymentProvider.ProviderException("bad signature", false, null));

    ApiException e =
        assertThrows(
            ApiException.class, () -> service.handleWebhook(PROVIDER, "{}".getBytes(), header));

    assertThat(e.status(), is(400));
    assertThat(e.code(), is("PAYMENT_WEBHOOK_INVALID"));
    verify(repo, never()).hasSeenWebhook(anyString(), anyString());
    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(disputes, never()).fromProvider(anyString(), any(), any());
  }
}
