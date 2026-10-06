-- Statistical demand forecast (demand forecasting & replenishment, 06.x).
--
-- Every grocery source treats deciding what to order before anyone asks for it as the core
-- discipline. The platform holds the inputs — demand history, safety stock, reorder points and par
-- levels — and needs a figure of expected demand to act on: that figure is this table. One row per
-- item per store, replaced on each run, with the method the shape
-- of the demand chose (smoothing with a day-of-week profile for a steady seller, Croston with the
-- Syntetos–Boylan correction for an intermittent one, a plain mean when there is too little to say
-- more), the expected quantity per day over the horizon, and the forecast's own report on itself
-- from a hold-out of its history — each accuracy figure null when it cannot be honestly computed.
-- The reorder-point computation reads the expected demand over the lead time from here when a
-- forecast exists, and from the monthly average when it does not.
CREATE TABLE demand_forecasts (
    id              UUID           NOT NULL,
    tenant_id       UUID           NOT NULL,
    store_id        UUID           NOT NULL,
    variant_id      UUID           NOT NULL,
    method          TEXT           NOT NULL,
    intermittent    BOOLEAN        NOT NULL,
    alpha           NUMERIC(4,3),                      -- the smoothing constant the hold-out chose; null for MEAN
    level           NUMERIC(14,4)  NOT NULL,           -- expected demand per day before the weekday profile
    weekday_profile NUMERIC(8,4)[],                    -- seven multipliers, Monday first; null when none
    history_from    DATE           NOT NULL,
    history_to      DATE           NOT NULL,
    history_days    INT            NOT NULL,
    horizon_days    INT            NOT NULL,
    from_day        DATE           NOT NULL,           -- the first forecast day
    points          NUMERIC(14,4)[] NOT NULL,          -- one expected quantity per day from from_day
    holdout_days    INT            NOT NULL,
    mape            NUMERIC(10,2),                     -- over hold-out days that had demand
    bias            NUMERIC(10,2),                     -- forecast minus actual, as a percentage of actual
    mase            NUMERIC(10,3),                     -- against a naive one-day-back forecast
    computed_at     TIMESTAMPTZ    NOT NULL,

    -- Fresh and short-shelf-life items. A fresh item's level follows its last eight weeks, not six
    -- months: what sold in spring says little about what sells this week. The forecast carries what the
    -- batches say about the item's life: the median days from receipt to expiry, whether that makes it
    -- fresh (fourteen days or fewer), the waste rate (the share of sold-or-wasted stock that went out of
    -- date unsold), and the longest cover an order should be given (the shelf life). purchase-svc caps
    -- an order's cover to max_cover_days (OrderProposal), so an order is never for longer than the item
    -- lives.
    fresh           BOOLEAN        NOT NULL DEFAULT false,
    shelf_life_days INT,
    waste_rate      NUMERIC(6,4),                      -- wasted / (sold + wasted), a fraction; null when nothing sold or wasted
    max_cover_days  INT,                               -- the shelf life; null when the item keeps

    -- Seasonality and promotions. A forecast carries the year's shape: a December that sells double is
    -- not noise in September, and a promotion's fortnight must not inflate the level for months after
    -- it ends. So it carries twelve monthly indices, January first, from the days no promotion ran, once
    -- thirteen months of history have been seen — and what a promotion does to the item: the uplift
    -- measured from its own promoted days against its ordinary ones (or, when the item has too few,
    -- pooled across the store's), and how many of the history's and the horizon's days a promotion ran
    -- or will run on. The level is fitted with both taken out of the history and both put back into the
    -- days ahead. The promotion windows come from pricing-svc, which owns promotions; nothing here reads
    -- its tables.
    seasonal_indices        NUMERIC(8,4)[],            -- twelve, January first; null under thirteen months
    uplift                  NUMERIC(8,4),              -- promoted-day demand over ordinary; null when none could be measured
    uplift_source           TEXT,                      -- ITEM (its own promotions) or STORE (pooled); null with no uplift
    promoted_history_days   INT NOT NULL DEFAULT 0,
    promoted_ahead_days     INT NOT NULL DEFAULT 0,

    CONSTRAINT pk_demand_forecasts   PRIMARY KEY (id),
    CONSTRAINT uq_demand_forecast    UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT ck_forecast_method    CHECK (method IN ('MEAN', 'SES', 'CROSTON_SBA')),
    CONSTRAINT ck_forecast_horizon   CHECK (horizon_days BETWEEN 1 AND 365),
    CONSTRAINT ck_forecast_history   CHECK (history_days >= 0 AND history_to >= history_from),
    CONSTRAINT ck_forecast_uplift_source CHECK (uplift_source IS NULL OR uplift_source IN ('ITEM', 'STORE')),
    CONSTRAINT ck_forecast_uplift    CHECK (uplift IS NULL OR uplift >= 1)
);

CREATE INDEX idx_demand_forecasts_store ON demand_forecasts (tenant_id, store_id, variant_id);
