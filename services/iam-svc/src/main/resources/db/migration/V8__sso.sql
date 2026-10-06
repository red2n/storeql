-- Single sign-on through a business's own identity provider, over OpenID Connect.
--
-- A business connects one provider. Its staff are found by the sign-in name the business chose,
-- sent to the provider, and come back proved; they are matched to the logins the business already
-- has, never created, because a login's roles come from tenant-svc's assignments and a login with
-- no assignment can do nothing. A business may require the provider of some tiers of its staff, in
-- which case a password stops working for them — never for an owner, so a provider that breaks
-- cannot lock a business out of its own account.

CREATE TABLE sso_connections (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL,          -- one connection per business
    slug                   TEXT NOT NULL,          -- the sign-in name staff type, lower case, platform-unique
    issuer                 TEXT NOT NULL,          -- the provider's issuer, exactly as its discovery document states it
    client_id              TEXT NOT NULL,
    client_secret_sealed   TEXT,                   -- AES-256-GCM under the deployment's sealing secret; NULL after an import, until entered again
    enabled                BOOLEAN NOT NULL,
    required_tiers         TEXT NOT NULL,          -- comma-separated tiers a password no longer signs in; OWNER is never among them
    require_verified_email BOOLEAN NOT NULL,       -- a first sign-in is matched by email only when the provider says it verified it
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    updated_by             UUID NOT NULL
);
CREATE UNIQUE INDEX uq_sso_connections_tenant ON sso_connections (tenant_id);
CREATE UNIQUE INDEX uq_sso_connections_slug ON sso_connections (lower(slug));

-- Which of the provider's people is which login: the provider's subject, not the email, once the
-- first sign-in has matched them. An email can be reassigned to somebody else at the provider; a
-- subject cannot.
CREATE TABLE sso_identities (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issuer        TEXT NOT NULL,
    subject       TEXT NOT NULL,
    email         TEXT,                            -- as the provider asserted it at the last sign-in
    linked_at     TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_sso_identities_subject ON sso_identities (tenant_id, issuer, subject);
CREATE UNIQUE INDEX uq_sso_identities_user ON sso_identities (tenant_id, user_id, issuer);

-- A sign-in in flight: sent to the provider, then back with a ticket for the app to redeem. The
-- state, the ticket and the app's PKCE verifier are random; only their hashes are kept, and the
-- verifier for the provider is kept sealed. Minutes old at most.
CREATE TABLE sso_flows (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    connection_id          UUID NOT NULL REFERENCES sso_connections(id) ON DELETE CASCADE,
    state_hash             TEXT NOT NULL,
    nonce                  TEXT NOT NULL,
    code_verifier_sealed   TEXT NOT NULL,          -- this service's PKCE verifier for the provider
    app_challenge          TEXT NOT NULL,          -- S256 of the app's own verifier: only the app that started the flow can finish it
    return_to              TEXT NOT NULL,
    ticket_hash            TEXT,
    user_id                UUID REFERENCES users(id) ON DELETE CASCADE,
    amr                    TEXT,                   -- how the provider proved them, as the session will record it
    expires_at             TIMESTAMPTZ NOT NULL,
    returned_at            TIMESTAMPTZ,            -- the provider sent the browser back
    redeemed_at            TIMESTAMPTZ,            -- the app turned the ticket into a sign-in
    created_at             TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_sso_flows_state ON sso_flows (state_hash);
CREATE UNIQUE INDEX uq_sso_flows_ticket ON sso_flows (ticket_hash) WHERE ticket_hash IS NOT NULL;
CREATE INDEX idx_sso_flows_tenant ON sso_flows (tenant_id, created_at);
CREATE INDEX idx_sso_flows_expiry ON sso_flows (expires_at);
