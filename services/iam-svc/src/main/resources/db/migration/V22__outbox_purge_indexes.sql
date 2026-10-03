-- The scheduled purge (common-service OutboxPublisher, through BaseOutboxRepository) deletes in
-- batches of the oldest rows:
--   outbox            WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at
--   processed_events  WHERE processed_at < ? ORDER BY processed_at
-- With no index each batch reads the whole table to find its rows. The outbox index is partial: it
-- holds only delivered rows, so it stays small and the drain (idx_outbox_unpublished) is untouched.
CREATE INDEX IF NOT EXISTS idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at ON processed_events (processed_at);
