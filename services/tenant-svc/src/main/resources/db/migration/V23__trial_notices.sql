-- Self-serve signup and trials (21.13): what a business was told about its trial, once per stage.
--
-- A trial is announced twice at most — that it is about to end, and that it has ended with the
-- first invoice — and the billing run that says so may run any number of times a day. The unique
-- pair is what makes "once" true; the notice itself is the outbox row written in the same
-- transaction as this claim, so a run that dies halfway leaves both or neither.
CREATE TABLE trial_notices (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    subscription_id UUID        NOT NULL,
    stage           TEXT        NOT NULL,   -- ENDING | ENDED
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_trial_notices_stage CHECK (stage IN ('ENDING', 'ENDED')),
    CONSTRAINT uq_trial_notices UNIQUE (subscription_id, stage)
);
CREATE INDEX idx_trial_notices_tenant ON trial_notices (tenant_id, created_at);
