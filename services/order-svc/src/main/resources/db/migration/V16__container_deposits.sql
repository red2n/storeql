-- Deposit return schemes (readiness review 09.16).
--
-- A scheme puts a deposit on a drink's container at the sale and pays it back when the empty
-- container comes back. The sale keeps each deposit as its own line beside the item: what
-- container, how many, the scheme's amount, and how VAT treats it — outside the scope of VAT in
-- the United Kingdom (VATA 1994 ss.55B–55D, SI 2025/67, from 1 October 2027), taxed as the drink
-- in Germany (Verpackungsgesetz §31). The refund at the till is its own record: containers by
-- material and volume, the amount handed back, the till session it left, and one event so the
-- drawer carries the pay-out.
CREATE TABLE order_deposits (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    order_id       UUID NOT NULL REFERENCES orders (id),
    order_item_id  UUID NOT NULL,
    variant_id     UUID NOT NULL,
    material       TEXT NOT NULL,          -- PET, ALUMINIUM, STEEL or GLASS
    volume_ml      INTEGER NOT NULL,
    qty            NUMERIC(14, 3) NOT NULL,
    deposit_each   NUMERIC NOT NULL,
    amount         NUMERIC NOT NULL,       -- qty × deposit_each, as charged
    currency       TEXT NOT NULL,
    vat_treatment  TEXT NOT NULL,          -- OUTSIDE_SCOPE or STANDARD
    vat_rate       NUMERIC(8, 4),          -- the drink's rate when STANDARD; null when outside scope
    vat_amount     NUMERIC NOT NULL,       -- the VAT inside amount; 0 when outside scope
    scheme_scope   TEXT NOT NULL,          -- the scheme's country
    citation       TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_order_deposits_material CHECK (material IN ('PET', 'ALUMINIUM', 'STEEL', 'GLASS')),
    CONSTRAINT chk_order_deposits_vat CHECK (vat_treatment IN ('OUTSIDE_SCOPE', 'STANDARD'))
);
CREATE INDEX ix_order_deposits_tenant_order   ON order_deposits (tenant_id, order_id);
CREATE INDEX ix_order_deposits_tenant_created ON order_deposits (tenant_id, created_at);

CREATE TABLE container_refunds (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    store_id         UUID NOT NULL,
    till_session_id  UUID NOT NULL,
    currency         TEXT NOT NULL,
    containers       INTEGER NOT NULL,
    amount           NUMERIC NOT NULL,
    scheme_scope     TEXT NOT NULL,
    idempotency_key  TEXT NOT NULL,
    refunded_by      UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_container_refunds_key UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX ix_container_refunds_tenant_created ON container_refunds (tenant_id, created_at);

CREATE TABLE container_refund_lines (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    refund_id     UUID NOT NULL REFERENCES container_refunds (id),
    material      TEXT NOT NULL,
    volume_ml     INTEGER NOT NULL,
    count         INTEGER NOT NULL,
    deposit_each  NUMERIC NOT NULL,
    amount        NUMERIC NOT NULL,
    CONSTRAINT chk_container_refund_lines_count CHECK (count > 0)
);
CREATE INDEX ix_container_refund_lines_tenant_refund ON container_refund_lines (tenant_id, refund_id);
