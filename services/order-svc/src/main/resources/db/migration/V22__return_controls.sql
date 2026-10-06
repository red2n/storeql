-- ── Return controls (intent/return-controls.md) ─────────────────────────────
--
-- A return names each line's condition, is checked against the business's own return policy before
-- anything is written, names the manager who allowed it when it fell outside the policy, and can be
-- retried without paying twice. The columns it needs are on returns and return_items (V1__init.sql).

-- The policy: one row per business. No row means the default (30 days, no cashier ceiling,
-- no-receipt returns off), applied in code, so a business that never sets one is not asked to.
-- The ceilings are in the business's home currency; no amount is assumed here.
CREATE TABLE return_policies (
    tenant_id          UUID PRIMARY KEY,
    window_days        INT NOT NULL,
    cashier_ceiling    NUMERIC(18,4),
    no_receipt_allowed BOOLEAN NOT NULL DEFAULT false,
    no_receipt_ceiling NUMERIC(18,4),
    updated_by         UUID,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_return_policies_window CHECK (window_days BETWEEN 1 AND 3650),
    CONSTRAINT chk_return_policies_cashier CHECK (cashier_ceiling IS NULL OR cashier_ceiling >= 0),
    CONSTRAINT chk_return_policies_no_receipt
        CHECK (no_receipt_ceiling IS NULL OR no_receipt_ceiling >= 0)
);
