-- Manual grants: who handed out points or store credit by hand, and why.
--
-- A loyalty adjustment (up or down), a manual award of points and an issue of store credit by hand
-- are management's decisions (OWNER/MANAGER), so each keeps the person who made it and the reason.
-- The ledgers stay exactly as they were (append-only); this table is written on the same transaction
-- as the ledger entry it explains, and is itself append-only: only ever inserted.
--
-- It is also what makes a manual award or adjustment safe to retry: a request carries an
-- Idempotency-Key, and (tenant_id, idempotency_key) is unique, so the same key writes once. Store
-- credit issued by hand carries no key today, so its rows have a null one.
CREATE TABLE manual_grants (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    customer_id     UUID          NOT NULL,
    kind            TEXT          NOT NULL,             -- LOYALTY_EARN | LOYALTY_ADJUST | STORE_CREDIT_ISSUE
    amount          NUMERIC(18,2) NOT NULL,             -- points, or money in currency; signed for an adjustment
    currency        TEXT,                               -- store credit only
    reason          TEXT,
    actor_id        UUID,                               -- the signed-in user who did it
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_manual_grants_key
    ON manual_grants (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_manual_grants_customer ON manual_grants (tenant_id, customer_id, created_at DESC);
