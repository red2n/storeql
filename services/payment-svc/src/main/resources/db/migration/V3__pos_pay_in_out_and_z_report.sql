-- POS cash management: pay-in / pay-out (petty cash) and daily Z-report settlement.

-- ── Pay-in / Pay-out (petty cash) ────────────────────────────────────────────
-- Distinct from cash_drops (safe drops). Pay-out = petty cash expense taken from drawer.
-- Pay-in = cash added to drawer for non-sale reasons (e.g. change fund replenishment).
-- Append-only: no UPDATE/DELETE.
CREATE TABLE IF NOT EXISTS cash_movements (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    till_session_id UUID          NOT NULL,
    direction       TEXT          NOT NULL,   -- PAY_IN | PAY_OUT
    amount          NUMERIC(14,4) NOT NULL,
    reason          TEXT          NOT NULL,
    authorised_by   UUID,                     -- supervisor UUID when required
    recorded_by     UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- A retried movement with the same Idempotency-Key is the same movement (golden rule #11).
    idempotency_key VARCHAR(255),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_cash_movements_session
    ON cash_movements (tenant_id, till_session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cash_movements_store
    ON cash_movements (tenant_id, store_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_movement_idempotency
    ON cash_movements (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ── Daily Z-report ────────────────────────────────────────────────────────────
-- One row per store per business day. A manager settles it (POST /admin/cash/z-report) once the
-- store's tills for the day are closed; the settle is refused while a till session at the store is
-- still open (409 Z_REPORT_SESSIONS_OPEN).
-- Summarises all tender types and cash movements for reconciliation.
-- Append-only: a day is settled once. A correction is a NEW ROW with version n+1 that names the
-- row it replaces (replaces_id) and says why (correction_reason); nothing is overwritten, which is
-- why the unique key includes the version.
-- time_zone is the zone the day was counted in. Only a row settled before the column existed has it
-- null: the answer then reads UTC with zoneAssumed true, though the column's zone_assumed stays
-- false for that row. zone_assumed is true when the zone was assumed rather than read from the
-- store.
-- No DEFAULT on currency: a day's currency is always bound, never assumed.
-- The money columns hold four places, which is every ISO 4217 currency's minor units (the most is
-- four, CLF and UYW), so a day in a three-place currency is never rounded to two.
CREATE TABLE IF NOT EXISTS z_reports (
    id                UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    store_id          UUID          NOT NULL,
    business_date     DATE          NOT NULL,
    version           INTEGER       NOT NULL DEFAULT 1,
    replaces_id       UUID,
    correction_reason TEXT,
    total_sales       NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_refunds     NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_discounts   NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_tax         NUMERIC(18,4) NOT NULL DEFAULT 0,
    net_sales         NUMERIC(18,4) NOT NULL DEFAULT 0,   -- total_sales - total_refunds
    cash_sales        NUMERIC(18,4) NOT NULL DEFAULT 0,
    card_sales        NUMERIC(18,4) NOT NULL DEFAULT 0,
    gift_card_sales   NUMERIC(18,4) NOT NULL DEFAULT 0,
    other_sales       NUMERIC(18,4) NOT NULL DEFAULT 0,
    opening_float     NUMERIC(14,4) NOT NULL DEFAULT 0,
    cash_drops        NUMERIC(14,4) NOT NULL DEFAULT 0,
    cash_refunds      NUMERIC(14,4) NOT NULL DEFAULT 0,
    pay_ins           NUMERIC(14,4) NOT NULL DEFAULT 0,
    pay_outs          NUMERIC(14,4) NOT NULL DEFAULT 0,
    expected_cash     NUMERIC(14,4) NOT NULL DEFAULT 0,   -- computed at close
    counted_cash      NUMERIC(14,4),                      -- entered by manager
    over_short        NUMERIC(14,4),                      -- counted - expected
    transaction_count INTEGER       NOT NULL DEFAULT 0,
    currency          TEXT          NOT NULL,
    generated_by      UUID,
    generated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    time_zone         TEXT,
    zone_assumed      BOOLEAN       NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_z_reports_day_version_key UNIQUE (tenant_id, store_id, business_date, version)
);
CREATE INDEX IF NOT EXISTS idx_z_reports_store
    ON z_reports (tenant_id, store_id, business_date DESC);
