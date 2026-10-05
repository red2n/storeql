-- Money at its own currency's minor units, never an assumed two places.
--
-- A Kuwaiti, Bahraini, Jordanian, Omani, Tunisian or Iraqi dinar has three decimal places (ISO 4217),
-- and two columns here had two. Postgres rounds a value to the column's scale without a word, so a
-- card taken for KWD 1.125 would have been recorded as 1.13, and a day's KWD takings rounded to the
-- 0.01 in the z-report that settles it. Every other money column in this schema is already four
-- places (NUMERIC(14,4) or (18,4)), which holds any ISO 4217 currency's minor units (the most is
-- four, CLF and UYW); these two are brought into line.
--
-- Only widened: every value already held keeps its digits, so nothing a restore reads differs, and
-- no view, rule or function depends on these columns.

-- The card attempt: the amount the device was asked for, at its currency's units (TerminalService).
ALTER TABLE terminal_payments ALTER COLUMN amount TYPE NUMERIC(18,4);

-- The settled day's totals, beside the cash figures that were already four places.
ALTER TABLE z_reports
    ALTER COLUMN total_sales     TYPE NUMERIC(18,4),
    ALTER COLUMN total_refunds   TYPE NUMERIC(18,4),
    ALTER COLUMN total_discounts TYPE NUMERIC(18,4),
    ALTER COLUMN total_tax       TYPE NUMERIC(18,4),
    ALTER COLUMN net_sales       TYPE NUMERIC(18,4),
    ALTER COLUMN cash_sales      TYPE NUMERIC(18,4),
    ALTER COLUMN card_sales      TYPE NUMERIC(18,4),
    ALTER COLUMN gift_card_sales TYPE NUMERIC(18,4),
    ALTER COLUMN other_sales     TYPE NUMERIC(18,4);
