-- Loyalty points that expire (13.x) reach the deferred-revenue ledger (17.11) as a fourth kind of
-- event: a lapse the customer did not choose, whose deferred income is breakage once nothing is
-- outstanding. The kind check learns the word.
ALTER TABLE loyalty_events DROP CONSTRAINT loyalty_events_kind_check;
ALTER TABLE loyalty_events ADD CONSTRAINT loyalty_events_kind_check
    CHECK (kind IN ('EARNED', 'REDEEMED', 'ADJUSTED', 'EXPIRED'));
