-- The scheduled purge (common-service OutboxPublisher, hourly, in bounded batches) deletes
--   * published outbox rows older than storeql.outbox.retention-days, and
--   * consumer dedupe rows (processed_events) older than storeql.processed-events.retention-days.
-- Each batch is
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--   DELETE FROM processed_events WHERE event_id IN (SELECT event_id FROM processed_events
--     WHERE processed_at < ? ORDER BY processed_at LIMIT ? FOR UPDATE SKIP LOCKED)
-- and with no index on either column every batch scanned and sorted the whole table. The unpublished
-- outbox rows, which the drain reads, stay out of the first index: it is partial on what the purge
-- reads and nothing else, so it costs nothing while the relay keeps up.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
