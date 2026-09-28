-- Every uuid in every StoreQL schema is an RFC 9562 version-7 UUID (version nibble 7, variant 10),
-- and the database says so itself: after every migrate, in every service, each uuid and uuid[]
-- column that lacks one gets a CHECK that refuses anything else. So does every idempotency_key
-- column: a key is text, because it is the caller's, but it is a UUIDv7 in its canonical lowercase
-- form — the one the HTTP boundary (IdempotencyKeyFilter, IdempotencyKeys) lets through and
-- normalises to, and the one Ids.derived gives the keys the services make for themselves.
--
-- The code already mints only v7 (Ids.newId) and reads only v7 (Ids.parse, and the HTTP boundary in
-- common-web). This is the layer that holds whatever path a write takes — a repository, a set-based
-- insert, a fixture, a hand-typed INSERT in psql. A column added by any later migration is covered
-- the moment it exists, with nobody having to remember it.
--
-- Postgres 16 has no uuid_extract_version (17 does), so the version and variant are read from the
-- bytes: byte 6's high nibble is the version, byte 8's top two bits the variant.

-- The guards are SQL-standard function bodies (BEGIN ATOMIC), parsed once at creation: what they
-- name is bound then, and recorded as a dependency. A body kept as a string ($$ ... $$) is parsed at
-- every call under the caller's search_path — and pg_restore runs with an empty one, so a guard that
-- named its sibling unqualified failed every COPY into a table it checks, and a backup could not be
-- restored. The backup drill found it; PostgresSupport's audit now refuses a guard defined that way.
CREATE OR REPLACE FUNCTION uuid_is_v7(u uuid) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
BEGIN ATOMIC
    SELECT (get_byte(uuid_send(u), 6) >> 4) = 7 AND (get_byte(uuid_send(u), 8) >> 6) = 2;
END;

CREATE OR REPLACE FUNCTION uuid_all_v7(us uuid[]) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
BEGIN ATOMIC
    SELECT coalesce(bool_and(uuid_is_v7(u)), true) FROM unnest(us) AS u;
END;

-- Canonical text only: lowercase, hyphenated, 36 characters — so one key is never two rows.
CREATE OR REPLACE FUNCTION uuid_text_is_v7(t text) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
BEGIN ATOMIC
    SELECT t ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$';
END;

DO
$$
DECLARE
    col  RECORD;
    name TEXT;
BEGIN
    FOR col IN
        SELECT c.table_name, c.column_name, c.udt_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
        WHERE c.table_schema = current_schema()
          AND t.table_type = 'BASE TABLE'
          AND (c.udt_name IN ('uuid', '_uuid')
               OR (c.column_name = 'idempotency_key' AND c.udt_name IN ('text', 'varchar')))
    LOOP
        -- Readable, and unique per column however long the names: under Postgres's 63-byte limit.
        name := 'v7_' || left(col.column_name, 40) || '_'
                || substr(md5(col.table_name || '.' || col.column_name), 1, 8);
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint k
            JOIN pg_class r ON r.oid = k.conrelid
            JOIN pg_namespace n ON n.oid = r.relnamespace
            WHERE n.nspname = current_schema() AND r.relname = col.table_name AND k.conname = name
        ) THEN
            BEGIN
                EXECUTE format(
                    'ALTER TABLE %I.%I ADD CONSTRAINT %I CHECK (%I IS NULL OR %I.%I(%I))',
                    current_schema(), col.table_name, name, col.column_name, current_schema(),
                    CASE col.udt_name
                        WHEN '_uuid' THEN 'uuid_all_v7'
                        WHEN 'uuid' THEN 'uuid_is_v7'
                        ELSE 'uuid_text_is_v7'
                    END,
                    col.column_name);
            EXCEPTION WHEN duplicate_object THEN
                NULL; -- another replica migrating at the same moment added it first
            END;
        END IF;
    END LOOP;
END
$$;
