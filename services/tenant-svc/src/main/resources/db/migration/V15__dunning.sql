-- 21.12 Dunning and suspend-for-non-payment.
--
-- An overdue invoice is chased on a schedule, then the platform is taken away, then the debt is given
-- up on. Each step happens once however many times the run is run: UNIQUE (invoice_id, step) is what
-- says so, not a check somebody remembered to write.
--
-- The decision that shapes this file: paying up must reactivate a business the platform suspended for
-- non-payment, and must never reactivate one an administrator switched off. tenants.status records
-- ACTIVE or INACTIVE; tenants.deactivated_reason (V1) says why a business is INACTIVE, so a payment can
-- tell the two apart and never overrule a decision somebody took.

-- What the platform's tolerance is. A singleton, like platform_billing_profile: one row, id = 1, and
-- the database enforces it.
--
-- The defaults are from published dunning practice rather than invented: reminders on days 1, 3, 5
-- and 7 after the due date (three or four attempts over ten to fourteen days recovers most of what is
-- recoverable), service interrupted at fourteen, the debt given up on at thirty. Configurable,
-- because a platform's own tolerance is a commercial decision and not a technical one.
CREATE TABLE dunning_policy (
    id                       SMALLINT    PRIMARY KEY,
    enabled                  BOOLEAN     NOT NULL,
    -- Days after the due date, ascending, as a comma-separated list: 1,3,5,7. A list rather than
    -- columns because a platform may want three reminders or five, and adding a column for each would
    -- make the policy a migration instead of a setting.
    reminder_days            TEXT        NOT NULL,
    suspend_after_days       INTEGER     NOT NULL,
    uncollectible_after_days INTEGER     NOT NULL,
    updated_by               UUID        NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_dunning_singleton CHECK (id = 1),
    CONSTRAINT chk_dunning_reminders CHECK (reminder_days ~ '^[0-9]+(,[0-9]+)*$'),
    -- Suspending before the last reminder has been sent would take the platform away from a business
    -- that has not yet been told it is late, and giving up before suspending would write off a debt
    -- the platform never stopped serving. The order is part of the policy, so the database holds it.
    CONSTRAINT chk_dunning_order CHECK (uncollectible_after_days > suspend_after_days),
    CONSTRAINT chk_dunning_suspend CHECK (suspend_after_days BETWEEN 1 AND 365),
    CONSTRAINT chk_dunning_write_off CHECK (uncollectible_after_days BETWEEN 2 AND 730)
);

-- Deliberately not seeded. A seed row would need an updated_by, which is the id of the person who set
-- the policy, and a migration has no such person: any id put there would name an actor who never acted
-- (and a column DEFAULT that fills in a uuid is refused by the integration-test audit). So the
-- defaults live in code (Dunning.DEFAULT_POLICY) and an absent row means "the defaults", the same way
-- Entitlements treats a business on no plan as unrestricted. updated_by is then only ever written
-- when a person actually set the policy (DunningRepository.savePolicy, from the acting person's id),
-- which is the only time the question "who?" has an answer.

-- What has been done about one overdue invoice, append-only. The unique index is the idempotency:
-- a run that runs twice, or two replicas running at the same instant, chase once.
CREATE TABLE dunning_events (
    id         UUID        PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    invoice_id UUID        NOT NULL REFERENCES billing_invoices (id),
    -- REMINDER_1 … REMINDER_n, SUSPENDED, UNCOLLECTIBLE, DUE_DATE_EXTENDED, RESOLVED
    step       TEXT        NOT NULL,
    detail     TEXT,
    -- Null when the run did it; the administrator when a human did.
    actor_id   UUID,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_dunning_step CHECK (step ~ '^[A-Z][A-Z0-9_]*$')
);

-- One step per invoice, ever. A reminder that has been sent is not sent again because the run ran
-- again; an extension is the exception, and its detail carries the day it was extended to.
CREATE UNIQUE INDEX uq_dunning_step ON dunning_events (invoice_id, step)
    WHERE step <> 'DUE_DATE_EXTENDED';
CREATE INDEX idx_dunning_tenant ON dunning_events (tenant_id, created_at DESC);
CREATE INDEX idx_dunning_invoice ON dunning_events (invoice_id, created_at);
