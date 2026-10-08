package com.storeql.gateway.flow;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Redis for every test in the JVM that needs one, started on first use and left to the
 * Testcontainers reaper at exit: the flow tests are about what a real Redis does (scripts, key
 * expiry, sorted sets), and a container per class would cost more than the tests. It asks for a
 * password because the gateway's client always sends one.
 */
final class TestRedis {

  static final String PASSWORD = "flow-test";

  private static final class Holder {
    @SuppressWarnings("resource") // stopped by the reaper when the JVM ends
    static final GenericContainer<?> CONTAINER =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);

    static final RedisClient CLIENT;
    static final StatefulRedisConnection<String, String> CONNECTION;

    static {
      CONTAINER.start();
      CLIENT =
          RedisClient.create(
              RedisURI.Builder.redis(CONTAINER.getHost(), CONTAINER.getMappedPort(6379))
                  .withPassword(PASSWORD.toCharArray())
                  .build());
      CONNECTION = CLIENT.connect();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    CONNECTION.close();
                    CLIENT.shutdown();
                  }));
    }
  }

  private TestRedis() {}

  static String host() {
    return Holder.CONTAINER.getHost();
  }

  static int port() {
    return Holder.CONTAINER.getMappedPort(6379);
  }

  /** A direct line to the Redis, for tests to read and write behind the gateway's back. */
  static RedisCommands<String, String> commands() {
    return Holder.CONNECTION.sync();
  }
}
