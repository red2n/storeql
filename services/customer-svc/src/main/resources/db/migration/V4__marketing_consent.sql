-- Marketing consent, preferences and unsubscribe.
--
--   PECR reg.22 (retained)          an electronic marketing message needs prior consent, unless the
--                                   address was obtained in the course of a sale of a similar
--                                   product and an opt-out was offered then and in every message
--   UK GDPR art.7(1)                where consent is the basis, the controller must be able to
--                                   DEMONSTRATE that the person consented
--   PECR reg.23 / UK GDPR art.21(3) every message must carry a working way to opt out, and an
--                                   objection to direct marketing is absolute: it stops, at once
--
-- Art.7(1) is what shapes this. A boolean saying "subscribed" cannot be evidence of anything, so
-- there are two tables: what is true now, and an append-only record of every time it changed, with
-- who changed it, how, and what they were shown. The second one is the proof; the first is only a
-- fast read of it.

CREATE TABLE marketing_preferences (
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL REFERENCES customers (id),
    channel     TEXT        NOT NULL,
    granted     BOOLEAN     NOT NULL,
    -- Why this contact is lawful. SOFT_OPT_IN is PECR's existing-customer exception and is not the
    -- same as consent: it is narrower, it only covers similar products, and it is worth being able
    -- to tell apart afterwards.
    basis       TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_marketing_preferences PRIMARY KEY (tenant_id, customer_id, channel),
    CONSTRAINT chk_marketing_channel CHECK (channel IN ('EMAIL', 'SMS', 'PHONE', 'POST')),
    CONSTRAINT chk_marketing_basis CHECK (basis IN ('CONSENT', 'SOFT_OPT_IN', 'NONE')),
    -- An opt-out has no lawful basis to record: it is the absence of one.
    CONSTRAINT chk_marketing_basis_granted CHECK ((basis = 'NONE') = (granted = FALSE))
);
CREATE INDEX idx_marketing_preferences_customer
    ON marketing_preferences (tenant_id, customer_id);

-- Append-only: never UPDATE or DELETE (golden rule #8). This is the art.7(1) evidence.
CREATE TABLE marketing_consent_log (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL,
    channel     TEXT        NOT NULL,
    granted     BOOLEAN     NOT NULL,
    basis       TEXT        NOT NULL,
    -- How it was given or withdrawn: a tick at signup, the preference centre, a one-click
    -- unsubscribe, a member of staff acting on a phone call.
    source      TEXT        NOT NULL,
    -- What the person was actually shown when they agreed. Consent to an unrecorded wording cannot
    -- be demonstrated later, which is the whole point of this table.
    notice      TEXT,
    -- The staff member, when someone acted on the customer's behalf; null when the customer did it.
    actor_id    UUID,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_consent_log_channel CHECK (channel IN ('EMAIL', 'SMS', 'PHONE', 'POST')),
    -- PURPOSE_WITHDRAWN is the switch-off of each channel when the MARKETING purpose is withdrawn,
    -- written on the same transaction as that withdrawal. It is its own source, so the evidence says
    -- why the channel went off (the purpose) — never disguised as a preference-centre click, an
    -- unsubscribe link or a member of staff.
    CONSTRAINT chk_consent_log_source CHECK (source IN (
        'SIGNUP', 'CHECKOUT', 'PREFERENCE_CENTRE', 'STAFF', 'UNSUBSCRIBE_LINK', 'IMPORT',
        'PURPOSE_WITHDRAWN'))
);
CREATE INDEX idx_marketing_consent_log_customer
    ON marketing_consent_log (tenant_id, customer_id, recorded_at DESC);

-- The opt-out link a marketing message has to carry. A bearer capability, so only its hash is kept
-- — the same reasoning as iam-svc's refresh tokens: a leaked database must not hand out working
-- unsubscribe links for every customer, even though the harm is small.
CREATE TABLE marketing_unsubscribe_tokens (
    token_hash  TEXT        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL REFERENCES customers (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at     TIMESTAMPTZ
);
CREATE INDEX idx_marketing_unsub_customer
    ON marketing_unsubscribe_tokens (tenant_id, customer_id);
