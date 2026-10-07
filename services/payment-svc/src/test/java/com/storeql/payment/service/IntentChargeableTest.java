package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.storeql.payment.provider.StripePaymentProvider;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.test.DriverStub;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An online card amount Stripe cannot charge exactly — a dinar whose last fils is not 0, Stripe
 * charging dinars in tens of fils — is a pricing fact, not an outage: refused 422 {@code
 * PAYMENT_AMOUNT_NOT_CHARGEABLE} naming the nearest amounts Stripe can charge, before an intent is
 * recorded or Stripe is asked. It used to be {@code 502 PAYMENT_PROVIDER_UNAVAILABLE}, which the
 * storefront shows as "payments are down".
 */
class IntentChargeableTest {

  private DriverStub stripe;
  private PaymentIntentService svc;
  private final UUID tenant = Ids.newId();
  private final AtomicReference<PaymentIntent> created = new AtomicReference<>();

  @BeforeEach
  void wire() throws Exception {
    stripe =
        DriverStub.start(
            r -> DriverStub.Reply.json(200, "{\"id\":\"pi_1\",\"status\":\"requires_capture\"}"));
    // The driver's settings are package-private to its own package: set as configuration would.
    StripePaymentProvider provider = new StripePaymentProvider();
    set(provider, "secretKeyConfig", Optional.of("key-" + Ids.newId()));
    set(provider, "webhookSecretConfig", Optional.empty());
    Method init = StripePaymentProvider.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(provider);
    set(provider, "apiBase", stripe.url());
    PaymentProvider manual = mock(PaymentProvider.class);
    when(manual.name()).thenReturn(PaymentIntent.PROVIDER_MANUAL);

    TenantProfiles profiles =
        TenantProfiles.forTest(
            id ->
                Optional.of(
                    "{\"data\":{\"id\":\""
                        + id
                        + "\",\"currency\":\"KWD\",\"country\":\"KW\",\"mode\":\"LIVE\"}}"),
            Clock.systemUTC());
    svc = new PaymentIntentService();
    svc.providers = PaymentProviders.forTest(List.of(provider, manual), "STRIPE", profiles);
    svc.profiles = profiles;
    svc.repo = mock(PaymentIntentRepository.class);
    when(svc.repo.create(any()))
        .thenAnswer(
            inv -> {
              created.set(inv.getArgument(0));
              return created.get();
            });
    when(svc.repo.findById(any(), any())).thenAnswer(inv -> created.get());
    svc.guard = mock(OrderPaymentGuard.class);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  @AfterEach
  void stop() {
    stripe.close();
  }

  private PaymentIntent open(String amount) {
    UUID order = Ids.newId();
    when(svc.guard.verifyOnlineClaim(eq(tenant), eq(order), any(), any()))
        .thenReturn(
            new OrderPaymentGuard.VerifiedOrder(
                new OrderClient.OrderInfo(
                    null, null, "ONLINE", new BigDecimal(amount), "PENDING", null, "KWD"),
                null));
    TenantContext ctx = mock(TenantContext.class);
    when(ctx.requireTenantId()).thenReturn(tenant);
    return svc.create(
        new CreatePaymentIntentRequest(order.toString(), new BigDecimal(amount), null, null),
        ctx,
        Ids.newId().toString());
  }

  @Test
  @DisplayName(
      "KWD 1.125 is refused 422 naming 1.120 and 1.130, with no intent recorded and Stripe never"
          + " asked")
  void anAmountStripeCannotChargeIsTheCustomersToKnow() {
    ApiException e = assertThrows(ApiException.class, () -> open("1.125"));
    assertEquals(422, e.status());
    assertEquals("PAYMENT_AMOUNT_NOT_CHARGEABLE", e.code());
    assertEquals(List.of("currency=KWD;chargeableBelow=1.120;chargeableAbove=1.130"), e.details());
    assertTrue(
        e.getMessage().contains("1.120") && e.getMessage().contains("1.130"), e.getMessage());
    verify(svc.repo, never()).create(any());
    verify(svc.repo, never()).markTerminal(any(), any(), anyString(), anyString(), anyString());
    assertEquals(0, stripe.count(), "Stripe was never asked");
  }

  @Test
  @DisplayName("An amount below the smallest Stripe charges names only the one above")
  void belowTheSmallestNamesOnlyAbove() {
    ApiException e = assertThrows(ApiException.class, () -> open("0.005"));
    assertEquals(422, e.status());
    assertEquals(List.of("currency=KWD;chargeableAbove=0.010"), e.details());
    assertEquals(0, stripe.count());
  }

  @Test
  @DisplayName("What Stripe can charge is opened as before, in fils")
  void aChargeableAmountIsOpened() {
    PaymentIntent opened = open("1.120");
    assertEquals("STRIPE", opened.provider());
    assertEquals(1, stripe.count());
    assertEquals("1120", stripe.last().form().get("amount").get(0));
  }
}
