-- Idempotency guard for event consumers: dedupe by event id (golden rule #7).
-- payment-svc consumes order events (OrderReturned, OrderCancelled, OrderVoided and the line events
-- that close or substitute a line refund what they took), GiftCardRedeemed and
-- ContainerDepositRefunded. The shared BaseJdbcRepository.markProcessedIfNew* helpers write here so
-- a redelivered event is acted on at most once.
--
-- The dedupe key is (event_id, consumer), not event_id alone: one consumer's mark must not stop
-- another consumer from applying the same event (each consumer name is one purpose).
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     UUID NOT NULL,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);

-- Read by the scheduled purge, oldest first (see the purge statement in V1__init.sql). Written once
-- per event handled and read only by its primary key, so this plain index is its whole cost.
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
