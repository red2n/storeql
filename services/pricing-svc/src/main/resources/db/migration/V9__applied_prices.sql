-- The prior price of a price reduction.
--
-- Directive 98/6/EC art.6a (inserted by Directive (EU) 2019/2161, applying since 28 May 2022): an
-- announcement of a price reduction states the prior price, the lowest price applied during a period
-- of at least 30 days before the reduction; for a progressively increasing reduction, where a member
-- state allows it, the price before its first application. A list price is overwritten in place, and
-- so are VAT rates and a product's VAT category, so what a shopper was charged last month cannot be
-- reconstructed from the definitions.
--
-- So the prices actually applied are recorded, as the price engine itself computes them. A change
-- that can move a price queues an evaluation in the same transaction as the change; a promotion or
-- price list that starts or ends on a schedule queues one for that moment; a worker evaluates each,
-- in order for each variant, and appends a row only when what a shopper is offered changed.
--
-- A worker runs after the change commits. Price lists, promotions, their scopes and their switches
-- carry when they were made and thrown, and list prices keep their history below, so an evaluation
-- reads those as they stood at its moment. A VAT rate, a VAT category and the catalogue are still
-- overwritten in place: when one of those changed again before the worker ran, what the shopper saw
-- at the evaluated moment cannot be known, and the row says so. No reduction whose 30 days cross such
-- a span is announced.

-- Every price a list item has carried, from when. Appended with each change to the item.
CREATE TABLE price_list_item_prices (
    id                 UUID           PRIMARY KEY,
    tenant_id          UUID           NOT NULL,
    price_list_item_id UUID           NOT NULL REFERENCES price_list_items (id),
    price              NUMERIC        NOT NULL CHECK (price >= 0),
    valid_from         TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_price_list_item_prices_item
    ON price_list_item_prices (tenant_id, price_list_item_id, valid_from DESC, id DESC);

CREATE TABLE applied_prices (
    id              UUID           PRIMARY KEY,
    tenant_id       UUID           NOT NULL,
    variant_id      UUID           NOT NULL,
    channel         TEXT           NOT NULL CHECK (channel IN ('ONLINE', 'POS')),
    -- Null for the business-wide offer; a store only where a store-scoped promotion applies.
    store_id        UUID,
    -- False while the variant has no price in force: a gap ends a run of reductions.
    priced          BOOLEAN        NOT NULL,
    -- What the shopper is offered, VAT and item-level promotions included.
    price           NUMERIC        CHECK (price >= 0),
    -- The same before VAT, for a till that shows prices net of it.
    net_price       NUMERIC        CHECK (net_price >= 0),
    -- The offer without promotions: the reduction is price against this.
    regular_price   NUMERIC        CHECK (regular_price >= 0),
    promotion_name  TEXT,
    currency        TEXT           CHECK (currency ~ '^[A-Z]{3}$'),
    applied_from    TIMESTAMPTZ    NOT NULL,
    -- Null when the row is certain. Otherwise the moment from which what was offered cannot be
    -- known, until the next row.
    uncertain_since TIMESTAMPTZ,
    recorded_at     TIMESTAMPTZ    NOT NULL,
    cause           TEXT           NOT NULL CHECK (char_length(cause) BETWEEN 1 AND 60),
    CONSTRAINT chk_applied_price_priced CHECK (
        priced = (price IS NOT NULL) AND priced = (net_price IS NOT NULL)
        AND priced = (regular_price IS NOT NULL) AND priced = (currency IS NOT NULL)),
    CONSTRAINT chk_applied_price_uncertain CHECK (
        uncertain_since IS NULL OR uncertain_since <= applied_from)
);
CREATE INDEX idx_applied_prices_key
    ON applied_prices (tenant_id, variant_id, channel, store_id, applied_from DESC, id DESC);
CREATE INDEX idx_applied_prices_recorded ON applied_prices (tenant_id, recorded_at DESC);

-- Append-only, enforced where it cannot be skipped: a prior price read from a history that can be
-- edited is a claim, not a record. The same holds for the list prices it is rebuilt from.
-- The one delete allowed is erasure. When a departed business's retrieval period ends (EU Data Act
-- art.25(2)(h)), its rows are erased in the transaction that names it in
-- storeql.erasing_tenant, and only that business's rows can go.
CREATE FUNCTION applied_prices_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.tenant_id::text = current_setting('storeql.erasing_tenant', true) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$$;
CREATE TRIGGER trg_applied_prices_append_only
    BEFORE UPDATE OR DELETE ON applied_prices
    FOR EACH ROW EXECUTE FUNCTION applied_prices_append_only();
CREATE TRIGGER trg_price_list_item_prices_append_only
    BEFORE UPDATE OR DELETE ON price_list_item_prices
    FOR EACH ROW EXECUTE FUNCTION applied_prices_append_only();

-- How far each key of the ledger has been evaluated. An evaluation older than that was made visible
-- after a newer one had already been worked, so what it finds is recorded as uncertain.
CREATE TABLE applied_price_marks (
    id                UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL,
    variant_id        UUID        NOT NULL,
    channel           TEXT        NOT NULL CHECK (channel IN ('ONLINE', 'POS')),
    store_id          UUID,
    evaluated_through TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_applied_price_marks_key
    ON applied_price_marks (tenant_id, variant_id, channel, store_id);

CREATE TABLE price_evaluations (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    -- Null evaluates every priced variant of the tenant.
    variant_id  UUID,
    -- The moment the price is evaluated as of: the change's own transaction time, or the boundary
    -- of a schedule, never earlier than the change that queued it.
    as_of       TIMESTAMPTZ NOT NULL,
    -- When a worker may take it: as_of at first, then pushed on while a worker holds it, so an
    -- evaluation that dies is taken again rather than lost.
    due_at      TIMESTAMPTZ NOT NULL,
    cause       TEXT        NOT NULL CHECK (char_length(cause) BETWEEN 1 AND 60),
    -- True when the change overwrote something the price engine cannot read as of an earlier
    -- moment: while it waits, an earlier evaluation of the same variant cannot be certain.
    overwrites  BOOLEAN     NOT NULL,
    enqueued_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_price_evaluations_due ON price_evaluations (due_at, tenant_id);
CREATE INDEX idx_price_evaluations_tenant ON price_evaluations (tenant_id, as_of, id);
