package com.storeql.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.service.TenantProfiles;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which provider takes a business's money (22.8): the one the deployment configured — except in a
 * sandbox, where it is always the MANUAL provider, so nothing a sandbox does authorises or captures
 * a penny however the stack is configured.
 */
class PaymentProvidersTest {

  private static final UUID LIVE = Ids.parse("01a090ae-611e-7071-8516-000000000101");
  private static final UUID SANDBOX = Ids.parse("01a090ae-611e-7071-8516-000000000102");

  private static PaymentProvider named(String name) {
    PaymentProvider p = mock(PaymentProvider.class);
    when(p.name()).thenReturn(name);
    return p;
  }

  private static TenantProfiles profiles() {
    return TenantProfiles.forTest(
        tenantId ->
            Optional.of(
                "{\"data\":{\"id\":\""
                    + tenantId
                    + "\",\"currency\":\"GBP\",\"country\":\"GB\",\"mode\":\""
                    + (SANDBOX.equals(tenantId) ? "SANDBOX" : "LIVE")
                    + "\"}}"),
        Clock.systemUTC());
  }

  @Test
  @DisplayName(
      "A live business pays through the configured provider; a sandbox through MANUAL, whatever is configured")
  void theSandboxIsAlwaysManual() {
    PaymentProvider stripe = named("STRIPE");
    PaymentProvider manual = named(PaymentIntent.PROVIDER_MANUAL);
    PaymentProviders providers =
        PaymentProviders.forTest(List.of(stripe, manual), "stripe", profiles());

    assertEquals("STRIPE", providers.active().name());
    assertFalse(providers.isManual());
    assertEquals("STRIPE", providers.forTenant(LIVE).name());
    assertEquals(PaymentIntent.PROVIDER_MANUAL, providers.forTenant(SANDBOX).name());
    // A business whose profile cannot be read is not assumed to be a sandbox.
    PaymentProviders blind =
        PaymentProviders.forTest(
            List.of(stripe, manual),
            "STRIPE",
            TenantProfiles.forTest(t -> Optional.empty(), Clock.systemUTC()));
    assertEquals("STRIPE", blind.forTenant(SANDBOX).name());
  }

  @Test
  @DisplayName("A stack on MANUAL is manual for everyone")
  void manualEverywhere() {
    PaymentProviders providers =
        PaymentProviders.forTest(
            List.of(named(PaymentIntent.PROVIDER_MANUAL)),
            PaymentIntent.PROVIDER_MANUAL,
            profiles());
    assertTrue(providers.isManual());
    assertEquals(PaymentIntent.PROVIDER_MANUAL, providers.forTenant(LIVE).name());
    assertEquals(PaymentIntent.PROVIDER_MANUAL, providers.forTenant(SANDBOX).name());
  }

  @Test
  @DisplayName("A provider nobody registered cannot be configured")
  void anUnknownProviderRefusesToStart() {
    assertThrows(
        IllegalStateException.class,
        () ->
            PaymentProviders.forTest(
                List.of(named(PaymentIntent.PROVIDER_MANUAL)), "ADYEN", profiles()));
  }
}
