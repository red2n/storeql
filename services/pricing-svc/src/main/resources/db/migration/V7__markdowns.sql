-- Date-code markdown: reduce to clear.
--
-- The single most common daily task on a fresh counter: a batch is a day or two from its date,
-- and someone stickers it at a lower price so it sells rather than goes in the bin. The sticker
-- is the whole mechanism — the till cannot know which batch a scanned pack came from, so the
-- reduced price rides on a new barcode the sticker carries, and that barcode is what the till
-- scans. A markdown here is therefore three things at once: the decision (which batch, how much
-- off, why, who), the sticker's barcode, and the price the till charges when it reads it.
--
-- The ladder is the plan: how much off at how many days to go, so the morning's work is a list the
-- system proposes rather than a judgement made pack by pack.

-- How much off at how many days to expiry. A store's own ladder wins over the tenant's; a tenant
-- with none gets the default the service names, and the plan says so.
CREATE TABLE markdown_ladders (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    store_id       UUID,                          -- NULL = the tenant's default ladder
    days_to_expiry INT           NOT NULL,
    percent_off    NUMERIC(5,2)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT chk_ladder_days    CHECK (days_to_expiry >= 0),
    CONSTRAINT chk_ladder_percent CHECK (percent_off > 0 AND percent_off <= 100),
    CONSTRAINT uq_ladder_step UNIQUE NULLS NOT DISTINCT (tenant_id, store_id, days_to_expiry)
);

-- The sticker's item code: five digits inside the in-store EAN-13 range, taken from a counter row
-- under its lock so two counters stickering at once never print the same code.
CREATE TABLE markdown_label_series (
    tenant_id   UUID   PRIMARY KEY,
    next_number BIGINT NOT NULL DEFAULT 1
);

CREATE TABLE markdowns (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    store_id       UUID          NOT NULL,
    variant_id     UUID          NOT NULL,
    -- The batch inventory-svc holds; referenced, never joined (its table is another service's).
    batch_id       UUID,
    batch_no       TEXT,
    expiry_date    DATE          NOT NULL,
    -- How many packs were stickered. The till refuses to sell more at this price than this.
    qty            NUMERIC(14,3) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    original_price NUMERIC       NOT NULL,
    markdown_price NUMERIC       NOT NULL,
    percent_off    NUMERIC(5,2)  NOT NULL,
    reason         TEXT          NOT NULL,
    -- The sticker's EAN-13: 21 + item(5) + price in minor units(5) + check. What the till scans.
    label_code     CHAR(13)      NOT NULL,
    status         TEXT          NOT NULL,        -- ACTIVE | CANCELLED (EXPIRED is the date)
    applied_by     UUID,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    cancelled_at   TIMESTAMPTZ,
    cancelled_by   UUID,
    cancel_reason  TEXT,
    -- The tax mode of the list the sticker was cut from: original_price and markdown_price mean the
    -- same thing they meant there, so a sticker on a tax-inclusive shelf is a shelf price.
    tax_mode       TEXT          NOT NULL DEFAULT 'EXCLUSIVE',
    CONSTRAINT chk_markdown_tax_mode CHECK (tax_mode IN ('EXCLUSIVE', 'INCLUSIVE')),
    CONSTRAINT chk_markdown_qty     CHECK (qty > 0),
    CONSTRAINT chk_markdown_price   CHECK (markdown_price >= 0 AND markdown_price < original_price),
    CONSTRAINT chk_markdown_reason  CHECK (reason IN ('SHORT_DATED', 'CLEARANCE', 'DAMAGED_PACK', 'OVERSTOCK')),
    CONSTRAINT chk_markdown_status  CHECK (status IN ('ACTIVE', 'CANCELLED'))
);
-- One live sticker per code: a cancelled markdown's code can be reissued, an active one's cannot.
CREATE UNIQUE INDEX uq_markdown_label_active ON markdowns (tenant_id, label_code) WHERE status = 'ACTIVE';
CREATE INDEX idx_markdowns_store ON markdowns (tenant_id, store_id, status, expiry_date);
CREATE INDEX idx_markdowns_batch ON markdowns (tenant_id, batch_id) WHERE batch_id IS NOT NULL;

-- What sold at the reduced price, per order, so a markdown can be exhausted and a report can say
-- what reducing to clear cost and saved. Idempotent per (markdown, order): a replayed offline sale
-- or a retried checkout records once.
CREATE TABLE markdown_redemptions (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    markdown_id UUID          NOT NULL REFERENCES markdowns(id),
    order_id    UUID          NOT NULL,
    qty         NUMERIC(14,3) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_markdown_redemption UNIQUE (tenant_id, markdown_id, order_id)
);
CREATE INDEX idx_markdown_redemptions_markdown ON markdown_redemptions (tenant_id, markdown_id);
