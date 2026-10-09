-- Catalogue import (intent/catalogue-import.md): a supermarket's own export in, checked first, applied
-- in restart-safe chunks, reconciled to the penny. Every table is the business's: tenant_id first.

-- How a business's export maps onto StoreQL, saved once under a name and applied to every store's file
-- by header text. The mapping is JSON; its hash says which mapping a job ran under.
CREATE TABLE import_mappings (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    name          TEXT        NOT NULL,
    mapping       TEXT        NOT NULL,
    mapping_hash  CHAR(64)    NOT NULL,
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_import_mappings_tenant_name UNIQUE (tenant_id, name)
);

-- The file as uploaded, kept whole so a job can be resumed from it and the report can be checked
-- against it. Append-only: a file is never changed or removed. The same file for the same store is
-- one row (sha256), so a retried upload makes no second copy.
CREATE TABLE import_files (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    store_id      UUID        NOT NULL,
    file_name     TEXT        NOT NULL,
    sha256        CHAR(64)    NOT NULL,
    size_bytes    INTEGER     NOT NULL,
    content       BYTEA       NOT NULL,
    encoding      TEXT        NOT NULL,
    delimiter     CHAR(1)     NOT NULL,
    row_count     INTEGER     NOT NULL,
    headers       TEXT        NOT NULL,
    uploaded_by   UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_import_files_tenant_store_sha UNIQUE (tenant_id, store_id, sha256),
    CONSTRAINT chk_import_files_size CHECK (size_bytes > 0 AND size_bytes <= 12582912)
);

-- One run of a file under a mapping: a DRY_RUN writes only its row results; an APPLY follows a dry run of
-- the same file and mapping and carries the work in import_chunks. Progress records, not history.
CREATE TABLE import_jobs (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    store_id         UUID        NOT NULL,
    file_id          UUID        NOT NULL,
    mapping_id       UUID        NOT NULL,
    mapping_hash     CHAR(64)    NOT NULL,
    file_sha256      CHAR(64)    NOT NULL,
    kind             TEXT        NOT NULL,
    dry_run_of       UUID,
    price_list_id    UUID,
    status           TEXT        NOT NULL,
    phase            TEXT,
    counts           TEXT        NOT NULL DEFAULT '{}',
    failure_code     TEXT,
    failure_detail   TEXT,
    idempotency_key  TEXT,
    started_by       UUID,
    -- Who started it, as the gateway described them (roles, stores, permissions), so the worker asks
    -- the other services as that person asked and never as more. No token is stored.
    starter          TEXT        NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    last_progress_at TIMESTAMPTZ,
    lease_until      TIMESTAMPTZ,
    CONSTRAINT fk_import_jobs_file    FOREIGN KEY (file_id)    REFERENCES import_files (id),
    CONSTRAINT fk_import_jobs_mapping FOREIGN KEY (mapping_id) REFERENCES import_mappings (id),
    CONSTRAINT fk_import_jobs_dry_run FOREIGN KEY (dry_run_of) REFERENCES import_jobs (id),
    CONSTRAINT chk_import_jobs_kind   CHECK (kind IN ('DRY_RUN', 'APPLY')),
    CONSTRAINT chk_import_jobs_status CHECK (status IN
        ('DRY_RUN_DONE', 'DRY_RUN_FAILED', 'QUEUED', 'APPLYING', 'DONE', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_import_jobs_apply_follows CHECK (kind = 'DRY_RUN' OR dry_run_of IS NOT NULL)
);
CREATE INDEX idx_import_jobs_tenant ON import_jobs (tenant_id, created_at DESC);
CREATE UNIQUE INDEX uq_import_jobs_tenant_key ON import_jobs (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- One apply at a time per business: a second would write the same catalogue twice over.
CREATE UNIQUE INDEX uq_import_jobs_one_applying ON import_jobs (tenant_id)
    WHERE status IN ('QUEUED', 'APPLYING');
CREATE INDEX idx_import_jobs_work ON import_jobs (lease_until) WHERE status IN ('QUEUED', 'APPLYING');

-- What the job found in (or did to) each row: the report the owner reads. Append-only.
CREATE TABLE import_row_results (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    job_id      UUID        NOT NULL,
    line        INTEGER     NOT NULL,
    sku         TEXT,
    action      TEXT        NOT NULL,
    refusals    TEXT        NOT NULL DEFAULT '[]',
    gaps        TEXT        NOT NULL DEFAULT '[]',
    changes     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_import_row_results_job FOREIGN KEY (job_id) REFERENCES import_jobs (id),
    CONSTRAINT uq_import_row_results_job_line UNIQUE (tenant_id, job_id, line),
    CONSTRAINT chk_import_row_results_action CHECK (action IN
        ('CREATE', 'UPDATE', 'UNCHANGED', 'SKIPPED', 'REFUSED'))
);
CREATE INDEX idx_import_row_results_job ON import_row_results (tenant_id, job_id, action, line);

-- The apply's work in 500-row pieces, so a killed worker resumes at the first unfinished one and no
-- row is written twice (each piece is written and marked done on one transaction).
CREATE TABLE import_chunks (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    job_id      UUID        NOT NULL,
    phase       TEXT        NOT NULL,
    seq         INTEGER     NOT NULL,
    first_line  INTEGER     NOT NULL,
    last_line   INTEGER     NOT NULL,
    row_count   INTEGER     NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'PENDING',
    detail      TEXT,
    done_at     TIMESTAMPTZ,
    CONSTRAINT fk_import_chunks_job FOREIGN KEY (job_id) REFERENCES import_jobs (id),
    CONSTRAINT uq_import_chunks_job_phase_seq UNIQUE (tenant_id, job_id, phase, seq),
    CONSTRAINT chk_import_chunks_phase CHECK (phase IN ('PRODUCTS', 'VAT', 'PRICES', 'STOCK')),
    CONSTRAINT chk_import_chunks_status CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);
CREATE INDEX idx_import_chunks_job ON import_chunks (tenant_id, job_id, phase, status, seq);

-- Further codes that find the same product: an old EAN, a multipack, a case, a PLU. One code names one
-- variant in a business (compared as GTIN-14, so an EAN-13 and its GTIN-14 are the same code). The pack
-- quantity says how many units a scan of it stands for. Append-only.
CREATE TABLE variant_barcode_aliases (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    variant_id  UUID        NOT NULL,
    gtin14      CHAR(14)    NOT NULL,
    kind        TEXT        NOT NULL,
    pack_qty    INTEGER     NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_variant_barcode_aliases_variant FOREIGN KEY (variant_id) REFERENCES product_variants (id),
    CONSTRAINT uq_variant_barcode_aliases_tenant_gtin UNIQUE (tenant_id, gtin14),
    CONSTRAINT chk_variant_barcode_aliases_kind CHECK (kind IN ('OLD_EAN', 'MULTIPACK', 'CASE', 'PLU')),
    CONSTRAINT chk_variant_barcode_aliases_pack CHECK (pack_qty >= 1)
);
CREATE INDEX idx_variant_barcode_aliases_variant ON variant_barcode_aliases (tenant_id, variant_id);
