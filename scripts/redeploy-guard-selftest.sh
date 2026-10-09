#!/usr/bin/env bash
#
# Proves the data-wipe guard in scripts/redeploy.sh without touching Docker: with STOREQL_ENV anything
# but dev or ci, `--wipe-data` is refused with a non-zero exit, says why, and changes nothing (the
# .env is byte for byte the same). Run in CI beside the other script self-tests.
#   scripts/redeploy-guard-selftest.sh     exit 0 only when every check passed
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/scripts"
cp "$ROOT/scripts/redeploy.sh" "$WORK/scripts/"
passed=0
failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }

try() { # try <label> <STOREQL_ENV value or -> <env file line or -> expect-refused(yes|no)
  local label="$1" env="$2" line="$3" want="$4" out status before after
  printf 'STOREQL_JWT_SECRET=x\n' > "$WORK/.env"
  [ "$line" != "-" ] && printf '%s\n' "$line" >> "$WORK/.env"
  before="$(sha256sum "$WORK/.env" | cut -d' ' -f1)"
  if [ "$env" = "-" ]; then
    out="$(env -u STOREQL_ENV JAVA_HOME=/nonexistent "$WORK/scripts/redeploy.sh" --wipe-data --no-build 2>&1)"; status=$?
  else
    out="$(STOREQL_ENV="$env" JAVA_HOME=/nonexistent "$WORK/scripts/redeploy.sh" --wipe-data --no-build 2>&1)"; status=$?
  fi
  after="$(sha256sum "$WORK/.env" | cut -d' ' -f1)"
  if [ "$want" = "yes" ]; then
    if [ "$status" -ne 0 ] && grep -q -- "--wipe-data refused" <<<"$out" && [ "$before" = "$after" ]; then ok "$label"; else bad "$label (status $status)"; fi
  else
    # Allowed past the guard: it must not say refused (it goes on to need docker, which this test never reaches).
    if ! grep -q -- "--wipe-data refused" <<<"$out"; then ok "$label"; else bad "$label"; fi
  fi
}

try "no STOREQL_ENV anywhere is refused" - - yes
try "prod in the environment is refused" prod - yes
try "prod in .env is refused" - "STOREQL_ENV=prod" yes
try "a typo is not dev" - "STOREQL_ENV=develop" yes
try "dev in the environment is allowed" dev - no
try "dev in .env is allowed" - "STOREQL_ENV=dev" no
try "ci is allowed" ci - no
try "the environment wins over .env: prod over dev" prod "STOREQL_ENV=dev" yes

echo "redeploy guard: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
