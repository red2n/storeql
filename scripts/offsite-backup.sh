#!/usr/bin/env bash
#
# Copies the backups volume to somewhere that is not this machine (docs/BACKUP-AND-RESTORE.md,
# "Off-site"): a backup on the database's own disk -- or its own host -- is not a backup.
#
#   scripts/offsite-backup.sh             copy the dumps, base backups and archived WAL, then verify
#   scripts/offsite-backup.sh --verify    only verify what is there against the volume (nothing is written)
#
# What leaves the machine is already encrypted: the backup service encrypts every dump to
# STOREQL_BACKUP_RECIPIENT, an age public key, and the matching identity is not on this host. A dump
# that is not encrypted (no .age) is therefore REFUSED, not copied, unless OFFSITE_ALLOW_PLAINTEXT=1.
# The copy never deletes at the destination (a mistake here must not empty the off-site copy); retention
# there is the bucket's own lifecycle rule. After the copy every file is checked against the volume (size
# and hash where the remote keeps one); a missing or different file fails the run, loudly, and the
# success time is recorded only when the check passes (.storeql-deploy/offsite.last, the heartbeat a
# cron or an alert reads).
#
# Env: OFFSITE_REMOTE (required): an rclone remote path, `offsite:bucket/prefix` (defined in the rclone
#        config), or an absolute directory (used by the self-test and for a mounted drive)
#      RCLONE_CONFIG (~/.config/rclone/rclone.conf)   BACKUPS_VOLUME (storeql_backups)
#      RCLONE_IMAGE (rclone/rclone:1.68.2, pinned)    DEPLOY_DIR (.storeql-deploy)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
BACKUPS_VOLUME="${BACKUPS_VOLUME:-storeql_backups}"
RCLONE_IMAGE="${RCLONE_IMAGE:-rclone/rclone:1.68.2}"
RCLONE_CONFIG="${RCLONE_CONFIG:-$HOME/.config/rclone/rclone.conf}"
DEPLOY_DIR="${DEPLOY_DIR:-.storeql-deploy}"
SUBDIRS="dumps base wal"

red() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
say() { printf '\033[1;36m%s\033[0m\n' "$*"; }
die() { red "$*"; exit 1; }

VERIFY_ONLY=false
for a in "$@"; do
  case "$a" in
    --verify) VERIFY_ONLY=true ;;
    -h|--help) awk 'NR>1{if(/^#/){sub(/^# ?/,"");print}else exit}' "$0"; exit 0 ;;
    *) die "unknown argument: $a" ;;
  esac
done
[ -n "${OFFSITE_REMOTE:-}" ] || die "OFFSITE_REMOTE is not set: where should the backups go? (an rclone remote such as offsite:storeql-pilot, see docs/BACKUP-AND-RESTORE.md)"
docker volume inspect "$BACKUPS_VOLUME" >/dev/null 2>&1 || die "the backups volume $BACKUPS_VOLUME does not exist on this host"

# rclone runs in a container: the volume read-only, the config read-only, and -- for a local directory
# destination -- that directory read-write at the same path.
run_rclone() {
  local mounts=(-v "$BACKUPS_VOLUME:/backups:ro")
  [ -f "$RCLONE_CONFIG" ] && mounts+=(-v "$RCLONE_CONFIG:/config/rclone/rclone.conf:ro")
  case "$OFFSITE_REMOTE" in
    /*) mounts+=(-v "$OFFSITE_REMOTE:$OFFSITE_REMOTE") ;;
  esac
  docker run --rm "${mounts[@]}" "$RCLONE_IMAGE" "$@"
}
dest() { printf '%s/%s' "${OFFSITE_REMOTE%/}" "$1"; }

# 1. nothing readable by a thief leaves the machine
plain="$(docker run --rm -v "$BACKUPS_VOLUME:/backups:ro" alpine:3 sh -c 'ls /backups/dumps 2>/dev/null | grep -E "\.(dump|sql)$" || true')"
if [ -n "$plain" ] && [ "${OFFSITE_ALLOW_PLAINTEXT:-}" != "1" ]; then
  die "refusing to copy unencrypted dumps off this machine (set STOREQL_BACKUP_RECIPIENT so the backup service encrypts them): $(echo "$plain" | tr '\n' ' ')"
fi

# 2. copy (never delete at the destination)
mkdir -p "$DEPLOY_DIR"
if ! $VERIFY_ONLY; then
  for d in $SUBDIRS; do
    say "copying $d"
    run_rclone copy "/backups/$d" "$(dest "$d")" --checksum --transfers 4 --retries 3 >/dev/null 2>"$DEPLOY_DIR/.offsite.err" \
      || die "copying $d failed: $(tail -3 "$DEPLOY_DIR/.offsite.err" 2>/dev/null)"
  done
fi

# 3. every file checked against the volume: present, same size, same hash where the remote has one
failed=0
for d in $SUBDIRS; do
  out="$(run_rclone check "/backups/$d" "$(dest "$d")" --one-way 2>&1)"
  if [ $? -ne 0 ]; then
    red "verification of $d failed:"; echo "$out" | tail -5 >&2
    failed=1
  fi
done
[ "$failed" -eq 0 ] || die "the off-site copy does not match this machine's backups; do not rely on it"

latest="$(docker run --rm -v "$BACKUPS_VOLUME:/backups:ro" alpine:3 sh -c 'ls -1 /backups/dumps 2>/dev/null | grep -v manifest | sort | tail -1')"
mkdir -p "$DEPLOY_DIR"
printf '%s %s\n' "$(date -u +%FT%TZ)" "${latest:-none}" > "$DEPLOY_DIR/offsite.last"
rm -f "$DEPLOY_DIR/.offsite.err"
say "off-site copy verified (latest dump: ${latest:-none})"
