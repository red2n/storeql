-- Asymmetric token signing with key rotation (RFC 8725, OWASP ASVS V9).
--
-- iam-svc signed access tokens HS256 with a secret the gateway (and the MQTT broker, and
-- notification-svc) also held, so anything holding it could mint an owner's token. Tokens are now
-- signed RS256 with a private key only iam-svc can unseal; verifiers fetch the public half from
-- /auth/.well-known/jwks.json by the token's key id. One key signs at a time (ACTIVE); a rotated
-- key stays published while the tokens it signed can still be alive (RETIRING), then is RETIRED
-- and its private half wiped. A platform table: keys belong to the deployment, not to a business.
CREATE TABLE signing_keys (
    kid                 TEXT PRIMARY KEY,          -- the key id in a token's header and in the JWKS
    algorithm           TEXT NOT NULL,             -- RS256
    public_key          TEXT NOT NULL,             -- X.509 SubjectPublicKeyInfo, base64
    private_key_sealed  TEXT NOT NULL,             -- PKCS#8 sealed AES-256-GCM; '' once retired
    status              TEXT NOT NULL,             -- ACTIVE | RETIRING | RETIRED
    created_at          TIMESTAMPTZ NOT NULL,
    retiring_at         TIMESTAMPTZ,
    retired_at          TIMESTAMPTZ,
    CONSTRAINT chk_signing_keys_status CHECK (status IN ('ACTIVE', 'RETIRING', 'RETIRED'))
);
-- One key signs at a time, whatever the number of replicas racing to create or rotate it.
CREATE UNIQUE INDEX uq_signing_keys_one_active ON signing_keys (status) WHERE status = 'ACTIVE';
