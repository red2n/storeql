package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.test.RedisSupport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link RedisCache} against a REAL Redis, through the same {@link RedisClientProducer} the
 * services run.
 *
 * <p>Every other test of the cache ({@code RedisCacheTest}) is about Redis being unavailable and
 * stubs Redis out, and the cache is fail-open on purpose: a Redis that is not there is a cache
 * miss, not an error. So none of them can tell a working Redis path from a broken one, and a
 * Lettuce/Netty mismatch (Lettuce 7 calls Netty 4.2-only classes; the parent pom once pinned Netty
 * 4.1 for a security fix) is invisible to all of them. This one proves the positive: a value goes
 * in, comes back, carries its TTL, expires, is evicted, and a big payload survives the trip, on the
 * Lettuce and Netty versions this build actually resolves.
 */
class RedisCacheLiveIT {

  private static final String PASSWORD = "cache-live-it";
  private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

  private static RedisSupport plain;
  private static GenericContainer<?> passworded;
  private static RedisClient verifierClient;
  private static StatefulRedisConnection<String, String> verifier;

  @BeforeAll
  @SuppressWarnings("resource") // stopped in tearDown
  static void startRedis() {
    plain = RedisSupport.start();
    passworded =
        new GenericContainer<>(REDIS_IMAGE)
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);
    passworded.start();
  }

  /**
   * A direct line to the plain Redis, to see what the cache really left there. Opened on first use,
   * not in {@code startRedis}, so that a Lettuce/Netty mismatch fails {@link
   * #lettuceAndNettyAreOnTheSameLine} by name rather than every test at once in the set-up.
   */
  private static RedisCommands<String, String> verifier() {
    if (verifier == null) {
      verifierClient =
          RedisClient.create(RedisURI.Builder.redis(plain.host(), plain.port()).build());
      verifier = verifierClient.connect();
    }
    return verifier.sync();
  }

  @AfterAll
  static void tearDown() {
    if (verifier != null) verifier.close();
    if (verifierClient != null) verifierClient.shutdown();
    if (passworded != null) passworded.stop();
    if (plain != null) plain.stop();
  }

  /** A production producer and a cache reading from it, closed together. */
  private record Live(RedisClientProducer producer, RedisCache cache) implements AutoCloseable {

    /**
     * Configured the way a service's config would; the cache gets its commands the way CDI gives
     * them: the produced bean is made once, on first use, and every later call goes to that one
     * connection. (Calling the producer per command would close and rebuild it each time.)
     */
    @SuppressWarnings("unchecked")
    static Live to(String host, int port, String password) {
      RedisClientProducer producer = new RedisClientProducer();
      producer.redisHost = host;
      producer.redisPort = port;
      producer.redisPassword = Optional.ofNullable(password);
      AtomicReference<RedisCommands<String, String>> bean = new AtomicReference<>();
      RedisCache cache = new RedisCache();
      cache.commandsSource =
          (Instance<RedisCommands<String, String>>)
              Proxy.newProxyInstance(
                  Instance.class.getClassLoader(),
                  new Class<?>[] {Instance.class},
                  (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                      return bean.updateAndGet(c -> c != null ? c : producer.redisCommands());
                    }
                    throw new UnsupportedOperationException(method.getName());
                  });
      return new Live(producer, cache);
    }

    @Override
    public void close() {
      producer.close(null);
    }
  }

  private static Live onPlainRedis() {
    return Live.to(plain.host(), plain.port(), null);
  }

  private static Live onPasswordedRedis(String password) {
    return Live.to(passworded.getHost(), passworded.getMappedPort(6379), password);
  }

  @Test
  void lettuceAndNettyAreOnTheSameLine() {
    // The failure this guards against is a classpath one, and a classpath failure surfaces below
    // as a cache miss. Name it directly: Lettuce 7 needs this Netty 4.2 class.
    assertDoesNotThrow(
        () -> Class.forName("io.netty.channel.MultiThreadIoEventLoopGroup"),
        "Lettuce 7 needs Netty 4.2; the Netty on the classpath is older (version.lib.netty)");
  }

  @Test
  void aCachedValueIsReadBackAndCarriesItsTtl() {
    String json = "{\"sku\":\"A-1\",\"name\":\"café ☕\"}";
    try (Live live = onPlainRedis()) {
      live.cache().put("live:roundtrip", json, 60);

      assertEquals(json, live.cache().get("live:roundtrip"));
      // Seen from a second, independent connection: it is really in Redis, with an expiry.
      long ttl = verifier().ttl("live:roundtrip");
      assertTrue(ttl > 0 && ttl <= 60, "the entry carries the TTL it was cached with, was " + ttl);
    }
  }

  @Test
  void aKeyNeverCachedIsAMissAndNotAnOutage() {
    try (Live live = onPlainRedis()) {
      assertNull(live.cache().get("live:never-cached"));
      // A miss on a healthy Redis starts no backoff: the next write lands and reads back.
      live.cache().put("live:after-miss", "v", 60);
      assertEquals("v", live.cache().get("live:after-miss"));
    }
  }

  @Test
  void anEntryExpiresWhenItsTtlRunsOut() throws Exception {
    try (Live live = onPlainRedis()) {
      live.cache().put("live:short-lived", "here", 1);
      assertEquals("here", live.cache().get("live:short-lived"), "readable inside its TTL");

      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (live.cache().get("live:short-lived") != null && System.nanoTime() < deadline) {
        Thread.sleep(100);
      }

      assertNull(live.cache().get("live:short-lived"), "gone once the TTL has run out");
      assertEquals(0L, verifier().exists("live:short-lived"), "Redis itself dropped it");
      // Expiry is not a failure: the cache is still usable straight away.
      live.cache().put("live:short-lived", "again", 60);
      assertEquals("again", live.cache().get("live:short-lived"));
    }
  }

  @Test
  void evictRemovesTheNamedKeysAndOnlyThose() {
    try (Live live = onPlainRedis()) {
      live.cache().put("live:evict:a", "a", 60);
      live.cache().put("live:evict:b", "b", 60);
      live.cache().put("live:evict:c", "c", 60);

      live.cache().evict("live:evict:a", "live:evict:b");

      assertNull(live.cache().get("live:evict:a"));
      assertNull(live.cache().get("live:evict:b"));
      assertEquals("c", live.cache().get("live:evict:c"));
      assertEquals(0L, verifier().exists("live:evict:a", "live:evict:b"));
    }
  }

  @Test
  void aLargePayloadSurvivesTheTrip() {
    // Netty 4.2 changed its default buffer allocator; a cached page is far bigger than a counter.
    String big = "0123456789abcdef".repeat(32 * 1024); // 512 KiB
    try (Live live = onPlainRedis()) {
      live.cache().put("live:big", big, 60);

      assertEquals(big, live.cache().get("live:big"));
    }
  }

  @Test
  void aPasswordProtectedRedisIsReachedWithTheConfiguredPassword() {
    try (Live live = onPasswordedRedis(PASSWORD)) {
      live.cache().put("live:auth", "behind-a-password", 60);

      assertEquals("behind-a-password", live.cache().get("live:auth"));
    }
  }

  @Test
  void aWrongPasswordIsServedUncachedAndNothingIsStored() {
    // The producer itself fails, so a misconfigured password is visible in the log...
    try (Live wrong = onPasswordedRedis("not-the-password")) {
      assertThrows(RuntimeException.class, wrong.producer()::redisCommands);
    }
    // ...and the cache turns that into misses, never an exception.
    try (Live wrong = onPasswordedRedis("not-the-password")) {
      assertDoesNotThrow(() -> wrong.cache().put("live:wrong-auth", "never-stored", 60));
      assertNull(wrong.cache().get("live:wrong-auth"));
    }
    // Nothing reached Redis under the wrong credentials.
    try (Live right = onPasswordedRedis(PASSWORD)) {
      assertNull(right.cache().get("live:wrong-auth"));
    }
  }

  @Test
  @SuppressWarnings("resource") // stopped inside the test
  void aRedisThatGoesAwayIsServedUncachedNotAsAFailure() {
    GenericContainer<?> mortal = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    mortal.start();
    try (Live live = Live.to(mortal.getHost(), mortal.getMappedPort(6379), null)) {
      live.cache().put("live:mortal", "alive", 60);
      assertEquals("alive", live.cache().get("live:mortal"));

      mortal.stop();

      // The live connection is cut under the cache. A read must come back as a miss, in about the
      // producer's command timeout rather than Lettuce's 60 seconds, and a write must not throw.
      long started = System.nanoTime();
      assertNull(live.cache().get("live:mortal"));
      assertDoesNotThrow(() -> live.cache().put("live:mortal", "x", 60));
      assertDoesNotThrow(() -> live.cache().evict("live:mortal"));
      Duration took = Duration.ofNanos(System.nanoTime() - started);
      assertTrue(
          took.compareTo(Duration.ofSeconds(5)) < 0,
          "an outage must cost one command timeout, not a hung request: " + took);
    } finally {
      if (mortal.isRunning()) mortal.stop();
    }
  }
}
