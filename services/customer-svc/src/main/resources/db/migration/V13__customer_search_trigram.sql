-- The till's customer search matches %term% against the name, the email, the phone and the phone's
-- digits. Without a trigram index every keystroke scans the business's whole customer table; with
-- one, Postgres can answer the four ILIKE/LIKE conditions from bitmap index scans.
--
-- pg_trgm is a trusted extension (PostgreSQL 13+), but a deployment may not let this role create it.
-- Then the search still works, as before, and the indexes are simply not built: nothing here may
-- stop the service starting. The operator class is looked up in the schema the extension lives in
-- and written qualified, so the indexes survive a restore that runs with an empty search path.
DO $$
DECLARE
    ext_schema text;
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pg_trgm could not be created (%): customer search keeps its sequential scan', SQLERRM;
    END;

    SELECT n.nspname INTO ext_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'pg_trgm';
    IF ext_schema IS NULL THEN
        RETURN;
    END IF;

    -- The name as an IMMUTABLE expression (concat_ws is only STABLE): the search query uses the
    -- very same expression, which is what lets the planner use the index.
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_name_trgm ON customers USING gin'
        || ' ((COALESCE(first_name, '''') || '' '' || COALESCE(last_name, '''')) %I.gin_trgm_ops)',
        ext_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_email_trgm ON customers USING gin'
        || ' (email %I.gin_trgm_ops)',
        ext_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_phone_trgm ON customers USING gin'
        || ' (phone %I.gin_trgm_ops)',
        ext_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_customers_phone_digits_trgm ON customers USING gin'
        || ' ((regexp_replace(phone, ''[^0-9]'', '''', ''g'')) %I.gin_trgm_ops)',
        ext_schema);
END
$$;
