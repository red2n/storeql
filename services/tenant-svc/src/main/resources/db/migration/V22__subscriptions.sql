-- Subscription billing and invoicing (21.9). The platform sells a plan (21.8); this is what turns
-- that into money owed and an invoice that says so.
--
-- It lives beside plans in tenant-svc because tenant-svc owns the customer relationship: the
-- business, its plan, and its standing. A separate billing service would have to read all three on
-- every cycle. If this grows a second product to sell, that is the moment to split it out.
--
-- Three rules run through the schema, and each is the industry-standard one:
--   * The price is LOCKED on the subscription when the business is sold the plan. A later price
--     change reaches new subscribers only. That is why plan_prices is append-only, and why the
--     amount is copied here rather than read back through the plan at billing time.
--   * Billing is IN ADVANCE, on the subscription's own anniversary, so every period has the same
--     shape as the next and proration is arithmetic rather than a special case.
--   * An invoice is numbered gaplessly and is NEVER edited. A mistake is a credit note.

-- ── who is selling ────────────────────────────────────────────────────────────
-- One row, the platform's own identity as it appears on an invoice. Without it nothing can be
-- billed: an invoice with no seller is not an invoice in any jurisdiction the platform trades in.
CREATE TABLE platform_billing_profile (
    id              SMALLINT     PRIMARY KEY,   -- always 1: a singleton the database enforces
    legal_name      TEXT         NOT NULL,
    address_line1   TEXT         NOT NULL,
    address_line2   TEXT,
    city            TEXT         NOT NULL,
    postcode        TEXT,
    country         TEXT         NOT NULL,      -- ISO 3166-1 alpha-2, where the platform is established
    vat_number      TEXT,                       -- prefixed with its country, as EN 16931 wants
    company_number  TEXT,
    invoice_prefix  TEXT         NOT NULL,      -- INV: what the number reads as
    payment_terms_days INTEGER   NOT NULL,      -- how long after issue an invoice falls due
    tax_rate        NUMERIC(6,4) NOT NULL,      -- the platform's own standard rate, for a domestic sale
    bank_details    TEXT,                       -- printed on the invoice for a transfer
    updated_by      UUID         NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_billing_profile_singleton CHECK (id = 1),
    CONSTRAINT ck_billing_profile_country CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_billing_profile_terms CHECK (payment_terms_days BETWEEN 0 AND 180),
    CONSTRAINT ck_billing_profile_rate CHECK (tax_rate >= 0 AND tax_rate < 1)
);

-- ── the rates the platform charges, by country ────────────────────────────────
-- Needed only for the destination case: a business in another EU state with no VAT number is
-- charged its own country's rate. A country with no row here is not guessed at — the invoice is
-- refused, because an invoice carrying the wrong VAT is worse than one that was not issued.
CREATE TABLE platform_vat_rates (
    country        TEXT         NOT NULL,
    rate           NUMERIC(6,4) NOT NULL,
    effective_from DATE         NOT NULL,
    note           TEXT,
    updated_by     UUID         NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (country, effective_from),
    CONSTRAINT ck_platform_vat_country CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_platform_vat_rate CHECK (rate >= 0 AND rate < 1)
);

-- ── what a business is signed up to ───────────────────────────────────────────
CREATE TABLE subscriptions (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenants (id),
    plan_id           UUID          NOT NULL REFERENCES plans (id),
    status            TEXT          NOT NULL,

    -- Locked at the sale. Read from plan_prices once, then never again: what the business agreed
    -- to pay does not change because the price list did.
    price_amount      NUMERIC(18,4) NOT NULL,
    currency          TEXT          NOT NULL,
    billing_interval  TEXT          NOT NULL,

    -- The period this subscription has been billed for, in advance.
    period_start      DATE          NOT NULL,
    period_end        DATE          NOT NULL,   -- exclusive: the next period starts here
    trial_end         DATE,                     -- null when it never had a trial

    -- A downgrade waits for the period the business has already paid for to finish.
    pending_plan_id   UUID          REFERENCES plans (id),
    cancel_at_period_end BOOLEAN    NOT NULL,

    -- The buyer's VAT number decides whether this is a reverse charge, so the check that it is real
    -- is evidence, not a detail: an audit asks when it was verified. Verifying it against VIES is a
    -- seam, like the e-invoicing networks; until there is one, a platform administrator records the
    -- check and this says who and when.
    buyer_vat_number  TEXT,
    vat_checked_at    TIMESTAMPTZ,
    vat_checked_by    UUID,
    vat_check_source  TEXT,                     -- VIES | MANUAL | SIMULATED

    -- Where the business is, for billing. Not the same question as where its shops are: a business
    -- with three shops in three countries is established in one, and that one decides the VAT and
    -- prints on the invoice. Seeded from tenants.country at sign-up and the business's to correct.
    -- Keeping it here rather than reading a store's address means the tax decision and the invoice's
    -- buyer block read one row, and a business that moves does not rewrite its old invoices.
    buyer_name        TEXT,                     -- the legal name it is invoiced as; tenants.legal_name when unset
    buyer_line1       TEXT,
    buyer_line2       TEXT,
    buyer_city        TEXT,
    buyer_postcode    TEXT,
    buyer_country     CHAR(2)       NOT NULL,

    billing_email     TEXT,                     -- where the invoice goes; the owner's when unset
    started_at        TIMESTAMPTZ   NOT NULL,
    cancelled_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_subscriptions_status CHECK (
        status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')
    ),
    CONSTRAINT ck_subscriptions_interval CHECK (billing_interval IN ('MONTH', 'YEAR')),
    CONSTRAINT ck_subscriptions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_subscriptions_buyer_country CHECK (buyer_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_subscriptions_amount CHECK (price_amount >= 0),
    CONSTRAINT ck_subscriptions_period CHECK (period_end > period_start),
    CONSTRAINT ck_subscriptions_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- A business has one subscription. Two would mean two answers to what it pays.
CREATE UNIQUE INDEX uq_subscriptions_tenant ON subscriptions (tenant_id);
-- What the billing run reads: everything due on or before today, oldest first.
CREATE INDEX idx_subscriptions_due ON subscriptions (period_end)
    WHERE status IN ('TRIALING', 'ACTIVE', 'PAST_DUE');

-- What happened to a subscription, in order. Append-only.
CREATE TABLE subscription_events (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    subscription_id UUID        NOT NULL,
    kind            TEXT        NOT NULL,   -- STARTED, TRIAL_ENDED, RENEWED, UPGRADED, DOWNGRADE_SCHEDULED, DOWNGRADED, CANCELLED, REACTIVATED, PAST_DUE, SUSPENDED
    detail          TEXT,
    actor_id        UUID,                   -- null when the billing run did it
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_subscription_events ON subscription_events (tenant_id, subscription_id, created_at);

-- ── the invoice number ────────────────────────────────────────────────────────
-- Gapless, per year. One row locked FOR UPDATE hands out the next: a sequence would leave holes on
-- a rolled-back transaction, and a hole in an invoice run is a question an auditor asks.
CREATE TABLE billing_invoice_numbers (
    year        INTEGER PRIMARY KEY,
    next_number BIGINT  NOT NULL,

    CONSTRAINT ck_invoice_numbers_next CHECK (next_number > 0)
);

-- ── the invoice ───────────────────────────────────────────────────────────────
CREATE TABLE billing_invoices (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants (id),
    subscription_id UUID          NOT NULL REFERENCES subscriptions (id),
    number          TEXT          NOT NULL,   -- INV-2026-000123
    status          TEXT          NOT NULL,
    -- PERIOD (the run raised it, one per period) or ADJUSTMENT (a proration, any number per period).
    -- A proration is not a period: it adjusts one, it can happen more than once in a period, and it must
    -- not compete with the period's own invoice for a slot. No DEFAULT: every INSERT names the kind, the
    -- way every INSERT names its id. A default would let a caller raise an adjustment that silently claims
    -- a period's slot.
    kind            TEXT          NOT NULL,

    issue_date      DATE          NOT NULL,
    due_date        DATE          NOT NULL,
    period_start    DATE          NOT NULL,
    period_end      DATE          NOT NULL,

    currency        TEXT          NOT NULL,
    net_amount      NUMERIC(18,4) NOT NULL,
    -- DOMESTIC        the buyer is where the platform is: the platform's own rate
    -- REVERSE_CHARGE  another EU state, VAT number validated: no VAT, and the invoice says so
    -- DESTINATION     another EU state, no VAT number: that country's rate (the OSS case)
    -- OUT_OF_SCOPE    outside the EU
    tax_treatment   TEXT          NOT NULL,
    tax_rate        NUMERIC(6,4)  NOT NULL,
    tax_amount      NUMERIC(18,4) NOT NULL,
    total_amount    NUMERIC(18,4) NOT NULL,
    amount_paid     NUMERIC(18,4) NOT NULL,

    -- Both sides as they stood the day it was issued. An invoice explains itself years later, when
    -- the business has moved and the platform has renamed itself.
    seller_snapshot TEXT          NOT NULL,
    buyer_snapshot  TEXT          NOT NULL,
    buyer_vat_number TEXT,

    -- How a suspended business pays: it cannot sign in, so the link carries its own proof. Only the
    -- hash is kept, the way a password is.
    pay_token_hash  TEXT,
    voided_reason   TEXT,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_invoices_status CHECK (status IN ('OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE')),
    CONSTRAINT ck_invoices_kind CHECK (kind IN ('PERIOD', 'ADJUSTMENT')),
    CONSTRAINT ck_invoices_treatment CHECK (
        tax_treatment IN ('DOMESTIC', 'REVERSE_CHARGE', 'DESTINATION', 'OUT_OF_SCOPE')
    ),
    CONSTRAINT ck_invoices_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_invoices_adds_up CHECK (total_amount = net_amount + tax_amount),
    CONSTRAINT ck_invoices_paid CHECK (amount_paid >= 0 AND amount_paid <= total_amount),
    CONSTRAINT ck_invoices_due CHECK (due_date >= issue_date),
    -- Only the two taxed treatments carry tax. Saying so in the schema stops a rate being applied
    -- to a reverse charge by accident — which would be VAT charged that should not have been.
    CONSTRAINT ck_invoices_tax_zero CHECK (
        tax_treatment IN ('DOMESTIC', 'DESTINATION') OR (tax_amount = 0 AND tax_rate = 0)
    )
);

CREATE UNIQUE INDEX uq_invoices_number ON billing_invoices (number);
-- A period is invoiced once. This is what makes the billing run safe to repeat. Only the invoices that
-- are a period hold the slot: an adjustment does not compete for it. A withdrawn (VOID) one frees its slot;
-- it keeps its number but it is no longer the period's invoice.
CREATE UNIQUE INDEX uq_invoices_period ON billing_invoices (subscription_id, period_start)
    WHERE status <> 'VOID' AND kind = 'PERIOD';
-- What an adjustment is read by: the period it adjusts, newest first.
CREATE INDEX idx_invoices_adjustments ON billing_invoices (subscription_id, period_start)
    WHERE kind = 'ADJUSTMENT';
CREATE INDEX idx_invoices_tenant ON billing_invoices (tenant_id, issue_date DESC, id);
-- What dunning reads: everything open and past its date.
CREATE INDEX idx_invoices_overdue ON billing_invoices (due_date) WHERE status = 'OPEN';

COMMENT ON COLUMN billing_invoices.kind IS
    'PERIOD (the run raised it, one per period) or ADJUSTMENT (a proration, any number per period).';

CREATE TABLE billing_invoice_lines (
    id          UUID          PRIMARY KEY,
    -- The business's own record under the Data Act, so it carries the tenant and is exported with
    -- the rest: a table with neither a tenant predicate nor a reference-data registration fails the
    -- export catalogue (TenantDataSpec). settlement_lines set the precedent.
    tenant_id   UUID          NOT NULL,
    invoice_id  UUID          NOT NULL REFERENCES billing_invoices (id),
    line_no     INTEGER       NOT NULL,
    kind        TEXT          NOT NULL,   -- PLAN | PRORATION | CREDIT | USAGE (a metered overage, billed in arrears: usage_periods)
    description TEXT          NOT NULL,
    quantity    NUMERIC(12,4) NOT NULL,
    unit_amount NUMERIC(18,4) NOT NULL,
    amount      NUMERIC(18,4) NOT NULL,

    CONSTRAINT ck_invoice_lines_kind CHECK (kind IN ('PLAN', 'PRORATION', 'CREDIT', 'USAGE')),
    CONSTRAINT ck_invoice_lines_amount CHECK (amount = round(quantity * unit_amount, 4))
);
CREATE UNIQUE INDEX uq_invoice_lines ON billing_invoice_lines (invoice_id, line_no);
CREATE INDEX idx_invoice_lines_tenant ON billing_invoice_lines (tenant_id, invoice_id);

-- ── what was paid ─────────────────────────────────────────────────────────────
-- Append-only, like every other record of money moving here.
CREATE TABLE billing_payments (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    invoice_id      UUID          NOT NULL REFERENCES billing_invoices (id),
    amount          NUMERIC(18,4) NOT NULL,
    currency        TEXT          NOT NULL,
    method          TEXT          NOT NULL,   -- BANK_TRANSFER | CARD
    provider        TEXT,                     -- who took it, when it was not a transfer
    provider_ref    TEXT,
    received_on     DATE          NOT NULL,
    recorded_by     UUID,                     -- null when a provider's webhook said so
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_billing_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_billing_payments_method CHECK (method IN ('BANK_TRANSFER', 'CARD'))
);
CREATE INDEX idx_billing_payments_invoice ON billing_payments (invoice_id, received_on);
-- A provider tells us about one payment more than once; it is still one payment.
CREATE UNIQUE INDEX uq_billing_payments_provider
    ON billing_payments (provider, provider_ref)
    WHERE provider IS NOT NULL AND provider_ref IS NOT NULL;
CREATE UNIQUE INDEX uq_billing_payments_idempotency
    ON billing_payments (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
