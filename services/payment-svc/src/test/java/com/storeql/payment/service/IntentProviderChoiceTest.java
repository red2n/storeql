package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.client.OrderClient;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.dto.Dtos.CreatePaymentIntentRequest;
import com.storeql.payment.provider.PaymentProvider;
import com.storeql.payment.provider.PaymentProviders;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The provider an intent is recorded under is the one that authorises it (22.8): a sandbox's intent
 * is the MANUAL provider's and is authorised by it, so nothing a sandbox does places a hold on a
 * real card however the stack is configured; a live business's goes to the configured one.
 */
class IntentProviderChoiceTest {

  private static final UUID LIVE = Ids.newId();
  private static final UUID SANDBOX = Ids.newId();

  private static PaymentProvider named(String name) {
    PaymentProvider p = mock(PaymentProvider.class);
    when(p.name()).thenReturn(name);
    when(p.authorize(any()))
        .thenReturn(new PaymentProvider.Authorization("ref-" + name, "AUTHORIZED", null));
    return p;
  }

  private static TenantProfiles profiles() {
    return TenantProfiles.forTest(
        tenantId ->
            Optional.of(
                "{\"data\":{\"id\":\""
                    + tenantId
                    + "\",\"currency\":\"KWD\",\"country\":\"KW\",\"mode\":\""
                    + (SANDBOX.equals(tenantId) ? "SANDBOX" : "LIVE")
                    + "\"}}"),
        Clock.systemUTC());
  }

  private static PaymentIntent opened(UUID tenant, PaymentProvider stripe, PaymentProvider manual) {
    PaymentIntentService svc = new PaymentIntentService();
    svc.providers = PaymentProviders.forTest(List.of(stripe, manual), "STRIPE", profiles());
    svc.profiles = profiles();
    svc.repo = mock(PaymentIntentRepository.class);
    when(svc.repo.create(any())).thenAnswer(inv -> inv.getArgument(0));
    svc.guard = mock(OrderPaymentGuard.class);
    UUID order = Ids.newId();
    when(svc.guard.verifyOnlineClaim(eq(tenant), eq(order), any(), any()))
        .thenReturn(
            new OrderPaymentGuard.VerifiedOrder(
                new OrderClient.OrderInfo(
                    null, null, "ONLINE", new BigDecimal("1.120"), "PENDING", null, "KWD"),
                null));
    TenantContext ctx = mock(TenantContext.class);
    when(ctx.requireTenantId()).thenReturn(tenant);
    svc.create(
        new CreatePaymentIntentRequest(order.toString(), new BigDecimal("1.120"), null, null),
        ctx,
        Ids.newId().toString());
    ArgumentCaptor<PaymentIntent> row = ArgumentCaptor.forClass(PaymentIntent.class);
    verify(svc.repo).create(row.capture());
    return row.getValue();
  }

  @Test
  @DisplayName("A sandbox's intent is MANUAL's and MANUAL authorises it: Stripe is never asked")
  void aSandboxIsAuthorisedByTheProviderItIsRecordedUnder() {
    PaymentProvider stripe = named("STRIPE");
    PaymentProvider manual = named(PaymentIntent.PROVIDER_MANUAL);
    PaymentIntent intent = opened(SANDBOX, stripe, manual);
    assertEquals(PaymentIntent.PROVIDER_MANUAL, intent.provider());
    verify(manual).authorize(any());
    verify(stripe, never()).authorize(any());
  }

  @Test
  @DisplayName("A live business's intent is the configured provider's, and it authorises it")
  void aLiveBusinessIsAuthorisedByTheConfiguredProvider() {
    PaymentProvider stripe = named("STRIPE");
    PaymentProvider manual = named(PaymentIntent.PROVIDER_MANUAL);
    PaymentIntent intent = opened(LIVE, stripe, manual);
    assertEquals("STRIPE", intent.provider());
    verify(stripe).authorize(any());
    verify(manual, never()).authorize(any());
  }
}
