-- Automatic order proposal to the supplier (demand forecasting & replenishment, 06.x).
--
-- The reorder point said when; the forecast said how much; nothing raised the order. A proposal
-- run reads the store's stock position from inventory-svc (reorder plans, what is on hand, the
-- forecast), adds what this service already has on order, and for every item at or below its
-- reorder point raises a DRAFT purchase order on the supplier the business last bought it from —
-- one order per supplier, every line carrying the arithmetic that produced it. A person submits
-- it, as they submit any draft; nothing is committed to a supplier by a machine.
ALTER TABLE purchase_orders ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL';
ALTER TABLE purchase_orders ADD CONSTRAINT ck_po_source CHECK (source IN ('MANUAL', 'PROPOSAL'));

-- Why the proposal put this line here, in the buyer's words; null on a line a person typed.
ALTER TABLE purchase_order_lines ADD COLUMN proposal_reason TEXT;

CREATE TABLE order_proposal_runs (
    id             UUID        NOT NULL,
    tenant_id      UUID        NOT NULL,
    store_id       UUID        NOT NULL,
    ran_by         UUID,
    ran_at         TIMESTAMPTZ NOT NULL,
    cover_days     INT         NOT NULL,
    considered     INT         NOT NULL,      -- items with a reorder plan at the store
    orders_raised  INT         NOT NULL,
    lines_raised   INT         NOT NULL,
    order_ids      UUID[]      NOT NULL,      -- the draft orders this run raised
    skipped        JSONB       NOT NULL,      -- [{"variantId", "reason"}]: what could not be judged, and why

    CONSTRAINT pk_order_proposal_runs PRIMARY KEY (id),
    CONSTRAINT ck_proposal_cover CHECK (cover_days BETWEEN 1 AND 365)
);

CREATE INDEX idx_order_proposal_runs_store ON order_proposal_runs (tenant_id, store_id, ran_at DESC);
