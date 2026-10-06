-- Gap #50 — SIM ↔ POS sync.
-- pos_stock_positions: local stock-level projection updated from inventory-svc events.
-- processed_events:    idempotent deduplication for all Kafka consumers in this service.

CREATE TABLE pos_stock_positions (
    tenant_id   UUID          NOT NULL,
    store_id    UUID          NOT NULL,
    variant_id  UUID          NOT NULL,
    on_hand_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, store_id, variant_id)
);
CREATE INDEX idx_psp_tenant_store ON pos_stock_positions (tenant_id, store_id);

-- The dedupe key is (event_id, consumer), not event_id alone: one consumer's mark must not stop another
-- consumer from applying the same event (each consumer name is one purpose).
CREATE TABLE processed_events (
    event_id   UUID        NOT NULL,
    consumer   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);
-- The hourly purge asks for processed_at first and falls back to created_at: the dedupe table keeps its
-- timestamp in created_at. Ordered and batched by it, so the index keeps every batch off a full scan.
CREATE INDEX idx_processed_events_created_at ON processed_events (created_at);
