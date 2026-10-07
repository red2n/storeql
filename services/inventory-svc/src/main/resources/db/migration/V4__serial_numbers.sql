-- Gap #3: Serial Number Control (Oracle Inventory Ch. 8)
-- Tracks individual physical units for high-value / warranty items.

CREATE TABLE serial_numbers (
    id           UUID        NOT NULL,
    tenant_id    UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    variant_id   UUID        NOT NULL,
    batch_id     UUID,
    serial_no    TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'IN_STOCK',
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sold_at      TIMESTAMPTZ,
    CONSTRAINT pk_serial_numbers     PRIMARY KEY (id),
    CONSTRAINT chk_serial_status     CHECK (status IN ('IN_STOCK','RESERVED','SOLD','RETURNED','LOST','DAMAGED')),
    CONSTRAINT uq_serial_per_tenant  UNIQUE (tenant_id, serial_no)
);

CREATE INDEX idx_serial_tenant_store
    ON serial_numbers (tenant_id, store_id, variant_id, status);

CREATE INDEX idx_serial_batch
    ON serial_numbers (tenant_id, batch_id);

-- Genealogy / audit trail: every status transition is recorded (append-only).
CREATE TABLE serial_movements (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    serial_id   UUID        NOT NULL,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    ref_type    TEXT,
    ref_id      UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_serial_movements PRIMARY KEY (id)
);

CREATE INDEX idx_serial_movements_serial
    ON serial_movements (tenant_id, serial_id, created_at DESC);
