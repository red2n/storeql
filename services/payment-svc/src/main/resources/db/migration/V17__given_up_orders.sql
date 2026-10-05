-- Card terminals, settled on the server: the orders given up (2 Oct 2026, review of V16).
--
-- An order cancelled or voided owes back what a card machine took for it. That was worked out once,
-- when the order event arrived, from what was known then. An approval learnt of afterwards — a
-- timeout a manager later saw approved, or a card still at the machine when the sale was given up
-- and approved a moment later — was owed back to nobody, and a till replaying its queued tender could
-- then record it as paid on the given-up order: the customer paid for a sale that never happened,
-- with nothing owed and nothing flagged.
--
-- Whether an order was given up is kept here, so the moment a sale on it is known to have taken
-- money, what it took is owed back on that same transaction (card_refund_dues, source ORDER_EVENT)
-- and put back through the machine; and no card a machine may have taken is recorded on it, nor a
-- new one taken for it (409 PAYMENT_ORDER_GIVEN_UP).
--
-- Written once per order, by the first order event that gives it up, on that event's own
-- transaction, after the order's unrecorded sales on terminals are locked — so a machine's answer, a
-- person's word or a tender naming one is taken wholly before it or wholly after, and sees it.
-- Never updated or deleted.
CREATE TABLE given_up_orders (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    order_id    UUID        NOT NULL,
    -- The OrderCancelled or OrderVoided that gave it up: the key money owed back is asked under is
    -- derived from it, so it is owed once whichever path learns of it first.
    event_id    UUID        NOT NULL,
    reason      TEXT        NOT NULL,
    given_up_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_given_up_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    -- Given up once: a second event that gives it up changes nothing.
    CONSTRAINT uq_given_up_order UNIQUE (tenant_id, order_id)
);

COMMENT ON TABLE given_up_orders IS
    'Orders cancelled or voided: a card a machine takes for one afterwards is owed back. Written once.';
