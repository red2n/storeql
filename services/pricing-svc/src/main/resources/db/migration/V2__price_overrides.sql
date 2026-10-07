-- POS price overrides — append-only audit log of staff-approved ad-hoc price changes.
-- Append-only: no UPDATE or DELETE (golden rule #8), except tenant erasure.
CREATE TABLE price_overrides (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    order_id        UUID,
    variant_id      UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    original_price  NUMERIC,
    override_price  NUMERIC       NOT NULL CHECK (override_price >= 0),
    override_reason TEXT,
    overridden_by   UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_price_overrides_tenant_store   ON price_overrides (tenant_id, store_id);
CREATE INDEX idx_price_overrides_tenant_variant ON price_overrides (tenant_id, variant_id);
CREATE INDEX idx_price_overrides_tenant_order   ON price_overrides (tenant_id, order_id)
    WHERE order_id IS NOT NULL;
