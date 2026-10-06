-- pricing-svc schema — UK VAT model, price lists, promotions, POSLog tax transactions.
-- Money is NUMERIC. A column that holds a price or an amount in the business's currency is unscaled
-- (price_list_items.price, price_overrides, markdowns, applied_prices, the tax and promotion
-- redemption amounts), so its value is kept at the scale its writer gives it, and every writer
-- gives it the currency's own minor units (common-service Fx.minorUnits): a computed amount is
-- rounded half up to them before it is written, and a price or amount a person types that is
-- finer than them is refused, never rounded. Currencies differ in their minor units (the yen has
-- none, the Kuwaiti dinar three), and a declared scale rounds on write without complaint, so a
-- dinar price of 1.235 would be kept as 1.24: that is why these columns declare none.
-- Three groups declare a scale. promotions.value is NUMERIC(18,4): a percentage for PERCENT and
-- BASKET_PERCENT, otherwise an amount in the business's currency. competitor_prices.price and the
-- repricing amounts are NUMERIC(19,4), and the VAT return's boxes are NUMERIC(18,2), in pounds.
-- The service holds the prices a business sets to the rule above before it writes them, so a
-- column's four places never round one: the value of a FLAT, BASKET_FLAT, SPEND_THRESHOLD or
-- MIX_MATCH promotion and a repricing rule's UNDERCUT_AMOUNT value are refused when finer than the
-- business's currency. A rival's price is the exception, on purpose: it is an observation of what
-- a competitor charges, not a price the business sets, so it is kept as seen at the column's four
-- places and may be finer than the currency (forecourt fuel to a tenth of a penny); a price finer
-- than the column's four places is refused, because the column would round it. A percentage
-- (PERCENT, BASKET_PERCENT, UNDERCUT_PERCENT) is not money and is kept at the column's four
-- places; the promotion engine rounds the amount it takes off half up to the currency's minor
-- units when it applies it.
-- Percentages and VAT rates are not money and keep their scales: NUMERIC(5,2) and NUMERIC(5,4)
-- (a VAT rate 0.2000 = 20%).
-- Times: TIMESTAMPTZ UTC. Every row of a business's data carries tenant_id, NOT NULL. Two tables
-- are exceptions. processed_events (V8) is a consumer's dedupe key and has no tenant_id. The
-- outbox is deliberately cross-tenant: its tenant_id is nullable, and the relay drains and purges
-- every business's rows in one pass, so no query on it names a tenant and it has no tenant-led
-- index.

-- HMRC VAT rate definitions. code: T1=Standard 20%, T5=Reduced 5%, T0=Zero 0%, TX=Exempt.
-- Per HMRC VAT Notice 700. Each tenant configures their own rates (supports multi-jurisdiction).
CREATE TABLE vat_rates (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    code           VARCHAR(8)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    rate           NUMERIC(5,4) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    exempt         BOOLEAN     NOT NULL DEFAULT FALSE,
    description    VARCHAR(255),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vat_rates_tenant_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_vat_rates_tenant ON vat_rates (tenant_id);

-- Maps product variants to their HMRC VAT code.
CREATE TABLE product_vat_categories (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    variant_id     UUID        NOT NULL,
    vat_code       VARCHAR(8)  NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_vat_tenant_variant UNIQUE (tenant_id, variant_id)
);
CREATE INDEX idx_product_vat_tenant ON product_vat_categories (tenant_id, variant_id);

-- B2B customer VAT registration. VAT number format: GB + 9 digits (e.g. GB123456789).
-- An EN 16931 invoice names its buyer by the name the buyer is registered under (BT-44), its VAT
-- identifier (BT-48) and, for delivery over Peppol, its electronic address (BT-49). The customer
-- record holds a person's first and last name, which is not who a company's invoice is addressed
-- to, so the buyer's invoice identity lives on this row. country_code has no default: a business's
-- countries are its own, so a row that leaves it out fails rather than taking a default.
CREATE TABLE customer_vat_status (
    id                        UUID        PRIMARY KEY,
    tenant_id                 UUID        NOT NULL,
    customer_id               UUID        NOT NULL,
    vat_number                VARCHAR(20),
    vat_registered            BOOLEAN     NOT NULL DEFAULT FALSE,
    reverse_charge_eligible   BOOLEAN     NOT NULL DEFAULT FALSE,
    country_code              CHAR(2)     NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    legal_name                TEXT,       -- as registered for VAT; the invoice's buyer name
    einvoice_scheme           TEXT,       -- EAS code of the buyer's Peppol participant identifier
    einvoice_id               TEXT,       -- the identifier within that scheme; for an Indian buyer the VAT number is its GSTIN
    CONSTRAINT uq_customer_vat_tenant_customer UNIQUE (tenant_id, customer_id)
);
CREATE INDEX idx_customer_vat_tenant ON customer_vat_status (tenant_id, customer_id);

-- Price zones: the groups of stores that price alike. A list bound to a zone (price_lists.zone_id)
-- is what the zone's stores charge instead of the tenant-wide list. Store assignments are in V10.
CREATE TABLE price_zones (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_price_zone_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_price_zones_tenant ON price_zones (tenant_id, created_at, id);

-- Named price lists per tenant. The currency is the business's own and has no default.
-- A list bound to a price zone (zone_id) is what the zone's stores charge instead of the
-- tenant-wide list (zone_id NULL). The zone's table is created just above, so the foreign key is
-- declared with the column.
CREATE TABLE price_lists (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    name           VARCHAR(100) NOT NULL,
    channel        VARCHAR(20) NOT NULL DEFAULT 'ALL',
    currency       CHAR(3)     NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to   TIMESTAMPTZ,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    zone_id        UUID,       -- NULL = the tenant-wide list every store falls back to
    CONSTRAINT uq_price_lists_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_price_lists_zone_id FOREIGN KEY (zone_id) REFERENCES price_zones (id)
);
CREATE INDEX idx_price_lists_tenant ON price_lists (tenant_id, active);
CREATE INDEX idx_price_lists_zone ON price_lists (tenant_id, zone_id) WHERE zone_id IS NOT NULL;

-- Per-variant prices within a price list. Supports qty-break tiers via min_qty.
-- The price is kept at the list's currency scale (see the header on money).
CREATE TABLE price_list_items (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    price_list_id  UUID         NOT NULL REFERENCES price_lists (id),
    variant_id     UUID         NOT NULL,
    price          NUMERIC      NOT NULL CHECK (price >= 0),
    min_qty        NUMERIC(18,4) NOT NULL DEFAULT 1 CHECK (min_qty > 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_price_list_items_tenant_list_variant_qty
        UNIQUE (tenant_id, price_list_id, variant_id, min_qty)
);
CREATE INDEX idx_price_list_items_tenant ON price_list_items (tenant_id, price_list_id, variant_id);

-- Time-bounded promotional discounts. The type says what is discounted and what it is taken from:
--   PERCENT, FLAT                 per matching line: a percentage off, or an amount off each unit
--   BASKET_PERCENT, BASKET_FLAT   off the whole basket
--   SPEND_THRESHOLD               a fixed amount off once the basket clears min_order_amount
--   BOGO                          buy buy_qty of the scoped items, get get_qty at get_discount_pct off
--   MIX_MATCH                     any buy_qty units from the scope for value ("any 3 for 10")
-- value's unit is the type's: a percentage for PERCENT and BASKET_PERCENT, an amount otherwise.
-- Anything a tenant can configure here changes what it charges, so each type's shape is enforced
-- by a CHECK rather than trusted to the writer.
CREATE TABLE promotions (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    store_id         UUID,       -- NULL = every store; a value confines the promotion to that store
    name             VARCHAR(200) NOT NULL,
    type             VARCHAR(20) NOT NULL,
    value            NUMERIC(18,4) NOT NULL CHECK (value > 0),
    min_order_amount NUMERIC,
    channel          VARCHAR(20) NOT NULL DEFAULT 'ALL',
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Applied in ascending order, so a lower number runs first. Explicit rather than emergent:
    -- which promotion wins is written down, not an accident of a SQL sort.
    priority         INTEGER     NOT NULL DEFAULT 100,
    -- An exclusive promotion that applies stops every promotion after it. This is how "20% off
    -- everything, cannot be combined with other offers" is expressed.
    exclusive        BOOLEAN     NOT NULL DEFAULT FALSE,
    -- NULL = automatic (applies whenever it matches). Non-null = the customer must present it.
    coupon_code      TEXT,
    -- Usage caps. NULL means uncapped. Usage is counted from promotion_redemptions, the append-only
    -- ledger (V3; tenant erasure apart), never from a counter on this row.
    max_redemptions  INTEGER,
    max_per_customer INTEGER,
    -- BOGO: buy buy_qty of the scoped items, get get_qty at get_discount_pct off (100 = free).
    -- MIX_MATCH uses buy_qty alone as the bundle size.
    buy_qty          NUMERIC(18,3),
    get_qty          NUMERIC(18,3),
    get_discount_pct NUMERIC(5,2),
    CONSTRAINT chk_promotions_type CHECK (type IN (
        'PERCENT', 'FLAT', 'BASKET_PERCENT', 'BASKET_FLAT', 'SPEND_THRESHOLD', 'BOGO', 'MIX_MATCH'
    )),
    -- A BOGO needs its three quantities, a MIX_MATCH its bundle size, and nothing else may carry
    -- them: a half-configured BOGO is a promotion that silently discounts nothing. Enforced in the
    -- database so it holds however the row was written.
    CONSTRAINT chk_promotions_bogo_shape CHECK (
        (type = 'BOGO' AND buy_qty > 0 AND get_qty > 0
                       AND get_discount_pct > 0 AND get_discount_pct <= 100)
        OR (type = 'MIX_MATCH' AND buy_qty >= 2 AND get_qty IS NULL AND get_discount_pct IS NULL)
        OR (type NOT IN ('BOGO', 'MIX_MATCH') AND buy_qty IS NULL AND get_qty IS NULL
                       AND get_discount_pct IS NULL)
    ),
    -- A threshold promotion without a threshold is just a discount, and would apply to every basket.
    CONSTRAINT chk_promotions_threshold_shape CHECK (
        type <> 'SPEND_THRESHOLD' OR min_order_amount IS NOT NULL
    ),
    -- A percentage that is not a percentage is a unit confusion waiting to happen.
    CONSTRAINT chk_promotions_percent_range CHECK (
        type NOT IN ('PERCENT','BASKET_PERCENT') OR (value > 0 AND value <= 100)
    )
);
-- Coupon codes are matched case-insensitively — a customer typing SAVE10 must get the promotion
-- created as save10 — so uniqueness is case-insensitive too, or two rows could both claim the same
-- code and which one applied would be an accident of ordering.
CREATE UNIQUE INDEX uq_promotions_coupon
    ON promotions (tenant_id, upper(coupon_code))
    WHERE coupon_code IS NOT NULL;
-- The engine's candidate query filters on tenant, active, window and store, and orders by priority.
CREATE INDEX idx_promotions_tenant
    ON promotions (tenant_id, active, starts_at, priority);
-- Finding what is still running is the query the switch exists to serve. Without it, "show me
-- every live promotion so I can stop the wrong one" is a full scan of every promotion the tenant
-- has ever created.
CREATE INDEX idx_promotions_live
    ON promotions (tenant_id, ends_at) WHERE active = TRUE;

-- Scope a promotion to VARIANT, CATEGORY, or ALL.
CREATE TABLE promotion_items (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    promotion_id   UUID        NOT NULL REFERENCES promotions (id),
    scope_type     VARCHAR(20) NOT NULL CHECK (scope_type IN ('VARIANT','CATEGORY','ALL')),
    scope_id       UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promotion_items_tenant_scope ON promotion_items (tenant_id, scope_type);
CREATE INDEX idx_promotion_items_promo ON promotion_items (promotion_id);

-- POSLog-compatible tax capture per order line.
-- tax_point_date = time of supply per s.6 VATA 1994. Feeds HMRC MTD boxes 1 and 6.
-- Append-only: no UPDATE or DELETE on this table, except tenant erasure.
CREATE TABLE tax_transactions (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    order_id       UUID         NOT NULL,
    order_line_id  UUID         NOT NULL,
    variant_id     UUID         NOT NULL,
    store_id       UUID         NOT NULL,
    vat_code       VARCHAR(8)   NOT NULL,
    vat_rate       NUMERIC(5,4) NOT NULL,
    net_amount     NUMERIC      NOT NULL,
    vat_amount     NUMERIC      NOT NULL,
    gross_amount   NUMERIC      NOT NULL,
    exempt         BOOLEAN      NOT NULL DEFAULT FALSE,
    tax_point_date TIMESTAMPTZ  NOT NULL,
    invoice_ref    VARCHAR(50),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tax_transactions_tenant ON tax_transactions (tenant_id, tax_point_date);
CREATE INDEX idx_tax_transactions_order  ON tax_transactions (tenant_id, order_id);

-- Transactional outbox for async event publishing (PriceChanged, PromotionActivated).
-- A row that fails to publish is retried after a backoff (storeql.outbox.backoff-base-seconds,
-- doubling, capped at storeql.outbox.backoff-cap-seconds), and only that row's aggregate waits for
-- it. After storeql.outbox.max-attempts it is a dead letter: never claimed again, kept for an
-- operator, and it holds back its own aggregate only.
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
-- The scheduled purge (common-service OutboxPublisher) deletes, in batches of a thousand, the
-- published rows older than a retention, oldest first. A published row is in neither of the claim's
-- indexes (below), which hold only waiting rows; this partial index on published_at holds the
-- published rows and no others.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
-- The claim (common-service BaseOutboxRepository.claim): rows that may publish now, in the order
-- they were written. This index serves its ordered scan (ORDER BY created_at, id LIMIT n); a row
-- that is dead or published is not in it. The claim's check for an earlier waiting row of the same
-- aggregate reads idx_outbox_aggregate_pending, below. Marking a row published and recording a
-- failure go by primary key. No index of every unpublished row by created_at (idx_outbox_unpublished)
-- is kept: it would also hold the dead letters, which the claim's ordered scan never reads.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;
-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
