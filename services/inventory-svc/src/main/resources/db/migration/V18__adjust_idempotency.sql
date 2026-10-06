-- POST /admin/inventory/adjust has no natural "the adjustment row" to key a unique index off of
-- (unlike receive/reserve, it only appends to the shared stock_movements ledger), so a standalone
-- idempotency ledger is used instead: a retried adjust with the same key is a no-op rather than
-- double-applying the delta.
CREATE TABLE inventory_adjustment_events (
    tenant_id       UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);
