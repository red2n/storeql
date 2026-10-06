-- Landed cost (07.x): a charge landed on a receipt lifts the unit cost of the batches that receipt
-- created, by the per-unit share purchase-svc apportioned; a reversal lowers it by the same.

-- Append-only: every change to a batch's cost, with what it was and what it became, so a valuation
-- can be explained back to the charge that moved it.
CREATE TABLE batch_cost_adjustments (
    id           UUID          PRIMARY KEY,
    tenant_id    UUID          NOT NULL,
    store_id     UUID          NOT NULL,
    variant_id   UUID          NOT NULL,
    batch_id     UUID          NOT NULL REFERENCES inventory_batches(id),
    source_type  TEXT          NOT NULL,
    source_id    UUID          NOT NULL,
    event_id     UUID          NOT NULL,
    per_unit     NUMERIC(18,4) NOT NULL,
    cost_before  NUMERIC(18,4),
    cost_after   NUMERIC(18,4) NOT NULL,
    applied_at   TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_batch_cost_adjustments ON batch_cost_adjustments (tenant_id, batch_id, applied_at DESC);
CREATE INDEX idx_batch_cost_adjustments_source ON batch_cost_adjustments (tenant_id, source_id);
