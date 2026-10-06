-- The transactional outbox (golden rule 6). cart-svc announces one thing: a departed business's
-- erasure (21.14), to tenant-svc's evidence. What it erased is written in the erasure's own
-- transaction, so an erasure is never recorded that did not happen, nor happens unrecorded.
--
-- A row that fails to publish is retried after a backoff (storeql.outbox.backoff-base-seconds,
-- doubling, capped at storeql.outbox.backoff-cap-seconds), and only that row's aggregate waits for
-- it. After storeql.outbox.max-attempts it is a dead letter: never claimed again, kept for an
-- operator, and it holds back its own aggregate only.
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

-- Every row not yet sent, dead letters included. The drain reads idx_outbox_claim instead, which
-- covers the same rows without the dead letters, so nothing in the drain needs this one.
-- A candidate to drop.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- The hourly purge (common-service OutboxPublisher, through BaseOutboxRepository) deletes published
-- outbox rows in batches, oldest first:
--
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--
-- With no index on published_at every batch scans and sorts the whole table. The index is partial,
-- like the claim's (idx_outbox_claim, which the drain reads): this one holds the rows already sent,
-- so it stays as small as the retention window once the purge keeps up.
--
-- cart-svc keeps no processed_events table, so a redelivered OrderPlaced is not deduplicated: it
-- marks the customer's ACTIVE cart checked out again, which closes a newer cart the shopper opened
-- since the first delivery (OrderPlacedHandler). The purge's second statement fails on that missing
-- table, which the purge takes as nothing to trim (BaseOutboxRepository.purgeProcessedEvents),
-- so it needs no index here.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;

-- The claim: rows neither published nor dead, in the order they were written. The drain also
-- checks next_attempt_at, so a row backing off is skipped by the query, not by this index.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
