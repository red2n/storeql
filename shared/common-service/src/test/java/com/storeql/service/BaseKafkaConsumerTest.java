package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage: a consumer that fails to start (bad config, unreachable broker, etc.) used
 * to log a WARNING and otherwise leave the service looking perfectly healthy — readiness never
 * checked Kafka, so the pod stayed in rotation while permanently never processing another event.
 * start() must now register the failure in KafkaConsumerRegistry. No CDI container here — same
 * reflection-based approach as KafkaEventLoopTest/BaseJdbcRepositoryTest.
 */
class BaseKafkaConsumerTest {

  private static final String EXPLODING_NAME = "exploding-test-consumer";
  private static final String HEALTHY_NAME = "healthy-test-consumer";

  private static final class ExplodingConsumer extends BaseKafkaConsumer {
    @Override
    protected List<String> topics() {
      // Simulates any failure while assembling the loop (bad config, resolving a topic name,
      // etc.) — thrown before KafkaEventLoop's constructor runs, so no network is touched.
      throw new RuntimeException("bad config");
    }

    @Override
    protected String consumerName() {
      return EXPLODING_NAME;
    }

    @Override
    protected String groupId() {
      return "test-group";
    }

    @Override
    protected void handle(String topic, String value) {}
  }

  private static class HealthyConsumer extends BaseKafkaConsumer {
    @Override
    protected List<String> topics() {
      return List.of("some-topic");
    }

    @Override
    protected String consumerName() {
      return HEALTHY_NAME;
    }

    @Override
    protected String groupId() {
      return "test-group";
    }

    @Override
    protected void handle(String topic, String value) {}
  }

  /** A projection of live events: it starts where the topic is now, not at the beginning. */
  private static final class LatestConsumer extends HealthyConsumer {
    @Override
    protected String offsetReset() {
      return "latest";
    }
  }

  private static void enable(BaseKafkaConsumer c) throws Exception {
    setField(c, "kafkaEnabled", true);
    setField(c, "bootstrap", "localhost:9092");
  }

  private static void setField(Object o, String name, Object value) throws Exception {
    Field f = BaseKafkaConsumer.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, value);
  }

  private static void start(BaseKafkaConsumer c) throws Exception {
    Method m = BaseKafkaConsumer.class.getDeclaredMethod("start");
    m.setAccessible(true);
    m.invoke(c);
  }

  private static void stop(BaseKafkaConsumer c) throws Exception {
    Method m = BaseKafkaConsumer.class.getDeclaredMethod("stop");
    m.setAccessible(true);
    m.invoke(c);
  }

  @AfterEach
  void cleanup() {
    KafkaConsumerRegistry.clear(EXPLODING_NAME);
    KafkaConsumerRegistry.clear(HEALTHY_NAME);
  }

  @Test
  void aConsumerStartsAtTheEarliestOffsetUnlessItSaysOtherwise() throws Exception {
    org.junit.jupiter.api.Assertions.assertEquals("earliest", new HealthyConsumer().offsetReset());
    // What the consumer answers is what its loop is built with.
    var c = new LatestConsumer();
    enable(c);
    start(c);
    try {
      var f = BaseKafkaConsumer.class.getDeclaredField("loop");
      f.setAccessible(true);
      org.junit.jupiter.api.Assertions.assertEquals(
          "latest", ((KafkaEventLoop) f.get(c)).offsetReset());
    } finally {
      stop(c);
    }
  }

  @Test
  void aConsumerThatFailsToStartIsRegisteredAsFailed() throws Exception {
    var c = new ExplodingConsumer();
    enable(c);

    start(c);

    assertTrue(KafkaConsumerRegistry.failedConsumers().contains(EXPLODING_NAME));
  }

  @Test
  void aConsumerThatStartsSuccessfullyIsNotRegisteredAsFailed() throws Exception {
    var c = new HealthyConsumer();
    enable(c);

    start(c);
    try {
      assertFalse(KafkaConsumerRegistry.failedConsumers().contains(HEALTHY_NAME));
    } finally {
      stop(c);
    }
  }

  @Test
  void stoppingAConsumerClearsItsFailedRegistration() throws Exception {
    var c = new ExplodingConsumer();
    enable(c);
    start(c);
    assertTrue(KafkaConsumerRegistry.failedConsumers().contains(EXPLODING_NAME));

    stop(c);

    assertFalse(KafkaConsumerRegistry.failedConsumers().contains(EXPLODING_NAME));
  }

  @Test
  void aDisabledConsumerNeverRegistersAsFailedEvenIfItWouldHaveExploded() throws Exception {
    var c = new ExplodingConsumer();
    setField(c, "kafkaEnabled", false);
    setField(c, "bootstrap", "localhost:9092");

    start(c);

    assertFalse(KafkaConsumerRegistry.failedConsumers().contains(EXPLODING_NAME));
  }
}
