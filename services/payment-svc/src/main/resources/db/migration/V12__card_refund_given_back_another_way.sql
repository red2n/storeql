-- Money owed back to a card that no card machine can put back.
--
-- A card a terminal took goes back through a terminal: the one that took it or, once that is
-- retired, another of its vendor in service at its store (a refund is linked to the sale by the
-- vendor's own reference, so it needs the vendor, not the device). When there is none — the store
-- no longer has a machine of that vendor, or the card itself can no longer take a refund — the due
-- stays NEEDS_ATTENTION, and a manager says how it was given back instead: cash, the acquirer's own
-- refund of the card (outside any machine of ours, with its reference), or a transfer. That closes
-- the due, once. The due's state for it is REFUNDED_ANOTHER_WAY (card_refund_dues, V9).

-- What a person did instead. Append-only: once per due, with who, when and why, and never
-- updated or deleted. The books' refund written with it (when the money was ever in the books) is
-- the due's own refund_id; an approval never recorded on a sale has nothing in the books to
-- reverse, so it is closed by the acquirer's refund alone (method CARD).
CREATE TABLE card_refund_due_closures (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    due_id          UUID        NOT NULL REFERENCES card_refund_dues (id),
    -- How the money left: cash from the drawer, the card through the acquirer, or a transfer. Never
    -- value issued (a gift card, store credit, a voucher): those have their own routes and records.
    method          TEXT        NOT NULL,
    -- What finds it again: the acquirer's refund reference (required for CARD), or a transfer's.
    reference       TEXT,
    reason          TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    closed_by       UUID        NOT NULL,
    closed_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_card_refund_closure_method CHECK (method IN ('CASH', 'CARD', 'UPI', 'WALLET')),
    CONSTRAINT ck_card_refund_closure_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_card_refund_closure_reference CHECK (
        reference IS NULL OR char_length(btrim(reference)) BETWEEN 1 AND 255
    ),
    CONSTRAINT ck_card_refund_closure_card_reference CHECK (
        method <> 'CARD' OR reference IS NOT NULL
    ),
    -- Closed once: money given back by hand is not given back by hand again.
    CONSTRAINT uq_card_refund_closure_due UNIQUE (tenant_id, due_id),
    CONSTRAINT uq_card_refund_closure_key UNIQUE (tenant_id, idempotency_key)
);

-- What a store's managers closed by hand, newest last (ids are time-ordered).
CREATE INDEX idx_card_refund_due_closures_store
    ON card_refund_due_closures (tenant_id, store_id, id);

COMMENT ON TABLE card_refund_due_closures IS
    'How money owed back to a card was given back when no machine could put it back. Append-only, once per due.';
