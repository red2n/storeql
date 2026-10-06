-- Where a business's invoices leave (the e-invoicing transport seam behind 07.13 and 18.9).
--
-- An EN 16931 document is only the format; the mandates name a network: Peppol (Belgium since 2026,
-- Germany from 2027, the UK from 2029), France's approved platforms (from Sep 2026), Poland's KSeF
-- (2026) and India's Invoice Registration Portal. A business chooses one, and a provider behind it:
-- SIMULATED (this platform standing in for the network, for a stack with no contract) or a real one
-- with the platform's credentials. Nothing here is a contract with a certified provider.
CREATE TABLE einvoice_transport_settings (
    tenant_id        UUID        NOT NULL,
    network          TEXT        NOT NULL,   -- NONE | PEPPOL | FR_PDP | KSEF | IRP
    provider         TEXT,                   -- SIMULATED | ACCESS_POINT …; none when NONE
    provider_account TEXT,                   -- the business at the provider: a legal-entity id, a NIP, an IRP user
    -- A provider that takes the business's own credentials — India's portal wants the taxpayer's API user
    -- and password, KSeF an authorisation token — keeps them here, sealed under the deployment's key
    -- (storeql.einvoice.secrets-key, AES-GCM) and never reads them back over the API. Nothing is stored
    -- until the key exists: a deployment without one cannot choose such a provider.
    provider_secret  TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    CONSTRAINT pk_einvoice_transport_settings PRIMARY KEY (tenant_id),
    CONSTRAINT chk_einvoice_transport_network  CHECK (network IN ('NONE', 'PEPPOL', 'FR_PDP', 'KSEF', 'IRP')),
    CONSTRAINT chk_einvoice_transport_provider CHECK ((network = 'NONE') = (provider IS NULL))
);

-- Every attempt to send a document, and what the network said. Queued when the document is issued,
-- sent by a worker that takes due rows under SKIP LOCKED, so two instances never send one twice;
-- tried again after a failed connection, never after the network's own refusal. A document is sent
-- again only after a rejection or a failure, as a new row: the answers already given are kept.
CREATE TABLE sales_invoice_transmissions (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    invoice_id      UUID        NOT NULL REFERENCES sales_invoices (id),
    network         TEXT        NOT NULL,
    provider        TEXT        NOT NULL,
    status          TEXT        NOT NULL,   -- QUEUED | SENDING | PENDING | ACCEPTED | REJECTED | FAILED
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    receiver        TEXT,                   -- scheme:identifier the document was addressed to, when the network needs one
    provider_ref    TEXT,                   -- the network's reference: a message id, a KSeF number, an IRN
    detail          TEXT,                   -- the last outcome, in words
    response        JSONB,                  -- the network's last answer, as it came
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    created_by      UUID,
    CONSTRAINT chk_transmission_status CHECK (status IN ('QUEUED', 'SENDING', 'PENDING', 'ACCEPTED', 'REJECTED', 'FAILED'))
);
-- One document is in the network's hands, or delivered, at most once at a time: a second send while
-- one is open or accepted is refused by the database, however many ask at once.
CREATE UNIQUE INDEX uq_transmission_active ON sales_invoice_transmissions (tenant_id, invoice_id)
    WHERE status IN ('QUEUED', 'SENDING', 'PENDING', 'ACCEPTED');
CREATE INDEX idx_transmissions_due     ON sales_invoice_transmissions (next_attempt_at) WHERE status IN ('QUEUED', 'PENDING');
CREATE INDEX idx_transmissions_invoice ON sales_invoice_transmissions (tenant_id, invoice_id, created_at DESC, id);
CREATE INDEX idx_transmissions_tenant  ON sales_invoice_transmissions (tenant_id, created_at DESC, id);
