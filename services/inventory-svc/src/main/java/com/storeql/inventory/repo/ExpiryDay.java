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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link Expiry} of a tenant now: each store's day from the zone tenant-svc records for it
 * (read through {@link TenantProfiles}, as every service reads a store's zone). The zones are
 * remembered for a minute, and so is a failed read, so an unreachable tenant-svc costs one attempt
 * a minute rather than one per draw; a store whose zone cannot be read is read in UTC.
 */
@ApplicationScoped
public class ExpiryDay {

  private static final Duration KEEP = Duration.ofMinutes(1);

  private record Zones(Map<UUID, ZoneId> zones, Instant readAt) {}

  @Inject TenantProfiles profiles;

  private final Map<UUID, Zones> cache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();

  /** The tenant's stores' days at this moment. */
  public Expiry of(UUID tenantId) {
    Instant now = clock.instant();
    Zones hit = cache.get(tenantId);
    if (hit == null || hit.readAt().plus(KEEP).isBefore(now)) {
      hit = new Zones(read(tenantId), now);
      cache.put(tenantId, hit);
    }
    return Expiry.at(now, hit.zones());
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
