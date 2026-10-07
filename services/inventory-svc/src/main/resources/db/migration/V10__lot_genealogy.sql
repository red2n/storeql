-- Gap #11: Lot Genealogy (Oracle Inventory Ch. 7)
-- Tracks parent/child relationships between stock batches (SPLIT, MERGE, TRANSFORM).

CREATE TABLE lot_genealogy (
    id              UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    parent_batch_id UUID         NOT NULL,
    child_batch_id  UUID         NOT NULL,
    qty             NUMERIC(18,3) NOT NULL,
    relation_type   TEXT         NOT NULL DEFAULT 'SPLIT',
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_lot_genealogy  PRIMARY KEY (id),
    CONSTRAINT uq_lot_genealogy  UNIQUE (tenant_id, parent_batch_id, child_batch_id),
    CONSTRAINT chk_lot_relation  CHECK (relation_type IN ('SPLIT','MERGE','TRANSFORM')),
    CONSTRAINT chk_lot_different CHECK (parent_batch_id <> child_batch_id)
);

CREATE INDEX idx_lot_gen_tenant_parent ON lot_genealogy (tenant_id, parent_batch_id);
CREATE INDEX idx_lot_gen_tenant_child  ON lot_genealogy (tenant_id, child_batch_id);
