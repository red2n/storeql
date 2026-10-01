-- The waits a business sets on an order, one row per business (fulfilment-overrides slice 2 and
-- unit-pricing-and-listing-rules slice 5). Every limit is off until the business sets it: a null
-- is "the platform's own default" for the unpaid-order limit and "never" for the price wait.
CREATE TABLE order_settings (
    tenant_id               UUID PRIMARY KEY,
    pending_limit_hours     INTEGER,
    price_wait_flag_minutes   INTEGER,
    price_wait_cancel_minutes INTEGER,
    updated_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_order_settings_pending CHECK (pending_limit_hours IS NULL OR pending_limit_hours >= 1),
    CONSTRAINT ck_order_settings_price_flag CHECK (price_wait_flag_minutes IS NULL OR price_wait_flag_minutes >= 1),
    CONSTRAINT ck_order_settings_price_cancel CHECK (
        price_wait_cancel_minutes IS NULL
        OR (price_wait_cancel_minutes >= 1
            AND (price_wait_flag_minutes IS NULL OR price_wait_cancel_minutes >= price_wait_flag_minutes)))
);

-- Set once, by the sweeper, when an order waiting for a price passes the first limit.
ALTER TABLE orders ADD COLUMN price_overdue_at TIMESTAMPTZ;
CREATE INDEX idx_orders_awaiting_price ON orders (tenant_id, created_at) WHERE status = 'AWAITING_PRICE';

-- Gift-card value has a sale behind it (till-sessions-and-registers slice 8). A card sold is a
-- line on an order; the card is issued or topped up when the order is paid, and gift_card_id is
-- written then, once. target_code is the card to top up, or null for a new one.
CREATE TABLE gift_card_load_lines (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    order_id     UUID NOT NULL,
    amount       NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    target_code  TEXT,
    gift_card_id UUID,
    loaded_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_gift_card_load_lines_order ON gift_card_load_lines (tenant_id, order_id);

-- Where the value came from, and who put it there and why, for a card a manager loads by hand.
ALTER TABLE gift_cards ADD COLUMN source TEXT;
ALTER TABLE gift_cards ADD COLUMN reason TEXT;
ALTER TABLE gift_card_transactions ADD COLUMN source TEXT;
ALTER TABLE gift_card_transactions ADD COLUMN reason TEXT;
ALTER TABLE gift_card_transactions ADD COLUMN acted_by UUID;
