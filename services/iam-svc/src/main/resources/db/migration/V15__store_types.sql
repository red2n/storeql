-- Ship-from-store and dark-store picking (intent/ship-from-store-and-dark-store-picking.md).
-- iam-svc keeps each store's type beside its status, from tenant-svc's StoreStatusChanged (which
-- carries the type), so a till session at a dark store — a shop with no shop floor — is refused
-- without a cross-service call at clock-in. Absence of a row = a shop.
-- Open, for the owner (not changed by the fold): no index starts with tenant_id here either,
-- against CLAUDE.md's rule for tenant tables. The type is read by store_id, the primary key.
CREATE TABLE store_types (
    store_id   UUID        PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    type       TEXT        NOT NULL,  -- STORE | WAREHOUSE | DARK_STORE
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
