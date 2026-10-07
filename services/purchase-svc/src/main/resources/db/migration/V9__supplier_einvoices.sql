-- Structured supplier e-invoices received (readiness review 07.13).
--
-- A supplier invoice is keyed into a form, or it arrives as a structured e-invoice. Since 1 January
-- 2025 every German business must be able to receive an EN 16931 e-invoice (UStG §14); Belgian
-- businesses have exchanged invoices over Peppol since 1 January 2026; France's reform has every
-- business receiving them from 1 September 2026; and Poland's KSeF carries every invoice from 2026. A
-- supplier there may send nothing else, so a tenant that can only key invoices cannot be invoiced at all.
--
-- A received document is read by shared/einvoice — UBL, CII, FA(3), or the CII inside a Factur-X or
-- ZUGFeRD PDF — and checked against CEN's and Peppol's rules. It is then matched to a supplier, a
-- purchase order and the order's lines, and captured through the same three-way match a keyed invoice
-- goes through; a credit note closes the return it credits. What cannot be matched waits here, saying
-- why, for a person to finish, and what the person decides about a supplier's item codes is kept so
-- the next invoice matches on its own.
--
-- Delivery in over a network. A Peppol access point pushes what it received over AS4, France's
-- approved platform hands over what was deposited for the business, and the simulated provider on
-- this platform delivers straight into the receiver's inbox when the receiver is a business here. The
-- channel says which network it came through, and the network's own reference for the delivery is
-- kept beside the document, so a question from the network can be answered by it. KSeF is not a
-- delivery network: a Polish buyer fetches its invoices from the system (see V10).
--
-- The document is kept exactly as it arrived. For an e-invoice the file is the invoice: the laws
-- that make a business accept one make it keep that file, unaltered, for as long as the invoice is
-- kept (UStG §14b, CGI art.289, VAT Act 1994 Sch.11 para.6). A rendering of it is not a copy.
--
-- A supplier's electronic address (suppliers.einvoice_scheme / einvoice_id) and VAT number are on
-- suppliers (V1), where the e-invoice matching looks them up.

-- Every e-invoice received, once per document. The same bytes sent twice are one document.
CREATE TABLE supplier_einvoices (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL,
    received_by         UUID,
    channel             TEXT        NOT NULL,      -- UPLOAD, a delivering network, or KSEF (fetched, V10)
    -- The network's own reference for the delivery; null for an upload.
    delivery_ref        TEXT,
    content_type        TEXT        NOT NULL,
    container           TEXT        NOT NULL,      -- XML, or PDF for a Factur-X/ZUGFeRD hybrid
    syntax              TEXT        NOT NULL,      -- UBL | CII | FA3 (FA(3), Poland's KSeF structure)
    embedded_filename   TEXT,                      -- the PDF attachment the XML was found under
    document            BYTEA       NOT NULL,
    document_sha256     TEXT        NOT NULL,
    customization_id    TEXT,                      -- BT-24
    type_code           TEXT,                      -- BT-3: 380 invoice, 381 credit note …
    invoice_number      TEXT,                      -- BT-1
    issue_date          DATE,                      -- BT-2
    currency            TEXT,                      -- BT-5
    seller_name         TEXT,                      -- BT-27
    seller_vat_id       TEXT,                      -- BT-31
    seller_endpoint     TEXT,                      -- BT-34 as scheme:identifier
    buyer_vat_id        TEXT,                      -- BT-48
    buyer_endpoint      TEXT,                      -- BT-49 as scheme:identifier
    order_reference     TEXT,                      -- BT-13
    preceding_invoice   TEXT,                      -- BT-25
    net_amount          NUMERIC,                   -- BT-109
    vat_amount          NUMERIC,                   -- BT-110
    gross_amount        NUMERIC,                   -- BT-112
    payable_amount      NUMERIC,                   -- BT-115
    violations          JSONB       NOT NULL,      -- [{rule, severity, message}] as checked on arrival
    status              TEXT        NOT NULL,
    problem             TEXT,                      -- what a person has to decide, while it waits
    supplier_id         UUID,
    po_id               UUID,
    supplier_invoice_id UUID,                      -- CAPTURED: the invoice it became
    vendor_return_id    UUID,                      -- CREDITED: the return its credit note closed
    decided_at          TIMESTAMPTZ,
    decided_by          UUID,
    decision_reason     TEXT,
    -- Held while one request captures or matches the document, so two people pressing Match at
    -- once make one invoice, and a claim left by a crash lapses after two minutes.
    claim_token         UUID,
    claimed_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_supplier_einvoice_document UNIQUE (tenant_id, document_sha256),
    CONSTRAINT chk_supplier_einvoice_status CHECK (status IN (
        'NOT_COMPLIANT',   -- breaks a fatal EN 16931 or Peppol rule; kept, not captured
        'MISDIRECTED',     -- addressed to another business
        'NEEDS_SUPPLIER',  -- no supplier has its electronic address or VAT identifier
        'NEEDS_ORDER',     -- no open purchase order of that supplier's is referenced
        'NEEDS_LINES',     -- at least one line is not yet a line of the order
        'NEEDS_RETURN',    -- a credit note with no single return to close
        'DUPLICATE',       -- that supplier's invoice number is already captured
        'NEEDS_DECISION',  -- matched, but the capture was refused: a closed period, another currency
        'CAPTURED',
        'CREDITED',
        'REFUSED')),
    CONSTRAINT chk_supplier_einvoice_channel CHECK (channel IN ('UPLOAD', 'PEPPOL', 'FR_PDP', 'SIMULATED', 'KSEF')),
    CONSTRAINT chk_supplier_einvoice_container CHECK (container IN ('XML', 'PDF')),
    CONSTRAINT chk_supplier_einvoice_syntax CHECK (syntax IN ('UBL', 'CII', 'FA3'))
);
CREATE INDEX idx_supplier_einvoices_status
    ON supplier_einvoices (tenant_id, status, received_at DESC);
CREATE INDEX idx_supplier_einvoices_supplier
    ON supplier_einvoices (tenant_id, supplier_id, invoice_number);

-- Each line as the supplier sent it, and the order line it was matched to.
CREATE TABLE supplier_einvoice_lines (
    id                   UUID        PRIMARY KEY,
    tenant_id            UUID        NOT NULL,
    einvoice_id          UUID        NOT NULL REFERENCES supplier_einvoices(id),
    position             INT         NOT NULL,
    line_id              TEXT,                     -- BT-126
    item_name            TEXT,                     -- BT-153
    sellers_item_id      TEXT,                     -- BT-155
    buyers_item_id       TEXT,                     -- BT-156
    standard_item_id     TEXT,                     -- BT-157 as scheme:identifier
    order_line_reference TEXT,                     -- BT-132
    quantity             NUMERIC,                  -- BT-129
    unit_code            TEXT,                     -- BT-130
    net_amount           NUMERIC,                  -- BT-131
    net_price            NUMERIC,                  -- BT-146
    vat_category         TEXT,                     -- BT-151
    vat_rate             NUMERIC,                  -- BT-152
    po_line_id           UUID,
    variant_id           UUID,
    matched_by           TEXT,                     -- ORDER_LINE | ITEM_CODE | PERSON
    CONSTRAINT uq_supplier_einvoice_line UNIQUE (tenant_id, einvoice_id, position),
    CONSTRAINT chk_supplier_einvoice_line_match CHECK (matched_by IS NULL OR matched_by IN ('ORDER_LINE', 'ITEM_CODE', 'PERSON'))
);
CREATE INDEX idx_supplier_einvoice_lines_invoice
    ON supplier_einvoice_lines (tenant_id, einvoice_id, position);

-- What a supplier's own item codes mean here, learned when a person matches a line: the next
-- invoice carrying the same code matches without asking.
CREATE TABLE supplier_item_codes (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    supplier_id  UUID        NOT NULL REFERENCES suppliers(id),
    kind         TEXT        NOT NULL,             -- SELLER (BT-155) | BUYER (BT-156) | STANDARD (BT-157)
    code         TEXT        NOT NULL,
    variant_id   UUID        NOT NULL,
    learned_from UUID        REFERENCES supplier_einvoices(id),
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_supplier_item_code UNIQUE (tenant_id, supplier_id, kind, code),
    CONSTRAINT chk_supplier_item_code_kind CHECK (kind IN ('SELLER', 'BUYER', 'STANDARD'))
);
CREATE INDEX idx_supplier_item_codes_variant
    ON supplier_item_codes (tenant_id, supplier_id, variant_id);
