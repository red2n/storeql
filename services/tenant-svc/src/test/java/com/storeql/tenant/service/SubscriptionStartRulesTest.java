package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.repo.BillingRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two refusals that stand before a business is subscribed. Sign-up is the only caller and it
 * swallows both (a business that exists and is not billed is fixed afterwards), so no HTTP answer
 * ever carries them: they are proved here, where the service is called as it is.
 */
class SubscriptionStartRulesTest {

  private static final LocalDate DAY = LocalDate.parse("2026-10-01");

  private static Tenant tenant(UUID id, UUID planId) {
    return new Tenant(
        id,
        "Shop",
        "Shop Ltd",
        "ACTIVE",
        planId,
        Ids.newId(),
        "GB",
        "GBP",
        Instant.now(),
        Instant.now(),
        null,
        null,
        null,
        null,
        Tenant.MODE_LIVE,
        null);
  }

  private static Subscription anySubscription(UUID tenantId) {
    return new Subscription(
        Ids.newId(),
        tenantId,
        Ids.newId(),
        "ACTIVE",
        null,
        "GBP",
        "MONTHLY",
        DAY,
        DAY.plusMonths(1),
        null,
        null,
        false,
        null,
        null,
        Instant.now(),
        null,
        Instant.now(),
        Instant.now());
  }

  private static SubscriptionService service(Optional<Subscription> held, Optional<Tenant> t) {
    SubscriptionService svc = new SubscriptionService();
    svc.repo =
        new BillingRepository() {
          @Override
          public Optional<Subscription> ofTenant(UUID tenantId) {
            return held;
          }
        };
    svc.tenants =
        new TenantRepository() {
          @Override
          public Optional<Tenant> findTenant(UUID tenantId) {
            return t;
          }
        };
    return svc;
  }

  @Test
  @DisplayName("A business already signed up is refused, not signed up twice")
  void aBusinessAlreadySubscribedIsRefused() {
    UUID id = Ids.newId();
    SubscriptionService svc =
        service(Optional.of(anySubscription(id)), Optional.of(tenant(id, Ids.newId())));

    ApiException e =
        assertThrows(ApiException.class, () -> svc.start(id, DAY, Ids.newId(), "o@example.com"));

    assertEquals(409, e.status());
    assertEquals("SUBSCRIPTION_EXISTS", e.code());
  }

  @Test
  @DisplayName("A business on no plan has nothing to bill and is refused by name")
  void aBusinessOnNoPlanIsRefused() {
    UUID id = Ids.newId();
    SubscriptionService svc = service(Optional.empty(), Optional.of(tenant(id, null)));

    ApiException e =
        assertThrows(ApiException.class, () -> svc.start(id, DAY, Ids.newId(), "o@example.com"));

    assertEquals(409, e.status());
    assertEquals("TENANT_HAS_NO_PLAN", e.code());
  }
}
