-- Which promotions applied to an order, and for how much. Append-only (golden rule #8).
--
-- The order's promotion_discount total is on orders (V1__init.sql); it is kept apart from the staff
-- discount_amount on purpose (see the comment there).
--
-- pricing-svc keeps the redemption ledger that enforces usage caps; this is the order's own
-- record, and it exists for two readers the ledger cannot serve: a receipt that has to print
-- "Summer Sale  -£5.00" beside the line it came off, and a refund that needs to know what was
-- discounted before deciding what to give back.
CREATE TABLE order_promotions (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    order_id       UUID          NOT NULL REFERENCES orders(id),
    promotion_id   UUID          NOT NULL,
    promotion_name TEXT          NOT NULL,
    -- Null for a whole-basket promotion, which belongs to no single line.
    variant_id     UUID,
    amount         NUMERIC       NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_promotions_order ON order_promotions (tenant_id, order_id);

-- Reporting reads this by promotion to answer "what did that campaign cost us", which is the
-- question a marketing manager asks the day after it ends.
CREATE INDEX idx_order_promotions_promotion
    ON order_promotions (tenant_id, promotion_id, created_at DESC);
