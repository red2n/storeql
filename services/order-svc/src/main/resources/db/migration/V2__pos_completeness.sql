-- Gaps #42, #43, #44, #46 — POS completeness: special orders, POSLog, receipts, tax-exempt flag.

-- ── Gap #42: Special orders — customer order at store for future delivery ────
CREATE TABLE special_orders (
    id                      UUID        PRIMARY KEY,
    tenant_id               UUID        NOT NULL,
    store_id                UUID        NOT NULL,
    customer_id             UUID,
    customer_name           TEXT,
    customer_phone          TEXT,
    customer_email          TEXT,
    delivery_address        TEXT,
    requested_delivery_date DATE,
    notes                   TEXT,
    status                  TEXT        NOT NULL DEFAULT 'PENDING',
    subtotal                NUMERIC     NOT NULL,
    total                   NUMERIC     NOT NULL,
    currency                TEXT        NOT NULL,
    idempotency_key         TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_special_orders_idem UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_special_orders_tenant       ON special_orders (tenant_id, store_id, created_at DESC);
CREATE INDEX idx_special_orders_tenant_cust  ON special_orders (tenant_id, customer_id)
    WHERE customer_id IS NOT NULL;

CREATE TABLE special_order_items (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    so_id        UUID        NOT NULL REFERENCES special_orders(id),
    variant_id   UUID        NOT NULL,
    qty          NUMERIC(18,3) NOT NULL,
    unit_price   NUMERIC     NOT NULL,
    line_total   NUMERIC     NOT NULL,
    notes        TEXT
);
CREATE INDEX idx_soi_tenant_so ON special_order_items (tenant_id, so_id);

-- Append-only status audit for special orders.
CREATE TABLE special_order_status_history (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    so_id       UUID        NOT NULL,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    reason      TEXT,
    changed_by  UUID,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sosh_tenant_so ON special_order_status_history (tenant_id, so_id, changed_at);

-- ── Gap #43: POSLog — append-only transaction journal per fulfilled POS order ─
CREATE TABLE pos_log_entries (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    order_id        UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    cashier_id      UUID,
    subtotal        NUMERIC       NOT NULL,
    tax_amount      NUMERIC       NOT NULL DEFAULT 0,
    discount_amount NUMERIC       NOT NULL DEFAULT 0,
    total           NUMERIC       NOT NULL,
    currency        TEXT          NOT NULL,
    tax_exempt      BOOLEAN       NOT NULL DEFAULT false,
    exempt_reason   TEXT,
    transaction_ts  TIMESTAMPTZ   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_pos_log_tenant_order ON pos_log_entries (tenant_id, order_id);
CREATE INDEX idx_pos_log_tenant_store ON pos_log_entries (tenant_id, store_id, transaction_ts DESC);
CREATE UNIQUE INDEX uq_pos_log_order_uniq ON pos_log_entries (tenant_id, order_id);
-- "What did this cashier ring up?" is the denominator of the staff exception report: a raw count of
-- exceptions ranks staff by how much they worked, not by how they behaved.
CREATE INDEX idx_pos_log_tenant_cashier ON pos_log_entries (tenant_id, cashier_id, transaction_ts DESC)
    WHERE cashier_id IS NOT NULL;

-- ── Gap #44: Receipts — append-only log of receipt generation events ─────────
CREATE TABLE order_receipts (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    order_id     UUID        NOT NULL,
    receipt_type TEXT        NOT NULL DEFAULT 'PRINT',  -- PRINT | EMAIL
    emailed_to   TEXT,
    print_count  INTEGER     NOT NULL DEFAULT 1,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_receipts_tenant_order ON order_receipts (tenant_id, order_id);
