-- POS clock-in guard: iam-svc keeps a local projection of each store's status
-- (fed by storeql.tenant.store-status-changed events) so PosSessionService can reject
-- a clock-in to a closed or suspended store without a cross-service call at request time.
-- Absence of a row = treat as ACTIVE.
-- Open, for the owner (not changed by the fold): CLAUDE.md asks for a composite index starting
-- with tenant_id on every tenant table, and none exists here. The status is read by store_id,
-- the primary key.

CREATE TABLE store_status (
    store_id          UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED | CLOSED
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
