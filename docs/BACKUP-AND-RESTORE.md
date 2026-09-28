# Backup and restore

How the platform's data is backed up, how it comes back, and how that is proved (the rehearsed
backup-restore drill; PRD §9 "backups per DB; PITR").

## What is backed up, and how

One Postgres holds every service's schema. The `backup` service (`infra/backup`, the Postgres
image's own tools plus `age` and `jq`) does three things on a schedule:

- **A dump every night** at `STOREQL_BACKUP_AT` (UTC, 02:30 by default): `pg_dump` in custom format,
  taken from one exported snapshot, beside a **manifest** written in the same snapshot — every
  table's row count, the artefact's SHA-256, the WAL position, the server version. The counts are
  what a restore is compared against, and the checksum is what a restore is refused on.
- **Encrypted to a recipient.** `STOREQL_BACKUP_RECIPIENT` is an age public key; the dump is
  encrypted to it and only the matching identity reads it back. The backup host holds the public
  key alone: a stolen backups disk is noise. `docker compose run --rm backup age-keygen` prints a
  pair; the identity line goes off the machine, into the place a restore will be run from. With no
  recipient the backup is plain and every log line and the `storeql_backup_encrypted` metric say so.
- **A base backup once a week** (`pg_basebackup`, `STOREQL_BACKUP_BASE_EVERY_DAYS`) for
  **point-in-time recovery**: Postgres archives every closed WAL segment into the backups volume
  (`archive_mode=on`, `archive_command` in `docker-compose.yml` and `k8s/10-postgres.yaml`), and
  closes a segment at least every `PG_ARCHIVE_TIMEOUT` seconds (300) — which bounds the **data at
  risk (RPO): five minutes** of writes at most, on a disk that survives the database's.

Retention keeps the newest `STOREQL_BACKUP_KEEP` dumps (14) and `STOREQL_BACKUP_KEEP_BASE` base
backups (4); archived WAL older than the oldest kept base backup is removed (`pg_archivecleanup`).
On docker-compose the container runs `schedule` and keeps its own clock; on Kubernetes the CronJob
runs `nightly` once a day — a dump, and a base backup when one is due — and the cluster keeps the clock.
The job writes its metrics for Prometheus through the node exporter's textfile collector
(`storeql_backup_last_success_timestamp_seconds`, `_bytes`, `_tables`, `_rows`, `_encrypted`,
`_base_last_timestamp_seconds`); the `backups` alert group says when a backup is missing or a day
late, when backups are unencrypted, when no base backup has been taken in nine days, and when
Postgres cannot archive its WAL.

Where it lives: the `backups` volume on docker-compose, the `storeql-backups` claim on Kubernetes. **A
backup on the database's own disk is not a backup**: bind the volume to another disk, or add a copy to
object storage after each run (`rclone`/`aws s3 cp` of `/backups`); nothing else changes.

## Restoring

The same image restores. Every command below reads the artefact from the backups volume and, for an
encrypted one, needs `STOREQL_BACKUP_IDENTITY_FILE` mounted.

**The whole database, from a dump** — into a fresh, empty Postgres (RTO measured by the drill: seconds
for a stack this size; minutes for gigabytes, restored with four parallel jobs):

```bash
docker compose run --rm backup verify  storeql-20260923T101352Z.dump.age      # checksum, decryption, every entry
docker run --rm --network storeql_network -v storeql_backups:/backups:ro \
  -v ./identity.txt:/run/identity.txt:ro -e STOREQL_BACKUP_IDENTITY_FILE=/run/identity.txt \
  -e PGHOST=<fresh-postgres> -e PGPASSWORD=... ghcr.io/red2n/storeql-backup:latest \
  restore storeql-20260923T101352Z.dump.age
```

`restore` restores, re-applies the per-service roles (`infra/postgres-init-roles.sql`), and compares
every table's count with the manifest; a mismatch is printed and the command fails.

**To a moment** — the base backup before it plus the archived WAL, replayed to a time and promoted:

```bash
docker run -d --name recovered --network storeql_network -v storeql_backups:/backups:ro \
  ghcr.io/red2n/storeql-backup:latest pitr /backups/base/20260923T101305Z '2026-09-23 10:13:59+00'
```

The container serves the recovered database on 5432 once recovery has reached the target; a target
beyond the last archived segment is refused ("recovery ended before configured recovery target was
reached") rather than silently served short. Point the services at it, or dump it and restore. The
recovering server replays with `hot_standby = off`: it serves nothing until promoted, so it need not
carry the primary's `max_connections` and the other limits a compose or Kubernetes flag set there —
with hot standby on, Postgres refuses to replay under a lower setting ("insufficient parameter
settings"), which is what the second drill met.

**One business, not the whole database:** the tenant's own export and import (21.14, EU Data Act)
through each service's `/admin/tenant-data`, not a restore.

## The drill

`scripts/backup-drill.sh` rehearses all of it against the running stack, every time, and appends
what it measured to [RESTORE-REHEARSAL.md](RESTORE-REHEARSAL.md): a backup taken by the job, verified
as stored, restored into a fresh server and compared table by table; then a base backup, two marker
rows a few seconds apart, and a recovery to the moment between them — the first marker back, the
second not. `scripts/backup-selftest.sh` proves the same promises against throwaway containers,
plus the refusals (a changed byte, a missing identity), and runs in CI.

The first drill found what the tests had not: a restore into a fresh server failed on four tables,
because the array form of the UUIDv7 check (`uuid_all_v7`) called its sibling function without a
schema, and `pg_restore` runs with an empty search path. The 15 September rehearsal predates those
guards; the guard is now defined with a parsed body that binds the reference at creation, and the
test audit every service runs refuses a guard function that is not. The second drill found the
recovery refusing to replay because the stack's Postgres runs with `max_connections=300` and the
recovering one with the image's 100 (above); the self-test now starts its throwaway server with the
stack's setting so that refusal cannot come back unseen. Two findings from two drills is the argument
for the drill: neither was in a test, and both would have been met for the first time on the day a
restore was needed.
