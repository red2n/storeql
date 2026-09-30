-- A person's decision on a push whose outcome was unknown (UNCERTAIN): the journal did land in the
-- package (recorded delivered, under the package's own reference, never pushed again) or it never
-- landed (queued to be tried again). Kept on the row: who decided, when, and the note.
ALTER TABLE accounting_syncs
    ADD COLUMN resolution      TEXT,
    ADD COLUMN resolved_by     UUID,
    ADD COLUMN resolved_at     TIMESTAMPTZ,
    ADD COLUMN resolution_note TEXT,
    ADD CONSTRAINT ck_accounting_sync_resolution CHECK (resolution IS NULL OR resolution IN ('LANDED', 'NOT_LANDED'));
