-- A business's sandbox, as iam-svc knows it. tenant-svc announces a sandbox as a
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
