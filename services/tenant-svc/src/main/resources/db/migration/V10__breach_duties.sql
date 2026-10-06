-- What a business must do when the platform tells it of a personal data breach (13.12, with 21.15).
--
-- The platform is a processor: it tells each business affected (security_notices). From that
-- moment the business's own clock runs as its own law sets it. Under India's DPDP Act a Data
-- Fiduciary tells each affected Data Principal without delay, tells the Board without delay, and
-- reports to the Board within seventy-two hours (DPDP Rules 2025 r.7); under the GDPR a controller
-- notifies its supervisory authority within seventy-two hours and tells the people affected without
-- undue delay where the risk to them is high (arts.33–34). The duties are reference data with their
-- citations, as the platform's own reporting stages are; what the business did is recorded once per
-- duty and never rewritten.
CREATE TABLE breach_duties (
    regime    TEXT    NOT NULL,
    duty      TEXT    NOT NULL,
    -- The clock runs from the notice: the moment the business became aware.
    anchor    TEXT    NOT NULL,
    -- ISO-8601 duration after the anchor; null where the law says only "without delay".
    due_after TEXT,
    position  INTEGER NOT NULL,
    citation  TEXT    NOT NULL,
    summary   TEXT    NOT NULL,
    CONSTRAINT pk_breach_duties PRIMARY KEY (regime, duty),
    CONSTRAINT chk_breach_regime CHECK (regime IN ('DPDP', 'GDPR')),
    CONSTRAINT chk_breach_duty CHECK (duty IN ('PRINCIPALS_TOLD', 'BOARD_INTIMATED', 'BOARD_REPORTED', 'AUTHORITY_NOTIFIED', 'SUBJECTS_TOLD')),
    CONSTRAINT chk_breach_anchor CHECK (anchor IN ('NOTICE')),
    CONSTRAINT chk_breach_due CHECK (due_after IS NULL OR due_after ~ '^P')
);
INSERT INTO breach_duties (regime, duty, anchor, due_after, position, citation, summary) VALUES
 ('DPDP','PRINCIPALS_TOLD','NOTICE',NULL,1,'DPDP Rules 2025 r.7(1)',
  'Each affected person told without delay, in plain words: the breach, its likely consequences, what is being done, what they can do, and who to contact'),
 ('DPDP','BOARD_INTIMATED','NOTICE',NULL,2,'DPDP Rules 2025 r.7(2)(a)',
  'The Data Protection Board told without delay: the nature, extent, timing and location of the breach and its likely impact'),
 ('DPDP','BOARD_REPORTED','NOTICE','PT72H',3,'DPDP Rules 2025 r.7(2)(b)',
  'Within seventy-two hours, or longer if the Board allows: the facts and causes, the mitigation taken, findings on who caused it, remedies against recurrence, and the report of the intimations made'),
 ('GDPR','AUTHORITY_NOTIFIED','NOTICE','PT72H',1,'Regulation (EU) 2016/679 art.33; UK GDPR art.33',
  'The supervisory authority notified within seventy-two hours of becoming aware, unless the breach is unlikely to put people at risk'),
 ('GDPR','SUBJECTS_TOLD','NOTICE',NULL,2,'Regulation (EU) 2016/679 art.34; UK GDPR art.34',
  'Each person told without undue delay where the breach is likely to put their rights at high risk');

CREATE TABLE security_notice_reports (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    notice_id   UUID        NOT NULL REFERENCES security_notices (id),
    duty        TEXT        NOT NULL,
    done_at     TIMESTAMPTZ NOT NULL,
    -- The Board's or authority's reference, or the intimation's id, as the business notes it.
    reference   TEXT,
    note        TEXT,
    recorded_by UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notice_report UNIQUE (tenant_id, notice_id, duty)
);
CREATE INDEX idx_notice_reports_tenant ON security_notice_reports (tenant_id, notice_id);
