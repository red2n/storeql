-- Shelf capacity as replenishment's target (07.17), projected from product-svc's planograms.
--
-- Replenishment has been driven from a stock number: on hand against a reorder level. That answers
-- "will we run out", which is the warehouse's question, not the shop floor's. The shop floor's
-- question is "does the bay look full", and the two differ by exactly the shelf: 40 units on hand is
-- plenty for a bay that holds 12 and a gap in one that holds 60.
--
-- A planogram knows the answer — facings times depth is what the shelf holds — and says so. This
-- is a projection of that event, not a second copy of the layout: no positions, no shelves, no
-- sequence, only what a replenishment run needs. product-svc stays the owner (golden rule #1).
CREATE TABLE shelf_targets (
    tenant_id        UUID        NOT NULL,
    store_id         UUID        NOT NULL,
    -- Per fixture, not per store: a line may be sited on a gondola and an end cap at once, and the
    -- store's target is the two added together. Collapsing them here would lose the second siting.
    fixture_id       UUID        NOT NULL,
    variant_id       UUID        NOT NULL,
    capacity         INTEGER     NOT NULL,
    min_presentation INTEGER     NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_shelf_targets PRIMARY KEY (tenant_id, store_id, fixture_id, variant_id),
    CONSTRAINT chk_shelf_target_capacity CHECK (capacity > 0),
    CONSTRAINT chk_shelf_target_min CHECK (min_presentation >= 0 AND min_presentation <= capacity)
);

CREATE INDEX idx_shelf_targets_variant ON shelf_targets (tenant_id, store_id, variant_id);

-- Which layout each fixture's targets came from, and at what version.
--
-- Separate from the rows above so the high-water mark survives a layout that drops a line: if the
-- version lived on the target rows, a later planogram that no longer stocks a variant would leave
-- no row to compare against, and a re-delivered older event would put the line back. Kafka
-- re-delivers (at-least-once), and topic partitions give no order across fixtures, so a projection
-- that trusts arrival order is a projection that eventually holds a shelf nobody ever built.
CREATE TABLE shelf_target_fixtures (
    tenant_id         UUID        NOT NULL,
    fixture_id        UUID        NOT NULL,
    store_id          UUID        NOT NULL,
    planogram_id      UUID        NOT NULL,
    planogram_version INTEGER     NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_shelf_target_fixtures PRIMARY KEY (tenant_id, fixture_id),
    CONSTRAINT chk_shelf_target_version CHECK (planogram_version >= 1)
);

COMMENT ON TABLE shelf_targets IS
    'What each shelf holds when full, per (store, fixture, variant). A projection of product-svc''s published planograms.';
COMMENT ON COLUMN shelf_targets.min_presentation IS
    'The count below which the bay looks picked over — a merchandising minimum, not a stock minimum.';
COMMENT ON TABLE shelf_target_fixtures IS
    'The planogram version each fixture''s targets came from: the high-water mark that makes a re-delivered older event a no-op.';
