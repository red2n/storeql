package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Expiry;
import com.storeql.service.TenantProfiles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The {@link Expiry} of a tenant now: each store's day from the zone tenant-svc records for it
 * (read through {@link TenantProfiles}, as every service reads a store's zone). The zones are
 * remembered for a minute, and so is a failed read, so an unreachable tenant-svc costs one attempt
 * a minute rather than one per draw; a store whose zone cannot be read is read in UTC.
 *
 * <p>The read is an HTTP call, and callers ask while a stock transaction holds row locks and a pool
 * connection. So it is single-flight and never waits once a tenant has been read: past its minute
 * the last good zones are served while one background read refreshes them, and a first read (or one
 * racing it) is made by one caller under a lock that the others wait on, never one read per caller.
 */
@ApplicationScoped
public class ExpiryDay {

  private static final Duration KEEP = Duration.ofMinutes(1);

  private record Zones(Map<UUID, ZoneId> zones, Instant readAt) {}

  @Inject TenantProfiles profiles;

  private final Map<UUID, Zones> cache = new ConcurrentHashMap<>();
  private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
  private Clock clock = Clock.systemUTC();
  private Function<UUID, Map<UUID, ZoneId>> reader = this::read;
  private Executor background = r -> Thread.ofVirtual().name("expiry-day-refresh").start(r);

  /** For tests: a stand-in for tenant-svc, a clock to age the cache with, and who refreshes. */
  static ExpiryDay forTest(
      Function<UUID, Map<UUID, ZoneId>> reader, Clock clock, Executor background) {
    ExpiryDay d = new ExpiryDay();
    d.reader = reader;
    d.clock = clock;
    d.background = background;
    return d;
  }

  /** The tenant's stores' days at this moment. */
  public Expiry of(UUID tenantId) {
    Instant now = clock.instant();
    Zones hit = cache.get(tenantId);
    if (hit == null) {
      hit = readOnce(tenantId, now);
    } else if (hit.readAt().plus(KEEP).isBefore(now)) {
      refreshInBackground(tenantId);
    }
    return Expiry.at(now, hit.zones());
  }

  /** The first read of a tenant: one caller reads under the lock, the others use its answer. */
  private Zones readOnce(UUID tenantId, Instant now) {
    ReentrantLock lock = locks.computeIfAbsent(tenantId, k -> new ReentrantLock());
    lock.lock();
    try {
      Zones hit = cache.get(tenantId);
      if (hit == null) {
        hit = new Zones(reader.apply(tenantId), now);
        cache.put(tenantId, hit);
      }
      return hit;
    } finally {
      lock.unlock();
    }
  }

  private void refreshInBackground(UUID tenantId) {
    if (!refreshing.add(tenantId)) {
      return; // someone is already reading; keep serving the last good zones
    }
    try {
      background.execute(
          () -> {
            try {
              cache.put(tenantId, new Zones(reader.apply(tenantId), clock.instant()));
            } finally {
              refreshing.remove(tenantId);
            }
          });
    } catch (RuntimeException e) {
      refreshing.remove(tenantId); // keep serving the last good zones; the next call tries again
    }
  }

  private Map<UUID, ZoneId> read(UUID tenantId) {
    Map<UUID, ZoneId> zones = new HashMap<>();
    try {
      TenantProfiles.Stores stores = profiles.stores(tenantId, null);
      for (UUID id : stores.ids()) {
        ZoneId z = stores.zoneOf(id);
        if (z != null) zones.put(id, z);
      }
    } catch (RuntimeException e) {
      // Unreadable: every store is read in UTC until the next attempt.
      zones.clear();
    }
    return zones;
  }
}
