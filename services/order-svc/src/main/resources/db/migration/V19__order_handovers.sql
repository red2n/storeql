-- Ship-from-store and dark-store picking (intent/ship-from-store-and-dark-store-picking.md).
-- FULFILLED is the moment an online order is picked and packed — the goods off the shelf, the sale
-- recognised. The handover that follows is its own recorded step: a delivery DISPATCHED to a
-- carrier, or a pickup COLLECTED by its shopper. One per order, append-only; the order's status is
-- not changed by it, so every consumer keeps its one meaning of FULFILLED. Every id is bound by
-- the service.
CREATE TABLE order_handovers (
    id            UUID          NOT NULL,
    tenant_id     UUID          NOT NULL,
    order_id      UUID          NOT NULL REFERENCES orders (id),
    store_id      UUID          NOT NULL,
    kind          TEXT          NOT NULL,
    carrier       TEXT,
    reference     TEXT,
    parcels       INTEGER,
    collected_by  TEXT,
    handed_by     UUID,
    handed_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_handovers PRIMARY KEY (id),
    CONSTRAINT uq_order_handovers_order UNIQUE (tenant_id, order_id),
    CONSTRAINT ck_order_handovers_kind CHECK (kind IN ('DISPATCHED', 'COLLECTED')),
    CONSTRAINT ck_order_handovers_shape CHECK (
        (kind = 'DISPATCHED' AND carrier IS NOT NULL AND collected_by IS NULL)
        OR (kind = 'COLLECTED' AND carrier IS NULL AND reference IS NULL AND parcels IS NULL)),
    CONSTRAINT ck_order_handovers_parcels CHECK (parcels IS NULL OR parcels > 0)
);
CREATE INDEX idx_order_handovers_store ON order_handovers (tenant_id, store_id, handed_at DESC);
