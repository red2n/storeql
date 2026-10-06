-- reporting-svc schema

-- Consumer dedupe marks, one per (event, consumer). The key is the pair, not event_id alone: one
-- consumer's mark must not stop another consumer from applying the same event, and each consumer
-- name is one purpose.
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, consumer)
);

-- The scheduled purge (common-service OutboxPublisher -> BaseOutboxRepository) deletes marks once
-- they are old enough, a batch at a time, found by age:
--
--   DELETE FROM processed_events WHERE (event_id, consumer) IN (
--     SELECT event_id, consumer FROM processed_events
--      WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
-- processed_events gains a row for every consumed event and the dedupe check reads it on every event,
-- so the purge's search by age needs its own index; the oldest marks are the ones it takes first.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- Projection: current on-hand per (tenant, store, variant). Updated from stock events.
CREATE TABLE inventory_projection (
    tenant_id   UUID        NOT NULL,
    store_id    UUID        NOT NULL,
    variant_id  UUID        NOT NULL,
    on_hand     NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, store_id, variant_id)
);

CREATE INDEX idx_inv_proj_tenant ON inventory_projection (tenant_id);

-- Append-only movement log for stats / demand history aggregation (Gap #49).
CREATE TABLE movement_events (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    store_id    UUID NOT NULL,
    variant_id  UUID NOT NULL,
    event_type  VARCHAR(40) NOT NULL,   -- StockReceived / StockDeducted / StockAdjusted
    qty_change  NUMERIC(19,4) NOT NULL, -- positive = in, negative = out
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mvt_tenant_store ON movement_events (tenant_id, store_id, occurred_at DESC);
CREATE INDEX idx_mvt_variant      ON movement_events (tenant_id, variant_id, occurred_at DESC);

-- Open supply in transit: INTRANSIT transfer orders shipped but not yet received (Gap #48).
-- Empty today: TransferOrderShipped carries the lines, but StockEventDispatcher does not read them yet.
CREATE TABLE open_supply_lines (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    from_store_id UUID NOT NULL,
    to_store_id   UUID NOT NULL,
    variant_id  UUID NOT NULL,
    qty         NUMERIC(19,4) NOT NULL,
    event_id    UUID NOT NULL
);

CREATE INDEX idx_supply_tenant_variant ON open_supply_lines (tenant_id, variant_id);
CREATE INDEX idx_supply_tenant_store   ON open_supply_lines (tenant_id, to_store_id);
