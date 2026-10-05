-- Outbox retry and dead-letter state. A row that fails to publish is retried after a backoff
-- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
-- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
-- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
ALTER TABLE outbox
    ADD COLUMN attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_error      TEXT,
    ADD COLUMN dead_at         TIMESTAMPTZ;

ALTER TABLE outbox
    ADD CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL);

-- The claim: rows that may publish now, in the order they were written.
CREATE INDEX IF NOT EXISTS idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
