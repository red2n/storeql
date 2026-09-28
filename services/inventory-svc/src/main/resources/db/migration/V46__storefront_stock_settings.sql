-- "Only N left" on the storefront: a business-wide threshold, off until an owner
-- (or a manager held to no store) sets one. One row per tenant — there is no per-store setting, so
-- the tenant id is the whole key. Absent means off: GET /admin/inventory/storefront-settings and the
-- public GET /inventory/availability both read "no row" as lowStockThreshold null, never as zero.
CREATE TABLE storefront_stock_settings (
    tenant_id           UUID        NOT NULL,
    low_stock_threshold INT,
    updated_by          UUID        NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_storefront_stock_settings PRIMARY KEY (tenant_id),
    CONSTRAINT ck_storefront_stock_settings_threshold
        CHECK (low_stock_threshold IS NULL OR low_stock_threshold BETWEEN 1 AND 1000)
);
