package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The point of {@link RedisCache} is that a Redis outage costs latency and nothing else, so these
 * tests are all about what happens when Redis is unavailable. Hand-rolled JDK proxies rather than a
 * mocking framework — {@code RedisCommands} has hundreds of methods and common-service has no
 * Mockito dependency.
 */
class RedisCacheTest {

  /** A commands handle where every operation fails, as it would against a dead connection. */
  @SuppressWarnings("unchecked")
  private static RedisCommands<String, String> failingCommands() {
    return (RedisCommands<String, String>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> {
              throw new RedisConnectionException("redis is down");
            });
  }

  /** A CDI {@code Instance} whose resolution is driven by {@code supplier}, counting attempts. */
  @SuppressWarnings("unchecked")
  private static Instance<RedisCommands<String, String>> resolvingTo(
      Supplier<RedisCommands<String, String>> supplier, AtomicInteger resolutions) {
    return (Instance<RedisCommands<String, String>>)
        Proxy.newProxyInstance(
            Instance.class.getClassLoader(),
            new Class<?>[] {Instance.class},
            (proxy, method, args) -> {
              if ("get".equals(method.getName())) {
                resolutions.incrementAndGet();
                return supplier.get();
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static RedisCache cacheBackedBy(
      Supplier<RedisCommands<String, String>> supplier, AtomicInteger resolutions) {
    RedisCache cache = new RedisCache();
    cache.commandsSource = resolvingTo(supplier, resolutions);
    return cache;
  }

  @Test
  void readReportsAMissWhenTheCommandFails() {
    RedisCache cache = cacheBackedBy(RedisCacheTest::failingCommands, new AtomicInteger());
    // A miss, not an exception — the caller falls through to Postgres.
    assertNull(cache.get("product:t:1"));
  }

  @Test
  void writeAndEvictSwallowFailures() {
    RedisCache cache = cacheBackedBy(RedisCacheTest::failingCommands, new AtomicInteger());
    assertDoesNotThrow(() -> cache.put("product:t:1", "payload", 300));
    assertDoesNotThrow(() -> cache.evict("product:t:1"));
    assertDoesNotThrow(() -> cache.evict("a", "b"));
  }

  @Test
  void unreachableRedisAtStartupIsAMissRatherThanAFailure() {
    RedisCache cache =
        cacheBackedBy(
            () -> {
              throw new RedisConnectionException("cannot connect");
            },
            new AtomicInteger());
    assertNull(cache.get("product:t:1"));
    assertDoesNotThrow(() -> cache.put("product:t:1", "payload", 300));
  }

  @Test
  void aFailedConnectionIsNotRetriedOnEveryCall() {
    AtomicInteger resolutions = new AtomicInteger();
    RedisCache cache =
        cacheBackedBy(
            () -> {
              throw new RedisConnectionException("cannot connect");
            },
            resolutions);

    for (int i = 0; i < 25; i++) {
      cache.get("product:t:" + i);
    }

    // Without the backoff every request would pay a fresh connect attempt while Redis was away,
    // which is slower than having no cache at all.
    assertEquals(1, resolutions.get());
  }

  /**
   * What CDI does for a normal-scoped producer: {@code Instance.get()} returns a client proxy and
   * does not run the producer; the producer runs when a method is first invoked through the proxy,
   * and runs again on every later invocation that finds no connection. {@code producerRuns} counts
   * the producer runs; the producer always fails, as Redis does while it is down.
   */
  @SuppressWarnings("unchecked")
  private static Instance<RedisCommands<String, String>> cdiStyleInstance(
      AtomicInteger producerRuns) {
    RedisCommands<String, String> clientProxy =
        (RedisCommands<String, String>)
            Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> {
                  producerRuns.incrementAndGet();
                  throw new RedisConnectionException("cannot connect");
                });
    return (Instance<RedisCommands<String, String>>)
        Proxy.newProxyInstance(
            Instance.class.getClassLoader(),
            new Class<?>[] {Instance.class},
            (proxy, method, args) -> {
              if ("get".equals(method.getName())) return clientProxy;
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /**
   * Under CDI the connect failure surfaces on the first command, not at resolution. The backoff
   * must hold across those commands too, or every cache call pays a fresh connect attempt while
   * Redis is away, which is slower than having no cache.
   */
  @Test
  void aFailedConnectSurfacingOnTheFirstCommandIsBackedOffToo() {
    AtomicInteger producerRuns = new AtomicInteger();
    RedisCache cache = new RedisCache();
    cache.commandsSource = cdiStyleInstance(producerRuns);

    for (int i = 0; i < 25; i++) {
      assertNull(cache.get("product:t:" + i));
    }

    assertEquals(1, producerRuns.get(), "one connect attempt, then the backoff holds");
  }

  @Test
  void aFailedCommandStopsResolutionUntilTheBackoffEnds() {
    AtomicInteger resolutions = new AtomicInteger();
    RedisCache cache = cacheBackedBy(RedisCacheTest::failingCommands, resolutions);

    for (int i = 0; i < 10; i++) {
      cache.get("product:t:" + i);
    }

    // The first command fails and starts the backoff: the later calls do not resolve the handle.
    assertEquals(1, resolutions.get());
  }
}
