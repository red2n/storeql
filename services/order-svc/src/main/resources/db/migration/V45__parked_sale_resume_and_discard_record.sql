-- A parked sale is now kept when it is finished with, and says who finished it and how.
--
-- Until now resuming was the till reading the basket and discarding it was a DELETE, so nothing
-- could say which cashier picked up a sale another had parked, or who threw one away. Loss
-- prevention asks exactly that ("who finished a sale someone else started?"), so the row stays:
-- resumed_at/resumed_by for a basket picked back up, discarded_at/discarded_by for one thrown
-- away. Either one takes the sale off the open list; a sale is finished once.
ALTER TABLE parked_sales
    ADD COLUMN resumed_by   UUID,
    ADD COLUMN discarded_at TIMESTAMPTZ,
    ADD COLUMN discarded_by UUID;

DROP INDEX IF EXISTS idx_parked_sales_tenant;
CREATE INDEX idx_parked_sales_open
    ON parked_sales (tenant_id, store_id, parked_at DESC)
    WHERE resumed_at IS NULL AND discarded_at IS NULL;
