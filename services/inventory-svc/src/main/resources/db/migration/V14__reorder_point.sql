-- Gap #19: Reorder Point planning with EOQ
-- ROP = average daily demand × lead time days + safety stock
-- EOQ = sqrt(2 × annual demand × ordering cost / holding cost per unit per year)

CREATE TABLE reorder_point_plans (
    id              UUID            NOT NULL,
    tenant_id       UUID            NOT NULL,
    store_id        UUID            NOT NULL,
    variant_id      UUID            NOT NULL,
    lead_time_days  INT             NOT NULL DEFAULT 7,
    -- Cost per order placed, in the business currency. A planning estimate (an input to the square root
    -- of the EOQ), not money owed, paid or posted, so NUMERIC(18,2) is kept on purpose: in practice a
    -- minor unit either way moves the order quantity by very little.
    ordering_cost   NUMERIC(18,2)   NOT NULL DEFAULT 0,
    holding_cost_pct NUMERIC(7,4)   NOT NULL DEFAULT 0.20, -- annual holding cost as % of unit cost
    unit_cost       NUMERIC(18,6)   NOT NULL DEFAULT 0,
    -- Order modifiers: the minimum and maximum order quantity and the lot multiplier. Null when the plan sets none.
    min_order_qty   NUMERIC(18,3),
    max_order_qty   NUMERIC(18,3),
    lot_multiplier  NUMERIC(18,3),
    -- computed results (null until first compute run)
    avg_daily_demand NUMERIC(18,6),
    rop             NUMERIC(18,3),                         -- reorder point quantity
    eoq             NUMERIC(18,3),                         -- economic order quantity
    computed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_rop_plans PRIMARY KEY (id),
    CONSTRAINT uq_rop_plan  UNIQUE (tenant_id, store_id, variant_id)
);

CREATE INDEX idx_rop_tenant ON reorder_point_plans (tenant_id, store_id);
