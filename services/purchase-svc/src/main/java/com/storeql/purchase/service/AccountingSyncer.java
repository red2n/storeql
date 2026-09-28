package com.storeql.purchase.service;

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
 * Pushes journals to every connected package by the clock (17.9): one pass every {@code
 * storeql.accounting.poll-seconds}, queuing what the ledger has posted since and trying what is
 * due. Off when {@code storeql.accounting.enabled} is false — tests and stacks that drive a pass by
 * hand.
 */
@ApplicationScoped
public class AccountingSyncer {

  private static final Logger LOG = System.getLogger(AccountingSyncer.class.getName());

  @Inject AccountingService service;

  @Inject
  @ConfigProperty(name = "storeql.accounting.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.accounting.poll-seconds", defaultValue = "30")
  long pollSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    if (!enabled) {
      LOG.log(Level.INFO, "Accounting sync is off (storeql.accounting.enabled=false)");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "accounting-syncer");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(this::tickQuietly, pollSeconds, pollSeconds, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }

  private void tickQuietly() {
    try {
      service.tick();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Accounting sync tick failed: " + e.getMessage(), e);
    }
  }
}
