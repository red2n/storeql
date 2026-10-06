-- Who made the sale, and what a period of them earned (store operations & workforce).
--
-- The platform could already say who rang a sale up — pos_log_entries.cashier_id, the POS journal —
-- and that is not the same question. The person who operated the till is not always the person who
-- sold the goods: on a counter, an assistant sells and a supervisor takes the money, and a shop that
-- pays commission pays the seller. The seller is orders.seller_user_id (V1__init.sql).

-- Attribution is corrected, not overwritten: money follows it, so who changed it, when, and why has
-- to survive the change. Append-only.
CREATE TABLE order_seller_changes (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    order_id      UUID        NOT NULL REFERENCES orders(id),
    from_user_id  UUID,
    to_user_id    UUID,
    reason        TEXT        NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL,
    changed_by    UUID        NOT NULL
);

CREATE INDEX idx_order_seller_changes_order ON order_seller_changes (tenant_id, order_id, changed_at DESC);

COMMENT ON TABLE order_seller_changes IS
    'Append-only record of who a sale was credited to and who changed it. Commission follows attribution, so a silent change is a silent payment.';

-- What a period earned, frozen once approved.
--
-- A statement is a draft until somebody approves it, and immutable after: a figure somebody was paid
-- on must not move because a scheme was corrected, a late refund landed, or a sale was re-attributed
-- afterwards. A statement that needs changing is superseded by a new one that says what it replaces.
--
-- Per currency, as every other period report here is: summing takings across currencies produces a
-- number that is not money in any of them.
CREATE TABLE commission_statements (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    -- Null means every store: a shop with one till does not want to choose, and a chain settling per
    -- store does.
    store_id       UUID,
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,
    currency       CHAR(3)       NOT NULL,
    status         TEXT          NOT NULL DEFAULT 'DRAFT',
    net_sales      NUMERIC       NOT NULL,
    commission     NUMERIC       NOT NULL,
    note           TEXT,
    supersedes     UUID          REFERENCES commission_statements(id) DEFERRABLE INITIALLY DEFERRED,
    superseded_by  UUID          REFERENCES commission_statements(id) DEFERRABLE INITIALLY DEFERRED,
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NOT NULL,
    approved_at    TIMESTAMPTZ,
    approved_by    UUID,

    CONSTRAINT chk_statement_status CHECK (status IN ('DRAFT', 'APPROVED', 'SUPERSEDED')),
    CONSTRAINT chk_statement_period CHECK (period_end >= period_start),
    -- Approved means somebody signed it off, and an approval with no approver is unattributable.
    CONSTRAINT chk_statement_approved CHECK (
        (status <> 'APPROVED') OR (approved_at IS NOT NULL AND approved_by IS NOT NULL)
    )
);

-- One approved statement per scope and period. NULLS NOT DISTINCT, because a NULL store_id in a
-- plain unique index would let a whole-business statement be approved twice for the same month.
CREATE UNIQUE INDEX uq_statement_approved_period ON commission_statements (
    tenant_id, store_id, period_start, period_end, currency
) NULLS NOT DISTINCT WHERE status = 'APPROVED' AND superseded_by IS NULL;

CREATE INDEX idx_statements_tenant ON commission_statements (tenant_id, period_start DESC, created_at DESC);

COMMENT ON TABLE commission_statements IS
    'What a period earned, per currency and optionally per store. A draft until approved, immutable after, superseded when it has to change.';

-- One row per person, per stretch of days under one arrangement, per rate band — so a statement
-- explains itself in the terms the arrangement was written in, and a person paid on it can check
-- which part of what they sold earned which rate.
--
-- A stretch under no arrangement is a row with no scheme and no rate, carrying the sales that earned
-- nothing. Leaving those out would make a statement whose sales do not add up to the period's
-- takings, which is the first thing anybody checks.
CREATE TABLE commission_statement_lines (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    statement_id   UUID          NOT NULL REFERENCES commission_statements(id),
    seller_user_id UUID          NOT NULL,
    scheme_id      UUID,
    scheme_name    TEXT,
    segment_from   DATE          NOT NULL,
    segment_to     DATE          NOT NULL,
    threshold_from NUMERIC,
    rate           NUMERIC(12,4),
    -- Net sales under a percentage arrangement, units under a per-unit one: the same thing the rate
    -- is charged on. Deliberately unconstrained and written as computed, not rounded to the statement's
    -- minor units, so a count of units keeps the fraction the rate was set against. See the header of
    -- V1__init.sql for the money columns generally.
    amount         NUMERIC        NOT NULL,
    commission     NUMERIC        NOT NULL,
    -- A per-unit arrangement pays an amount per unit in the currency it names, which may be any ISO 4217
    -- code; a statement is counted in one currency. The commission is in the statement's currency,
    -- translated at the business's own rate when it has to be, and a line that showed a rate of 0.50 beside
    -- a commission of 0.43 would explain nothing to the person paid on it. So the line keeps the rate's
    -- currency and the commission as rated in it, and the statement explains itself after the rate has
    -- moved on. Both are null on a percentage line, whose rate is a ratio, and where the rate needed no
    -- translation.
    rate_currency    CHAR(3),
    rated_commission NUMERIC,

    CONSTRAINT chk_line_segment CHECK (segment_to >= segment_from),
    CONSTRAINT chk_line_rated_commission CHECK (rated_commission IS NULL OR rate_currency IS NOT NULL)
);

CREATE INDEX idx_statement_lines_statement
    ON commission_statement_lines (tenant_id, statement_id, seller_user_id, segment_from);

COMMENT ON TABLE commission_statement_lines IS
    'One row per person, per stretch under one arrangement, per rate band. A row with no scheme carries sales that earned nothing.';
