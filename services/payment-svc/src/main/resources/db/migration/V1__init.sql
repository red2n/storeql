-- payment-svc schema
-- Golden rule #8: payments and refunds are append-only.

CREATE TABLE IF NOT EXISTS payment_tenders (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    amount          NUMERIC(14,4) NOT NULL,
    method          VARCHAR(30) NOT NULL,   -- CASH | CARD | GIFT_CARD | VOUCHER
    reference       VARCHAR(255),           -- card auth code, gift-card code, etc.
    idempotency_key VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'CAPTURED', -- CAPTURED | FAILED
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The store the money was taken at, so Z-reports aggregate by store without joining order-svc
    -- (database-per-service). Null where the payment named no store.
    store_id        UUID,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS refund_tenders (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    payment_id      UUID        NOT NULL,
    amount          NUMERIC(14,4) NOT NULL,
    method          VARCHAR(30) NOT NULL,
    reference       VARCHAR(255),
    idempotency_key VARCHAR(255),
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The store the refund belongs to: the store of the payment it refunds, or an exchange's own
    -- store. A store's reads take it from the refund alone, so every write path sets it wherever the
    -- payment named one.
    store_id        UUID,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id              UUID        NOT NULL PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    tenant_id       UUID,
    aggregate_id    UUID        NOT NULL,
    payload         TEXT        NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Retry and dead-letter state. A row that fails to publish is retried after a backoff
    -- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
    -- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
    -- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_idempotency
    ON payment_tenders (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Tenant index for fast order-scoped queries
CREATE INDEX IF NOT EXISTS idx_payment_tenders_order
    ON payment_tenders (tenant_id, order_id);

CREATE INDEX IF NOT EXISTS idx_payment_tenders_store
    ON payment_tenders (tenant_id, store_id, created_at DESC)
    WHERE store_id IS NOT NULL;

-- Matching looks a tender up by what the acquirer calls it.
CREATE INDEX IF NOT EXISTS idx_payment_tenders_tenant_reference
    ON payment_tenders (tenant_id, reference) WHERE reference IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_idempotency
    ON refund_tenders (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refund_tenders_order
    ON refund_tenders (tenant_id, order_id);

CREATE INDEX IF NOT EXISTS idx_refund_tenders_payment
    ON refund_tenders (tenant_id, payment_id);

CREATE INDEX IF NOT EXISTS idx_refund_tenders_store
    ON refund_tenders (tenant_id, store_id, created_at DESC)
    WHERE store_id IS NOT NULL;

-- Matching looks a refund up by what the acquirer calls it.
CREATE INDEX IF NOT EXISTS idx_refund_tenders_tenant_reference
    ON refund_tenders (tenant_id, reference) WHERE reference IS NOT NULL;

-- Indexes for the scheduled purge (common-service OutboxPublisher -> BaseOutboxRepository).
--
-- Once an hour the purge deletes, in batches of a thousand, the outbox rows that were published
-- more than a retention ago and the processed_events rows older than the dedupe window. Each batch
-- is this statement, oldest first:
--
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--   DELETE FROM processed_events WHERE (event_id, consumer) IN (SELECT event_id, consumer
--     FROM processed_events WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--
-- A published row is one the drain index below does not hold, so a partial index on published_at
-- holds exactly the rows the purge wants and costs the drain nothing.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox (created_at) WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;

-- The claim: rows that may publish now, in the order they were written.
CREATE INDEX IF NOT EXISTS idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
