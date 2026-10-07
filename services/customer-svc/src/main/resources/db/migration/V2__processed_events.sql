-- Idempotency guard for event consumers: dedupe by event id (golden rule #7).
-- customer-svc's Kafka consumers write here (order-confirmed, which accrues loyalty; and the return,
-- void, refund and no-receipt-return events, which take loyalty back or credit store credit), and so
-- do the shared BaseJdbcRepository.markProcessedIfNew* helpers, so a redelivered event is applied once.
--
-- The dedupe key is (event_id, consumer), not event_id alone: one consumer's mark must not stop
-- another consumer from applying the same event (each consumer name is one purpose).
CREATE TABLE processed_events (
    event_id     UUID NOT NULL,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);

-- The scheduled purge deletes rows older than storeql.processed-events.retention-days, in bounded
-- batches ordered by processed_at; this index keeps each batch from scanning and sorting the table.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
