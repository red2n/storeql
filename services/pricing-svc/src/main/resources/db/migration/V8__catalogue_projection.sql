-- Catalogue projection: what product-svc publishes and this service must hold beside its prices.
--
-- A promotion scoped to a CATEGORY needs to know which variants are in which category, and a unit
-- price needs how a variant is sold. product-svc publishes both, and these tables are the
-- projection: ProductCategorised carries a product's category path (its own category up to the
-- root) and its variant ids, VariantCreated names the product a new variant belongs to, and
-- VariantMeasured carries the standard unit and the quantity one price buys. A category scope
-- resolves to variants through them at quote time, and a promotion scoped to a parent category
-- reaches the products of its children because the whole path is kept.

-- Consumer dedupe. The key is (event_id, consumer), not event_id alone: one consumer's mark must
-- not stop another consumer from applying the same event, because each consumer name is one purpose.
CREATE TABLE processed_events (
    event_id     UUID NOT NULL,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, consumer)
);
-- The scheduled purge deletes the rows older than the dedupe window, oldest first, in batches.
-- A handler reaches processed_events only through its primary key: the insert that marks an event
-- handled is ON CONFLICT on (event_id, consumer). The purge finds the rows by processed_at instead,
-- so the plain index on the timestamp is the whole cost of letting it do so.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

CREATE TABLE catalogue_products (
    tenant_id     UUID   NOT NULL,
    product_id    UUID   NOT NULL,
    -- The product's category, then its parent, then the parent's parent, up to the root.
    category_path UUID[] NOT NULL DEFAULT '{}',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, product_id)
);
CREATE INDEX idx_catalogue_products_path ON catalogue_products USING GIN (category_path);

-- Unit pricing (the Price Marking Order 2004 as amended, from 6 April 2026, and Directive 98/6/EC
-- art.3): a unit price beside a selling price, per kilogram, litre, metre, square metre, or per item
-- for goods sold by number, for the price the shopper is actually charged, promotional prices
-- included. The measure columns keep the standard unit and the quantity one price buys. No measure
-- means none was declared, and the quote says so instead of showing a wrong unit price.
CREATE TABLE catalogue_variants (
    tenant_id        UUID NOT NULL,
    variant_id       UUID NOT NULL,
    product_id       UUID NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    sold_by          TEXT,
    measure_unit     TEXT CHECK (measure_unit IN ('KG', 'L', 'M', 'SQM', 'EA')),
    measure_quantity NUMERIC(18, 6) CHECK (measure_quantity > 0),
    measured_at      TIMESTAMPTZ,
    -- product-svc's version of the measure: an older one arriving late never replaces a newer.
    measure_version  BIGINT CHECK (measure_version >= 0),
    PRIMARY KEY (tenant_id, variant_id),
    CONSTRAINT chk_catalogue_variant_measure_pair
        CHECK ((measure_unit IS NULL) = (measure_quantity IS NULL))
);
CREATE INDEX idx_catalogue_variants_product ON catalogue_variants (tenant_id, product_id);
