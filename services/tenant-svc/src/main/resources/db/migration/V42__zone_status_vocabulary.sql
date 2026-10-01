-- A zone's status is a checked vocabulary (workforce-rules slice 9): ACTIVE, OUT_OF_SERVICE, RETIRED.
-- It was free text nothing listened to. Values that are none of the three become ACTIVE, the value
-- every zone starts with, so nothing that trades is switched off by this migration.

UPDATE zones SET status = 'ACTIVE' WHERE status NOT IN ('ACTIVE', 'OUT_OF_SERVICE', 'RETIRED');

ALTER TABLE zones
    ADD CONSTRAINT ck_zone_status CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE', 'RETIRED'));
