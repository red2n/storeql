package com.storeql.notification.messaging;

import com.storeql.notification.service.RetentionPurgeService;
import com.storeql.service.Retention;
import com.storeql.service.RetentionSweeperBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The daily purge of the notification log (21.16), tenant by tenant — plus one platform-wide step
 * on the same schedule: password-reset rows, which belong to no tenant and so are on no tenant's
 * own schedule ({@link RetentionPurgeService#purgePasswordResets()}).
 */
@ApplicationScoped
public class RetentionSweeper extends RetentionSweeperBase {

  private static final Logger LOG = System.getLogger(RetentionSweeper.class.getName());

  @Inject RetentionPurgeService service;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @Override
  protected String name() {
    return "notification-retention-sweeper";
  }

  @Override
  protected List<UUID> tenants() {
    return service.tenants();
  }

  @Override
  protected Optional<Retention.Run> purge(UUID tenantId) {
    return service.purge(tenantId);
  }

  /**
   * The tenant-by-tenant sweep, then the platform's own password-reset purge — on the same
   * schedule, but never counted against a tenant, since it is not one.
   */
  @Override
  public void sweepQuietly() {
    super.sweepQuietly();
    try {
      int purged = service.purgePasswordResets();
      if (purged > 0) {
        LOG.log(Level.INFO, "{0}: purged {1} expired password-reset row(s)", name(), purged);
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "{0}: password-reset purge deferred: {1}", name(), e.getMessage());
    }
  }
}
