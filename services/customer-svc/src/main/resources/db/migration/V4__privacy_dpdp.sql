-- India's Digital Personal Data Protection Act 2023 and the DPDP Rules 2025 (readiness review 13.12).
--
-- Consent, export and erasure were built to UK GDPR and PECR. India's regime, whose substantive
-- duties bind from 13 May 2027 (DPDP Rules 2025, notified 13 November 2025), asks for more of the
-- same shape, and none of it was modelled: a notice, itemised by purpose, in English or any of the
-- twenty-two languages of the Eighth Schedule on request (s.5, r.3); consent per purpose that is
-- free, specific, informed, unconditional and unambiguous, withdrawn as easily as it was given
-- (s.6); the business contact a person may put questions and grievances to, published (s.8(9), r.9);
-- every request a Data Principal makes — access, correction, erasure, nomination, grievance —
-- answered within a period the business publishes, ninety days at most (ss.11–14, r.14); a breach
-- intimated to each affected person without delay (r.7(1)); and a child, under eighteen, whose data
-- is processed only on a parent's verifiable consent and never tracked or targeted (s.9, r.10).
--
-- Every table here is tenant data. What a person was shown when they agreed is kept with the
-- consent, as the marketing consent log keeps it: consent to an unrecorded wording cannot be
-- demonstrated later. The log is append-only.

-- The business's own answers: who takes grievances, and how long it gives itself to answer.
CREATE TABLE privacy_settings (
    tenant_id          UUID        PRIMARY KEY,
    grievance_name     TEXT,
    grievance_email    TEXT,
    grievance_phone    TEXT,
    grievance_address  TEXT,
    -- The period the business publishes for answering a request (r.14(3)): at most ninety days.
    response_days      INTEGER     NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    updated_by         UUID,
    CONSTRAINT chk_privacy_response_days CHECK (response_days BETWEEN 1 AND 90)
);

-- The notice, per language, versioned: publishing writes a new version and never rewrites one,
-- because a consent names the version the person read.
CREATE TABLE privacy_notices (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    language     TEXT        NOT NULL,
    version      INTEGER     NOT NULL,
    title        TEXT        NOT NULL,
    body         TEXT        NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    published_by UUID,
    CONSTRAINT uq_privacy_notice_version UNIQUE (tenant_id, language, version),
    CONSTRAINT chk_privacy_notice_language CHECK (language ~ '^[a-z]{2,3}$')
);
CREATE INDEX idx_privacy_notices_current ON privacy_notices (tenant_id, language, version DESC);

-- What each person has agreed to, purpose by purpose, as it stands now.
CREATE TABLE purpose_consents (
    tenant_id      UUID        NOT NULL,
    customer_id    UUID        NOT NULL REFERENCES customers (id),
    purpose        TEXT        NOT NULL,
    granted        BOOLEAN     NOT NULL,
    -- The notice the person was shown: its language and version, null when none was published.
    notice_language TEXT,
    notice_version  INTEGER,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_purpose_consents PRIMARY KEY (tenant_id, customer_id, purpose),
    CONSTRAINT chk_purpose_consent_purpose CHECK (purpose IN ('LOYALTY', 'MARKETING', 'PERSONALISATION', 'ANALYTICS'))
);

-- The evidence: every grant and withdrawal, who did it, from where, against which notice.
CREATE TABLE purpose_consent_log (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    customer_id     UUID        NOT NULL,
    purpose         TEXT        NOT NULL,
    granted         BOOLEAN     NOT NULL,
    source          TEXT        NOT NULL,
    notice_language TEXT,
    notice_version  INTEGER,
    -- The staff member, when someone acted on the customer's behalf; null when the customer did it.
    actor_id        UUID,
    recorded_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_purpose_log_purpose CHECK (purpose IN ('LOYALTY', 'MARKETING', 'PERSONALISATION', 'ANALYTICS')),
    CONSTRAINT chk_purpose_log_source CHECK (source IN ('SIGNUP', 'PREFERENCE_CENTRE', 'STAFF', 'WITHDRAW_ALL', 'GUARDIAN'))
);
CREATE INDEX idx_purpose_consent_log_customer ON purpose_consent_log (tenant_id, customer_id, recorded_at DESC);

-- A parent's or guardian's consent for a child (s.9, r.10): who gave it, how the business verified
-- that they are the parent and of age, and by whom. Withdrawn, the child's consents fall with it.
CREATE TABLE guardian_consents (
    tenant_id        UUID        NOT NULL,
    customer_id      UUID        NOT NULL REFERENCES customers (id),
    guardian_name    TEXT        NOT NULL,
    -- How the parent's identity and age were verified: details the business already holds
    -- (r.10(1)(a)), a document seen over the counter, or a Digital Locker token (r.10(1)(b)).
    verification     TEXT        NOT NULL,
    -- What was seen or received, as the business chooses to note it: never a document number.
    reference        TEXT,
    given_at         TIMESTAMPTZ NOT NULL,
    recorded_by      UUID,
    withdrawn_at     TIMESTAMPTZ,
    withdrawn_by     UUID,
    CONSTRAINT pk_guardian_consents PRIMARY KEY (tenant_id, customer_id),
    CONSTRAINT chk_guardian_verification CHECK (verification IN ('DETAILS_HELD', 'DOCUMENT_SEEN', 'DIGITAL_LOCKER')),
    CONSTRAINT chk_guardian_withdrawn CHECK ((withdrawn_at IS NULL) = (withdrawn_by IS NULL))
);

-- What a person has asked for: the queue the business answers within its published period.
CREATE TABLE privacy_requests (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    customer_id  UUID        NOT NULL REFERENCES customers (id),
    kind         TEXT        NOT NULL,
    detail       TEXT,
    -- For a nomination: who may exercise the person's rights if they die or cannot (s.14).
    nominee_name    TEXT,
    nominee_contact TEXT,
    opened_at    TIMESTAMPTZ NOT NULL,
    -- The day the business's published period runs out, fixed when the request is opened.
    due_on       DATE        NOT NULL,
    status       TEXT        NOT NULL,
    resolution   TEXT,
    resolved_at  TIMESTAMPTZ,
    resolved_by  UUID,
    CONSTRAINT chk_privacy_request_kind CHECK (kind IN ('ACCESS', 'CORRECTION', 'ERASURE', 'NOMINATION', 'GRIEVANCE')),
    CONSTRAINT chk_privacy_request_status CHECK (status IN ('OPEN', 'RESOLVED', 'REFUSED')),
    CONSTRAINT chk_privacy_request_resolved CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    CONSTRAINT chk_privacy_request_nominee CHECK (kind <> 'NOMINATION' OR nominee_name IS NOT NULL)
);
CREATE INDEX idx_privacy_requests_queue ON privacy_requests (tenant_id, status, due_on, opened_at);
CREATE INDEX idx_privacy_requests_customer ON privacy_requests (tenant_id, customer_id, opened_at DESC);

-- A breach told to the business's customers (r.7(1)): what was sent, to how many, and when, so the
-- business can report the intimations it made (r.7(2)(b)(vi)).
CREATE TABLE breach_intimations (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    -- The platform's security notice this answers (21.15), when there is one.
    notice_id    UUID,
    subject      TEXT        NOT NULL,
    body         TEXT        NOT NULL,
    sent_at      TIMESTAMPTZ NOT NULL,
    sent_by      UUID,
    recipients   INTEGER     NOT NULL,
    failures     INTEGER     NOT NULL
);
CREATE INDEX idx_breach_intimations_tenant ON breach_intimations (tenant_id, sent_at DESC);
