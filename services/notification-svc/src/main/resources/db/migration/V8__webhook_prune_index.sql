-- The prune of settled deliveries (created_at < cutoff AND status <> 'PENDING') ran as a sequential
-- scan; it now runs hourly in bounded batches and uses this partial index.
CREATE INDEX idx_webhook_deliveries_settled ON webhook_deliveries (created_at) WHERE status <> 'PENDING';
