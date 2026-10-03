package com.storeql.service;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Keeps a per-tenant cache bounded. The caches here are keyed by tenant and expire by age on read,
 * but an entry for a tenant nobody asks about again was never removed; on a platform with many
 * businesses that is a slow leak. After a put, call {@link #trim}: once the map exceeds the limit
 * (MicroProfile Config {@code storeql.cache.max-tenants}, default 10000) expired entries go first,
 * then arbitrary ones until it fits. Eviction only costs a re-read.
 */
final class CacheSweep {

  private static final int MAX = (int) maxTenants();

  private CacheSweep() {}

  private static long maxTenants() {
    return Cfg.getLong("storeql.cache.max-tenants", 10_000L);
  }

  static <K, V> void trim(Map<K, V> cache, Predicate<V> expired) {
    trim(cache, expired, MAX);
  }

  static <K, V> void trim(Map<K, V> cache, Predicate<V> expired, int max) {
    if (cache.size() <= max) return;
    cache.values().removeIf(expired);
    if (cache.size() <= max) return;
    var it = cache.keySet().iterator();
    while (cache.size() > max && it.hasNext()) {
      it.next();
      it.remove();
    }
  }
}
