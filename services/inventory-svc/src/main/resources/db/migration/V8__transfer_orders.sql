-- Gap #6: Transfer Orders — inter-store stock transfers (Oracle Inventory Ch. 11)
-- DIRECT: stock moves atomically on ship (no intransit period).
-- INTRANSIT: ship deducts source; receive adds destination; in-transit qty tracked via shipped_qty.

CREATE TABLE transfer_orders (
    id                UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    from_store_id     UUID        NOT NULL,
    to_store_id       UUID        NOT NULL,
    transfer_type     TEXT        NOT NULL DEFAULT 'DIRECT',
    status            TEXT        NOT NULL DEFAULT 'PENDING',
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    shipped_at        TIMESTAMPTZ,
    received_at       TIMESTAMPTZ,
    -- What raised the transfer. A proposed one (PROPOSAL) starts as a DRAFT that a person releases into
    -- the ordinary PENDING -> SHIPPED -> RECEIVED lifecycle. CROSSDOCK is raised by a warehouse's receipt.
    source            TEXT        NOT NULL DEFAULT 'MANUAL',
    proposal_run_id   UUID,
    -- A cross-dock transfer names the purchase order and the receipt it came from (purchase-svc's,
    -- referenced, never joined). Each of its lines names the batch it ships from.
    purchase_order_id UUID,
    goods_receipt_id  UUID,
    CONSTRAINT pk_transfer_orders PRIMARY KEY (id),
    CONSTRAINT chk_transfer_type   CHECK (transfer_type IN ('DIRECT','INTRANSIT')),
    CONSTRAINT chk_transfer_status CHECK (status        IN ('DRAFT','PENDING','SHIPPED','RECEIVED','CANCELLED')),
    CONSTRAINT chk_transfer_source CHECK (source        IN ('MANUAL','PROPOSAL','CROSSDOCK'))
);
CREATE INDEX idx_transfer_orders_tenant ON transfer_orders (tenant_id, from_store_id, status, created_at DESC);
CREATE INDEX idx_transfer_orders_to ON transfer_orders (tenant_id, to_store_id, status);

CREATE TABLE transfer_order_lines (
    id                  UUID           NOT NULL,
    tenant_id           UUID           NOT NULL,
    transfer_order_id   UUID           NOT NULL,
    variant_id          UUID           NOT NULL,
    requested_qty       NUMERIC(18,3)  NOT NULL,
    shipped_qty         NUMERIC(18,3),
    received_qty        NUMERIC(18,3),
    reason              TEXT,
    -- The batch a cross-dock line ships from.
    source_batch_id     UUID,
    CONSTRAINT pk_transfer_order_lines PRIMARY KEY (id),
    CONSTRAINT fk_tol_order FOREIGN KEY (transfer_order_id) REFERENCES transfer_orders(id)
);
CREATE INDEX idx_tol_order ON transfer_order_lines (transfer_order_id);
