package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The gateway's one shared Redis client. A failed connect must not leave its client running, and
 * callers must share one client rather than each building their own.
 */
class RedisClientProducerTest {

  /** Hands out mocked clients, and records every one so a test can see what was shut down. */
  private static final class Factory extends RedisClientProducer {
    final List<RedisClient> built = new ArrayList<>();
    final Set<RedisClient> shut = ConcurrentHashMap.newKeySet();
    final boolean connectFails;
    final RedisCommands<String, String> commands = mock(RedisCommands.class);

    Factory(boolean connectFails) {
      this.connectFails = connectFails;
    }

    @Override
    synchronized RedisClient newClient() {
      RedisClient client = mock(RedisClient.class);
      if (connectFails) {
        try {
          when(client.connect()).thenThrow(new RedisConnectionException("redis is down"));
        } catch (RedisConnectionException e) {
          throw new AssertionError(e);
        }
      } else {
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        when(connection.sync()).thenReturn(commands);
        when(client.connect()).thenReturn(connection);
      }
      org.mockito.Mockito.doAnswer(
              inv -> {
                shut.add(client);
                return null;
              })
          .when(client)
          .shutdown();
      built.add(client);
      return client;
    }
  }

  @Test
  void aFailedConnectShutsItsClientDownBeforeTheNextAttempt() {
    Factory f = new Factory(true);
    for (int i = 0; i < 10; i++) {
      assertThrows(RedisConnectionException.class, f::redisCommands);
    }
    assertEquals(10, f.built.size(), "one client per attempt, while Redis is down");
    assertEquals(10, f.shut.size(), "every failed client is shut down, not only the last");
  }

  @Test
  void aSuccessfulConnectIsSharedByEveryCaller() {
    Factory f = new Factory(false);
    RedisCommands<String, String> first = f.redisCommands();
    RedisCommands<String, String> second = f.redisCommands();
    assertSame(first, second);
    assertEquals(1, f.built.size());
    verify(f.built.get(0), times(1)).connect();
  }

  @Test
  void concurrentFirstCallersShareOneClient() throws Exception {
    Factory f = new Factory(false);
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch start = new CountDownLatch(1);
    List<Future<RedisCommands<String, String>>> calls = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      calls.add(
          pool.submit(
              () -> {
                start.await();
                return f.redisCommands();
              }));
    }
    start.countDown();
    RedisCommands<String, String> seen = calls.get(0).get(10, TimeUnit.SECONDS);
    for (Future<RedisCommands<String, String>> c : calls) {
      assertSame(seen, c.get(10, TimeUnit.SECONDS));
    }
    pool.shutdown();
    assertEquals(1, f.built.size(), "exactly one client, however many callers raced");
  }

  @Test
  void closeReleasesTheConnectionAndClientAndALaterCallRebuilds() {
    Factory f = new Factory(false);
    f.redisCommands();
    f.close(f.commands);
    verify(f.built.get(0)).shutdown();
    f.redisCommands();
    assertEquals(2, f.built.size(), "after close, the next call builds a fresh client");
    verify(f.built.get(0), times(1)).connect(); // the closed client is not reconnected
  }
}
