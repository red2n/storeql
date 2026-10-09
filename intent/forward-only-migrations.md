# Forward-only migrations: from the DEV create-only rule to releases a customer's data survives

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the UK pilot assessment (pre-mortem and hosting review) · 2026-10-09 |
| **Roadmap** | new: pilot gate, migrations slice (M1 to M4 in the merged plan) |
| **Services** | none owns data; every service's `db/migration` folder, `FlywayRunner` in common-service, the audits in common-test, CI and the release path change |
| **Builds on** | [no-alter-migrations memory and CLAUDE.md "Migrations"](../CLAUDE.md), `FlywayRunner` (narrow refusal), `PostgresSupport.stop()` audits (names, UUIDv7), `scripts/backup-drill.sh`, [docs/RELEASE-PROCESS.md](../docs/RELEASE-PROCESS.md), `scripts/verify-release.sh` |
| **Built in** | (not yet built) |

## Problem

Until now nothing was deployed, so a migration only ever CREATEd and a change to a table was edited into the `CREATE TABLE` that made it; a database that had run the older copy was reset. That rule cannot survive the first customer: once a business's catalogue, stock and sales are in the database, an edited applied migration fails Flyway's checksum and the only repair is a wipe. There is also no release tag to freeze against, no CI check that a published migration was left alone, no proof that a new release can start on the last release's data, and nothing that stops a pod serving with a half-migrated schema.

## Outcome

From the first release tag, a published migration file is immutable and every schema change is a new forward migration that is additive by default, so a deploy can be rolled back by running the previous image. CI fails a change that touches a frozen file; a service whose migration fails does not serve; an upgrade drill proves release N+1 migrates a real-shaped database and release N still runs on it. Before the tag nothing changes for developers: the fold-into-`CREATE` rule still holds, and the tag is the flip.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the developer and the person who deploys.
- **Channels:** repository, CI, the deploy script. **Scope:** all twelve business services, the gateway and config service where they migrate.
- **Roles that can write:** none (no API). **Sandbox tenant:** not applicable.

## Scope

- **In:** `scripts/migration-freeze-check.py` and its self-test, run in CI; a strict `FlywayRunner` (a failed migration stops the pod; readiness is DOWN until the schema is at the build's version) with pinned settings; the policy text rewritten in every place that states it; `scripts/deploy-release.sh` (verified backup, per-service migrate-only step, rollback line); `scripts/upgrade-selftest.sh` and `scripts/upgrade-drill.sh`; release-path changes the first tag needs (GHCR cleanup keeps semver tags, production compose pins a tag and declares its volumes external, `redeploy.sh --wipe-data` refuses outside dev and CI); the first tag `v0.1.0`.
- **Out, on purpose:**
  - **A separate migrator database role** (DDL) beside a runtime role (DML only): a post-pilot hardening item. The runtime role keeps owning its schema.
  - **Kubernetes migrate Jobs** and the nightly upgrade-drill workflow: the pilot runs on one VM with compose; they come with the first cluster.
  - **Dotted hotfix migrations, `CONCURRENTLY` sidecar files, and migrate modes beyond `strict`, `lenient`, `only`** beyond what the deploy script uses: written down, built when a release needs them.
  - **Rewriting history:** migrations already published stay as they are; nothing is renumbered after the tag.

## Data and flow

- **Owned by** none: no table, event or endpoint. Flyway's own history table stays per service schema.
- **Events published, Retryable writes, Error codes:** none. One new exception, `MigrationFailedException`, ends the pod's start.

## Money, time and limits

None. Backfills in a migration are one bounded set-based statement; anything larger is an idempotent, resumable application job run after the expand release.

## Constraints

- A migration that adds a constraint to a big table adds it `NOT VALID` and validates it in a later file (the `afterMigrate` UUIDv7 check scans a table under an exclusive lock today and changes to this pattern).
- The migration connection goes to Postgres directly, never through PgBouncer. Flyway's Postgres advisory lock serialises two runners.
- `k6/` and CI keep resetting databases with `down -v`: still right for dev and CI, never for the pilot.
- Backups: `scripts/backup-drill.sh` runs after the last fold and before the tag (CLAUDE.md requires it for schema changes).

## Open questions

1. **When does the freeze start?** Recommended: at `v0.1.0`, cut once the pricing and importer schema have been folded and before the pilot's first real data loads; if the dry-run database were wiped before go-live the tag could move to after the dry run. → **Tag first, before the dry run loads anything the customer expects to keep** (by industry standard: the customer's data is never wiped, so the tag date is the data-load date. Claude, 2026-10-09; the owner may move it)
2. **What replaces "numbers run 1..n with no gaps, never renumber"?** → **Unique integer versions per service, strictly greater than the highest in the latest tag, gaps allowed**; a pull request that loses a numbering race renumbers before it merges, never after. It also settles the contradiction between CLAUDE.md ("no gaps") and ARCHITECTURE ("has gaps"). (Claude, 2026-10-09)
3. **Destructive DDL after the tag?** → **Only with a first line `-- storeql:contract after=vX.Y.Z reason=...`**; the guard checks the tag exists, is an ancestor and is at least one release older. `DROP`, `RENAME`, `ALTER COLUMN TYPE`, `SET NOT NULL`, `DELETE`, `TRUNCATE` are contract moves; rolling back past one means restoring the pre-upgrade backup. (by industry standard: expand, then switch, then contract. Claude, 2026-10-09)
4. **How long a maintenance window may an upgrade take at the two stores, and outside trading hours?** For the customer; assumed stop-migrate-start in a closed window until they say otherwise.

## Acceptance

- [ ] A change to, a rename of or a deletion of a versioned migration present in the latest tag fails the freeze check naming the file; a new higher-numbered file passes; before any tag the check passes and is a no-op — `scripts/migration-freeze-check.py --self-test` (breaks each promise in memory, and runs against this repository's real history with a throwaway tag).
- [ ] A `contract` file without the marker, with a tag that does not exist, or with one that is not older than the latest release fails; with a valid marker it passes — the same self-test.
- [x] A migration that fails (bad SQL, a missing privilege, a schema with tables and no history, a checksum mismatch) stops the service from serving, in `strict` mode; an unreachable database is retried with a growing wait while `/health/ready` is DOWN, and a late database recovers; a failure found on a retry keeps readiness DOWN and ends the retry; shutdown ends the retry; an unknown mode stops startup; `lenient` (the default) and `off` hold the service back never — `FlywayRunnerTest` (real Postgres, 11 new cases beside the 18 that pin lenient).
- [x] Two runners starting at once migrate once (each migration applied once, both end current) — `FlywayRunnerTest.strictTwoRunnersMigrateOnce` (Flyway's advisory lock).
- [ ] Release N+1 migrates a database made by release N, and release N still runs on the N+1 schema; a `contract` migration is flagged as not rollback-safe — `scripts/upgrade-selftest.sh` (throwaway Postgres, fixture folders, negative controls).
- [ ] On the stack: release N images with a k6-seeded database, `deploy-release.sh` to N+1, health and history and row counts checked, k6 smoke green, rollback to N and smoke green, and the pre-upgrade backup restores — `scripts/upgrade-drill.sh`.
- [ ] `deploy-release.sh` refuses to run without a verified backup taken just before; `redeploy.sh --wipe-data` refuses unless `STOREQL_ENV` is `dev` or `ci`.
- [ ] GHCR cleanup keeps semver-tagged images; `scripts/supply-chain-check.py` fails a workflow edit that drops the exclusion; `scripts/verify-release.sh` passes on `v0.1.0`.
- [ ] The policy reads the same in CLAUDE.md, `docs/coding-standards.md`, `docs/ARCHITECTURE.md`, `.claude/commands/migration.md` and `FlywayRunner`'s message, and says "until the first tag fold, from the tag forward-only" — checked by `scripts/migration-freeze-check.py` reading the docs for the stale wording.
- [ ] `scripts/backup-drill.sh` passes after the last fold and before the tag.

## Screens

None.

## Decisions

- **The guard reads tags, so there is no flag-day commit.** Behaviour flips when `v0.1.0` exists, not when someone edits a sentence. Before it, the freeze check is a tested no-op.
- **What is frozen:** every versioned `V*.sql` under a service's `db/migration` (and any versioned file in `shared/`) that exists in the latest reachable tag, byte for byte. Repeatable and `afterMigrate` files are exempt but stay idempotent.
- **Additive by default, expand then contract.** A new table, a nullable column or a constant default, a new index, a constraint added `NOT VALID` then validated later. Rollback is the previous image; a release carrying a contract file is rolled back by restoring the verified backup the deploy script took.
- **Flyway settings are pinned in `FlywayRunner`:** `validateOnMigrate` true, `outOfOrder` false, `cleanDisabled` true, Flyway's default `*:future` kept (so image N starts on schema N+1), `validateMigrationNaming` true, `baselineOnMigrate` false in `strict` (a schema with tables and no history is refused, not baselined over).
- **The mode is set per deployment; the code default stays `lenient`.** `FlywayRunner` was kept narrow on purpose because Kubernetes pods cannot reach Postgres directly (a strict default would hold every such pod's readiness DOWN forever). `docker-compose.prod.yml` sets `storeql.db.migrate.mode: strict` for the twelve services that own a schema, the pilot's single VM migrates itself, and a cluster will use `off` plus a run-once Job. `HealthChecks.SchemaReadiness` is the readiness gate. An unrecognised mode stops startup.
- **Pilot topology:** one VM with compose, one replica, migrations inline in `strict`, plus a migrate-only step per service in the deploy script so a failure is seen before traffic moves. The VPS overlay declares its Postgres and backup volumes `external: true`, so `down -v` cannot remove them.
- **Hotfixes:** branch `release/0.1.x` from the tag, cherry-pick, no migration unless a dotted expand-only one, tag `v0.1.1`, forward-merge to main.
- **Schema-free pieces are built first, dormant.** The freeze check, CI job, strict runner, audits and the deploy script touch no table, so they land before the pricing and importer folds; those folds are the last use of the fold rule, then the tag.

## Flow Tests entry

None: no business flow changes.
