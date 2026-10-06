-- EMV terminals and pinpads (07.16): the till stops taking a card payment on trust.
--
-- A CARD tender was recorded because a cashier said so. Nothing asked a terminal whether the card was
-- actually approved, and nothing kept what an EMV receipt has to carry — the scheme, the masked digits,
-- the authorisation code, the application the card ran, how it was read and how the cardholder was
-- verified. The dispute module says as much in its own comment: "a card taken on a terminal the
-- platform does not talk to". This is the platform talking to it.
--
-- The shape is the e-invoicing transport seam's, because the problem is the same: one behaviour, many
-- vendors, and no vendor available in a test. A SIMULATED terminal is always deployed and says on the
-- record that nothing left the building; a real vendor appears only when its credentials are
-- configured.
--
-- THE CARD NUMBER IS NEVER HERE. The terminal performs the EMV transaction itself and returns an
-- outcome; the platform sends an amount and receives a verdict. That is what keeps this service out of
-- PCI-DSS scope, and it is why there is no column below that could hold a PAN — not an encrypted one,
-- not a truncated one beyond the last four digits a receipt is allowed to show. A schema with nowhere
-- to put it cannot grow a path that does.

-- The terminals a business has, and where each one stands.
CREATE TABLE card_terminals (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    -- A terminal belongs to a store: it is a physical object on a counter, and a payment taken on it
    -- is taken there. Reporting and settlement both ask which store took the money.
    store_id    UUID        NOT NULL,
    label       TEXT        NOT NULL,
    vendor      TEXT        NOT NULL,
    -- The vendor's own identifier for the device, as printed on it. Null until somebody pairs it.
    serial      TEXT,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    -- Why it was retired, so a device withdrawn after a fault is distinguishable from one replaced on
    -- an upgrade. A retired terminal is never deleted: payments point at it.
    retired_reason TEXT,

    CONSTRAINT ck_terminal_vendor CHECK (
        vendor IN ('SIMULATED', 'STRIPE_TERMINAL', 'ADYEN', 'VERIFONE')
    ),
    CONSTRAINT ck_terminal_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_terminal_retired_reason CHECK (
        (status = 'RETIRED') OR retired_reason IS NULL
    )
);

CREATE INDEX idx_card_terminals_tenant ON card_terminals (tenant_id, store_id, status);

-- One label per store, so a cashier choosing "Till 2" is choosing one device. Partial: a retired
-- terminal's label is free for its replacement, which is what happens when a device is swapped.
CREATE UNIQUE INDEX uq_card_terminals_label
    ON card_terminals (tenant_id, store_id, lower(label))
    WHERE status = 'ACTIVE';

-- A vendor's serial names one physical device and cannot be in two places.
CREATE UNIQUE INDEX uq_card_terminals_serial
    ON card_terminals (tenant_id, vendor, serial)
    WHERE serial IS NOT NULL AND status = 'ACTIVE';

-- Every attempt at a terminal, approved or not.
--
-- Declines are kept deliberately. A cashier needs to see that the card was declined rather than that
-- nothing happened, an acquirer's settlement file has to be reconciled against attempts and not only
-- against takings, and a run of declines on one device is how a broken pinpad announces itself.
CREATE TABLE terminal_payments (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    terminal_id     UUID        NOT NULL REFERENCES card_terminals (id),
    order_id        UUID        NOT NULL,
    -- The amount the device was asked for, in the currency's own units. Four places hold every
    -- ISO 4217 currency's minor units (the most is four, CLF and UYW), so nothing is rounded to two.
    amount          NUMERIC(18, 4) NOT NULL,
    currency        CHAR(3)     NOT NULL,
    -- SALE or REFUND. A refund at the terminal is put back on the card that paid, which is the only
    -- way a card refund is allowed to work.
    kind            TEXT        NOT NULL,
    -- The attempt this refund puts back, for a REFUND. Null for a SALE.
    refund_of       UUID        REFERENCES terminal_payments (id),
    state           TEXT        NOT NULL,
    -- What the terminal said, in its own words, when it declined or failed. Shown to the cashier,
    -- because "declined" without a reason sends them to ring the bank.
    outcome_detail  TEXT,

    -- What an EMV receipt must carry. All of it comes from the terminal; none of it identifies the
    -- cardholder. `pan_last4` is the four digits a receipt is permitted to print — there is no column
    -- for any other part of the number, by design.
    scheme          TEXT,
    pan_last4       CHAR(4),
    auth_code       TEXT,
    -- The EMV application the card and terminal agreed on: its identifier and the label printed on
    -- the receipt, e.g. A0000000031010 / "VISA DEBIT".
    aid             TEXT,
    application_label TEXT,
    entry_mode      TEXT,
    verification    TEXT,

    -- The vendor's own reference, so a settlement line can be matched back to this attempt.
    provider_ref    TEXT,
    -- The payment this became, once approved. Null for anything that took no money.
    payment_id      UUID,
    requested_at    TIMESTAMPTZ NOT NULL,
    -- A refund the platform owes (an order cancelled, voided or returned) is asked with no person
    -- behind it; everything else is somebody's.
    requested_by    UUID,
    settled_at      TIMESTAMPTZ,

    -- Why the money went back, and who asked. Written with the attempt and never changed after.
    --
    -- due_id names the money owed back a refund attempt is for. It is deliberately NOT a foreign key:
    -- card_refund_dues already refers to terminal_payments (the sale it reverses, the refund that put
    -- it back), and a reference back would be a cycle, which the tenant data catalog (21.14) cannot
    -- order — every export, import and erasure of every business's payment data would then be refused
    -- (500 TENANT_DATA_CATALOG_INCOMPLETE), and an erasure could not delete one table before the other.
    -- The column names a due only where TerminalRepository.claimRefund sets it (Terminals.forDue),
    -- with the id of a due read (or written) on that same transaction, so it always names a row
    -- that exists. Every other attempt, a sale's included, writes NULL.
    reason          TEXT,
    due_id          UUID,

    CONSTRAINT ck_terminal_payment_kind CHECK (kind IN ('SALE', 'REFUND')),
    CONSTRAINT ck_terminal_payment_state CHECK (
        state IN ('REQUESTED', 'APPROVED', 'DECLINED', 'CANCELLED', 'FAILED', 'TIMED_OUT')
    ),
    CONSTRAINT ck_terminal_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_terminal_entry_mode CHECK (
        entry_mode IS NULL OR entry_mode IN ('CHIP', 'CONTACTLESS', 'SWIPE', 'MANUAL')
    ),
    CONSTRAINT ck_terminal_verification CHECK (
        verification IS NULL OR verification IN ('PIN', 'SIGNATURE', 'NONE', 'DEVICE')
    ),
    CONSTRAINT ck_terminal_pan_last4 CHECK (pan_last4 IS NULL OR pan_last4 ~ '^[0-9]{4}$'),
    -- An approval has to say what was approved: a receipt with no scheme and no last four digits is
    -- not a valid card receipt in any market the platform trades in.
    CONSTRAINT ck_terminal_approved_has_receipt CHECK (
        state <> 'APPROVED'
        OR (scheme IS NOT NULL AND pan_last4 IS NOT NULL AND entry_mode IS NOT NULL)
    ),
    -- A refund names what it refunds; a sale does not.
    CONSTRAINT ck_terminal_refund_of CHECK (
        (kind = 'REFUND' AND refund_of IS NOT NULL) OR (kind = 'SALE' AND refund_of IS NULL)
    ),
    CONSTRAINT ck_terminal_settled_at CHECK (
        (state = 'REQUESTED') = (settled_at IS NULL)
    ),
    CONSTRAINT ck_terminal_reason CHECK (reason IS NULL OR char_length(reason) <= 500),
    -- Every refund says why. That holds for every row, which is what lets a business's data be
    -- imported again (21.14): an import inserts each row, and this check runs on each one.
    CONSTRAINT ck_terminal_refund_has_reason CHECK (kind <> 'REFUND' OR reason IS NOT NULL),
    CONSTRAINT ck_terminal_requested_by CHECK (
        requested_by IS NOT NULL OR (kind = 'REFUND' AND due_id IS NOT NULL)
    )
);

CREATE INDEX idx_terminal_payments_order ON terminal_payments (tenant_id, order_id);
CREATE INDEX idx_terminal_payments_device
    ON terminal_payments (tenant_id, terminal_id, requested_at DESC);
CREATE INDEX idx_terminal_payments_provider_ref
    ON terminal_payments (tenant_id, provider_ref)
    WHERE provider_ref IS NOT NULL;

-- One tender per approval and one approval per tender.
CREATE UNIQUE INDEX uq_terminal_payments_tender
    ON terminal_payments (tenant_id, payment_id) WHERE payment_id IS NOT NULL;
-- What the guard reads on every new sale: a terminal's sales that may still hold money unrecorded.
-- Declines, cancellations, failures and recorded sales are never in it.
CREATE INDEX idx_terminal_payments_open
    ON terminal_payments (tenant_id, terminal_id, requested_at)
    WHERE kind = 'SALE' AND payment_id IS NULL AND state IN ('REQUESTED', 'APPROVED', 'TIMED_OUT');
-- And a terminal's refunds that may still be moving money: at the machine, or timed out (whether a
-- person has said what it shows is read beside it). A refund nobody can account for holds its
-- machine too, and leaves the sale it would reverse holding it.
CREATE INDEX idx_terminal_payments_open_refunds
    ON terminal_payments (tenant_id, terminal_id, requested_at)
    WHERE kind = 'REFUND' AND state IN ('REQUESTED', 'TIMED_OUT');
-- The refunds of one sale, for what is still on the card.
CREATE INDEX idx_terminal_payments_refund_of
    ON terminal_payments (tenant_id, refund_of) WHERE refund_of IS NOT NULL;
CREATE INDEX idx_terminal_payments_due
    ON terminal_payments (tenant_id, due_id) WHERE due_id IS NOT NULL;

-- The replay guard, and the reason this table has its own rather than leaning on the payments table.
--
-- A terminal payment is THE canonical double-charge: the cashier presses "card" again because the
-- screen did not change, and the customer is charged twice on a real card. So the key is unique here,
-- at the point the request is made, before anything is asked of the terminal — a retry finds the first
-- attempt and returns it rather than starting a second EMV transaction.
CREATE TABLE terminal_payment_keys (
    tenant_id      UUID        NOT NULL,
    idempotency_key TEXT       NOT NULL,
    terminal_payment_id UUID   NOT NULL REFERENCES terminal_payments (id),
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_terminal_payment_keys PRIMARY KEY (tenant_id, idempotency_key)
);

COMMENT ON TABLE card_terminals IS
    'The EMV terminals a business has, per store. A retired terminal is kept: payments point at it.';
COMMENT ON TABLE terminal_payments IS
    'Every attempt at a terminal, approved or not, with what an EMV receipt must carry. Never a PAN.';
COMMENT ON COLUMN terminal_payments.pan_last4 IS
    'The four digits a receipt may print. There is deliberately no column for any other part.';
