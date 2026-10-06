-- Gap #18: Kanban Replenishment (all 4 types: Supplier, Inter-Org, Intra-Org, Production)
-- Each kanban card represents one replenishment signal for a (store, variant) pair.

CREATE TABLE kanban_cards (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    variant_id      UUID        NOT NULL,
    kanban_type     TEXT        NOT NULL,        -- SUPPLIER | INTER_ORG | INTRA_ORG | PRODUCTION
    status          TEXT        NOT NULL DEFAULT 'EMPTY',  -- EMPTY | TRIGGERED | IN_PROGRESS | REPLENISHED
    reorder_qty     NUMERIC(18,3) NOT NULL,
    source_store_id UUID,                        -- for INTER_ORG / INTRA_ORG transfers
    supplier_ref    TEXT,                        -- for SUPPLIER type
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    triggered_at    TIMESTAMPTZ,
    replenished_at  TIMESTAMPTZ,
    -- Order modifiers: the minimum and maximum order quantity and the lot multiplier. Null when the card sets none.
    min_order_qty   NUMERIC(18,3),
    max_order_qty   NUMERIC(18,3),
    lot_multiplier  NUMERIC(18,3),
    CONSTRAINT pk_kanban_cards PRIMARY KEY (id),
    CONSTRAINT chk_kanban_type   CHECK (kanban_type IN ('SUPPLIER','INTER_ORG','INTRA_ORG','PRODUCTION')),
    CONSTRAINT chk_kanban_status CHECK (status IN ('EMPTY','TRIGGERED','IN_PROGRESS','REPLENISHED'))
);

CREATE INDEX idx_kanban_tenant  ON kanban_cards (tenant_id, store_id);
CREATE INDEX idx_kanban_variant ON kanban_cards (tenant_id, store_id, variant_id);
CREATE INDEX idx_kanban_status  ON kanban_cards (tenant_id, status);
