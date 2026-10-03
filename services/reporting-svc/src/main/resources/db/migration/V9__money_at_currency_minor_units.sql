-- Money at its own currency's minor units, never an assumed two places.
--
-- A Kuwaiti, Bahraini, Jordanian, Omani, Tunisian or Iraqi dinar has three decimal places (ISO 4217),
-- and the sales and labour facts kept two. Postgres rounds a value to the column's scale without a
-- word, so a KWD 1.125 sale was reported as 1.13 and a day's dinar takings drifted from the till's
-- by a fils a sale. Four places hold any ISO 4217 currency's minor units (the most is four, CLF and
-- UYW), as unit_price here already does; the reports show each figure at its own currency's units
-- (Mappers.money), so pounds still read 12.50 and yen 1500.
--
-- Only widened: every value already held keeps its digits, so nothing a restore reads differs, and
-- no view, rule or function depends on these columns. Quantities (qty NUMERIC(18,3)) are not money
-- and are unchanged.

ALTER TABLE sales_facts
    ALTER COLUMN gross_amount    TYPE NUMERIC(18,4),
    ALTER COLUMN refunded_amount TYPE NUMERIC(18,4);

ALTER TABLE sales_line_facts ALTER COLUMN line_total TYPE NUMERIC(18,4);

-- What a time entry cost, in the business's currency (null when no rate was in force).
ALTER TABLE labour_facts ALTER COLUMN cost TYPE NUMERIC(18,4);
