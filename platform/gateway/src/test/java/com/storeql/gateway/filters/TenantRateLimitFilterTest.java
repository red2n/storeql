package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import com.storeql.ids.Ids;
import com.storeql.test.RedisSupport;
import com.storeql.web.HttpHeaders;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A plan's requests a minute are counted per business, after the request is known to be that
 * business's (21.11): the request over the rate is a 429 that says how long to wait; another
 * business's counter is its own; a request with no tenant — the platform's administrator, a sign-in
 * — is not counted; a business whose plan names no rate is not limited.
 */
@ExtendWith(MockitoExtension.class)
class TenantRateLimitFilterTest {

  private static RedisSupport REDIS;
  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  @Mock GatewayConfig config;
  @Mock TenantAllowances allowances;
  @Mock ContainerRequestContext ctx;

  private TenantRateLimitFilter filter;

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
    filter = new TenantRateLimitFilter();
    filter.config = config;
    filter.allowances = allowances;
    filter.counter = new RateCounter();
    filter.counter.redis = connection.sync();
  }

  @AfterEach
  void cleanUp() {
    connection.sync().flushall();
  }

  @Test
  void theRequestOverThePlansRateIsRefusedAndToldHowLongToWait() {
    String tenant = Ids.newId().toString();
    when(config.rateLimitEnabled()).thenReturn(true);
    when(ctx.getHeaderString(HttpHeaders.TENANT_ID)).thenReturn(tenant);
    when(allowances.requestsPerMinute(tenant)).thenReturn(OptionalLong.of(2));

    filter.filter(ctx);
    filter.filter(ctx);
    filter.filter(ctx);

    ArgumentCaptor<Response> refused = ArgumentCaptor.forClass(Response.class);
    verify(ctx, times(1)).abortWith(refused.capture());
    assertEquals(429, refused.getValue().getStatus());
    String retryAfter = refused.getValue().getHeaderString("Retry-After");
    long seconds = Long.parseLong(retryAfter);
    assertTrue(seconds >= 1 && seconds <= 60, "Retry-After is the rest of the minute: " + seconds);
    assertTrue(refused.getValue().getEntity().toString().contains(TenantRateLimitFilter.CODE));
  }

  @Test
  void eachBusinessHasItsOwnMinute() {
    String a = Ids.newId().toString();
    String b = Ids.newId().toString();
    when(config.rateLimitEnabled()).thenReturn(true);
    when(ctx.getHeaderString(HttpHeaders.TENANT_ID)).thenReturn(a, a, a, b);
    when(allowances.requestsPerMinute(any())).thenReturn(OptionalLong.of(2));

    filter.filter(ctx);
    filter.filter(ctx);
    filter.filter(ctx); // a's third: refused
    filter.filter(ctx); // b's first: fine

    verify(ctx, times(1)).abortWith(any());
  }

  @Test
  void noTenantAndNoRateAreNotCounted() {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(ctx.getHeaderString(HttpHeaders.TENANT_ID)).thenReturn(null);
    for (int i = 0; i < 5; i++) filter.filter(ctx);

    String unlimited = Ids.newId().toString();
    when(ctx.getHeaderString(HttpHeaders.TENANT_ID)).thenReturn(unlimited);
    when(allowances.requestsPerMinute(unlimited)).thenReturn(OptionalLong.empty());
    for (int i = 0; i < 5; i++) filter.filter(ctx);

    verify(ctx, never()).abortWith(any());
    assertEquals(0, connection.sync().keys("ratelimit:tenant:*").size(), "nothing was counted");
  }

  @Test
  void switchedOffWithTheRestOfRateLimiting() {
    when(config.rateLimitEnabled()).thenReturn(false);
    for (int i = 0; i < 5; i++) filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    verify(allowances, never()).requestsPerMinute(any());
  }
}
