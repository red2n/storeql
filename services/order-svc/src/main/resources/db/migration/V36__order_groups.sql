-- Order orchestration and split fulfilment (intent/order-orchestration-and-split-fulfilment.md).
-- A delivery order the delivery-area store cannot fill alone is placed as a group: one checkout,
-- one payment, one child order per store. Each child keeps its single store, so every consumer of
-- order events keeps working; the group is order-svc's own. Every id is bound by the service.
CREATE TABLE order_groups (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    customer_id     UUID,
    login_id        UUID,
    total           NUMERIC(18,2) NOT NULL,
    currency        TEXT          NOT NULL,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_groups PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uq_order_groups_key ON order_groups (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_order_groups_login ON order_groups (tenant_id, login_id, created_at DESC);

-- The part's place in its group, the delivery-area store's part first: the order the shopper
-- reads them in. Set exactly when the order belongs to a group.
ALTER TABLE orders
    ADD COLUMN group_id   UUID REFERENCES order_groups (id),
    ADD COLUMN group_part SMALLINT,
    ADD CONSTRAINT ck_orders_group_part CHECK ((group_id IS NULL) = (group_part IS NULL));
CREATE INDEX idx_orders_group ON orders (tenant_id, group_id, group_part) WHERE group_id IS NOT NULL;
