-- Wave picking and directed putaway.
--
-- Confirmed online orders wait at their store (a projection of order-svc's OrderConfirmed, cleared
-- as they are fulfilled or cancelled); a wave gathers them into one walk through the zones, one
-- pick line per batch naming the orders it serves; completing it deducts what was picked and tells
-- order-svc. Stock arriving with no zone is placed by the store's putaway rules, or waits on the
-- putaway list. Every id is minted by the service (UUIDv7).

-- The orders confirmed for a store and not yet fulfilled, with what each still needs.
CREATE TABLE awaiting_orders (
    order_id        UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    fulfilment_type TEXT        NOT NULL,
    confirmed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The open wave this order is in, if any; cleared when the wave completes or is cancelled.
    wave_id         UUID,
    CONSTRAINT pk_awaiting_orders PRIMARY KEY (order_id)
);
CREATE INDEX idx_awaiting_orders_store ON awaiting_orders (tenant_id, store_id, confirmed_at);

CREATE TABLE awaiting_order_lines (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    order_id        UUID          NOT NULL,
    variant_id      UUID          NOT NULL,
    qty_outstanding NUMERIC(18,3) NOT NULL,
    CONSTRAINT pk_awaiting_order_lines PRIMARY KEY (id),
    CONSTRAINT uq_awaiting_order_line UNIQUE (order_id, variant_id),
    CONSTRAINT fk_awaiting_line_order FOREIGN KEY (order_id) REFERENCES awaiting_orders (order_id) ON DELETE CASCADE,
    CONSTRAINT ck_awaiting_line_qty CHECK (qty_outstanding >= 0)
);
CREATE INDEX idx_awaiting_order_lines_tenant ON awaiting_order_lines (tenant_id, order_id);

CREATE TABLE pick_waves (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_by    UUID,
    completed_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    idempotency_key TEXT,
    CONSTRAINT pk_pick_waves PRIMARY KEY (id),
    CONSTRAINT ck_pick_wave_status CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX idx_pick_waves_store ON pick_waves (tenant_id, store_id, status, created_at DESC);
CREATE UNIQUE INDEX uq_pick_waves_idem ON pick_waves (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- One pick line per batch: where it sits, what to take, what was taken.
CREATE TABLE pick_wave_lines (
    id           UUID          NOT NULL,
    tenant_id    UUID          NOT NULL,
    wave_id      UUID          NOT NULL,
    walk_order   INT           NOT NULL,
    zone_id      UUID,
    batch_id     UUID          NOT NULL,
    batch_no     TEXT,
    variant_id   UUID          NOT NULL,
    directed_qty NUMERIC(18,3) NOT NULL,
    picked_qty   NUMERIC(18,3),
    CONSTRAINT pk_pick_wave_lines PRIMARY KEY (id),
    CONSTRAINT fk_pick_wave_line_wave FOREIGN KEY (wave_id) REFERENCES pick_waves (id) ON DELETE CASCADE,
    CONSTRAINT ck_pick_wave_line_qty CHECK (directed_qty > 0 AND (picked_qty IS NULL OR (picked_qty >= 0 AND picked_qty <= directed_qty)))
);
CREATE INDEX idx_pick_wave_lines_wave ON pick_wave_lines (tenant_id, wave_id, walk_order);

-- Which order each line serves, in confirmation order, and what of it was picked.
CREATE TABLE pick_wave_allocations (
    id         UUID          NOT NULL,
    tenant_id  UUID          NOT NULL,
    line_id    UUID          NOT NULL,
    seq        INT           NOT NULL,
    order_id   UUID          NOT NULL,
    qty        NUMERIC(18,3) NOT NULL,
    picked_qty NUMERIC(18,3),
    CONSTRAINT pk_pick_wave_allocations PRIMARY KEY (id),
    CONSTRAINT fk_pick_wave_allocation_line FOREIGN KEY (line_id) REFERENCES pick_wave_lines (id) ON DELETE CASCADE,
    CONSTRAINT ck_pick_wave_allocation_qty CHECK (qty > 0)
);
CREATE INDEX idx_pick_wave_allocations_line ON pick_wave_allocations (tenant_id, line_id, seq);

-- What a wave deducted per order and product, so the OrderFulfilled that follows deducts nothing
-- twice; acknowledged_by is the fulfilment's dedupe id once it has been matched.
CREATE TABLE wave_picked_lines (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    wave_id         UUID          NOT NULL,
    order_id        UUID          NOT NULL,
    variant_id      UUID          NOT NULL,
    qty             NUMERIC(18,3) NOT NULL,
    picked_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    acknowledged_by UUID,
    CONSTRAINT pk_wave_picked_lines PRIMARY KEY (id),
    CONSTRAINT ck_wave_picked_qty CHECK (qty > 0)
);
CREATE INDEX idx_wave_picked_lines_order ON wave_picked_lines (tenant_id, order_id, variant_id);

-- Where a product goes when it arrives with no zone: a rule per product per store, and a store
-- default (variant_id null).
CREATE TABLE putaway_rules (
    id         UUID        NOT NULL,
    tenant_id  UUID        NOT NULL,
    store_id   UUID        NOT NULL,
    variant_id UUID,
    zone_id    UUID        NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_putaway_rules PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uq_putaway_rule_variant ON putaway_rules (tenant_id, store_id, variant_id) WHERE variant_id IS NOT NULL;
CREATE UNIQUE INDEX uq_putaway_rule_default ON putaway_rules (tenant_id, store_id) WHERE variant_id IS NULL;

-- A batch that arrived with no zone and no rule to place it: waits for a person.
CREATE TABLE putaway_tasks (
    id                UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    store_id          UUID          NOT NULL,
    batch_id          UUID          NOT NULL,
    variant_id        UUID          NOT NULL,
    qty               NUMERIC(18,3) NOT NULL,
    suggested_zone_id UUID,
    status            TEXT          NOT NULL DEFAULT 'OPEN',
    placed_zone_id    UUID,
    placed_by         UUID,
    placed_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_putaway_tasks PRIMARY KEY (id),
    CONSTRAINT uq_putaway_task_batch UNIQUE (batch_id),
    CONSTRAINT ck_putaway_task_status CHECK (status IN ('OPEN', 'PLACED'))
);
CREATE INDEX idx_putaway_tasks_store ON putaway_tasks (tenant_id, store_id, status, created_at);
