-- Gross margin and GMROI (readiness review 19.7).
--
-- Stock turn already costs every sale from the batches it drew down; what it could not do is set
-- that cost against what the sale earned, because the revenue lived in order-svc and neither service
-- may read the other's tables. OrderFulfilled carries each line's revenue, net of VAT and of the
-- order's discounts, and this service keeps it beside the movement it paid for, in the same
-- transaction and under the same per-line dedupe, so a margin is never computed from a sale half
-- recorded.
--
-- A return takes back its share of the revenue the order's line recorded, and the cost of the units
-- it returns, at the average cost of the batches that line drew down: a returned item is received
-- into a batch with no cost price of its own, so its cost can only come from the sale it reverses.

CREATE TABLE sale_revenue (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    store_id    UUID          NOT NULL,
    variant_id  UUID          NOT NULL,
    order_id    UUID          NOT NULL,
    -- Negative for a return.
    qty         NUMERIC(18,3) NOT NULL,
    -- Unconstrained NUMERIC per SJ-D25: scale belongs to the currency. Negative for a return.
    net_amount  NUMERIC       NOT NULL,
    -- Set on a return only: the cost taken back, negative. A sale's cost stays with its movements.
    cost_amount NUMERIC,
    kind        VARCHAR(10)   NOT NULL,
    recorded_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_sale_revenue_kind CHECK (kind IN ('SALE', 'RETURN'))
);
CREATE INDEX idx_sale_revenue_window ON sale_revenue (tenant_id, recorded_at);
CREATE INDEX idx_sale_revenue_line ON sale_revenue (tenant_id, order_id, variant_id);
