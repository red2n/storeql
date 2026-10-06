-- Flow-guard: order-svc keeps local projections of tenant and store operational status
-- (fed by Kafka events) so OrderService.placeOrder() can reject orders for suspended
-- or closed tenants/stores without a synchronous cross-service call at request time.
-- Absence of a row = treat as ACTIVE (fail-open for back-compat and event-delivery lag).

CREATE TABLE tenant_status (
    tenant_id         UUID        PRIMARY KEY,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | INACTIVE, as TenantStatusChanged carries them
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The business's currency, projected from TenantCreated (the event iam-svc also consumes). Nullable: a
    -- tenant whose TenantCreated has not been projected has no row yet, or no currency. Checkout reads it
    -- here first; with no value here it asks tenant-svc (TenantProfiles.requireCurrency), and when tenant-svc
    -- cannot answer it refuses (503) rather than guessing a default. The status fails open to ACTIVE when
    -- there is no row, as the header says.
    currency          CHAR(3)
);

CREATE TABLE store_status (
    store_id          UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED | CLOSED
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_status_tenant ON store_status (tenant_id, store_id);
