-- The till's card rule (intent/card-payments.md, slice 1). A store with a card machine StoreQL drives
-- takes cards through it; a store whose machines StoreQL does not see (or where the owner has allowed a
-- standalone machine beside a registered one) records what that machine took with the machine's own
-- receipt reference, so every card sale can be found in the acquirer's file. The permission is the
-- owner's alone, per store, and every change is kept with who made it and when. Every id is bound by
-- the service; the store ids are tenant-svc's, referenced and never joined.
CREATE TABLE store_card_settings (
    tenant_id          UUID        NOT NULL,
    store_id           UUID        NOT NULL,
    standalone_allowed BOOLEAN     NOT NULL,
    changed_by         UUID,
    changed_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_store_card_settings PRIMARY KEY (tenant_id, store_id)
);

-- Append-only.
CREATE TABLE store_card_setting_changes (
    id                 UUID        NOT NULL,
    tenant_id          UUID        NOT NULL,
    store_id           UUID        NOT NULL,
    standalone_allowed BOOLEAN     NOT NULL,
    changed_by         UUID,
    changed_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_store_card_setting_changes PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_store_card_setting_changes_store
    ON store_card_setting_changes (tenant_id, store_id, changed_at DESC);
