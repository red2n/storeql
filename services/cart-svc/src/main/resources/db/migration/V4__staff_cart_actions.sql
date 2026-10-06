-- Assisted shopping leaves a trace: when a member of staff adds to, changes or removes from a cart
-- that is not their own (a shopper's, or a guest's reached without its session token), one row says
-- who did it, in which role, to which cart, and what.
--
-- Append-only: rows are inserted on the same transaction as the change they describe and never
-- updated or deleted. A shopper acting on their own cart writes nothing here.
CREATE TABLE cart_staff_actions (
    id         UUID          PRIMARY KEY,
    tenant_id  UUID          NOT NULL,
    cart_id    UUID          NOT NULL,
    action     TEXT          NOT NULL,             -- ADD_ITEM | SET_QTY | REMOVE_ITEM
    item_id    UUID,
    variant_id UUID,
    qty        NUMERIC(10,4),                      -- added, the new quantity, or the quantity removed
    actor_id   UUID,                               -- the signed-in staff user
    actor_role TEXT          NOT NULL,             -- OWNER | MANAGER | STOREKEEPER | CASHIER
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_cart_staff_actions_cart ON cart_staff_actions (tenant_id, cart_id, created_at DESC);
