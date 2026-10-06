-- One primary image per product, stored in-service as BYTEA (the stack has no object store yet;
-- images are owner-uploaded thumbnails, capped at 256 KB by the service). Served from
-- GET /catalog/products/{id}/image with a public 60-second cache (Cache-Control max-age=60).
--
-- Every image in this table is strictly under 256 KB, enforced here at the storage layer on every row.
-- The table is created by this migration, so it starts empty and no row can predate the CHECK. A
-- database that applied the earlier 512 KB migrations is not built from these files, and this comment
-- does not describe its rows; see docs/follow-ups.md (2b).
--
-- ProductService rejects oversized uploads at the API boundary, and the admin app compresses to the
-- same budget before it uploads. This constraint is what makes it an invariant rather than a
-- convention: images are read back in full on every storefront render, so nothing may depend on a
-- client — or a future service — having done the right thing on the way in.
CREATE TABLE product_images (
    product_id   UUID PRIMARY KEY REFERENCES products(id),
    tenant_id    UUID NOT NULL,
    content_type TEXT NOT NULL,
    bytes        BYTEA NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT product_images_size_under_256kb CHECK (octet_length(bytes) < 262144)
);
CREATE INDEX idx_product_images_tenant ON product_images (tenant_id);
