-- Substitutions for out-of-stock online lines (intent/substitutions-for-out-of-stock-online-lines.md).
-- A picker who finds a line short either closes it short — the unfilled quantity comes off the
-- order and the money for it goes back — or, where the shopper allowed it, puts a substitute in the
-- bag: a new line, charged at no more than the original, marked as standing in for it. Every id is
-- bound by the service.

-- The shopper's choice at checkout: on unless they turned it off.
ALTER TABLE orders ADD COLUMN allow_substitutions BOOLEAN NOT NULL DEFAULT true;

-- What of a line will never be handed over (closed short), and which line a substitute replaces.
-- outstanding = qty - fulfilled_qty - short_qty; a line's line_total and vat_amount are reduced pro
-- rata to what stands.
ALTER TABLE order_items
    ADD COLUMN short_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    ADD COLUMN substitutes_item_id UUID REFERENCES order_items (id),
    ADD CONSTRAINT ck_order_items_short_within CHECK (fulfilled_qty + short_qty <= qty);

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
    charged_amount        NUMERIC(18,2) NOT NULL DEFAULT 0,
    refund_amount         NUMERIC(18,2) NOT NULL DEFAULT 0,
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
