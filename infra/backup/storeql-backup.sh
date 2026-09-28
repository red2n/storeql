#!/usr/bin/env bash
# storeql-backup — the stack's Postgres backed up, verified, restored and recovered to a moment.
#
#   storeql-backup schedule            take a backup every day at STOREQL_BACKUP_AT (UTC), a base
#                                      backup every STOREQL_BACKUP_BASE_EVERY_DAYS, prune, repeat
#   storeql-backup now                 one backup now; prints the artefact's name
#   storeql-backup base                one base backup now (for point-in-time recovery); prints its name
#   storeql-backup verify <artefact>   the checksum, the decryption, and pg_restore reading the whole
#   storeql-backup manifest <artefact> the artefact's manifest
#   storeql-backup restore <artefact>  restore into the server named by PGHOST/PGDATABASE, re-apply
#                                      the per-service roles, and compare every table's count to the
#                                      manifest (exit 1 on any mismatch)
#   storeql-backup pitr <base-dir> <time>   lay the base backup down as this container's data
#                                      directory, replay the archived WAL to <time>, promote, and
#                                      serve — the point-in-time restore
#   storeql-backup prune               apply the retention now
#
# A backup is pg_dump's custom format taken from one exported snapshot, beside a manifest written in
# the same snapshot: every table's row count, the artefact's SHA-256, the WAL position and the server
# version. With STOREQL_BACKUP_RECIPIENT (an age public key) the artefact is encrypted to it, and
# only the matching identity (STOREQL_BACKUP_IDENTITY_FILE) can read it back; without one the backup
# is plain and the log says so. Retention keeps the newest STOREQL_BACKUP_KEEP dumps and
# STOREQL_BACKUP_KEEP_BASE base backups, and archived WAL older than the oldest kept base backup goes.
#
# Connection: PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSWORD as libpq reads them.
set -euo pipefail

ROOT="${STOREQL_BACKUP_ROOT:-/backups}"
DUMPS="$ROOT/dumps"
BASES="$ROOT/base"
WAL="$ROOT/wal"
METRICS="$ROOT/metrics"
KEEP="${STOREQL_BACKUP_KEEP:-14}"
KEEP_BASE="${STOREQL_BACKUP_KEEP_BASE:-4}"
AT="${STOREQL_BACKUP_AT:-02:30}"
BASE_EVERY_DAYS="${STOREQL_BACKUP_BASE_EVERY_DAYS:-7}"
RECIPIENT="${STOREQL_BACKUP_RECIPIENT:-}"
IDENTITY="${STOREQL_BACKUP_IDENTITY_FILE:-}"
ROLES_SQL="${STOREQL_BACKUP_ROLES_SQL:-/usr/local/share/storeql/postgres-init-roles.sql}"
: "${PGHOST:=postgres}" "${PGPORT:=5432}" "${PGUSER:=storeql}" "${PGDATABASE:=storeql}"
export PGHOST PGPORT PGUSER PGDATABASE

log() { printf '%s backup: %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "$*"; exit 1; }
now_s() { date +%s.%N; }
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }
ensure_dirs() { mkdir -p "$DUMPS" "$BASES" "$WAL" "$METRICS"; }

# Every table's row count, one line each: schema.table|n
COUNTS_SQL="SELECT table_schema || '.' || table_name || '|' ||
  (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', table_schema, table_name),
    false, true, '')))[1]::text
  FROM information_schema.tables
  WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
  ORDER BY 1"

manifest_path() { # the manifest beside an artefact
  local name="$1"
  name="${name%.age}"
  name="${name%.dump}"
  echo "$DUMPS/$name.manifest.json"
}

# ── now ───────────────────────────────────────────────────────────────────────────────────────
cmd_now() {
  ensure_dirs
  local ts work snapshot dump artefact sha bytes lsn version t0 t1 tables rows
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  t0=$(now_s)
  # One snapshot: exported from a session held open, dumped, counted, then let go.
  coproc SNAP { psql -At -q -v ON_ERROR_STOP=1 2>>"$work/snap.err"; }
  echo "BEGIN ISOLATION LEVEL REPEATABLE READ; SELECT pg_export_snapshot();" >&"${SNAP[1]}"
  snapshot=""
  while IFS= read -r -t 60 line <&"${SNAP[0]}"; do
    if [[ "$line" =~ ^[0-9A-F]+-[0-9A-F]+-[0-9]+$ ]]; then snapshot="$line"; break; fi
  done
  [ -n "$snapshot" ] || die "no snapshot exported: $(cat "$work/snap.err" 2>/dev/null)"
  dump="$work/storeql-$ts.dump"
  pg_dump -Fc --snapshot="$snapshot" -f "$dump"
  {
    echo "\\o $work/counts.txt"
    echo "$COUNTS_SQL;"
    echo "\\o"
    echo "SELECT 'lsn=' || pg_current_wal_lsn()::text;"
    echo "SELECT 'version=' || current_setting('server_version');"
    echo "SELECT 'counted';"
  } >&"${SNAP[1]}"
  lsn=""; version=""
  while IFS= read -r -t 120 line <&"${SNAP[0]}"; do
    case "$line" in
      lsn=*) lsn="${line#lsn=}" ;;
      version=*) version="${line#version=}" ;;
      counted) break ;;
    esac
  done
  echo "COMMIT;" >&"${SNAP[1]}"
  eval "exec ${SNAP[1]}>&-"
  wait "$SNAP_PID" 2>/dev/null || true
  [ -s "$work/counts.txt" ] || die "the snapshot session never counted the tables"

  if [ -n "$RECIPIENT" ]; then
    artefact="storeql-$ts.dump.age"
    age -r "$RECIPIENT" -o "$DUMPS/$artefact" "$dump"
  else
    artefact="storeql-$ts.dump"
    cp "$dump" "$DUMPS/$artefact"
    log "no STOREQL_BACKUP_RECIPIENT: this backup is UNENCRYPTED; set an age public key for any deployment holding real data"
  fi
  sha="$(sha256sum "$DUMPS/$artefact" | cut -d' ' -f1)"
  bytes="$(stat -c %s "$DUMPS/$artefact")"
  t1=$(now_s)
  tables="$(wc -l < "$work/counts.txt")"
  rows="$(awk -F'|' '{ n += $2 } END { print n + 0 }' "$work/counts.txt")"
  jq -n \
    --arg takenAt "$(date -u +%FT%TZ)" --arg database "$PGDATABASE" --arg host "$PGHOST" \
    --arg version "$version" --arg artefact "$artefact" --argjson encrypted "$([ -n "$RECIPIENT" ] && echo true || echo false)" \
    --arg recipient "$RECIPIENT" --argjson bytes "$bytes" --arg sha256 "$sha" --arg snapshot "$snapshot" \
    --arg lsn "$lsn" --argjson tables "$tables" --argjson rows "$rows" --arg duration "$(secs "$t0" "$t1")" \
    --rawfile counts "$work/counts.txt" '
    {takenAt: $takenAt, database: $database, host: $host, serverVersion: $version, format: "pg_dump custom",
     artefact: $artefact, encrypted: $encrypted, recipient: (if $encrypted then $recipient else null end),
     bytes: $bytes, sha256: $sha256, snapshot: $snapshot, walLsn: $lsn, tables: $tables, rows: $rows,
     durationSeconds: ($duration | tonumber),
     counts: ($counts | split("\n") | map(select(length > 0)) | map(split("|")) | map({key: .[0], value: (.[1] | tonumber)}) | from_entries)}' \
    > "$(manifest_path "$artefact")"
  cp "$(manifest_path "$artefact")" "$DUMPS/latest.json"
  prune_dumps
  write_metrics "$t1" "$(secs "$t0" "$t1")" "$bytes" "$tables" "$rows"
  log "took $artefact: $tables tables, $rows rows, $bytes bytes in $(secs "$t0" "$t1")s"
  echo "$artefact"
}

prune_dumps() {
  local keep="$KEEP" n=0
  # Newest first by name (the timestamp is in the name); everything past KEEP goes, manifest and all.
  for m in $(ls -1 "$DUMPS"/storeql-*.manifest.json 2>/dev/null | sort -r); do
    n=$((n + 1))
    if [ "$n" -gt "$keep" ]; then
      local art
      art="$(jq -r .artefact "$m")"
      rm -f "$DUMPS/$art" "$m"
      log "pruned $art"
    fi
  done
}

write_metrics() { # t, duration, bytes, tables, rows
  local tmp="$METRICS/storeql_backup.prom.tmp" base_ts=0 kept encrypted
  kept="$(ls -1 "$DUMPS"/storeql-*.manifest.json 2>/dev/null | wc -l)"
  encrypted="$([ -n "$RECIPIENT" ] && echo 1 || echo 0)"
  if [ -n "$(ls -1d "$BASES"/*/ 2>/dev/null)" ]; then
    base_ts="$(jq -r '.takenAtEpoch // 0' "$(ls -1d "$BASES"/*/ | sort | tail -1)manifest.json" 2>/dev/null || echo 0)"
  fi
  {
    echo "# HELP storeql_backup_last_success_timestamp_seconds When the last backup finished, unix seconds."
    echo "# TYPE storeql_backup_last_success_timestamp_seconds gauge"
    printf 'storeql_backup_last_success_timestamp_seconds %d\n' "${1%.*}"
    echo "# TYPE storeql_backup_last_duration_seconds gauge"
    echo "storeql_backup_last_duration_seconds $2"
    echo "# TYPE storeql_backup_last_bytes gauge"
    echo "storeql_backup_last_bytes $3"
    echo "# TYPE storeql_backup_last_tables gauge"
    echo "storeql_backup_last_tables $4"
    echo "# TYPE storeql_backup_last_rows gauge"
    echo "storeql_backup_last_rows $5"
    echo "# HELP storeql_backup_encrypted 1 when backups are encrypted to a recipient, 0 when they are plain."
    echo "# TYPE storeql_backup_encrypted gauge"
    echo "storeql_backup_encrypted $encrypted"
    echo "# TYPE storeql_backup_kept gauge"
    echo "storeql_backup_kept $kept"
    echo "# HELP storeql_backup_base_last_timestamp_seconds When the last base backup for point-in-time recovery was taken."
    echo "# TYPE storeql_backup_base_last_timestamp_seconds gauge"
    echo "storeql_backup_base_last_timestamp_seconds $base_ts"
  } > "$tmp"
  mv "$tmp" "$METRICS/storeql_backup.prom"
}

# ── base ──────────────────────────────────────────────────────────────────────────────────────
cmd_base() {
  ensure_dirs
  local ts dir t0 t1 start_file label
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  dir="$BASES/$ts"
  mkdir -p "$dir"
  t0=$(now_s)
  pg_basebackup -D "$dir" -Ft -z -X none -c fast -l "storeql-$ts" --no-password
  t1=$(now_s)
  label="$(tar -xzOf "$dir/base.tar.gz" backup_label)"
  start_file="$(echo "$label" | sed -n 's/^START WAL LOCATION: .* (file \([0-9A-F]*\))$/\1/p')"
  jq -n --arg takenAt "$(date -u +%FT%TZ)" --argjson takenAtEpoch "${t1%.*}" --arg name "$ts" \
    --arg startWalFile "$start_file" --arg label "$label" --arg duration "$(secs "$t0" "$t1")" \
    --argjson bytes "$(stat -c %s "$dir/base.tar.gz")" \
    '{name: $name, takenAt: $takenAt, takenAtEpoch: $takenAtEpoch, startWalFile: $startWalFile, bytes: $bytes,
      durationSeconds: ($duration | tonumber), backupLabel: $label}' > "$dir/manifest.json"
  prune_bases
  log "base backup $ts: $(stat -c %s "$dir/base.tar.gz") bytes in $(secs "$t0" "$t1")s, WAL from $start_file"
  echo "$ts"
}

prune_bases() {
  local n=0 oldest_kept=""
  for d in $(ls -1d "$BASES"/*/ 2>/dev/null | sort -r); do
    n=$((n + 1))
    if [ "$n" -gt "$KEEP_BASE" ]; then rm -rf "$d"; log "pruned base backup $(basename "$d")"; else oldest_kept="$d"; fi
  done
  # Archived WAL older than the oldest kept base backup can never be needed again.
  if [ -n "$oldest_kept" ] && [ -f "${oldest_kept}manifest.json" ]; then
    local start
    start="$(jq -r .startWalFile "${oldest_kept}manifest.json")"
    if [ -n "$start" ] && [ "$start" != null ]; then pg_archivecleanup "$WAL" "$start" 2>/dev/null || true; fi
  fi
}

# ── verify / manifest / restore ───────────────────────────────────────────────────────────────
plain_dump() { # decrypts (or copies) an artefact into $2
  local art="$DUMPS/$1" out="$2"
  [ -f "$art" ] || die "no such artefact: $1"
  case "$1" in
    *.age)
      [ -n "$IDENTITY" ] && [ -f "$IDENTITY" ] || die "$1 is encrypted; STOREQL_BACKUP_IDENTITY_FILE must name the identity that can read it"
      age -d -i "$IDENTITY" -o "$out" "$art" ;;
    *) cp "$art" "$out" ;;
  esac
}

cmd_manifest() { cat "$(manifest_path "$1")"; }

cmd_verify() {
  local art="$1" m sha work entries
  m="$(manifest_path "$art")"
  [ -f "$m" ] || die "no manifest for $art"
  sha="$(sha256sum "$DUMPS/$art" | cut -d' ' -f1)"
  [ "$sha" = "$(jq -r .sha256 "$m")" ] || die "checksum mismatch for $art: the artefact is not what was written"
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  plain_dump "$art" "$work/dump"
  entries="$(pg_restore --list "$work/dump" | grep -c '^[0-9]')"
  [ "$entries" -gt 0 ] || die "pg_restore reads nothing from $art"
  log "verified $art: checksum, decryption, $entries entries, $(jq -r .tables "$m") tables in the manifest"
  echo "ok $art $entries"
}

cmd_restore() {
  local art="$1" m work t0 t1 t2 mismatches
  m="$(manifest_path "$art")"
  [ -f "$m" ] || die "no manifest for $art"
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  cmd_verify "$art" >/dev/null
  plain_dump "$art" "$work/dump"
  t0=$(now_s)
  pg_restore --no-owner --no-privileges -j 4 -d "$PGDATABASE" "$work/dump"
  t1=$(now_s)
  if [ -f "$ROLES_SQL" ]; then
    psql -q -v ON_ERROR_STOP=1 -f "$ROLES_SQL" >/dev/null
  fi
  psql -At -c "$COUNTS_SQL" > "$work/restored.txt"
  jq -r '.counts | to_entries[] | "\(.key)|\(.value)"' "$m" | sort > "$work/expected.txt"
  sort "$work/restored.txt" > "$work/restored.sorted"
  t2=$(now_s)
  mismatches="$(diff "$work/expected.txt" "$work/restored.sorted" || true)"
  if [ -n "$mismatches" ]; then
    log "restore of $art: MISMATCHES"
    echo "$mismatches"
    exit 1
  fi
  log "restored $art into $PGHOST/$PGDATABASE in $(secs "$t0" "$t1")s (roles and verify $(secs "$t1" "$t2")s): every table matched"
  echo "every table matched: $(jq -r .tables "$m") tables, $(jq -r .rows "$m") rows, restore $(secs "$t0" "$t1")s"
}

# ── pitr ──────────────────────────────────────────────────────────────────────────────────────
cmd_pitr() {
  local base="$1" target="$2" data="${PGDATA:-/var/lib/postgresql/data}"
  [ -f "$base/base.tar.gz" ] || die "no base backup at $base"
  [ -n "$target" ] || die "a recovery target time is needed"
  mkdir -p "$data"
  chmod 0700 "$data"
  tar -xzf "$base/base.tar.gz" -C "$data"
  rm -rf "$data/pg_wal"
  mkdir -m 0700 "$data/pg_wal"
  cat >> "$data/postgresql.auto.conf" <<CONF
# storeql-backup pitr
restore_command = 'cp $WAL/%f %p'
recovery_target_time = '$target'
recovery_target_action = 'promote'
archive_mode = off
# Replay only, then promote: with hot standby off the recovering server need not match the
# primary's max_connections, workers, senders or locks, which a compose or k8s flag set there.
hot_standby = off
CONF
  touch "$data/recovery.signal"
  log "recovering $base to $target from $WAL, then serving"
  exec postgres -D "$data" -c listen_addresses='*'
}

# ── schedule ──────────────────────────────────────────────────────────────────────────────────
next_run_epoch() {
  local now hh mm midnight target
  now="$(date -u +%s)"
  hh="${AT%%:*}"; mm="${AT##*:}"
  midnight=$(( now - now % 86400 ))
  target=$(( midnight + 10#$hh * 3600 + 10#$mm * 60 ))
  [ "$target" -le "$now" ] && target=$(( target + 86400 ))
  echo "$target"
}

base_due() {
  local newest
  newest="$(ls -1d "$BASES"/*/ 2>/dev/null | sort | tail -1)"
  [ -z "$newest" ] && return 0
  local taken
  taken="$(jq -r '.takenAtEpoch // 0' "${newest}manifest.json" 2>/dev/null || echo 0)"
  [ $(( $(date -u +%s) - taken )) -ge $(( BASE_EVERY_DAYS * 86400 )) ]
}

# One run, for a scheduler that is not this script (the k8s CronJob): a backup, and a base backup
# when one is due.
cmd_nightly() {
  ensure_dirs
  cmd_now
  if base_due; then cmd_base; else log "base backup not due (every $BASE_EVERY_DAYS days)"; fi
}

cmd_schedule() {
  ensure_dirs
  log "scheduled: a backup every day at $AT UTC, a base backup every $BASE_EVERY_DAYS days, keeping $KEEP dumps and $KEEP_BASE base backups$([ -n "$RECIPIENT" ] && echo ", encrypted to $RECIPIENT" || echo ", UNENCRYPTED")"
  if [ -z "$(ls -1 "$DUMPS"/storeql-*.manifest.json 2>/dev/null)" ]; then
    log "no backup yet: taking one now"
    cmd_now >/dev/null || log "the first backup failed; the schedule carries on"
  fi
  if base_due; then cmd_base >/dev/null || log "the base backup failed; the schedule carries on"; fi
  while :; do
    local next
    next="$(next_run_epoch)"
    log "next backup at $(date -u -d "@$next" +%FT%TZ 2>/dev/null || echo "$next")"
    sleep $(( next - $(date -u +%s) ))
    cmd_now >/dev/null || log "the backup failed; trying again tomorrow"
    if base_due; then cmd_base >/dev/null || log "the base backup failed; trying again tomorrow"; fi
  done
}

case "${1:-schedule}" in
  schedule) cmd_schedule ;;
  now) cmd_now ;;
  nightly) cmd_nightly ;;
  base) cmd_base ;;
  verify) [ $# -ge 2 ] || die "verify <artefact>"; cmd_verify "$2" ;;
  manifest) [ $# -ge 2 ] || die "manifest <artefact>"; cmd_manifest "$2" ;;
  restore) [ $# -ge 2 ] || die "restore <artefact>"; cmd_restore "$2" ;;
  pitr) [ $# -ge 3 ] || die "pitr <base-dir> <time>"; cmd_pitr "$2" "$3" ;;
  prune) ensure_dirs; prune_dumps; prune_bases ;;
  age-keygen) exec age-keygen ;;
  sh) shift; exec sh "$@" ;;
  *) die "unknown command: $1" ;;
esac
