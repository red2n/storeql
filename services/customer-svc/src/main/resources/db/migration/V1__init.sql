-- customer-svc schema: customer profiles, addresses, loyalty, store credit, transactional outbox.
-- Multi-tenancy (CLAUDE.md, the two ideas): every table of tenant data here carries tenant_id NOT NULL
-- and leads its composite indexes with it, except where a comment at the index says otherwise.
-- Golden rule #8: loyalty_ledger and store_credit_ledger are append-only — no UPDATE/DELETE.

CREATE TABLE customers (
    id                    UUID        PRIMARY KEY,
    tenant_id             UUID        NOT NULL,
    email                 TEXT        NOT NULL,
    phone                 TEXT,                          -- as typed, always kept
    -- The phone in E.164, read in the business's own country: no country, prefix or zone is ever
    -- named in code — every parse tries the business's own home country (TenantProfiles' profile
    -- country) and then each of its stores' (TenantProfiles.Stores.countries). Set on every write of
    -- phone by this service (registration and a profile update), and by the start-up backfill for rows
    -- written before it; null when no phone is held, when the number was typed with no reachable
    -- region, or when none of the business's regions parses it to a valid number.
    phone_e164            TEXT,
    -- Bookkeeping of the start-up phone backfill, not part of the API: the last time this row was
    -- normalised against regions that were actually readable, whatever it found. A row stays
    -- eligible while this is null, so a genuinely unparseable number is tried once and left alone,
    -- while a business whose countries could not be read at the time (a down tenant-svc) keeps its
    -- rows eligible for a later start.
    phone_e164_checked_at TIMESTAMPTZ,
    -- Nullable: a person who signs in and buys has given a shop their email, not their name. A
    -- linked login often has none until the shopper fills one in, and an empty string pretending to
    -- be a name is worse than an absent one.
    first_name            TEXT,
    last_name             TEXT,
    dob                   DATE,
    gender                TEXT,                          -- M | F | OTHER | PREFER_NOT
    status                TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED | ANONYMIZED
    -- A legacy one-bit mirror of marketing consent, kept beside marketing_preferences, which is the
    -- consent of record with its evidence. Set when the signup or profile form last gave marketing
    -- consent, and cleared on erasure. A channel withdrawal does not clear it.
    gdpr_consent_at       TIMESTAMPTZ,                   -- when the signup or profile form last gave it
    anonymized_at         TIMESTAMPTZ,                   -- set on GDPR erasure; email/phone zeroed
    -- The shopper's login (a global account across every storefront) and this tenant's customer
    -- record for them: the join is recorded on the side that is tenant-scoped, at most one customer
    -- per login per tenant. Null stays ordinary: a POS walk-in created at the till has no login until
    -- its shopper signs in with the same email, and that login then adopts the till's record rather
    -- than making a second one (CustomerRepository.linkLogin). An anonymized record is never adopted,
    -- and a record once linked is never re-linked to another login.
    login_id              UUID,
    -- The language a customer reads their messages in (ISO 639, lower case). Null until they say,
    -- when their shop's own language is used. Not personal data in itself, and not erased with the
    -- rest of the record: a language says nothing about who someone is.
    preferred_language    TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email),
    CONSTRAINT chk_customers_preferred_language
        CHECK (preferred_language IS NULL OR preferred_language ~ '^[a-z]{2,3}$')
);
CREATE INDEX idx_customers_tenant        ON customers (tenant_id, status, created_at DESC);
CREATE INDEX idx_customers_tenant_phone  ON customers (tenant_id, phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_customers_phone_e164    ON customers (tenant_id, phone_e164);
CREATE UNIQUE INDEX uq_customers_login
    ON customers (tenant_id, login_id) WHERE login_id IS NOT NULL;

-- The till's customer search matches %term% against the name, the email, the phone and the phone's
-- digits. Without a trigram index every keystroke scans the business's whole customer table; with
-- one, Postgres can answer the four ILIKE/LIKE conditions from bitmap index scans.
--
-- pg_trgm is a trusted extension (PostgreSQL 13+), but a deployment may not let this role create it.
-- Then the search still works, by a sequential scan, and the indexes are simply not built: nothing
-- here may stop the service starting. The operator class is looked up in the schema the extension lives in
-- and written qualified, so the indexes survive a restore that runs with an empty search path.
--
-- These four, like the outbox's indexes below, are not led by tenant_id: each is keyed on the
-- expression alone, so one index serves every business's rows, and the search query's own
-- tenant_id condition does the filtering.
DO $$
DECLARE
    ext_schema text;
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pg_trgm could not be created (%): customer search keeps its sequential scan', SQLERRM;
    END;

    SELECT n.nspname INTO ext_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'pg_trgm';
    IF ext_schema IS NULL THEN
        RETURN;
    END IF;

    -- The name as an IMMUTABLE expression (concat_ws is only STABLE): the search query uses the
    -- very same expression, which is what lets the planner use the index.
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_name_trgm ON customers USING gin'
        || ' ((COALESCE(first_name, '''') || '' '' || COALESCE(last_name, '''')) %I.gin_trgm_ops)',
        ext_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_email_trgm ON customers USING gin'
        || ' (email %I.gin_trgm_ops)',
        ext_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_phone_trgm ON customers USING gin'
        || ' (phone %I.gin_trgm_ops)',
        ext_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_phone_digits_trgm ON customers USING gin'
        || ' ((regexp_replace(phone, ''[^0-9]'', '''', ''g'')) %I.gin_trgm_ops)',
        ext_schema);
END
$$;

CREATE TABLE customer_addresses (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL REFERENCES customers(id),
    type        TEXT        NOT NULL DEFAULT 'HOME',  -- HOME | BILLING | SHIPPING
    line1       TEXT        NOT NULL,
    line2       TEXT,
    city        TEXT,
    state       TEXT,
    country     TEXT        NOT NULL,
    pincode     TEXT,
    is_default  BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_addresses_tenant_cust ON customer_addresses (tenant_id, customer_id);

-- One loyalty account per customer per tenant. Points balance is denormalized here for fast reads;
-- the append-only ledger is the authoritative source of truth.
CREATE TABLE loyalty_accounts (
    id               UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    customer_id      UUID          NOT NULL REFERENCES customers(id),
    points_balance   NUMERIC(18,2) NOT NULL DEFAULT 0,
    lifetime_points  NUMERIC(18,2) NOT NULL DEFAULT 0,
    -- The tier's name in the business's programme (loyalty_tiers.name). A new account is BRONZE; the
    -- default programme's four names are BRONZE|SILVER|GOLD|PLATINUM.
    tier             TEXT          NOT NULL DEFAULT 'BRONZE',
    -- What counts towards the tier, and since when the customer has held it. Until a business sets a
    -- qualifying window, lifetime points count.
    qualifying_points NUMERIC(18,2) NOT NULL DEFAULT 0,
    tier_since        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id)
);
CREATE INDEX idx_loyalty_accounts_tenant ON loyalty_accounts (tenant_id, tier);

-- Append-only: never UPDATE or DELETE rows.
CREATE TABLE loyalty_ledger (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    customer_id   UUID          NOT NULL,
    type          TEXT          NOT NULL,             -- EARN | REDEEM | EXPIRE | ADJUST | REVERSE
    points        NUMERIC(18,2) NOT NULL,             -- positive = earned, negative = redeemed/expired
    balance_after NUMERIC(18,2) NOT NULL,
    order_id      UUID,
    reason        TEXT,
    -- The order's total at the time of an earning, so a return takes back only the returned share.
    -- Null for an earning made by hand (a return then reverses by the programme's rate). Money in the
    -- sale's currency, so a dinar total keeps its third place and the share is exact. Written by the
    -- INSERT, never updated: the ledger stays append-only.
    order_total   NUMERIC(18,4),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_loyalty_ledger_tenant_cust ON loyalty_ledger (tenant_id, customer_id, created_at DESC);
-- Everything earned and everything taken back for one order, per business.
CREATE INDEX idx_loyalty_ledger_order ON loyalty_ledger (tenant_id, order_id) WHERE order_id IS NOT NULL;

-- One store-credit account per customer per tenant per currency.
CREATE TABLE store_credit_accounts (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    customer_id UUID          NOT NULL REFERENCES customers(id),
    -- Money in the account's currency, held at four places: the most any ISO 4217 currency has
    -- (CLF and UYW). A Kuwaiti, Bahraini, Jordanian, Omani, Tunisian or Iraqi dinar has three, and
    -- two places rounded KWD 1.125 of credit to 1.13, money made out of nothing. The service refuses an
    -- amount finer than its own currency's minor units (STORE_CREDIT_AMOUNT_INVALID), so the extra
    -- places are never filled with anything a currency does not have.
    balance     NUMERIC(18,4) NOT NULL DEFAULT 0,
    -- No default: every insert binds the currency, and an omission must fail rather than stamp the
    -- account with a currency the business does not use.
    currency    TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id, currency)
);
CREATE INDEX idx_store_credit_accounts_tenant ON store_credit_accounts (tenant_id, customer_id);

-- Append-only: never UPDATE or DELETE rows.
CREATE TABLE store_credit_ledger (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    customer_id   UUID          NOT NULL,
    type          TEXT          NOT NULL,             -- ISSUE | REDEEM | EXPIRE | ADJUST
    -- Money in the ledger's currency, at the same four places as the account (see balance above).
    amount        NUMERIC(18,4) NOT NULL,             -- positive = issued, negative = redeemed
    balance_after NUMERIC(18,4) NOT NULL,
    -- No default, for the same reason as store_credit_accounts.currency.
    currency      TEXT          NOT NULL,
    order_id      UUID,
    reason        TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_credit_ledger_tenant_cust ON store_credit_ledger (tenant_id, customer_id, created_at DESC);

-- Transactional outbox (golden rule #6 — event + DB write are atomic).
CREATE TABLE outbox (
    id           UUID        PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    topic        TEXT        NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Retry and dead-letter state. A row that fails to publish is retried after a backoff
    -- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
    -- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
    -- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);
-- The outbox's indexes are not led by tenant_id: the relay drains every business's rows in one order
-- (created_at, id), and tenant_id here is nullable, since an event may belong to no business.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
-- The scheduled purge (common-service OutboxPublisher, hourly, in bounded batches) deletes published
-- rows older than storeql.outbox.retention-days, ordered by published_at. This index serves that scan.
-- It is partial on what the purge reads and nothing else, so the unpublished rows the drain reads stay
-- out of it and it costs nothing while the relay keeps up.
CREATE INDEX idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;
-- The claim: rows that may publish now, in the order they were written.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;
-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
