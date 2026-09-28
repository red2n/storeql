-- Consignment stock (readiness review: "Consignment and dropship stock ownership"), the buyer's
-- side.
--
-- Under a consignment (sale-or-return) agreement the supplier delivers stock it still owns; the
-- business sells it, and only the sale creates a debt. Until now every purchase order was for
-- goods the business would own on arrival: the receipt posted a stock asset against goods
-- received not invoiced, and the supplier's invoice cleared it. None of that is true of
-- consignment stock — nothing is owed at the door.
--
-- A purchase order now says whose the goods will be. A receipt against a CONSIGNMENT order tells
-- inventory-svc so (GoodsReceived carries the ownership and the supplier) and posts nothing.
-- When inventory-svc sells from such a batch it announces ConsignmentStockSold; each announcement
-- becomes one row here, owed to the supplier at the order's price — cost of sales against the
-- supplier, the moment the debt exists — and a settlement gathers a period's unsettled sales into
-- one statement for the supplier to invoice against. A sale is settled once: the settlement that
-- took it is the one thing ever written to its row after it is recorded.

ALTER TABLE purchase_orders ADD COLUMN ownership TEXT NOT NULL DEFAULT 'OWNED';
ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_po_ownership CHECK (ownership IN ('OWNED', 'CONSIGNMENT'));

CREATE TABLE consignment_sales (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    event_id      UUID        NOT NULL,                      -- inventory-svc's announcement, once
    supplier_id   UUID        NOT NULL REFERENCES suppliers (id),
    store_id      UUID,
    variant_id    UUID        NOT NULL,
    batch_id      UUID,                                      -- inventory-svc's batch, referenced
    order_id      UUID,                                      -- order-svc's sale, referenced
    qty           NUMERIC     NOT NULL,
    unit_cost     NUMERIC     NOT NULL,                      -- the order's price, per unit
    amount        NUMERIC     NOT NULL,                      -- qty x unit_cost, in the currency
    currency      CHAR(3)     NOT NULL,                      -- the supplier's invoicing currency
    sold_on       DATE        NOT NULL,
    settlement_id UUID,                                      -- set once, by the statement that took it
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_consignment_sale_event UNIQUE (tenant_id, event_id),
    CONSTRAINT chk_consignment_sale_qty CHECK (qty > 0),
    CONSTRAINT chk_consignment_sale_cost CHECK (unit_cost >= 0)
);
CREATE INDEX ix_consignment_sales_open
    ON consignment_sales (tenant_id, supplier_id, sold_on) WHERE settlement_id IS NULL;
CREATE INDEX ix_consignment_sales_tenant ON consignment_sales (tenant_id, recorded_at DESC, id);

CREATE TABLE consignment_settlements (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    supplier_id UUID        NOT NULL REFERENCES suppliers (id),
    reference   TEXT        NOT NULL,                        -- what the supplier invoices against
    period_from DATE        NOT NULL,
    period_to   DATE        NOT NULL,
    currency    CHAR(3)     NOT NULL,
    total       NUMERIC     NOT NULL,
    sales_count INT         NOT NULL,
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_consignment_settlement_reference UNIQUE (tenant_id, reference),
    CONSTRAINT chk_consignment_settlement_period CHECK (period_from <= period_to)
);
CREATE INDEX ix_consignment_settlements_tenant
    ON consignment_settlements (tenant_id, supplier_id, created_at DESC, id);

ALTER TABLE consignment_sales
    ADD CONSTRAINT fk_consignment_sale_settlement
        FOREIGN KEY (settlement_id) REFERENCES consignment_settlements (id);
