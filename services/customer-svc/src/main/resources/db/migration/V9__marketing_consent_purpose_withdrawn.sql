-- The marketing-consent cascade: withdrawing the MARKETING purpose switches off every
-- marketing channel that is on, in the same transaction as the withdrawal. Each channel's
-- switch-off is logged in marketing_consent_log with its own source, PURPOSE_WITHDRAWN, so the
-- evidence trail says why the channel went off (the purpose, not a preference-centre click, an
-- unsubscribe link, or a member of staff) — never disguised as one of the existing sources.
ALTER TABLE marketing_consent_log DROP CONSTRAINT chk_consent_log_source;
ALTER TABLE marketing_consent_log ADD CONSTRAINT chk_consent_log_source CHECK (source IN (
    'SIGNUP', 'CHECKOUT', 'PREFERENCE_CENTRE', 'STAFF', 'UNSUBSCRIBE_LINK', 'IMPORT',
    'PURPOSE_WITHDRAWN'));
