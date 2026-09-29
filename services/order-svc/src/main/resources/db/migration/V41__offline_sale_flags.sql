-- Offline sales flagged for a manager. A till that loses its network completes the sale anyway and
-- replays it later, saying when the cashier rang it up. As at every till that sells offline, that
-- sale has already happened — the goods left, the money was taken — so a replay within the grace
-- (storeql.order.offline-replay.grace-hours) is recorded, never refused. What would have stopped it
-- when it was rung up is written here instead, one row per line and kind, on the order's own
-- transaction: a line an open recall covered, or one weighed on a scale not fit for trade at the
-- store. The audit trail (GET /admin/audit/events) reads it as two kinds of entry.
--
-- Append-only (golden rule #8): inserted with the order, never updated or deleted. Every id is bound
-- by the service.
CREATE TABLE offline_sale_flags (
    id                   UUID          NOT NULL,
    tenant_id            UUID          NOT NULL,
    order_id             UUID          NOT NULL REFERENCES orders (id),
    store_id             UUID          NOT NULL,
    kind                 TEXT          NOT NULL,
    -- The order line, counted from one as the receipt and the entry's words count it.
    line_no              INTEGER       NOT NULL,
    variant_id           UUID          NOT NULL,
    -- What the pack declared of itself, when it did: a recall's entry only.
    batch_no             TEXT,
    expiry               DATE,
    -- The recall that covered the line (inventory-svc's, referenced never joined).
    recall_id            UUID,
    recall_reference     TEXT,
    -- The scale the line was weighed on (tenant-svc's register, referenced never joined) and the
    -- register's word for it, or NOT_REGISTERED when the store's register does not hold it.
    instrument_id        UUID,
    instrument_standing  TEXT,
    -- When the cashier completed the sale, as the till said and the grace allowed.
    rung_up_at           TIMESTAMPTZ   NOT NULL,
    reason               TEXT          NOT NULL,
    cashier_id           UUID,
    recorded_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_offline_sale_flags PRIMARY KEY (id),
    CONSTRAINT ck_offline_sale_flags_kind
        CHECK (kind IN ('OFFLINE_SALE_OF_RECALLED_ITEM', 'OFFLINE_SALE_ON_UNFIT_SCALE')),
    CONSTRAINT ck_offline_sale_flags_shape CHECK (
        (kind = 'OFFLINE_SALE_OF_RECALLED_ITEM'
            AND recall_id IS NOT NULL AND instrument_id IS NULL AND instrument_standing IS NULL)
        OR (kind = 'OFFLINE_SALE_ON_UNFIT_SCALE'
            AND instrument_id IS NOT NULL AND recall_id IS NULL AND recall_reference IS NULL
            AND batch_no IS NULL AND expiry IS NULL)),
    CONSTRAINT ck_offline_sale_flags_line CHECK (line_no > 0),
    CONSTRAINT uq_offline_sale_flags_line UNIQUE (tenant_id, order_id, kind, line_no)
);
-- The trail reads newest first per tenant, by when the sale was rung up.
CREATE INDEX idx_offline_sale_flags_tenant_time ON offline_sale_flags (tenant_id, rung_up_at DESC);
