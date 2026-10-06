-- Multi-factor authentication. A login may hold an authenticator app (TOTP), passkeys
-- (WebAuthn) and ten single-use recovery codes; a business may require a second factor of its
-- staff by tier. Everything here except the policy is credential material or the record of it: a
-- secret is kept sealed or hashed, never exported, and a passkey's public parts are kept as the
-- authenticator sends them. All of it goes with the login.

CREATE TABLE mfa_totp (
    user_id        UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_sealed  TEXT NOT NULL,                  -- AES-256-GCM under the deployment's sealing secret
    status         TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE')),
    last_used_step BIGINT NOT NULL,                -- a code from this time step or before is refused: no code works twice
    created_at     TIMESTAMPTZ NOT NULL,
    confirmed_at   TIMESTAMPTZ
);

CREATE TABLE mfa_recovery_codes (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  TEXT NOT NULL,                      -- SHA-256 of a 60-bit random code
    created_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_mfa_recovery_code ON mfa_recovery_codes (user_id, code_hash);

CREATE TABLE mfa_passkeys (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id TEXT NOT NULL,                   -- base64url, as the authenticator names the key
    public_key    TEXT NOT NULL,                   -- the COSE key, base64
    sign_count    BIGINT NOT NULL,                 -- never goes backwards: a clone shows here
    name          TEXT NOT NULL,                   -- "Ana's laptop"
    user_verified BOOLEAN NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    last_used_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_mfa_passkey_credential ON mfa_passkeys (credential_id);
CREATE INDEX idx_mfa_passkeys_user ON mfa_passkeys (user_id);

-- A sign-in that has passed its first factor and owes a second, or a passkey registration in
-- progress. The token is random and only its hash is kept; a handful of wrong answers ends it.
CREATE TABLE mfa_challenges (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind               TEXT NOT NULL CHECK (kind IN ('LOGIN', 'PASSKEY_REGISTRATION')),
    token_hash         TEXT NOT NULL,
    webauthn_challenge TEXT,                       -- base64url; set when a passkey ceremony is opened
    attempts           INT NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    -- What the waiting sign-in proved before its second factor was asked for: a password, or the
    -- provider. NULL means a password.
    first_factor       TEXT
);
CREATE UNIQUE INDEX uq_mfa_challenge_token ON mfa_challenges (token_hash);
CREATE INDEX idx_mfa_challenges_expiry ON mfa_challenges (expires_at);

-- The business's rule: which tiers of its staff must have a second factor.
CREATE TABLE mfa_policies (
    tenant_id      UUID PRIMARY KEY,
    required_tiers TEXT NOT NULL,                  -- comma-separated tiers, e.g. OWNER,MANAGER; empty = nobody is made to
    updated_at     TIMESTAMPTZ NOT NULL,
    updated_by     UUID NOT NULL
);
