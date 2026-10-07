-- Append-only archive for stock_movements rows purged from the hot table.
-- Golden rule #8 (CLAUDE.md): stock_movements must stay append-only — rows are
-- relocated here, never destroyed. Same shape as stock_movements + archived_at.
CREATE TABLE stock_movements_archive (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    store_id    UUID NOT NULL,
    variant_id  UUID NOT NULL,
    batch_id    UUID,
    type        TEXT NOT NULL,
    qty         NUMERIC(18,3) NOT NULL,
    ref_type    TEXT,
    ref_id      UUID,
    reason_code TEXT,
    -- As stock_movements.actor_id: carried over unchanged when a row is relocated.
    actor_id    UUID,
    created_at  TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_movements_archive_tenant ON stock_movements_archive (tenant_id, store_id, variant_id, created_at DESC);
