-- Supplier lead-time tracking and scorecards.
--
-- Every goods receipt measures the delivery it books against the order's promise — the date the
-- order named, or failing that the supplier's quoted lead time — and keeps the measurement as a
-- fact of its own. A period's facts, with the orders' fill, the returns raised and the invoices
-- matched, are weighed into one scorecard per supplier.

-- The supplier's quoted lead time in days: the promise a delivery is measured against when the
-- order named no date. Null when the supplier has never quoted one.
ALTER TABLE suppliers ADD COLUMN lead_time_days INT;
ALTER TABLE suppliers
    ADD CONSTRAINT ck_supplier_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0);

-- When the order went to the supplier: the moment it became SUBMITTED, whether straight from
-- DRAFT or through approval. Null for orders that never did, and for orders older than this.
ALTER TABLE purchase_orders ADD COLUMN submitted_at TIMESTAMPTZ;

CREATE TABLE supplier_deliveries (
    id            UUID          NOT NULL,
    tenant_id     UUID          NOT NULL,
    supplier_id   UUID          NOT NULL,
    po_id         UUID          NOT NULL,
    gr_id         UUID          NOT NULL,
    store_id      UUID          NOT NULL,
    -- When the order went to the supplier (its creation, for an order submitted before this).
    ordered_at    TIMESTAMPTZ   NOT NULL,
    -- The date the goods were due: the order's, or ordered_at plus the quoted lead time; null
    -- when nothing was promised.
    promised_date DATE,
    received_at   TIMESTAMPTZ   NOT NULL,
    -- Whole days from order to arrival, never negative.
    lead_days     INT           NOT NULL,
    -- Days after the promise; negative when early; null when nothing was promised.
    late_days     INT,
    -- Whether this receipt completed the order.
    complete      BOOLEAN       NOT NULL,
    received_qty  NUMERIC(14,3) NOT NULL,
    CONSTRAINT pk_supplier_deliveries PRIMARY KEY (id),
    CONSTRAINT uq_supplier_delivery_receipt UNIQUE (gr_id),
    CONSTRAINT fk_supplier_delivery_receipt FOREIGN KEY (gr_id) REFERENCES goods_receipts (id),
    CONSTRAINT ck_supplier_delivery_lead CHECK (lead_days >= 0),
    CONSTRAINT ck_supplier_delivery_qty CHECK (received_qty >= 0)
);
CREATE INDEX idx_supplier_deliveries_supplier
    ON supplier_deliveries (tenant_id, supplier_id, received_at DESC);
CREATE INDEX idx_supplier_deliveries_period ON supplier_deliveries (tenant_id, received_at);
