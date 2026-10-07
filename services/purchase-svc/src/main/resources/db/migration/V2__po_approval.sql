-- Purchase order approval with spend authority limits (readiness review, horizon 2 item 5).
--
-- Anyone holding a staff role could commit the business to any amount: /purchase-orders is not under
-- /admin/, so AdminAuthorizationFilter's default-deny for mutations required only "some staff role".
-- A CASHIER could raise a purchase order for a million pounds and submit it, and nothing compared the
-- figure to the person. The approval limit compares a real figure in a known currency: the order's
-- total is its own (SJ-D22) and its currency is its own (SJ-D23), so a threshold means the same
-- thing for every order. The order's approval state and its approver are columns on purchase_orders
-- (V1); the decisions themselves are kept here.

-- Append-only (golden rule #8). Columns on the order would hold a single decision, but a rejection
-- sends the order back to DRAFT to be edited and resubmitted, so one order can cycle through several
-- decisions. Only a table can hold that, and a spend-authority trail that keeps just the last decision
-- is not an audit trail.
CREATE TABLE purchase_order_approvals (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    po_id        UUID        NOT NULL REFERENCES purchase_orders(id),
    decision     VARCHAR(20) NOT NULL,
    -- The figure the decision was actually made against, captured at decision time rather than read
    -- back from the order later: the order can be edited after a rejection, and an approval that
    -- silently re-points at a larger total is the whole attack this feature exists to stop.
    total_net    NUMERIC     NOT NULL,
    currency     CHAR(3)     NOT NULL,
    -- The authority the decider held, so the trail answers "were they allowed to?" without
    -- depending on configuration that has since changed. NULL for a REQUESTED row, which records a
    -- submission rather than a decision.
    authority    NUMERIC,
    decided_by   UUID,
    decided_role VARCHAR(20),
    reason       TEXT,
    decided_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_poa_decision CHECK (decision IN ('REQUESTED','APPROVED','REJECTED')),
    -- A rejection must say why. An approval need not: "yes" is complete on its own, "no" is not,
    -- because only the rejection leaves someone with work to do and no idea what to change.
    CONSTRAINT chk_poa_reason CHECK (decision <> 'REJECTED' OR reason IS NOT NULL)
);

CREATE INDEX idx_poa_po ON purchase_order_approvals (tenant_id, po_id, decided_at DESC);
