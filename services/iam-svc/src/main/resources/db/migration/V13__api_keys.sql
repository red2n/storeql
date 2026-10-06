-- A business's own API keys: what its systems — an ERP, an accounting package, an integrator —
-- present at the gateway instead of a person's sign-in.
--
-- The key itself is never stored: its SHA-256 is, looked up on every request, and the first
-- twelve characters are kept so the owner can tell keys apart. A key acts in one staff tier —
-- MANAGER, STOREKEEPER or CASHIER, never OWNER, so a leaked key cannot close the business, export
-- it or change what it pays — for some stores or all, until it is revoked, expires, or the
-- business is switched off. A revoked key stays on the list, marked, so the owner can see what
-- was issued and when it stopped.
--
-- A key is owned by the live business whatever tenant it acts as: a sandbox key (sqk_test_...) has
-- tenant_id = the sandbox and owner_tenant_id = the live business, so the owner lists and revokes
-- every key it has, live and sandbox, in one place. The sandbox pairs are kept in tenant_sandboxes
-- (V14).
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,                -- the business the key acts as: a sandbox for a sandbox key
    owner_tenant_id UUID NOT NULL,                -- the live business that made the key and may revoke it
    sandbox         BOOLEAN NOT NULL DEFAULT false, -- true for a key that acts in the sandbox (prefix sqk_test_)
    name            TEXT NOT NULL,                -- what the owner calls it: "Warehouse ERP"
    prefix          TEXT NOT NULL,                -- the first twelve characters, shown on the list
    key_hash        TEXT NOT NULL,                -- SHA-256 of the whole key, hex
    role            TEXT NOT NULL,                -- MANAGER | STOREKEEPER | CASHIER
    store_ids       UUID[],                       -- the stores it may work in; NULL for every store
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ,                  -- NULL: until revoked
    last_used_at    TIMESTAMPTZ,                  -- kept to the minute
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID
);
CREATE UNIQUE INDEX uq_api_keys_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id, id);
CREATE INDEX idx_api_keys_owner ON api_keys (owner_tenant_id, id);

COMMENT ON COLUMN api_keys.owner_tenant_id IS
    'The live business that made the key and may revoke it; equal to tenant_id for a live key.';
COMMENT ON COLUMN api_keys.sandbox IS
    'True for a key that acts in the business''s sandbox (prefix sqk_test_).';
