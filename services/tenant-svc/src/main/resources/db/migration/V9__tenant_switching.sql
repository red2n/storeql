-- Switching and erasure (readiness review 21.14).
--
--   EU Data Act (EU) 2023/2854 art.25(2)(a)  a notice of at most two months to start switching, then a
--                                            transitional period of at most 30 calendar days, which
--                                            the customer may extend once (art.25(4))
--   art.25(2)(g)  a data retrieval period of at least 30 calendar days after the transitional period
--   art.25(2)(h)  complete erasure of the exportable data and digital assets after it
--   art.25(3)     the contract ends when switching completes, or at the end of the notice period
--                 when the customer asks only for its data to be erased
--
-- A business gives notice once at a time. What it chose and each step after is kept: the notice and
-- its dates, an extension and a withdrawal on the same row with who made them, and what every
-- service erased. Neither table is erased with the business's other data, and neither is loaded by an
-- import: the platform keeps the record that a business left and what was erased, and a notice is the
-- leaving business's own, never the importing one's.
CREATE TABLE tenant_switches (
    id                 UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenants (id),
    -- SWITCH: take the data to another provider or its own systems; ERASE: have it erased.
    intent             TEXT        NOT NULL CHECK (intent IN ('SWITCH', 'ERASE')),
    notice_given_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    notice_given_by    UUID        NOT NULL,
    notice_ends_on     DATE        NOT NULL,
    transition_ends_on DATE        NOT NULL,
    extended_at        TIMESTAMPTZ,
    extended_by        UUID,
    retrieval_ends_on  DATE        NOT NULL,
    -- The first day the data may be erased: the day after retrieval ends, or the notice's end for ERASE.
    erasure_due_on     DATE        NOT NULL,
    cancelled_at       TIMESTAMPTZ,
    cancelled_by       UUID,
    cancel_reason      TEXT        CHECK (cancel_reason IS NULL OR char_length(cancel_reason) BETWEEN 1 AND 500),
    erasure_event_id   UUID        UNIQUE,
    erasure_started_at TIMESTAMPTZ,
    CONSTRAINT chk_switch_dates CHECK (
        notice_ends_on <= transition_ends_on AND transition_ends_on <= retrieval_ends_on
        AND retrieval_ends_on <= erasure_due_on),
    CONSTRAINT chk_switch_extended CHECK ((extended_at IS NULL) = (extended_by IS NULL)),
    CONSTRAINT chk_switch_cancelled CHECK (
        (cancelled_at IS NULL) = (cancelled_by IS NULL) AND (cancelled_at IS NULL) = (cancel_reason IS NULL)),
    CONSTRAINT chk_switch_erasure CHECK ((erasure_event_id IS NULL) = (erasure_started_at IS NULL)),
    CONSTRAINT chk_switch_cancelled_or_erased CHECK (cancelled_at IS NULL OR erasure_started_at IS NULL)
);
-- One notice standing per business: a second is refused until the first is withdrawn.
CREATE UNIQUE INDEX uq_tenant_switch_standing ON tenant_switches (tenant_id) WHERE cancelled_at IS NULL;
CREATE INDEX idx_tenant_switches_due ON tenant_switches (erasure_due_on)
    WHERE cancelled_at IS NULL AND erasure_started_at IS NULL;

-- What each service erased, as it announced (TenantDataErased), once per announcement.
CREATE TABLE tenant_erasure_evidence (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    switch_id        UUID        NOT NULL REFERENCES tenant_switches (id),
    erasure_event_id UUID        NOT NULL,
    service          TEXT        NOT NULL CHECK (char_length(service) BETWEEN 1 AND 60),
    rows_erased      INTEGER     NOT NULL CHECK (rows_erased >= 0),
    tables           JSONB       NOT NULL,
    erased_at        TIMESTAMPTZ NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_erasure_evidence ON tenant_erasure_evidence (tenant_id, switch_id, service);
