-- The transactional outbox (golden rule 6). A departed business's erasure (21.14) is the first thing
-- this service announces: what it erased goes to tenant-svc's evidence, written in the erasure's own
-- transaction so an erasure is never recorded that did not happen, nor happens unrecorded.
--
-- Retry and dead-letter state. A row that fails to publish is retried after a backoff
-- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds), and
-- only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead letter: never
-- claimed again, kept for an operator, and it holds back its own aggregate only.
--
-- The outbox is deliberately cross-tenant: the relay drains every business's rows, oldest first, and
-- no statement on the table filters by tenant_id, so no index here leads with it. The column says
-- whose event a row is; the only code that reads it is the relay's dead-letter warning
-- (BaseOutboxRepository.recordFailures).
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

-- The claim (common-service BaseOutboxRepository.claim): rows that may publish now, in the order they
-- were written. This index serves its ordered scan (ORDER BY created_at, id LIMIT n); a dead letter
-- is not in it. Marking a row published and recording a failure go by primary key. No index of every
-- unpublished row by created_at (idx_outbox_unpublished) is kept: it would also hold the dead
-- letters, which the claim's ordered scan never reads.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check, the claim's NOT EXISTS: an aggregate's earlier unpublished rows, and
-- whether any is dead or backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;

-- The scheduled purge deletes published rows once they are old enough, a batch at a time, found by age:
--
--   DELETE FROM outbox WHERE id IN (
--     SELECT id FROM outbox
--      WHERE published_at IS NOT NULL AND published_at < ?
--      ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
-- idx_outbox_published serves that search. Partial, so it holds only published rows and shrinks as
-- the purge takes them; the unpublished backlog is never in it.
CREATE INDEX idx_outbox_published
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;
