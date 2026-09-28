-- Accounting connectors (17.9): the package a business keeps its books in — Xero, QuickBooks
-- Online, Sage Business Cloud Accounting, or the platform's own stand-in — the mapping of its
-- nominal codes onto the package's accounts, and every journal's push to it.
--
-- One package per business. The tokens the package issued are sealed under a key of their own
-- (storeql.accounting.secrets-key) and never leave this table in the clear. A sync is one journal's
-- journey: PENDING with a next try, DELIVERED with what the package called it, FAILED after its
-- tries or a refusal that needs a person, UNCERTAIN when a push may have reached a package with no
-- idempotency key and got no answer (a second try could book it twice; a person decides), SKIPPED
-- when someone said it is not to be pushed. Attempts are append-only.
CREATE TABLE accounting_connections (
    id                 UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL,
    provider           TEXT        NOT NULL,
    status             TEXT        NOT NULL,
    settings           TEXT        NOT NULL,        -- JSON: the package's identifiers, as its provider names them
    credentials_sealed TEXT,                        -- the tokens, sealed; NULL for a package that needs none
    sync_from          DATE        NOT NULL,        -- journals dated from this day are pushed
    created_by         UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    last_sync_at       TIMESTAMPTZ,
    last_error         TEXT,                        -- the first thing that went wrong on the last pass
    disabled_reason    TEXT,
    CONSTRAINT ck_accounting_provider CHECK (provider IN ('XERO', 'QUICKBOOKS', 'SAGE', 'SIMULATED')),
    CONSTRAINT ck_accounting_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE UNIQUE INDEX uq_accounting_connections_tenant ON accounting_connections (tenant_id);

CREATE TABLE accounting_account_mappings (
    connection_id    UUID        NOT NULL REFERENCES accounting_connections (id) ON DELETE CASCADE,
    nominal_code     VARCHAR(10) NOT NULL,
    external_account TEXT        NOT NULL,           -- what a journal line names the account by in the package
    external_name    TEXT,
    PRIMARY KEY (connection_id, nominal_code)
);

CREATE TABLE accounting_syncs (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    connection_id   UUID        NOT NULL REFERENCES accounting_connections (id) ON DELETE CASCADE,
    journal_id      UUID        NOT NULL,
    status          TEXT        NOT NULL,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    external_id     TEXT,                            -- what the package called the journal, once delivered
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    leased_until    TIMESTAMPTZ,                     -- claimed by one instance until then
    created_at      TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT ck_accounting_sync_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'UNCERTAIN', 'SKIPPED')),
    CONSTRAINT uq_accounting_sync UNIQUE (connection_id, journal_id)
);
CREATE INDEX idx_accounting_syncs_due ON accounting_syncs (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_accounting_syncs_tenant ON accounting_syncs (tenant_id, id DESC);

CREATE TABLE accounting_sync_attempts (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    sync_id      UUID        NOT NULL REFERENCES accounting_syncs (id) ON DELETE CASCADE,
    attempt      INTEGER     NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    status_code  INTEGER,
    error        TEXT,
    snippet      TEXT,                               -- the package's answer, the first 2000 characters
    duration_ms  INTEGER     NOT NULL
);
CREATE INDEX idx_accounting_attempts_sync ON accounting_sync_attempts (tenant_id, sync_id, attempt);
