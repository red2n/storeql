-- Merchandising: what gets shelf space, how much, and where it sits.
--
-- Deciding what to stock, how much space it gets and where it sits. The tables here are planograms and
-- shelf layout, space planning and category resets, and the capacity that drives replenishment. The
-- brand flag (brands.own_brand) and the shelf width of a unit (product_variants.facing_width_mm) are
-- columns on the catalogue tables in V1__init.sql. Per-store assortment (product_stores,
-- V13__product_store_assortment.sql) and the new-line half of range review (the item lifecycle in
-- V1__init.sql) are not duplicated here.
--
-- It lives in product-svc because product-svc already answers "what do we range, and where" — it owns
-- the catalogue and the per-store assortment. Capacity is published as an event for inventory-svc to
-- project, the way catalog_lines_out already works, so replenishment reads a local table and no
-- service joins across another's.

-- ── fixtures: the physical furniture ───────────────────────────────────────────────────────────────
--
-- A fixture is a run of shelving, a chiller or an end cap: the thing a planogram is drawn for. It
-- belongs to a store, and optionally to a zone — zone_id references tenant-svc's zones and carries no
-- foreign key, because that table belongs to another service (golden rule #1). Nullable because a
-- buyer plans a fixture before anybody decides which aisle it stands in.
CREATE TABLE merch_fixtures (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    store_id    UUID        NOT NULL,
    zone_id     UUID,
    code        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    -- How many shelves it has, top to bottom, and how wide each is in millimetres. Width is what makes
    -- a planogram checkable: facings multiplied by a variant's width either fits the shelf or does not.
    shelf_count INTEGER     NOT NULL,
    shelf_width_mm INTEGER  NOT NULL,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_fixture_kind CHECK (
        kind IN ('GONDOLA', 'END_CAP', 'CHILLER', 'FREEZER', 'SHELF_RUN', 'BIN', 'COUNTER')
    ),
    CONSTRAINT ck_fixture_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_fixture_shelves CHECK (shelf_count BETWEEN 1 AND 30),
    CONSTRAINT ck_fixture_width CHECK (shelf_width_mm BETWEEN 100 AND 20000)
);

CREATE INDEX idx_merch_fixtures_store ON merch_fixtures (tenant_id, store_id, status);
CREATE UNIQUE INDEX uq_merch_fixtures_code
    ON merch_fixtures (tenant_id, store_id, lower(code))
    WHERE status = 'ACTIVE';

-- ── planograms: a layout, versioned, and immutable once published ──────────────────────────────────
--
-- A published planogram is never edited. A change is a new version that supersedes it, both kept — the
-- same rule as an invoice, a statutory filing and a card attempt elsewhere in this platform, and for
-- the same reason: somebody has to be able to ask what the shelf was supposed to look like last
-- Tuesday, and an edited row cannot answer that. It is also what gives a category reset something to
-- schedule: a version with a date, not a table somebody is still typing into.
CREATE TABLE planograms (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    fixture_id     UUID        NOT NULL REFERENCES merch_fixtures (id),
    version        INTEGER     NOT NULL,
    status         TEXT        NOT NULL,
    -- The day it takes effect. A planogram is drawn weeks ahead of the reset that puts it on the shelf.
    effective_from DATE        NOT NULL,
    note           TEXT,
    -- The version this one replaces, and the pointer back, so "the one in force" is an index and not a
    -- query somebody has to remember to write correctly.
    supersedes     UUID        REFERENCES planograms (id),
    superseded_by  UUID        REFERENCES planograms (id) DEFERRABLE INITIALLY DEFERRED,
    created_at     TIMESTAMPTZ NOT NULL,
    created_by     UUID        NOT NULL,
    published_at   TIMESTAMPTZ,
    published_by   UUID,

    CONSTRAINT ck_planogram_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_planogram_version CHECK (version >= 1),
    -- A published planogram says who published it and when; a draft says neither.
    CONSTRAINT ck_planogram_published CHECK (
        (status = 'DRAFT') = (published_at IS NULL AND published_by IS NULL)
    ),
    CONSTRAINT ck_planogram_supersedes_self CHECK (supersedes IS NULL OR supersedes <> id),
    CONSTRAINT ck_planogram_superseded_self CHECK (superseded_by IS NULL OR superseded_by <> id)
);

-- One draft at a time per fixture: two people drawing the same shelf at once is a merge nobody wins.
CREATE UNIQUE INDEX uq_planogram_one_draft
    ON planograms (tenant_id, fixture_id)
    WHERE status = 'DRAFT';

-- One version in force per fixture. Keyed on superseded_by IS NULL, like the statutory filings: the
-- one that stands is the one nothing has replaced.
CREATE UNIQUE INDEX uq_planogram_in_force
    ON planograms (tenant_id, fixture_id)
    WHERE status = 'PUBLISHED' AND superseded_by IS NULL;

CREATE UNIQUE INDEX uq_planogram_version ON planograms (tenant_id, fixture_id, version);
CREATE INDEX idx_planograms_fixture ON planograms (tenant_id, fixture_id, effective_from DESC);

-- ── positions: a variant on a shelf, and the capacity that follows ────────────────────────────────
--
-- The arithmetic that makes this worth having. FACINGS is how many units face the customer across the
-- shelf; DEPTH is how many sit behind each one. Capacity is the product of the two — it is GENERATED,
-- because a capacity that can disagree with the layout it came from is worse than no capacity at all,
-- and a service that maintains it is right until the first write path that forgets (the same reason
-- product_variants.gtin14 is generated).
--
-- MIN_PRESENTATION is the merchandising minimum: the count below which the shelf looks picked over,
-- which is a different number from a stock minimum and the reason replenishment is driven from the
-- shelf and not only from the stockroom.
CREATE TABLE planogram_positions (
    id               UUID    PRIMARY KEY,
    tenant_id        UUID    NOT NULL,
    planogram_id     UUID    NOT NULL REFERENCES planograms (id) ON DELETE CASCADE,
    variant_id       UUID    NOT NULL REFERENCES product_variants (id),
    -- 1 is the top shelf, because that is how a planogram is read and drawn.
    shelf            INTEGER NOT NULL,
    -- Left to right within the shelf.
    sequence         INTEGER NOT NULL,
    facings          INTEGER NOT NULL,
    depth            INTEGER NOT NULL,
    capacity         INTEGER GENERATED ALWAYS AS (facings * depth) STORED,
    min_presentation INTEGER NOT NULL,

    CONSTRAINT ck_position_shelf CHECK (shelf >= 1),
    CONSTRAINT ck_position_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_position_facings CHECK (facings BETWEEN 1 AND 99),
    CONSTRAINT ck_position_depth CHECK (depth BETWEEN 1 AND 99),
    -- A presentation minimum above capacity would ask replenishment for more than fits, for ever.
    CONSTRAINT ck_position_min_presentation CHECK (
        min_presentation >= 0 AND min_presentation <= facings * depth
    )
);

-- Two products cannot stand in one slot. A variant MAY appear twice on one planogram, though —
-- multi-siting a line (by the door and in its own aisle) is ordinary merchandising, so there is
-- deliberately no unique constraint on (planogram, variant).
CREATE UNIQUE INDEX uq_position_slot
    ON planogram_positions (planogram_id, shelf, sequence);

CREATE INDEX idx_positions_variant ON planogram_positions (tenant_id, variant_id);
CREATE INDEX idx_positions_planogram ON planogram_positions (planogram_id, shelf, sequence);

-- ── space planning: what share a category is meant to get ─────────────────────────────────────────
--
-- The plan against which the drawn planograms are judged: this category should hold about this share
-- of a store's shelf width. A target rather than a rule, because the check is "what did we actually
-- give it" — a share nobody compares to reality is a wish.
CREATE TABLE category_space_plans (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    category_id  UUID        NOT NULL REFERENCES categories (id),
    -- A share of the store's total shelf width, 0..1 with four places: 0.0825 is 8.25 per cent.
    target_share NUMERIC(5, 4) NOT NULL,
    review_on    DATE,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_space_share CHECK (target_share > 0 AND target_share <= 1)
);

CREATE UNIQUE INDEX uq_space_plan ON category_space_plans (tenant_id, store_id, category_id);
CREATE INDEX idx_space_plans_store ON category_space_plans (tenant_id, store_id);

-- ── category resets: the day a set of new layouts goes on the shelf ───────────────────────────────
--
-- A reset is the event, not the drawing: a named set of published planograms that go live together on
-- a date, because a category is re-laid all at once and half a reset is a mess in an aisle. The
-- planograms are already versioned and dated; this groups them so somebody can say "the spring soft
-- drinks reset" and see exactly which shelves it moves.
CREATE TABLE category_resets (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    category_id   UUID        NOT NULL REFERENCES categories (id),
    name          TEXT        NOT NULL,
    scheduled_for DATE        NOT NULL,
    status        TEXT        NOT NULL,
    -- Why it was abandoned, when it was. Paired to the status so a stale reason cannot sit on a live
    -- reset, the same pairing tenants.deactivated_reason has.
    cancelled_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID        NOT NULL,
    completed_at  TIMESTAMPTZ,

    CONSTRAINT ck_reset_status CHECK (status IN ('PLANNED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_reset_cancelled CHECK ((status = 'CANCELLED') = (cancelled_reason IS NOT NULL)),
    CONSTRAINT ck_reset_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX idx_resets_tenant ON category_resets (tenant_id, scheduled_for DESC);

-- Which shelves the reset moves. A planogram belongs to at most one reset: two resets claiming the
-- same shelf on different days is the contradiction this prevents being recorded at all.
CREATE TABLE category_reset_planograms (
    reset_id     UUID NOT NULL REFERENCES category_resets (id) ON DELETE CASCADE,
    planogram_id UUID NOT NULL REFERENCES planograms (id),
    tenant_id    UUID NOT NULL,
    CONSTRAINT pk_reset_planograms PRIMARY KEY (reset_id, planogram_id)
);
CREATE INDEX idx_category_reset_planograms_tenant_reset
    ON category_reset_planograms (tenant_id, reset_id);

CREATE UNIQUE INDEX uq_reset_planogram_once ON category_reset_planograms (planogram_id);

COMMENT ON TABLE merch_fixtures IS
    'The physical furniture a planogram is drawn for. zone_id is tenant-svc''s; no FK across services.';
COMMENT ON TABLE planograms IS
    'A fixture''s layout, versioned. Published versions are immutable; a change supersedes and both stay.';
COMMENT ON COLUMN planogram_positions.capacity IS
    'facings * depth, generated — a capacity that can disagree with its layout is worse than none.';
COMMENT ON TABLE category_resets IS
    'The day a set of published planograms goes on the shelf together. A planogram belongs to one reset.';
