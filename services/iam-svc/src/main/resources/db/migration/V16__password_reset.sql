-- Forgotten password (intent/password-reset.md, the design system's Login card): a link, minted
-- per eligible login and spent once, that lets a person back in without proving who they are
-- first — the link itself is the proof. The raw token is never stored, only its hash.
--
-- password_reset_tokens.tenant_id is the LOGIN's own tenant (NULL for a shopper), carried here for
-- isolation, export and erasure (21.14) — never the forgot-password request's own, which belongs
-- to no business. ON DELETE CASCADE clears a login's tokens if its user row is ever hard-deleted;
-- the one path that anonymises a row instead of deleting it (UserRepository.deleteCustomerAccount)
-- clears them explicitly, in the same transaction, for the same reason.
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id   UUID,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    replaced_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);
CREATE INDEX idx_password_reset_tokens_expires ON password_reset_tokens (expires_at);

-- The abuse throttle on forgotten-password requests (storeql.iam.password-reset.max-per-hour, a
-- few an address an hour): address_hash is the SHA-256 of the lower-cased, trimmed address, never
-- the address itself, so this table carries nothing to leak and belongs to no business.
CREATE TABLE password_reset_requests (
    id           UUID PRIMARY KEY,
    address_hash TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_password_reset_requests_addr ON password_reset_requests (address_hash, requested_at);
