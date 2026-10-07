-- Pincode → fulfilling-store mapping (docs/onboarding-and-locations.md §5).
-- Lowest priority wins when multiple stores cover the same pincode.

CREATE TABLE delivery_areas (
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    store_id   UUID        NOT NULL,
    pincode    TEXT        NOT NULL,
    priority   INT         NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_areas_tenant_pincode ON delivery_areas (tenant_id, pincode);
CREATE INDEX idx_delivery_areas_tenant_store ON delivery_areas (tenant_id, store_id);
CREATE UNIQUE INDEX uq_delivery_areas_tenant_store_pincode
    ON delivery_areas (tenant_id, store_id, pincode);
