-- Publishing a shift is a retryable write (workforce-rules: "Retryable writes (Idempotency-Key):
-- publish, …"), so it runs under the caller's Idempotency-Key and a retry answers the first
-- outcome instead of a 409 for a shift the first attempt already published.
--
-- One row per published shift, append-only: which key published it, who and when. The key is the
-- identity of the attempt (a UUIDv7 in canonical form, guarded by afterMigrate's CHECK on every
-- idempotency_key column), unique within the business; the shift is published once, so it is
-- unique too. The move of work_shifts.status and this row are written on one transaction.
CREATE TABLE shift_publications (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    shift_id         UUID        NOT NULL REFERENCES work_shifts (id),
    idempotency_key  TEXT        NOT NULL,
    published_by     UUID        NOT NULL,
    published_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_shift_publications_key   UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_shift_publications_shift UNIQUE (tenant_id, shift_id)
);
