-- Supplier payment runs and remittance (readiness review 17.10).
--
-- The ledger knows what is owed to each supplier (the accounting seam posts the creditor at capture),
-- and a payment run settles it. This is the payment run as finance suites run it: proposed from what
-- is due, approved by a second person, paid with a posting that clears the creditor against the bank,
-- and advised to each supplier. Where a supplier is paid and told is held on suppliers (V1).

CREATE TABLE payment_runs (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    -- What the bank statement and the remittance advice say: PAY-yyyymmdd-xxxxxx.
    reference      TEXT        NOT NULL,
    -- PROPOSED → APPROVED → PAID, or CANCELLED from either of the first two.
    status         VARCHAR(20) NOT NULL,
    pay_up_to      DATE        NOT NULL,
    payment_date   DATE        NOT NULL,
    currency       CHAR(3)     NOT NULL,
    total          NUMERIC     NOT NULL,
    proposed_by    UUID,
    proposed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by    UUID,
    approved_at    TIMESTAMPTZ,
    paid_by        UUID,
    paid_at        TIMESTAMPTZ,
    cancelled_by   UUID,
    cancelled_at   TIMESTAMPTZ,
    cancel_reason  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_run_status CHECK (status IN ('PROPOSED','APPROVED','PAID','CANCELLED')),
    CONSTRAINT uq_payment_run_reference UNIQUE (tenant_id, reference)
);
CREATE INDEX idx_payment_runs_tenant ON payment_runs (tenant_id, created_at DESC);

-- One row per document a run settles: an invoice it pays, or a supplier credit note it offsets.
CREATE TABLE payment_run_items (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    run_id        UUID        NOT NULL REFERENCES payment_runs(id),
    supplier_id   UUID        NOT NULL REFERENCES suppliers(id),
    store_id      UUID,
    item_type     VARCHAR(20) NOT NULL,
    document_id   UUID        NOT NULL,
    reference     TEXT        NOT NULL,
    document_date DATE,
    due_date      DATE,
    -- Positive for an invoice paid, positive for a credit note too: item_type says which way.
    amount        NUMERIC     NOT NULL,
    -- True while the run it sits in is PROPOSED or APPROVED. A document may sit in one open run at
    -- a time — the index below makes two proposals racing for the same invoice produce one run
    -- that holds it, not two runs that would each pay it.
    open          BOOLEAN     NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pri_type CHECK (item_type IN ('INVOICE','CREDIT_NOTE')),
    CONSTRAINT chk_pri_amount CHECK (amount > 0)
);
CREATE INDEX idx_pri_run ON payment_run_items (tenant_id, run_id);
CREATE UNIQUE INDEX uq_pri_open_document
    ON payment_run_items (tenant_id, item_type, document_id) WHERE open;
