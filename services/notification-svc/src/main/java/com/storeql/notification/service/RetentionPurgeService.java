package com.storeql.notification.service;

import com.storeql.notification.repo.NotificationRepository;
import com.storeql.notification.repo.RetentionRunRepository;
import com.storeql.service.Retention;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * This service's retention purge (21.16): the log of messages sent, older than the period the
 * business set for {@code NOTIFICATION_LOG}, except a held customer's — plus the one row that is
 * nobody's business to schedule: {@link #purgePasswordResets()}.
 */
@ApplicationScoped
public class RetentionPurgeService {

  static final String SERVICE = "notification-svc";
  static final String TOPIC = "storeql.notification.retention-run-completed";

  @Inject RetentionRunRepository repo;
  @Inject Retention retention;
  @Inject NotificationRepository notifications;

  /**
   * How long a password-reset row is kept: a platform rule, since the row belongs to no business
   * and so is on no business's own {@code NOTIFICATION_LOG} schedule.
   */
  @Inject
  @ConfigProperty(name = "storeql.notification.password-reset.retention-days", defaultValue = "30")
  int passwordResetRetentionDays;

  /**
   * Purges one tenant as its schedule says.
   *
   * @return the run, or empty when the business has set no period
   */
  public Optional<Retention.Run> purge(UUID tenantId) {
    return retention.purge(
        tenantId,
        SERVICE,
        Retention.NOTIFICATION_LOG,
        (cutoff, classHeld, sheet, payload) ->
            repo.purgeLog(
                tenantId,
                cutoff,
                classHeld,
                sheet.heldSubjects(Retention.NOTIFICATION_LOG, "CUSTOMER"),
                Retention.announce(TOPIC, tenantId, payload)));
  }

  public List<UUID> tenants() {
    return repo.tenantsWithLog();
  }

  /**
   * The platform's own sweep of password-reset rows: every one older than {@link
   * #passwordResetRetentionDays}, whatever login it was for — never a business's schedule, never
   * held. Run once a day by {@link com.storeql.notification.messaging.RetentionSweeper}, not once
   * per tenant.
   *
   * @return how many rows were deleted
   */
  public int purgePasswordResets() {
    return purgePasswordResets(Instant.now());
  }

  /** As above, from a given moment — so a test can name the cutoff exactly. */
  int purgePasswordResets(Instant now) {
    return notifications.purgePasswordResetsBefore(
        now.minus(Duration.ofDays(passwordResetRetentionDays)));
  }
}
