-- reporting-svc schema

-- Consumer dedupe marks, one per (event, consumer). The key is the pair, not event_id alone: one
-- consumer's mark must not stop another consumer from applying the same event, and each consumer
-- name is one purpose.
--
-- One kind of row is not an event's mark: under the consumer 'reporting-svc/transfer-landed', the
-- note that a transfer landed. A TransferOrderReceived writes it (ReportingRepository.retireSupplyLines)
-- under an id derived from the business and the transfer order, since the shipment and the receipt
-- have event ids of their own and share only the transfer order; a TransferOrderShipped read
-- afterwards finds it (applyTransferShippedOnce) and opens no line. The note and the per-transfer
-- lock that orders the two are what make shipment and receipt independent of the order they are
-- read in: keep them.
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

-- Open supply in transit: the lines of INTRANSIT transfer orders shipped but not yet received (Gap #48).
-- Opened by TransferOrderShipped, one line per variant, bound for the receiving store; closed by the
-- TransferOrderReceived of the same transfer. The two events have event ids of their own, so the key
-- that ties a receipt to its lines is the transfer order they both name as their aggregate, never the
-- shipment's event id. A DIRECT transfer lands as it ships, so it never opens a line.
CREATE TABLE open_supply_lines (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    -- inventory-svc's transfer order (referenced, never joined): what a receipt retires its lines by.
    transfer_order_id UUID NOT NULL,
    from_store_id UUID NOT NULL,
    to_store_id   UUID NOT NULL,
    variant_id  UUID NOT NULL,
    qty         NUMERIC(19,4) NOT NULL,
    -- The TransferOrderShipped that opened the line: where it came from, not how it is retired.
    event_id    UUID NOT NULL
);

CREATE INDEX idx_supply_tenant_variant ON open_supply_lines (tenant_id, variant_id);
CREATE INDEX idx_supply_tenant_store   ON open_supply_lines (tenant_id, to_store_id);
-- The receipt's delete: every line of one business's transfer, found without reading the table.
CREATE INDEX idx_supply_tenant_transfer ON open_supply_lines (tenant_id, transfer_order_id);
