-- Gap #37: Container types / cartonization.
-- Tenant-defined container type catalogue (PALLET, CASE, INNER_PACK, EACH, etc.)
-- plus a variant-to-container link recording how many units fit per container.

CREATE TABLE container_types (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    code            TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    description     TEXT,
    length_mm       NUMERIC(10,2),
    width_mm        NUMERIC(10,2),
    height_mm       NUMERIC(10,2),
    max_weight_kg   NUMERIC(10,3),
    tare_weight_kg  NUMERIC(10,3),
    max_units       INT,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_container_types_tenant ON container_types (tenant_id);

CREATE TABLE variant_container_links (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    variant_id          UUID        NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    container_type_id   UUID        NOT NULL REFERENCES container_types(id) ON DELETE CASCADE,
    qty_per_container   INT         NOT NULL DEFAULT 1,
    is_primary          BOOLEAN     NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id, container_type_id)
);
CREATE INDEX idx_vcl_tenant_variant ON variant_container_links (tenant_id, variant_id);
