-- notification-svc schema

-- The consumer dedupe mark. The key is (event_id, consumer), not event_id alone: one consumer's
-- mark must not stop another consumer from applying the same event (each consumer name is one
-- purpose). Purged by processed_at once older than storeql.processed-events.retention-days, in
-- bounded batches (common-service OutboxPublisher); the index serves that purge.
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

CREATE TABLE shortage_alerts (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    store_id    UUID NOT NULL,
    variant_id  UUID NOT NULL,
    available   NUMERIC(19,4) NOT NULL,
    threshold   NUMERIC(19,4) NOT NULL,
    event_id    UUID NOT NULL,
    alerted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shortage_alerts_tenant ON shortage_alerts (tenant_id, alerted_at DESC);
CREATE INDEX idx_shortage_alerts_store  ON shortage_alerts (tenant_id, store_id, alerted_at DESC);
