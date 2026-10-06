# Create a Flyway migration file

Usage: `/migration <service-name> <short description>`

Example: `/migration inventory-svc add recall_batch_notes table`

**The product is in DEV and nothing is deployed, so a migration only CREATES.** Never write `ALTER`,
`MODIFY`, `RENAME`, `DROP` or a backfill `UPDATE`/`DELETE`. See CLAUDE.md, "Migrations".

Steps:
1. Find the existing migrations for the service:
   ```bash
   ls services/$SERVICE/src/main/resources/db/migration/
   ```
2. Decide what the change is:
   - **A change to an existing table** (a column, a constraint, an index, a default, a comment, a seed
     row): edit the `CREATE TABLE` (and its `CREATE INDEX`, `COMMENT ON`, seed `INSERT`) in the
     migration file that creates that table. Do not add a new file. If it needs a table that a later
     file creates, create that table earlier instead of adding an `ALTER`.
   - **A new table**: add `V<N>__<slug>.sql` where N is the highest existing V number + 1, with the
     `CREATE TABLE`, its indexes and comments. Never reuse a deleted number and never renumber a file.
     Slug: lowercase, underscores, from the description.
3. Rules for any new table:
   - `tenant_id UUID NOT NULL` + a composite index starting with `tenant_id` (unless it is an
     infrastructure table like `outbox`, which the relay drains across tenants).
   - Money columns: `NUMERIC(18,4)` — never `FLOAT` or `DOUBLE`.
   - Timestamps: `TIMESTAMPTZ` — never `TIMESTAMP WITHOUT TIME ZONE`.
   - No `DEFAULT` that generates a uuid; every `INSERT` binds an id minted with `Ids.newId()`.
4. After editing a migration, a local database that ran the old copy must be reset
   (`docker compose down -v`) because Flyway checks file checksums.

Parse `$ARGUMENTS`:
- `$ARGUMENTS` format: `<service-name> <description words...>`
- First word = service name (strip `-svc` suffix if needed to match directory name)
- Remaining words = what the change is
