-- A sign-in is a session: the chain of refresh tokens it rotates through shares one session_id
-- (sign-in protection slice 3). Older tokens each stand for a session of their own.
ALTER TABLE refresh_tokens ADD COLUMN session_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE refresh_tokens ADD COLUMN device_label TEXT;
ALTER TABLE refresh_tokens ADD COLUMN network TEXT;
UPDATE refresh_tokens SET session_id = id, started_at = created_at;
ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE refresh_tokens ALTER COLUMN started_at SET NOT NULL;
CREATE INDEX idx_refresh_user_session ON refresh_tokens (user_id, session_id);
