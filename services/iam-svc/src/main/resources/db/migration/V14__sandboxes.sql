-- A business's sandbox (22.8), as iam-svc knows it. tenant-svc announces a sandbox as a
-- TenantCreated with mode SANDBOX and sandboxOf naming the live business; this service keeps the
-- pair — binding no owner, since the login already owns the live business — so the live owner can
-- trade its token for one in the sandbox, and so a key minted for the sandbox acts there and
-- nowhere else. A business may have had several sandboxes over time (each removed one is switched
-- off in tenant_status); the active one is the newest not switched off.
CREATE TABLE tenant_sandboxes (
    sandbox_tenant_id UUID        PRIMARY KEY,
    live_tenant_id    UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_sandboxes_live ON tenant_sandboxes (live_tenant_id, created_at);

-- A key is owned by the live business whatever tenant it acts as: a sandbox key (sqk_test_...) has
-- tenant_id = the sandbox and owner_tenant_id = the live business, so the owner lists and revokes
-- every key it has, live and sandbox, in one place.
ALTER TABLE api_keys ADD COLUMN owner_tenant_id UUID;
UPDATE api_keys SET owner_tenant_id = tenant_id WHERE owner_tenant_id IS NULL;
ALTER TABLE api_keys ALTER COLUMN owner_tenant_id SET NOT NULL;
ALTER TABLE api_keys ADD COLUMN sandbox BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_api_keys_owner ON api_keys (owner_tenant_id, id);

COMMENT ON COLUMN api_keys.owner_tenant_id IS
    'The live business that made the key and may revoke it; equal to tenant_id for a live key.';
COMMENT ON COLUMN api_keys.sandbox IS
    'True for a key that acts in the business''s sandbox (prefix sqk_test_).';
