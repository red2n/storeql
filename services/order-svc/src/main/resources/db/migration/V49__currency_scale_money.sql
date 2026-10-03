-- Money in the currency's own minor units (SJ-D25, as purchase-svc's V5 did; the currency minor-units
-- sweep of 2 Oct 2026).
--
-- NUMERIC(18,2) and NUMERIC(14,2) are statements about sterling, not about money. ISO 4217 gives
-- the yen and the won no minor unit and the Kuwaiti, Bahraini and Omani dinars, the Jordanian dinar
-- and the Tunisian dinar three. Postgres rounds to a column's declared scale on write without
-- complaint, so a dinar business's 1.235 was kept as 1.24 on every order, return, gift card and
-- deposit, and a yen business's ¥1,234 came back as 1234.00, a precision the yen does not have.
--
-- Unconstrained NUMERIC keeps a value at the scale it is written at, so the currency decides the
-- precision: order-svc rounds every amount half up to the currency's minor units (common-service
-- Fx.minorUnits) before it writes it — what the column used to do for the pound, and right for the
-- yen and the dinar. Existing rows are untouched: they are already at two places.
--
-- Left as they are, on purpose:
--   * sales_invoices and ereporting_submissions: EN 16931 (BR-DEC) and the French e-reporting flux
--     state amounts to at most two decimals; an invoice or a report in those formats is in euros
--     (or another two-decimal currency) by the law that asks for it.
--   * order_items.vat_amount NUMERIC(18,4), return_items.unit_price NUMERIC(18,4), the return
--     policy's ceilings NUMERIC(18,4), fiscal receipts' NUMERIC(18,4) totals: already finer than
--     any currency's minor units.
--   * Quantities (NUMERIC(18,3)): how finely stock is counted has nothing to do with the currency.

ALTER TABLE orders
    ALTER COLUMN subtotal           TYPE NUMERIC,
    ALTER COLUMN tax_amount         TYPE NUMERIC,
    ALTER COLUMN discount_amount    TYPE NUMERIC,
    ALTER COLUMN total              TYPE NUMERIC,
    ALTER COLUMN paid_amount        TYPE NUMERIC,
    ALTER COLUMN refunded_amount    TYPE NUMERIC,
    ALTER COLUMN promotion_discount TYPE NUMERIC;

ALTER TABLE order_items
    ALTER COLUMN unit_price      TYPE NUMERIC,
    ALTER COLUMN line_total      TYPE NUMERIC,
    ALTER COLUMN discount_amount TYPE NUMERIC;

ALTER TABLE order_groups ALTER COLUMN total TYPE NUMERIC;

ALTER TABLE order_discounts
    ALTER COLUMN subtotal        TYPE NUMERIC,
    ALTER COLUMN discount_amount TYPE NUMERIC;

ALTER TABLE order_promotions ALTER COLUMN amount TYPE NUMERIC;

ALTER TABLE order_payment_events ALTER COLUMN amount TYPE NUMERIC;

ALTER TABLE order_line_adjustments
    ALTER COLUMN charged_amount TYPE NUMERIC,
    ALTER COLUMN refund_amount  TYPE NUMERIC;

ALTER TABLE returns      ALTER COLUMN refund_amount TYPE NUMERIC;
ALTER TABLE return_items ALTER COLUMN refund_amount TYPE NUMERIC;

ALTER TABLE layaways
    ALTER COLUMN total_amount TYPE NUMERIC,
    ALTER COLUMN deposit_paid TYPE NUMERIC,
    ALTER COLUMN balance      TYPE NUMERIC;

ALTER TABLE layaway_items
    ALTER COLUMN unit_price TYPE NUMERIC,
    ALTER COLUMN line_total TYPE NUMERIC;

ALTER TABLE layaway_deposits ALTER COLUMN amount TYPE NUMERIC;

ALTER TABLE gift_cards
    ALTER COLUMN initial_balance TYPE NUMERIC,
    ALTER COLUMN current_balance TYPE NUMERIC;

ALTER TABLE gift_card_transactions
    ALTER COLUMN amount         TYPE NUMERIC,
    ALTER COLUMN balance_before TYPE NUMERIC,
    ALTER COLUMN balance_after  TYPE NUMERIC;

ALTER TABLE gift_card_load_lines ALTER COLUMN amount TYPE NUMERIC;

ALTER TABLE special_orders
    ALTER COLUMN subtotal TYPE NUMERIC,
    ALTER COLUMN total    TYPE NUMERIC;

ALTER TABLE special_order_items
    ALTER COLUMN unit_price TYPE NUMERIC,
    ALTER COLUMN line_total TYPE NUMERIC;

ALTER TABLE pos_log_entries
    ALTER COLUMN subtotal        TYPE NUMERIC,
    ALTER COLUMN tax_amount      TYPE NUMERIC,
    ALTER COLUMN discount_amount TYPE NUMERIC,
    ALTER COLUMN total           TYPE NUMERIC;

ALTER TABLE parked_sales
    ALTER COLUMN subtotal        TYPE NUMERIC,
    ALTER COLUMN discount_amount TYPE NUMERIC;

ALTER TABLE parked_sale_items
    ALTER COLUMN unit_price      TYPE NUMERIC,
    ALTER COLUMN line_total      TYPE NUMERIC,
    ALTER COLUMN discount_amount TYPE NUMERIC;

ALTER TABLE order_deposits
    ALTER COLUMN deposit_each TYPE NUMERIC,
    ALTER COLUMN amount       TYPE NUMERIC,
    ALTER COLUMN vat_amount   TYPE NUMERIC;

ALTER TABLE container_refunds ALTER COLUMN amount TYPE NUMERIC;

ALTER TABLE container_refund_lines
    ALTER COLUMN deposit_each TYPE NUMERIC,
    ALTER COLUMN amount       TYPE NUMERIC;

ALTER TABLE commission_statements
    ALTER COLUMN net_sales  TYPE NUMERIC,
    ALTER COLUMN commission TYPE NUMERIC;

ALTER TABLE commission_statement_lines
    ALTER COLUMN threshold_from TYPE NUMERIC,
    ALTER COLUMN commission     TYPE NUMERIC;
