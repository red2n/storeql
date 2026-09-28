-- Consignment stock ownership (readiness review: "Consignment and dropship stock ownership").
--
-- Stock on the shelf is not always the business's. Under a consignment (sale-or-return)
-- agreement the supplier still owns what it delivered, the business sells it like any other
-- stock, and only the sale creates a debt to the supplier. Until now every batch was silently the
-- business's own: a balance sheet counted the supplier's goods as an asset, and the moment the
-- goods sold nobody was told the supplier was owed.
--
-- A batch now says whose it is. OWNED is the business's own; CONSIGNMENT is the supplier's, and
-- then the supplier is named (tenant-svc has no suppliers; purchase-svc owns them — the id is
-- referenced, never joined). Ownership rides with the stock through a transfer or a lot split, it
-- is what the valuation report sets apart, and a sale drawn from a consignment batch is announced
-- to purchase-svc as ConsignmentStockSold at the batch's cost, which is the order's price.
ALTER TABLE inventory_batches
    ADD COLUMN ownership         TEXT NOT NULL DEFAULT 'OWNED',
    ADD COLUMN owner_supplier_id UUID,
    ADD CONSTRAINT chk_batch_ownership CHECK (ownership IN ('OWNED', 'CONSIGNMENT')),
    ADD CONSTRAINT chk_batch_owner_named
        CHECK (ownership = 'OWNED' OR owner_supplier_id IS NOT NULL);

-- The consignment holding per supplier: what a settlement statement is checked against.
CREATE INDEX idx_batches_consignment
    ON inventory_batches (tenant_id, owner_supplier_id, variant_id)
    WHERE ownership = 'CONSIGNMENT';
