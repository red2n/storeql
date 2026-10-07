-- Retention schedules (readiness review 21.16).
--
--   UK GDPR art.5(1)(e)   personal data kept no longer than necessary — storage limitation — and
--   art.30                a record of the retention periods applied
--   VATA 1994 Sch.11 para.6(3)   VAT records kept for six years
--
-- A business keeps each class of data for a period it sets, never shorter than the longest period
-- the law of any country it trades in requires. The floors are platform reference data, like the
-- legal obligations (V6): a change in the law is a migration with its citation. The schedule is the
-- business's decision, kept as a history so what was set, by whom and when is never lost; a legal
-- hold stops a subject or a class being purged while a matter is open; and every purge a service
-- runs is recorded here, whichever service ran it, so one register says what was deleted and when.

-- The classes of data a schedule governs. Reference data, not tenant data.
CREATE TABLE retention_classes (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    -- What a purge does to this class: DELETE the rows, ANONYMISE the personal data, or KEEP —
    -- the record must stay for the floor's whole period and the platform never purges it.
    purge_kind  TEXT NOT NULL,
    purged_by   TEXT,
    description TEXT NOT NULL,
    CONSTRAINT chk_retention_class_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT chk_retention_purge_kind CHECK (purge_kind IN ('DELETE', 'ANONYMISE', 'KEEP'))
);

INSERT INTO retention_classes (code, name, purge_kind, purged_by, description) VALUES
 ('TRANSACTIONS', 'Transactions', 'KEEP', NULL,
  'Orders, receipts, returns and payments: the VAT record of each sale. Kept for the period; the platform never deletes them.'),
 ('ORDER_PERSONAL_DATA', 'Personal details on settled orders', 'ANONYMISE', 'order-svc',
  'The delivery name, address, phone and notes on an order once it is settled. The order and its lines stay; the person leaves.'),
 ('CUSTOMER_RECORDS', 'Customer records', 'ANONYMISE', 'customer-svc',
  'A customer record, its addresses and consents, once nothing has happened on it for the period: erased as the customer could have asked, and every copy with it.'),
 ('NOTIFICATION_LOG', 'Messages sent', 'DELETE', 'notification-svc',
  'The log of emails, texts and pushes sent to customers and staff.');

-- The least the law of a country, or of a regime through its members, requires a class be kept.
CREATE TABLE retention_floors (
    data_class TEXT    NOT NULL REFERENCES retention_classes (code),
    scope_kind TEXT    NOT NULL,
    scope      TEXT    NOT NULL,
    min_days   INTEGER NOT NULL,
    citation   TEXT    NOT NULL,
    summary    TEXT    NOT NULL,
    CONSTRAINT pk_retention_floors PRIMARY KEY (data_class, scope_kind, scope),
    CONSTRAINT chk_retention_floor_scope_kind CHECK (scope_kind IN ('REGIME', 'COUNTRY')),
    CONSTRAINT chk_retention_floor_days CHECK (min_days > 0)
);

INSERT INTO retention_floors (data_class, scope_kind, scope, min_days, citation, summary) VALUES
 ('TRANSACTIONS','COUNTRY','GB',2190,'Value Added Tax Act 1994 Sch.11 para.6(3); HMRC VAT Notice 700/21',
  'VAT records, including every sale and the invoices behind it, are kept for six years.'),
 ('TRANSACTIONS','COUNTRY','DE',2920,'Abgabenordnung §147(3) as amended by the Bürokratieentlastungsgesetz IV (1 January 2025)',
  'Accounting vouchers (Buchungsbelege) are kept for eight years; books and annual accounts ten.'),
 ('TRANSACTIONS','COUNTRY','FR',3650,'Code de commerce art. L123-22',
  'Accounting documents and supporting vouchers are kept for ten years.'),
 ('TRANSACTIONS','COUNTRY','IN',2920,'Companies Act 2013 s.128(5)',
  'Books of account and their vouchers are kept for not less than eight financial years.');

-- The business's own periods: one row per decision, the latest per class in force. Append-only.
CREATE TABLE retention_schedules (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    data_class  TEXT        NOT NULL REFERENCES retention_classes (code),
    period_days INTEGER     NOT NULL,
    set_by      UUID        NOT NULL,
    set_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_retention_period CHECK (period_days >= 0 AND period_days <= 36500)
);
CREATE INDEX idx_retention_schedules_tenant ON retention_schedules (tenant_id, data_class, set_at DESC, id DESC);

-- A matter that stops a purge: a whole class, or one customer or order, until it is released.
CREATE TABLE retention_holds (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    -- NULL: every class.
    data_class     TEXT        REFERENCES retention_classes (code),
    subject_kind   TEXT        NOT NULL,
    subject_id     UUID,
    reason         TEXT        NOT NULL,
    placed_by      UUID        NOT NULL,
    placed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by    UUID,
    released_at    TIMESTAMPTZ,
    release_reason TEXT,
    CONSTRAINT chk_retention_hold_kind CHECK (subject_kind IN ('ALL', 'CUSTOMER', 'ORDER')),
    CONSTRAINT chk_retention_hold_subject CHECK ((subject_kind = 'ALL') = (subject_id IS NULL)),
    CONSTRAINT chk_retention_hold_release CHECK ((released_at IS NULL) = (released_by IS NULL)
        AND (released_at IS NULL) = (release_reason IS NULL))
);
CREATE INDEX idx_retention_holds_tenant ON retention_holds (tenant_id, placed_at DESC, id DESC);
CREATE INDEX idx_retention_holds_active ON retention_holds (tenant_id, data_class) WHERE released_at IS NULL;

-- Every purge a service ran, as it announced it. Append-only; the id is the event's, so a
-- redelivery records nothing twice.
CREATE TABLE retention_runs (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    service       TEXT        NOT NULL,
    data_class    TEXT        NOT NULL REFERENCES retention_classes (code),
    cutoff        TIMESTAMPTZ NOT NULL,
    rows_affected INTEGER     NOT NULL,
    held_skipped  INTEGER     NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_retention_run_counts CHECK (rows_affected >= 0 AND held_skipped >= 0)
);
CREATE INDEX idx_retention_runs_tenant ON retention_runs (tenant_id, finished_at DESC, id DESC);
