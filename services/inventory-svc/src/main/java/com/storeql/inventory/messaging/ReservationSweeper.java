package com.storeql.inventory.messaging;

import com.storeql.inventory.repo.InventoryRepository.ReservationRef;
import com.storeql.inventory.service.InventoryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Background sweeper that releases expired HELD reservations (abandoned carts) so the held stock
 * returns to availability — the industry-standard way inventory systems reclaim stuck holds. Each
 * release emits StockReleased.
 */
@ApplicationScoped
public class ReservationSweeper {

  private static final Logger LOG = System.getLogger(ReservationSweeper.class.getName());

  @Inject InventoryService service;

  @Inject
  @ConfigProperty(name = "storeql.inventory.sweeper-seconds", defaultValue = "30")
  long sweeperSeconds;

  @Inject
  @ConfigProperty(name = "storeql.inventory.sweeper-batch", defaultValue = "200")
  int batchSize;

  @Inject
  @ConfigProperty(name = "storeql.inventory.sweeper-budget-seconds", defaultValue = "20")
  long budgetSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "inventory-reservation-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, sweeperSeconds, sweeperSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Reservation sweeper started (every {0}s)", sweeperSeconds);
  }

  private void sweepQuietly() {
    try {
      int released =
          sweep(
              service::expiredReservationsWithTenant,
              (tenantId, id) -> service.release(tenantId, id),
              Math.max(1, batchSize),
              budgetNanos());
      if (released > 0) {
        LOG.log(Level.DEBUG, "Swept {0} expired reservations", released);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Reservation sweep deferred: " + e.getMessage());
    }
  }

  private long budgetNanos() {
    return TimeUnit.SECONDS.toNanos(Math.max(1, budgetSeconds));
  }

  /**
   * One tick: release expired holds a page at a time until a page comes back short, a page releases
   * nothing (every hold in it failed; the same ones would come back) or the time budget is spent.
   * One hold that fails is logged and skipped; it never ends the tick for the rest.
   *
   * @param page the next page of expired holds, soonest first, at most the given size
   * @param release releases one hold (tenant, id)
   * @return how many holds were released
   */
  static int sweep(
      IntFunction<List<ReservationRef>> page,
      BiConsumer<UUID, UUID> release,
      int batchSize,
      long budgetNanos) {
    long deadline = System.nanoTime() + budgetNanos;
    int released = 0;
    while (true) {
      List<ReservationRef> refs = page.apply(batchSize);
      int pageReleased = 0;
      for (ReservationRef ref : refs) {
        try {
          release.accept(ref.tenantId(), ref.id());
          pageReleased++;
        } catch (RuntimeException e) {
          LOG.log(Level.WARNING, "Could not release expired reservation " + ref.id(), e);
        }
      }
      released += pageReleased;
      if (refs.size() < batchSize || pageReleased == 0 || System.nanoTime() >= deadline) {
        return released;
      }
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
