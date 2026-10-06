-- iam-svc schema. Identity for BOTH staff and customers.
--
-- Tenant scoping: a business's staff belong to it (users.tenant_id set), and an address or phone is
-- one login inside a business. A shopper's account (type CUSTOMER) belongs to no business: its
-- tenant_id is NULL, and the one login signs in at any business's storefront. A login that belongs
-- to no business yet (a business sign-up not yet onboarded, the platform administrator) is NULL
-- too. So users.tenant_id is intentionally NULLABLE, a deliberate exception to the usual NOT NULL
-- rule. What a business does with its own staff names the business before it touches a role:
-- listing reads users WHERE tenant_id = ? first; assigning (StaffAssigned) and removing
-- (StaffRemoved) each lock the login's row under the business (tenant_id = ? AND id = ?, FOR NO KEY
-- UPDATE) before any role is written or deleted, so a login leaving the business waits for them,
-- and refuse, audited against the business that sent the event and no login, a login that is not
-- the business's. A removal's delete, its count of the staff roles left and its check for a
-- shopper's role join users on the tenant as well. A sign-in finds the logins an address holds in
-- every business and outside any, and a lookup by id reads the tenant off the row, so its caller
-- checks it.
--
-- A shopper's account and a business account are separate identities (as at Shopify,
-- Square and Stripe): one person may shop with an address or phone and run a business with the same
-- one. Inside a business an address or phone is one login. Outside any business it is one login of
-- each kind: one shopper's (CUSTOMER), and one of the business kind (STAFF: a business sign-up not
-- yet onboarded, the platform administrator). Two shopper sign-ups, or two business sign-ups, cannot
-- share an address or a phone. The phone indexes are split by kind for the same reason: a shopper's
-- sign-up and a business sign-up may each record a number.

-- users.status holds the two values the code writes: ACTIVE, from the day a login is made, and
-- DELETED, once a shopper has deleted their own account (the row stays, with no address, phone or
-- password, so an id kept elsewhere still resolves). A password sign-in, a second-factor step, an
-- SSO sign-in and a forgotten-password link admit an ACTIVE login only: any other value is refused
-- (a password sign-in answers 401 INVALID_CREDENTIALS, after the same hashing work as for an
-- unknown address).
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    tenant_id     UUID,                              -- a business's STAFF; NULL otherwise (see the header)
    type          TEXT NOT NULL,                     -- STAFF | CUSTOMER
    email         TEXT,
    phone         TEXT,
    password_hash TEXT,                              -- Argon2; NULL if OTP-only
    status        TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | DELETED (see above)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()  -- audit timestamp
);
CREATE UNIQUE INDEX uq_users_business_email ON users (tenant_id, lower(email))
    WHERE tenant_id IS NOT NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_unbound_email ON users (type, lower(email))
    WHERE tenant_id IS NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_business_phone ON users (tenant_id, phone)
    WHERE tenant_id IS NOT NULL AND phone IS NOT NULL;
CREATE UNIQUE INDEX uq_users_unbound_phone ON users (type, phone)
    WHERE tenant_id IS NULL AND phone IS NOT NULL;
CREATE INDEX idx_users_tenant ON users (tenant_id, status);

CREATE TABLE roles (
    id    UUID PRIMARY KEY,
    name  TEXT NOT NULL UNIQUE                       -- PLATFORM_ADMIN, OWNER, MANAGER, STOREKEEPER, CASHIER, CUSTOMER
);

-- A role assignment. The five tier roles above are what every token carries and every tier gate
-- reads. What a tenant may also do is define a role ON a tier that holds fewer of that tier's
-- permissions, and assign staff to it:
--
--   role_code       the tenant's own code for the role the assignment was made with, e.g. SHIFT_LEAD;
--                   NULL for a plain tier assignment
--   permissions     the permissions that role held when it was assigned or last redefined, as a
--                   comma-separated list ('' for a role narrowed to nothing); NULL for a plain tier
--                   assignment, which is judged by the tier's defaults
--   permissions_at  the version those permissions came from: the role's own updated_at. Two
--                   redefinitions of one role within a second can reach this service in either
--                   order, so RoleDefined applies only when it is newer, or the row has no version
--                   yet, or the event carries none; and a StaffAssigned made with a newer
--                   definition is not undone by an older RoleDefined that arrives afterwards.
--
-- Kept beside the assignment rather than in a roles projection of its own so that a login reads
-- one table, and updated in place when tenant-svc announces a role redefined (RoleDefined). A role
-- in use cannot be deleted, so a row never outlives the code it names.
CREATE TABLE user_roles (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(id),
    role_id        UUID NOT NULL REFERENCES roles(id),
    store_id       UUID,                             -- optional: role scoped to a store
    role_code      TEXT,
    permissions    TEXT,
    permissions_at TIMESTAMPTZ,
    UNIQUE (user_id, role_id, store_id)
);
CREATE INDEX idx_user_roles_user ON user_roles (user_id);
CREATE INDEX idx_user_roles_code ON user_roles (role_code) WHERE role_code IS NOT NULL;

-- A sign-in is a session: the chain of refresh tokens it rotates through shares one session_id.
-- A session the provider vouched for lasts only so long before the provider is asked again (see
-- authenticated_at): that is what makes switching someone off at the provider switch them off here.
CREATE TABLE refresh_tokens (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id),
    token_hash       TEXT NOT NULL,                  -- store a hash, never the raw token
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked          BOOLEAN NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    session_id       UUID NOT NULL,                  -- the sign-in this token belongs to
    started_at       TIMESTAMPTZ NOT NULL,           -- when that sign-in began
    device_label     TEXT,                           -- the short device label, so a session can be shown to its owner
    network          TEXT,                           -- the truncated network prefix, for the same reason
    amr              TEXT,                           -- how the session was authenticated, carried across refreshes so a renewed token says the same
    authenticated_at TIMESTAMPTZ                     -- when the session was signed into, carried across every rotation; NULL: no provider age to check
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id, revoked);
CREATE INDEX idx_refresh_user_session ON refresh_tokens (user_id, session_id);
CREATE UNIQUE INDEX uq_refresh_hash ON refresh_tokens (token_hash);
-- The daily purge of refresh tokens long expired scans by expiry.
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);

CREATE TABLE otp_codes (
    id         UUID PRIMARY KEY,
    target     TEXT NOT NULL,                        -- email or phone the code was sent to
    code_hash  TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed   BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_target ON otp_codes (target, consumed);

-- Append-only audit log.
CREATE TABLE audit_log (
    id         UUID PRIMARY KEY,
    tenant_id  UUID,
    user_id    UUID,
    action     TEXT NOT NULL,                        -- USER_REGISTERED, LOGIN_OK, LOGIN_FAILED, ...
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant ON audit_log (tenant_id, created_at DESC);

-- Transactional outbox: events written in the same tx as the state change, drained to Kafka.
--
-- The outbox is cross-tenant on purpose: one relay (common-service BaseOutboxRepository) drains the
-- rows of every business in the order they were written, so no statement the service or the relay
-- runs on it filters by tenant_id, and it has no index that starts with tenant_id. Its tenant_id
-- only names the business an event is about (NULL when it is about none, as for a shopper) and is
-- not a lookup key.
--
-- A row that fails to publish is retried after a backoff (storeql.outbox.backoff-base-seconds,
-- doubling, capped at storeql.outbox.backoff-cap-seconds), and only that row's aggregate waits for
-- it. After storeql.outbox.max-attempts it is a dead letter: never claimed again, kept for an
-- operator, and it holds back its own aggregate only.
CREATE TABLE outbox (
    id              UUID PRIMARY KEY,
    event_type      TEXT NOT NULL,
    topic           TEXT NOT NULL,
    tenant_id       UUID,
    aggregate_id    UUID NOT NULL,
    payload         TEXT NOT NULL,                   -- JSON
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);
-- The claim (common-service BaseOutboxRepository.claim): rows that may publish now, in the order
-- they were written. idx_outbox_claim serves its ordered scan (ORDER BY created_at, id LIMIT n):
-- partial on not published and not dead, it holds every row the claim can return and no dead
-- letter, already in the claim's order (the backoff, next_attempt_at, is read from the row).
-- Marking a row published and recording a failure go by primary key. No index of every waiting row
-- by created_at (idx_outbox_unpublished) is kept: it would also hold the dead letters, which the
-- claim's ordered scan never reads, and the claim is the one statement that reads waiting rows in
-- that order. OutboxPurgeIndexIT plans the claim as BaseOutboxRepository really runs it.
CREATE INDEX idx_outbox_claim ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;
-- The claim's per-aggregate check (its NOT EXISTS: is an earlier waiting row of the same aggregate
-- dead or backing off?) reads waiting rows by aggregate_id, which idx_outbox_claim does not lead
-- with: idx_outbox_aggregate_pending, partial on not published, serves it, and holds the dead
-- letters the check looks for.
CREATE INDEX idx_outbox_aggregate_pending ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
-- The scheduled purge (common-service OutboxPublisher, through BaseOutboxRepository) deletes in
-- batches of the oldest delivered rows. The index is partial: it holds only delivered rows, so it
-- stays small, and the claim's two indexes, which hold only waiting rows, never carry them.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;

-- Seed the standard roles.
INSERT INTO roles (id, name) VALUES
    ('01a090a0-1bc3-7000-851b-81f26d60cbb7', 'PLATFORM_ADMIN'),
    ('01a090a0-1bc3-7001-958b-727cae4292fa', 'OWNER'),
    ('01a090a0-1bc3-7002-9476-b590fcfcb183', 'MANAGER'),
    ('01a090a0-1bc3-7003-8305-a8158d7ec60c', 'STOREKEEPER'),
    ('01a090a0-1bc3-7004-ad00-f94781b687f8', 'CASHIER'),
    ('01a090a0-1bc3-7005-a214-7a6740565dbb', 'CUSTOMER');
