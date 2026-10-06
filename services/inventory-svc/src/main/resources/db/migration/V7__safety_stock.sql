-- Gap #8: Safety Stock Calculation (Oracle Inventory Ch. 14)
-- Stores per-(store, variant) safety stock params and the last computed result.
-- Two methods: MAD (Mean Absolute Deviation formula) and USER_DEFINED (% of avg demand).

CREATE TABLE safety_stock_params (
    id                  UUID           NOT NULL,
    tenant_id           UUID           NOT NULL,
    store_id            UUID           NOT NULL,
    variant_id          UUID           NOT NULL,
    method              TEXT           NOT NULL DEFAULT 'MAD',
    lead_time_days      INTEGER        NOT NULL DEFAULT 7,
    service_level_pct   NUMERIC(5,2)   NOT NULL DEFAULT 95.00,
    user_defined_pct    NUMERIC(5,2),
    safety_stock_qty    NUMERIC(18,3),
    computed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_stock_params PRIMARY KEY (id),
    CONSTRAINT uq_ssp_store_variant   UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT chk_ssp_method         CHECK (method IN ('MAD','USER_DEFINED')),
    CONSTRAINT chk_ssp_service_level  CHECK (service_level_pct > 0 AND service_level_pct < 100),
    CONSTRAINT chk_ssp_user_pct       CHECK (user_defined_pct IS NULL OR user_defined_pct > 0)
);
CREATE INDEX idx_safety_stock_params_tenant ON safety_stock_params (tenant_id, store_id);
