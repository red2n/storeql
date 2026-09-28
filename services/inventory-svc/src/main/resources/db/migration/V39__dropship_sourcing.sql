-- Dropship (readiness review: "Consignment and dropship stock ownership"), inventory's side.
--
-- A dropship variant is stock the business never holds: purchase-svc sources it from a supplier
-- per order, shipped straight to the customer. inventory-svc still answers "is it available" and
-- still places the checkout hold that lets order-svc take the order — so it must know which
-- variants are fulfilled that way. purchase-svc, which owns the arrangement, announces it
-- (VariantSourcingChanged); this table is inventory-svc's projection of that, one row per variant,
-- the latest word winning.
CREATE TABLE variant_sourcing (
    tenant_id   UUID        NOT NULL,
    variant_id  UUID        NOT NULL,
    fulfilment  TEXT        NOT NULL,      -- STOCK (from the shelf) | DROPSHIP (from the supplier, per order)
    supplier_id UUID,                      -- the supplier that fulfils a DROPSHIP variant; purchase-svc's, referenced
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, variant_id),
    CONSTRAINT chk_variant_sourcing CHECK (fulfilment IN ('STOCK', 'DROPSHIP'))
);
CREATE INDEX idx_variant_sourcing_dropship
    ON variant_sourcing (tenant_id) WHERE fulfilment = 'DROPSHIP';

-- A hold on a dropship variant holds nothing on the shelf: it exists so the checkout can run as it
-- always has, and it says so, so consuming it draws no batch.
ALTER TABLE reservations
    ADD COLUMN fulfilment TEXT NOT NULL DEFAULT 'STOCK',
    ADD CONSTRAINT chk_reservation_fulfilment CHECK (fulfilment IN ('STOCK', 'DROPSHIP'));
