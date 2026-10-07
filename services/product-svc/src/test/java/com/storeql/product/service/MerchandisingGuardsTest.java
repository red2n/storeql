package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Merchandising;
import com.storeql.product.domain.Merchandising.Fixture;
import com.storeql.product.domain.Merchandising.Planogram;
import com.storeql.product.domain.Merchandising.Reset;
import com.storeql.product.repo.MerchandisingRepository;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the merchandising service refuses before it touches the shelf plan: a facing width outside
 * 1..5000mm, a draft or a reset with no day, a reset called off with no reason. The request's own
 * validation answers the missing ones first over HTTP ({@code MerchandisingIT}); these hold the
 * service to the same rule for any caller that does not come through that boundary, and show that
 * nothing is written when it says no.
 */
class MerchandisingGuardsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID FIXTURE = Ids.newId();

  private final List<String> writes = new ArrayList<>();

  private final MerchandisingService svc = new MerchandisingService();

  MerchandisingGuardsTest() {
    svc.repo =
        new MerchandisingRepository() {
          @Override
          public Optional<Fixture> fixture(UUID tenantId, UUID id) {
            return Optional.of(
                new Fixture(
                    id,
                    tenantId,
                    Ids.newId(),
                    null,
                    "AISLE-1",
                    "Aisle one",
                    "GONDOLA",
                    4,
                    1000,
                    Merchandising.ACTIVE,
                    Instant.now(),
                    Instant.now()));
          }

          @Override
          public boolean setFacingWidth(UUID tenantId, UUID variantId, Integer facingWidthMm) {
            writes.add("setFacingWidth");
            return true;
          }

          @Override
          public Planogram startDraft(
              UUID tenantId, UUID fixtureId, LocalDate effectiveFrom, String note, UUID actorId) {
            writes.add("startDraft");
            return null;
          }

          @Override
          public Reset addReset(Reset reset, UUID actorId) {
            writes.add("addReset");
            return reset;
          }

          @Override
          public boolean moveReset(UUID tenantId, UUID id, String from, String to, String reason) {
            writes.add("moveReset");
            return true;
          }
        };
  }

  private static void assertRefused(ApiException refused, String code) {
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is(code));
  }

  @Test
  @DisplayName("A facing narrower than 1mm or wider than 5000mm is refused and not saved")
  void aFacingOutsideTheRangeIsRefused() {
    for (int mm : new int[] {0, -5, 5001}) {
      assertRefused(
          assertThrows(ApiException.class, () -> svc.setFacingWidth(TENANT, Ids.newId(), mm)),
          "FACING_WIDTH_INVALID");
    }
    assertThat("nothing was saved", writes.isEmpty(), is(true));
  }

  @Test
  @DisplayName("A draft planogram with no day it takes effect is refused and not started")
  void aDraftWithNoDayIsRefused() {
    assertRefused(
        assertThrows(
            ApiException.class, () -> svc.startDraft(TENANT, FIXTURE, null, null, Ids.newId())),
        "PLANOGRAM_DATE_REQUIRED");
    assertThat("nothing was started", writes.isEmpty(), is(true));
  }

  @Test
  @DisplayName("A reset with no day it happens is refused and not planned")
  void aResetWithNoDayIsRefused() {
    assertRefused(
        assertThrows(
            ApiException.class,
            () -> svc.planReset(TENANT, Ids.newId(), "Autumn reset", null, Ids.newId())),
        "RESET_DATE_REQUIRED");
    assertThat("nothing was planned", writes.isEmpty(), is(true));
  }

  @Test
  @DisplayName("A reset is not called off without a reason, and stays planned")
  void aResetIsNotCancelledWithoutAReason() {
    for (String reason : new String[] {null, "", "   "}) {
      assertRefused(
          assertThrows(ApiException.class, () -> svc.cancel(TENANT, Ids.newId(), reason)),
          "RESET_REASON_REQUIRED");
    }
    assertThat("nothing was moved", writes.isEmpty(), is(true));
  }
}
