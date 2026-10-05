package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KafkaConsumerRegistryTest {

  @AfterEach
  void cleanup() {
    KafkaConsumerRegistry.clear("payment-consumer");
    KafkaConsumerRegistry.clear("inventory-consumer");
  }

  @Test
  void startsEmpty() {
    assertEquals(Set.of(), KafkaConsumerRegistry.failedConsumers());
  }

  @Test
  void markFailedAddsTheConsumerName() {
    KafkaConsumerRegistry.markFailed("payment-consumer");

    assertEquals(Set.of("payment-consumer"), KafkaConsumerRegistry.failedConsumers());
  }

  @Test
  void trackMultipleFailedConsumersIndependently() {
    KafkaConsumerRegistry.markFailed("payment-consumer");
    KafkaConsumerRegistry.markFailed("inventory-consumer");

    assertEquals(
        Set.of("payment-consumer", "inventory-consumer"), KafkaConsumerRegistry.failedConsumers());
  }

  @Test
  void clearRemovesOnlyTheNamedConsumer() {
    KafkaConsumerRegistry.markFailed("payment-consumer");
    KafkaConsumerRegistry.markFailed("inventory-consumer");

    KafkaConsumerRegistry.clear("payment-consumer");

    assertTrue(KafkaConsumerRegistry.failedConsumers().contains("inventory-consumer"));
    assertEquals(1, KafkaConsumerRegistry.failedConsumers().size());
  }

  /** A running loop whose broker stops answering is reported stalled until it answers again. */
  @Test
  void aRunningLoopThatStopsAnsweringIsReportedStalled() {
    KafkaEventLoop loop =
        new KafkaEventLoop("stalled-probe", "localhost:1", "g", List.of("x"), (t, v) -> {});
    KafkaConsumerRegistry.track(loop);
    try {
      loop.markHealthy(0);
      long window = 60_000;
      assertEquals(
          Set.of("stalled-probe"), KafkaConsumerRegistry.stalledConsumers(window + 1, window));

      loop.markHealthy(window + 1);
      assertEquals(Set.of(), KafkaConsumerRegistry.stalledConsumers(window + 2, window));
    } finally {
      KafkaConsumerRegistry.untrack("stalled-probe");
    }
  }
}
