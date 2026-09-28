-- Webhooks for a business's own systems (22.6): the events it asked to be told of, delivered to
-- the addresses it gave, signed with a secret only it and this service hold.
--
-- An endpoint is a URL, a description, the event types it wants and a secret — kept sealed under
-- the deployment's key, because it must be opened to sign each delivery, and shown to the owner
-- once when made and once when rotated. A delivery is one event to one endpoint: made when the
-- event arrives, tried until it lands or the attempts run out, and kept so the business can see
-- what was sent, when, and what came back. Every attempt is a row of its own, never rewritten.
CREATE TABLE webhook_endpoints (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    url                  TEXT NOT NULL,
    description          TEXT NOT NULL,
    secret_sealed        TEXT NOT NULL,         -- AES-GCM under storeql.webhooks.secrets-key
    events               TEXT[] NOT NULL,       -- the event types subscribed, e.g. OrderPlaced
    enabled              BOOLEAN NOT NULL,
    disabled_reason      TEXT,                  -- why this service switched it off, if it did
    consecutive_failures INT NOT NULL,          -- reset by a delivery that lands
    last_delivered_at    TIMESTAMPTZ,
    created_by           UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_webhook_endpoints_tenant ON webhook_endpoints (tenant_id, id);

CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    endpoint_id     UUID NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,
    event_id        UUID NOT NULL,
    event_type      TEXT NOT NULL,
    payload         TEXT NOT NULL,              -- the event as published, carried whole
    status          TEXT NOT NULL,              -- PENDING | DELIVERED | DEAD
    attempts        INT NOT NULL,
    next_attempt_at TIMESTAMPTZ,                -- when PENDING: not before this
    delivered_at    TIMESTAMPTZ,
    last_status     INT,                        -- the last HTTP status, if any answer came
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_webhook_deliveries_event ON webhook_deliveries (endpoint_id, event_id);
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_webhook_deliveries_tenant ON webhook_deliveries (tenant_id, id);
CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id, id);

-- Append-only: what each try got back.
CREATE TABLE webhook_attempts (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    delivery_id      UUID NOT NULL REFERENCES webhook_deliveries(id) ON DELETE CASCADE,
    attempt          INT NOT NULL,
    attempted_at     TIMESTAMPTZ NOT NULL,
    status_code      INT,                       -- NULL when nothing answered
    error            TEXT,
    response_snippet TEXT,                      -- the first kilobytes of what came back
    duration_ms      INT NOT NULL
);
CREATE INDEX idx_webhook_attempts_delivery ON webhook_attempts (delivery_id, attempt);
