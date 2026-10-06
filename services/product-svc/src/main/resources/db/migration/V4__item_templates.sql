CREATE TABLE item_templates (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    attributes  TEXT,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_item_templates   PRIMARY KEY (id),
    CONSTRAINT uq_item_tpl_name    UNIQUE (tenant_id, name),
    CONSTRAINT chk_item_tpl_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_item_tpl_tenant ON item_templates (tenant_id, status);

CREATE TABLE item_template_applications (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    variant_id  UUID        NOT NULL REFERENCES product_variants(id),
    template_id UUID        NOT NULL REFERENCES item_templates(id),
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_item_tpl_app PRIMARY KEY (id)
);
CREATE INDEX idx_item_tpl_app_variant ON item_template_applications (tenant_id, variant_id);
