-- payment-svc schema
-- Golden rule #8: payments and refunds are append-only.
--
-- The outbox is the one table here with no index led by tenant_id, and that is on purpose: the
-- relay drains every business's rows together, in the order they were written, so its indexes
-- lead with created_at, aggregate_id or published_at.

CREATE TABLE IF NOT EXISTS payment_tenders (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    amount          NUMERIC(14,4) NOT NULL,
    -- How the money moved: CASH | CARD | UPI | WALLET | GIFT_CARD | VOUCHER | STORE_CREDIT |
    -- EXCHANGE, exactly the set the service writes, held by chk_payment_tenders_method below.
    method          VARCHAR(30) NOT NULL,
    reference       VARCHAR(255),           -- card auth code, gift-card code, etc.
    idempotency_key VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'CAPTURED', -- CAPTURED | FAILED
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The store the money was taken at, so Z-reports aggregate by store without joining order-svc
    -- (database-per-service). Null where the payment named no store.
    store_id        UUID,
    -- How a till CARD tender came to be recorded: TERMINAL (a card machine StoreQL drives approved it)
    -- or STANDALONE (the cashier recorded what a machine StoreQL does not see took, with that machine's
    -- receipt reference). Null for every other tender and for a card paid online.
    entry_mode      VARCHAR(12),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT chk_payment_tenders_entry_mode CHECK (entry_mode IS NULL OR entry_mode IN ('TERMINAL', 'STANDALONE')),
    CONSTRAINT chk_payment_tenders_method CHECK (
        method IN ('CASH', 'CARD', 'UPI', 'WALLET', 'GIFT_CARD', 'VOUCHER', 'STORE_CREDIT',
                   'EXCHANGE')
    )
);

CREATE TABLE IF NOT EXISTS refund_tenders (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    payment_id      UUID        NOT NULL,
    amount          NUMERIC(14,4) NOT NULL,
    -- How the value went back, from the set of payment_tenders.method (held by
    -- chk_refund_tenders_method below). A refund a person records (POST /payments/by-order/{id}/
    -- refunds) is under the method the request names. A refund of an order's tenders (a
    -- cancellation, a void, a return to the original tender, an exchange's cash-back) is under the
    -- method of the tender refunded, except that a return whose value goes to a liability writes
    -- STORE_CREDIT or GIFT_CARD, and an exchange's own refund EXCHANGE. A card payment taken on a
    -- terminal is refunded as CARD when the machine put it back, or, when it was given back another
    -- way, as that way (CASH, CARD for the acquirer's own refund, UPI or WALLET): so a refund of a
    -- CARD tender may carry any of those, and payment_id says which tender it is of.
    method          VARCHAR(30) NOT NULL,
    reference       VARCHAR(255),
    idempotency_key VARCHAR(255),
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The store the refund belongs to: the store of the payment it refunds, or an exchange's own
    -- store. A store's reads take it from the refund alone, so every write path sets it wherever the
    -- payment named one.
    store_id        UUID,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT chk_refund_tenders_method CHECK (
        method IN ('CASH', 'CARD', 'UPI', 'WALLET', 'GIFT_CARD', 'VOUCHER', 'STORE_CREDIT',
                   'EXCHANGE')
    )
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
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
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

-- Indexes for the relay and the scheduled purge (common-service OutboxPublisher ->
-- BaseOutboxRepository).
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
-- A published row is in neither of the claim's indexes below, which hold only waiting rows, so
-- this partial index on published_at holds the published rows and no others.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;

-- The claim (common-service BaseOutboxRepository.claim): rows that may publish now, in the order
-- they were written. This index serves its ordered scan (ORDER BY created_at, id LIMIT n); a dead
-- letter is not in it. The claim's check for an earlier waiting row of the same aggregate reads the
-- index below, and marking a row published and recording a failure go by primary key. No index of
-- every unpublished row by created_at (idx_outbox_unpublished) is kept: it would also hold the dead
-- letters, which the claim's ordered scan never reads, and the claim is the one statement that reads
-- waiting rows in that order. OutboxPurgeIndexIT plans the claim as the shared repository runs it.
CREATE INDEX IF NOT EXISTS idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is dead or
-- backing off.
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
