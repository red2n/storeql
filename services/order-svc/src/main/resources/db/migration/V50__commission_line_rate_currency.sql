-- A commission statement line says what currency a per-unit rate is in, and what it earned there
-- when that is not the statement's (the currency minor-units sweep, 2 Oct 2026).
--
-- A per-unit arrangement pays an amount per unit in the currency it names, which may be any ISO
-- 4217 code; a statement is counted in one currency. The line's commission is in the statement's
-- currency, translated at the business's own rate when it has to be — and a line that showed a
-- rate of 0.50 beside a commission of 0.43 would explain nothing to the person paid on it. So the
-- line keeps the rate's currency and the commission as rated in it, and the statement explains
-- itself after the rate has moved on.
--
-- Both are null on every line written before this, which were all in the statement's currency
-- (nothing was translated), and on a percentage line, whose rate is a ratio.

ALTER TABLE commission_statement_lines
    ADD COLUMN rate_currency    CHAR(3),
    ADD COLUMN rated_commission NUMERIC;

ALTER TABLE commission_statement_lines
    ADD CONSTRAINT ck_line_rated_commission CHECK (rated_commission IS NULL OR rate_currency IS NOT NULL);
