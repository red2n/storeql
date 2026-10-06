-- Loyalty tiers and points expiry (customer, loyalty & engagement, 13.x).
--
-- A business sets its own programme: its tiers — each a name, the qualifying points that reach it
-- and the earn multiplier it gives — how many months a point lives, and how many months of earning
-- count towards a tier. A point lives for ever unless the programme sets an expiry. Every earning is
-- a lot with its own expiry; points are spent from the lot that dies first, then the oldest; a sweep
-- writes the EXPIRE ledger entry the day a lot dies and announces it, so the deferred revenue the
-- point carried (17.11) is released as breakage. The ledger stays append-only: a lot's remainder is
-- the only thing that moves.
CREATE TABLE loyalty_programmes (
    tenant_id          UUID        NOT NULL,
    expiry_months      INT,                        -- months a point lives; null for never
    qualifying_months  INT,                        -- months of earning that count towards a tier; null for a lifetime
    reason             TEXT        NOT NULL,
    set_by             UUID,
    set_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_loyalty_programmes PRIMARY KEY (tenant_id),
    CONSTRAINT ck_loyalty_expiry CHECK (expiry_months IS NULL OR expiry_months BETWEEN 1 AND 120),
    CONSTRAINT ck_loyalty_qualifying CHECK (qualifying_months IS NULL OR qualifying_months BETWEEN 1 AND 36)
);

CREATE TABLE loyalty_tiers (
    id          UUID          NOT NULL,
    tenant_id   UUID          NOT NULL,
    rank        INT           NOT NULL,
    name        TEXT          NOT NULL,
    threshold   NUMERIC(18,2) NOT NULL,
    multiplier  NUMERIC(6,3)  NOT NULL,
    CONSTRAINT pk_loyalty_tiers PRIMARY KEY (id),
    CONSTRAINT uq_loyalty_tier_rank UNIQUE (tenant_id, rank),
    CONSTRAINT uq_loyalty_tier_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_loyalty_tier_threshold CHECK (threshold >= 0),
    CONSTRAINT ck_loyalty_tier_multiplier CHECK (multiplier >= 1 AND multiplier <= 10)
);

CREATE TABLE loyalty_point_lots (
    id                UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    customer_id       UUID          NOT NULL,
    ledger_entry_id   UUID,                      -- the EARN or ADJUST that made it; null for an opening lot
    points            NUMERIC(18,2) NOT NULL,
    remaining         NUMERIC(18,2) NOT NULL,
    earned_at         TIMESTAMPTZ   NOT NULL,
    expires_at        TIMESTAMPTZ,               -- null: never
    expired_entry_id  UUID,                      -- the EXPIRE entry that closed it
    CONSTRAINT pk_loyalty_point_lots PRIMARY KEY (id),
    CONSTRAINT ck_loyalty_lot_points CHECK (points > 0),
    CONSTRAINT ck_loyalty_lot_remaining CHECK (remaining >= 0 AND remaining <= points)
);
CREATE INDEX idx_loyalty_lots_open ON loyalty_point_lots (tenant_id, customer_id, expires_at, earned_at)
    WHERE remaining > 0;
-- Not led by tenant_id. The expiry sweep runs one business at a time (LoyaltyProgrammeRepository.sweep,
-- which reads its lots by tenant_id), but this partial index is on expires_at alone: the lots due
-- across every business are read as one range, and the business's own rows are filtered from them.
-- A (tenant_id, expires_at) index would serve each business's sweep on its own. The choice is open:
-- changing the index is a schema change, for the owner to decide.
CREATE INDEX idx_loyalty_lots_due ON loyalty_point_lots (expires_at) WHERE remaining > 0 AND expires_at IS NOT NULL;
