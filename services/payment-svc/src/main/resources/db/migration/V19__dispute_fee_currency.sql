-- A dispute's fee is in the currency it was charged in (2 Oct 2026, review of the minor-unit work).
--
-- A card scheme disputes a charge in the charge's own currency; the acquirer charges its fee for the
-- dispute in the account's settlement currency, which is not always the same — a yen charge on a
-- Stripe account paid out in pounds is disputed in yen and costs a fee in pounds (Stripe's balance
-- transactions name their own currency). The fee was read in the dispute's currency, so 1500 pence
-- was kept as 1500 yen. From now on the fee keeps the currency it was charged in, and every row
-- written from now on names it.
--
-- Every dispute recorded before this had its fee read, or typed from the acquirer's letter, in the
-- dispute's currency, so that is what it is given; no figure is touched. The column stays nullable,
-- read as the dispute's own currency where it is null (DisputeRepository), so a business's export
-- taken before this column existed can still be imported (21.14): its rows carry no fee currency,
-- and their fee was in the dispute's.
ALTER TABLE disputes ADD COLUMN fee_currency VARCHAR(3);
UPDATE disputes SET fee_currency = currency WHERE fee_currency IS NULL;
ALTER TABLE disputes
    ADD CONSTRAINT ck_disputes_fee_currency CHECK (fee_currency IS NULL OR fee_currency ~ '^[A-Z]{3}$');

COMMENT ON COLUMN disputes.fee_currency IS
    'The currency the acquirer charged the dispute fee in (its settlement currency); null reads as the dispute''s own.';
