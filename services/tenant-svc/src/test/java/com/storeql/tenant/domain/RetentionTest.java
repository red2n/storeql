package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one rule a retention period must pass before the law's floor is even looked at. */
class RetentionTest {

  @Test
  @DisplayName(
      "A period below zero or above the ceiling is refused by name, with or without a floor")
  void aPeriodOutsideTheRangeIsRefused() {
    Retention.Floor floor = new Retention.Floor("TRANSACTIONS", "DE", 2920, "AO", "ten years");
    for (int days : new int[] {-1, Retention.MAX_DAYS + 1}) {
      ApiException none =
          assertThrows(ApiException.class, () -> Retention.requireLawful(days, null));
      assertEquals(400, none.status());
      assertEquals("RETENTION_PERIOD_INVALID", none.code());
      // The range is judged before the floor, so the answer is the same where a floor binds.
      ApiException bound =
          assertThrows(ApiException.class, () -> Retention.requireLawful(days, floor));
      assertEquals("RETENTION_PERIOD_INVALID", bound.code());
    }
  }

  @Test
  @DisplayName("Zero and the ceiling itself are inside the range")
  void theEdgesAreAllowed() {
    assertDoesNotThrow(() -> Retention.requireLawful(0, null));
    assertDoesNotThrow(() -> Retention.requireLawful(Retention.MAX_DAYS, null));
  }
}
