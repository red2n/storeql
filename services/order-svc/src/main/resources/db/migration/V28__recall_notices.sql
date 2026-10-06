-- Recall notice to buyers (readiness review 05.10).
--
--   Regulation (EU) 2023/988 art.35   directly notify every affected consumer who can be identified
--   art.36                            what the notice says; art.37 the remedy the consumer chooses
--
-- inventory-svc finds the sales that drew on a recalled pack and announces each order. This service
-- knows who placed the order — the login, the shop's customer record, or only a phone number left
-- with a guest checkout — so it is where the notice to that buyer lives: issued, the remedy they
-- chose, and how it was settled. The notice carries the recall's text as it stood, so what the
-- buyer was told never changes under them.

CREATE TABLE recall_notices (
    id                   UUID          PRIMARY KEY,
    tenant_id            UUID          NOT NULL,
    recall_id            UUID          NOT NULL,
    reference            TEXT          NOT NULL,
    hazard               TEXT          NOT NULL,
    reason               TEXT          NOT NULL,
    customer_notice      TEXT          NOT NULL,
    -- Comma-separated, as the recall offered them.
    remedies             TEXT          NOT NULL,
    single_remedy_reason TEXT,
    contact_phone        TEXT,
    contact_url          TEXT,
    order_id             UUID          NOT NULL,
    store_id             UUID          NOT NULL,
    channel              TEXT          NOT NULL,
    customer_id          UUID,
    login_id             UUID,
    -- The number a guest left at checkout: the only way to reach a buyer with no account.
    buyer_phone          TEXT,
    sold_at              TIMESTAMPTZ   NOT NULL,
    -- ISSUED: someone to tell, and told. UNIDENTIFIED: an anonymous till sale, kept for the count
    -- and for the buyer who comes back with the receipt.
    status               TEXT          NOT NULL,
    -- Whether the order named anyone the notice could be written to, fixed when the notice was issued. It
    -- is not read off the status, which moves: an anonymous till sale that staff settle at the counter stops
    -- being UNIDENTIFIED, and the count of buyers told would grow by one who was never written to. The
    -- number a buyer left is forgotten with their erasure; that they were told is not.
    buyer_identified     BOOLEAN       NOT NULL,
    issued_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    remedy               TEXT,
    remedy_chosen_at     TIMESTAMPTZ,
    remedy_chosen_by     UUID,
    remedy_chosen_via    TEXT,
    resolution           TEXT,
    resolved_at          TIMESTAMPTZ,
    resolved_by          UUID,
    return_id            UUID,
    resolution_notes     TEXT,
    CONSTRAINT uq_recall_notice_order UNIQUE (tenant_id, recall_id, order_id),
    CONSTRAINT chk_recall_notice_remedies CHECK (
        remedies ~ '^(REPAIR|REPLACEMENT|REFUND)(,(REPAIR|REPLACEMENT|REFUND))*$'),
    CONSTRAINT chk_recall_notice_status CHECK (
        status IN ('ISSUED', 'UNIDENTIFIED', 'REMEDY_CHOSEN', 'RESOLVED')),
    CONSTRAINT chk_recall_notice_remedy CHECK (
        remedy IS NULL OR remedy IN ('REPAIR', 'REPLACEMENT', 'REFUND')),
    CONSTRAINT chk_recall_notice_chosen CHECK (
        (remedy IS NULL) = (remedy_chosen_at IS NULL) AND (remedy IS NULL) = (remedy_chosen_via IS NULL)),
    CONSTRAINT chk_recall_notice_via CHECK (
        remedy_chosen_via IS NULL OR remedy_chosen_via IN ('SHOPPER', 'STAFF')),
    CONSTRAINT chk_recall_notice_resolution CHECK (
        resolution IS NULL OR resolution IN ('REFUNDED', 'REPLACED', 'REPAIRED', 'DECLINED')),
    CONSTRAINT chk_recall_notice_resolved CHECK (
        (status = 'RESOLVED') = (resolution IS NOT NULL) AND (resolution IS NULL) = (resolved_at IS NULL))
);
CREATE INDEX idx_recall_notices_recall ON recall_notices (tenant_id, recall_id, issued_at DESC, id DESC);
CREATE INDEX idx_recall_notices_login ON recall_notices (tenant_id, login_id, issued_at DESC)
    WHERE login_id IS NOT NULL;
CREATE INDEX idx_recall_notices_customer ON recall_notices (tenant_id, customer_id)
    WHERE customer_id IS NOT NULL;

-- What the buyer bought that the recall covers, as inventory-svc matched it. Append-only.
CREATE TABLE recall_notice_lines (
    id           UUID          PRIMARY KEY,
    tenant_id    UUID          NOT NULL,
    notice_id    UUID          NOT NULL REFERENCES recall_notices (id),
    variant_id   UUID          NOT NULL,
    -- As product-svc named it when the notice was issued; null when it could not be asked.
    product_name TEXT,
    sku          TEXT,
    batch_no     TEXT,
    expiry_date  DATE,
    qty          NUMERIC(18,3) NOT NULL,
    match_type   TEXT          NOT NULL,
    CONSTRAINT chk_recall_notice_line_match CHECK (
        match_type IN ('IN_SCOPE', 'LOT_UNKNOWN', 'DATE_UNKNOWN'))
);
CREATE INDEX idx_recall_notice_lines_notice ON recall_notice_lines (tenant_id, notice_id);
