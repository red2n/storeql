-- A quote recorded after the request's due day is kept, and flagged: the due day is advisory.
ALTER TABLE rfq_suppliers ADD COLUMN received_late BOOLEAN NOT NULL DEFAULT FALSE;
