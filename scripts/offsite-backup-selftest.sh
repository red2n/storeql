#!/usr/bin/env bash
#
# Proves scripts/offsite-backup.sh against a throwaway docker volume and a local directory standing in
# for the bucket: the dumps, base backups and archived WAL arrive and verify; the copy is repeatable;
# an unencrypted dump is refused and nothing leaves; a file lost or changed off-site is caught by
# --verify; a copy never deletes what is already off-site; the heartbeat is written only after a
# verified copy; a missing destination or volume is refused.
#   scripts/offsite-backup-selftest.sh     exit 0 only when every check passed
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
VOL="storeql_offsite_selftest_$$"
passed=0
failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }
cleanup() { docker volume rm -f "$VOL" >/dev/null 2>&1; docker run --rm -v "$WORK:/w" alpine:3 rm -rf /w/remote /w/deploy >/dev/null 2>&1; rm -rf "$WORK"; }
trap cleanup EXIT

mkdir -p "$WORK/remote" "$WORK/deploy"
docker volume create "$VOL" >/dev/null
# fixture: two encrypted dumps with manifests, a base backup, some WAL
docker run --rm -v "$VOL:/b" alpine:3 sh -c '
  mkdir -p /b/dumps /b/base/20261009 /b/wal
  echo "ciphertext-1" > /b/dumps/storeql-20261008.dump.age; echo "{\"rows\":1}" > /b/dumps/storeql-20261008.manifest.json
  echo "ciphertext-2" > /b/dumps/storeql-20261009.dump.age; echo "{\"rows\":2}" > /b/dumps/storeql-20261009.manifest.json
  echo "base" > /b/base/20261009/base.tar.age
  for i in 1 2 3; do echo "wal-$i" > /b/wal/00000001000000000000000$i; done' >/dev/null

run() { OFFSITE_REMOTE="$WORK/remote" BACKUPS_VOLUME="$VOL" DEPLOY_DIR="$WORK/deploy" RCLONE_CONFIG=/nonexistent "$ROOT/scripts/offsite-backup.sh" "$@" 2>&1; }
remote_has() { [ -f "$WORK/remote/$1" ]; }

out="$(run)"; st=$?
[ $st -eq 0 ] && ok "a copy succeeds" || bad "a copy succeeds: $out"
remote_has dumps/storeql-20261009.dump.age && remote_has dumps/storeql-20261009.manifest.json && ok "the newest dump and its manifest arrive" || bad "dump arrives"
remote_has base/20261009/base.tar.age && ok "the base backup arrives, keeping its folder" || bad "base arrives"
remote_has wal/000000010000000000000003 && ok "the archived WAL arrives" || bad "wal arrives"
[ "$(cut -d' ' -f2 "$WORK/deploy/offsite.last" 2>/dev/null)" = "storeql-20261009.dump.age" ] && ok "the heartbeat names the latest dump" || bad "heartbeat: $(cat "$WORK/deploy/offsite.last" 2>/dev/null)"

out="$(run)"; st=$?
[ $st -eq 0 ] && ok "copying again is a no-op that still verifies" || bad "second copy: $out"

# a file lost off-site is caught
docker run --rm -v "$WORK:/w" alpine:3 rm /w/remote/dumps/storeql-20261008.dump.age
out="$(run --verify)"; st=$?
[ $st -ne 0 ] && ok "--verify catches a dump lost off-site" || bad "--verify should fail: $out"
rm -f "$WORK/deploy/offsite.last"
out="$(run)"; st=$?
[ $st -eq 0 ] && remote_has dumps/storeql-20261008.dump.age && ok "the next copy puts it back" || bad "restore of lost file: $out"

# a file changed off-site is caught
docker run --rm -v "$WORK:/w" alpine:3 sh -c 'echo tampered > /w/remote/wal/000000010000000000000002'
out="$(run --verify)"; st=$?
[ $st -ne 0 ] && ok "--verify catches a file that differs off-site" || bad "--verify should fail on a changed file: $out"
run >/dev/null

# a copy never deletes at the destination
docker run --rm -v "$WORK:/w" alpine:3 sh -c 'echo old > /w/remote/dumps/storeql-19990101.dump.age'
run >/dev/null
remote_has dumps/storeql-19990101.dump.age && ok "a copy never deletes what is already off-site" || bad "a copy deleted a destination file"

# an unencrypted dump is refused and nothing new leaves
rm -f "$WORK/deploy/offsite.last"
docker run --rm -v "$VOL:/b" alpine:3 sh -c 'echo plain > /b/dumps/storeql-20261010.dump; echo ciphertext-3 > /b/dumps/storeql-20261010.dump.age' >/dev/null
out="$(run)"; st=$?
[ $st -ne 0 ] && echo "$out" | grep -q "unencrypted" && ok "an unencrypted dump is refused" || bad "plaintext should be refused: $out"
remote_has dumps/storeql-20261010.dump.age && bad "something left although a plaintext dump was present" || ok "...and nothing leaves"
[ ! -f "$WORK/deploy/offsite.last" ] && ok "...and no heartbeat is written" || bad "heartbeat written on refusal"
docker run --rm -v "$VOL:/b" alpine:3 rm /b/dumps/storeql-20261010.dump >/dev/null

# configuration mistakes
out="$(OFFSITE_REMOTE= BACKUPS_VOLUME="$VOL" DEPLOY_DIR="$WORK/deploy" "$ROOT/scripts/offsite-backup.sh" 2>&1)"; st=$?
[ $st -ne 0 ] && ok "no destination is refused" || bad "no destination should fail"
out="$(OFFSITE_REMOTE="$WORK/remote" BACKUPS_VOLUME=storeql_no_such_volume DEPLOY_DIR="$WORK/deploy" "$ROOT/scripts/offsite-backup.sh" 2>&1)"; st=$?
[ $st -ne 0 ] && ok "a volume that does not exist is refused" || bad "missing volume should fail"

echo "offsite backup: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
