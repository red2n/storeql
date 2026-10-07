-- Input VAT. Box 4 of the VAT return (VAT reclaimed on purchases) and box 7 (net purchases) come
-- from the supplier invoices purchase-svc captures; database-per-service means this service cannot
-- read them, so purchase-svc publishes SupplierInvoiceCaptured and this table is the projection.
-- One row per event: the unique constraint on event_id is the idempotency, so a redelivered event
-- inserts nothing; invoice_id names the invoice the event carried. Tax point is the invoice date.
CREATE TABLE input_tax_transactions (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    event_id       UUID          NOT NULL,
    invoice_id     UUID          NOT NULL,
    po_id          UUID,
    supplier_id    UUID,
    invoice_number VARCHAR(64),
    currency       CHAR(3),
    net_amount     NUMERIC       NOT NULL,
    vat_amount     NUMERIC       NOT NULL,
    gross_amount   NUMERIC       NOT NULL,
    tax_point_date TIMESTAMPTZ   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_input_tax_event UNIQUE (event_id)
);
CREATE INDEX idx_input_tax_tenant ON input_tax_transactions (tenant_id, tax_point_date);
