-- The stranded-order sweeper reads only PENDING orders, oldest change first: without this it scans
-- and sorts the whole orders table every few minutes.
CREATE INDEX idx_orders_pending ON orders (updated_at) WHERE status = 'PENDING';

-- The fulfilment queue (online orders still owing goods at a store) reads a few hundred rows of a
-- store's whole history otherwise.
CREATE INDEX idx_orders_owing ON orders (tenant_id, store_id, created_at, id)
  WHERE channel = 'ONLINE' AND status IN ('CONFIRMED', 'PARTIALLY_FULFILLED');
