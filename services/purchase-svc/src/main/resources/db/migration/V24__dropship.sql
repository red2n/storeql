-- Dropship (readiness review: "Consignment and dropship stock ownership"), the buyer's side.
--
-- Some of what a shop sells it never holds: the supplier ships it straight to the customer, per
-- order. The business owns nothing on a shelf for it — no batch, no valuation, no goods-in — and
-- owes the supplier only for what was ordered and delivered.
--
-- An arrangement says which supplier fulfils a variant and at what cost; one is live per variant.
-- Making or ending one tells inventory-svc (VariantSourcingChanged), which then answers "available"
-- with nothing on the shelf and places a hold that draws nothing. A confirmed order with such a
-- line raises one DRAFT purchase order per supplier — source DROPSHIP, shipped to the customer, the
-- sale it came from named — for a person to submit, as a proposal's draft is. It is never received
-- into stock: the supplier's delivery to the customer is marked instead, and the cost of goods the
-- business never held is posted against what the supplier will invoice.

CREATE TABLE dropship_arrangements (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    variant_id  UUID        NOT NULL,                       -- product-svc's variant, referenced
    supplier_id UUID        NOT NULL REFERENCES suppliers (id),
    unit_cost   NUMERIC     NOT NULL,                       -- what the supplier charges per unit
    vat_code    TEXT        NOT NULL DEFAULT 'T1',
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at    TIMESTAMPTZ,
    CONSTRAINT chk_dropship_unit_cost CHECK (unit_cost >= 0)
);
-- One live arrangement per variant: which supplier ships it is never a toss-up.
CREATE UNIQUE INDEX ux_dropship_arrangement_live
    ON dropship_arrangements (tenant_id, variant_id) WHERE active;
CREATE INDEX ix_dropship_arrangements_tenant
    ON dropship_arrangements (tenant_id, created_at DESC, id);

-- A dropship order knows the sale it fulfils and where the supplier ships it.
ALTER TABLE purchase_orders
    ADD COLUMN sales_order_id UUID,                          -- order-svc's order, referenced
    ADD COLUMN ship_to        TEXT;                          -- the customer, as the order said
CREATE INDEX ix_po_sales_order ON purchase_orders (tenant_id, sales_order_id)
    WHERE sales_order_id IS NOT NULL;

ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS ck_po_source;
ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_po_source CHECK (source IN ('MANUAL', 'PROPOSAL', 'DROPSHIP'));
