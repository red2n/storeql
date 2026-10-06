-- Invoices and credit notes to business buyers (readiness review 18.9).
--
-- A sale to a VAT-registered business owes it an invoice: in Belgium, France, Poland and from 2027
-- Germany a structured EN 16931 one, in India one reported to the Invoice Registration Portal, and
-- in the UK a full VAT invoice on request. The receipt the till prints is not that document: it
-- names no buyer and is numbered per store, where a business's invoices run in one series.
-- The VAT code and rate each line was quoted at are on order_items (V1__init.sql).

-- One counter per series and year, moved under its row lock inside the issuing transaction, so a
-- rolled-back issue gives its number back: the same gapless guarantee as receipt_series (V16).
CREATE TABLE sales_invoice_series (
    tenant_id   UUID        NOT NULL,
    series_code TEXT        NOT NULL,   -- INV for invoices, CRN for credit notes
    period      TEXT        NOT NULL,   -- the calendar year the numbering restarts in
    next_number BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_sales_invoice_series PRIMARY KEY (tenant_id, series_code, period),
    CONSTRAINT chk_sales_invoice_next  CHECK (next_number >= 1)
);

-- The documents, as issued. Never updated: a correction is a credit note, and the document a buyer
-- was given is the one kept.
CREATE TABLE sales_invoices (
    id                   UUID          PRIMARY KEY,
    tenant_id            UUID          NOT NULL,
    store_id             UUID          NOT NULL,
    order_id             UUID          NOT NULL,
    return_id            UUID,                     -- a credit note's return
    type_code            TEXT          NOT NULL,   -- UNTDID 1001: 380 invoice, 381 credit note
    series_code          TEXT          NOT NULL,
    period               TEXT          NOT NULL,
    number               BIGINT        NOT NULL,
    full_number          TEXT          NOT NULL,   -- INV/2026/000001: what the document prints
    issue_date           DATE          NOT NULL,
    issued_at            TIMESTAMPTZ   NOT NULL,
    issued_by            UUID,
    customer_id          UUID          NOT NULL,
    buyer_name           TEXT          NOT NULL,
    buyer_vat_id         TEXT,
    currency             CHAR(3)       NOT NULL,
    net_amount           NUMERIC(18,2) NOT NULL,
    vat_amount           NUMERIC(18,2) NOT NULL,
    payable_amount       NUMERIC(18,2) NOT NULL,
    preceding_invoice_id UUID,                     -- the invoice a credit note credits
    customization_id     TEXT          NOT NULL,   -- EN 16931 alone, or Peppol BIS Billing 3.0
    document             TEXT          NOT NULL,   -- the UBL as issued; CII and Factur-X are written from it
    irp_payload          TEXT,                     -- India: INV-01 JSON, when it passes the portal's checks
    irp_problems         JSONB,                    -- India: what the portal would refuse, when it would
    CONSTRAINT uq_sales_invoice_number UNIQUE (tenant_id, series_code, period, number),
    CONSTRAINT chk_sales_invoice_type   CHECK (type_code IN ('380', '381')),
    CONSTRAINT chk_sales_invoice_no     CHECK (number >= 1),
    CONSTRAINT chk_sales_invoice_credit CHECK ((type_code = '381') = (return_id IS NOT NULL))
);

-- One invoice per sale and one credit note per return, held by the database.
CREATE UNIQUE INDEX uq_sales_invoice_order  ON sales_invoices (tenant_id, order_id) WHERE type_code = '380';
CREATE UNIQUE INDEX uq_sales_invoice_return ON sales_invoices (tenant_id, return_id) WHERE return_id IS NOT NULL;
CREATE INDEX idx_sales_invoices_issued ON sales_invoices (tenant_id, issued_at DESC, id);
