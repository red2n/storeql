-- The dedupe key is (event_id, consumer), not event_id alone: one consumer's mark must not stop
-- another consumer from applying the same event (each consumer name is one purpose). The existing
-- rows already have unique event ids, so the new key is satisfied by every row that exists.
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, consumer);
