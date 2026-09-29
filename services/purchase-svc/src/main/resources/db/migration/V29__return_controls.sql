-- Return controls (intent/return-controls.md) reach the deferred-revenue ledger.
--
-- Points taken back because the sale that earned them was returned or voided arrive as a fifth kind
-- of loyalty event: the deferral that went with them goes back to sales. The kind check learns it.
ALTER TABLE loyalty_events DROP CONSTRAINT loyalty_events_kind_check;
ALTER TABLE loyalty_events ADD CONSTRAINT loyalty_events_kind_check
    CHECK (kind IN ('EARNED', 'REDEEMED', 'ADJUSTED', 'EXPIRED', 'REVERSED'));

-- A gift card loaded by a return's refund is counted in the gift-card pool but posts nothing of its
-- own: the refund, announced by payment-svc, already credits the gift-card liability, and a second
-- posting would owe the cardholder twice. Such a load has no journal; every other load still must.
ALTER TABLE gift_card_loads ALTER COLUMN journal_id DROP NOT NULL;
ALTER TABLE gift_card_loads ADD CONSTRAINT ck_gift_card_loads_journal
    CHECK (journal_id IS NOT NULL OR paid_by = 'RETURN');
