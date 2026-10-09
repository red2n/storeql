-- Opening stock (intent/catalogue-import.md). The one time a store's stock of a variant was opened
-- from an import: what a business already holds on the day it starts, loaded as a received batch
-- through the same door as any delivery (recall gate, putaway, expiry) and remembered here so that
-- a retried call, or the same file run again, never doubles it. A second opening for the same
-- (store, variant) is refused: a wrong opening is corrected by an adjustment or a stock take, not by
-- loading it again. Append-only. The job and variant ids are product-svc's, referenced and never
-- joined; every id is bound by the service.
CREATE TABLE opening_stock_loads (
    id         UUID          NOT NULL,
    tenant_id  UUID          NOT NULL,
    store_id   UUID          NOT NULL,
    variant_id UUID          NOT NULL,
    job_id     UUID          NOT NULL,
    batch_id   UUID          NOT NULL,
    qty        NUMERIC(18,3) NOT NULL,
    unit_cost  NUMERIC(18,4),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_opening_stock_loads PRIMARY KEY (id),
    CONSTRAINT fk_opening_stock_loads_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches (id),
    CONSTRAINT uq_opening_stock_loads_variant UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT uq_opening_stock_loads_batch UNIQUE (batch_id),
    CONSTRAINT chk_opening_stock_loads_qty CHECK (qty > 0),
    CONSTRAINT chk_opening_stock_loads_cost CHECK (unit_cost IS NULL OR unit_cost >= 0)
);
CREATE INDEX idx_opening_stock_loads_job ON opening_stock_loads (tenant_id, job_id, store_id);
