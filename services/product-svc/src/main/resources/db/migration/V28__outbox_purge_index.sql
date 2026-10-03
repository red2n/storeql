-- The scheduled outbox purge (common-service OutboxPublisher -> BaseOutboxRepository.purgePublished)
-- takes the rows that were published before the retention cutoff, a batch at a time:
--
--   DELETE FROM outbox WHERE id IN (
--     SELECT id FROM outbox
--      WHERE published_at IS NOT NULL AND published_at < ?
--      ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
-- idx_outbox_unpublished (V1) serves the relay's half of the table, the rows that are still waiting.
-- Nothing served the purge's: each batch read the whole table to find the old published rows, and
-- this is the table the relay reads on every tick.
--
-- Partial, so it holds only the rows the purge can take and shrinks as they are taken. Rows enter it
-- when the relay marks them published and leave it when the purge deletes them; the unpublished
-- backlog, the part the relay keeps hot, is never in it.
--
-- product-svc has no processed_events table: its one consumer, the erasure of a departed business,
-- is idempotent by construction (erasing twice finds nothing the second time), so it keeps no
-- dedupe rows. The purge's second statement finds no table and has nothing here to index.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;
