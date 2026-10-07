package com.storeql.customer.messaging;

import com.storeql.customer.service.PhoneBackfillService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * The start-up backfill of {@code customers.phone_e164}: once, when this instance comes up, every
 * tenant with a customer row still needing it. Idempotent, and safe to leave unconditional — a
 * tenant whose regions cannot be read this run is simply left for the next one.
 *
 * <p>{@code onStart} forces CDI to instantiate this eagerly (Helidon MP gotcha #9); the work runs
 * in the background so a failure here logs and lets the service start regardless.
 */
@ApplicationScoped
public class PhoneBackfillRunner {

  private static final Logger LOG = System.getLogger(PhoneBackfillRunner.class.getName());

  @Inject PhoneBackfillService backfill;

  /**
   * Starts the work on a background virtual thread once the application is up: it can take a while
   * with many tenants (and calls tenant-svc), and the service must be started, not wait.
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    Thread.ofVirtual().name("phone-backfill").start(this::run);
  }

  void run() {
    try {
      int fixed = backfill.run();
      if (fixed > 0) {
        LOG.log(Level.INFO, "phone backfill: {0} customer(s) normalised to E.164", fixed);
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "phone backfill deferred: {0}", e.getMessage());
    }
  }
}
