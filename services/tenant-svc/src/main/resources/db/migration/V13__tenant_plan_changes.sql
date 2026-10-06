-- How a business came to be on the plan it is on (21.8). Append-only.
--
-- The plans themselves, the platform's price list, are created in V1__init.sql, ahead of tenants,
-- which refers to them: tenants.plan_id is what a business is on now, and this table is how it got
-- there. The platform's tables carry no tenant_id, and a plan change names the business it moved.

CREATE TABLE tenant_plan_changes (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenants (id),
    from_plan_id UUID        REFERENCES plans (id),
    to_plan_id   UUID        REFERENCES plans (id),
    changed_by   UUID,                        -- null when the platform did it: a new business put on the default plan
    reason       TEXT,
    changed_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tenant_plan_changes_tenant ON tenant_plan_changes (tenant_id, changed_at DESC);
