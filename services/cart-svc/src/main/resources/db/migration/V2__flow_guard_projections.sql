-- Flow-guard: cart-svc keeps local projections of tenant and store operational status
-- (fed by Kafka events) so CartService rejects cart writes for a tenant that is switched off, or a
-- store that is suspended or closed, without a synchronous cross-service call at request time.
-- Absence of a row = treat as ACTIVE (fail-open for back-compat and event-delivery lag).

CREATE TABLE tenant_status (
    tenant_id         UUID        PRIMARY KEY,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | INACTIVE (tenant-svc)
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store_status (
    store_id          UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED | CLOSED
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
