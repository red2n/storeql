package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.notification.repo.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The platform's own password-reset purge computes its cutoff from {@code
 * storeql.notification.password-reset.retention-days} and asks {@link NotificationRepository} to
 * delete exactly what is older than it — no Mockito, a tiny capturing repository stands in for
 * Postgres, same as {@code OnceRepo} does for {@code Notifier}.
 */
class RetentionPurgeServiceTest {

  private static final class CapturingRepository extends NotificationRepository {
    Instant lastCutoff;
    int toReturn;

    @Override
    public int purgePasswordResetsBefore(Instant cutoff) {
      this.lastCutoff = cutoff;
      return toReturn;
    }
  }

  @Test
  void theDefaultThirtyDayPeriodCutsOffThirtyDaysBeforeNow() {
    RetentionPurgeService service = new RetentionPurgeService();
    CapturingRepository repo = new CapturingRepository();
    repo.toReturn = 3;
    service.notifications = repo;
    service.passwordResetRetentionDays = 30;

    Instant now = Instant.parse("2026-09-26T12:00:00Z");
    int purged = service.purgePasswordResets(now);

    assertEquals(now.minus(Duration.ofDays(30)), repo.lastCutoff);
    assertEquals(Instant.parse("2026-08-27T12:00:00Z"), repo.lastCutoff);
    assertEquals(3, purged);
  }

  @Test
  void aConfiguredPeriodOtherThanTheDefaultChangesTheCutoff() {
    RetentionPurgeService service = new RetentionPurgeService();
    CapturingRepository repo = new CapturingRepository();
    service.notifications = repo;
    service.passwordResetRetentionDays = 7;

    service.purgePasswordResets(Instant.parse("2026-09-26T12:00:00Z"));

    assertEquals(Instant.parse("2026-09-19T12:00:00Z"), repo.lastCutoff);
  }
}
