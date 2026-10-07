package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/**
 * KafkaEventLoop builds a real KafkaConsumer in its constructor, but the constructor doesn't touch
 * the network (connection attempts only happen on poll()), so it's safe to instantiate here and
 * drive the private retry-tracking logic directly via reflection — same approach CartServiceTest
 * uses where there's no DI seam to mock instead.
 */
class KafkaEventLoopTest {

  private static KafkaEventLoop newLoop() {
    return new KafkaEventLoop(
        "test-loop", "localhost:9092", "test-group", List.of("some-topic"), (t, v) -> {});
  }

  private static int recordAttempt(KafkaEventLoop loop, TopicPartition tp, long offset)
      throws Exception {
    Method m =
        KafkaEventLoop.class.getDeclaredMethod("recordAttempt", TopicPartition.class, long.class);
    m.setAccessible(true);
    return (int) m.invoke(loop, tp, offset);
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<TopicPartition, ?> attempts(KafkaEventLoop loop) throws Exception {
    Field f = KafkaEventLoop.class.getDeclaredField("attempts");
    f.setAccessible(true);
    return (java.util.Map<TopicPartition, ?>) f.get(loop);
  }

  @Test
  void aLoopMayStartAtTheLatestOffsetAndRefusesAnUnknownReset() {
    // A projection of live events starts at the latest offset; a typo is refused up front rather
    // than read as a default the author did not choose.
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            new KafkaEventLoop(
                "latest-loop", "localhost:9092", "g", List.of("t"), "latest", (t, v) -> {}));
    org.junit.jupiter.api.Assertions.assertThrows(
        org.apache.kafka.common.config.ConfigException.class,
        () ->
            new KafkaEventLoop(
                "odd-loop", "localhost:9092", "g", List.of("t"), "sideways", (t, v) -> {}));
  }

  @Test
  void attemptCountIncreasesOnRepeatedFailuresOfTheSameOffset() throws Exception {
    KafkaEventLoop loop = newLoop();
    var tp = new TopicPartition("some-topic", 0);

    assertEquals(1, recordAttempt(loop, tp, 42L));
    assertEquals(2, recordAttempt(loop, tp, 42L));
    assertEquals(3, recordAttempt(loop, tp, 42L));
  }

  @Test
  void attemptCountResetsWhenThePartitionMovesPastTheStuckOffset() throws Exception {
    KafkaEventLoop loop = newLoop();
    var tp = new TopicPartition("some-topic", 0);

    recordAttempt(loop, tp, 42L);
    recordAttempt(loop, tp, 42L);

    assertEquals(1, recordAttempt(loop, tp, 43L));
  }

  @Test
  void clearingAnAttemptRemovesItsTrackingEntry() throws Exception {
    KafkaEventLoop loop = newLoop();
    var tp = new TopicPartition("some-topic", 0);

    recordAttempt(loop, tp, 42L);
    assertEquals(1, attempts(loop).size());

    attempts(loop).remove(tp);
    assertEquals(0, attempts(loop).size());
  }

  @Test
  void differentPartitionsTrackAttemptsIndependently() throws Exception {
    KafkaEventLoop loop = newLoop();
    var tp0 = new TopicPartition("some-topic", 0);
    var tp1 = new TopicPartition("some-topic", 1);

    recordAttempt(loop, tp0, 1L);
    recordAttempt(loop, tp0, 1L);
    recordAttempt(loop, tp1, 1L);

    assertEquals(3, recordAttempt(loop, tp0, 1L));
    assertEquals(2, recordAttempt(loop, tp1, 1L));
  }

  /**
   * A dead letter the broker never acknowledges is not a handled record: the loop keeps the record
   * for another attempt rather than skipping it and losing it.
   */
  @Test
  void aDeadLetterTheBrokerNeverAcknowledgesIsNotCountedAsWritten() {
    MockProducer<String, String> producer =
        new MockProducer<>(true, new StringSerializer(), new StringSerializer()) {
          @Override
          public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record) {
            return CompletableFuture.failedFuture(new RuntimeException("broker down"));
          }
        };
    var rec = new ConsumerRecord<>("storeql.x", 0, 7L, "k", "{}");
    assertFalse(KafkaEventLoop.publishDeadLetter(producer, rec));
  }

  @Test
  void anAcknowledgedDeadLetterGoesToTheDltTopic() {
    MockProducer<String, String> producer =
        new MockProducer<>(true, new StringSerializer(), new StringSerializer());
    var rec = new ConsumerRecord<>("storeql.x", 0, 7L, "k", "{}");
    assertTrue(KafkaEventLoop.publishDeadLetter(producer, rec));
    assertEquals("storeql.x.DLT", producer.history().get(0).topic());
  }

  /**
   * A handler that fails with an {@link Error} is a failed record like any other: the partition is
   * rewound to it and the poll loop carries on. Before, the Error escaped the loop, the scheduled
   * poll task died, and nothing consumed the topic again until restart.
   */
  @Test
  void aHandlerErrorRewindsThePartitionAndDoesNotEscape() {
    KafkaEventLoop loop =
        new KafkaEventLoop(
            "errors",
            "localhost:1",
            "g",
            List.of("x"),
            (topic, value) -> {
              throw new AssertionError("boom");
            });
    var rec = new org.apache.kafka.clients.consumer.ConsumerRecord<>("x", 0, 5L, "k", "v");
    Map<TopicPartition, Long> rewind = loop.dispatch(List.of(rec));
    assertEquals(Map.of(new TopicPartition("x", 0), 5L), rewind);
  }

  /** A checked exception thrown past the compiler is still a failed record, not a dead loop. */
  @Test
  void aSneakyCheckedFailureRewindsThePartitionToo() {
    KafkaEventLoop loop =
        new KafkaEventLoop(
            "sneaky",
            "localhost:1",
            "g",
            List.of("x"),
            (topic, value) -> {
              KafkaEventLoopTest.<RuntimeException>sneaky(new IOException("disk gone"));
            });
    var rec = new org.apache.kafka.clients.consumer.ConsumerRecord<>("x", 0, 9L, "k", "v");
    assertEquals(Map.of(new TopicPartition("x", 0), 9L), loop.dispatch(List.of(rec)));
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneaky(Throwable t) throws E {
    throw (E) t;
  }

  /**
   * A loop is stalled once its broker has not answered for the window: readiness must say so, so a
   * replica whose broker went away stops taking traffic. Before, a readiness probe saw no failed
   * start and reported UP for a loop that had silently stopped.
   */
  @Test
  void aLoopWhoseBrokerStopsAnsweringIsUnhealthyAfterTheWindow() {
    KafkaEventLoop loop =
        new KafkaEventLoop("stall", "localhost:1", "g", List.of("x"), (topic, value) -> {});
    long window = 60_000;
    loop.markHealthy(1_000);
    assertTrue(loop.isHealthy(1_000 + window, window));
    assertFalse(loop.isHealthy(1_001 + window, window));
    loop.markHealthy(2_000_000);
    assertTrue(loop.isHealthy(2_000_001, window), "an answer brings it back");
  }
}
