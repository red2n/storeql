#!/usr/bin/env bash
#
# Drives the backup and restore machinery end to end on this machine, against throwaway containers
# (the rehearsed backup-restore drill, readiness review horizon 04): a Postgres with WAL archiving,
# the storeql-backup image built from infra/backup, and every promise the drill relies on — a backup
# taken from one snapshot with a manifest of every table's count, encrypted to a recipient the backup
# host cannot read, unreadable without the identity, a changed byte caught, restored row for row into a
# fresh server, retention keeping the newest, a base backup plus archived WAL restored to a moment so
# that a later write is not there, and the same without encryption when no recipient is given.
#
# Needs docker. Usage: scripts/backup-selftest.sh     exit 0 only when every check passed
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="selftest-$$"
NET="storeql-backup-$RUN"
VOL="storeql-backup-$RUN"
PG="storeql-backup-$RUN-pg"
PG2="storeql-backup-$RUN-restore"
PITR="storeql-backup-$RUN-pitr"
IMAGE="storeql-backup:selftest"
WORK="$(mktemp -d)"
passed=0
failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
cleanup() {
  docker rm -f "$PG" "$PG2" "$PITR" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm "$VOL" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

psqlpg() { docker exec -i "$1" psql -U storeql -d storeql -At -q -v ON_ERROR_STOP=1 "${@:2}"; }
wait_ready() {
  for _ in $(seq 1 90); do docker exec "$1" pg_isready -U storeql -d storeql >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
# The backup image against the throwaway server, with the recipient and identity given.
backup() {
  docker run --rm --network "$NET" -v "$VOL":/backups -v "$WORK/identity.txt":/run/identity.txt:ro \
    -e PGHOST="$PG" -e PGPASSWORD=selftest -e STOREQL_BACKUP_IDENTITY_FILE=/run/identity.txt "$@"
}

echo "backup self-test: building $IMAGE"
docker build -q -t "$IMAGE" -f "$ROOT/infra/backup/Dockerfile" "$ROOT" >/dev/null || { echo "the backup image does not build" >&2; exit 1; }
docker network create "$NET" >/dev/null
docker volume create "$VOL" >/dev/null

# ── a Postgres that archives its WAL, as docker-compose.yml's does ────────────────────────────────
docker run -d --name "$PG" --network "$NET" -v "$VOL":/backups \
  -v "$ROOT/infra/postgres-init-hba.sh":/docker-entrypoint-initdb.d/02-replication-hba.sh:ro \
  -e POSTGRES_USER=storeql -e POSTGRES_PASSWORD=selftest -e POSTGRES_DB=storeql \
  postgres:16-alpine sh -c 'mkdir -p /backups/wal /backups/dumps /backups/base /backups/metrics && chown -R postgres:postgres /backups && exec docker-entrypoint.sh postgres -c max_connections=300 -c archive_mode=on -c "archive_command=test ! -f /backups/wal/%f && cp %p /backups/wal/%f" -c archive_timeout=60' >/dev/null
# max_connections=300 as the compose stack sets it: a recovering server with the image's default
# 100 refused to replay under hot standby ("insufficient parameter settings") until pitr turned it off.
wait_ready "$PG" || { echo "the throwaway Postgres never came up" >&2; docker logs "$PG" | tail -20; exit 1; }
psqlpg "$PG" <<'SQL'
CREATE SCHEMA app;
CREATE TABLE app.orders (id bigserial PRIMARY KEY, note text NOT NULL, at timestamptz NOT NULL DEFAULT now());
INSERT INTO app.orders (note) SELECT 'order ' || g FROM generate_series(1, 1000) g;
CREATE TABLE app.markers (id bigserial PRIMARY KEY, label text NOT NULL, at timestamptz NOT NULL DEFAULT now());
SQL
check "the server allows the backup job's replication connection (pg_hba)" "docker exec $PG grep -q storeql-backup /var/lib/postgresql/data/pg_hba.conf"

# ── a recipient the backup host cannot read for ────────────────────────────────────────────────
docker run --rm "$IMAGE" age-keygen > "$WORK/identity.txt" 2>/dev/null
RECIPIENT="$(grep -o 'age1[0-9a-z]*' "$WORK/identity.txt" | head -1)"
check "an age key pair is made for the run" "[ -n '$RECIPIENT' ]"

# ── a backup: one snapshot, a manifest, encrypted to the recipient ───────────────────────────────
first="$(backup -e STOREQL_BACKUP_RECIPIENT="$RECIPIENT" "$IMAGE" now 2>"$WORK/now1.log")"
echo "$first" > "$WORK/first.txt"
check "a backup is taken and names its artefact" "[ -n '$first' ]"
manifest="$(docker run --rm -v "$VOL":/backups:ro "$IMAGE" manifest "$first" 2>/dev/null)"
check "the artefact is encrypted to the recipient" "case '$first' in *.dump.age) true;; *) false;; esac && echo '$manifest' | grep -q '\"encrypted\": *true'"
check "the manifest counts every table from the same snapshot" "echo '$manifest' | grep -q '\"app.orders\": *1000'"
check "the manifest carries the artefact's checksum and the WAL position" "echo '$manifest' | grep -q '\"sha256\"' && echo '$manifest' | grep -q '\"walLsn\"'"
check "the backup writes its metrics for the node exporter" "docker run --rm -v $VOL:/backups:ro $IMAGE sh -c 'grep -q storeql_backup_last_success_timestamp_seconds /backups/metrics/storeql_backup.prom'"

# ── verified: with the identity, not without; a changed byte is caught ───────────────────────────
check "the backup verifies with the identity" "backup $IMAGE verify '$first' >/dev/null 2>&1"
check "it cannot be read without the identity" "! docker run --rm --network $NET -v $VOL:/backups:ro $IMAGE verify '$first' >/dev/null 2>&1"
docker run --rm -v "$VOL":/backups "$IMAGE" sh -c "printf 'x' | dd of=/backups/dumps/$first bs=1 seek=200 conv=notrunc 2>/dev/null"
check "a changed byte in the artefact is caught" "! backup $IMAGE verify '$first' >/dev/null 2>&1"

# ── restored row for row into a fresh server ─────────────────────────────────────────────────────
second="$(backup -e STOREQL_BACKUP_RECIPIENT="$RECIPIENT" "$IMAGE" now 2>"$WORK/now2.log")"
docker run -d --name "$PG2" --network "$NET" -e POSTGRES_USER=storeql -e POSTGRES_PASSWORD=restore -e POSTGRES_DB=storeql postgres:16-alpine >/dev/null
wait_ready "$PG2"
restore_out="$(docker run --rm --network "$NET" -v "$VOL":/backups:ro -v "$WORK/identity.txt":/run/identity.txt:ro -e PGHOST="$PG2" -e PGPASSWORD=restore -e STOREQL_BACKUP_IDENTITY_FILE=/run/identity.txt "$IMAGE" restore "$second" 2>&1)"
check "restored into a fresh server, every table matching its manifest" "echo '$restore_out' | grep -q 'every table matched'"
check "the rows are there" "[ \"\$(psqlpg $PG2 -c 'SELECT count(*) FROM app.orders')\" = 1000 ]"
check "the per-service roles are re-applied on restore" "[ \"\$(psqlpg $PG2 -c \"SELECT count(*) FROM pg_roles WHERE rolname = 'iam_svc'\")\" = 1 ]"

# ── retention keeps the newest ───────────────────────────────────────────────────────────────────
third="$(backup -e STOREQL_BACKUP_RECIPIENT="$RECIPIENT" -e STOREQL_BACKUP_KEEP=1 "$IMAGE" now 2>"$WORK/now3.log")"
kept="$(docker run --rm -v "$VOL":/backups:ro "$IMAGE" sh -c 'ls /backups/dumps/*.dump.age | wc -l')"
check "retention keeps only the newest when asked to keep one" "[ '$kept' = 1 ] && docker run --rm -v $VOL:/backups:ro $IMAGE sh -c 'test -f /backups/dumps/$third'"

# ── a base backup, archived WAL, and a restore to a moment ───────────────────────────────────────
base="$(backup -e STOREQL_BACKUP_RECIPIENT="$RECIPIENT" "$IMAGE" base 2>"$WORK/base.log")"
check "a base backup is taken for point-in-time recovery" "[ -n '$base' ] && docker run --rm -v $VOL:/backups:ro $IMAGE sh -c 'test -f /backups/base/$base/base.tar.gz && test -f /backups/base/$base/manifest.json'"
# Each marker committed on its own, then the segment switched: an insert and a switch in one statement
# string share a transaction, and its commit lands after the switch, in a segment nobody archives yet.
psqlpg "$PG" -c "INSERT INTO app.markers (label) VALUES ('before')" -c "SELECT pg_switch_wal()" >/dev/null
sleep 2
target="$(psqlpg "$PG" -c "SELECT now()")"
sleep 2
psqlpg "$PG" -c "INSERT INTO app.markers (label) VALUES ('after')" >/dev/null
lastseg="$(psqlpg "$PG" -c "SELECT pg_walfile_name(pg_current_wal_insert_lsn())")"
psqlpg "$PG" -c "SELECT pg_switch_wal()" >/dev/null
archived=no
for _ in $(seq 1 60); do
  la="$(psqlpg "$PG" -c "SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver")"
  if [ -n "$la" ] && [ ! "$la" \< "$lastseg" ]; then archived=yes; break; fi
  sleep 1
done
check "the WAL carrying both writes is archived" "[ '$archived' = yes ]"
docker run -d --name "$PITR" --network "$NET" -v "$VOL":/backups:ro "$IMAGE" pitr "/backups/base/$base" "$target" >/dev/null
recovered=no
for _ in $(seq 1 90); do
  if docker exec "$PITR" pg_isready -U storeql -d storeql >/dev/null 2>&1 && [ "$(psqlpg "$PITR" -c 'SELECT pg_is_in_recovery()' 2>/dev/null)" = f ]; then recovered=yes; break; fi
  sleep 1
done
if [ "$recovered" != yes ]; then
  echo "  ---- the point-in-time server's log:" >&2
  docker logs "$PITR" 2>&1 | tail -40 >&2
  echo "  ---- its data directory:" >&2
  docker exec "$PITR" ls -la /var/lib/postgresql/data 2>&1 | head -12 >&2
fi
check "the base backup and the archived WAL come up as a server, recovery finished and promoted" "[ '$recovered' = yes ]"
check "restored to the moment: the earlier write is there, the later one is not" "[ \"\$(psqlpg $PITR -c \"SELECT string_agg(label, ',' ORDER BY id) FROM app.markers\")\" = before ]"
check "...and the thousand orders came with the base backup" "[ \"\$(psqlpg $PITR -c 'SELECT count(*) FROM app.orders')\" = 1000 ]"

# ── without a recipient: plain, said so, and readable as it is ───────────────────────────────────
plain="$(docker run --rm --network "$NET" -v "$VOL":/backups -e PGHOST="$PG" -e PGPASSWORD=selftest "$IMAGE" now 2>"$WORK/now4.log")"
check "with no recipient the backup is plain and its manifest says so" "case '$plain' in *.dump) true;; *) false;; esac && docker run --rm -v $VOL:/backups:ro $IMAGE manifest '$plain' | grep -q '\"encrypted\": *false'"
check "...and verifies without any identity" "docker run --rm -v $VOL:/backups:ro $IMAGE verify '$plain' >/dev/null 2>&1"
check "the log said aloud that it was unencrypted" "grep -qi 'unencrypted' $WORK/now4.log"

echo
echo "backup self-test: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
