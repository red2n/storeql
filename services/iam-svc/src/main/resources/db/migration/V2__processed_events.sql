-- Idempotency guard for event consumers: dedupe by event id (golden rule #7).
--
-- The key is (event_id, consumer), not event_id alone: one consumer's mark must not stop another
-- consumer from applying the same event (each consumer name is one purpose).
CREATE TABLE processed_events (
    event_id     UUID,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, consumer)
);
-- The scheduled purge deletes the oldest marks in batches, ordered by processed_at.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
