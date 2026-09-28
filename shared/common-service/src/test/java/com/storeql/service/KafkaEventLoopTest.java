package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
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
}
