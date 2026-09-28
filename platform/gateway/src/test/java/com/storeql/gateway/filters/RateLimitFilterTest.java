package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import com.storeql.test.RedisSupport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Counters now live in Redis (shared across gateway replicas) instead of gateway heap, so these
 * tests run against a real Redis container rather than mocking the storage layer — a mock would
 * just verify the mock, not the atomic INCR+EXPIRE behaviour the fix depends on.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  private static RedisSupport REDIS;
  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext requestContext;

  private RateLimitFilter filter;

  @BeforeAll
  static void startRedis() {
    REDIS = RedisSupport.start();
    client = RedisClient.create(RedisURI.Builder.redis(REDIS.host(), REDIS.port()).build());
    connection = client.connect();
  }

  @AfterAll
  static void stopRedis() {
    connection.close();
    client.shutdown();
    REDIS.stop();
  }

  @BeforeEach
  void setUp() {
    connection.sync().flushall();
    filter = new RateLimitFilter();
    filter.config = config;
    filter.counter = counterOn(connection);
  }

  @AfterEach
  void cleanUp() {
    connection.sync().flushall();
  }

  @Test
  void shouldAbortRequestAfterRateLimitExceeded() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    verify(requestContext, times(1)).abortWith(any());
  }

  @Test
  void rotatingForwardedForHeaderDoesNotEscapeTheLimitWhenTrustDisabled() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);
    when(config.trustForwardedHeaders()).thenReturn(false);
    // Attacker rotates X-Forwarded-For per request; without a trusted proxy the header must
    // never even be read, so all requests land in the same (socket-derived) bucket.
    org.mockito.Mockito.lenient()
        .when(requestContext.getHeaderString("X-Forwarded-For"))
        .thenReturn("10.0.0.1", "10.0.0.2", "10.0.0.3");

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    verify(requestContext, times(1)).abortWith(any());
  }

  @Test
  void forwardedForIsHonouredOnlyBehindTrustedProxy() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);
    when(config.trustForwardedHeaders()).thenReturn(true);
    when(requestContext.getHeaderString("X-Forwarded-For"))
        .thenReturn("10.0.0.1", "10.0.0.2", "10.0.0.3");

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    // three distinct clients as reported by the trusted proxy — nobody throttled
    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void counterIsSharedAcrossInstancesViaRedis() throws IOException {
    // The whole point of the fix: a second filter instance (i.e. a second gateway replica) must
    // see the same counter, because it lives in Redis rather than per-instance heap.
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);
    when(config.trustForwardedHeaders()).thenReturn(false);

    RateLimitFilter secondReplica = new RateLimitFilter();
    secondReplica.config = config;
    secondReplica.counter = counterOn(connection);

    filter.filter(requestContext); // replica 1: count=1
    secondReplica.filter(requestContext); // replica 2: count=2
    secondReplica.filter(requestContext); // replica 2: count=3 -> rejected

    verify(requestContext, times(1)).abortWith(any());
  }

  @Test
  void counterResetsAfterTheWindowExpires() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(1);
    when(config.trustForwardedHeaders()).thenReturn(false);

    filter.filter(requestContext);
    String key = "ratelimit:" + ClientIp.resolve(requestContext, null, false);
    assertEquals(RateLimitFilter.WINDOW_SECONDS, connection.sync().ttl(key));
  }

  /** The shared counter, on this test's Redis. */
  private static RateCounter counterOn(StatefulRedisConnection<String, String> c) {
    RateCounter counter = new RateCounter();
    counter.redis = c.sync();
    return counter;
  }
}
