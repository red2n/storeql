package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedisClientProducerTest {

  @Test
  void anUnreachableRedisNeverBuildsASecondClient() throws Exception {
    RedisClientProducer producer = new RedisClientProducer();
    producer.redisHost = "127.0.0.1";
    producer.redisPort = 1; // nothing listens here
    producer.redisPassword = Optional.empty();
    try {
      assertThrows(RuntimeException.class, producer::redisCommands);
      assertTrue(producer.hasClient());
      Object first = clientOf(producer);
      assertThrows(RuntimeException.class, producer::redisCommands);
      assertThrows(RuntimeException.class, producer::redisCommands);
      org.junit.jupiter.api.Assertions.assertSame(first, clientOf(producer));
    } finally {
      producer.close(null);
    }
  }

  private static Object clientOf(RedisClientProducer p) throws Exception {
    var f = RedisClientProducer.class.getDeclaredField("client");
    f.setAccessible(true);
    return f.get(p);
  }
}
