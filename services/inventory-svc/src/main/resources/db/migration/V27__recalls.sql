-- Product withdrawals and recalls.
--
--   Regulation (EC) 178/2002 art.19 (retained)   a food business that has reason to believe food
--                                                it sold is unsafe must withdraw it, and recall it
--                                                from consumers where it may have reached them
--   FSA, Guidance on food traceability, withdrawals and recalls within the UK food industry
--
-- A withdrawal takes stock off sale; a recall also tells the customers who may have bought it. Both
-- are opened against a scope — a variant, optionally narrowed to a lot and a date range — and both
-- need the same evidence afterwards: what was taken off sale, at which store, what was found on the
-- shelf, and what became of it. Everything below except the recall's own status is append-only.

CREATE TABLE recalls (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    -- The supplier's or the FSA's reference, so the notice and the record can be matched.
    reference        TEXT        NOT NULL,
    kind             TEXT        NOT NULL,
    hazard           TEXT        NOT NULL,
    reason           TEXT        NOT NULL,
    -- The point-of-sale notice a recall must display; a withdrawal has none.
    customer_notice  TEXT,
    source           TEXT        NOT NULL,
    source_reference TEXT,
    status           TEXT        NOT NULL DEFAULT 'OPEN',
    opened_by        UUID        NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_by         UUID,
    ended_at         TIMESTAMPTZ,
    end_notes        TEXT,
    -- What the notice offers and where to turn. A withdrawal carries none of it.
    remedies             TEXT,
    single_remedy_reason TEXT,
    contact_phone        TEXT,
    contact_url          TEXT,
    -- Sales on or after this day are looked for; NULL means every sale of the packs in scope.
    sold_from            DATE,
    CONSTRAINT chk_recall_kind CHECK (kind IN ('WITHDRAWAL', 'RECALL')),
    CONSTRAINT chk_recall_hazard CHECK (hazard IN (
        'MICROBIOLOGICAL', 'ALLERGEN', 'FOREIGN_BODY', 'CHEMICAL', 'LABELLING', 'QUALITY', 'OTHER')),
    -- Country-neutral sources sit beside the UK regulators (FSA, FSS): REGULATOR is whichever authority
    -- the business answers to (the notice's own reference says which); MANUFACTURER and SUPPLIER say who
    -- issued the notice; INTERNAL is found in house.
    CONSTRAINT chk_recall_source CHECK (source IN (
        'SUPPLIER', 'MANUFACTURER', 'REGULATOR', 'FSA', 'FSS', 'INTERNAL', 'OTHER')),
    CONSTRAINT chk_recall_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_recall_notice CHECK (kind <> 'RECALL' OR customer_notice IS NOT NULL),
    CONSTRAINT chk_recall_ended CHECK ((status = 'OPEN') = (ended_at IS NULL)),
    CONSTRAINT chk_recall_ended_by CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CONSTRAINT chk_recall_remedies CHECK (
        remedies IS NULL OR remedies ~ '^(REPAIR|REPLACEMENT|REFUND)(,(REPAIR|REPLACEMENT|REFUND))*$')
);
-- A recall offers a remedy and names a contact. NOT VALID: every row written, including an update, is
-- checked; a row already present when the check is added is not re-checked. CREATE TABLE accepts NOT
-- VALID but creates a validated constraint (PostgreSQL 16), so these two are added after the table.
ALTER TABLE recalls ADD CONSTRAINT chk_recall_offers_remedy CHECK (
    kind <> 'RECALL' OR remedies IS NOT NULL) NOT VALID;
ALTER TABLE recalls ADD CONSTRAINT chk_recall_names_contact CHECK (
    kind <> 'RECALL' OR contact_phone IS NOT NULL OR contact_url IS NOT NULL) NOT VALID;
-- One record per notice: a retried open finds the recall it already made instead of a second one.
CREATE UNIQUE INDEX uq_recalls_reference ON recalls (tenant_id, lower(reference));
CREATE INDEX idx_recalls_status ON recalls (tenant_id, status, opened_at DESC, id DESC);

CREATE TABLE recall_items (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    recall_id   UUID NOT NULL REFERENCES recalls (id),
    variant_id  UUID NOT NULL,
    -- NULL means every lot, and no dates means every date: a line naming neither covers every pack.
    batch_no    TEXT,
    expiry_from DATE,
    expiry_to   DATE,
    CONSTRAINT chk_recall_item_dates CHECK (expiry_from IS NULL OR expiry_to IS NULL OR expiry_from <= expiry_to)
);
CREATE INDEX idx_recall_items_recall ON recall_items (tenant_id, recall_id);
CREATE INDEX idx_recall_items_variant ON recall_items (tenant_id, variant_id);

-- Every batch a recall took off sale, and why. match_type says whether the batch is certainly in scope or
-- only could be, because its lot or date is not known: a pack nobody can rule out is withdrawn.
CREATE TABLE recall_batches (
    tenant_id             UUID          NOT NULL,
    recall_id             UUID          NOT NULL REFERENCES recalls (id),
    batch_id              UUID          NOT NULL REFERENCES inventory_batches (id),
    store_id              UUID          NOT NULL,
    variant_id            UUID          NOT NULL,
    match_type            TEXT          NOT NULL,
    qty_at_quarantine     NUMERIC(18,3) NOT NULL,
    -- The batch's status before any recall held it, restored if every holding recall lets it go.
    prior_material_status TEXT          NOT NULL,
    quarantined_on        TEXT          NOT NULL,
    quarantined_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_recall_batches PRIMARY KEY (tenant_id, recall_id, batch_id),
    CONSTRAINT chk_recall_batch_match CHECK (match_type IN ('IN_SCOPE', 'LOT_UNKNOWN', 'DATE_UNKNOWN')),
    CONSTRAINT chk_recall_batch_on CHECK (quarantined_on IN ('OPEN', 'ARRIVAL'))
);
CREATE INDEX idx_recall_batches_batch ON recall_batches (tenant_id, batch_id);
CREATE INDEX idx_recall_batches_store ON recall_batches (tenant_id, recall_id, store_id);

-- A pack checked and found not to be affected. Only a batch that could be in scope can be released;
-- one that certainly is stays withdrawn.
CREATE TABLE recall_batch_releases (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    recall_id   UUID        NOT NULL REFERENCES recalls (id),
    batch_id    UUID        NOT NULL REFERENCES inventory_batches (id),
    reason      TEXT        NOT NULL,
    released_by UUID        NOT NULL,
    released_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_recall_batch_release UNIQUE (tenant_id, recall_id, batch_id)
);

-- What a store found on its shelves and what became of it. The system quantity is kept beside the
-- counted one because the gap between them is the question a recall's close-out has to answer.
CREATE TABLE recall_store_actions (
    id               UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    recall_id        UUID          NOT NULL REFERENCES recalls (id),
    store_id         UUID          NOT NULL,
    qty_found        NUMERIC(18,3) NOT NULL,
    system_qty       NUMERIC(18,3) NOT NULL,
    disposition      TEXT          NOT NULL,
    notice_displayed BOOLEAN       NOT NULL,
    notes            TEXT,
    recorded_by      UUID          NOT NULL,
    recorded_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_recall_action_qty CHECK (qty_found >= 0),
    CONSTRAINT chk_recall_action_disposition CHECK (
        disposition IN ('HELD_FOR_COLLECTION', 'RETURNED_TO_SUPPLIER', 'DESTROYED'))
);
CREATE INDEX idx_recall_actions_store ON recall_store_actions (tenant_id, recall_id, store_id, recorded_at);
