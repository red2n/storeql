-- Statutory reporting by jurisdiction: which returns a business owes, when each falls due, and what
-- it filed.
--
-- The platform can already *produce* what several jurisdictions want — Germany's DSFinV-K and
-- Portugal's SAF-T from order-svc, the VAT return and its MTD submission from pricing-svc. What it
-- could not do is say which returns a given business owes, when, or whether any of them went. An
-- auditor does not ask whether a SAF-T can be generated; it asks to see the one filed for September.
--
-- Two things are deliberately NOT here. There is no due_date column and no DUE status: both are
-- derived from the return's frequency and its statutory offset against the period, on every read,
-- because a stored deadline is a deadline that goes stale — the same reasoning as the incident
-- register's clocks (V7). And there are no export bytes: SAF-T belongs to order-svc and the VAT
-- return to pricing-svc, so a return names *where* its export lives and the console follows the link.
-- Serving another service's data from here would be golden rule #1, and proxying a whole fiscal
-- export synchronously would be a poor shape besides.

-- What each jurisdiction requires. Reference data with a citation, like legal_obligations (V6), and
-- seeded for the same reason: this is the law, not a preference, so it needs no actor to have chosen
-- it.
CREATE TABLE statutory_returns (
    code           TEXT NOT NULL,
    -- COUNTRY + 'PT', or REGIME + 'EU' so a regime's return reaches every member while it is one.
    scope_kind     TEXT NOT NULL,
    scope          TEXT NOT NULL,
    name           TEXT NOT NULL,
    frequency      TEXT NOT NULL,
    -- ISO-8601 period added to period_end, which is EXCLUSIVE — the first day after the period. So
    -- the offset is what the law adds to "the day the period ended", and the arithmetic lands on the
    -- day the instrument names:
    --   Portugal, "by the 5th of the following month":  1 Oct + P4D   = 5 Oct
    --   HMRC, "one month and 7 days after the period":  1 Oct + P1M6D = 7 Nov  (as HMRC publishes it)
    --   EU, "by the 20th of the month after":           1 Oct + P19D  = 20 Oct
    -- A period and not a day count, because a month is a month: P1M6D from a quarter to 31 March is
    -- 7 May and from one to 30 September is 7 November, which no number of days gives you. Do not
    -- "correct" P1M6D to P1M7D: seven days from the day after the period is the 8th, not the 7th.
    due_after      TEXT NOT NULL,
    -- Where the export that answers this return lives. A link, never a proxy: the service that owns
    -- the data serves the bytes. The path is the route as that service itself serves it — not the
    -- gateway's /api/v1/{service} form, and not assumed to sit under /admin/: pricing-svc's VAT return
    -- is /vat-return, order-svc's exports are under /admin. Null where the platform cannot produce it
    -- at all, which the calendar shows as such — a gap the business must close with an accountant is
    -- worth seeing.
    export_service TEXT,
    export_path    TEXT,
    citation       TEXT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to   DATE,

    CONSTRAINT pk_statutory_returns PRIMARY KEY (code, scope_kind, scope, effective_from),
    CONSTRAINT ck_statutory_scope_kind CHECK (scope_kind IN ('COUNTRY', 'REGIME')),
    -- DECADAL is a real frequency in French tax law and not a rounding of "monthly": a business on the
    -- ordinary monthly VAT regime reports three times a month. It cannot be expressed as a day count — the
    -- third period of a month runs from the 21st to the 1st, which is 11 days in March and 8 in February —
    -- so it is a frequency the calendar derives, exactly like a quarter.
    CONSTRAINT ck_statutory_frequency CHECK (frequency IN ('DECADAL', 'MONTHLY', 'QUARTERLY', 'ANNUAL')),
    -- At least one component: a bare 'P' is not a period, and it would read as "due immediately".
    CONSTRAINT ck_statutory_due_after CHECK (
        due_after ~ '^P([0-9]+M)?([0-9]+D)?$' AND due_after <> 'P'
    ),
    CONSTRAINT ck_statutory_window CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- A route without a service, or the other way about, is half a link and would 404 quietly.
    CONSTRAINT ck_statutory_export CHECK (
        (export_service IS NULL AND export_path IS NULL)
        OR (export_service IS NOT NULL AND export_path IS NOT NULL)
    )
);

CREATE INDEX idx_statutory_returns_scope ON statutory_returns (scope_kind, scope);

-- Portugal: the monthly SAF-T (PT) of issued invoices, by the 5th of the following month.
INSERT INTO statutory_returns
    (code, scope_kind, scope, name, frequency, due_after, export_service, export_path, citation,
     effective_from)
VALUES
    ('SAFT_PT', 'COUNTRY', 'PT', 'SAF-T (PT) sales invoices', 'MONTHLY', 'P4D',
     'order-svc', '/admin/fiscal-receipts/export?format=saft-pt',
     'Portaria n.º 302/2016; Decreto-Lei n.º 198/2012 art. 3', '2020-01-01'),

-- Germany: KassenSichV sets NO periodic filing date — a Kassennachschau can arrive any morning and
-- the export must be producible at once. The monthly row is therefore the platform's own checkpoint,
-- named as one, so that a month whose export nobody has ever produced is visible before an inspector
-- asks for it rather than after. It is the one date here that is not a statutory deadline.
    ('DSFINVK_DE', 'COUNTRY', 'DE', 'DSFinV-K export (monthly checkpoint, not a filing)', 'MONTHLY',
     'P9D',
     'order-svc', '/admin/fiscal-receipts/export?format=dsfinvk',
     'KassenSichV §4; AO §146a', '2020-01-01'),

-- The United Kingdom: VAT under Making Tax Digital, one month and seven days after the quarter.
    ('VAT_RETURN_UK', 'COUNTRY', 'GB', 'VAT return (Making Tax Digital)', 'QUARTERLY', 'P1M6D',
     'pricing-svc', '/vat-return',
     'VATA 1994 sch.11 para.2; SI 1995/2518 reg.25', '2019-04-01'),

-- The EU: the recapitulative statement for cross-border B2B supplies, by the 20th of the month after
-- the period. A regime row, so it reaches a member while it is one and stops when it is not — which
-- is why membership is asked of the period's own dates and not of a list.
    ('EC_SALES_LIST', 'REGIME', 'EU', 'Recapitulative statement (EC Sales List)', 'MONTHLY', 'P19D',
     NULL, NULL,
     'Directive 2006/112/EC art. 262-264', '2010-01-01'),

-- France's e-reporting, on the statutory calendar. Two rows, because the law asks for two streams with the
-- same cadence but different content. Transaction data: the B2C sales and the cross-border sales of the
-- period, with the payload built by order-svc, which owns the sales and already holds each line's VAT rate.
    ('EREPORTING_TX_FR', 'COUNTRY', 'FR',
     'E-reporting: transaction data (données de transaction)', 'DECADAL', 'P10D',
     'order-svc', '/admin/ereporting/submissions',
     'CGI art. 290; décret n° 2022-1299 du 7 oct. 2022; LF 2024 art. 91 (dates)', '2026-09-01'),

-- Payment data: when the money for a service was actually received. Reported on the same cadence and
-- to the same platform, and kept as its own return because a business may owe one stream and not the
-- other — a shop selling only goods owes no payment data at all, and a calendar that hid that
-- distinction would show it a duty it does not have.
    ('EREPORTING_PAY_FR', 'COUNTRY', 'FR',
     'E-reporting: payment data (données de paiement, services)', 'DECADAL', 'P10D',
     'order-svc', '/admin/ereporting/submissions',
     'CGI art. 290 A; décret n° 2022-1299 du 7 oct. 2022', '2026-09-01');

COMMENT ON COLUMN statutory_returns.frequency IS
    'DECADAL (three ten-day periods a month, French e-reporting), MONTHLY, QUARTERLY or ANNUAL. Stored; the due date is derived from it.';

-- What a business filed. Append-only: a correction is a new filing that supersedes its predecessor,
-- with both on the record, for the same reason an invoice is never edited.
CREATE TABLE statutory_filings (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenants (id),
    return_code    TEXT        NOT NULL,
    period_start   DATE        NOT NULL,
    period_end     DATE        NOT NULL,   -- exclusive, as every period here is
    filed_at       TIMESTAMPTZ NOT NULL,
    filed_by       UUID        NOT NULL,
    -- The authority's receipt. Null only where the authority gives none, which some do not.
    reference      TEXT,
    provider       TEXT        NOT NULL,   -- HMRC_MTD | MANUAL | SIMULATED
    -- SHA-256 of what was sent, so a filing can be proved against an export produced later. Base64,
    -- as every other digest on this platform is.
    payload_digest TEXT,
    -- The filing this one corrects, if it corrects one. Both stay on the record.
    supersedes     UUID        REFERENCES statutory_filings (id),
    -- And the pointer the other way, which is what makes "the current filing" expressible as an
    -- index. Setting it is not editing a filing: the content never changes, only whether this is
    -- still the one that stands — the same move as an invoice going VOID.
    --
    -- DEFERRABLE INITIALLY DEFERRED, and it has to be. Recording a correction is two statements in
    -- one transaction, and only one order works: the predecessor is marked FIRST, because until it
    -- is, uq_statutory_filing_period below already holds the period and refuses the insert. In that
    -- order the mark names a row that does not exist yet, so the key can only be checked at commit —
    -- by which time both rows are there. Making it immediate (or swapping the two statements) breaks
    -- one of the two, which is how this was found.
    superseded_by  UUID        REFERENCES statutory_filings (id) DEFERRABLE INITIALLY DEFERRED,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_statutory_filing_period CHECK (period_end > period_start),
    CONSTRAINT ck_statutory_filing_provider CHECK (provider IN ('HMRC_MTD', 'MANUAL', 'SIMULATED')),
    -- A filing cannot correct or be corrected by itself.
    CONSTRAINT ck_statutory_filing_supersedes CHECK (supersedes IS NULL OR supersedes <> id),
    CONSTRAINT ck_statutory_filing_superseded_by CHECK (superseded_by IS NULL OR superseded_by <> id)
);

-- One filing per period per return per business that still stands.
--
-- Keyed on superseded_by and not on supersedes: the filing that stands is the one nothing has
-- corrected, and a first attempt at the index had it the other way about — which would have kept the
-- *superseded* row as the live one and let corrections pile up without limit.
CREATE UNIQUE INDEX uq_statutory_filing_period
    ON statutory_filings (tenant_id, return_code, period_start)
    WHERE superseded_by IS NULL;

CREATE INDEX idx_statutory_filings_tenant
    ON statutory_filings (tenant_id, return_code, period_start DESC);

COMMENT ON TABLE statutory_returns IS
    'What each jurisdiction requires and when. Reference data: the law, not a tenant preference.';
COMMENT ON TABLE statutory_filings IS
    'Evidence that a return went. Append-only; a correction supersedes and both stay on the record.';
