-- Price zones and competitor-driven repricing (readiness review 03.x).
--
-- A business does not charge the same everywhere: a city-centre store, a motorway forecourt and
-- an out-of-town warehouse each face a different rival across the road. A price zone groups the
-- stores that price alike; a price list bound to a zone beats the tenant-wide list for a store in
-- that zone and is invisible to every other store. A store sits in one zone at most, so the price
-- a till charges is never a toss-up between two lists.
--
-- The rival across the road is the second half. What a competitor charges is observed — typed in
-- by staff or imported in bulk — and kept as an append-only record per variant, optionally per
-- zone. A repricing rule on a price list turns the freshest observation of each rival into a
-- proposal (match the lowest, undercut it by a percentage or an amount, rounded to a .99 or not,
-- never below a floor), and management applies the proposal into that list or dismisses it.
-- pricing-svc holds no cost, so the floor is a percentage of the current price and the rule says
-- so; margin protection proper lives with the buyer's cost in purchase-svc.

CREATE TABLE price_zones (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_price_zone_name UNIQUE (tenant_id, name)
);
CREATE INDEX ix_price_zones_tenant ON price_zones (tenant_id, created_at, id);

-- Which stores price alike. A store is in one zone at most; assigning it again moves it.
-- tenant-svc owns the store; it is referenced, never joined (its table is another service's).
CREATE TABLE price_zone_stores (
    tenant_id UUID NOT NULL,
    zone_id   UUID NOT NULL REFERENCES price_zones (id),
    store_id  UUID NOT NULL,
    PRIMARY KEY (tenant_id, store_id)
);
CREATE INDEX ix_price_zone_stores_zone ON price_zone_stores (tenant_id, zone_id);

-- A price list bound to a zone; NULL is the tenant-wide list every store falls back to.
ALTER TABLE price_lists ADD COLUMN zone_id UUID REFERENCES price_zones (id);
CREATE INDEX ix_price_lists_zone ON price_lists (tenant_id, zone_id) WHERE zone_id IS NOT NULL;

-- What a rival charged, as seen: append-only, in the business's own currency (like for like).
CREATE TABLE competitor_prices (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    variant_id  UUID          NOT NULL,
    competitor  TEXT          NOT NULL,
    price       NUMERIC(19,4) NOT NULL,
    currency    CHAR(3)       NOT NULL,
    zone_id     UUID          REFERENCES price_zones (id),   -- NULL = seen everywhere
    observed_on DATE          NOT NULL,
    source      TEXT          NOT NULL,                       -- MANUAL | IMPORT
    recorded_by UUID,
    recorded_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_competitor_price  CHECK (price > 0),
    CONSTRAINT chk_competitor_source CHECK (source IN ('MANUAL', 'IMPORT'))
);
CREATE INDEX ix_competitor_prices_variant
    ON competitor_prices (tenant_id, variant_id, observed_on DESC, recorded_at DESC);

-- How a list answers its rivals. The floor is a share of the current price: pricing-svc has no
-- cost to protect a margin with, and the rule is honest about that.
CREATE TABLE repricing_rules (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    name          TEXT          NOT NULL,
    price_list_id UUID          NOT NULL REFERENCES price_lists (id),
    strategy      TEXT          NOT NULL,                     -- MATCH_LOWEST | UNDERCUT_PERCENT | UNDERCUT_AMOUNT
    value         NUMERIC(19,4) NOT NULL DEFAULT 0,           -- the percentage or the amount
    floor_percent NUMERIC(5,2)  NOT NULL,                     -- never below this share of the current price
    rounding      TEXT          NOT NULL,                     -- NONE | ENDING_99
    max_age_days  INT           NOT NULL DEFAULT 14,          -- an observation older than this is stale
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_repricing_rule_name UNIQUE (tenant_id, name),
    CONSTRAINT chk_repricing_strategy CHECK (strategy IN ('MATCH_LOWEST', 'UNDERCUT_PERCENT', 'UNDERCUT_AMOUNT')),
    CONSTRAINT chk_repricing_value    CHECK (value >= 0),
    CONSTRAINT chk_repricing_floor    CHECK (floor_percent > 0 AND floor_percent <= 100),
    CONSTRAINT chk_repricing_rounding CHECK (rounding IN ('NONE', 'ENDING_99')),
    CONSTRAINT chk_repricing_max_age  CHECK (max_age_days BETWEEN 1 AND 365)
);
CREATE INDEX ix_repricing_rules_tenant ON repricing_rules (tenant_id, created_at, id);

-- What a run proposed, and what became of it. One open proposal per rule and variant: a later run
-- refreshes it rather than piling up.
CREATE TABLE repricing_proposals (
    id               UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    rule_id          UUID          NOT NULL REFERENCES repricing_rules (id),
    price_list_id    UUID          NOT NULL REFERENCES price_lists (id),
    zone_id          UUID          REFERENCES price_zones (id),
    variant_id       UUID          NOT NULL,
    current_price    NUMERIC(19,4) NOT NULL,
    competitor       TEXT          NOT NULL,
    competitor_price NUMERIC(19,4) NOT NULL,
    observed_on      DATE          NOT NULL,
    proposed_price   NUMERIC(19,4) NOT NULL,
    currency         CHAR(3)       NOT NULL,
    status           TEXT          NOT NULL,                  -- PROPOSED | APPLIED | DISMISSED
    proposed_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    decided_by       UUID,
    CONSTRAINT chk_repricing_proposal_status CHECK (status IN ('PROPOSED', 'APPLIED', 'DISMISSED'))
);
CREATE INDEX ix_repricing_proposals_status ON repricing_proposals (tenant_id, status, proposed_at DESC, id);
CREATE UNIQUE INDEX ux_repricing_proposals_open
    ON repricing_proposals (tenant_id, rule_id, variant_id) WHERE status = 'PROPOSED';
