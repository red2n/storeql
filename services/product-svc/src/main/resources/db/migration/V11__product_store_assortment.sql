-- Per-store product assortment: which stores carry (sell) a product.
-- Convention: a product with NO rows here is sold at ALL stores (default, unrestricted).
-- Adding rows restricts the product to exactly those stores.
-- product-svc references store_id (owned by tenant-svc) but never joins across services.
CREATE TABLE product_stores (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    product_id  UUID        NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id    UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, product_id, store_id)
);
CREATE INDEX idx_product_stores_tenant_store ON product_stores (tenant_id, store_id);
CREATE INDEX idx_product_stores_product ON product_stores (tenant_id, product_id);
