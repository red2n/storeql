-- Hardening: a sale or a hold walks the SKU's batches that still hold stock, not its whole
-- history of emptied ones. The partial index holds only those, in the order a draw takes them.
CREATE INDEX idx_batches_live
    ON inventory_batches (tenant_id, store_id, variant_id, expiry_date NULLS LAST, created_at)
    WHERE remaining_qty > 0 AND material_status = 'AVAILABLE';

-- Reservations were only indexed by (status, expires_at), with no tenant first. Every read of
-- what is held for a store and variant, an order or a tenant's newest holds starts at the tenant.
CREATE INDEX idx_reservations_held_variant
    ON reservations (tenant_id, store_id, variant_id)
    WHERE status = 'HELD';
CREATE INDEX idx_reservations_order
    ON reservations (tenant_id, order_id);
CREATE INDEX idx_reservations_tenant_created
    ON reservations (tenant_id, created_at DESC);
