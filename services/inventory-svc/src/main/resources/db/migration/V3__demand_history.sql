-- Gap #7: Demand History Aggregation (Oracle Inventory Ch. 14)
-- Aggregates stock_movements (type='SALE') into periodic demand buckets
-- for use in forecasting and safety-stock calculations.

CREATE TABLE demand_history (
    id             UUID          NOT NULL,
    tenant_id      UUID          NOT NULL,
    store_id       UUID          NOT NULL,
    variant_id     UUID          NOT NULL,
    bucket_date    DATE          NOT NULL,
    bucket_type    TEXT          NOT NULL,
    demand_qty     NUMERIC(14,4) NOT NULL,
    movement_count INT           NOT NULL DEFAULT 0,
    computed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_demand_history    PRIMARY KEY (id),
    CONSTRAINT chk_demand_bucket_type CHECK (bucket_type IN ('DAY', 'WEEK', 'MONTH')),
    CONSTRAINT uq_demand_bucket     UNIQUE (tenant_id, store_id, variant_id, bucket_date, bucket_type)
);

CREATE INDEX idx_demand_tenant_store
    ON demand_history (tenant_id, store_id, variant_id, bucket_date DESC);
