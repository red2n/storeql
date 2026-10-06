-- Cash management: till sessions and cash drops.
-- Golden rule #8: cash_drops is append-only; till_sessions are closed (not deleted) on Z-report.

CREATE TABLE IF NOT EXISTS till_sessions (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    opened_by       UUID          NOT NULL,   -- user_id from JWT
    float_amount    NUMERIC(14,4) NOT NULL,   -- opening cash count
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',   -- OPEN | CLOSED
    counted_cash    NUMERIC(14,4),            -- filled on Z-report close
    over_short      NUMERIC(14,4),            -- counted_cash - expected_cash, filled on close
    opened_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    -- A closed session keeps who closed it and the note the closer gave for a difference. Both are
    -- written once, by the close that also writes counted_cash and over_short.
    closed_by       UUID,
    note            TEXT,
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX IF NOT EXISTS idx_till_sessions_store
    ON till_sessions (tenant_id, store_id, status);

-- Append-only: each row is an immutable cash-drop record.
CREATE TABLE IF NOT EXISTS cash_drops (
    id               UUID          NOT NULL,
    tenant_id        UUID          NOT NULL,
    till_session_id  UUID          NOT NULL,
    amount           NUMERIC(14,4) NOT NULL,
    recorded_by      UUID          NOT NULL,
    notes            TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX IF NOT EXISTS idx_cash_drops_session
    ON cash_drops (tenant_id, till_session_id);
