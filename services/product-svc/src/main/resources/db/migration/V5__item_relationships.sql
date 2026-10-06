-- Gap #32: item relationships — substitute and complementary links between variants.
-- Not append-only: a link is inserted and deleted, never updated. Remove the row to sever the link
-- (no soft-delete needed); to change a link, remove it and add the other.
CREATE TABLE item_relationships (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    variant_id          UUID        NOT NULL REFERENCES product_variants(id),
    related_variant_id  UUID        NOT NULL REFERENCES product_variants(id),
    relationship_type   TEXT        NOT NULL CHECK (relationship_type IN ('SUBSTITUTE','COMPLEMENTARY')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id, related_variant_id, relationship_type)
);
CREATE INDEX idx_item_rel_tenant_variant ON item_relationships (tenant_id, variant_id);
