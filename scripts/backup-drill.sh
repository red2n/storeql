#!/usr/bin/env bash
# The backup-restore drill, rehearsed against the running stack (readiness review horizon 04): a
# backup is taken now by the backup job, verified as the stored artefact (checksum, decryption, every
# entry readable), restored into a fresh Postgres and compared with its manifest table by table; then a
# base backup is taken, two markers are written a few seconds apart, and the base backup plus the
# archived WAL are recovered to the moment between them — the first marker back, the second not. Every
# step is timed and the result appended to docs/RESTORE-REHEARSAL.md, so the recovery time and the
# data at risk promised are ones that were measured.
#
# Needs the stack's postgres and backup services up (docker compose up -d postgres backup) and, for an
# encrypted backup, STOREQL_BACKUP_IDENTITY_FILE naming the age identity that can read it.
#
# Usage: scripts/backup-drill.sh [--full-only] [report.md]
set -euo pipefail
cd "$(dirname "$0")/.."
FULL_ONLY=false
[ "${1:-}" = "--full-only" ] && { FULL_ONLY=true; shift; }
REPORT="${1:-docs/RESTORE-REHEARSAL.md}"
PROJECT="${COMPOSE_PROJECT_NAME:-$(basename "$PWD")}"
# Compose names the network itself and prefixes the volume with the project: read both from its config.
COMPOSE_JSON="$(docker compose config --format json)"
NET="$(echo "$COMPOSE_JSON" | python3 -c 'import json,sys; n=json.load(sys.stdin)["networks"]["storeql_network"]; print(n.get("name") or sys.argv[1] + "_storeql_network")' "$PROJECT")"
VOL="$(echo "$COMPOSE_JSON" | python3 -c 'import json,sys; v=json.load(sys.stdin)["volumes"]["backups"]; print(v.get("name") or sys.argv[1] + "_backups")' "$PROJECT")"
PG="${STOREQL_PG_CONTAINER:-storeql-postgres}"
DB="${STOREQL_PG_DB:-storeql}"
PGUSER_="${STOREQL_PG_USER:-storeql}"
IMAGE="$(echo "$COMPOSE_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["backup"]["image"])')"
IDENTITY="${STOREQL_BACKUP_IDENTITY_FILE:-}"
SCRATCH="storeql-drill-restore"
PITR="storeql-drill-pitr"
WORK="$(mktemp -d)"
cleanup() {
  docker rm -f "$SCRATCH" "$PITR" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
now() { date +%s.%N; }
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }
live() { docker exec -i "$PG" psql -U "$PGUSER_" -d "$DB" -At -q -v ON_ERROR_STOP=1 "$@"; }
identity_args=()
if [ -n "$IDENTITY" ]; then identity_args=(-v "$(realpath "$IDENTITY")":/run/identity.txt:ro -e STOREQL_BACKUP_IDENTITY_FILE=/run/identity.txt); fi
# The backup image against a server on the compose network, reading the stack's backups volume.
job() { # <pghost> <pgpassword> <args...>
  local host="$1" pass="$2"; shift 2
  docker run --rm --network "$NET" -v "$VOL":/backups:ro "${identity_args[@]}" -e PGHOST="$host" -e PGUSER="$PGUSER_" -e PGDATABASE="$DB" -e PGPASSWORD="$pass" "$IMAGE" "$@"
}

docker inspect "$PG" >/dev/null 2>&1 || { echo "the stack's Postgres ($PG) is not running: docker compose up -d postgres backup" >&2; exit 1; }
docker volume inspect "$VOL" >/dev/null 2>&1 || { echo "no backups volume $VOL: is the backup service in the stack?" >&2; exit 1; }
echo "backup drill: $PG/$DB, volume $VOL, image $IMAGE"

# ── 1. a backup, now, as the job takes it ────────────────────────────────────────────────────────
t0=$(now)
artefact="$(docker compose run --rm -T backup now 2>"$WORK/now.log")"
t_backup=$(now)
[ -n "$artefact" ] || { cat "$WORK/now.log" >&2; echo "no backup was taken" >&2; exit 1; }
manifest="$(docker run --rm -v "$VOL":/backups:ro "$IMAGE" manifest "$artefact")"
tables="$(echo "$manifest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tables"])')"
rows="$(echo "$manifest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["rows"])')"
bytes="$(echo "$manifest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["bytes"])')"
encrypted="$(echo "$manifest" | python3 -c 'import json,sys; print("yes" if json.load(sys.stdin)["encrypted"] else "no")')"
echo "backup $artefact: $tables tables, $rows rows, $bytes bytes, encrypted $encrypted, $(secs "$t0" "$t_backup")s"

# ── 2. verified as stored ────────────────────────────────────────────────────────────────────────
job postgres unused verify "$artefact" >/dev/null
t_verified=$(now)
echo "verified in $(secs "$t_backup" "$t_verified")s"

# ── 3. restored into a fresh server, table by table ──────────────────────────────────────────────
docker rm -f "$SCRATCH" >/dev/null 2>&1 || true
docker run -d --name "$SCRATCH" --network "$NET" -e POSTGRES_USER="$PGUSER_" -e POSTGRES_PASSWORD=drill -e POSTGRES_DB="$DB" postgres:16-alpine >/dev/null
for _ in $(seq 1 90); do docker exec "$SCRATCH" pg_isready -U "$PGUSER_" -d "$DB" >/dev/null 2>&1 && break; sleep 1; done
t_ready=$(now)
restore_out="$(job "$SCRATCH" drill restore "$artefact" 2>"$WORK/restore.log")" || { cat "$WORK/restore.log" >&2; echo "$restore_out"; echo "RESTORE MISMATCH" >&2; exit 1; }
t_restored=$(now)
pg_version="$(docker exec "$SCRATCH" psql -U "$PGUSER_" -d "$DB" -At -c "SHOW server_version")"
echo "restored: $restore_out (server up $(secs "$t_verified" "$t_ready")s, restore and verify $(secs "$t_ready" "$t_restored")s)"

# ── 4. to a moment: a base backup, two markers, the archived WAL ─────────────────────────────────
pitr_result="not run (--full-only)"
pitr_line=""
if [ "$FULL_ONLY" = false ]; then
  t_pitr0=$(now)
  base="$(docker compose run --rm -T backup base 2>"$WORK/base.log")"
  t_base=$(now)
  [ -n "$base" ] || { cat "$WORK/base.log" >&2; echo "no base backup was taken" >&2; exit 1; }
  live -c "CREATE SCHEMA IF NOT EXISTS ops" -c "CREATE TABLE IF NOT EXISTS ops.backup_drill_markers (id bigserial PRIMARY KEY, label text NOT NULL, at timestamptz NOT NULL DEFAULT now())" >/dev/null
  # Each marker committed on its own before the segment is switched: a commit after a switch lands in
  # a segment nobody has archived yet.
  live -c "INSERT INTO ops.backup_drill_markers (label) VALUES ('before-$base')" -c "SELECT pg_switch_wal()" >/dev/null
  sleep 2
  target="$(live -c "SELECT now()")"
  sleep 2
  live -c "INSERT INTO ops.backup_drill_markers (label) VALUES ('after-$base')" >/dev/null
  lastseg="$(live -c "SELECT pg_walfile_name(pg_current_wal_insert_lsn())")"
  live -c "SELECT pg_switch_wal()" >/dev/null
  archived=no
  for _ in $(seq 1 120); do
    la="$(live -c "SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver")"
    if [ -n "$la" ] && [ ! "$la" \< "$lastseg" ]; then archived=yes; break; fi
    sleep 1
  done
  t_archived=$(now)
  [ "$archived" = yes ] || { echo "the WAL carrying the markers was never archived: is archive_mode on?" >&2; exit 1; }
  docker rm -f "$PITR" >/dev/null 2>&1 || true
  docker run -d --name "$PITR" --network "$NET" -v "$VOL":/backups:ro "$IMAGE" pitr "/backups/base/$base" "$target" >/dev/null
  recovered=no
  for _ in $(seq 1 120); do
    if docker exec "$PITR" pg_isready -U "$PGUSER_" -d "$DB" >/dev/null 2>&1 && [ "$(docker exec "$PITR" psql -U "$PGUSER_" -d "$DB" -At -c 'SELECT pg_is_in_recovery()' 2>/dev/null)" = f ]; then recovered=yes; break; fi
    if ! docker inspect -f '{{.State.Running}}' "$PITR" 2>/dev/null | grep -q true; then break; fi
    sleep 1
  done
  t_recovered=$(now)
  if [ "$recovered" != yes ]; then docker logs "$PITR" 2>&1 | tail -30 >&2; echo "the point-in-time server never came up" >&2; exit 1; fi
  markers="$(docker exec "$PITR" psql -U "$PGUSER_" -d "$DB" -At -c "SELECT string_agg(label, ',' ORDER BY id) FROM ops.backup_drill_markers WHERE label LIKE '%-$base'")"
  restored_rows="$(docker exec "$PITR" psql -U "$PGUSER_" -d "$DB" -At -c "SELECT count(*) FROM ops.backup_drill_markers")"
  if [ "$markers" = "before-$base" ]; then pitr_result="the earlier write back, the later one not"; else pitr_result="WRONG: markers restored = '$markers'"; fi
  archive_timeout="$(live -c "SHOW archive_timeout")"
  pitr_line="- Point in time: base backup $(secs "$t_pitr0" "$t_base")s, WAL archived within $(secs "$t_base" "$t_archived")s of the switch, recovery to \`$target\` served in $(secs "$t_archived" "$t_recovered")s: **$pitr_result** ($restored_rows marker rows on the recovered server; archive_timeout $archive_timeout bounds the data at risk)"
  echo "point in time: $pitr_result (base $(secs "$t_pitr0" "$t_base")s, archive $(secs "$t_base" "$t_archived")s, recovery $(secs "$t_archived" "$t_recovered")s)"
fi
t_end=$(now)

{
  [ -s "$REPORT" ] || printf '# Backup and restore drills\n'
  printf '\n## %s\n\n' "$(date -u +'%Y-%m-%d %H:%M UTC')"
  printf -- '- Drill: `scripts/backup-drill.sh` against `%s`/`%s`; the backup taken by the stack'"'"'s backup job, restored on PostgreSQL %s\n' "$PG" "$DB" "$pg_version"
  printf -- '- Backup `%s`: %s tables, %s rows, %s bytes, encrypted %s; taken in %ss, verified as stored (checksum, decryption, every entry) in %ss\n' "$artefact" "$tables" "$rows" "$bytes" "$encrypted" "$(secs "$t0" "$t_backup")" "$(secs "$t_backup" "$t_verified")"
  printf -- '- Full restore: fresh server up %ss, restored with the roles re-applied and every table compared in %ss: **%s**\n' "$(secs "$t_verified" "$t_ready")" "$(secs "$t_ready" "$t_restored")" "$restore_out"
  [ -n "$pitr_line" ] && printf '%s\n' "$pitr_line"
  printf -- '- %ss in all\n' "$(secs "$t0" "$t_end")"
} >> "$REPORT"
echo "drill done in $(secs "$t0" "$t_end")s; appended to $REPORT"
case "$pitr_result" in WRONG*) exit 1;; esac
