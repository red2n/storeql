-- 21.15: security incident, breach and exploited-vulnerability reporting.
--
-- Since 11 September 2026 a manufacturer of a product with digital elements must report an actively
-- exploited vulnerability, and a severe incident affecting its security, on a clock that starts when it
-- becomes aware: an early warning within 24 hours, a notification within 72, and a final report later
-- (Regulation (EU) 2024/2847, art.14). A personal data breach at a processor must reach each controller
-- without undue delay (GDPR / UK GDPR art.33(2)); here the controllers are the businesses on the platform.
--
-- Platform data, not tenant data: an incident belongs to the platform, so these tables carry no
-- tenant_id except the notices, which belong to the business they are addressed to. The timeline is
-- append-only — what was reported, when and under which reference is never rewritten — and an
-- incident's state is derived from it on every read.

-- The statutory stages and their clocks are reference data with their citations, not constants in code.
CREATE TABLE incident_reporting_stages (
    incident_kind TEXT    NOT NULL,
    stage         TEXT    NOT NULL,
    -- What the clock runs from: the moment of awareness, the notification, or a corrective measure.
    anchor        TEXT    NOT NULL,
    -- ISO-8601 duration or period after the anchor; null where the law sets no fixed time.
    due_after     TEXT,
    position      INTEGER NOT NULL,
    citation      TEXT    NOT NULL,
    summary       TEXT    NOT NULL,
    CONSTRAINT pk_incident_reporting_stages PRIMARY KEY (incident_kind, stage),
    CONSTRAINT chk_stage_kind CHECK (incident_kind IN ('EXPLOITED_VULNERABILITY', 'SEVERE_INCIDENT', 'PERSONAL_DATA_BREACH')),
    CONSTRAINT chk_stage_name CHECK (stage IN ('EARLY_WARNING', 'NOTIFICATION', 'FINAL_REPORT', 'TENANT_NOTICE')),
    CONSTRAINT chk_stage_anchor CHECK (anchor IN ('AWARE', 'NOTIFIED', 'MITIGATED')),
    CONSTRAINT chk_stage_due CHECK (due_after IS NULL OR due_after ~ '^P')
);

INSERT INTO incident_reporting_stages (incident_kind, stage, anchor, due_after, position, citation, summary) VALUES
 ('EXPLOITED_VULNERABILITY','EARLY_WARNING','AWARE','PT24H',1,'Regulation (EU) 2024/2847 art.14',
  'Early warning to the coordinating CSIRT and ENISA through the single reporting platform'),
 ('EXPLOITED_VULNERABILITY','NOTIFICATION','AWARE','PT72H',2,'Regulation (EU) 2024/2847 art.14',
  'Vulnerability notification: the product, the nature of the exploit, and corrective or mitigating measures'),
 ('EXPLOITED_VULNERABILITY','FINAL_REPORT','MITIGATED','P14D',3,'Regulation (EU) 2024/2847 art.14',
  'Final report, no later than 14 days after a corrective or mitigating measure is available'),
 ('EXPLOITED_VULNERABILITY','TENANT_NOTICE','AWARE',NULL,4,'Regulation (EU) 2024/2847 art.14',
  'Businesses using the platform told of the vulnerability and what to do about it'),
 ('SEVERE_INCIDENT','EARLY_WARNING','AWARE','PT24H',1,'Regulation (EU) 2024/2847 art.14',
  'Early warning to the coordinating CSIRT and ENISA through the single reporting platform'),
 ('SEVERE_INCIDENT','NOTIFICATION','AWARE','PT72H',2,'Regulation (EU) 2024/2847 art.14',
  'Incident notification: its nature, an initial assessment and corrective measures taken'),
 ('SEVERE_INCIDENT','FINAL_REPORT','NOTIFIED','P1M',3,'Regulation (EU) 2024/2847 art.14',
  'Final report within one month of the incident notification'),
 ('SEVERE_INCIDENT','TENANT_NOTICE','AWARE',NULL,4,'Regulation (EU) 2024/2847 art.14',
  'Businesses using the platform told of the incident and what to do about it'),
 ('PERSONAL_DATA_BREACH','TENANT_NOTICE','AWARE',NULL,1,'Regulation (EU) 2016/679 art.33(2); UK GDPR art.33(2)',
  'Each business affected told without undue delay: as controller it has 72 hours from becoming aware to tell its supervisory authority');

CREATE TABLE security_incidents (
    id                  UUID        PRIMARY KEY,
    kind                TEXT        NOT NULL,
    title               TEXT        NOT NULL,
    summary             TEXT        NOT NULL,
    -- When the platform became aware: every statutory clock runs from here.
    aware_at            TIMESTAMPTZ NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL,
    opened_by           UUID,
    affects_all_tenants BOOLEAN     NOT NULL,
    CONSTRAINT chk_incident_kind CHECK (kind IN ('EXPLOITED_VULNERABILITY', 'SEVERE_INCIDENT', 'PERSONAL_DATA_BREACH')),
    CONSTRAINT chk_incident_title CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT chk_incident_summary CHECK (length(summary) BETWEEN 1 AND 4000)
);
CREATE INDEX idx_security_incidents_aware ON security_incidents (aware_at DESC, id DESC);

-- The businesses affected, when not all of them.
CREATE TABLE security_incident_tenants (
    incident_id UUID NOT NULL REFERENCES security_incidents (id),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    CONSTRAINT pk_security_incident_tenants PRIMARY KEY (incident_id, tenant_id)
);

CREATE TABLE security_incident_events (
    id          UUID        PRIMARY KEY,
    incident_id UUID        NOT NULL REFERENCES security_incidents (id),
    kind        TEXT        NOT NULL,
    -- When it happened, which may be before it was written down.
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID,
    -- The authority's reference, such as the single reporting platform's case number.
    reference   TEXT,
    note        TEXT,
    CONSTRAINT chk_incident_event_kind CHECK (kind IN ('EARLY_WARNING_SENT', 'NOTIFICATION_SENT', 'MITIGATION_AVAILABLE',
        'FINAL_REPORT_SENT', 'TENANTS_NOTIFIED', 'NOTE', 'CLOSED')),
    CONSTRAINT chk_incident_event_reference CHECK (reference IS NULL OR length(reference) BETWEEN 1 AND 120),
    CONSTRAINT chk_incident_event_note CHECK (note IS NULL OR length(note) BETWEEN 1 AND 2000)
);
CREATE INDEX idx_security_incident_events ON security_incident_events (incident_id, occurred_at, id);
-- Each stage is recorded once, however many people press the button at the same moment.
CREATE UNIQUE INDEX uq_security_incident_stage_once ON security_incident_events (incident_id, kind) WHERE kind <> 'NOTE';

CREATE TABLE security_notices (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenants (id),
    incident_id     UUID        NOT NULL REFERENCES security_incidents (id),
    title           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL,
    issued_by       UUID,
    -- The business's owner or manager confirming they have read it: the evidence the controller was told.
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by UUID,
    CONSTRAINT uq_security_notice_per_tenant UNIQUE (incident_id, tenant_id),
    CONSTRAINT chk_security_notice_ack CHECK ((acknowledged_at IS NULL) = (acknowledged_by IS NULL))
);
CREATE INDEX idx_security_notices_tenant ON security_notices (tenant_id, issued_at DESC, id DESC);
