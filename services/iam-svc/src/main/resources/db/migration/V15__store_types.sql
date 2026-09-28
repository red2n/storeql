-- Ship-from-store and dark-store picking (intent/ship-from-store-and-dark-store-picking.md).
-- iam-svc keeps each store's type beside its status, from tenant-svc's StoreStatusChanged (which
-- now carries the type), so a till session at a dark store — a shop with no shop floor — is refused
-- without a cross-service call at clock-in. Absence of a row = a shop (stores created before the
-- type was announced).
CREATE TABLE store_types (
    store_id   UUID        PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    type       TEXT        NOT NULL,  -- STORE | WAREHOUSE | DARK_STORE
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
