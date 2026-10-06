-- Making Tax Digital for VAT — the digital link from the computed return to HMRC.
--
-- MTD's rule is that the nine boxes reach HMRC from the records that produced them with no
-- re-keying. The return is computed here from tax_transactions (boxes 1 and 6) and
-- input_tax_transactions (boxes 4 and 7). Box 3 is box 1 plus box 2, box 5 is box 3 less box 4 made
-- absolute, and boxes 2, 8 and 9 are zero in that computation. This file holds the registration
-- that names the VAT number the return is filed under and the provider it is filed through, and an
-- append-only record of every submission and what HMRC answered.

-- One registration per tenant. The tokens HMRC's OAuth grant issues for the taxpayer are held
-- encrypted with a key from configuration; SIMULATED needs none.
CREATE TABLE vat_registrations (
    tenant_id             UUID PRIMARY KEY,
    vrn                   TEXT NOT NULL,           -- nine digits with HMRC's mod-97 check
    provider              TEXT NOT NULL,           -- SIMULATED | HMRC
    hmrc_access_token     TEXT,                    -- AES-GCM, base64; NULL until connected
    hmrc_refresh_token    TEXT,
    hmrc_token_expires_at TIMESTAMPTZ,
    connected_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID,
    CONSTRAINT chk_vat_registration_provider CHECK (provider IN ('SIMULATED', 'HMRC'))
);

-- Every return filed, as filed. Append-only: a filed return is a legal record, and only tenant
-- erasure (21.14) deletes one; a correction is HMRC's error-correction process, never an edit
-- here. The nine boxes are stored as sent. Boxes 1 to 5 are to the penny and boxes 6 to 9 are whole
-- pounds, as HMRC's MTD VAT return requires. The return is in pounds under UK law, so these columns
-- keep NUMERIC(18,2) whatever the business's currency. The figures are the tax transactions'
-- amounts summed as recorded: nothing converts them to pounds. MtdService.submit checks neither
-- the business's currency nor its country, so a business that is not in the UK can register and
-- file its own currency's sums as pounds. That is a known gap (CLAUDE.md: UK-only law applies only
-- to a business that uses it), not a design.
CREATE TABLE vat_return_submissions (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    vrn                TEXT NOT NULL,
    period_key         TEXT NOT NULL,               -- HMRC's key for the obligation, e.g. 18A1
    period_from        TIMESTAMPTZ NOT NULL,
    period_to          TIMESTAMPTZ NOT NULL,
    box1 NUMERIC(18,2) NOT NULL, box2 NUMERIC(18,2) NOT NULL, box3 NUMERIC(18,2) NOT NULL,
    box4 NUMERIC(18,2) NOT NULL, box5 NUMERIC(18,2) NOT NULL, box6 NUMERIC(18,2) NOT NULL,
    box7 NUMERIC(18,2) NOT NULL, box8 NUMERIC(18,2) NOT NULL, box9 NUMERIC(18,2) NOT NULL,
    finalised          BOOLEAN NOT NULL,
    provider           TEXT NOT NULL,
    status             TEXT NOT NULL,               -- ACCEPTED | REJECTED
    submitted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_by       UUID,
    processing_date    TIMESTAMPTZ,                 -- what HMRC answered
    form_bundle_number TEXT,
    payment_indicator  TEXT,
    charge_ref_number  TEXT,
    receipt_id         TEXT,                        -- HMRC's Receipt-ID header
    receipt_timestamp  TIMESTAMPTZ,
    error_code         TEXT,                        -- on REJECTED: HMRC's code
    error_message      TEXT,
    CONSTRAINT chk_vat_submission_status CHECK (status IN ('ACCEPTED', 'REJECTED'))
);

-- One accepted return per obligation: HMRC refuses the second with DUPLICATE_SUBMISSION, and so
-- does this table before the call is ever made.
CREATE UNIQUE INDEX uq_vat_submission_accepted
    ON vat_return_submissions (tenant_id, vrn, period_key) WHERE status = 'ACCEPTED';
CREATE INDEX idx_vat_submissions_tenant ON vat_return_submissions (tenant_id, submitted_at DESC);
