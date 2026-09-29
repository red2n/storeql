-- ── Return controls (intent/return-controls.md) ─────────────────────────────
--
-- A return now says what condition each item came back in, is checked against the business's own
-- return policy before anything is written, names the manager who allowed it when it fell outside
-- the policy, and can be retried without paying twice.

-- The policy: one row per business. No row means the default (30 days, no cashier ceiling,
-- no-receipt returns off), applied in code, so a business that never sets one is not asked to.
-- The ceilings are in the business's home currency; no amount is assumed here.
CREATE TABLE return_policies (
    tenant_id          UUID PRIMARY KEY,
    window_days        INT NOT NULL,
    cashier_ceiling    NUMERIC(18,4),
    no_receipt_allowed BOOLEAN NOT NULL DEFAULT false,
    no_receipt_ceiling NUMERIC(18,4),
    updated_by         UUID,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_return_policies_window CHECK (window_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_return_policies_cashier CHECK (cashier_ceiling IS NULL OR cashier_ceiling >= 0),
    CONSTRAINT ck_return_policies_no_receipt
        CHECK (no_receipt_ceiling IS NULL OR no_receipt_ceiling >= 0)
);

-- The condition column was never written by anything but the admin screen, which sent GOOD for
-- every line. Map what is there onto the four conditions the platform now knows, and leave what
-- cannot be mapped unknown (NULL) rather than guess: those returns stay as they were recorded.
UPDATE return_items
   SET condition = CASE upper(btrim(condition))
                       WHEN 'GOOD'    THEN 'SEALED'
                       WHEN 'NEW'     THEN 'SEALED'
                       WHEN 'USED'    THEN 'OPENED'
                       WHEN 'DAMAGED' THEN 'DAMAGED'
                       ELSE NULL
                   END
 WHERE condition IS NOT NULL;

-- NULL stays allowed for those legacy rows; every new line must name one of the four.
ALTER TABLE return_items
    ADD CONSTRAINT ck_return_items_condition
    CHECK (condition IS NULL OR condition IN ('SEALED', 'OPENED', 'DAMAGED', 'FAULTY'));

-- idempotency_key: a retried return answers with the first one. approved_by: the sales.refund
-- holder who allowed a return outside the policy. outside_policy: why it needed them (WINDOW,
-- CEILING, FAULTY_PAST_WINDOW); an array, not a comma-joined string, so a reason can be asked for
-- with && and never split by mistake. gift_card_id: the card a GIFT_CARD refund was put on.
ALTER TABLE returns ADD COLUMN idempotency_key UUID;
ALTER TABLE returns ADD COLUMN approved_by UUID;
ALTER TABLE returns ADD COLUMN outside_policy TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE returns ADD COLUMN gift_card_id UUID;
CREATE UNIQUE INDEX uq_returns_idempotency_key
    ON returns (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- The same for a post-void: a retry answers with the first void.
ALTER TABLE pos_void_log ADD COLUMN idempotency_key UUID;
CREATE UNIQUE INDEX uq_pos_void_idempotency_key
    ON pos_void_log (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Receipt lookup at the till: by the printed fiscal number (case-insensitive) or by the short
-- reference the receipt prints, which is the last eight characters of the order id.
CREATE INDEX idx_fiscal_receipts_full_number ON fiscal_receipts (tenant_id, lower(full_number));
CREATE INDEX idx_orders_short_ref ON orders (tenant_id, (right(id::text, 8)));
