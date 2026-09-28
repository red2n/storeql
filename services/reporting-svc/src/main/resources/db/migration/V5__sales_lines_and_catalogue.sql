-- Sales by category (reporting & analytics, 19.x).
--
-- sales_facts knows a sale as one row: an order, a total, a currency. A category report needs the
-- sale line by line — which variant, how many, for how much — and the catalogue's own word on where
-- each variant sits. Both are projections of events already published: OrderConfirmed now carries
-- its lines, and product-svc has announced ProductCategorised (a product's category path, leaf
-- first, root last, and the variants it names) and VariantCreated since PR #38.
--
-- What is NOT here is a category's name. The report answers in category ids; the catalogue that
-- owns the names is product-svc, and the app reads them from it. A copy of the names here would be
-- one more thing to keep in step for a label.
CREATE TABLE sales_line_facts (
    tenant_id    UUID          NOT NULL,
    order_id     UUID          NOT NULL,
    -- 1-based position in the order, as OrderConfirmed listed it: the same event twice is the same rows.
    line_no      INTEGER       NOT NULL,
    variant_id   UUID          NOT NULL,
    store_id     UUID,
    channel      TEXT,                          -- ONLINE | POS
    qty          NUMERIC(18,3) NOT NULL,
    -- Null when the line was priced off-platform; the line total is always known.
    unit_price   NUMERIC(18,4),
    line_total   NUMERIC(18,2) NOT NULL,
    currency     TEXT          NOT NULL,
    confirmed_at TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_sales_line_facts PRIMARY KEY (tenant_id, order_id, line_no),
    CONSTRAINT ck_sales_line_no CHECK (line_no >= 1)
);

CREATE INDEX idx_sales_line_facts_confirmed ON sales_line_facts (tenant_id, confirmed_at);
CREATE INDEX idx_sales_line_facts_variant ON sales_line_facts (tenant_id, variant_id);

-- A product's category path as last announced: leaf first, root last, empty for no category. A
-- later announcement replaces an earlier one; an earlier one redelivered late changes nothing.
CREATE TABLE catalogue_products (
    tenant_id     UUID        NOT NULL,
    product_id    UUID        NOT NULL,
    category_path UUID[]      NOT NULL,
    announced_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_catalogue_products PRIMARY KEY (tenant_id, product_id)
);

-- Which product a variant belongs to, from ProductCategorised's variant list and VariantCreated.
CREATE TABLE catalogue_variants (
    tenant_id  UUID NOT NULL,
    variant_id UUID NOT NULL,
    product_id UUID NOT NULL,

    CONSTRAINT pk_catalogue_variants PRIMARY KEY (tenant_id, variant_id)
);

CREATE INDEX idx_catalogue_variants_product ON catalogue_variants (tenant_id, product_id);
