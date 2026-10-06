-- Gap #39: Category sets (flexfields) — multi-set category model.
-- The existing flat `categories` tree is unchanged. This layer adds named
-- category sets (Oracle category_sets), each with its own membership list,
-- so variants can carry one category assignment per set independently.

CREATE TABLE category_sets (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    name            TEXT        NOT NULL,
    description     TEXT,
    purpose         TEXT        NOT NULL DEFAULT 'GENERAL',
    default_cat_id  UUID        REFERENCES categories(id) ON DELETE SET NULL,
    controlled      BOOLEAN     NOT NULL DEFAULT false,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_category_sets_tenant ON category_sets (tenant_id);

-- Which categories are valid members of a set.
CREATE TABLE category_set_members (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    set_id          UUID        NOT NULL REFERENCES category_sets(id) ON DELETE CASCADE,
    category_id     UUID        NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, set_id, category_id)
);
CREATE INDEX idx_csm_tenant_set ON category_set_members (tenant_id, set_id);

-- Per-variant category assignment within a specific set.
-- A variant has at most one active category per set.
CREATE TABLE variant_category_set_assignments (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    variant_id      UUID        NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    set_id          UUID        NOT NULL REFERENCES category_sets(id) ON DELETE CASCADE,
    category_id     UUID        NOT NULL REFERENCES categories(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id, set_id)
);
CREATE INDEX idx_vcsa_tenant_variant ON variant_category_set_assignments (tenant_id, variant_id);
CREATE INDEX idx_vcsa_tenant_set     ON variant_category_set_assignments (tenant_id, set_id);
