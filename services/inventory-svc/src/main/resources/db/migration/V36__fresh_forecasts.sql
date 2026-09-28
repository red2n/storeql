-- Fresh and short-shelf-life forecasting (demand forecasting & replenishment, 06.x).
--
-- A fresh item is forecast like any other and ordered unlike any other: an order that outlives the
-- item is waste, not stock. Each forecast now carries what the batches say about the item's life —
-- the median days from receipt to expiry, whether that makes it fresh (fourteen days or fewer),
-- how much of what was received went out of date unsold (the waste rate), and the longest cover an
-- order should be given (the shelf life) — so the order proposal can cap the cover and the EOQ to
-- what sells before the item expires. A fresh item's level also follows its last eight weeks, not
-- six months: what sold in spring says little about what sells this week.
ALTER TABLE demand_forecasts ADD COLUMN fresh BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE demand_forecasts ADD COLUMN shelf_life_days INT;
ALTER TABLE demand_forecasts ADD COLUMN waste_rate NUMERIC(6,4);      -- fraction of received that expired unsold; null when nothing sold or wasted
ALTER TABLE demand_forecasts ADD COLUMN max_cover_days INT;           -- the shelf life; null when the item keeps
