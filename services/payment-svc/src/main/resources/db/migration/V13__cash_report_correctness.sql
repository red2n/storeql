-- Cash-report correctness (intent/till-sessions-and-registers.md, slice 1 and the payment-svc part
-- of slice 3).
--
-- A closed till session keeps who closed it and the note the closer gave for a difference. Both are
-- written once, by the close that also writes counted_cash and over_short.
ALTER TABLE till_sessions ADD COLUMN IF NOT EXISTS closed_by UUID;
ALTER TABLE till_sessions ADD COLUMN IF NOT EXISTS note TEXT;

-- The day report is append-only: a day is settled once, and a correction is a NEW ROW that names
-- the row it replaces (version n+1, replaces_id = version n), never an overwrite. The unique key
-- therefore gains the version. Rows written before this keep what they have (version 1); their
-- time_zone is null, which the answer reads as "UTC, assumed" because that is how they were counted.
ALTER TABLE z_reports ADD COLUMN IF NOT EXISTS cash_refunds NUMERIC(14,4) NOT NULL DEFAULT 0;
ALTER TABLE z_reports ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE z_reports ADD COLUMN IF NOT EXISTS replaces_id UUID;
ALTER TABLE z_reports ADD COLUMN IF NOT EXISTS correction_reason TEXT;
ALTER TABLE z_reports ADD COLUMN IF NOT EXISTS time_zone TEXT;
ALTER TABLE z_reports ADD COLUMN IF NOT EXISTS zone_assumed BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE z_reports DROP CONSTRAINT IF EXISTS z_reports_tenant_id_store_id_business_date_key;
ALTER TABLE z_reports
    ADD CONSTRAINT z_reports_day_version_key UNIQUE (tenant_id, store_id, business_date, version);
