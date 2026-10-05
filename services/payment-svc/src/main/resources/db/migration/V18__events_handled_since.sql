-- payment-svc acts on a void from now on, and never on the voids before (2 Oct 2026, review of the
-- card-terminal settlement, V16).
--
-- payment-svc began consuming storeql.order.order-voided with V16's work: a voided till sale gives
-- back what it took, a card through the machine that took it. Its consumer group had never read
-- that topic, so its first deployment starts at the earliest void Kafka still holds — on a stack
-- with history, every void retained — and each carries an eventId new to processed_events, so the
-- dedupe does not stop it. Those voids were settled by hand when they happened (the till said money
-- was not put back). Acting on them now would refund them a second time, dated today: a cash refund
-- at the sale's store that today's expected drawer cash and Z-report subtract although nobody paid
-- it out, a PaymentRefunded the ledger and reporting post today, and a card put back through the
-- machine.
--
-- So the moment this migration runs is kept here, and a void announced before it is logged and not
-- acted on (its eventId is a UUIDv7, which says when it was made: EventCutoff.predates); every one
-- after is, wherever the consumer's offset starts. Not a consumer group of its own at the latest
-- offset: a new group commits no offset until it reads a record, so a restart before the first void
-- starts it at the latest offset again, and a void announced while it was down is never refunded.
--
-- Not a business's data (no tenant_id): left out of the tenant export, and never erased. Written by
-- migrations only; never updated or deleted.
CREATE TABLE events_handled_since (
    event_type    TEXT        PRIMARY KEY,
    handled_since TIMESTAMPTZ NOT NULL,
    why           TEXT        NOT NULL,

    CONSTRAINT ck_events_handled_since_why CHECK (char_length(btrim(why)) BETWEEN 1 AND 500)
);

INSERT INTO events_handled_since (event_type, handled_since, why) VALUES (
    'OrderVoided',
    now(),
    'payment-svc gives back what a voided sale took from here on; voids before were settled by hand'
);

COMMENT ON TABLE events_handled_since IS
    'When payment-svc began acting on an event kind it once ignored: older events of it are history.';
