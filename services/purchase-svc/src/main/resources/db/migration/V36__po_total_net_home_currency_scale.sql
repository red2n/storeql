-- The translated net is money in the business's home currency, and V22 gave it NUMERIC(18,2) —
-- the very column type V5 (SJ-D25) took off every other money column in this service.
--
-- Fx.toHome already rounds the figure to the home currency's own minor units (ISO 4217) before
-- the write; a scale fixed at 2 then rounds it again on the way in:
--
--   BHD, KWD, OMR, JOD, TND   3 minor units   BHD 0.462 SILENTLY STORED AS 0.46 — the figure the
--                                             approval decision was made against, changed after it
--   JPY, KRW, VND             0 minor units   ¥1843 came back as 1843.00, a precision the yen
--                                             does not have
--
-- Unconstrained NUMERIC stores the value at the scale it is given, so the home currency decides
-- the precision rather than the schema. Existing rows keep the value they hold (a widening cast
-- loses nothing); fx_rate stays NUMERIC(24,10) — a rate, not an amount, at Fx.RATE_SCALE.
ALTER TABLE purchase_orders
    ALTER COLUMN total_net_home TYPE NUMERIC;
