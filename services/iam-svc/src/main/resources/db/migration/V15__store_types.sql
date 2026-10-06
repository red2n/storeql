-- Ship-from-store and dark-store picking (intent/ship-from-store-and-dark-store-picking.md).
-- iam-svc keeps each store's type beside its status, from tenant-svc's StoreStatusChanged (which
-- carries the type), so a till session at a dark store — a shop with no shop floor — is refused
-- without a cross-service call at clock-in. Absence of a row = a shop.
-- The index leads with tenant_id, as CLAUDE.md asks of every tenant table; export and erasure
-- filter by it, and a read by business and store uses it. The type is read by store_id, the
-- primary key.
CREATE TABLE store_types (
    store_id   UUID        PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    type       TEXT        NOT NULL,  -- STORE | WAREHOUSE | DARK_STORE
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_types_tenant_store ON store_types (tenant_id, store_id);
