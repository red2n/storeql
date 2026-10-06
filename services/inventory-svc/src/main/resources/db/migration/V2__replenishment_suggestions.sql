-- Gap #1: Min-Max Planning Engine
-- Replenishment suggestions produced by the min-max engine. The reorder-to level (max_qty) lives on
-- reorder_thresholds.

-- suggested_qty = max_qty - available  (or  threshold*2 - available when max_qty is NULL).
CREATE TABLE replenishment_suggestions (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    store_id       UUID          NOT NULL,
    variant_id     UUID          NOT NULL,
    available_qty  NUMERIC(14,4) NOT NULL,
    min_qty        NUMERIC(14,4) NOT NULL,
    max_qty        NUMERIC(14,4),
    suggested_qty  NUMERIC(14,4) NOT NULL,
    status         TEXT          NOT NULL DEFAULT 'OPEN',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,
    CONSTRAINT chk_suggestion_status CHECK (status IN ('OPEN','ORDERED','CANCELLED'))
);

CREATE INDEX idx_suggestions_tenant_status
    ON replenishment_suggestions (tenant_id, status);

-- Unique partial index: prevents duplicate OPEN suggestions for the same (store, variant).
-- ON CONFLICT uses this index for idempotent inserts.
CREATE UNIQUE INDEX idx_suggestions_open_unique
    ON replenishment_suggestions (tenant_id, store_id, variant_id)
    WHERE status = 'OPEN';
