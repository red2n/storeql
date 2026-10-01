package com.storeql.gateway.filters;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * A bounded, expiring cache for the gateway's lookups on the request path (tenant status, plan
 * allowances, API-key verdicts).
 *
 * <p>Bounded: least recently used out when full, constant time (no scan). Single-flight: while one
 * caller loads a key the others for that key do not load it again, they wait for that answer — or,
 * where {@code serveStale} is set and an expired value is in hand, take the expired value at once.
 * The lock guards the map only; the load itself runs outside it, so no caller is parked behind
 * another's network call except by choice (the single-flight wait, a future, not a monitor).
 */
final class LookupCache<V> {

  private record Entry<V>(V value, long expiresAt) {}

  private final long ttlMillis;
  private final LongSupplier clock;
  private final boolean serveStale;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Entry<V>> entries;
  private final Map<String, CompletableFuture<V>> inflight = new HashMap<>();

  LookupCache(int maxEntries, long ttlMillis, boolean serveStale, LongSupplier clock) {
    this.ttlMillis = ttlMillis;
    this.serveStale = serveStale;
    this.clock = clock;
    this.entries = new Lru<>(maxEntries);
  }

  /**
   * Access-ordered and bounded: the least recently used entry goes once the map passes its limit. A
   * static class, so its size() can only ever mean the map's own.
   */
  private static final class Lru<K, T> extends LinkedHashMap<K, T> {
    private static final long serialVersionUID = 1L;
    private final int limit;

    Lru(int limit) {
      super(16, 0.75f, true);
      this.limit = limit;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, T> eldest) {
      return size() > limit;
    }
  }

  /**
   * The value for a key: from memory while fresh, else loaded once however many callers ask.
   *
   * @param cacheable whether a loaded value is kept (an answer that is not an answer is not)
   */
  // The loader's own exception is rethrown unchanged on purpose (its stack trace is its own), so
  // a waiting caller sees exactly what the loading caller saw.
  @SuppressWarnings("PMD.PreserveStackTrace")
  V get(String key, Function<String, V> loader, Predicate<V> cacheable) {
    CompletableFuture<V> mine = null;
    CompletableFuture<V> theirs;
    lock.lock();
    try {
      Entry<V> e = entries.get(key);
      if (e != null && e.expiresAt() > clock.getAsLong()) {
        return e.value();
      }
      theirs = inflight.get(key);
      if (theirs == null) {
        mine = new CompletableFuture<>();
        inflight.put(key, mine);
      } else if (serveStale && e != null) {
        return e.value();
      }
    } finally {
      lock.unlock();
    }
    if (mine == null) {
      try {
        return theirs.join();
      } catch (CompletionException ce) {
        // The loader's own failure, as its caller sees it, not wrapped a second time.
        if (ce.getCause() instanceof RuntimeException re) {
          throw re;
        }
        throw ce;
      }
    }
    try {
      V value = loader.apply(key);
      lock.lock();
      try {
        if (cacheable.test(value)) {
          entries.put(key, new Entry<>(value, clock.getAsLong() + ttlMillis));
        }
        inflight.remove(key);
      } finally {
        lock.unlock();
      }
      mine.complete(value);
      return value;
    } catch (RuntimeException ex) {
      lock.lock();
      try {
        inflight.remove(key);
      } finally {
        lock.unlock();
      }
      mine.completeExceptionally(ex);
      throw ex;
    } finally {
      if (!mine.isDone()) {
        // An Error out of the loader: still release the key and the callers waiting on it.
        lock.lock();
        try {
          inflight.remove(key);
        } finally {
          lock.unlock();
        }
        mine.completeExceptionally(new IllegalStateException("lookup did not complete"));
      }
    }
  }

  int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }
}
