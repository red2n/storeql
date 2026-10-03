-- Indexes for the outbox's drain and for the scheduled purge (common-service OutboxPublisher ->
-- BaseOutboxRepository).
--
-- The drain reads the oldest rows not yet published, a batch at a time, many times a second:
--
--   SELECT id, aggregate_id, topic, payload FROM outbox WHERE published_at IS NULL
--     ORDER BY created_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED
--
-- V1 indexed the outbox on (published, created_at) WHERE NOT published. Nothing writes that boolean:
-- the shared drain marks published_at and leaves published false, so the index holds every row ever
-- written, and its predicate is not the drain's, so the drain could not use it. The drain's own
-- index is the one every other service has, with the id the ORDER BY ends in, so that a batch reads
-- its rows in order and never sorts; it holds only what is waiting, and so stays small while the
-- relay keeps up. The dead index goes: it grew with every event and served nothing.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox (created_at, id) WHERE published_at IS NULL;

DROP INDEX IF EXISTS outbox_unpublished;

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
-- With no index on either timestamp every batch scanned and sorted the whole table to find its
-- thousand rows. A published row is the one the drain index has let go of; a partial index on
-- published_at holds exactly those and costs the drain nothing. processed_events is written once per
-- event handled and read only by its primary key, so the plain index on the timestamp is the whole
-- cost.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
