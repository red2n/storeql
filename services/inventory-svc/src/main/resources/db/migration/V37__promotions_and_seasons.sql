-- Promotional uplift and seasonality (demand forecasting & replenishment, 06.x).
--
-- The forecast knew the week and nothing longer: a December that sells double looked like noise in
-- September, and a promotion's fortnight inflated the level for months after it ended, then was
-- forgotten the day the next one was planned. Each forecast now carries the year's shape — twelve
-- monthly indices, January first, from the days no promotion ran, once thirteen months of history
-- have been seen — and what a promotion does to the item: the uplift measured from its own promoted
-- days against its ordinary ones (or, when the item has too few, pooled across the store's), and how
-- many of the history's and the horizon's days a promotion ran or will run on. The level is fitted
-- with both taken out of the history and both put back into the days ahead. The promotion windows
-- come from pricing-svc, which owns promotions; nothing here reads its tables.
ALTER TABLE demand_forecasts ADD COLUMN seasonal_indices NUMERIC(8,4)[];          -- twelve, January first; null under thirteen months
ALTER TABLE demand_forecasts ADD COLUMN uplift NUMERIC(8,4);                       -- promoted-day demand over ordinary; null when none could be measured
ALTER TABLE demand_forecasts ADD COLUMN uplift_source TEXT;                        -- ITEM (its own promotions) or STORE (pooled); null with no uplift
ALTER TABLE demand_forecasts ADD COLUMN promoted_history_days INT NOT NULL DEFAULT 0;
ALTER TABLE demand_forecasts ADD COLUMN promoted_ahead_days INT NOT NULL DEFAULT 0;
ALTER TABLE demand_forecasts ADD CONSTRAINT ck_forecast_uplift_source
    CHECK (uplift_source IS NULL OR uplift_source IN ('ITEM', 'STORE'));
ALTER TABLE demand_forecasts ADD CONSTRAINT ck_forecast_uplift CHECK (uplift IS NULL OR uplift >= 1);
