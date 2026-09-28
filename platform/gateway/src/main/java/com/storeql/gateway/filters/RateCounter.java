package com.storeql.gateway.filters;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * A counter per key per fixed window, in Redis, shared by every rate limit the gateway keeps.
 *
 * <p>One round trip: the increment and, on the first hit, the expiry that opens the window, so a
 * crash between the two cannot leave a key that counts forever. When Redis cannot be reached the
 * count is zero and the request goes through unmetered: a rate limit is there to shed floods, and a
 * store that cannot reach its counter must not stop trading over it.
 */
@ApplicationScoped
public class RateCounter {

  private static final Logger LOG = System.getLogger(RateCounter.class.getName());

  private static final String INCR_WITH_EXPIRE_SCRIPT =
      "local current = redis.call('INCR', KEYS[1]) "
          + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
          + "return {current, redis.call('TTL', KEYS[1])}";

  @Inject RedisCommands<String, String> redis;

  /**
   * One hit on a key.
   *
   * @param count how many hits the window has seen, this one included; zero when Redis is away
   * @param secondsLeft how long until the window resets, which is what a refusal tells the caller
   */
  public record Hit(long count, long secondsLeft) {}

  /**
   * Counts one hit against {@code key} in a window of {@code windowSeconds} that opens on the first
   * hit.
   */
  public Hit consume(String key, long windowSeconds) {
    try {
      List<Object> reply =
          redis.eval(
              INCR_WITH_EXPIRE_SCRIPT,
              ScriptOutputType.MULTI,
              new String[] {key},
              String.valueOf(windowSeconds));
      if (reply == null || reply.size() < 2) return new Hit(0L, windowSeconds);
      long count = ((Number) reply.get(0)).longValue();
      long ttl = ((Number) reply.get(1)).longValue();
      return new Hit(count, ttl > 0 ? ttl : windowSeconds);
    } catch (RedisException e) {
      LOG.log(
          Level.WARNING,
          "Rate-limit counter unavailable — allowing request unmetered: {0}",
          e.toString());
      return new Hit(0L, windowSeconds);
    }
  }
}
