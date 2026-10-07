package com.storeql.notification.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Remembers, briefly, which (business, event type) pairs had no endpoint listening, so the fan-out
 * does not ask the database about every event of a business that has no webhooks. Only the negative
 * answer is kept (a positive one is a query that returns work anyway), it is dropped the moment
 * this instance registers or changes an endpoint of that business, and it expires on its own so
 * another instance's change is seen within the time-to-live.
 */
@ApplicationScoped
public class FanoutInterest {

  @Inject
  @ConfigProperty(name = "storeql.webhooks.fanout-none-cache-seconds", defaultValue = "30")
  long ttlSeconds;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.fanout-none-cache-max", defaultValue = "10000")
  int max;

  private final Map<UUID, Map<String, Long>> noneUntilNanos = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicInteger size =
      new java.util.concurrent.atomic.AtomicInteger();

  /**
   * @param tenantId the business
   * @param type the event type
   * @return true when no endpoint was listening a moment ago, so the query can be skipped
   */
  public boolean knownEmpty(UUID tenantId, String type) {
    if (ttlSeconds <= 0) return false;
    Map<String, Long> types = noneUntilNanos.get(tenantId);
    if (types == null) return false;
    Long until = types.get(type);
    if (until == null) return false;
    if (System.nanoTime() - until < 0) return true;
    types.remove(type);
    return false;
  }

  /** Records that the query for this pair came back empty. */
  public void noteEmpty(UUID tenantId, String type) {
    if (ttlSeconds <= 0) return;
    if (size.get() >= max) {
      noneUntilNanos.clear();
      size.set(0);
    }
    long until = System.nanoTime() + Duration.ofSeconds(ttlSeconds).toNanos();
    if (noneUntilNanos.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>()).put(type, until)
        == null) {
      size.incrementAndGet();
    }
  }

  /** Forgets what is known of a business: one of its endpoints was added or changed. */
  public void invalidate(UUID tenantId) {
    Map<String, Long> gone = noneUntilNanos.remove(tenantId);
    if (gone != null) size.updateAndGet(n -> Math.max(0, n - gone.size()));
  }

  /**
   * @return how many pairs are remembered (for tests)
   */
  int remembered() {
    return noneUntilNanos.values().stream().mapToInt(Map::size).sum();
  }
}
