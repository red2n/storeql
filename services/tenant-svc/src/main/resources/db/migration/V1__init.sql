-- tenant-svc schema: the Tenant → Stores → Zones location model + staff (docs/onboarding-and-locations.md §3).
-- delivery_areas (pincode to fulfilling store) is created in V6__delivery_areas.sql.

-- Plans and packaging (21.8). The platform's price list: what a business can be sold, for how much
-- in which currency, and what it includes. These are the platform's tables, the same for every
-- business, so they carry no tenant_id. They come before tenants because tenants.plan_id refers to a
-- plan here; how a business came to be on its plan is tenant_plan_changes (V21__plans.sql).
--
-- A price is never edited: a new price takes effect from a date and the old one stays, because an
-- invoice raised last month must still be explicable next year (21.9 bills from this table).

CREATE TABLE plans (
    id               UUID        PRIMARY KEY,
    code             TEXT        NOT NULL,   -- STARTER, GROWTH: what a person and an API call say
    name             TEXT        NOT NULL,
    description      TEXT,
    status           TEXT        NOT NULL,   -- DRAFT: being written; ACTIVE: sold; RETIRED: kept by who has it, sold to nobody
    billing_interval TEXT        NOT NULL,   -- MONTH | YEAR
    trial_days       INTEGER     NOT NULL,
    is_default       BOOLEAN     NOT NULL,   -- the plan a new business starts on
    is_public        BOOLEAN     NOT NULL,   -- shown on the public price list
    sort_order       INTEGER     NOT NULL,
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_plans_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_plans_interval CHECK (billing_interval IN ('MONTH', 'YEAR')),
    CONSTRAINT ck_plans_trial CHECK (trial_days BETWEEN 0 AND 365),
    -- Nobody starts on a plan that is not sold.
    CONSTRAINT ck_plans_default_is_sold CHECK (NOT is_default OR status = 'ACTIVE')
);

CREATE UNIQUE INDEX uq_plans_code ON plans (upper(code));
-- One default at most.
CREATE UNIQUE INDEX uq_plans_default ON plans (is_default) WHERE is_default;
CREATE INDEX idx_plans_listing ON plans (status, sort_order, code);

CREATE TABLE plan_prices (
    id             UUID          PRIMARY KEY,
    plan_id        UUID          NOT NULL REFERENCES plans (id),
    currency       TEXT          NOT NULL,   -- ISO 4217: a plan is priced in each currency it is sold in
    amount         NUMERIC(18,4) NOT NULL,   -- per billing interval, before tax
    effective_from DATE          NOT NULL,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_plan_prices_amount CHECK (amount >= 0),
    CONSTRAINT ck_plan_prices_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX uq_plan_prices_day ON plan_prices (plan_id, currency, effective_from);

-- What a plan includes. A limit with no value is unlimited; a feature is included or it is not.
-- The keys are a catalogue kept in code (Plans.CATALOGUE): a key nobody enforces would be a promise
-- nobody keeps.
CREATE TABLE plan_entitlements (
    plan_id     UUID    NOT NULL REFERENCES plans (id),
    key         TEXT    NOT NULL,
    limit_value BIGINT,
    enabled     BOOLEAN,
    PRIMARY KEY (plan_id, key),

    CONSTRAINT ck_plan_entitlements_limit CHECK (limit_value IS NULL OR limit_value >= 0),
    -- A row is a limit or a feature, never both.
    CONSTRAINT ck_plan_entitlements_kind CHECK (limit_value IS NULL OR enabled IS NULL)
);

-- The plan sandboxes sit on, which the platform keeps for sandboxes alone: sold (ACTIVE, so it can
-- be held), on the public price list to nobody, never the default, with no price. Its allowances
-- are small on purpose, and the platform may change them like any plan's. A live business is never
-- put on it (PLAN_SANDBOX_ONLY). Fixed ids, so every deployment has the same plan.
INSERT INTO plans (id, code, name, description, status, billing_interval, trial_days, is_default,
                   is_public, sort_order, created_by, created_at, updated_at)
VALUES ('019965a0-0000-7000-8000-000000000001', 'SANDBOX', 'Sandbox',
        'The plan a business''s sandbox sits on: enough to try every integration, and never billed.',
        'ACTIVE', 'MONTH', 0, false, false, 1000,
        '019965a0-0000-7000-8000-000000000002', now(), now());

INSERT INTO plan_entitlements (plan_id, key, limit_value, enabled) VALUES
    ('019965a0-0000-7000-8000-000000000001', 'stores.max',          2,    NULL),
    ('019965a0-0000-7000-8000-000000000001', 'staff.max',           5,    NULL),
    ('019965a0-0000-7000-8000-000000000001', 'products.max',        200,  NULL),
    ('019965a0-0000-7000-8000-000000000001', 'requests.per-minute', 300,  NULL),
    ('019965a0-0000-7000-8000-000000000001', 'images.mb.max',       50,   NULL),
    ('019965a0-0000-7000-8000-000000000001', 'documents.mb.max',    20,   NULL),
    ('019965a0-0000-7000-8000-000000000001', 'feature.storefront',  NULL, true);

CREATE TABLE tenants (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    legal_name TEXT,
    status     TEXT NOT NULL DEFAULT 'PENDING',      -- PENDING | ACTIVE | INACTIVE
    -- The plan the business is on. Its foreign key to plans is declared with the constraints below.
    plan_id    UUID,
    owner_user_id UUID,                               -- the iam-svc user who created the tenant
    country    TEXT NOT NULL,                         -- ISO-3166 alpha-2
    currency   TEXT NOT NULL,                         -- ISO-4217
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),    -- audit and cache invalidation on mutable entities

    -- The business's own e-invoicing identity. An e-invoice names its buyer by VAT identifier (BT-48) and
    -- electronic address (BT-49), and its seller the same way (BT-31, BT-34). purchase-svc reads the buyer's
    -- to know an invoice it received is addressed to this business; order-svc writes the seller's on every
    -- invoice it issues, and Peppol refuses an invoice without one (PEPPOL-EN16931-R020).
    vat_number      TEXT,   -- prefixed with the issuing country, as EN 16931 requires (BR-CO-09)
    einvoice_scheme TEXT,   -- EAS code of the Peppol participant identifier
    einvoice_id     TEXT,   -- the identifier within that scheme

    -- Why a business is switched off, and by whom. Without this, "pay your bill and the platform comes
    -- back" cannot tell a business the platform suspended from one an administrator suspended, and a
    -- payment would lift both. Only NON_PAYMENT is ever lifted by money; everything else stays exactly as
    -- it is, and the payment is still recorded.
    deactivated_reason TEXT,        -- NON_PAYMENT (dunning, lifted by paying up) | ADMINISTRATOR (never lifted by a payment) | SANDBOX_DELETED (its owner removed the sandbox it was)
    deactivated_by     UUID,
    deactivated_at     TIMESTAMPTZ,
    -- What the administrator said, in their own words, kept with who and when, so a suspension a business
    -- can appeal is explainable.
    deactivated_note   TEXT,

    -- A sandbox (22.8) is a second tenant of its own, marked as such and pointing at the live business it
    -- stands in for, where an integrator can create products, book stock, place orders and receive webhooks
    -- against nothing real: no message leaves it, no money moves, and nothing in it is billed. One active
    -- sandbox per business at a time; removed, it is switched off with the reason SANDBOX_DELETED and every
    -- service erases what it held of it, and another can be made. The live business's owner owns the sandbox
    -- too, which is how the owner's token is traded for one that names the sandbox (iam-svc, 22.8) — and why
    -- a login's "own business" is always the live one.
    mode       TEXT NOT NULL DEFAULT 'LIVE',
    sandbox_of UUID REFERENCES tenants (id),

    CONSTRAINT fk_tenants_plan FOREIGN KEY (plan_id) REFERENCES plans (id),
    CONSTRAINT ck_tenant_deactivated_reason CHECK (
        deactivated_reason IS NULL OR deactivated_reason IN ('NON_PAYMENT', 'ADMINISTRATOR', 'SANDBOX_DELETED')
    ),
    -- A business that is off has a reason; one that is on has none. Stated here so the pair cannot drift:
    -- a reason left behind on a reactivated business would make the next payment lift a suspension nobody
    -- asked it to lift.
    CONSTRAINT ck_tenant_deactivated_pair CHECK ((status = 'INACTIVE') OR (deactivated_reason IS NULL)),
    -- A business that is on has no note, the same pair rule as the reason.
    CONSTRAINT ck_tenant_deactivated_note CHECK ((status = 'INACTIVE') OR (deactivated_note IS NULL)),
    CONSTRAINT ck_tenants_mode CHECK (mode IN ('LIVE', 'SANDBOX')),
    -- A sandbox always says what it is a sandbox of; a live business never does.
    CONSTRAINT ck_tenants_sandbox_of CHECK ((mode = 'SANDBOX') = (sandbox_of IS NOT NULL))
);
-- The e-invoicing address and VAT number are read across businesses, platform-wide, before anything is
-- read into one business's inbox: an access point delivers to the participant identifier the document
-- names as its buyer (BT-49), and a document with no address names its buyer by VAT identifier (BT-48).
CREATE INDEX idx_tenants_einvoice_address
    ON tenants (einvoice_scheme, lower(einvoice_id))
    WHERE einvoice_id IS NOT NULL;
CREATE INDEX idx_tenants_vat_number
    ON tenants (upper(replace(vat_number, ' ', '')))
    WHERE vat_number IS NOT NULL;
CREATE INDEX idx_tenants_plan ON tenants (plan_id) WHERE plan_id IS NOT NULL;
-- One sandbox at a time: a second is refused at the row, whatever two requests race to.
CREATE UNIQUE INDEX uq_tenants_active_sandbox
    ON tenants (sandbox_of) WHERE mode = 'SANDBOX' AND status = 'ACTIVE';
CREATE INDEX idx_tenants_sandbox_of ON tenants (sandbox_of) WHERE sandbox_of IS NOT NULL;

COMMENT ON COLUMN tenants.deactivated_reason IS
    'NON_PAYMENT (dunning, lifted by paying up), ADMINISTRATOR (never lifted by a payment) or SANDBOX_DELETED (its owner removed the sandbox it was).';
COMMENT ON COLUMN tenants.mode IS
    'LIVE, or SANDBOX for a business''s test double (22.8): every service that reads the profile treats a sandbox as unreal — no message leaves it, no money moves.';
COMMENT ON COLUMN tenants.sandbox_of IS
    'For a SANDBOX, the live business it stands in for; NULL for a live business.';

CREATE TABLE stores (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    name           TEXT NOT NULL,
    code           TEXT NOT NULL,                     -- unique per tenant
    type           TEXT NOT NULL DEFAULT 'STORE',     -- STORE | WAREHOUSE | DARK_STORE
    line1          TEXT, line2 TEXT, city TEXT, state TEXT,
    country        TEXT, pincode TEXT,
    geo_lat        NUMERIC(9,6), geo_lng NUMERIC(9,6),
    -- The store's own IANA time zone. Required and never defaulted: no country has one right answer (the
    -- United States, Australia and Brazil each span several zones), so the zone is supplied when a store is
    -- created and kept when an update leaves it out. A London store is never quietly given UTC's clock.
    timezone       TEXT NOT NULL,
    business_hours TEXT,                              -- JSON string
    status         TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | SUSPENDED | CLOSED
    is_default     BOOLEAN NOT NULL DEFAULT false,
    -- Per-store storefront pricing display. When false, the online storefront for this store hides product
    -- prices and shows stock availability instead; ordering still works.
    show_prices    BOOLEAN NOT NULL DEFAULT true,
    -- Per-store tender configuration, owner/admin-controlled. CSV of enabled methods, subset of CASH, CARD,
    -- UPI, WALLET. Storefront checkout and POS tender screens offer only these; payment-svc rejects captures
    -- with a disabled method.
    enabled_payment_methods TEXT NOT NULL DEFAULT 'CASH,CARD',
    -- A phone at the till (intent/phone-at-the-till.md): whether a store's till asks for the customer's
    -- phone. OPTIONAL is the default for every store: the till asks and the cashier may
    -- leave it blank as the customer prefers. REQUIRED refuses a till sale with neither a number nor a
    -- customer; OFF never asks. order-svc reads it through TenantProfiles.
    till_phone     TEXT NOT NULL DEFAULT 'OPTIONAL',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_stores_till_phone CHECK (till_phone IN ('REQUIRED', 'OPTIONAL', 'OFF'))
);
CREATE INDEX idx_stores_tenant ON stores (tenant_id, status);

CREATE TABLE zones (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    store_id   UUID NOT NULL REFERENCES stores(id),
    name       TEXT NOT NULL,
    code       TEXT NOT NULL,                         -- unique per store
    type       TEXT NOT NULL DEFAULT 'AISLE',         -- AISLE|RACK|SHELF|COLD_ROOM|BACK_STORE|RECEIVING|DISPLAY|DEFAULT
    -- ACTIVE | OUT_OF_SERVICE | RETIRED: a checked vocabulary, not free text.
    status     TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, code),
    CONSTRAINT ck_zone_status CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE', 'RETIRED'))
);
CREATE INDEX idx_zones_tenant_store ON zones (tenant_id, store_id, status);

CREATE TABLE staff_assignments (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    user_id    UUID NOT NULL,                         -- from iam-svc
    -- NULL: a business-wide assignment, for a MANAGER-tier person held to no store (head office). Only an
    -- owner grants or removes it, and the service allows it for the MANAGER tier alone.
    store_id   UUID REFERENCES stores(id),
    -- The role the assignment names: a built-in tier (OWNER | MANAGER | STOREKEEPER | CASHIER) or the
    -- business's own custom code (tenant_roles, V8).
    role       TEXT NOT NULL,
    -- The tier that role stands on, which is what iam-svc binds.
    base_tier  TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id, store_id, role)
);
CREATE INDEX idx_staff_tenant ON staff_assignments (tenant_id);
CREATE INDEX idx_staff_role ON staff_assignments (tenant_id, role);
-- (tenant, user, store, role) is unique for stores; NULLs never collide there, so the business-wide row
-- needs its own: one per person and role.
CREATE UNIQUE INDEX uq_staff_business_wide
    ON staff_assignments (tenant_id, user_id, role) WHERE store_id IS NULL;

-- Transactional outbox.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    topic        TEXT NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Retry and dead-letter state. A row that fails to publish is retried after a backoff
    -- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds), and
    -- only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead letter: never
    -- claimed again, kept for an operator, and it holds back its own aggregate only.
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,

    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
-- The scheduled purge of delivered outbox rows (common-service OutboxPublisher, through
-- BaseOutboxRepository.purgePublished) deletes in batches of the oldest ones:
--   WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at LIMIT n
-- With no index each batch reads the whole table to find them. The index is partial: it holds only
-- delivered rows, so it stays small and the drain (idx_outbox_unpublished) is untouched.
-- tenant-svc keeps no processed_events table (its consumers dedupe on unique keys of their own,
-- such as usage_records' source_ref), so the purge of those has nothing to scan and needs no index.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
-- The claim: rows that may publish now, in the order they were written.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;
-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
