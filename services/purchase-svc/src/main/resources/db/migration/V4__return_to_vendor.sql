-- Return to vendor and debit notes (readiness review 07.8; Oracle SIOCS ch. 8, RMFCS RTV).
--
-- The only way stock leaves a bad delivery. A goods receipt books stock against an order, and a return
-- sends it back: a damaged case, a wrong item, an over-delivery or a recalled lot is returned to the
-- supplier rather than written off as shrink, which is the wrong record for goods the supplier owes
-- money on. A received order is not cancelled for this reason (see purchase_orders, V1): the return
-- to vendor is its reverse.
--
-- A return is two documents in one: the goods going back (a stock movement inventory-svc makes
-- when it consumes ReturnedToVendor) and the DEBIT NOTE the retailer raises against the supplier
-- for their value, priced at the order's own prices. The supplier answers with a credit note,
-- which closes the return. Neither touches the purchase order's status: what was received was
-- received, and the three-way match still compares the invoice to it — the debit note is the
-- offset, which is how accounts payable expects it.

-- One counter per tenant: a debit note number is quoted on the supplier's credit note and in the
-- ledger, so it is short, sequential and never reused. Same shape as rfq_series (V18) — a row
-- moved under its lock, not a SEQUENCE that gaps on rollback.
CREATE TABLE debit_note_series (
    tenant_id   UUID   PRIMARY KEY,
    next_number BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_debit_note_next CHECK (next_number >= 1)
);

CREATE TABLE vendor_returns (
    id                 UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL,
    po_id              UUID        NOT NULL REFERENCES purchase_orders(id),
    supplier_id        UUID        NOT NULL REFERENCES suppliers(id),
    -- The store the goods were received into, and leave from. Taken from the order, never the
    -- request: a return against an order is a return from where that order was delivered.
    store_id           UUID        NOT NULL,
    -- RAISED    the goods have gone back and the debit note is issued
    -- CREDITED  the supplier's credit note has been recorded against it
    status             TEXT        NOT NULL,
    reason             TEXT        NOT NULL,
    notes              TEXT,
    currency           CHAR(3)     NOT NULL,
    -- Unconstrained NUMERIC per SJ-D25: scale belongs to the currency, not the column.
    net_amount         NUMERIC     NOT NULL,
    vat_amount         NUMERIC     NOT NULL DEFAULT 0,
    gross_amount       NUMERIC     NOT NULL,
    debit_note_number  TEXT        NOT NULL,
    raised_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    raised_by          UUID,
    credit_note_number TEXT,
    credit_note_date   DATE,
    credit_amount      NUMERIC,
    credited_at        TIMESTAMPTZ,
    credited_by        UUID,
    idempotency_key    TEXT,
    -- The payment run that settles this return's credit against the supplier's invoices (the credit
    -- is offset in a run, see payment_run_items).
    allocated_at       TIMESTAMPTZ,
    allocated_run_id   UUID,
    CONSTRAINT chk_vendor_return_status CHECK (status IN ('RAISED', 'CREDITED')),
    CONSTRAINT chk_vendor_return_reason CHECK (reason IN
        ('DAMAGED', 'WRONG_ITEM', 'OVER_DELIVERED', 'QUALITY', 'EXPIRED', 'RECALL', 'OTHER')),
    CONSTRAINT uq_vendor_return_debit_note UNIQUE (tenant_id, debit_note_number)
);
CREATE UNIQUE INDEX uq_vendor_returns_idem
    ON vendor_returns (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_vendor_returns_po ON vendor_returns (tenant_id, po_id);
CREATE INDEX idx_vendor_returns_supplier ON vendor_returns (tenant_id, supplier_id, raised_at DESC);

CREATE TABLE vendor_return_lines (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    return_id   UUID          NOT NULL REFERENCES vendor_returns(id),
    variant_id  UUID          NOT NULL,
    qty         NUMERIC(14,3) NOT NULL,
    -- The order's price for the variant at the time of the return: what the debit note charges
    -- back. Stored, not joined, because the order can be amended afterwards and the debit note
    -- must keep saying what it said.
    unit_price  NUMERIC       NOT NULL,
    vat_code    VARCHAR(10)   NOT NULL DEFAULT 'T1',
    line_net    NUMERIC       NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_vendor_return_qty CHECK (qty > 0)
);
-- The over-return guard sums returned quantity per variant against received quantity per variant,
-- inside the raise transaction: this is its index.
CREATE INDEX idx_vendor_return_lines_return ON vendor_return_lines (tenant_id, return_id, variant_id);
