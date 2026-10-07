-- Usage metering and quotas (21.10). A plan (21.8) says how many things a business may have — stores,
-- staff, products. This is the other half: how much it does in a billing period — orders taken, texts
-- sent — what the plan includes of it, and what is owed beyond that.
--
-- Three rules run through the schema:
--   * Usage is RECORDED ONCE, from the event of the service that did the thing, keyed on that thing:
--     an order counted is an order id, so the till's offline replay of the same sale is not a second
--     order. The record is append-only; nothing edits what was used.
--   * Usage is BILLED IN ARREARS, on the renewal that bills the next period in advance, so one invoice
--     carries both — the shape every metered SaaS bill has.
--   * What is billed is SNAPSHOT per period, in the invoice's own transaction: everything recorded
--     before the period ended, less what earlier periods already billed. A record that commits a
--     moment after its period was billed is billed with the next one — late, never lost, never twice.

-- ── what a plan includes of each meter ────────────────────────────────────────
-- The platform's price list, like plan_entitlements: replaced whole, a meter left out is one the plan
-- does not name (unlimited, uncharged).
CREATE TABLE plan_meters (
    plan_id   UUID    NOT NULL REFERENCES plans (id),
    meter     TEXT    NOT NULL,   -- ORDERS | SMS: what the catalogue calls it
    -- How many are included each billing period. Null: unlimited.
    included  BIGINT,
    -- Whether use beyond what is included is refused rather than charged. Only a meter the catalogue
    -- marks refusable may be hard: an order is never refused, a marketing text may be.
    hard      BOOLEAN NOT NULL,

    PRIMARY KEY (plan_id, meter),
    CONSTRAINT chk_plan_meters_meter CHECK (meter IN ('ORDERS', 'SMS')),
    CONSTRAINT chk_plan_meters_included CHECK (included IS NULL OR included >= 0),
    -- A hard ceiling on unlimited use means nothing.
    CONSTRAINT chk_plan_meters_hard CHECK (NOT hard OR included IS NOT NULL)
);

-- What each unit beyond the included costs, per currency and from a date. Never edited: a new row
-- takes effect and the old one stays, as with plan_prices, so an overage billed last year is still
-- explicable. A period is charged at the price in force on the day it started.
CREATE TABLE plan_meter_prices (
    id             UUID          PRIMARY KEY,
    plan_id        UUID          NOT NULL REFERENCES plans (id),
    meter          TEXT          NOT NULL,
    currency       TEXT          NOT NULL,
    unit_amount    NUMERIC(18,4) NOT NULL,
    effective_from DATE          NOT NULL,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_plan_meter_prices_meter CHECK (meter IN ('ORDERS', 'SMS')),
    CONSTRAINT chk_plan_meter_prices_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_plan_meter_prices_amount CHECK (unit_amount >= 0)
);
CREATE UNIQUE INDEX uq_plan_meter_prices_day
    ON plan_meter_prices (plan_id, meter, currency, effective_from);

-- ── what a business used ──────────────────────────────────────────────────────
-- One row per thing counted. Append-only.
CREATE TABLE usage_records (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    meter       TEXT        NOT NULL,
    -- Orders count one each; a text counts the segments it was sent as, because that is what the
    -- carrier charges for.
    quantity    BIGINT      NOT NULL,
    -- What was counted, in the words of the service that counted it: an order id, a sent text's event
    -- id. Unique per business and meter, so a redelivered event is the same usage.
    source_ref  TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_usage_records_meter CHECK (meter IN ('ORDERS', 'SMS')),
    CONSTRAINT chk_usage_records_quantity CHECK (quantity > 0)
);
CREATE UNIQUE INDEX uq_usage_records_source ON usage_records (tenant_id, meter, source_ref);
-- What a period's total and a quota check read: one business's meter over a span of time.
CREATE INDEX idx_usage_records_span ON usage_records (tenant_id, meter, recorded_at);

-- What was billed for one meter over one period, written in the transaction that raised the invoice.
-- Append-only. A period ended with the subscription (cancelled at its end) and nothing over the
-- allowance has no invoice, and says so with a null invoice_id.
CREATE TABLE usage_periods (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    subscription_id UUID          NOT NULL REFERENCES subscriptions (id),
    meter           TEXT          NOT NULL,
    period_start    DATE          NOT NULL,
    period_end      DATE          NOT NULL,
    used            BIGINT        NOT NULL,
    included        BIGINT,                   -- as the plan said then; null: unlimited
    overage         BIGINT        NOT NULL,
    currency        TEXT          NOT NULL,
    unit_amount     NUMERIC(18,4),            -- null: the plan prices no overage for this meter
    amount          NUMERIC(18,4) NOT NULL,
    -- Why nothing was charged when something was over: TRIAL, NOT_PRICED. Null when charged, or when
    -- nothing was over.
    not_charged     TEXT,
    invoice_id      UUID          REFERENCES billing_invoices (id),
    created_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_usage_periods_meter CHECK (meter IN ('ORDERS', 'SMS')),
    CONSTRAINT chk_usage_periods_used CHECK (used >= 0 AND overage >= 0),
    CONSTRAINT chk_usage_periods_span CHECK (period_end > period_start),
    CONSTRAINT chk_usage_periods_amount CHECK (amount >= 0),
    CONSTRAINT chk_usage_periods_not_charged CHECK (
        not_charged IS NULL OR not_charged IN ('TRIAL', 'NOT_PRICED')
    )
);
-- A period is billed once. This is what makes a second replica's renewal roll back rather than bill
-- the same usage twice.
CREATE UNIQUE INDEX uq_usage_periods ON usage_periods (subscription_id, meter, period_start);
CREATE INDEX idx_usage_periods_tenant ON usage_periods (tenant_id, period_start DESC);

-- When a business crossed 80% and then all of what its plan includes, once per meter and period: so
-- it is told once, and the platform can see who is about to outgrow a plan.
CREATE TABLE usage_alerts (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    meter        TEXT        NOT NULL,
    period_start DATE        NOT NULL,
    threshold    SMALLINT    NOT NULL,   -- percent of what is included
    used         BIGINT      NOT NULL,
    included     BIGINT      NOT NULL,
    raised_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_usage_alerts_meter CHECK (meter IN ('ORDERS', 'SMS')),
    CONSTRAINT chk_usage_alerts_threshold CHECK (threshold IN (80, 100))
);
CREATE UNIQUE INDEX uq_usage_alerts ON usage_alerts (tenant_id, meter, period_start, threshold);
-- The platform's read across businesses: who reached a threshold lately, newest first.
CREATE INDEX idx_usage_alerts_recent ON usage_alerts (raised_at DESC);
