-- Bank-standard payment files and the bank's answer (readiness review 17.12).
--
-- A payment run is written as the bank's own standard file. The SEPA Regulation (EU) 260/2012
-- art.5(1)(d) requires bundled euro credit transfers a business sends its bank to be ISO 20022 XML
-- (pain.001), and a UK bank takes a sterling bulk payment as a Bacs Standard 18 file. Since 9 October
-- 2025 the Instant Payments Regulation (EU) 2024/886 has a bank verify each payee's name against the
-- IBAN before a euro transfer, and answer match, close match or no match per payee. That answer is
-- read, so a payee the bank could not match is never posted as paid.
--
-- Both files name the account the business pays from, and Bacs its service user number. The files
-- themselves are not stored: they are written from the run, which names them the same way every
-- time, so the bank's duplicate check refuses a file sent twice.

-- The business's paying account per currency. Append-only: the account in force is the latest row,
-- and a change made after a run was approved stops that run's bank file, as a supplier's does.
CREATE TABLE paying_accounts (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    currency            CHAR(3)     NOT NULL,
    account_name        TEXT        NOT NULL,
    sort_code           VARCHAR(6),
    account_number      VARCHAR(8),
    iban                VARCHAR(34),
    bic                 VARCHAR(11),
    service_user_number VARCHAR(6),
    set_by              UUID,
    set_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_paying_account_shape
        CHECK ((sort_code IS NOT NULL AND account_number IS NOT NULL) OR iban IS NOT NULL)
);
CREATE INDEX idx_paying_accounts_current ON paying_accounts (tenant_id, currency, set_at DESC);

-- A status report the bank returned for a run's file, recorded once by its own message id.
CREATE TABLE payment_status_reports (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    run_id              UUID        NOT NULL REFERENCES payment_runs(id),
    message_id          VARCHAR(35) NOT NULL,
    original_message_id VARCHAR(35) NOT NULL,
    group_status        VARCHAR(4),
    received_by         UUID,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_status_report UNIQUE (tenant_id, message_id)
);
CREATE INDEX idx_payment_status_reports_run ON payment_status_reports (tenant_id, run_id, received_at DESC);

-- What a report said about each supplier's payment in the run. The latest report is in force.
CREATE TABLE payment_statuses (
    tenant_id     UUID        NOT NULL,
    report_id     UUID        NOT NULL REFERENCES payment_status_reports(id),
    run_id        UUID        NOT NULL,
    supplier_id   UUID        NOT NULL,
    end_to_end_id VARCHAR(35) NOT NULL,
    status        VARCHAR(4)  NOT NULL,
    reason_code   VARCHAR(35),
    payee_match   VARCHAR(4),
    matched_name  TEXT,
    held          BOOLEAN     NOT NULL,
    PRIMARY KEY (tenant_id, report_id, supplier_id),
    CONSTRAINT chk_payee_match CHECK (payee_match IS NULL OR payee_match IN ('MTCH','CMTC','NMTC','NOAP'))
);
CREATE INDEX idx_payment_statuses_run ON payment_statuses (tenant_id, run_id, supplier_id);

-- A held close match a manager checked and released, with why. Once per report and supplier.
CREATE TABLE payment_hold_releases (
    tenant_id   UUID        NOT NULL,
    report_id   UUID        NOT NULL REFERENCES payment_status_reports(id),
    run_id      UUID        NOT NULL,
    supplier_id UUID        NOT NULL,
    released_by UUID        NOT NULL,
    reason      TEXT        NOT NULL,
    released_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, report_id, supplier_id)
);
