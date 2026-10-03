-- Money at its own currency's minor units, never an assumed two places.
--
-- Store credit is money in its account's currency, and a Kuwaiti, Bahraini, Jordanian, Omani,
-- Tunisian or Iraqi dinar has three decimal places (ISO 4217). These columns had two, and Postgres
-- rounds a value to the column's scale without a word: KWD 1.125 of credit issued was kept as 1.13,
-- money made out of nothing, and a balance drifted from its own ledger. Four places hold any ISO 4217
-- currency's minor units (the most is four, CLF and UYW); the service refuses an amount finer than
-- its currency's own (STORE_CREDIT_AMOUNT_INVALID), so the extra places are never filled with
-- anything a currency does not have.
--
-- Loyalty points are not money and keep their two places (points_balance, lifetime_points,
-- loyalty_ledger.points/balance_after, loyalty_point_lots, tier thresholds). manual_grants.amount holds
-- either points or store credit, so it takes the wider scale; points written there are still held
-- to two places by the requests that make them.
--
-- Only widened: every value already held keeps its digits, so nothing a restore reads differs, and
-- no view, rule or function depends on these columns.

ALTER TABLE store_credit_accounts ALTER COLUMN balance TYPE NUMERIC(18,4);

ALTER TABLE store_credit_ledger
    ALTER COLUMN amount        TYPE NUMERIC(18,4),
    ALTER COLUMN balance_after TYPE NUMERIC(18,4);

ALTER TABLE manual_grants ALTER COLUMN amount TYPE NUMERIC(18,4);

-- The sale's total a loyalty earning was made on, kept so a return takes back its share: money in
-- the sale's currency, so a dinar total keeps its third place and the share is exact.
ALTER TABLE loyalty_ledger ALTER COLUMN order_total TYPE NUMERIC(18,4);
