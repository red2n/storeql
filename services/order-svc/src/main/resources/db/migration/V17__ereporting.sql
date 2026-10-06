-- E-reporting: the transactions an invoice does not cover (closing the code half of 18.9).
--
-- France's reform has two limbs. 18.9 built the first: a structured invoice for a business buyer,
-- sent over the network the business chose. The second is e-reporting — the sales an invoice does NOT
-- cover, reported to the administration through the same platform. A shop selling to shoppers issues
-- no e-invoices at all and still owes a report three times a month, so a platform that built only the
-- invoice limb leaves a French retailer in breach from 1 Sep 2026.
--
-- The calendar is tenant-svc's (07.14): the periods are derived there from a frequency and an offset,
-- and a business records what it filed there. This table is the other half — the DATA, built from
-- this service's own sales, kept as it was transmitted, and the network's answer beside it.
CREATE TABLE ereporting_submissions (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    -- The return this answers, as tenant-svc's calendar names it: EREPORTING_TX_FR for transaction
    -- data, EREPORTING_PAY_FR for payment data. Two streams, the same cadence, different content.
    return_code       TEXT          NOT NULL,
    period_start      DATE          NOT NULL,
    period_end        DATE          NOT NULL,   -- exclusive, as every period on this platform is
    currency          CHAR(3)       NOT NULL,
    -- What the period came to. Kept beside the payload so a list reads without parsing documents.
    transaction_count INTEGER       NOT NULL,
    net_total         NUMERIC(18,2) NOT NULL,
    vat_total         NUMERIC(18,2) NOT NULL,
    -- The document as transmitted, byte for byte, and its digest. The digest is what a business puts
    -- on the filing it records in tenant-svc, so the filing and the bytes can be tied together later
    -- without tenant-svc holding a copy of this service's data.
    payload           TEXT          NOT NULL,
    payload_digest    TEXT          NOT NULL,
    network           TEXT          NOT NULL,
    provider          TEXT          NOT NULL,
    status            TEXT          NOT NULL,
    detail            TEXT,
    provider_ref      TEXT,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    created_by        UUID          NOT NULL,
    transmitted_at    TIMESTAMPTZ,
    -- A correction is a new submission that supersedes its predecessor, both on the record — the same
    -- rule as an invoice and a statutory filing. Deferred, because the predecessor must be marked in
    -- the same statement order that the partial unique index below allows.
    supersedes        UUID          REFERENCES ereporting_submissions (id) DEFERRABLE INITIALLY DEFERRED,
    superseded_by     UUID          REFERENCES ereporting_submissions (id) DEFERRABLE INITIALLY DEFERRED,

    CONSTRAINT ck_ereporting_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_ereporting_period CHECK (period_end > period_start),
    CONSTRAINT ck_ereporting_counts CHECK (transaction_count >= 0),
    CONSTRAINT ck_ereporting_self   CHECK (supersedes IS NULL OR supersedes <> id)
);

-- One standing submission per period, stream and CURRENCY. A correction supersedes rather than
-- replaces, so the index counts only what stands — the same shape as a statutory filing and a
-- planogram. Currency is part of the key because a business that took money in two currencies owes a
-- report for each: folding them into one figure would report a sum that is not an amount of anything.
CREATE UNIQUE INDEX uq_ereporting_period
    ON ereporting_submissions (tenant_id, return_code, period_start, currency)
    WHERE superseded_by IS NULL;

CREATE INDEX idx_ereporting_tenant
    ON ereporting_submissions (tenant_id, period_start DESC, return_code);

COMMENT ON TABLE ereporting_submissions IS
    'What was reported for a period and what the network said. Immutable: a correction supersedes.';
COMMENT ON COLUMN ereporting_submissions.payload_digest IS
    'SHA-256 of the payload, base64 — what the business puts on the filing it records in tenant-svc.';
