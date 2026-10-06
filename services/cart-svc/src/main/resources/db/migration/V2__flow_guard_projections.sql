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

-- CLAUDE.md asks for a composite index starting with tenant_id on each tenant table, and this
-- table's primary key is store_id alone. The status check does not read this index: it looks a store
-- up by store_id, on purpose across businesses, so that a store of another business is told apart
-- from one not heard of yet (common-service StoreStatusRepository.isActive). What reads it is every
-- statement that takes one business's rows: its data export (the pages in store_id order, and the
-- manifest's counts) and the erasure of a departed business.
CREATE INDEX idx_store_status_tenant_store ON store_status (tenant_id, store_id);
