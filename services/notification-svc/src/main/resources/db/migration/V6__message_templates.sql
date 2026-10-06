-- Message templates (13.x): a business puts its messages in its own words, per message, per form
-- (email, text, push, store alert) and per language. A template is never edited: saving writes the
-- next version and retires the one before, so what was sent can always be traced to the words it
-- was sent in. Retiring the live version returns that message, form and language to the platform's
-- own words.

CREATE TABLE message_templates (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    message_type TEXT        NOT NULL,   -- ORDER_CONFIRMED, RECALL_NOTICE… (the catalogue in code)
    form         TEXT        NOT NULL,   -- EMAIL | SMS | PUSH | ALERT
    language     TEXT        NOT NULL,   -- ISO 639-1 or 639-2, lower case
    version      INTEGER     NOT NULL,
    subject      TEXT        NOT NULL,
    body         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID        NOT NULL,
    retired_at   TIMESTAMPTZ,
    retired_by   UUID,
    CONSTRAINT uq_message_template_version UNIQUE (tenant_id, message_type, form, language, version),
    CONSTRAINT chk_message_template_form CHECK (form IN ('EMAIL', 'SMS', 'PUSH', 'ALERT')),
    CONSTRAINT chk_message_template_language CHECK (language ~ '^[a-z]{2,3}$'),
    CONSTRAINT chk_message_template_version CHECK (version > 0)
);
-- One live version per message, form and language: two saves racing each other cannot both win.
CREATE UNIQUE INDEX uq_message_templates_live
    ON message_templates (tenant_id, message_type, form, language) WHERE retired_at IS NULL;
CREATE INDEX idx_message_templates_history
    ON message_templates (tenant_id, message_type, form, language, version DESC);

-- How a business's messages go out: the language they are written in when the reader's is not
-- known, and the name they are signed with (the business's own name when this is empty).
CREATE TABLE message_settings (
    tenant_id        UUID        PRIMARY KEY,
    default_language TEXT        NOT NULL,
    sign_off         TEXT,
    updated_at       TIMESTAMPTZ NOT NULL,
    updated_by       UUID        NOT NULL,
    CONSTRAINT chk_message_settings_language CHECK (default_language ~ '^[a-z]{2,3}$')
);
