-- The hourly purge (common-service OutboxPublisher, through BaseOutboxRepository) trims this
-- service's bookkeeping in batches, oldest first:
--
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--   DELETE FROM processed_events WHERE event_id IN (SELECT event_id FROM processed_events
--     WHERE created_at < ? ORDER BY created_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
-- With no index on the column a batch orders by, every batch scans and sorts the whole table, so a
-- purge costs more the longer the history it is there to trim. The outbox index is partial, like the
-- drain's (idx_outbox_unpublished holds the rows still to send, this one the rows already sent):
-- it stays as small as the retention window once the purge keeps up. order-svc's dedupe table keeps
-- its timestamp in created_at (the purge asks for processed_at first and falls back to it).
CREATE INDEX IF NOT EXISTS idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_processed_events_created_at ON processed_events (created_at);
