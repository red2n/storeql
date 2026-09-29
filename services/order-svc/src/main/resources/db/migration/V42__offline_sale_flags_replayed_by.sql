-- Who sent an offline sale from the till's queue, beside who rang it up. The two are not the same
-- person: a queue outlives a sign-out, and a manager may press Sync now for a cashier who has gone
-- home. So an entry names the member of staff the till recorded when the sale was made (cashier_id),
-- and only when that is a login the business holds at the store; otherwise cashier_id stays null
-- and the entry reads as rung up by an unknown member of staff. replayed_by is whoever's sign-in
-- sent it, as the request said.
--
-- The table is append-only (golden rule #8) and new since V41, so the column is added nullable: a
-- row written before this migration did not say, and no row is ever updated to say it now.
ALTER TABLE offline_sale_flags ADD COLUMN replayed_by UUID;

COMMENT ON COLUMN offline_sale_flags.cashier_id IS
    'Who rang the sale up, as the till recorded it at the sale, when that is a login of the business allowed at the store; null for an unknown member of staff.';
COMMENT ON COLUMN offline_sale_flags.replayed_by IS
    'Whose sign-in sent the sale from the till''s offline queue; may differ from cashier_id.';
COMMENT ON COLUMN offline_sale_flags.instrument_standing IS
    'The register''s standing of the scale, NOT_REGISTERED when the store''s register does not hold it, or UNKNOWN_AT_SALE when the register cannot show it was fit for trade when the sale was rung up.';
