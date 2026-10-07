-- cart-svc owns: carts + cart_items.
-- Carts are tenant-scoped; each cart belongs to one store.
-- Guest carts use session_id; authenticated carts use customer_id.
-- Stock is NEVER reserved in the cart — reservation happens at checkout in order-svc.

CREATE TABLE carts (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    customer_id UUID,                    -- NULL for guest carts
    session_id  TEXT,                    -- NULL for authenticated carts; guest session token
    store_id    UUID        NOT NULL,    -- cart is scoped to a store
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | CHECKED_OUT | ABANDONED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Composite indexes: tenant_id first (golden rule #3).
CREATE INDEX idx_carts_tenant_customer
    ON carts (tenant_id, customer_id, status)
    WHERE customer_id IS NOT NULL;

CREATE INDEX idx_carts_tenant_session
    ON carts (tenant_id, session_id, status)
    WHERE session_id IS NOT NULL;

-- At most one ACTIVE cart per customer and per guest session. Two concurrent "get or create cart"
-- calls for the same customer or session cannot each insert one: the database refuses the second.
CREATE UNIQUE INDEX uq_carts_active_customer
    ON carts (tenant_id, customer_id)
    WHERE customer_id IS NOT NULL AND status = 'ACTIVE';

CREATE UNIQUE INDEX uq_carts_active_session
    ON carts (tenant_id, session_id)
    WHERE session_id IS NOT NULL AND status = 'ACTIVE';

CREATE TABLE cart_items (
    id          UUID            PRIMARY KEY,
    cart_id     UUID            NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    tenant_id   UUID            NOT NULL,   -- denormalized for tenant-first filtering
    variant_id  UUID            NOT NULL,
    qty         NUMERIC(10,4)   NOT NULL DEFAULT 1,
    unit_price  NUMERIC(19,4),             -- the caller's price on add; NULL if none, never priced here
    added_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    UNIQUE (cart_id, variant_id)            -- addItem upserts qty; no duplicate rows per variant
);

CREATE INDEX idx_cart_items_tenant ON cart_items (tenant_id, cart_id);
