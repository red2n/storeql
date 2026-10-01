package com.storeql.order.messaging;

import com.storeql.order.service.OrderService;
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
 * Background sweeper for orders waiting for a price: flags one at its business's first limit and
 * cancels it at the second, releasing its stock the way any cancellation does. A business that has
 * set no limit is never touched. Next to {@link PendingOrderSweeper}, which does the same for
 * orders waiting to be paid.
 */
@ApplicationScoped
public class AwaitingPriceSweeper {

  private static final Logger LOG = System.getLogger(AwaitingPriceSweeper.class.getName());
  private static final int BATCH_LIMIT = 200;

  @Inject OrderService service;

  @Inject
  @ConfigProperty(name = "storeql.order.awaiting-price-sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(
      name = "storeql.order.awaiting-price-sweeper.interval-seconds",
      defaultValue = "60")
  long intervalSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Awaiting-price sweeper disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "order-awaiting-price-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Awaiting-price sweeper started (every {0}s)", intervalSeconds);
  }

  private void sweepQuietly() {
    try {
      int acted = service.sweepAwaitingPriceOrders(BATCH_LIMIT);
      if (acted > 0) {
        LOG.log(Level.INFO, "Awaiting-price sweeper flagged or cancelled {0} order(s)", acted);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Awaiting-price sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
