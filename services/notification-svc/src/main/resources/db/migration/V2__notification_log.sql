-- Outbound notification delivery log (N1): consumes user/order events and dispatches a message
-- through a channel, recording each send here. UNIQUE(event_id, type) makes a redelivered event a
-- no-op so a customer never gets the same welcome/confirmation twice.
--
-- subject_id is the customer (for a shop's message), the supplier (for a remittance advice) or the
-- account (for a platform message such as WELCOME) the notification was about. redacted_at records
-- that the recipient, subject and body were erased; the row stays so the send itself remains
-- accounted for. A send that names no subject has none, and cannot be found by one.
--
-- channel is the one that carried the message, spelt as that channel names itself: APP, PUSH, MQTT,
-- SMS or SMTP. EMAIL is a name to ask for on the API (the deployment's default channel), never a
-- value here: the password emails, which only SMTP carries, say SMTP like every other email.
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
    channel     TEXT        NOT NULL,       -- the channel that carried it: APP | PUSH | MQTT | SMS | SMTP
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

-- The erasure of what a message was about: a shop's messages about one customer
--   WHERE tenant_id = ? AND subject_id = ? AND redacted_at IS NULL
-- and the platform's about one account
--   WHERE tenant_id IS NULL AND subject_id = ? AND redacted_at IS NULL
-- (NotificationRepository.REDACT_FOR_CUSTOMER and REDACT_FOR_ACCOUNT). Both lead with the tenant,
-- the second through its null, so one index serves both. No statement asks for a subject without
-- the tenant, so there is no index on subject_id alone.
CREATE INDEX idx_notification_log_tenant_subject
    ON notification_log (tenant_id, subject_id) WHERE subject_id IS NOT NULL;
