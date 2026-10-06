-- The transactional outbox (golden rule 6). What this service announces: each purge of the
-- notification log (21.16) is announced to tenant-svc's register, and each text sent is announced
-- too (SmsSent, 21.10) so tenant-svc can meter it. The purge deletes in batches, each committed on
-- its own, and its announcement is written last, in a transaction of its own: a run that dies half
-- way has deleted its finished batches and announced nothing, and the next run finishes the rest.
--
-- The outbox is cross-tenant on purpose. The relay drains and the purge deletes every business's
-- rows together, never one business's, so no index here leads with tenant_id, and tenant_id is
-- nullable: it is NULL for an event that belongs to no business, as iam-svc's account events are
-- (every announcement this service writes carries one).
--
-- Retry and dead-letter state. A row that fails to publish is retried after a backoff
-- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
-- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
-- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
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
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);

-- The claim (common-service BaseOutboxRepository.claim): rows that may publish now, in the order they
-- were written. This index serves its ordered scan (ORDER BY created_at, id LIMIT n); dead letters
-- are left out. The claim's check for an earlier waiting row of the same aggregate reads the index
-- below, and the outcome of a drain goes by primary key. No index of every unpublished row by
-- created_at (idx_outbox_unpublished) is kept: it would also hold the dead letters, which the
-- claim's ordered scan never reads. OutboxPurgeIT plans the claim as the relay sends it and asserts
-- both indexes.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is dead or
-- backing off.
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
-- would scan and sort the whole table. The unpublished outbox rows, which the claim reads, stay out
-- of this index: it is partial on what the purge reads (published rows), so the claim's indexes
-- never carry a delivered row and this one never carries a waiting row.
CREATE INDEX idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;
