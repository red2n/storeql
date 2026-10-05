package com.storeql.service;

import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Fail-open facade over Redis for cache-aside reads.
 *
 * <p>A cache is an optimisation, not a source of truth: Postgres is. So a Redis outage must cost
 * latency, never correctness or availability. Calling {@link RedisCommands} directly does the
 * opposite — Lettuce throws {@link RedisException} on a dead or hung connection, and an unguarded
 * {@code redis.get} in a read path turns a cache outage into a 500 on the service's highest-traffic
 * endpoint. Every cache access goes through here so that cannot happen.
 *
 * <p>Two distinct failures are handled:
 *
 * <ul>
 *   <li><b>Redis dies while running</b> — commands throw, each method swallows and reports a miss.
 *       Lettuce reconnects underneath, so recovery needs no intervention.
 *   <li><b>Redis is down when the service starts</b> — the producer's {@code connect()} throws, so
 *       resolving the bean at all fails. Resolution is therefore lazy and retried on a backoff,
 *       rather than injected eagerly; without the backoff every request would pay a fresh connect
 *       attempt while Redis was away.
 * </ul>
 *
 * <p>Deliberately not covered by a readiness probe: fail-open is what makes Redis genuinely
 * optional, and a service that still serves every request correctly from Postgres is ready.
 */
@ApplicationScoped
public class RedisCache {

  private static final Logger LOG = System.getLogger(RedisCache.class.getName());

  /** How long to stop trying to obtain a connection after an attempt fails. */
  private static final long RECONNECT_BACKOFF_MS = 10_000;

  @Inject Instance<RedisCommands<String, String>> commandsSource;

  private volatile long nextAttemptAtMillis;

  /**
   * Reads a cached value.
   *
   * @return the cached value, or null on a miss — and on any Redis failure, which is reported as a
   *     miss so the caller falls through to its own source of truth
   */
  public String get(String key) {
    return call("read", key, redis -> redis.get(key));
  }

  /** Caches {@code value} under {@code key} for {@code ttlSeconds}. A failure is not fatal. */
  public void put(String key, String value, long ttlSeconds) {
    call("write", key, redis -> redis.set(key, value, SetArgs.Builder.ex(ttlSeconds)));
  }

  /**
   * Evicts {@code keys}.
   *
   * <p>A failed eviction is the one case that costs correctness rather than latency: the stale
   * entry survives until its TTL expires. Logged at WARNING for that reason — TTLs on cached
   * entries are what bound the damage, so cache-aside entries must always carry one.
   */
  public void evict(String... keys) {
    call("evict", String.join(",", keys), redis -> redis.del(keys));
  }

  /**
   * Runs one command, and is the only place a Redis failure is caught.
   *
   * <p>The connection is resolved and first used inside the guarded block. Under CDI, resolving the
   * client returns a proxy and does not connect; the connect runs on the first command, so a
   * connect failure surfaces here, where it starts the backoff. Catching only at resolution (as
   * before) left that failure uncaught and every later call re-ran the connect.
   *
   * <p>Any failure on the path starts the backoff, including a command that fails on a connection
   * that was up: the handle cannot tell the two apart, and both mean "serve uncached for now".
   *
   * @return the command's result, or null when Redis is in backoff or the command failed
   */
  private <T> T call(String operation, String key, Command<T> command) {
    long now = System.currentTimeMillis();
    if (now < nextAttemptAtMillis) return null;
    try {
      T result = command.run(commandsSource.get());
      nextAttemptAtMillis = 0;
      return result;
    } catch (RuntimeException e) {
      nextAttemptAtMillis = now + RECONNECT_BACKOFF_MS;
      LOG.log(
          Level.WARNING,
          "Redis {0} failed for key {1}; serving uncached for the next {2}ms: {3}",
          operation,
          key,
          RECONNECT_BACKOFF_MS,
          e.toString());
      return null;
    }
  }

  /** One Redis command, run against a resolved handle. */
  @FunctionalInterface
  private interface Command<T> {
    T run(RedisCommands<String, String> redis);
  }
}
