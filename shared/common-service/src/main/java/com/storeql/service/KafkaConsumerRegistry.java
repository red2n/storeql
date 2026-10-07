package com.storeql.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks Kafka consumers that failed to start. {@link BaseKafkaConsumer} used to swallow a start
 * failure behind a log line, so the service stayed "ready" forever while silently never processing
 * events. {@link HealthChecks.KafkaConsumerReadiness} reads this registry so a start failure fails
 * the readiness probe instead (golden rule #12: readiness checks DB+Kafka+config).
 */
final class KafkaConsumerRegistry {

  private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

  /** The loops that are running, by name, so readiness can ask whether each is still answered. */
  private static final Map<String, KafkaEventLoop> RUNNING = new ConcurrentHashMap<>();

  private KafkaConsumerRegistry() {}

  /**
   * @param consumerName the failing consumer's {@code consumerName()}
   */
  static void markFailed(String consumerName) {
    FAILED.add(consumerName);
  }

  /**
   * Removes {@code consumerName} from the failed set — called both on a successful start and on
   * {@link BaseKafkaConsumer#stop()}, so a shut-down consumer doesn't keep failing readiness.
   *
   * @param consumerName the consumer's {@code consumerName()}
   */
  static void clear(String consumerName) {
    FAILED.remove(consumerName);
  }

  /**
   * @return a snapshot of every consumer name currently marked failed; empty when all are healthy
   */
  static Set<String> failedConsumers() {
    return Set.copyOf(FAILED);
  }

  /** Records a running loop, so readiness can see whether its broker still answers. */
  static void track(KafkaEventLoop loop) {
    RUNNING.put(loop.name(), loop);
  }

  /** Forgets a loop that has stopped. */
  static void untrack(String loopName) {
    RUNNING.remove(loopName);
  }

  /**
   * @param now the current time, in epoch milliseconds
   * @param windowMillis how long a broker may go without answering before its loop is stalled
   * @return the names of running loops whose broker has not answered within the window
   */
  // the registry only reads the loops; each is closed by its owner, which also untracks it
  @SuppressWarnings("PMD.CloseResource")
  static Set<String> stalledConsumers(long now, long windowMillis) {
    Set<String> stalled = new java.util.HashSet<>();
    for (KafkaEventLoop loop : RUNNING.values()) {
      if (!loop.isHealthy(now, windowMillis)) stalled.add(loop.name());
    }
    return Set.copyOf(stalled);
  }
}
