-- Substitutions for out-of-stock online lines (intent/substitutions-for-out-of-stock-online-lines.md).
-- A picker who finds a line short either closes it short — the unfilled quantity comes off the
-- order and the money for it goes back — or, where the shopper allowed it, puts a substitute in the
-- bag: a new line, charged at no more than the original, marked as standing in for it. Every id is
-- bound by the service.
--
-- The line's short_qty and substitutes_item_id are on order_items, and orders.allow_substitutions is on
-- orders (V1__init.sql).

-- Append-only: each close or substitution, what it took off, what it charged, what it refunds.
CREATE TABLE order_line_adjustments (
    id                    UUID          NOT NULL,
    tenant_id             UUID          NOT NULL,
    order_id              UUID          NOT NULL REFERENCES orders (id),
    kind                  TEXT          NOT NULL,
    item_id               UUID          NOT NULL REFERENCES order_items (id),
    variant_id            UUID          NOT NULL,
    qty                   NUMERIC(18,3) NOT NULL,
    substitute_item_id    UUID          REFERENCES order_items (id),
    substitute_variant_id UUID,
    charged_amount        NUMERIC       NOT NULL DEFAULT 0,
    refund_amount         NUMERIC       NOT NULL DEFAULT 0,
    reason                TEXT,
    adjusted_by           UUID,
    idempotency_key       TEXT,
    adjusted_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_line_adjustments PRIMARY KEY (id),
    CONSTRAINT ck_order_line_adjustments_kind CHECK (kind IN ('SHORT_CLOSED', 'SUBSTITUTED')),
    CONSTRAINT ck_order_line_adjustments_qty CHECK (qty > 0),
    CONSTRAINT ck_order_line_adjustments_shape CHECK (
        (kind = 'SHORT_CLOSED' AND substitute_item_id IS NULL AND substitute_variant_id IS NULL)
        OR (kind = 'SUBSTITUTED' AND substitute_item_id IS NOT NULL AND substitute_variant_id IS NOT NULL))
);
CREATE INDEX idx_order_line_adjustments_order ON order_line_adjustments (tenant_id, order_id, adjusted_at);
CREATE UNIQUE INDEX uq_order_line_adjustments_key ON order_line_adjustments (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
