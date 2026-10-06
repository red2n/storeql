-- Gap #5: Move Orders / Pick Wave (Oracle Inventory Ch. 13)
-- A move order moves stock from one store (or zone) to another within the same tenant.
-- Status lifecycle: DRAFT → OPEN → COMPLETED | CANCELLED

CREATE TABLE move_orders (
    id            UUID        NOT NULL,
    tenant_id     UUID        NOT NULL,
    from_store_id UUID        NOT NULL,
    to_store_id   UUID        NOT NULL,
    -- The free-text zone names.
    from_zone     TEXT,
    to_zone       TEXT,
    notes         TEXT,
    status        TEXT        NOT NULL DEFAULT 'DRAFT',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    picked_at     TIMESTAMPTZ,
    -- The zones the order moves between, by id: tenant-svc's zones, referenced never joined.
    from_zone_id  UUID,
    to_zone_id    UUID,
    CONSTRAINT pk_move_orders PRIMARY KEY (id),
    CONSTRAINT chk_move_order_status CHECK (status IN ('DRAFT','OPEN','COMPLETED','CANCELLED'))
);
CREATE INDEX idx_move_orders_tenant ON move_orders (tenant_id, from_store_id, status, created_at DESC);

CREATE TABLE move_order_lines (
    id            UUID           NOT NULL,
    tenant_id     UUID           NOT NULL,
    move_order_id UUID           NOT NULL,
    variant_id    UUID           NOT NULL,
    requested_qty NUMERIC(18,3)  NOT NULL,
    picked_qty    NUMERIC(18,3),
    CONSTRAINT pk_move_order_lines PRIMARY KEY (id),
    CONSTRAINT fk_mol_order FOREIGN KEY (move_order_id) REFERENCES move_orders(id)
);
CREATE INDEX idx_mol_order ON move_order_lines (move_order_id);
