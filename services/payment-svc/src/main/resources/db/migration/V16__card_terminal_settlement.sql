-- Card terminals, settled on the server (07.16 follow-up, 2 Oct 2026): a till cannot charge a card
-- twice.
--
-- The till kept a "hold" on its own device while a card was at, or already taken by, the card
-- machine, so a sale changed mid-payment could not be paid a second time. A hold on a device can be
-- lost — storage that fails, a sign-out, another device, a copy that is corrupt — and then a real
-- customer is charged twice. The industry's rule is the server's: a card-present transaction left
-- unsettled on a terminal is reconciled before that terminal takes the next one.
--
-- A sale on a terminal is unsettled while it is still at the machine (REQUESTED); while it took money
-- (APPROVED, or TIMED_OUT and a person saw an approval on the machine) and is neither recorded as a
-- tender on its order nor owed or put back on the card in full; and while it TIMED_OUT and nobody has
-- said what the machine shows. Only what a refund has put back (approved, or seen approved) counts as
-- back on the card: one still at the machine, or timed out with nobody's word on it, leaves the sale
-- unsettled, and holds the terminal itself. Until all of it is settled, that terminal starts no new
-- sale.

-- 1. A person's word on an attempt the machine did not answer. Append-only: decided once, with who,
--    when and why, and never updated or deleted. The attempt row itself keeps TIMED_OUT, because that
--    is what the machine said; this is what a person saw, and both are kept.
CREATE TABLE terminal_attempt_decisions (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    attempt_id      UUID        NOT NULL REFERENCES terminal_payments (id),
    outcome         TEXT        NOT NULL,
    reason          TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    decided_by      UUID        NOT NULL,
    decided_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_terminal_decision_outcome CHECK (outcome IN ('APPROVED', 'NOT_TAKEN')),
    CONSTRAINT ck_terminal_decision_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    -- Decided once: a second word on the same attempt is a different story, not a correction.
    CONSTRAINT uq_terminal_decision_attempt UNIQUE (tenant_id, attempt_id),
    CONSTRAINT uq_terminal_decision_key UNIQUE (tenant_id, idempotency_key)
);

-- 2. Money owed back to a card.
--
-- An order cancelled or voided, or a return refunded to the original tender, used to write a
-- refund in the books only — a PaymentRefunded with nothing back on the card a terminal took. A card
-- taken on a terminal is put back through that terminal, linked to the sale it reverses, the way a
-- provider refund is; until the machine says it is done, what is owed is a row here. When the
-- machine cannot be reached, or refuses, the row says so and waits for a person (NEEDS_ATTENTION);
-- it is never retried behind anybody's back, because a refund that timed out may have gone through.
--
-- payment_id is the recorded tender the money is drawn against (null for an approval that was never
-- recorded on its order: then there is nothing in the books to reverse, only money on a card).
CREATE TABLE card_refund_dues (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    store_id          UUID          NOT NULL,
    order_id          UUID          NOT NULL,
    sale_attempt_id   UUID          NOT NULL REFERENCES terminal_payments (id),
    payment_id        UUID,
    amount            NUMERIC(18,4) NOT NULL,
    currency          CHAR(3)       NOT NULL,
    reason            TEXT          NOT NULL,
    -- ORDER_EVENT: the platform owes it (an order cancelled, voided or returned to the card).
    -- PERSON: a manager asked for it on a recorded sale; one the machine refused is not owed after.
    source            TEXT          NOT NULL,
    idempotency_key   TEXT          NOT NULL,
    -- What the PaymentRefunded announced when the machine has put it back will say, so the ledger,
    -- the customer's store credit and the order read it as they read every other refund.
    refund_kind       TEXT,
    refund_method     TEXT,
    return_id         UUID,
    customer_id       UUID,
    state             TEXT          NOT NULL,
    attention         TEXT,
    refund_attempt_id UUID          REFERENCES terminal_payments (id),
    refund_id         UUID,
    requested_by      UUID,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_card_refund_due_amount CHECK (amount > 0),
    CONSTRAINT ck_card_refund_due_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_card_refund_due_source CHECK (source IN ('ORDER_EVENT', 'PERSON')),
    CONSTRAINT ck_card_refund_due_state CHECK (
        state IN ('OWED', 'REFUNDED', 'NEEDS_ATTENTION', 'NOT_REFUNDED')
    ),
    -- Only a person's own refund that the machine refused stops being owed; what the platform owes
    -- stays owed until it is put back.
    CONSTRAINT ck_card_refund_due_not_refunded CHECK (state <> 'NOT_REFUNDED' OR source = 'PERSON'),
    CONSTRAINT ck_card_refund_due_refunded CHECK (
        state <> 'REFUNDED' OR refund_attempt_id IS NOT NULL
    ),
    CONSTRAINT ck_card_refund_due_person CHECK (source <> 'PERSON' OR requested_by IS NOT NULL),
    CONSTRAINT fk_card_refund_due_payment
        FOREIGN KEY (tenant_id, payment_id) REFERENCES payment_tenders (tenant_id, id),
    CONSTRAINT fk_card_refund_due_refund
        FOREIGN KEY (tenant_id, refund_id) REFERENCES refund_tenders (tenant_id, id),
    CONSTRAINT uq_card_refund_due_key UNIQUE (tenant_id, idempotency_key)
);

-- The list a manager works through: what is owed at a store, oldest first (ids are time-ordered).
CREATE INDEX idx_card_refund_dues_store
    ON card_refund_dues (tenant_id, store_id, state, id);
CREATE INDEX idx_card_refund_dues_order ON card_refund_dues (tenant_id, order_id);
CREATE INDEX idx_card_refund_dues_payment
    ON card_refund_dues (tenant_id, payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX idx_card_refund_dues_sale ON card_refund_dues (tenant_id, sale_attempt_id);

-- 3. Why money went back, and who asked. Written with the attempt and never changed after.
--
-- due_id names the money owed back a refund attempt is for. It is deliberately NOT a foreign key:
-- card_refund_dues already refers to terminal_payments (the sale it reverses, the refund that put it
-- back), and a reference back would be a cycle, which the tenant data catalog (21.14) cannot order —
-- every export, import and erasure of every business's payment data would then be refused
-- (500 TENANT_DATA_CATALOG_INCOMPLETE), and an erasure could not delete one table before the other.
-- The column is written only by TerminalRepository.claimRefund, with the id of a due read (or
-- written) on that same transaction, so it always names a row that exists.
ALTER TABLE terminal_payments ADD COLUMN reason TEXT;
ALTER TABLE terminal_payments ADD COLUMN due_id UUID;
ALTER TABLE terminal_payments
    ADD CONSTRAINT ck_terminal_reason CHECK (reason IS NULL OR char_length(reason) <= 500);
-- Every refund says why. The ones asked before this had nowhere to put a reason, so they say that,
-- in words nobody could take for a person's: the rule then holds for every row, which is what lets a
-- business's data be imported again (21.14) — an import inserts each row, and a check left NOT VALID
-- for old rows still refuses them on the way back in.
UPDATE terminal_payments
    SET reason = 'Not recorded: asked before a refund on a card machine kept its reason'
    WHERE kind = 'REFUND' AND reason IS NULL;
ALTER TABLE terminal_payments
    ADD CONSTRAINT ck_terminal_refund_has_reason CHECK (kind <> 'REFUND' OR reason IS NOT NULL);
-- A refund the platform owes (an order cancelled, voided or returned) is asked with no person
-- behind it; everything else is somebody's.
ALTER TABLE terminal_payments ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE terminal_payments
    ADD CONSTRAINT ck_terminal_requested_by CHECK (
        requested_by IS NOT NULL OR (kind = 'REFUND' AND due_id IS NOT NULL)
    );

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

COMMENT ON TABLE terminal_attempt_decisions IS
    'What a person saw on a card machine that did not answer. Append-only, decided once.';
COMMENT ON TABLE card_refund_dues IS
    'Money owed back to a card a terminal took, until the machine has put it back.';
