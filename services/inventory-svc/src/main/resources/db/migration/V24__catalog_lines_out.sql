-- Lines the catalogue has taken out of replenishment (item lifecycle).
--
-- product-svc announces a product discontinued, delisted, launched or reinstated with the variants
-- it covers. Inventory keeps only the variants that are out — DISCONTINUED (sold while stock
-- lasts, never reordered) or DELISTED — so the low-stock report and the planning run leave them
-- alone. A line that comes back deletes its rows. Database-per-service: no read of the catalogue.
CREATE TABLE catalog_lines_out (
    tenant_id   UUID NOT NULL,
    variant_id  UUID NOT NULL,
    product_id  UUID NOT NULL,
    status      TEXT NOT NULL,           -- DISCONTINUED or DELISTED
    changed_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, variant_id),
    CONSTRAINT chk_catalog_lines_out_status CHECK (status IN ('DISCONTINUED', 'DELISTED'))
);
CREATE INDEX ix_catalog_lines_out_tenant_product ON catalog_lines_out (tenant_id, product_id);
