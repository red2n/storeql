-- Phones in E.164, read in the business's own country: no country, prefix or zone is
-- ever named in code — every parse tries the business's own home country (TenantProfiles' profile
-- country) and then each of its stores' (TenantProfiles.Stores.countries), whichever the platform
-- happens to hold for that business.
--
-- phone_e164 is filled on every write of customers.phone (create, update, claim, import, checkout);
-- null when the number was typed with no reachable region, or none of the business's regions parse
-- it to a valid number — the phone is always kept as typed either way.
--
-- phone_e164_checked_at is the backfill's own bookkeeping, not part of the API: the last time this
-- row was normalised against regions that were actually readable, whatever it found. A row is
-- revisited by the start-up backfill only while this stays null, so a genuinely unparseable number
-- is tried once and left alone, while a business whose countries could not be read at the time (a
-- down tenant-svc) keeps its rows eligible for a later start, once tenant-svc answers again.
ALTER TABLE customers ADD COLUMN phone_e164 TEXT;
ALTER TABLE customers ADD COLUMN phone_e164_checked_at TIMESTAMPTZ;

CREATE INDEX idx_customers_phone_e164 ON customers (tenant_id, phone_e164);
