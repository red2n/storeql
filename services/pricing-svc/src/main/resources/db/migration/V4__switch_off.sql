-- The switch that stops a promotion or a price list, and starts it again.
--
-- promotions.active and price_lists.active are the switches the engine filters on. A promotion
-- created without an end date runs until its switch is thrown, so a discount that cannot be
-- switched off is the most expensive kind of declared column with no writer. Both switches are
-- thrown through the service, and every throw is kept here with who threw it.

-- Why a table rather than columns on the row: a promotion can be switched off and on again
-- repeatedly, so there is a history, and a record that keeps only the last change cannot answer
-- "who turned this back on?". The same reasoning purchase_order_approvals is built on.
CREATE TABLE promotion_status_changes (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    -- Which switch was thrown. Both tables carry the same switch and the same audit need, so one
    -- trail serves both rather than two that will drift apart.
    subject_type VARCHAR(20) NOT NULL,
    subject_id   UUID        NOT NULL,
    -- What it was changed TO. Recorded as the new state rather than as a verb, so a reader does not
    -- have to know which way "toggled" went.
    active       BOOLEAN     NOT NULL,
    reason       TEXT        NOT NULL,
    changed_by   UUID,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_psc_subject CHECK (subject_type IN ('PROMOTION','PRICE_LIST'))
);

-- The trail for one promotion, newest first -- the query the screen and any dispute both need.
CREATE INDEX idx_psc_subject
    ON promotion_status_changes (tenant_id, subject_type, subject_id, changed_at DESC);
