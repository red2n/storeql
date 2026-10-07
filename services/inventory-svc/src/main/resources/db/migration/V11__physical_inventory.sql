CREATE TABLE physical_inventories (
    id           UUID        NOT NULL,
    tenant_id    UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'OPEN',
    notes        TEXT,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_physical_inventories PRIMARY KEY (id),
    CONSTRAINT chk_pi_status CHECK (status IN ('OPEN','COUNTING','COMPLETED'))
);
CREATE INDEX idx_pi_tenant_store ON physical_inventories (tenant_id, store_id, started_at DESC);

CREATE TABLE physical_inventory_tags (
    id                    UUID           NOT NULL,
    tenant_id             UUID           NOT NULL,
    physical_inventory_id UUID           NOT NULL REFERENCES physical_inventories(id),
    variant_id            UUID           NOT NULL,
    zone_id               UUID,
    system_qty            NUMERIC(18,3)  NOT NULL DEFAULT 0,
    counted_qty           NUMERIC(18,3),
    adjustment_qty        NUMERIC(18,3)  GENERATED ALWAYS AS (counted_qty - system_qty) STORED,
    status                TEXT           NOT NULL DEFAULT 'OPEN',
    counted_at            TIMESTAMPTZ,
    CONSTRAINT pk_pi_tags PRIMARY KEY (id),
    CONSTRAINT uq_pi_tag  UNIQUE (tenant_id, physical_inventory_id, variant_id, zone_id),
    CONSTRAINT chk_pi_tag_status CHECK (status IN ('OPEN','COUNTED','ADJUSTED'))
);
CREATE INDEX idx_pi_tag_pi ON physical_inventory_tags (tenant_id, physical_inventory_id);
