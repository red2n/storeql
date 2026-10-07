package com.storeql.reporting.service;

import com.storeql.reporting.domain.PendingWork.Report;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The waiting-work report of each business, kept for a few seconds so that however many dashboards
 * of one business poll it, the owning services are asked (and count their queues) once per period.
 *
 * <p>Nothing here decides who may see a report: the caller is judged before this is asked, and the
 * key is the business alone, so a report is only ever handed back to the business it was made for.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Only a report in which every source answered is kept; a gap is read again by the next ask.
 *   <li>A report is kept for {@code cache-millis} counted from when its reading began, and at most
 *       {@code cache-max-businesses} are kept, the least recently asked for going first.
 *   <li>One reading at a time per business (single flight): an ask for a business whose reading is
 *       under way waits for it and shares its answer, kept or not, so a cold cache is not a herd.
 *       The reading is made outside every lock, and waiting is on a future, never a monitor, so a
 *       virtual thread is never pinned.
 *   <li>A period or a size of nothing turns all of this off: every ask is a reading of its own.
 * </ul>
 */
@ApplicationScoped
public class WaitingWorkCache {

  private record Kept(Report report, long beganNanos) {}

  @Inject
  @ConfigProperty(name = "storeql.reporting.waiting-work.cache-millis", defaultValue = "10000")
  long ttlMillis;

  @Inject
  @ConfigProperty(
      name = "storeql.reporting.waiting-work.cache-max-businesses",
      defaultValue = "10000")
  int maxBusinesses;

  private final LongSupplier nanos;

  /** Guards {@code kept}; only ever held for a map operation, never across a reading. */
  private final ReentrantLock lock = new ReentrantLock();

  /** In access order, so the first entry is the least recently asked for. */
  private final Map<UUID, Kept> kept = new LinkedHashMap<>(16, 0.75f, true);

  private final Map<UUID, CompletableFuture<Report>> flights = new ConcurrentHashMap<>();

  private final AtomicInteger followers = new AtomicInteger();

  public WaitingWorkCache() {
    this.nanos = System::nanoTime;
  }

  /** For tests: the period and size set by hand, and a clock to age the reports with. */
  WaitingWorkCache(long ttlMillis, int maxBusinesses, LongSupplier nanos) {
    this.ttlMillis = ttlMillis;
    this.maxBusinesses = maxBusinesses;
    this.nanos = nanos;
  }

  /**
   * The business's report: the one kept, or the one this ask (or another already under way for the
   * same business) makes.
   *
   * @param tenantId the business, from the verified token
   * @param make reads the services and assembles the report; called outside every lock
   * @return the report; the same instance for everyone sharing a reading
   * @throws RuntimeException what {@code make} threw, to the asker that ran it, and wrapped to
   *     those that were waiting on it
   */
  public Report get(UUID tenantId, Supplier<Report> make) {
    if (!enabled()) return make.get();
    Report hit = fresh(tenantId);
    if (hit != null) return hit;

    CompletableFuture<Report> mine = new CompletableFuture<>();
    CompletableFuture<Report> flying = flights.putIfAbsent(tenantId, mine);
    if (flying != null) return share(flying);

    try {
      // A reading that ended between our miss and our taking the flight has kept its report.
      Report again = fresh(tenantId);
      if (again != null) {
        mine.complete(again);
        return again;
      }
      long began = nanos.getAsLong();
      Report made = make.get();
      if (made.unreachable().isEmpty()) keep(tenantId, made, began);
      mine.complete(made);
      return made;
    } catch (RuntimeException | Error e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      flights.remove(tenantId, mine);
    }
  }

  /** How many asks are waiting on another's reading. For tests. */
  int followersWaiting() {
    return followers.get();
  }

  private boolean enabled() {
    return ttlMillis > 0 && maxBusinesses > 0;
  }

  /**
   * The reading is bounded by the client's shared deadline and its owner always completes the
   * future, so this wait ends; it is on a future, not a monitor.
   */
  private Report share(CompletableFuture<Report> flying) {
    followers.incrementAndGet();
    try {
      return flying.get();
    } catch (ExecutionException e) {
      throw new IllegalStateException("The waiting-work report could not be made", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while the waiting-work report was made", e);
    } finally {
      followers.decrementAndGet();
    }
  }

  private Report fresh(UUID tenantId) {
    long now = nanos.getAsLong();
    lock.lock();
    try {
      Kept hit = kept.get(tenantId);
      if (hit == null) return null;
      if (now - hit.beganNanos() < TimeUnit.MILLISECONDS.toNanos(ttlMillis)) return hit.report();
      kept.remove(tenantId);
      return null;
    } finally {
      lock.unlock();
    }
  }

  private void keep(UUID tenantId, Report report, long beganNanos) {
    lock.lock();
    try {
      kept.put(tenantId, new Kept(report, beganNanos));
      Iterator<UUID> oldest = kept.keySet().iterator();
      while (kept.size() > maxBusinesses && oldest.hasNext()) {
        oldest.next();
        oldest.remove();
      }
    } finally {
      lock.unlock();
    }
  }
}
