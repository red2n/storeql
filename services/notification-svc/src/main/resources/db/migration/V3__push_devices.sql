-- ── Notification channels beyond email (13.7) ────────────────────────────────
--
-- SMS goes to a phone number and needs no table. Push goes to a device, and a device has to be
-- registered by the login that holds it before anything can reach it. Tenant-scoped like every
-- other table: a shopper registers from a storefront, a member of staff from their console, and
-- each shop pushes only to devices registered with it.
CREATE TABLE push_devices (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    user_id       UUID        NOT NULL,           -- the login that registered the device
    platform      TEXT        NOT NULL,           -- ANDROID | IOS | WEB
    token         TEXT        NOT NULL,           -- the push provider's device token
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, token)
);
CREATE INDEX idx_push_devices_tenant_user ON push_devices (tenant_id, user_id, last_seen_at DESC);
