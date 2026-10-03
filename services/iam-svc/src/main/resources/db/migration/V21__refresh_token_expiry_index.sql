-- The daily purge of refresh tokens long expired scans by expiry.
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);
