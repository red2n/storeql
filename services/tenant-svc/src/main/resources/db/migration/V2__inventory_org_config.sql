-- Gap #53: Inventory org parameters — per-tenant profile knobs that control lot, serial,
-- grade, and costing behaviour across the org.

CREATE TABLE tenant_inventory_config (
    id                      UUID        PRIMARY KEY,
    tenant_id               UUID        NOT NULL UNIQUE,
    lot_control_enabled     BOOLEAN     NOT NULL DEFAULT true,
    serial_control_enabled  BOOLEAN     NOT NULL DEFAULT false,
    grade_control_enabled   BOOLEAN     NOT NULL DEFAULT false,
    expiry_tracking_enabled BOOLEAN     NOT NULL DEFAULT true,
    costing_method          TEXT        NOT NULL DEFAULT 'FIFO',   -- FIFO | AVERAGE | STANDARD
    default_uom             TEXT        NOT NULL DEFAULT 'EA',
    reorder_alert_enabled   BOOLEAN     NOT NULL DEFAULT true,
    auto_reserve_on_order   BOOLEAN     NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_inv_config_tenant ON tenant_inventory_config (tenant_id);
