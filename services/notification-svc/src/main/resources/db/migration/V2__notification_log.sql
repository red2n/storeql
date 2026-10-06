-- Outbound notification delivery log (N1): consumes user/order events and dispatches a message
-- through a channel, recording each send here. UNIQUE(event_id, type) makes a redelivered event a
-- no-op so a customer never gets the same welcome/confirmation twice.
--
-- subject_id is the customer (for a shop's message), the supplier (for a remittance advice) or the
-- account (for a platform message such as WELCOME) the notification was about. redacted_at records
-- that the recipient, subject and body were erased; the row stays so the send itself remains
-- accounted for. A send that names no subject has none, and cannot be found by one.
--
-- language and template record what the message was written in, and from which words: 'default'
-- for the platform's catalogue words, 'v3' for the business's third version. NULL where the words
-- were not chosen from the catalogue: a send a shop typed itself, the welcome note, and the
-- password emails, which record their language and no template.
CREATE TABLE notification_log (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID,                       -- NULL: a platform message that belongs to no business
    subject_id  UUID,
    event_id    UUID        NOT NULL,
    type        TEXT        NOT NULL,       -- e.g. WELCOME, ORDER_CONFIRMATION
    channel     TEXT        NOT NULL,       -- APP | PUSH | MQTT | SMS | SMTP | EMAIL (password)
    recipient   TEXT        NOT NULL,
    subject     TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    status      TEXT        NOT NULL,       -- SENT | SUPPRESSED | NOT_SENT
    language    TEXT,
    template    TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    redacted_at TIMESTAMPTZ,
    UNIQUE (event_id, type)
);

CREATE INDEX idx_notification_log_tenant ON notification_log (tenant_id, created_at);
CREATE INDEX idx_notification_log_subject
    ON notification_log (subject_id) WHERE subject_id IS NOT NULL;
