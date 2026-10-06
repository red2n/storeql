-- payment_intents: the record of asking a payment service provider to take money.
--
-- A payment_tenders row is written only for money that was taken: by a provider when an intent
-- captures, or recorded at the till (cash, store credit, a card machine's approval). An intent is
-- the asking, so it has a record of its own from the start, and an intent that never captures
-- leaves no tender behind. PRD §3.4 names it among payment-svc's own tables ("payments, refunds,
-- payment_intents").
--
-- Why a separate table rather than more columns on payment_tenders: a tender is an append-only
-- statement that money WAS taken (golden rule #8), while an intent is mutable by nature — it moves
-- REQUIRES_ACTION → AUTHORIZED → CAPTURED as the customer completes SCA and the provider confirms.
-- Those are different lifecycles and conflating them would make the append-only guarantee a lie.
-- On capture an intent writes exactly one tender and points at it via payment_id.

CREATE TABLE IF NOT EXISTS payment_intents (
    id               UUID          NOT NULL,
    tenant_id        UUID          NOT NULL,
    order_id         UUID          NOT NULL,
    -- Denormalised from the order, exactly as payment_tenders.store_id is: this endpoint has
    -- no staff role to trust, so the store must come from the order rather than the request.
    store_id         UUID,

    -- Which provider holds the money. PRD §11 assumes the v1 set is Razorpay (India) + Stripe, to
    -- be confirmed; MANUAL is the no-provider mode for local dev and for tenants who only ever take
    -- cash at the till.
    provider         VARCHAR(30)   NOT NULL,
    -- The provider's own id for this intent (Stripe `pi_…`, Razorpay `order_…`). Null until the
    -- provider call returns one; an intent whose call fails keeps it null, as markTerminal never
    -- writes it. See the unique index.
    provider_ref     VARCHAR(255),

    -- Money is NUMERIC and the currency is stored explicitly (golden rule #13). amount is what was
    -- authorised; captured_amount is what has actually been taken, which is the whole point of the
    -- auth/capture split and cannot be derived from amount alone.
    amount           NUMERIC(18,4) NOT NULL,
    captured_amount  NUMERIC(18,4) NOT NULL DEFAULT 0,
    currency         VARCHAR(3)    NOT NULL,

    status           VARCHAR(24)   NOT NULL,
    -- Where to send the customer to complete SCA / 3-D Secure. Transient and provider-supplied;
    -- stored so a resumed checkout can be handed the same URL rather than starting a new intent.
    next_action_url  TEXT,

    failure_code     VARCHAR(64),
    failure_message  TEXT,

    -- The tender written when this intent captured. Null until then, and the only link between the
    -- mutable intent and the append-only tender.
    payment_id       UUID,

    idempotency_key  VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_payment_intents_status CHECK (
        status IN ('REQUIRES_ACTION', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED')
    ),
    -- An intent can never have captured more than it authorised, and neither figure can go
    -- negative. A provider webhook reporting otherwise is a bug worth failing loudly on rather
    -- than silently recording.
    CONSTRAINT ck_payment_intents_amounts CHECK (
        amount >= 0 AND captured_amount >= 0 AND captured_amount <= amount
    ),
    -- CAPTURED is the only status that may name a tender, and it must name one.
    CONSTRAINT ck_payment_intents_captured_has_tender CHECK (
        (status = 'CAPTURED') = (payment_id IS NOT NULL)
    )
);

-- Order-scoped lookup: "is this order already being paid for?" on every checkout attempt.
CREATE INDEX IF NOT EXISTS idx_payment_intents_order
    ON payment_intents (tenant_id, order_id);

-- A provider reference maps to exactly one intent. This is what makes webhook handling safe: a
-- redelivered `payment_intent.succeeded` resolves to the same row rather than creating a second.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_intents_provider_ref
    ON payment_intents (tenant_id, provider, provider_ref)
    WHERE provider_ref IS NOT NULL;

-- Same idempotency contract as payment_tenders and refund_tenders (golden rule #11): a retried
-- checkout with the same Idempotency-Key returns the original intent instead of starting a second
-- authorisation against the customer's card.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_intents_idempotency
    ON payment_intents (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Intents left open mid-SCA, the customer having closed the tab while the funds are still held.
-- No sweep reads this yet; it is the lookup such a sweep would use.
CREATE INDEX IF NOT EXISTS idx_payment_intents_stale
    ON payment_intents (status, created_at)
    WHERE status IN ('REQUIRES_ACTION', 'AUTHORIZED');


-- Webhook dedupe. Infrastructure, in the same sense as outbox and processed_events, and carries no
-- tenant_id for the same reason: it is keyed by what the provider sent, and the tenant is whatever
-- the referenced intent says it is.
--
-- processed_events cannot serve here — it keys on a UUID event_id, and provider event ids are
-- opaque strings (`evt_1A2b…`). A webhook that is delivered twice, which every provider promises
-- will happen, must capture money once (golden rule #7).
CREATE TABLE IF NOT EXISTS payment_webhook_events (
    provider          VARCHAR(30)  NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, provider_event_id)
);
