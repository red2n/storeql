-- An obligation can carry a number: a period, a minimum validity, a share, a count (stage 0.4).
--
-- limit_value and limit_unit come together or not at all: a number without its unit is unreadable.
-- qualifier narrows a row to one kind of case (a channel, a product class) and is free of any
-- vocabulary here; the service that reads the obligation code owns the meaning.
-- Existing rows keep all three null. No new obligation code is seeded here: each slice that needs
-- one adds its own rows, with a citation, in its own migration.

ALTER TABLE legal_obligations
    ADD COLUMN limit_value NUMERIC(18,4),
    ADD COLUMN limit_unit  TEXT,
    ADD COLUMN qualifier   TEXT;

ALTER TABLE legal_obligations
    ADD CONSTRAINT chk_obligation_limit_pair
        CHECK ((limit_value IS NULL) = (limit_unit IS NULL)),
    ADD CONSTRAINT chk_obligation_limit_value CHECK (limit_value IS NULL OR limit_value >= 0),
    ADD CONSTRAINT chk_obligation_limit_unit
        CHECK (limit_unit IS NULL OR limit_unit ~ '^[A-Z][A-Z0-9_]*$'),
    ADD CONSTRAINT chk_obligation_qualifier
        CHECK (qualifier IS NULL OR (qualifier = btrim(qualifier) AND qualifier <> ''));
