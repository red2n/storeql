package com.storeql.gateway.flow;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * A Lua script sent once and called by its digest after, the way {@code RateCounter} does it: a
 * Redis that has lost its script cache (a restart, a flush) answers NOSCRIPT and the script is
 * loaded again, once. A script runs whole, so the counts it adds and the expiry it sets cannot be
 * parted by a crash.
 */
final class RedisScript {

  private final String source;

  /** The digest once loaded; null until the first call, and again after NOSCRIPT. */
  private volatile String sha;

  RedisScript(String source) {
    this.source = source;
  }

  <T> T run(
      RedisCommands<String, String> redis, ScriptOutputType type, String[] keys, String... args) {
    String digest = sha;
    if (digest == null) {
      digest = redis.scriptLoad(source);
      sha = digest;
    }
    try {
      return redis.evalsha(digest, type, keys, args);
    } catch (RedisNoScriptException e) {
      digest = redis.scriptLoad(source);
      sha = digest;
      return redis.evalsha(digest, type, keys, args);
    }
  }
}
