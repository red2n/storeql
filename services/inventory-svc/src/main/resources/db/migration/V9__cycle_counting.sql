-- Gap #10: Cycle Counting (Oracle Inventory Ch. 17)
-- A cycle count header defines the scope (store, ABC classes, tolerance).
-- Lines are generated from active (store, variant) pairs matching the scope.
-- Count entry records the actual counted qty; the engine auto-approves lines
-- within the tolerance band and flags the rest for manual approval.
-- Approved lines are converted to ADJUST stock movements.

CREATE TABLE cycle_count_headers (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    name            TEXT        NOT NULL,
    abc_classes     TEXT        NOT NULL DEFAULT 'A,B,C',
    tolerance_pct   NUMERIC(5,2) NOT NULL DEFAULT 5.00,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT pk_cycle_count_headers PRIMARY KEY (id),
    CONSTRAINT chk_cch_status CHECK (status IN ('OPEN','IN_PROGRESS','PENDING_APPROVAL','ADJUSTED','CLOSED'))
);
CREATE INDEX idx_cch_tenant ON cycle_count_headers (tenant_id, store_id, status, created_at DESC);

CREATE TABLE cycle_count_lines (
    id              UUID           NOT NULL,
    tenant_id       UUID           NOT NULL,
    header_id       UUID           NOT NULL,
    store_id        UUID           NOT NULL,
    variant_id      UUID           NOT NULL,
    system_qty      NUMERIC(18,3)  NOT NULL,
    counted_qty     NUMERIC(18,3),
    variance        NUMERIC(18,3),
    variance_pct    NUMERIC(10,4),
    status          TEXT           NOT NULL DEFAULT 'OPEN',
    counted_at      TIMESTAMPTZ,
    CONSTRAINT pk_cycle_count_lines PRIMARY KEY (id),
    CONSTRAINT uq_ccl_header_variant UNIQUE (header_id, variant_id),
    CONSTRAINT chk_ccl_status CHECK (status IN ('OPEN','COUNTED','APPROVED','REJECTED','ADJUSTED')),
    CONSTRAINT fk_ccl_header FOREIGN KEY (header_id) REFERENCES cycle_count_headers(id)
);
CREATE INDEX idx_ccl_header  ON cycle_count_lines (header_id, status);
CREATE INDEX idx_ccl_tenant  ON cycle_count_lines (tenant_id, store_id, variant_id);
