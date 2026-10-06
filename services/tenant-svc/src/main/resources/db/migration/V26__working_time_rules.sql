-- Working-time law as reference data (workforce-rules slice 1).
--
-- Workforce.concerns used to quote the EU Working Time Directive to every business on earth and read
-- the roster's day in UTC. A rule of law is data with a citation, applied to a store through its
-- country (directly or through a regime it belongs to, with the membership dates) and judged in the
-- store's own zone.
--
-- Platform reference data, like legal_obligations (V6): no tenant_id, nothing editable through the
-- API, a change in the law is a migration with its citation.
--
-- What ships is only what the code already applied: the eleven-hour daily rest and the break after
-- six hours, under the EU regime. Every other country's or regime's pack is added by a migration by
-- someone who has the legal source; this file states no other number.
--
-- The code and applies_to checks list only what the evaluator (domain/WorkingTime) applies today. A
-- rule the evaluator does not read would be a promise nobody keeps, so the migration that adds a code
-- (MAX_SHIFT, MAX_WEEK, MIN_BREAK, WEEK_REFERENCE), a region, or YOUNG_WORKER rules widens these
-- checks in the same change that teaches the evaluator to apply it.

CREATE TABLE working_time_rules (
    scope_kind     TEXT           NOT NULL,
    scope          TEXT           NOT NULL,
    code           TEXT           NOT NULL,
    applies_to     TEXT           NOT NULL,
    effective_from DATE           NOT NULL,
    effective_to   DATE,
    rule_value     NUMERIC(10,2)  NOT NULL,
    unit           TEXT           NOT NULL,
    severity       TEXT           NOT NULL,
    citation       TEXT           NOT NULL,
    summary        TEXT           NOT NULL,
    CONSTRAINT pk_working_time_rules PRIMARY KEY (scope_kind, scope, code, applies_to, effective_from),
    CONSTRAINT chk_wtr_scope_kind CHECK (scope_kind IN ('REGIME', 'COUNTRY')),
    CONSTRAINT chk_wtr_code CHECK (code IN ('MIN_DAILY_REST', 'BREAK_AFTER')),
    CONSTRAINT chk_wtr_applies_to CHECK (applies_to IN ('ALL')),
    CONSTRAINT chk_wtr_unit CHECK (unit IN ('HOURS', 'MINUTES')),
    CONSTRAINT chk_wtr_severity CHECK (severity IN ('ADVISORY', 'UNLAWFUL')),
    CONSTRAINT chk_wtr_value CHECK (rule_value > 0),
    CONSTRAINT chk_wtr_citation CHECK (length(btrim(citation)) > 0),
    CONSTRAINT chk_wtr_window CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_working_time_rules_scope ON working_time_rules (scope_kind, scope);

-- Both are ADVISORY: member states implement the directive with their own derogations and collective
-- agreements, so a roster that breaks them is flagged, never refused. Effective from 2 August 2004,
-- when Directive 2003/88/EC took the place of Directive 93/104/EC.
INSERT INTO working_time_rules
    (scope_kind, scope, code, applies_to, effective_from, effective_to, rule_value, unit, severity, citation, summary)
VALUES
 ('REGIME', 'EU', 'MIN_DAILY_REST', 'ALL', DATE '2004-08-02', NULL, 11, 'HOURS', 'ADVISORY',
  'Directive 2003/88/EC art. 3',
  'Eleven consecutive hours of rest in each 24-hour period.'),
 ('REGIME', 'EU', 'BREAK_AFTER', 'ALL', DATE '2004-08-02', NULL, 6, 'HOURS', 'ADVISORY',
  'Directive 2003/88/EC art. 4',
  'A rest break where the working day is longer than six hours; length and terms are the member state''s.');
