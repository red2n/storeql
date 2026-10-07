-- Tenant suspension enforcement: iam-svc keeps a local projection of each tenant's status
-- (fed by storeql.tenant.tenant-status-changed events) so login/refresh can reject a deactivated
-- tenant's staff. Absence of a row = treat as ACTIVE.

CREATE TABLE tenant_status (
    tenant_id         UUID        PRIMARY KEY,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | INACTIVE
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
