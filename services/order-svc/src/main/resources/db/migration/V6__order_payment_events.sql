-- Idempotency ledger for PaymentCaptured events. A split or partial tender publishes its own
-- PaymentCaptured for part of the order total, and orders.paid_amount accumulates them. Kafka redelivery
-- of the same paymentId must not double-count it into paid_amount (golden rule #7 — event consumers are
-- idempotent).
CREATE TABLE order_payment_events (
    tenant_id  UUID NOT NULL,
    payment_id UUID NOT NULL,
    order_id   UUID NOT NULL,
    amount     NUMERIC NOT NULL,
    -- How each tender was paid. The German file lists every payment as cash or not, and the TSE signs
    -- that split; the order's one payment_method cannot say how a cash-and-card sale divided.
    method     TEXT,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, payment_id)
);
CREATE INDEX idx_order_payment_events_order ON order_payment_events (tenant_id, order_id);
