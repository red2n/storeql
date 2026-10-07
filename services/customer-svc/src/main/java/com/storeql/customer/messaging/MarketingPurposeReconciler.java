package com.storeql.customer.messaging;

import com.storeql.customer.service.MarketingConsentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * The start-up reconciliation of the marketing-consent cascade: once, when this instance comes up,
 * every customer across every tenant whose MARKETING purpose stands withdrawn but who still has a
 * channel recorded as granted gets it switched off. Idempotent — a second start finds nothing left
 * to do — so this runs unconditionally rather than behind a flag.
 *
 * <p>{@code onStart} forces CDI to instantiate this eagerly (Helidon MP gotcha #9); the work itself
 * runs on a background virtual thread so it never delays start-up and a failure here logs only —
 * this fixes stale data, it does not gate readiness.
 */
@ApplicationScoped
public class MarketingPurposeReconciler {

  private static final Logger LOG = System.getLogger(MarketingPurposeReconciler.class.getName());

  @Inject MarketingConsentService marketing;

  /**
   * Starts the work on a background virtual thread once the application is up: it can take a while
   * with many tenants (and calls tenant-svc), and the service must be started, not wait.
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    Thread.ofVirtual().name("marketing-purpose-reconciler").start(this::reconcile);
  }

  void reconcile() {
    try {
      int fixed = marketing.reconcilePurposeWithdrawals();
      if (fixed > 0) {
        LOG.log(Level.INFO, "marketing purpose reconciliation: {0} channel(s) switched off", fixed);
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "marketing purpose reconciliation deferred: {0}", e.getMessage());
    }
  }
}
