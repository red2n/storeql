-- Gap #9: ABC Analysis (Oracle Inventory Ch. 16)
-- Ranks inventory SKUs by annual usage value (demand_qty * cost_price) and assigns
-- each (store, variant) to class A, B, or C using configurable cumulative-value thresholds.
-- Default thresholds: A = top 70% of cumulative value, B = next 20%, C = bottom 10%.

CREATE TABLE abc_compile_runs (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    store_id        UUID,
    criteria        TEXT        NOT NULL DEFAULT 'VALUE',
    threshold_a     NUMERIC(5,2) NOT NULL DEFAULT 70.00,
    threshold_ab    NUMERIC(5,2) NOT NULL DEFAULT 90.00,
    items_compiled  INTEGER     NOT NULL DEFAULT 0,
    compiled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_abc_compile_runs PRIMARY KEY (id),
    CONSTRAINT chk_abc_criteria     CHECK (criteria IN ('VALUE','VELOCITY')),
    CONSTRAINT chk_abc_threshold_a  CHECK (threshold_a > 0 AND threshold_a < 100),
    CONSTRAINT chk_abc_threshold_ab CHECK (threshold_ab > threshold_a AND threshold_ab < 100)
);
CREATE INDEX idx_abc_compile_runs_tenant ON abc_compile_runs (tenant_id, compiled_at DESC);

CREATE TABLE abc_assignments (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    store_id    UUID        NOT NULL,
    variant_id  UUID        NOT NULL,
    run_id      UUID        NOT NULL,
    class       TEXT        NOT NULL,
    score       NUMERIC(18,6) NOT NULL,
    rank        INTEGER     NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_abc_assignments PRIMARY KEY (id),
    CONSTRAINT uq_abc_store_variant UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT chk_abc_class CHECK (class IN ('A','B','C')),
    CONSTRAINT fk_abc_run   FOREIGN KEY (run_id) REFERENCES abc_compile_runs(id)
);
CREATE INDEX idx_abc_assignments_tenant  ON abc_assignments (tenant_id, store_id, class);
CREATE INDEX idx_abc_assignments_run     ON abc_assignments (run_id);
