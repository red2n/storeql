-- Idempotency guard for event consumers: dedupe by event id (golden rule 7). cart-svc's OrderPlaced
-- consumer writes here, on the same transaction that closes the shopper's cart, so a redelivered
-- event is recognised and closes nothing: without it, the redelivery would close a newer ACTIVE cart
-- the shopper opened since the first delivery.
--
-- The dedupe key is (event_id, consumer), not event_id alone: one consumer's mark must not stop
-- another consumer from applying the same event (each consumer name is one purpose).
--
-- It is delivery machinery, not a business's data: no tenant_id, and the data export leaves it out
-- (TenantDataSpec.INFRASTRUCTURE).
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, consumer)
);

-- The hourly purge (common-service OutboxPublisher, through BaseOutboxRepository) deletes rows
-- older than storeql.processed-events.retention-days in bounded batches ordered by processed_at,
-- and this index keeps each batch from scanning and sorting the table:
--
--   DELETE FROM processed_events WHERE (event_id, consumer) IN (SELECT event_id, consumer
--     FROM processed_events WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
