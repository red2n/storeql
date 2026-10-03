-- Money in the currency's own minor units (SJ-D25, as purchase-svc's V5 did; the currency minor-units
-- sweep of 2 Oct 2026).
--
-- V1 said "All amounts: NUMERIC(18,2)" — a statement about sterling. ISO 4217 gives the yen no minor
-- unit and the Kuwaiti, Bahraini, Omani, Jordanian and Tunisian dinars three, and Postgres rounds
-- to a column's declared scale on write without complaint: a dinar list price of 1.235 was kept as
-- 1.24, and a yen price of ¥1,234 came back as 1234.00.
--
-- Unconstrained NUMERIC keeps a value at the scale it is written at. pricing-svc checks a list price
-- against its list's currency (no finer than the currency, kept at its scale) and rounds every
-- computed amount — a resolved price, a promotion, a markdown, VAT — half up to the currency's
-- minor units (common-service Fx.minorUnits) before it writes it. Existing rows are untouched.
--
-- Left as they are, on purpose:
--   * vat_return_submissions.box1..box9: HMRC's MTD VAT return, in pounds by law (boxes 1-5 to
--     the penny, 6-9 whole pounds); no other currency is ever filed there.
--   * Percentages (NUMERIC(5,2)) and VAT rates (NUMERIC(5,4)): a rate is not money.
--   * The repricing columns, already NUMERIC(19,4) — finer than any currency's minor units.

ALTER TABLE price_list_items ALTER COLUMN price TYPE NUMERIC;

ALTER TABLE price_list_item_prices ALTER COLUMN price TYPE NUMERIC;

ALTER TABLE applied_prices
    ALTER COLUMN price         TYPE NUMERIC,
    ALTER COLUMN net_price     TYPE NUMERIC,
    ALTER COLUMN regular_price TYPE NUMERIC;

ALTER TABLE promotions ALTER COLUMN min_order_amount TYPE NUMERIC;

ALTER TABLE promotion_redemptions ALTER COLUMN amount TYPE NUMERIC;

ALTER TABLE price_overrides
    ALTER COLUMN original_price TYPE NUMERIC,
    ALTER COLUMN override_price TYPE NUMERIC;

ALTER TABLE markdowns
    ALTER COLUMN original_price TYPE NUMERIC,
    ALTER COLUMN markdown_price TYPE NUMERIC;

ALTER TABLE tax_transactions
    ALTER COLUMN net_amount   TYPE NUMERIC,
    ALTER COLUMN vat_amount   TYPE NUMERIC,
    ALTER COLUMN gross_amount TYPE NUMERIC;

ALTER TABLE input_tax_transactions
    ALTER COLUMN net_amount   TYPE NUMERIC,
    ALTER COLUMN vat_amount   TYPE NUMERIC,
    ALTER COLUMN gross_amount TYPE NUMERIC;
