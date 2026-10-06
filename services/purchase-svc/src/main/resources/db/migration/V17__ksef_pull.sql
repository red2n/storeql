-- Poland: a buyer fetches its invoices, and they arrive as FA(3) (readiness review 07.13).
--
-- Every other network on this platform DELIVERS: an access point pushes what it received, France's
-- platform hands over what was deposited. KSeF does neither. A Polish buyer's invoices sit in the
-- ministry's system until the buyer asks for them, and what comes back is FA(3) rather than an EN
-- 16931 document — KSeF takes and gives only its own structure.
--
-- Two consequences, and both are here:
--   * FA(3) is a syntax this inbox accepts (supplier_einvoices.syntax, V15). shared/einvoice's
--     Fa3Reader reads it into the same model a UBL or CII document produces, so the checks, the
--     supplier matching, the three-way match and the posting all work on a Polish invoice without
--     knowing it is one.
--   * the inbox needs a credential of its own. Sending is order-svc's; fetching is this service's, and
--     the business's KSeF token is sealed here under the deployment's key, never shown again.

-- Where this business fetches from, and what it signs in with. One row per business.
CREATE TABLE einvoice_inbox_settings (
    tenant_id       UUID        PRIMARY KEY,
    -- KSEF today: the only network that is asked rather than delivered from. NONE turns fetching off
    -- without losing the credential, which is what a business wants while it sorts out its token.
    network         TEXT        NOT NULL,
    provider        TEXT        NOT NULL,
    -- The business at the network: its NIP for KSeF.
    provider_account TEXT,
    -- The business's own credential, sealed under storeql.einvoice.secrets-key. Never read back out
    -- over HTTP: the settings say only whether one is held.
    provider_secret TEXT,
    -- How far the last fetch got, so the next one asks for what it has not seen. A window and not a
    -- cursor, because KSeF is asked by date range and a range is what can be asked again safely.
    fetched_to      DATE,
    last_fetch_at   TIMESTAMPTZ,
    last_fetch_note TEXT,
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      UUID,

    CONSTRAINT ck_inbox_network  CHECK (network IN ('NONE', 'KSEF')),
    CONSTRAINT ck_inbox_provider CHECK (provider IN ('NONE', 'SIMULATED', 'KSEF'))
);

COMMENT ON TABLE einvoice_inbox_settings IS
    'Where a business fetches its invoices from, for a network that is asked rather than delivered from. One row per business.';
COMMENT ON COLUMN einvoice_inbox_settings.provider_secret IS
    'The business''s KSeF token, sealed under the deployment''s key. Never shown again.';
COMMENT ON COLUMN einvoice_inbox_settings.fetched_to IS
    'The day the last fetch reached. The next asks from here, so nothing is missed and a repeat is harmless.';
