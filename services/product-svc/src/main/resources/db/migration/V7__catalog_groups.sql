-- Gap #35: Item catalog groups and descriptive elements.
-- Catalog groups are named sets of typed specification fields (descriptive elements).
-- Variants can be assigned to exactly one catalog group and supply values per element.

-- Named catalog groups (e.g. "Electronics Spec", "Apparel Spec")
CREATE TABLE catalog_groups (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_catalog_groups_tenant ON catalog_groups (tenant_id);

-- Typed descriptive elements within a catalog group
CREATE TABLE catalog_group_elements (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    group_id     UUID        NOT NULL REFERENCES catalog_groups(id) ON DELETE CASCADE,
    element_name TEXT        NOT NULL,
    data_type    TEXT        NOT NULL CHECK (data_type IN ('TEXT','NUMBER','BOOLEAN','DATE')),
    required     BOOLEAN     NOT NULL DEFAULT false,
    default_val  TEXT,
    sort_order   INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, group_id, element_name)
);
CREATE INDEX idx_catalog_elements_group ON catalog_group_elements (tenant_id, group_id);

-- Variant-to-catalog-group assignment with typed element values (JSONB key→value map)
CREATE TABLE variant_catalog_assignments (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    variant_id   UUID        NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    group_id     UUID        NOT NULL REFERENCES catalog_groups(id),
    element_vals JSONB       NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id)
);
CREATE INDEX idx_vca_tenant_variant ON variant_catalog_assignments (tenant_id, variant_id);
CREATE INDEX idx_vca_tenant_group   ON variant_catalog_assignments (tenant_id, group_id);
