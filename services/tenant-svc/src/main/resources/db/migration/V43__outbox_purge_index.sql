-- The scheduled purge of delivered outbox rows (common-service OutboxPublisher, through
-- BaseOutboxRepository.purgePublished) deletes in batches of the oldest ones:
--   WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at LIMIT n
-- With no index each batch reads the whole table to find them. The index is partial: it holds only
-- delivered rows, so it stays small and the drain (idx_outbox_unpublished) is untouched.
-- tenant-svc keeps no processed_events table (its consumers dedupe on unique keys of their own,
-- such as usage_records' source_ref), so the purge of those has nothing to scan and needs no index.
CREATE INDEX IF NOT EXISTS idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
