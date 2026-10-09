# Create a Flyway migration file

Usage: `/migration <service-name> <short description>`

Example: `/migration inventory-svc add recall_batch_notes table`

<!-- migration-policy:v1 -->
**Until the first release tag (`v0.1.0`) a migration only CREATES** (never `ALTER`, `MODIFY`, `RENAME`, `DROP` or a backfill
`UPDATE`/`DELETE`; fold the change into the `CREATE TABLE`). **From the tag on, every published file is frozen** and a change is a
*new* file: a version above the highest in the latest tag, additive (new table, nullable column or constant default, index,
constraint `NOT VALID` then validated later); a destructive move needs `-- storeql:contract after=vX.Y.Z reason=...` as its first line.
`git tag --list 'v*'` says which side you are on. See CLAUDE.md, "Migrations".

Steps:
1. Find the existing migrations for the service:
   ```bash
   ls services/$SERVICE/src/main/resources/db/migration/
   ```
2. Decide what the change is:
   - **Before the first tag, a change to an existing table** (a column, a constraint, an index, a default, a comment, a seed
     row): edit the `CREATE TABLE` (and its `CREATE INDEX`, `COMMENT ON`, seed `INSERT`) in the
     migration file that creates that table. Do not add a new file. If it needs a table that a later
     file creates, create that table earlier instead of adding an `ALTER`.
   - **After the first tag, a change to an existing table**: a new `V<N>` file above the latest tag's highest, additive; never edit,
     rename or delete a file the tag contains (`scripts/migration-freeze-check.py` fails the build).
   - **A new table**: add `V<N>__<slug>.sql` where N is the highest existing V number + 1, with the
     `CREATE TABLE`, its indexes and comments. Never reuse a deleted number and never renumber a file.
     Slug: lowercase, underscores, from the description.
3. Rules for any new table:
   - `tenant_id UUID NOT NULL` + a composite index starting with `tenant_id` (unless it is an
     infrastructure table like `outbox`, which the relay drains across tenants).
   - Money columns: `NUMERIC(18,4)` — never `FLOAT` or `DOUBLE`.
   - Timestamps: `TIMESTAMPTZ` — never `TIMESTAMP WITHOUT TIME ZONE`.
   - No `DEFAULT` that generates a uuid; every `INSERT` binds an id minted with `Ids.newId()`.
4. Before the tag, after editing a migration a local database that ran the old copy must be reset
   (`docker compose down -v`) because Flyway checks file checksums. After the tag nothing is edited, so nothing is reset.

Parse `$ARGUMENTS`:
- `$ARGUMENTS` format: `<service-name> <description words...>`
- First word = service name (strip `-svc` suffix if needed to match directory name)
- Remaining words = what the change is
