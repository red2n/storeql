-- Landed cost apportionment (07.x): freight, duty, insurance and the like, charged against a goods
-- receipt after the goods themselves and spread over its lines so the stock carries what it really
-- cost to get it onto the shelf.
--
-- A charge is applied once and never edited: what the lines were apportioned is what the stock was
-- revalued by and what the ledger posted. A mistake is reversed, with a reason, and applied again;
-- both stay. The lines sum to the charge exactly — the rounding remainder sits on the heaviest line.
CREATE TABLE landed_costs (
    id               UUID         PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    gr_id            UUID         NOT NULL REFERENCES goods_receipts(id),
    po_id            UUID         NOT NULL REFERENCES purchase_orders(id),
    store_id         UUID         NOT NULL,
    charge_type      VARCHAR(20)  NOT NULL,
    basis            VARCHAR(20)  NOT NULL,
    currency         CHAR(3)      NOT NULL,
    amount           NUMERIC      NOT NULL,
    reference        TEXT,
    charged_by       UUID         REFERENCES suppliers(id),
    notes            TEXT,
    status           VARCHAR(20)  NOT NULL,
    applied_at       TIMESTAMPTZ  NOT NULL,
    applied_by       UUID,
    reversed_at      TIMESTAMPTZ,
    reversed_by      UUID,
    reversed_reason  TEXT,
    idempotency_key  TEXT,
    CONSTRAINT chk_landed_charge_type CHECK (charge_type IN ('FREIGHT','DUTY','INSURANCE','HANDLING','OTHER')),
    CONSTRAINT chk_landed_basis       CHECK (basis IN ('BY_VALUE','BY_QUANTITY')),
    CONSTRAINT chk_landed_amount      CHECK (amount > 0),
    CONSTRAINT chk_landed_status      CHECK (status IN ('APPLIED','REVERSED')),
    -- A reversal carries its moment and its reason, and nothing else does.
    CONSTRAINT chk_landed_reversal    CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL AND reversed_reason IS NOT NULL))
);
CREATE INDEX idx_landed_costs_gr ON landed_costs (tenant_id, gr_id, applied_at DESC);
CREATE INDEX idx_landed_costs_po ON landed_costs (tenant_id, po_id, applied_at DESC);
CREATE UNIQUE INDEX uq_landed_costs_key ON landed_costs (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE landed_cost_lines (
    id              UUID     PRIMARY KEY,
    tenant_id       UUID     NOT NULL,
    landed_cost_id  UUID     NOT NULL REFERENCES landed_costs(id),
    gr_line_id      UUID     NOT NULL REFERENCES goods_receipt_lines(id),
    variant_id      UUID     NOT NULL,
    qty             NUMERIC  NOT NULL,
    -- The receipt line's value at the order's price: the weight a BY_VALUE apportionment uses.
    line_value      NUMERIC  NOT NULL,
    -- This line's share of the charge, to the currency's minor unit; the lines sum to the charge.
    amount          NUMERIC  NOT NULL,
    -- What one unit's cost rises by: amount over quantity, to four places.
    per_unit        NUMERIC  NOT NULL,
    CONSTRAINT chk_landed_line_qty    CHECK (qty > 0),
    CONSTRAINT chk_landed_line_amount CHECK (amount >= 0)
);
CREATE INDEX idx_landed_cost_lines ON landed_cost_lines (tenant_id, landed_cost_id);
