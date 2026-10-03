-- Correcting somebody's hours is a retryable write (workforce-rules: "Retryable writes
-- (Idempotency-Key): publish, adjust, …"), so it runs under the caller's Idempotency-Key and a
-- retry answers the correction the first attempt made instead of a 409 for an entry that attempt
-- already corrected.
--
-- One row per correction made through POST /admin/workforce/time-entries/{id}/adjust,
-- append-only: the entry corrected, the entry that now stands for it, under which key, who and
-- when. The key is the identity of the attempt (a UUIDv7 in canonical form, guarded by
-- afterMigrate's CHECK on every idempotency_key column), unique within the business; an entry is
-- corrected once (time_entries.superseded_by holds one successor), so it is unique too. The
-- supersede, the new entry, its breaks, its LabourRecorded and this row are one transaction.
--
-- Corrections made before this migration have no row: they were keyless, and a retry of one is
-- refused as it always was (WORKFORCE_ENTRY_NOT_STANDING).
CREATE TABLE time_entry_adjustments (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    entry_id         UUID        NOT NULL REFERENCES time_entries (id),
    correction_id    UUID        NOT NULL REFERENCES time_entries (id),
    idempotency_key  TEXT        NOT NULL,
    adjusted_by      UUID        NOT NULL,
    adjusted_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_time_entry_adjustments_key   UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_time_entry_adjustments_entry UNIQUE (tenant_id, entry_id)
);

COMMENT ON TABLE time_entry_adjustments IS
    'Which Idempotency-Key corrected which time entry, and the entry that stands for it: a retry of the correction is answered with it, never made twice.';
