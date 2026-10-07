-- Card terminals, settled on the server: a till cannot charge a card twice.
--
-- A till that held a sale on its own device while a card was at, or already taken by, the card machine
-- would lose that hold whenever the device lost it (storage that fails, a sign-out, another device, a
-- copy that is corrupt), and a real customer would then be charged twice. The rule is the server's:
-- a card-present transaction left unsettled on a terminal is reconciled before that terminal takes the
-- next one.
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

    CONSTRAINT chk_terminal_decision_outcome CHECK (outcome IN ('APPROVED', 'NOT_TAKEN')),
    CONSTRAINT chk_terminal_decision_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    -- Decided once: a second word on the same attempt is a different story, not a correction.
    CONSTRAINT uq_terminal_decision_attempt UNIQUE (tenant_id, attempt_id),
    CONSTRAINT uq_terminal_decision_key UNIQUE (tenant_id, idempotency_key)
);

-- 2. Money owed back to a card.
--
-- An order cancelled or voided, or a return refunded to the original tender, writes its refund in the
-- books, and the card a terminal took is put back through that terminal, linked to the sale it
-- reverses, the way a provider refund is. Until the machine says it is done, what is owed is a row
-- here. When the machine cannot be reached, or refuses, the row says so and waits for a person
-- (NEEDS_ATTENTION); it is never retried behind anybody's back, because a refund that timed out may
-- have gone through.
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

    CONSTRAINT chk_card_refund_due_amount CHECK (amount > 0),
    CONSTRAINT chk_card_refund_due_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT chk_card_refund_due_source CHECK (source IN ('ORDER_EVENT', 'PERSON')),
    -- REFUNDED means a machine of ours put it back on the card, and names the refund attempt that did.
    -- REFUNDED_ANOTHER_WAY is a person's close (card_refund_due_closures): cash, the acquirer's own
    -- refund of the card, or a transfer.
    CONSTRAINT chk_card_refund_due_state CHECK (
        state IN ('OWED', 'REFUNDED', 'NEEDS_ATTENTION', 'NOT_REFUNDED', 'REFUNDED_ANOTHER_WAY')
    ),
    -- Only a person's own refund that the machine refused stops being owed; what the platform owes
    -- stays owed until it is put back.
    CONSTRAINT chk_card_refund_due_not_refunded CHECK (state <> 'NOT_REFUNDED' OR source = 'PERSON'),
    CONSTRAINT chk_card_refund_due_refunded CHECK (
        state <> 'REFUNDED' OR refund_attempt_id IS NOT NULL
    ),
    CONSTRAINT chk_card_refund_due_person CHECK (source <> 'PERSON' OR requested_by IS NOT NULL),
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
-- One due for every card refund the business ever makes, and the system-health screen counts the ones
-- waiting for a person every few seconds, across all its stores: the partial index holds only those few
-- rows, where the store-led index above would be walked end to end for each store.
CREATE INDEX idx_card_refund_dues_tenant_state
    ON card_refund_dues (tenant_id, state) WHERE state = 'NEEDS_ATTENTION';

COMMENT ON TABLE terminal_attempt_decisions IS
    'What a person saw on a card machine that did not answer. Append-only, decided once.';
COMMENT ON TABLE card_refund_dues IS
    'Money owed back to a card a terminal took, until the machine has put it back.';
