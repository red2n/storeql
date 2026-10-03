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
import com.storeql.payment.provider.PaymentProviders;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  @DisplayName("An event about an unknown intent is recorded, or it is reprocessed forever")
  void unknownIntentIsStillRecorded() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    assertThat(true, is(true));
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
