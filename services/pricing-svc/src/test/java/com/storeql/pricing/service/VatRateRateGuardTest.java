package com.storeql.pricing.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.VatRate;
import com.storeql.pricing.dto.Dtos.CreateVatRateRequest;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A VAT rate is a fraction, so one above 1 is refused and nothing is written. The request's own
 * validation refuses it first over HTTP ({@code VatRateIT}); this holds the service to the same
 * rule for any caller that does not come through that boundary.
 */
class VatRateRateGuardTest {

  private static final UUID TENANT = Ids.newId();

  private final List<String> writes = new ArrayList<>();

  private final PricingService svc = new PricingService();

  VatRateRateGuardTest() {
    svc.repo =
        new PricingRepository() {
          @Override
          public Optional<VatRate> findVatRate(UUID tenantId, String code) {
            return Optional.of(
                new VatRate(
                    Ids.newId(),
                    tenantId,
                    code,
                    "Standard",
                    new BigDecimal("0.20"),
                    false,
                    null,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null,
                    Instant.now()));
          }

          @Override
          public VatRate createVatRate(VatRate r) {
            writes.add("createVatRate");
            return r;
          }

          @Override
          public VatRate updateVatRate(VatRate r) {
            writes.add("updateVatRate");
            return r;
          }
        };
  }

  private static CreateVatRateRequest rate(String rate) {
    return new CreateVatRateRequest(
        "T9", "Too much", new BigDecimal(rate), false, null, "2026-01-01T00:00:00Z");
  }

  private static TenantContext ctx() {
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        return TENANT;
      }

      @Override
      public UUID tenantId() {
        return TENANT;
      }

      @Override
      public Set<String> roles() {
        return Set.of("OWNER");
      }

      @Override
      public Set<UUID> storeIds() {
        return Set.of();
      }

      @Override
      public void requireAnyRole(String... required) {}

      @Override
      public void requireStoreAccess(UUID storeId) {}

      @Override
      public UUID userId() {
        return null;
      }
    };
  }

  @Test
  @DisplayName("A VAT rate above 1 is refused on create, and nothing is written")
  void aRateAboveOneIsRefusedOnCreate() {
    ApiException refused =
        assertThrows(ApiException.class, () -> svc.createVatRate(rate("1.01"), ctx()));
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is("PRICING_INVALID_RATE"));
    assertThat("nothing was written", writes.isEmpty(), is(true));
  }

  @Test
  @DisplayName("A VAT rate above 1 is refused on update, and the stored rate is left alone")
  void aRateAboveOneIsRefusedOnUpdate() {
    ApiException refused =
        assertThrows(ApiException.class, () -> svc.updateVatRate(ctx(), "T9", rate("20")));
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is("PRICING_INVALID_RATE"));
    assertThat("nothing was written", writes.isEmpty(), is(true));
  }
}
