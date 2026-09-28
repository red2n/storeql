package com.storeql.customer.messaging;

import com.storeql.customer.service.LoyaltyProgrammeService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Lets loyalty points die on their day and tiers fall when their earning rolls out of the
 * qualifying window (13.x): every {@code storeql.customer.loyalty.sweep-seconds}, for every
 * business with a rule. Off at zero, for a test that drives the sweep by hand.
 */
@ApplicationScoped
public class LoyaltyExpirySweeper {

  private static final Logger LOG = System.getLogger(LoyaltyExpirySweeper.class.getName());

  @Inject LoyaltyProgrammeService service;

  @Inject
  @ConfigProperty(name = "storeql.customer.loyalty.sweep-seconds", defaultValue = "3600")
  long sweepSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager CDI startup */
  }

  @PostConstruct
  void start() {
    if (sweepSeconds <= 0) {
      LOG.log(Level.INFO, "Loyalty expiry sweeper off");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "customer-loyalty-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(this::sweepQuietly, 60, sweepSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Loyalty expiry sweeper started (every {0}s)", sweepSeconds);
  }

  private void sweepQuietly() {
    try {
      service.sweep();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Loyalty sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
