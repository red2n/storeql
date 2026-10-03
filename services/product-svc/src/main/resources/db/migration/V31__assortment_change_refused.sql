-- A range change the sweep refuses for good is closed, not left due for ever (2 Oct 2026,
-- catalogue-hygiene).
--
-- A de-list that would take a line out of the last stores it is sold at can never be applied as it
-- reads: no product_stores rows means every store, the opposite of a de-list. It is refused when it
-- is recorded, judged on the line's whole plan, but the range can still move under it afterwards — a
-- PUT of the product's stores, another change recorded later, a store added to the cluster it aims
-- at — and the sweep then meets it on every run, refuses it every time, and it stays due for ever.
--
-- So the sweep closes it, once, with its reason: refused_at is set in the same transaction that
-- judged it, with the product row locked, and it is never due again. A change is applied or refused,
-- never both. The other refusals (a de-list of a line sold everywhere, a held manager's listing of a
-- line now sold everywhere, an empty cluster) still leave a change due: a range or a membership can
-- still allow those.

ALTER TABLE assortment_changes
    ADD COLUMN refused_at     TIMESTAMPTZ,
    ADD COLUMN refusal_code   TEXT,
    ADD COLUMN refusal_detail TEXT;

ALTER TABLE assortment_changes
    ADD CONSTRAINT ck_assortment_change_refusal CHECK (
        (refused_at IS NULL AND refusal_code IS NULL AND refusal_detail IS NULL)
        OR (refused_at IS NOT NULL AND length(btrim(refusal_code)) > 0
            AND length(btrim(refusal_detail)) > 0)
    ),
    ADD CONSTRAINT ck_assortment_change_settled_once CHECK (
        refused_at IS NULL OR applied_at IS NULL
    );

-- The sweep reads what is still open: neither applied nor closed.
DROP INDEX IF EXISTS idx_assortment_changes_pending;
CREATE INDEX idx_assortment_changes_pending
    ON assortment_changes (tenant_id, effective_from)
    WHERE applied_at IS NULL AND refused_at IS NULL;

COMMENT ON COLUMN assortment_changes.refused_at IS
    'When the sweep closed the change as refused for good (a de-list of a line''s last stores). Never due again.';
COMMENT ON COLUMN assortment_changes.refusal_code IS
    'The refusal it was closed with, e.g. ASSORTMENT_LAST_STORE.';
COMMENT ON COLUMN assortment_changes.refusal_detail IS
    'The sentence it was closed with, as the sweep reported it.';
