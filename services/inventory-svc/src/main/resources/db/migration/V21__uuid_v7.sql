-- UUIDv7 inside the database, for the one set-based insert that cannot take its ids from Java:
-- DemandHistoryRepository rebuilds demand_history with INSERT ... SELECT ... GROUP BY, one row per
-- group. Every other insert binds Ids.newId(). Replace with Postgres 18's built-in uuidv7().
--
-- Layout (RFC 9562): the first 6 bytes become Unix-epoch milliseconds, and setting bits 52 and 53
-- turns gen_random_uuid()'s version nibble from 4 into 7. gen_random_uuid() is only the source of
-- the 74 random bits and the RFC variant; no version-4 id is ever stored.
CREATE FUNCTION uuid_v7() RETURNS uuid
    LANGUAGE sql
    VOLATILE
    PARALLEL SAFE
AS
$$
SELECT encode(
           set_bit(
               set_bit(
                   overlay(uuid_send(gen_random_uuid())
                           PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                           FROM 1 FOR 6),
                   52, 1),
               53, 1),
           'hex')::uuid
$$;
