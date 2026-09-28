package com.storeql.customer.messaging;

import com.storeql.customer.service.MarketingConsentService;
import jakarta.annotation.PostConstruct;
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
 * runs from {@link PostConstruct} so a failure here logs and lets the service start regardless —
 * this fixes stale data, it does not gate readiness.
 */
@ApplicationScoped
public class MarketingPurposeReconciler {

  private static final Logger LOG = System.getLogger(MarketingPurposeReconciler.class.getName());

  @Inject MarketingConsentService marketing;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager CDI startup */
  }

  @PostConstruct
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
