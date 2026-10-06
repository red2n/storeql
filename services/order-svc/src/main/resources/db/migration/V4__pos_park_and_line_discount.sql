-- POS register operations: parked (suspended) sales, no-sale/open-drawer log,
-- line-level discounts on order_items, and the customer link on a parked sale
-- (indexed for redaction, not a foreign key: customer-svc owns customers).
-- (The line-level discount columns on order_items are defined in V1__init.sql.)

-- ── Parked (suspended) sales ──────────────────────────────────────────────────
-- A cashier can park an in-progress sale and resume it later (e.g. to serve the
-- next customer while the first one fetches their loyalty card).
-- Parked sales are DRAFT state; they become orders when resumed and tendered.
-- A parked sale is kept when it is finished with, and says who finished it and how: resuming the basket
-- and discarding it are both recorded rather than deleted, because loss prevention asks who finished a
-- sale someone else started. Either one takes the sale off the open list; a sale is finished once.
CREATE TABLE parked_sales (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    cashier_id      UUID,
    customer_id     UUID,
    customer_name   TEXT,
    subtotal        NUMERIC       NOT NULL DEFAULT 0,
    discount_amount NUMERIC       NOT NULL DEFAULT 0,
    notes           TEXT,
    parked_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,                          -- optional auto-expiry
    resumed_at      TIMESTAMPTZ,                          -- set when converted to an order
    resumed_by      UUID,                                 -- who picked the basket back up
    discarded_at    TIMESTAMPTZ,                          -- set when thrown away instead
    discarded_by    UUID,                                 -- who threw it away
    order_id        UUID                                  -- the order created on resume
);
-- The open list: sales neither resumed nor discarded.
CREATE INDEX idx_parked_sales_open
    ON parked_sales (tenant_id, store_id, parked_at DESC)
    WHERE resumed_at IS NULL AND discarded_at IS NULL;
-- Redaction finds a customer's parked sales by customer.
CREATE INDEX idx_parked_sales_customer
    ON parked_sales (tenant_id, customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE parked_sale_items (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    sale_id         UUID          NOT NULL REFERENCES parked_sales(id) ON DELETE CASCADE,
    variant_id      UUID          NOT NULL,
    qty             NUMERIC(18,3) NOT NULL,
    unit_price      NUMERIC       NOT NULL,
    line_total      NUMERIC       NOT NULL,
    discount_amount NUMERIC       NOT NULL DEFAULT 0,
    -- A parked sale keeps its stickers too, so a resumed sale still rings at the sticker's price.
    markdown_id     UUID,
    notes           TEXT
);
CREATE INDEX idx_parked_items_tenant_sale
    ON parked_sale_items (tenant_id, sale_id);

-- ── No-sale / open-drawer log (append-only) ───────────────────────────────────
-- Records every non-transactional cash-drawer open (no-sale) and any manager
-- override that required a supervisor PIN. Loss-prevention audit trail.
CREATE TABLE pos_no_sale_log (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    cashier_id   UUID,
    till_session_id UUID,
    reason       TEXT,
    authorised_by UUID,                    -- supervisor UUID when override required
    logged_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_no_sale_log_tenant_store
    ON pos_no_sale_log (tenant_id, store_id, logged_at DESC);
-- The audit trail reads the stream newest-first per tenant.
CREATE INDEX idx_no_sale_log_tenant_time ON pos_no_sale_log (tenant_id, logged_at DESC);
