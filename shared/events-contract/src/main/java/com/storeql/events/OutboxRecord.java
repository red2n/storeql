package com.storeql.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared shape of a transactional-outbox row.
 *
 * <p>Producers write one {@code OutboxRecord} <em>in the same DB transaction</em> as the business
 * state change, guaranteeing the event and the data agree. A background drainer publishes
 * unpublished records to Kafka and marks {@link #publishedAt()}. Delivery is at-least-once, so
 * consumers must be idempotent.
 *
 * <p>Each service owns its own {@code outbox} table (database-per-service); this type just
 * standardizes the columns. Suggested DDL:
 *
 * <pre>{@code
 * CREATE TABLE outbox (
 *   id              UUID PRIMARY KEY,
 *   event_type      TEXT NOT NULL,
 *   topic           TEXT NOT NULL,
 *   tenant_id       UUID NOT NULL,
 *   aggregate_id    UUID NOT NULL,
 *   payload         JSONB NOT NULL,
 *   created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
 *   published_at    TIMESTAMPTZ,
 *   attempts        INT NOT NULL DEFAULT 0,
 *   next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 *   last_error      TEXT,
 *   dead_at         TIMESTAMPTZ
 * );
 * -- the relay's claim: its ordered scan, ORDER BY created_at, id LIMIT n
 * CREATE INDEX idx_outbox_claim ON outbox (created_at, id)
 *   WHERE published_at IS NULL AND dead_at IS NULL;
 * -- the claim's NOT EXISTS check for an earlier waiting row of the same aggregate
 * CREATE INDEX idx_outbox_aggregate_pending ON outbox (aggregate_id, created_at, id)
 *   WHERE published_at IS NULL;
 * -- the scheduled purge of delivered rows, oldest first
 * CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
 * }</pre>
 *
 * <p>The relay (common-service {@code BaseOutboxRepository}) claims the waiting rows in {@code
 * created_at, id} order; marking a row published and recording a failure go by primary key, and the
 * purge reads the published rows through {@code idx_outbox_published}. A row that fails is retried
 * after a backoff, and once it has failed {@code storeql.outbox.max-attempts} times it is a dead
 * letter ({@code dead_at}): never claimed again, kept for an operator, and it holds back its own
 * aggregate only.
 *
 * <p>The table is deliberately cross-tenant: one relay drains every business's rows and no outbox
 * statement filters by {@code tenant_id}, so no index starts with it. The row's {@code tenant_id}
 * is read only by the relay's dead-letter warning, which names whose event could not be published.
 *
 * @param id primary key of the outbox row; also becomes the Kafka record's dedupe/idempotency key
 * @param eventType PascalCase past-tense event type, e.g. {@code "OrderPlaced"}
 * @param topic destination Kafka topic; see {@link #topicFor(String, String)}
 * @param tenantId tenant the event belongs to
 * @param aggregateId id of the aggregate the event is about (e.g. orderId, storeId)
 * @param payload the serialized event JSON, written verbatim to the Kafka record value
 * @param createdAt when the row was inserted (same transaction as the business state change)
 * @param publishedAt when the drainer successfully published this row, or {@code null} if still
 *     pending
 */
public record OutboxRecord(
    UUID id,
    String eventType,
    String topic,
    UUID tenantId,
    UUID aggregateId,
    String payload,
    Instant createdAt,
    Instant publishedAt) {

  /**
   * @return {@code true} if this record has not yet been published to Kafka (i.e. {@link
   *     #publishedAt()} is {@code null})
   */
  public boolean isPending() {
    return publishedAt == null;
  }

  /**
   * Builds a topic name following the platform convention {@code storeql.<domain>.<event>}.
   *
   * @param domain the business domain, e.g. {@code "order"}
   * @param eventKebab the event name in kebab-case, e.g. {@code "order-placed"}
   * @return the fully-qualified topic name, e.g. {@code "storeql.order.order-placed"}
   */
  public static String topicFor(String domain, String eventKebab) {
    return "storeql." + domain + "." + eventKebab;
  }
}
