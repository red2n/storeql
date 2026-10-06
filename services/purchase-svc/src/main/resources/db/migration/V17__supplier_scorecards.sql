-- Supplier lead-time tracking and scorecards.
--
-- Every goods receipt measures the delivery it books against the order's promise — the date the
-- order named, or failing that the supplier's quoted lead time (suppliers.lead_time_days, V1) — and
-- keeps the measurement as a fact of its own. A period's facts, with the orders' fill, the returns
-- raised and the invoices matched, are weighed into one scorecard per supplier.

CREATE TABLE supplier_deliveries (
    id            UUID          NOT NULL,
    tenant_id     UUID          NOT NULL,
    supplier_id   UUID          NOT NULL,
    po_id         UUID          NOT NULL,
    gr_id         UUID          NOT NULL,
    store_id      UUID          NOT NULL,
    -- When the order went to the supplier (its creation, where it was never submitted).
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
