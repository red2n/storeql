-- A sandbox for a business (22.8): a second tenant of its own, marked as such and pointing at the
-- live business it stands in for, where an integrator can create products, book stock, place orders
-- and receive webhooks against nothing real — no message leaves it, no money moves, and nothing in
-- it is billed. One active sandbox per business at a time; removed, it is switched off with the
-- reason SANDBOX_DELETED and every service erases what it held of it, and another can be made. The
-- live business's owner owns the sandbox too, which is how the owner's token is traded for one that
-- names the sandbox (iam-svc, 22.8) — and why a login's "own business" is always the live one.
ALTER TABLE tenants ADD COLUMN mode TEXT NOT NULL DEFAULT 'LIVE';
ALTER TABLE tenants ADD COLUMN sandbox_of UUID REFERENCES tenants (id);

ALTER TABLE tenants ADD CONSTRAINT ck_tenants_mode CHECK (mode IN ('LIVE', 'SANDBOX'));
-- A sandbox always says what it is a sandbox of; a live business never does.
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_sandbox_of
    CHECK ((mode = 'SANDBOX') = (sandbox_of IS NOT NULL));
-- One sandbox at a time: a second is refused at the row, whatever two requests race to.
CREATE UNIQUE INDEX uq_tenants_active_sandbox
    ON tenants (sandbox_of) WHERE mode = 'SANDBOX' AND status = 'ACTIVE';
CREATE INDEX idx_tenants_sandbox_of ON tenants (sandbox_of) WHERE sandbox_of IS NOT NULL;

-- Why a business is switched off gains a third reason: its owner removed the sandbox it was.
ALTER TABLE tenants DROP CONSTRAINT ck_tenant_deactivated_reason;
ALTER TABLE tenants ADD CONSTRAINT ck_tenant_deactivated_reason
    CHECK (deactivated_reason IS NULL
           OR deactivated_reason IN ('NON_PAYMENT', 'ADMINISTRATOR', 'SANDBOX_DELETED'));

COMMENT ON COLUMN tenants.mode IS
    'LIVE, or SANDBOX for a business''s test double (22.8): every service that reads the profile treats a sandbox as unreal — no message leaves it, no money moves.';
COMMENT ON COLUMN tenants.sandbox_of IS
    'For a SANDBOX, the live business it stands in for; NULL for a live business.';

-- The plan sandboxes sit on, which the platform keeps for sandboxes alone: sold (ACTIVE, so it can
-- be held), on the public price list to nobody, never the default, with no price. Its allowances
-- are small on purpose, and the platform may change them like any plan's. A live business is never
-- put on it (PLAN_SANDBOX_ONLY). Fixed ids, so every deployment has the same plan.
INSERT INTO plans (id, code, name, description, status, billing_interval, trial_days, is_default,
                   is_public, sort_order, created_by, created_at, updated_at)
VALUES ('019965a0-0000-7000-8000-000000000001', 'SANDBOX', 'Sandbox',
        'The plan a business''s sandbox sits on: enough to try every integration, and never billed.',
        'ACTIVE', 'MONTH', 0, false, false, 1000,
        '019965a0-0000-7000-8000-000000000002', now(), now());

INSERT INTO plan_entitlements (plan_id, key, limit_value, enabled) VALUES
    ('019965a0-0000-7000-8000-000000000001', 'stores.max',          2,    NULL),
    ('019965a0-0000-7000-8000-000000000001', 'staff.max',           5,    NULL),
    ('019965a0-0000-7000-8000-000000000001', 'products.max',        200,  NULL),
    ('019965a0-0000-7000-8000-000000000001', 'requests.per-minute', 300,  NULL),
    ('019965a0-0000-7000-8000-000000000001', 'images.mb.max',       50,   NULL),
    ('019965a0-0000-7000-8000-000000000001', 'documents.mb.max',    20,   NULL),
    ('019965a0-0000-7000-8000-000000000001', 'feature.storefront',  NULL, true);
