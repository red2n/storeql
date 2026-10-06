-- payment-svc acts on a void, and only on the voids announced from the moment this table exists.
--
-- payment-svc gives back what a voided till sale took: a card through the machine that took it, a cash
-- refund at the sale's store. A consumer group that starts reading storeql.order.order-voided begins at
-- the earliest void Kafka still holds, and each carries an eventId the dedupe has never seen, so the
-- dedupe does not stop it. Voids settled by hand before the consumer existed must not be refunded
-- again: a cash refund dated today would be subtracted from today's expected drawer cash although
-- nobody paid it out, and a card would be put back through the machine a second time.
--
-- So the moment this table is created is kept here, and a void announced before it is logged and not
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

    CONSTRAINT chk_events_handled_since_why CHECK (char_length(btrim(why)) BETWEEN 1 AND 500)
);

INSERT INTO events_handled_since (event_type, handled_since, why) VALUES (
    'OrderVoided',
    now(),
    'payment-svc gives back what a voided sale took from here on; voids before were settled by hand'
);

COMMENT ON TABLE events_handled_since IS
    'When payment-svc began acting on an event kind it once ignored: older events of it are history.';
