-- Depot / DC replenishment separate from store replenishment (intent/depot-dc-replenishment.md).
--
-- A shop is served by one warehouse of the same business (the store types are tenant-svc's,
-- checked through TenantProfiles, never joined); a product the shop buys direct from its supplier is
-- an exception. A proposal run for a warehouse writes DRAFT transfers to the shops it serves, which
-- a person releases into the ordinary PENDING -> SHIPPED -> RECEIVED lifecycle. Every id is bound by
-- the service (UUIDv7); nothing here is minted.

CREATE TABLE serving_relationships (
    id             UUID        NOT NULL,
    tenant_id      UUID        NOT NULL,
    store_id       UUID        NOT NULL,
    warehouse_id   UUID        NOT NULL,
    lead_time_days INTEGER     NOT NULL,
    created_by     UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_serving_relationships PRIMARY KEY (id),
    CONSTRAINT uq_serving_store UNIQUE (tenant_id, store_id),
    CONSTRAINT ck_serving_not_self CHECK (store_id <> warehouse_id),
    CONSTRAINT ck_serving_lead_time CHECK (lead_time_days BETWEEN 0 AND 90)
);
CREATE INDEX idx_serving_warehouse ON serving_relationships (tenant_id, warehouse_id);

CREATE TABLE serving_exceptions (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    store_id    UUID        NOT NULL,
    variant_id  UUID        NOT NULL,
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_serving_exceptions PRIMARY KEY (id),
    CONSTRAINT uq_serving_exception UNIQUE (tenant_id, store_id, variant_id)
);

CREATE TABLE transfer_proposal_runs (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    warehouse_id    UUID        NOT NULL,
    run_by          UUID,
    run_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    cover_days      INTEGER     NOT NULL,
    shops           INTEGER     NOT NULL,
    transfers       INTEGER     NOT NULL,
    lines           INTEGER     NOT NULL,
    short_lines     INTEGER     NOT NULL,
    idempotency_key TEXT,
    CONSTRAINT pk_transfer_proposal_runs PRIMARY KEY (id)
);
CREATE INDEX idx_transfer_proposal_runs_wh ON transfer_proposal_runs (tenant_id, warehouse_id, run_at DESC);
CREATE UNIQUE INDEX uq_transfer_proposal_runs_key
    ON transfer_proposal_runs (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- A transfer says what raised it; a proposed one starts as a DRAFT a person releases.
ALTER TABLE transfer_orders ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL';
ALTER TABLE transfer_orders ADD COLUMN proposal_run_id UUID;
ALTER TABLE transfer_orders ADD CONSTRAINT chk_transfer_source CHECK (source IN ('MANUAL','PROPOSAL'));
ALTER TABLE transfer_orders DROP CONSTRAINT chk_transfer_status;
ALTER TABLE transfer_orders ADD CONSTRAINT chk_transfer_status
    CHECK (status IN ('DRAFT','PENDING','SHIPPED','RECEIVED','CANCELLED'));
CREATE INDEX idx_transfer_orders_to ON transfer_orders (tenant_id, to_store_id, status);
ALTER TABLE transfer_order_lines ADD COLUMN reason TEXT;
