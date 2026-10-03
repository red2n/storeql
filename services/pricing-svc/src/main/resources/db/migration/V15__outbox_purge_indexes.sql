-- Indexes for the scheduled purge (common-service OutboxPublisher -> BaseOutboxRepository).
--
-- Once an hour the purge deletes, in batches of a thousand, the outbox rows that were published
-- more than a retention ago and the processed_events rows older than the dedupe window. Each batch
-- is this statement, oldest first:
--
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--   DELETE FROM processed_events WHERE event_id IN (SELECT event_id FROM processed_events
--     WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ?  FOR UPDATE SKIP LOCKED)
--
-- The only outbox index is the drain's (created_at WHERE published_at IS NULL), which holds none of
-- the rows the purge wants, and processed_events has only its primary key: so every batch scanned
-- and sorted the whole table to find its thousand rows. A published row is the one the drain index
-- has already let go of; a partial index on published_at holds exactly those and costs the drain
-- nothing. processed_events is written once per event handled and read only by its primary key, so
-- the plain index on the timestamp is the whole cost.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
