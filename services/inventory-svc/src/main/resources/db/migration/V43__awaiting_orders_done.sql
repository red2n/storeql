-- Wave picking, hardened after review (intent/wave-picking-and-directed-putaway.md).
--
-- An order that is done — cancelled, or handed over in full — leaves a tombstone here when it
-- leaves the waiting list, so a confirmation that arrives after it (the topics are consumed in
-- either order) cannot make the order wait again. The order id is order-svc's UUIDv7, bound on
-- every insert; nothing here is minted.
CREATE TABLE awaiting_orders_done (
    order_id  UUID        NOT NULL,
    tenant_id UUID        NOT NULL,
    done_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_awaiting_orders_done PRIMARY KEY (order_id)
);
CREATE INDEX idx_awaiting_orders_done_tenant ON awaiting_orders_done (tenant_id, done_at);
