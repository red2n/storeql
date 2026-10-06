-- What an hour costs, so labour can be set against sales (store operations & workforce).
--
-- The hours are recorded (V17); what they cost is not, and a labour figure without a rate is a count
-- of minutes. This is the rate and nothing else: no salaries, no deductions, no payroll. A platform
-- that held payroll would owe a great deal more than this one promises, and a shop asking "what did
-- this Saturday cost me against what it took" does not need it.
--
-- Append-only, and dated: a rate that changed in April must not re-cost January. The rate in force for
-- an hour is the latest one effective on or before the day it was worked.
CREATE TABLE staff_pay_rates (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    user_id        UUID          NOT NULL,
    effective_from DATE          NOT NULL,
    hourly_rate    NUMERIC(12,4) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NOT NULL,

    -- A rate of zero is meaningful (an unpaid trial, a proprietor drawing no wage); a negative one is
    -- not.
    CONSTRAINT chk_pay_rate_amount CHECK (hourly_rate >= 0),
    -- One rate per person per day: two rates effective the same morning is an undecidable cost.
    CONSTRAINT uq_pay_rate_day UNIQUE (tenant_id, user_id, effective_from)
);

CREATE INDEX idx_pay_rates_person ON staff_pay_rates (tenant_id, user_id, effective_from DESC);

COMMENT ON TABLE staff_pay_rates IS
    'What an hour of somebody''s time costs, dated and append-only. Not payroll: no salary, no deductions.';
COMMENT ON COLUMN staff_pay_rates.effective_from IS
    'The rate in force for an hour is the latest one effective on or before the day it was worked, so a rise does not re-cost the past.';
