-- A range review line's revenue and margin are money in the line's own currency (V27), and V27 kept
-- them at two decimal places whatever that currency was. Postgres rounds a value with more to fit
-- the column, so a Kuwaiti dinar figure of 1.234 (three minor units, ISO 4217) was kept as 1.23
-- without a word, and the review's record of what the line earned was not what the buyer gave.
--
-- Four decimal places is the most ISO 4217 gives any currency (CLF, UYW), so every currency's minor
-- units fit. The scale a figure may carry is its currency's, and product-svc holds each to it when
-- the line is added (AssortmentService.addLines, through common-service Fx.minorUnits): a figure
-- with more is refused, never rounded. The answer writes each at its currency's minor units.
--
-- Fourteen whole digits, two more than V27's NUMERIC(14, 2) held (twelve), and the most a figure
-- may carry (AssortmentService refuses one of 10^14 or more). Widening a NUMERIC only adds room:
-- every value V27 kept is kept as it was, and a restore of a dump taken before this runs it the
-- same way.
ALTER TABLE range_review_lines
    ALTER COLUMN revenue TYPE NUMERIC(18, 4),
    ALTER COLUMN margin  TYPE NUMERIC(18, 4);

COMMENT ON COLUMN range_review_lines.revenue IS
    'Money in the line''s currency, at that currency''s minor units (ISO 4217); checked by product-svc.';
COMMENT ON COLUMN range_review_lines.margin IS
    'Money in the line''s currency, at that currency''s minor units (ISO 4217); checked by product-svc.';
