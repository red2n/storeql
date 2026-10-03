-- The scheduled purge (common-service OutboxPublisher -> BaseOutboxRepository) deletes two kinds of
-- row once they are old enough, a batch at a time, and each batch has to find its rows by age.
--
-- 1. Published outbox rows (purgePublished):
--
--      DELETE FROM outbox WHERE id IN (
--        SELECT id FROM outbox
--         WHERE published_at IS NOT NULL AND published_at < ?
--         ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
--    idx_outbox_unpublished (V3) serves the relay's half, the rows still waiting. Nothing served
--    this one. Partial, so it holds only what the purge can take and shrinks as it takes it; the
--    unpublished backlog is never in it.
--
-- 2. Consumer dedupe rows (purgeProcessedEvents), by the column this table calls processed_at:
--
--      DELETE FROM processed_events WHERE event_id IN (
--        SELECT event_id FROM processed_events
--         WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
--    processed_events is keyed by event_id alone, so finding the old rows read the whole table: the
--    table every consumed event adds a row to, and the one the dedupe check reads on every event.
--    A plain index on the age the purge orders by; the oldest rows are the ones it takes first.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
