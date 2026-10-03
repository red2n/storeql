-- ── Return controls, slice 2 (intent/return-controls.md) ────────────────────
--
-- A direct exchange (the returned value pays the new basket), a return with no receipt, and a
-- gift-card redemption that can be retried without charging the card twice.

-- An exchange names the sale it bought (exchange_order_id) and that sale names the return
-- (orders.exchanged_from_return_id), so either can be found from the other. The return stays
-- insert-only; the link on the order is written once, on the transaction that places it.
ALTER TABLE returns ADD COLUMN exchange_order_id UUID;
ALTER TABLE orders  ADD COLUMN exchanged_from_return_id UUID;

-- A return with no receipt has no sale to point at, so order_id may be empty, but only then: every
-- other return still names its order. customer_contact is the phone or email the customer gave,
-- kept for the record and never logged. customer_id is the customer whose store credit it goes to.
ALTER TABLE returns ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE returns ADD COLUMN no_receipt       BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE returns ADD COLUMN customer_id      UUID;
ALTER TABLE returns ADD COLUMN customer_contact TEXT;
ALTER TABLE returns ADD CONSTRAINT ck_returns_order_or_no_receipt
    CHECK (order_id IS NOT NULL OR no_receipt);
-- Never cash, never back to a tender that was never taken: a no-receipt refund is store credit or
-- a gift card.
ALTER TABLE returns ADD CONSTRAINT ck_returns_no_receipt_method
    CHECK (NOT no_receipt OR refund_method IN ('STORE_CREDIT', 'GIFT_CARD'));

-- What each line was worth: a no-receipt return prices its lines at the current price, so the
-- unit price (VAT included) and the VAT in the line are kept beside the refund. Null on a return
-- against a sale, whose price is the sale's.
ALTER TABLE return_items ADD COLUMN unit_price NUMERIC(18,4);
ALTER TABLE return_items ADD COLUMN tax_amount NUMERIC(18,4);

CREATE INDEX idx_returns_no_receipt ON returns (tenant_id, store_id, created_at) WHERE no_receipt;

-- A redemption carries the caller's Idempotency-Key: a retry under the same key answers with the
-- first redemption and debits nothing. Only a REDEEM row ever has one.
ALTER TABLE gift_card_transactions ADD COLUMN idempotency_key UUID;
CREATE UNIQUE INDEX uq_gct_idempotency_key
    ON gift_card_transactions (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
