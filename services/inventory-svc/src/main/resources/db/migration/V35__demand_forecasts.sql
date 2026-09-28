-- Statistical demand forecast (demand forecasting & replenishment, 06.x).
--
-- Every grocery source treats deciding what to order before anyone asks for it as the core
-- discipline, and until now the platform stopped at the inputs: demand history, safety stock,
-- reorder points and par levels, all waiting for a figure of expected demand that nothing produced.
-- This is that figure: one row per item per store, replaced on each run, with the method the shape
-- of the demand chose (smoothing with a day-of-week profile for a steady seller, Croston with the
-- Syntetos–Boylan correction for an intermittent one, a plain mean when there is too little to say
-- more), the expected quantity per day over the horizon, and the forecast's own report on itself
-- from a hold-out of its history — each accuracy figure null when it cannot be honestly computed.
-- The reorder-point computation reads the expected demand over the lead time from here when a
-- forecast exists, and from the monthly average, as before, when it does not.
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

    CONSTRAINT pk_demand_forecasts   PRIMARY KEY (id),
    CONSTRAINT uq_demand_forecast    UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT ck_forecast_method    CHECK (method IN ('MEAN', 'SES', 'CROSTON_SBA')),
    CONSTRAINT ck_forecast_horizon   CHECK (horizon_days BETWEEN 1 AND 365),
    CONSTRAINT ck_forecast_history   CHECK (history_days >= 0 AND history_to >= history_from)
);

CREATE INDEX idx_demand_forecasts_store ON demand_forecasts (tenant_id, store_id, variant_id);
