-- Return controls (slice 1): a return or void takes back the points the sale earned.
--
-- The reversal is a REVERSE entry in the append-only loyalty_ledger (the type column has no CHECK, so
-- no constraint changes). To take back only the returned part of a sale, an earning has to remember
-- what it was earned on: order_total is the order's total at the time of the earning, null for an
-- earning made by hand or before this change (a return then reverses by the programme's rate).
-- Ledger rows stay append-only: the column is written by the INSERT, never updated.
ALTER TABLE loyalty_ledger ADD COLUMN order_total NUMERIC(18,2);

-- Everything earned and everything taken back for one order, per business.
CREATE INDEX idx_loyalty_ledger_order ON loyalty_ledger (tenant_id, order_id) WHERE order_id IS NOT NULL;
