-- product-svc schema: catalog (products, variants, categories tree, brands, media). README §9.3.
-- Every tenant table: tenant_id NOT NULL + an index starting with tenant_id. The one exception is
-- outbox, which is deliberately cross-tenant: the relay publishes every business's events in one
-- pass, in the order they were written, so none of its queries filters by tenant. Its tenant_id is
-- therefore nullable and not a lookup key: the only code that reads it is the relay's dead-letter
-- warning (BaseOutboxRepository.recordFailures), and none of the table's indexes starts with it.

-- ── brands ──────────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE brands (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    name       TEXT NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    -- A brand the business owns rather than buys. One flag, because own-brand changes how a line is
    -- treated at almost every step: margin is the business's own rather than a supplier's, a range
    -- review protects it against the brands beside it, a recall is the business's own responsibility,
    -- and a planogram usually guarantees it a facing at eye level. A boolean rather than a brand "kind",
    -- because every other distinction a shop draws (premium, value, exclusive) is a marketing label that
    -- changes, and this one is a fact about who owns the label.
    own_brand  BOOLEAN     NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_brands_tenant ON brands (tenant_id, name);
-- Partial indexes support fast "active only" queries without reading deleted rows.
CREATE INDEX idx_brands_tenant_active ON brands (tenant_id, name) WHERE status = 'ACTIVE';

COMMENT ON COLUMN brands.own_brand IS
    'True for a brand the business owns. Changes margin, range protection and recall responsibility.';

-- ── categories ──────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE categories (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    parent_id  UUID REFERENCES categories(id),   -- tree; NULL = root
    name       TEXT NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_categories_tenant ON categories (tenant_id, parent_id);
-- Partial indexes support fast "active only" queries without reading deleted rows.
CREATE INDEX idx_categories_tenant_active ON categories (tenant_id, parent_id) WHERE status = 'ACTIVE';

-- ── products ────────────────────────────────────────────────────────────────────────────────────────
--
-- A line is listed before it goes on sale, sells, is run down and is taken off. NEW_LINE (listed, not
-- yet on sale: a launch day may be named) and DISCONTINUED (still sold while stock lasts, never
-- reordered) complete the four, so a line being run down can be told from one on sale, and the till,
-- the shop and replenishment each act on it.

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    name            TEXT NOT NULL,
    description     TEXT,
    brand_id        UUID REFERENCES brands(id),
    category_id     UUID REFERENCES categories(id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE',     -- NEW_LINE | ACTIVE | DISCONTINUED | DELISTED
    launch_on       DATE,                               -- for a NEW_LINE: the day it is meant to go on sale
    discontinued_at TIMESTAMPTZ,                        -- when the line was marked for run-down
    sellable_online BOOLEAN NOT NULL DEFAULT true,
    sellable_pos    BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_products_status CHECK (status IN ('NEW_LINE', 'ACTIVE', 'DISCONTINUED', 'DELISTED'))
);
CREATE INDEX idx_products_tenant ON products (tenant_id, status, created_at DESC);
CREATE INDEX idx_products_tenant_category ON products (tenant_id, category_id);

-- ── the GTIN check digit ────────────────────────────────────────────────────────────────────────────
--
-- The one form a scanned code is matched against (product_variants.gtin14 below).
--
-- The same trade item is written four ways. A shop types the EAN-13 off the shelf edge; the case in
-- the stockroom carries the GTIN-14 of the same item; North American stock carries a UPC-A; a small
-- pack carries a GTIN-8. A GS1 2D code — DataMatrix or a Digital Link QR — always carries the
-- 14-digit form. So a till that compares strings decides that the packet in the customer's hand is a
-- different product from the one on the shelf edge, and finds nothing.
--
-- GS1's own rule is that the shorter forms ARE the 14-digit form with leading zeros. The 14-digit form
-- is a GENERATED column rather than a column the service writes, for one reason: it cannot drift. A
-- barcode corrected in the admin screen, a bulk import, a migration that touches the row — every one of
-- them updates the match key, because the database computes it. A column maintained in application code
-- would be right until the first write path that forgot it, and the symptom would be an item that scans
-- at one till and not at another.
--
-- The check digit is verified here too, so the column holds a GTIN or nothing. A barcode that is not
-- a GTIN — an internal code, a shelf label, a PLU — keeps working through the existing exact-match
-- lookup; it simply has no GTIN form, which is the truth about it.
--
-- The GS1 modulo-10 check, as a function so the generated column below is readable rather than
-- fourteen repetitions of lpad(). IMMUTABLE because a generated column may only call functions whose
-- answer depends on nothing but their arguments — which is true here and worth stating.
--
-- After padding to fourteen, the weights are fixed positions: counting from the check digit
-- backwards they alternate 3 and 1, which puts weight 3 on every odd position of the padded string
-- and 1 on every even one. Anchoring the weights on the left instead is correct for one GTIN length
-- and wrong for the other three — it reads EAN-13 and refuses every case code.
CREATE FUNCTION gs1_check_digit_holds(code TEXT) RETURNS BOOLEAN AS $$
    SELECT (
        10 - (
            3 * (
                substr(padded, 1, 1)::INT + substr(padded, 3, 1)::INT + substr(padded, 5, 1)::INT
              + substr(padded, 7, 1)::INT + substr(padded, 9, 1)::INT + substr(padded, 11, 1)::INT
              + substr(padded, 13, 1)::INT
            )
            + (
                substr(padded, 2, 1)::INT + substr(padded, 4, 1)::INT + substr(padded, 6, 1)::INT
              + substr(padded, 8, 1)::INT + substr(padded, 10, 1)::INT + substr(padded, 12, 1)::INT
            )
        ) % 10
    ) % 10 = substr(padded, 14, 1)::INT
    FROM (SELECT lpad(code, 14, '0') AS padded) AS p;
$$ LANGUAGE SQL IMMUTABLE STRICT;

COMMENT ON FUNCTION gs1_check_digit_holds(TEXT) IS
    'Whether a numeric GTIN of 8, 12, 13 or 14 digits satisfies the GS1 modulo-10 check digit.';

-- ── variants: the sellable unit, with its compliance, measure and scan attributes ───────────────────

CREATE TABLE product_variants (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    product_id           UUID NOT NULL REFERENCES products(id),
    sku                  TEXT NOT NULL,
    barcode              TEXT,
    attributes           TEXT,                          -- JSON string (e.g. {"size":"500g"})
    unit                 TEXT,                          -- e.g. EACH, KG, L
    -- Manufacturer part number. Allows cross-referencing a variant back to the manufacturer's own part
    -- number, which differs from sku (internal) and barcode (scan code).
    manufacturer_pn      TEXT,
    status               TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ISO 3166-1 alpha-2. Country of origin is mandatory for unprocessed meat, fruit and veg,
    -- fish, honey, olive oil and wine, and mandatory whenever its absence would mislead
    -- (EU 1169/2011 art.26).
    country_of_origin    CHAR(2),
    -- "Produce of Spain, packed in the UK" -- the sentence a label carries when one code cannot
    -- say it.
    origin_detail        TEXT,

    -- Which age rule applies (age_restriction_rules, V13), or NULL for the overwhelming majority that
    -- are unrestricted.
    restriction_category TEXT,

    -- Whether this is a food product at all, and whether its allergens have been stated.
    --
    -- This column is the whole safety argument. An empty allergen list must never be read as
    -- "free from" -- a tin of biscuits nobody has got round to declaring looks identical to one
    -- declared allergen-free, and the difference is a hospital admission. A food item is UNDECLARED
    -- until its allergens are stated; ComplianceRepository.markFoodUndeclared sets it when the variant
    -- is marked as food, so it is never silently safe to advertise. The default is NOT_APPLICABLE,
    -- because most of a catalogue is not food and defaulting everything to UNDECLARED would bury the
    -- real gaps under batteries and bin bags.
    allergen_status      TEXT        NOT NULL DEFAULT 'NOT_APPLICABLE',

    -- Ingredients, as printed. Required alongside the allergen list for prepacked food, and the
    -- source a declaration is checked against.
    ingredients          TEXT,

    -- How the item is sold. EACH is the default and covers nearly everything; WEIGHT is the loose
    -- produce, deli and butchery counter a supermarket cannot trade without.
    sold_by              TEXT        NOT NULL DEFAULT 'EACH',

    -- Net quantity in the pack, for the unit price a shelf edge must display
    -- (Price Marking Order 2004: price per kg / per litre alongside the selling price).
    net_content          NUMERIC(18,4),
    net_content_uom      TEXT,

    -- Packaging weight a scale deducts before pricing. Charging the customer for the tub is one
    -- of the things weights-and-measures inspection exists to catch.
    tare_weight          NUMERIC(18,4),

    -- True when every individual item has its own weight -- a joint of meat, a whole fish. The
    -- price is not knowable until the item is on the scale.
    catch_weight         BOOLEAN     NOT NULL DEFAULT FALSE,

    -- An item's HSN or SAC code.
    --
    -- India's e-invoice (FORM GST INV-01) names every line by its Harmonized System of Nomenclature
    -- code, or for a service its Services Accounting Code, and the Invoice Registration Portal refuses
    -- a line without one. It is a statement about the item, like its origin, so it sits with the other
    -- compliance attributes of the variant. Four, six or eight digits; NULL where nobody has classified
    -- the item, which only matters to a business that has to report it.
    hsn_code             TEXT,

    -- A version on a variant's measure. pricing-svc computes every unit price from the measure
    -- product-svc announces. Two saves of one variant a moment apart are published in outbox order,
    -- and outbox order is not commit order, so the older measure could arrive last and stand: a wrong
    -- unit price, silently. Each save takes the next version under the row's lock and the announcement
    -- carries it; pricing-svc keeps a measure only when its version is newer than the one it has.
    measure_version      BIGINT      NOT NULL DEFAULT 0 CHECK (measure_version >= 0),

    -- The drinks container a variant is sold in. A deposit return scheme charges a deposit on a drink
    -- by what it comes in: the material and the volume of the container (England and Northern Ireland
    -- from 1 October 2027, SI 2025/67: PET, steel and aluminium from 150 ml to 3 litres; Germany since
    -- 2003, Verpackungsgesetz §31). The amount is the scheme's, held as jurisdiction data in tenant-svc;
    -- the catalogue holds only the container, so the same product carries the right deposit in every
    -- country it is sold in.
    deposit_material     TEXT,       -- PET, ALUMINIUM, STEEL or GLASS; null when not a drinks container
    deposit_volume_ml   INTEGER,    -- the container's volume in millilitres

    -- The width a unit takes on a shelf. Nothing else in the catalogue carried this. Without it a
    -- planogram can be drawn but never checked: facings times width either fits the shelf or does not,
    -- and that is the one arithmetic a layout has to pass.
    --
    -- Nullable on purpose. Most catalogues have gaps, and a planogram nobody can save because one line
    -- has no measurement is worse than one whose width check covers what it can and says so. A position
    -- with no width is placed and not checked.
    facing_width_mm      INTEGER,

    -- The normalised GTIN, or NULL when the barcode is not a GTIN at all. Generated (see the GTIN check
    -- digit above), so it can never drift from barcode.
    gtin14               TEXT GENERATED ALWAYS AS (
        CASE
            WHEN barcode IS NULL THEN NULL
            WHEN barcode !~ '^[0-9]+$' THEN NULL
            WHEN length(barcode) NOT IN (8, 12, 13, 14) THEN NULL
            WHEN NOT gs1_check_digit_holds(barcode) THEN NULL
            ELSE lpad(barcode, 14, '0')
        END
    ) STORED,

    UNIQUE (tenant_id, sku),
    CONSTRAINT chk_variant_sold_by
        CHECK (sold_by IN ('EACH','WEIGHT','VOLUME','LENGTH')),
    CONSTRAINT chk_variant_allergen_status
        CHECK (allergen_status IN ('UNDECLARED','DECLARED','NOT_APPLICABLE')),
    -- A country code that is not two letters is a data-entry slip, and origin is a legal claim.
    CONSTRAINT chk_variant_origin
        CHECK (country_of_origin IS NULL OR country_of_origin ~ '^[A-Z]{2}$'),
    -- Selling by weight without saying which unit leaves the shelf edge unable to price it.
    CONSTRAINT chk_variant_net_content
        CHECK (sold_by = 'EACH' OR net_content_uom IS NOT NULL OR catch_weight),
    CONSTRAINT chk_variant_tare
        CHECK (tare_weight IS NULL OR tare_weight >= 0),
    CONSTRAINT chk_variant_hsn_code
        CHECK (hsn_code ~ '^([0-9]{4}|[0-9]{6}|[0-9]{8})$'),
    CONSTRAINT chk_variant_deposit_material
        CHECK (deposit_material IS NULL OR deposit_material IN ('PET', 'ALUMINIUM', 'STEEL', 'GLASS')),
    CONSTRAINT chk_variant_deposit_volume
        CHECK (deposit_volume_ml IS NULL OR (deposit_volume_ml > 0 AND deposit_volume_ml <= 10000)),
    CONSTRAINT chk_variant_deposit_pair
        CHECK ((deposit_material IS NULL) = (deposit_volume_ml IS NULL)),
    CONSTRAINT chk_variant_facing_width
        CHECK (facing_width_mm IS NULL OR facing_width_mm BETWEEN 1 AND 5000)
);

CREATE INDEX idx_variants_tenant_product ON product_variants (tenant_id, product_id);
CREATE UNIQUE INDEX uq_variants_tenant_barcode ON product_variants (tenant_id, barcode) WHERE barcode IS NOT NULL;
-- Partial indexes support fast "active only" queries without reading deleted rows.
CREATE INDEX idx_variants_tenant_active ON product_variants (tenant_id, product_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_variants_manufacturer_pn ON product_variants (tenant_id, manufacturer_pn)
    WHERE manufacturer_pn IS NOT NULL;
-- The till asks "is this restricted?" on every scanned line, and the compliance screen asks
-- "what have we not declared yet?". Both are partial -- the restricted and undeclared sets are
-- small next to the catalogue.
CREATE INDEX idx_variants_restricted
    ON product_variants (tenant_id, restriction_category)
    WHERE restriction_category IS NOT NULL;
CREATE INDEX idx_variants_undeclared
    ON product_variants (tenant_id)
    WHERE allergen_status = 'UNDECLARED';
-- The scan path's index. Not UNIQUE: uq_variants_tenant_barcode already keeps one variant per
-- barcode, and two different barcodes cannot produce the same GTIN-14 — but an existing tenant could
-- hold both '5012345678900' and '05012345678900', entered at different times by different people,
-- and a unique index would fail on their data rather than letting them fix it. The lookup takes the
-- first match by a stable order instead.
CREATE INDEX idx_variants_tenant_gtin14
    ON product_variants (tenant_id, gtin14)
    WHERE gtin14 IS NOT NULL;

COMMENT ON COLUMN product_variants.allergen_status IS
  'NOT_APPLICABLE (the default): not a food product. UNDECLARED: food whose allergens nobody has declared yet; listed by /admin/products/allergen-gaps and never shown to a shopper as free-from. DECLARED: allergens stated, where an empty declaration means none of the fourteen.';
COMMENT ON COLUMN product_variants.facing_width_mm IS
    'How wide one unit is as it faces the customer. Null means a layout using it cannot be width-checked.';
COMMENT ON COLUMN product_variants.gtin14 IS
    'The barcode as a 14-digit GTIN, generated and check-digit verified; NULL when it is not a GTIN.';

-- ── media ───────────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE product_media (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id),
    url        TEXT NOT NULL,
    position   INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_tenant_product ON product_media (tenant_id, product_id, position);

-- ── transactional outbox ────────────────────────────────────────────────────────────────────────────

-- Outbox retry and dead-letter state. A row that fails to publish is retried after a backoff
-- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
-- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
-- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
CREATE TABLE outbox (
    id              UUID PRIMARY KEY,
    event_type      TEXT NOT NULL,
    topic           TEXT NOT NULL,
    tenant_id       UUID,
    aggregate_id    UUID NOT NULL,
    payload         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);

-- The scheduled outbox purge (common-service OutboxPublisher -> BaseOutboxRepository.purgePublished)
-- takes the rows that were published before the retention cutoff, a batch at a time:
--
--   DELETE FROM outbox WHERE id IN (
--     SELECT id FROM outbox
--      WHERE published_at IS NOT NULL AND published_at < ?
--      ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
--
-- This partial index serves the purge's half of the table, so each batch finds its rows without
-- reading the whole table. The relay reads this same table on every tick, so the index stays out of
-- its way: it is partial, holding only published rows, and it shrinks as the purge takes them. Rows
-- enter it when the relay marks them published and leave it when the purge deletes them; the
-- unpublished backlog, the part the relay keeps hot, is never in it.
--
-- product-svc has no processed_events table: its one consumer, the erasure of a departed business,
-- is idempotent by construction (erasing twice finds nothing the second time), so it keeps no
-- dedupe rows. The purge's second statement finds no table and has nothing here to index.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;

-- The claim: rows that may publish now, in the order they were written. The relay's claim
-- (BaseOutboxRepository.claim) walks it for its ORDER BY created_at, id LIMIT n; the same statement's
-- check of an aggregate's earlier rows reads idx_outbox_aggregate_pending, below. No index of every
-- unpublished row by created_at (idx_outbox_unpublished) is kept: it would also hold the dead letters,
-- which the claim's ordered scan never reads, and the other statements on the table are the insert, the
-- outcome writes (by primary key) and the purge (idx_outbox_published, above), none of which reads
-- waiting rows in that order (CatalogIT plans the claim as the relay runs it).
CREATE INDEX idx_outbox_claim ON outbox (created_at, id) WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is dead or
-- backing off.
CREATE INDEX idx_outbox_aggregate_pending ON outbox (aggregate_id, created_at, id) WHERE published_at IS NULL;
