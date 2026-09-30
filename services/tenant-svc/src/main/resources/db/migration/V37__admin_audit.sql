-- Who changed what in a business's own set-up (flow catalogue: onb-stores-zones-hours gap 1,
-- stf-staff-assignment-and-roles gap 2). A store trading or not, whether its till asks for a
-- customer's phone, who holds which role and what a custom role may do are exactly the changes a
-- reviewer asks "who, when, from what to what" of, and until now only the row's latest value and a
-- Kafka event (nothing a person can read) said anything.
--
-- Append-only: an entry is written on the same transaction as the change it describes and is never
-- updated or deleted. A change of mind is a new entry.
--
-- store_id is null for a change that concerns the business as a whole (a role defined, a business-wide
-- assignment); a caller held to stores reads their stores' entries and those.
CREATE TABLE tenant_admin_audit (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    -- STORE_CREATED, STORE_STATUS_CHANGED, STORE_TILL_PHONE_CHANGED, STAFF_ASSIGNED,
    -- STAFF_UNASSIGNED, ROLE_DEFINED, ROLE_CHANGED, ROLE_DELETED
    type         TEXT        NOT NULL,
    -- The login that made the change; null when nobody did (a system step).
    actor_id     UUID,
    store_id     UUID,
    -- The person a staff entry is about.
    subject_id   UUID,
    -- The role code a staff or role entry is about (a built-in tier or the business's own code).
    subject_code TEXT,
    from_value   TEXT,
    to_value     TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_tenant_admin_audit_type CHECK (type ~ '^[A-Z][A-Z0-9_]*$')
);

-- Newest first for a business, and for one store within it.
CREATE INDEX idx_tenant_admin_audit_time ON tenant_admin_audit (tenant_id, occurred_at DESC, id DESC);
CREATE INDEX idx_tenant_admin_audit_store ON tenant_admin_audit (tenant_id, store_id, occurred_at DESC, id DESC);
CREATE INDEX idx_tenant_admin_audit_actor ON tenant_admin_audit (tenant_id, actor_id, occurred_at DESC);
