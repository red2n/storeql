-- Chargebacks and disputes (11.9). A cardholder asks their bank for the money back; the scheme
-- debits the acquirer, the acquirer debits the merchant — the amount and usually a fee — and the
-- merchant has until a date to answer with evidence. A dispute is told to this service by the
-- payment provider's webhook, or recorded by staff from the acquirer's letter when the card was
-- taken on a terminal the platform does not talk to (provider MANUAL).
--
-- A dispute is mutable (it moves NEEDS_RESPONSE -> UNDER_REVIEW -> WON | LOST | ACCEPTED); what
-- happened to it is not: dispute_events is append-only, like the tenders it is about.

CREATE TABLE IF NOT EXISTS disputes (
    id                   UUID          NOT NULL,
    tenant_id            UUID          NOT NULL,
    payment_id           UUID          NOT NULL,   -- the tender disputed
    order_id             UUID          NOT NULL,
    store_id             UUID,
    provider             VARCHAR(30)   NOT NULL,   -- STRIPE, RAZORPAY, or MANUAL (told by the acquirer, recorded by staff)
    provider_dispute_ref VARCHAR(255),             -- the provider's dispute id, or the acquirer's case number
    amount               NUMERIC(18,4) NOT NULL,   -- what the cardholder disputes
    fee_amount           NUMERIC(18,4) NOT NULL,   -- what the acquirer charges for the dispute itself
    -- The fee keeps the currency it was charged in. A scheme disputes a charge in the charge's own
    -- currency, but the acquirer charges the fee in its settlement currency, which may differ (a yen
    -- charge on a Stripe account paid out in pounds). Null reads as the dispute's own currency: a row
    -- from an export taken before this column existed carries none, and its fee was then in the
    -- dispute's (21.14).
    fee_currency         VARCHAR(3),
    currency             VARCHAR(3)    NOT NULL,
    reason               VARCHAR(40)   NOT NULL,
    network_reason_code  VARCHAR(20),              -- the scheme's own code: Visa 10.4, Mastercard 4837
    status               VARCHAR(24)   NOT NULL,
    funds_withdrawn      BOOLEAN       NOT NULL,   -- whether the acquirer has taken the money
    evidence_due_by      TIMESTAMPTZ,
    opened_at            TIMESTAMPTZ   NOT NULL,
    closed_at            TIMESTAMPTZ,
    idempotency_key      VARCHAR(255),
    created_by           UUID,                     -- null when a provider's webhook opened it
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_disputes_status CHECK (
        status IN ('NEEDS_RESPONSE', 'UNDER_REVIEW', 'WON', 'LOST', 'ACCEPTED')
    ),
    CONSTRAINT ck_disputes_reason CHECK (
        reason IN ('FRAUDULENT', 'PRODUCT_NOT_RECEIVED', 'PRODUCT_UNACCEPTABLE', 'DUPLICATE',
                   'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED', 'UNRECOGNIZED', 'GENERAL')
    ),
    CONSTRAINT ck_disputes_amounts CHECK (amount > 0 AND fee_amount >= 0),
    CONSTRAINT ck_disputes_fee_currency CHECK (fee_currency IS NULL OR fee_currency ~ '^[A-Z]{3}$'),
    -- Closed means a closing date, and only closed does.
    CONSTRAINT ck_disputes_closed CHECK (
        (status IN ('WON', 'LOST', 'ACCEPTED')) = (closed_at IS NOT NULL)
    )
);

-- The register, by what needs doing first.
CREATE INDEX IF NOT EXISTS idx_disputes_tenant_status
    ON disputes (tenant_id, status, evidence_due_by);
CREATE INDEX IF NOT EXISTS idx_disputes_tenant_opened
    ON disputes (tenant_id, opened_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_disputes_tenant_order
    ON disputes (tenant_id, order_id);

-- A tender has one dispute open at a time: the scheme reopens a case, it does not open a second.
CREATE UNIQUE INDEX IF NOT EXISTS uq_disputes_open_per_payment
    ON disputes (tenant_id, payment_id) WHERE status IN ('NEEDS_RESPONSE', 'UNDER_REVIEW');

-- A provider tells us about a dispute more than once; it is still one dispute.
CREATE UNIQUE INDEX IF NOT EXISTS uq_disputes_provider_ref
    ON disputes (provider, provider_dispute_ref) WHERE provider <> 'MANUAL' AND provider_dispute_ref IS NOT NULL;

-- A retried "record a chargeback" is the same chargeback (golden rule #11).
CREATE UNIQUE INDEX IF NOT EXISTS uq_disputes_idempotency
    ON disputes (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN disputes.fee_currency IS
    'The currency the acquirer charged the dispute fee in (its settlement currency); null reads as the dispute''s own.';

-- What happened to a dispute, in order. Append-only.
CREATE TABLE IF NOT EXISTS dispute_events (
    id         UUID         NOT NULL,
    tenant_id  UUID         NOT NULL,
    dispute_id UUID         NOT NULL,
    kind       VARCHAR(30)  NOT NULL,   -- OPENED, FUNDS_WITHDRAWN, EVIDENCE_SUBMITTED, ACCEPTED, WON, LOST, FUNDS_REINSTATED
    detail     TEXT,
    actor_id   UUID,                    -- null when the provider said so
    created_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_dispute_events_dispute
    ON dispute_events (tenant_id, dispute_id, created_at);

-- What the business answered with. One answer per dispute: a scheme takes evidence once.
CREATE TABLE IF NOT EXISTS dispute_evidence (
    tenant_id              UUID        NOT NULL,
    dispute_id             UUID        NOT NULL,
    product_description    TEXT,
    customer_name          TEXT,
    customer_email         TEXT,
    receipt_reference      TEXT,       -- the receipt or invoice number the customer was given
    fulfilment_proof       TEXT,       -- collected in store on a date, delivered to an address, signed for by whom
    customer_communication TEXT,
    refund_policy          TEXT,
    notes                  TEXT,
    submitted_by           UUID        NOT NULL,
    submitted_at           TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, dispute_id)
);
