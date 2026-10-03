-- Hardening (round 3): the scheduled purge in common-service's OutboxPublisher works in bounded
-- batches, and without an index to walk each batch scanned its whole table.
--
-- Published outbox rows older than the retention (BaseOutboxRepository.purgePublished):
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ?
--     ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
-- A partial index holds only the rows already published, oldest first, so a batch reads exactly
-- the rows it deletes. The rows still to publish keep idx_outbox_unpublished (V1), the drain's.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;

-- Consumer dedupe rows older than their retention (BaseOutboxRepository.purgeProcessedEvents;
-- this schema's timestamp is processed_at):
--   DELETE FROM processed_events WHERE event_id IN (SELECT event_id FROM processed_events
--     WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
