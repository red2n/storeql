-- The transactional outbox (golden rule 6). What this service announces: each purge of the
-- notification log (21.16) is announced to tenant-svc's register. The purge deletes in batches,
-- each committed on its own, and the announcement is written last, in a transaction of its own:
-- a run that dies half way has deleted its finished batches and announced nothing, and the next
-- run finishes the rest. Each text sent is announced too (SmsSent, 21.10), so
-- tenant-svc can meter it.
--
-- Retry and dead-letter state. A row that fails to publish is retried after a backoff
-- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
-- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
-- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
-- tenant_id is NULL for an event that belongs to no business, as iam-svc's account events are;
-- every announcement this service writes carries one. No index leads with it: the relay and the
-- purge read every business's rows, never one business's.
CREATE TABLE outbox (
    id              UUID        PRIMARY KEY,
    event_type      TEXT        NOT NULL,
    topic           TEXT        NOT NULL,
    tenant_id       UUID,
    aggregate_id    UUID        NOT NULL,
    payload         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- The claim: rows that may publish now, in the order they were written.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;

-- The scheduled purge (common-service OutboxPublisher, hourly, in bounded batches) deletes
--   * published outbox rows older than storeql.outbox.retention-days, and
--   * consumer dedupe rows (processed_events) older than storeql.processed-events.retention-days.
-- Each batch is one of
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--   DELETE FROM processed_events WHERE (event_id, consumer) IN (SELECT event_id, consumer
--     FROM processed_events WHERE processed_at < ? ORDER BY processed_at LIMIT ?
--     FOR UPDATE SKIP LOCKED)
-- and without an index on each batch's column (published_at here, processed_at in V1) every batch
-- would scan and sort the whole table. The unpublished outbox rows, which the drain reads, stay out
-- of this index: it is partial on what the purge reads and nothing else, so it costs nothing while
-- the relay keeps up.
CREATE INDEX idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;
