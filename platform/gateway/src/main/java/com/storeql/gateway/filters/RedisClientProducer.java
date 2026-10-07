package com.storeql.gateway.filters;

import com.storeql.gateway.GatewayConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One shared Redis connection for the gateway's rate-limit and brute-force counters — state that
 * must be visible across every gateway replica, not held in per-instance heap.
 */
@ApplicationScoped
public class RedisClientProducer {

  @Inject GatewayConfig config;

  /**
   * The one client, its connection and the commands built on it. Set together, once, under {@link
   * #lock}; cleared together by {@link #close}. Volatile so the fast path reads them without
   * locking.
   */
  private volatile RedisClient client;

  private volatile StatefulRedisConnection<String, String> connection;
  private volatile RedisCommands<String, String> commands;

  /**
   * Serialises building the client. A call that finds the client ready never takes this lock. A
   * virtual-thread friendly lock, so a caller waiting on a connect parks rather than pinning a
   * carrier thread.
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Command timeout for the rate-limit and brute-force counters.
   *
   * <p>Lettuce defaults to 60 seconds. On the gateway that is the whole platform's availability:
   * every request passes through {@link RateLimitFilter} before reaching any upstream, so a hung
   * Redis would pin each request thread for a minute and the public door would stop answering
   * entirely. Fail fast instead and let the filters decide what to do without the counter.
   */
  private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(250);

  // the client and connection are kept for close(), which is the one place they are released
  @SuppressWarnings("PMD.CloseResource")
  @Produces
  @ApplicationScoped
  public RedisCommands<String, String> redisCommands() {
    RedisCommands<String, String> ready = commands;
    if (ready != null) {
      return ready;
    }
    lock.lock();
    try {
      if (commands == null) {
        RedisClient built = newClient();
        try {
          StatefulRedisConnection<String, String> conn = built.connect();
          connection = conn;
          client = built;
          commands = conn.sync();
        } catch (RuntimeException e) {
          // a failed connect must not leave its client's threads and sockets running
          built.shutdown();
          throw e;
        }
      }
      return commands;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Builds a client for this gateway's Redis, configured but not yet connected. Package-private so
   * a test can count the clients a run builds and stub their connections.
   *
   * @return a new, unconnected client
   */
  RedisClient newClient() {
    RedisURI uri =
        RedisURI.Builder.redis(config.redisHost(), config.redisPort())
            .withPassword(config.redisPassword().toCharArray())
            .withTimeout(COMMAND_TIMEOUT)
            .build();
    RedisClient built = RedisClient.create(uri);
    // While Redis is down a command is refused at once (the filters then let the request through
    // unmetered), never queued: an unbounded queue behind a dead connection is heap that grows
    // with the traffic, and every queued command still waits out its timeout.
    built.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .requestQueueSize(config.redisRequestQueueSize())
            .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
            .build());
    return built;
  }

  void close(@Disposes RedisCommands<String, String> ignored) {
    lock.lock();
    try {
      if (connection != null) {
        connection.close();
      }
      if (client != null) {
        client.shutdown();
      }
      connection = null;
      client = null;
      commands = null;
    } finally {
      lock.unlock();
    }
  }
}
