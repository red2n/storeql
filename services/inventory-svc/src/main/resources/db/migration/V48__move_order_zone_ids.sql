-- inventory-screens slice 2: a move order names the zones it moves between by id (tenant-svc's zone
-- ids, referenced never joined). The free-text from_zone / to_zone stay readable on old orders.
ALTER TABLE move_orders ADD COLUMN from_zone_id UUID;
ALTER TABLE move_orders ADD COLUMN to_zone_id UUID;
