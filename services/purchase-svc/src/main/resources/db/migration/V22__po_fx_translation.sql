-- Multi-currency pricing and FX (03.x): the spend ceiling translated.
--
-- A spend ceiling had to be declared per currency because nothing could translate one; now a
-- purchase order in a currency with no ceiling of its own is measured in the business's home
-- currency at the rate the business keeps. The figure the decision was made against is kept on
-- the order — the rate and the translated net — because a rate moves and the record must not.
ALTER TABLE purchase_orders ADD COLUMN fx_rate        NUMERIC(24,10);   -- home units per one unit of the order's currency, as used at submission
ALTER TABLE purchase_orders ADD COLUMN total_net_home NUMERIC(18,2);    -- the net translated into the home currency at that rate
ALTER TABLE purchase_orders ADD COLUMN home_currency  CHAR(3);          -- the home currency the translation was into
