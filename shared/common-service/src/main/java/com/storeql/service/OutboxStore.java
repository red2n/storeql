package com.storeql.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the shared {@link OutboxPublisher} needs from a service's repository: atomically claim a
 * batch of unpublished rows and mark delivered. The service's repo implements this (it already has
 * the outbox table). Keeps the publisher generic.
 */
@FunctionalInterface
public interface OutboxStore {

  /**
   * Takes this schema's drain right, or returns empty when another replica holds it. Production
   * runs several replicas of every service, and each drains the same outbox: without one drainer at
   * a time, a second replica could publish an aggregate's later row while the first is still
   * publishing its earlier one. The right is a session-level advisory lock on a connection the
   * lease holds; it is released when the lease closes, or when the process or connection dies.
   *
   * @return the lease, or empty when another drainer holds the schema
   */
  default Optional<DrainLease> tryDrainLock() {
    return Optional.of(() -> {});
  }

  /**
   * Claims up to {@code limit} rows that may publish now, hands them to {@code publish}, and
   * records the outcome: delivered rows are marked published; a failed row gets one more attempt, a
   * backoff before its next try, and after the configured attempts a dead letter. Only the holder
   * of {@code lease} may call this.
   *
   * <p>A row is claimable only when no earlier row of its aggregate is still unpublished, so a row
   * that keeps failing holds back its own aggregate and nothing else. No transaction is open while
   * {@code publish} runs.
   *
   * @param lease the drain right from {@link #tryDrainLock()}
   * @param limit the most rows to claim in this call
   * @param publish sends the claimed rows and reports which were delivered and which failed
   * @return how many rows were claimed, and which of them were marked published
   */
  DrainResult drainOnce(
      DrainLease lease, int limit, Function<List<PendingOutbox>, PublishOutcome> publish);

  /** The drain right; closing it releases the lock. */
  @FunctionalInterface
  interface DrainLease extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * What one drain call did.
   *
   * @param claimed rows claimed and handed to publish, whatever their outcome
   * @param published the claimed rows that were delivered and marked published
   */
  record DrainResult(int claimed, List<UUID> published) {}

  /**
   * What publish reports back for a batch.
   *
   * @param published ids delivered
   * @param failed ids that were attempted and not delivered, with the reason for the log and the
   *     row (rows held back behind a failure are in neither list and are not counted as attempts)
   */
  record PublishOutcome(List<UUID> published, Map<UUID, String> failed) {}

  /**
   * Deletes outbox rows that were PUBLISHED before {@code cutoff}, at most {@code batch} rows. Rows
   * not yet published are never touched. Operational data, not business history: the event already
   * reached Kafka.
   *
   * @param cutoff rows with {@code published_at} before this instant are eligible
   * @param batch the most rows to delete in this call (bounded so no purge holds a long lock)
   * @return how many rows were deleted; callers repeat while it equals {@code batch}
   */
  default int purgePublished(java.time.Instant cutoff, int batch) {
    return 0;
  }

  /**
   * Deletes consumer dedupe rows ({@code processed_events}) recorded before {@code cutoff}, at most
   * {@code batch} rows. The cutoff must be older than the longest Kafka retention plus consumer
   * lag: dedupe only has to outlive possible redelivery.
   *
   * @param cutoff rows processed before this instant are eligible
   * @param batch the most rows to delete in this call
   * @return how many rows were deleted; callers repeat while it equals {@code batch}
   */
  default int purgeProcessedEvents(java.time.Instant cutoff, int batch) {
    return 0;
  }

  /**
   * A pending outbox row: where to publish and what.
   *
   * @param id the outbox row's primary key
   * @param aggregateId the aggregate the event is about, used as the Kafka record key so every
   *     event about one aggregate lands on one partition and is consumed in the order it was
   *     written — two redefinitions of one role in one second must not arrive swapped
   * @param topic destination Kafka topic
   * @param payload the serialized event JSON, sent verbatim as the Kafka record value
   */
  record PendingOutbox(UUID id, UUID aggregateId, String topic, String payload) {

    /** The record key: the aggregate when the row names one, else the row itself. */
    public String key() {
      return (aggregateId != null ? aggregateId : id).toString();
    }
  }
}
