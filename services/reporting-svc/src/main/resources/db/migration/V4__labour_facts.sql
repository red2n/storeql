-- Labour against sales (store operations & workforce).
--
-- A shop's two biggest numbers are what it took and what its hours cost. This is the second, projected
-- from tenant-svc's clock: one row per time entry, so a correction can take the figure it replaced
-- back out rather than leave the day counted twice. Money is kept at the business currency's minor
-- units, as sales_facts keeps it.
--
-- What is NOT here is who earned it. The event carries the store, the day and the money and no person:
-- a labour figure is a fact about a shop's Saturday, and pay belongs to the service that keeps it. A
-- projection holding both would put pay data in a reporting database for ever, for a report that never
-- needed it.
CREATE TABLE labour_facts (
    tenant_id  UUID          NOT NULL,
    -- tenant-svc's time entry. The key, so the same event twice is one row (at-least-once delivery).
    entry_id   UUID          NOT NULL,
    store_id   UUID          NOT NULL,
    day        DATE          NOT NULL,
    minutes    INTEGER       NOT NULL,
    -- Null when no pay rate was in force on the day the hours were worked. Reported as unknown rather
    -- than as zero: zero is a real rate somebody may be on, and a report that showed an uncosted
    -- Saturday as free labour would be worse than one that said it did not know.
    cost       NUMERIC(18,4),
    currency   CHAR(3),
    recorded_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_labour_facts PRIMARY KEY (tenant_id, entry_id),
    CONSTRAINT ck_labour_minutes CHECK (minutes >= 0),
    CONSTRAINT ck_labour_currency CHECK ((cost IS NULL) = (currency IS NULL))
);

CREATE INDEX idx_labour_facts_day ON labour_facts (tenant_id, day, store_id);

COMMENT ON TABLE labour_facts IS
    'What a store''s hours cost, per time entry, projected from tenant-svc. No person: pay stays where it is kept.';
COMMENT ON COLUMN labour_facts.cost IS
    'Null when the day had no rate in force — unknown, not zero, because zero is a rate somebody may be on.';
