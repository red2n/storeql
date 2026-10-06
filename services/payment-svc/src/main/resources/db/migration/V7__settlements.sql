-- Reconciliation against the acquirer's settlement file (11.10). A card payment is a promise until
-- the acquirer pays it out: days later, in a batch, net of its fees, of refunds and of chargebacks.
-- The settlement file says which payments the batch covers. Each line is matched to the tender, the
-- refund or the dispute this service holds; what does not match is an exception a manager decides;
-- and a batch with nothing left open is reconciled, which is what finally clears card clearing to
-- the bank in the ledger.
--
-- Tenders and refunds stay append-only (golden rule #8): that a payment has settled is a fact about
-- a settlement line, so the link lives on the line and nothing here updates a tender.

CREATE TABLE IF NOT EXISTS settlement_batches (
    id                UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    store_id          UUID,                     -- the store a merchant account belongs to, when it is one store's
    provider          VARCHAR(30)   NOT NULL,   -- who paid: the acquirer or provider, as the business names it
    reference         VARCHAR(255)  NOT NULL,   -- the payout or batch number
    format            VARCHAR(20)   NOT NULL,   -- the file's layout: a parser's name, so not a list kept here
    currency          VARCHAR(3)    NOT NULL,
    payout_date       DATE          NOT NULL,
    declared_net      NUMERIC(18,4),            -- what the acquirer says it paid, when the file or the person says
    sales_amount      NUMERIC(18,4) NOT NULL,   -- each of the five as the lines add up
    refund_amount     NUMERIC(18,4) NOT NULL,
    chargeback_amount NUMERIC(18,4) NOT NULL,
    fee_amount        NUMERIC(18,4) NOT NULL,
    net_amount        NUMERIC(18,4) NOT NULL,
    line_count        INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    idempotency_key   VARCHAR(255),
    imported_by       UUID          NOT NULL,
    imported_at       TIMESTAMPTZ   NOT NULL,
    reconciled_by     UUID,                     -- null when every line matched and nobody had to decide
    reconciled_at     TIMESTAMPTZ,

    PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_settlement_batches_status CHECK (status IN ('EXCEPTIONS', 'READY', 'RECONCILED')),
    CONSTRAINT ck_settlement_batches_reconciled CHECK (
        (status = 'RECONCILED') = (reconciled_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_settlement_batches_tenant_date
    ON settlement_batches (tenant_id, payout_date DESC, id);
CREATE INDEX IF NOT EXISTS idx_settlement_batches_tenant_status
    ON settlement_batches (tenant_id, status, payout_date DESC);

-- A payout is imported once, however many times its file is uploaded.
CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_batches_reference
    ON settlement_batches (tenant_id, provider, reference);
CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_batches_idempotency
    ON settlement_batches (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS settlement_lines (
    id                 UUID          NOT NULL,
    tenant_id          UUID          NOT NULL,
    batch_id           UUID          NOT NULL,
    line_no            INTEGER       NOT NULL,
    type               VARCHAR(24)   NOT NULL,
    reference          VARCHAR(255),            -- what the acquirer calls the transaction
    original_reference VARCHAR(255),            -- for a refund or a chargeback: the payment it is about
    gross_amount       NUMERIC(18,4) NOT NULL,  -- signed from the business's side: money in is positive
    fee_amount         NUMERIC(18,4) NOT NULL,  -- what the acquirer kept: a cost is positive
    net_amount         NUMERIC(18,4) NOT NULL,  -- gross less fee
    occurred_at        TIMESTAMPTZ,
    match_status       VARCHAR(20)   NOT NULL,
    matched_tender_id  UUID,
    matched_refund_id  UUID,
    matched_dispute_id UUID,
    store_id           UUID,                    -- the store of what it matched: the ledger is kept by store
    expected_amount    NUMERIC(18,4),           -- what this service holds, when it differs from the line
    resolution         VARCHAR(24),
    resolved_by        UUID,
    resolved_at        TIMESTAMPTZ,
    note               TEXT,

    PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_settlement_lines_type CHECK (
        type IN ('SALE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'FEE', 'ADJUSTMENT')
    ),
    CONSTRAINT ck_settlement_lines_match CHECK (
        match_status IN ('MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH', 'DUPLICATE', 'NOT_APPLICABLE')
    ),
    CONSTRAINT ck_settlement_lines_resolution CHECK (
        resolution IS NULL
        OR resolution IN ('MATCHED_BY_HAND', 'DIFFERENCE_ACCEPTED', 'UNALLOCATED')
    ),
    CONSTRAINT ck_settlement_lines_adds_up CHECK (net_amount = gross_amount - fee_amount),
    -- A decision has somebody's name and a time on it.
    CONSTRAINT ck_settlement_lines_resolved CHECK (
        (resolution IS NULL) = (resolved_by IS NULL) AND (resolution IS NULL) = (resolved_at IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_lines_batch_line
    ON settlement_lines (tenant_id, batch_id, line_no);

-- What is still open in a batch.
CREATE INDEX IF NOT EXISTS idx_settlement_lines_open
    ON settlement_lines (tenant_id, batch_id, match_status) WHERE resolution IS NULL;

-- A payment settles once, a refund once, and a dispute once each way: the second line that claims
-- one is the exception, not a second settlement.
CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_lines_tender
    ON settlement_lines (tenant_id, matched_tender_id)
    WHERE matched_tender_id IS NOT NULL AND type = 'SALE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_lines_refund
    ON settlement_lines (tenant_id, matched_refund_id)
    WHERE matched_refund_id IS NOT NULL AND type = 'REFUND';
CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_lines_dispute
    ON settlement_lines (tenant_id, matched_dispute_id, type)
    WHERE matched_dispute_id IS NOT NULL;
