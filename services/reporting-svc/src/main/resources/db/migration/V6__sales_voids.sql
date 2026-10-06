-- Voided till sales leave the sales reports.
--
-- order-svc announces OrderVoided when a till sale is voided after the fact: the receipt keeps its
-- number and gains a reason, the stock goes back, and the sale no longer stands. A voided sale must
-- not go on counting in the summary, the days, the categories and labour against takings: a till
-- that balanced against the report would not have balanced against the drawer. The mark that takes
-- it out is sales_facts.voided_at (in V2); this table is what hears the void.
--
-- Every void heard, one per order. A consumer catching up reads its topics in no particular order,
-- so a void can arrive before the OrderConfirmed of the sale it voids; kept here, it marks that sale
-- the moment it lands rather than being lost against a row that did not exist yet.
CREATE TABLE sales_voids (
    tenant_id UUID        NOT NULL,
    order_id  UUID        NOT NULL,
    -- The OrderVoided that said so: the first word on an order stands.
    event_id  UUID        NOT NULL,
    voided_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_sales_voids PRIMARY KEY (tenant_id, order_id)
);

COMMENT ON TABLE sales_voids IS
    'Voided till sales, as order-svc announced them (OrderVoided); kept so a void heard before its sale still voids it.';
