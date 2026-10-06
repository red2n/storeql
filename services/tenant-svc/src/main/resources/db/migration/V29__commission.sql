-- What a sale earns the person who made it (store operations & workforce).
--
-- The hours are recorded (V27) and what they cost is known (V28); what a sale earns is not. In a shop
-- that pays commission — a butcher's counter, a phone shop, a forecourt — an assistant's pay is part
-- wage and part what they sold, and a platform that knows the sale and the hours but not the
-- arrangement between them cannot answer what anybody is owed.
--
-- This is the arrangement and nothing else: a scheme, its rates, and who is on it. The money earned is
-- worked out where the sales are (order-svc), because a period's every sale and return must not cross
-- a service boundary to be counted.
--
-- A scheme is superseded, never edited: commission already paid at 2% must not become 3% because
-- somebody corrected the scheme in April. A correction writes a new version and points back at the one
-- it replaces.
CREATE TABLE commission_schemes (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    name           TEXT          NOT NULL,
    -- PERCENT_OF_NET: a percentage of the net sold, VAT out and discounts off — what the shop actually
    -- took, which is the only figure both sides can check.
    -- PER_UNIT: a fixed amount per unit sold, for counters that sell one kind of thing.
    basis          TEXT          NOT NULL,
    -- Required for PER_UNIT (the amount is money) and absent for PERCENT_OF_NET (the rate is a ratio).
    currency       CHAR(3),
    status         TEXT          NOT NULL DEFAULT 'ACTIVE',
    note           TEXT,
    supersedes     UUID          REFERENCES commission_schemes(id) DEFERRABLE INITIALLY DEFERRED,
    superseded_by  UUID          REFERENCES commission_schemes(id) DEFERRABLE INITIALLY DEFERRED,
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NOT NULL,

    CONSTRAINT ck_commission_basis  CHECK (basis IN ('PERCENT_OF_NET', 'PER_UNIT')),
    CONSTRAINT ck_commission_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    -- A per-unit scheme without a currency is an amount of nothing; a percentage with one invites the
    -- reader to think it is an amount.
    CONSTRAINT ck_commission_currency CHECK (
        (basis = 'PER_UNIT' AND currency IS NOT NULL) OR (basis = 'PERCENT_OF_NET' AND currency IS NULL)
    )
);

CREATE INDEX idx_commission_schemes_tenant ON commission_schemes (tenant_id, status, created_at DESC);

-- The rates, as bands. A scheme with one band starting at zero is a flat rate, which is most of them;
-- a scheme with more is the ordinary retail arrangement — 2% to ten thousand, 3% above it.
--
-- Bands are MARGINAL: only the part of the period's sales inside a band earns that band's rate. The
-- alternative, re-rating everything once a threshold is crossed, means a sale's commission changes
-- after the fact — and a figure that can change after the fact is one nobody can be paid on.
CREATE TABLE commission_scheme_bands (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    scheme_id      UUID          NOT NULL REFERENCES commission_schemes(id),
    -- The period-to-date net sales at which this band starts. The first band starts at zero. Held at four
    -- decimals and sixteen integer digits: enough for a percentage band at the business currency's minor
    -- units (none for yen, three for a Kuwaiti dinar) and a per-unit band's quantity at three. Each
    -- threshold is read back at its own scale, so no value is rounded on the way in.
    threshold_from NUMERIC(20,4) NOT NULL,
    -- A percentage (PERCENT_OF_NET, so 2.5 means 2.5%) or an amount per unit (PER_UNIT).
    rate           NUMERIC(12,4) NOT NULL,

    CONSTRAINT ck_band_threshold CHECK (threshold_from >= 0),
    CONSTRAINT ck_band_rate      CHECK (rate >= 0),
    -- Two bands starting at the same figure is an undecidable rate.
    CONSTRAINT uq_band_threshold UNIQUE (scheme_id, threshold_from)
);

CREATE INDEX idx_commission_bands_scheme ON commission_scheme_bands (tenant_id, scheme_id, threshold_from);

-- Who is on which scheme, dated exactly as a pay rate is: the scheme in force for a sale is the latest
-- one effective on or before the day it was sold, so a scheme somebody moved onto in April does not
-- re-earn January.
CREATE TABLE staff_commission_schemes (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    user_id        UUID          NOT NULL,
    -- Null takes the person off commission from that day: an arrangement that ended is not the absence
    -- of an arrangement, and a shop must be able to say when it stopped.
    scheme_id      UUID          REFERENCES commission_schemes(id),
    effective_from DATE          NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NOT NULL,

    CONSTRAINT uq_commission_assignment_day UNIQUE (tenant_id, user_id, effective_from)
);

CREATE INDEX idx_staff_commission_person
    ON staff_commission_schemes (tenant_id, user_id, effective_from DESC);

COMMENT ON TABLE commission_schemes IS
    'A commission arrangement: its basis and, in commission_scheme_bands, its rates. Superseded, never edited, so commission already earned cannot be re-rated.';
COMMENT ON TABLE commission_scheme_bands IS
    'Marginal rate bands: only the part of a period''s sales inside a band earns that band''s rate.';
COMMENT ON TABLE staff_commission_schemes IS
    'Who is on which scheme, from which day. A null scheme_id ends the arrangement from that day.';
