package com.storeql.service;

import java.util.List;
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
   * Locks up to {@code limit} unpublished rows ({@code FOR UPDATE SKIP LOCKED} — production runs
   * multiple replicas of every service, so without this every replica's drain would claim and
   * re-publish the same rows), hands them to {@code publish}, and — inside that same transaction —
   * marks published exactly the ids it returns. Rows {@code publish} doesn't report back stay
   * unpublished and are claimable again (by any replica) on the next drain.
   *
   * @param limit max rows to claim in one call
   * @param publish sends the claimed rows and returns the ids that were confirmed delivered
   * @return the ids that were claimed and successfully marked published
   */
  List<UUID> drainAndPublish(int limit, Function<List<PendingOutbox>, List<UUID>> publish);

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
