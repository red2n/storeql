-- Gap #33: supplier / customer cross-references.
-- Maps a variant to an external part number used by a supplier or B2B customer.
-- party_id is a logical UUID (supplier or customer) — no FK because those entities
-- live in purchase-svc / customer-svc (database-per-service rule).
CREATE TABLE item_cross_references (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    variant_id       UUID        NOT NULL REFERENCES product_variants(id),
    party_type       TEXT        NOT NULL CHECK (party_type IN ('SUPPLIER','CUSTOMER')),
    party_id         UUID        NOT NULL,
    party_name       TEXT,
    cross_ref_number TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id, party_type, party_id)
);
CREATE INDEX idx_xref_tenant_variant   ON item_cross_references (tenant_id, variant_id);
CREATE INDEX idx_xref_tenant_party     ON item_cross_references (tenant_id, party_type, party_id);
