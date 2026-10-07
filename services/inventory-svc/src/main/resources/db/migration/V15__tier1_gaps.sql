-- V15: Tier-1 gap completions (items 21-31)
-- 21 Transaction reason codes (controlled vocabulary)
CREATE TABLE transaction_reason_codes (
    id          UUID PRIMARY KEY,
    tenant_id   UUID,                                 -- NULL = platform-wide default
    code        TEXT        NOT NULL,
    description TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (tenant_id, code)
);
CREATE INDEX idx_reason_codes_tenant ON transaction_reason_codes (tenant_id);

-- Seed platform-wide reason codes (tenant_id NULL)
INSERT INTO transaction_reason_codes (id, tenant_id, code, description) VALUES
  ('01a090a0-1bc3-705c-bf2e-9aa333b52da0', NULL, 'DAMAGED',       'Stock damaged'),
  ('01a090a0-1bc3-705d-892a-c1b6002c1333', NULL, 'FOUND',         'Stock found during count'),
  ('01a090a0-1bc3-705e-986f-762b0ab5c63f', NULL, 'THEFT',         'Shrinkage / theft'),
  ('01a090a0-1bc3-705f-b480-2fc21bacf4d2', NULL, 'EXPIRY',        'Expired and written off'),
  ('01a090a0-1bc3-7060-9ef2-1409005e770a', NULL, 'VENDOR_RETURN', 'Returned to vendor'),
  ('01a090a0-1bc3-7061-bf56-41be377d3c05', NULL, 'CORRECTION',    'Data-entry correction'),
  ('01a090a0-1bc3-7062-8cbe-0d4dd6bc58cf', NULL, 'SAMPLING',      'Quality sampling');

-- 22 Configurable transaction source types
CREATE TABLE transaction_source_types (
    id          UUID PRIMARY KEY,
    tenant_id   UUID,                                 -- NULL = platform-wide default
    code        TEXT        NOT NULL,
    description TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (tenant_id, code)
);
CREATE INDEX idx_source_types_tenant ON transaction_source_types (tenant_id);

INSERT INTO transaction_source_types (id, tenant_id, code, description) VALUES
  ('01a090a0-1bc3-7063-a998-b4ce6747303b', NULL, 'RECEIVE',    'Goods receipt'),
  ('01a090a0-1bc3-7064-8669-d07018039e68', NULL, 'SALE',       'POS or online sale'),
  ('01a090a0-1bc3-7065-93e5-ee8c5eaf9038', NULL, 'ADJUST',     'Manual adjustment'),
  ('01a090a0-1bc3-7066-902b-bb9f62eb34ac', NULL, 'TRANSFER',   'Inter/intra store transfer'),
  ('01a090a0-1bc3-7067-ba09-f92bd7ee80b2', NULL, 'RETURN',     'Customer return'),
  ('01a090a0-1bc3-7068-9095-c2152f3224aa', NULL, 'RESERVE',    'Reservation hold'),
  ('01a090a0-1bc3-7069-8543-d504d7d4161a', NULL, 'RELEASE',    'Reservation release'),
  ('01a090a0-1bc3-706a-8978-785541df58b8', NULL, 'CYCLE_COUNT','Cycle count adjustment'),
  ('01a090a0-1bc3-706b-aeb4-ef140b731d8c', NULL, 'LOT_SPLIT',  'Lot split action'),
  ('01a090a0-1bc3-706c-a90c-f7fff87bb4c9', NULL, 'LOT_MERGE',  'Lot merge action'),
  -- Return to vendor (readiness review 07.8): the movement that sends goods back to a supplier.
  -- purchase-svc raises the return and the debit note; the stock leaves here, when ReturnedToVendor
  -- is consumed — a signed movement of its own type against the return, so a supplier's goods going
  -- back are never confused with a customer's return coming in (RETURN) or a write-off (ADJUST).
  ('01a09650-2b3c-7001-8f6a-2a6d1d9e5c11', NULL, 'RTV',        'Return to vendor');

-- 23 Lot action codes — lot_actions table. A split makes the result batch; a merge names the existing
-- batch the quantity went into.
CREATE TABLE lot_actions (
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    action_type     TEXT        NOT NULL,  -- SPLIT | MERGE
    source_batch_id UUID        NOT NULL,
    result_batch_id UUID        NOT NULL,
    qty             NUMERIC(18,3) NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The Idempotency-Key the split or merge was sent under; NULL when it was sent with none. A retry
    -- under the same key finds this row and answers it instead of moving the stock again.
    idempotency_key TEXT
);
CREATE INDEX idx_lot_actions_tenant   ON lot_actions (tenant_id, created_at DESC);
CREATE INDEX idx_lot_actions_source   ON lot_actions (tenant_id, source_batch_id);
CREATE INDEX idx_lot_actions_result   ON lot_actions (tenant_id, result_batch_id);
CREATE UNIQUE INDEX uq_lot_actions_idempotency ON lot_actions (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 26 Lot-specific UOM conversions
CREATE TABLE lot_uom_conversions (
    id          UUID PRIMARY KEY,
    tenant_id   UUID           NOT NULL,
    batch_id    UUID           NOT NULL,
    from_uom    TEXT           NOT NULL,
    to_uom      TEXT           NOT NULL,
    factor      NUMERIC(18,6)  NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, batch_id, from_uom, to_uom)
);
CREATE INDEX idx_lot_uom_tenant ON lot_uom_conversions (tenant_id, batch_id);

-- 27 PAR levels / replenishment counting
CREATE TABLE par_level_configs (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    variant_id      UUID          NOT NULL,
    par_qty         NUMERIC(18,3) NOT NULL,
    uom             TEXT,
    review_cycle    TEXT          NOT NULL DEFAULT 'DAILY',  -- DAILY | WEEKLY | MONTHLY
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, store_id, variant_id)
);
CREATE INDEX idx_par_level_tenant ON par_level_configs (tenant_id, store_id);

-- 31 GL account mapping — zone/subinventory → nominal code
CREATE TABLE zone_gl_mappings (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    store_id     UUID NOT NULL,
    zone_id      UUID,                    -- NULL = store-level default
    nominal_code TEXT NOT NULL,
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, store_id, zone_id)
);
CREATE INDEX idx_zone_gl_tenant ON zone_gl_mappings (tenant_id, store_id);
