#!/usr/bin/env bash
#
# Proves scripts/deploy-release.sh against a fake `docker` and `curl`: the refusals (no release named,
# `latest`, a host that is not prod, a missing data volume), that nothing is touched when a pull, a
# backup or its verification fails, that the verified backup is taken BEFORE the edge is stopped and the
# edge stopped BEFORE the services are replaced, that an unhealthy service leaves the edge stopped and
# prints the rollback line, and that the release is recorded only when everything is up and answering.
#   scripts/deploy-release-selftest.sh     exit 0 only when every check passed
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
LOG="$WORK/calls.log"

cat > "$WORK/bin/docker" <<'FAKE'
#!/usr/bin/env bash
all="$*"
echo "docker tag=${STOREQL_TAG:-unset} $all" >> "$FAKE_LOG"
case "$all" in
  "volume inspect"*) [ -z "${FAKE_NO_VOLUME:-}" ] ;;
  *" pull "*|*" pull") [ -z "${FAKE_PULL_FAIL:-}" ] ;;
  *"storeql-backup now"*) [ -z "${FAKE_BACKUP_FAIL:-}" ] && echo "storeql-2026-10-09.dump.age" ;;
  *"storeql-backup verify"*) [ -z "${FAKE_VERIFY_FAIL:-}" ] ;;
  *" config --services"*) printf '%s\n' postgres iam-svc tenant-svc caddy gateway storeql-app ;;
  *" ps --format"*)
    for s in postgres iam-svc tenant-svc caddy gateway storeql-app; do
      if [ "$s" = "${FAKE_UNHEALTHY:-}" ]; then echo "$s restarting unhealthy"; else echo "$s running healthy"; fi
    done ;;
  *) true ;;
esac
FAKE
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE_LOG"
[ -z "${FAKE_SMOKE_FAIL:-}" ]
FAKE
chmod +x "$WORK/bin/docker" "$WORK/bin/curl"

passed=0
failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }

# run <env assignments...> -- <script args...>: a fresh deploy dir unless RELEASE is set; sets $status $out
run() {
  local envs=()
  while [ "$1" != "--" ]; do envs+=("$1"); shift; done
  shift
  : > "$LOG"
  out="$(env PATH="$WORK/bin:$PATH" FAKE_LOG="$LOG" STOREQL_ENV=prod DEPLOY_DIR="$WORK/deploy" HEALTH_WAIT_SECONDS=1 HEALTH_POLL_SECONDS=0 "${envs[@]}" "$ROOT/scripts/deploy-release.sh" "$@" 2>&1)"
  status=$?
}
fresh() { rm -rf "$WORK/deploy"; mkdir -p "$WORK/deploy"; [ -n "${1:-}" ] && printf '%s' "$1" > "$WORK/deploy/release"; return 0; }
called() { grep -q -- "$1" "$LOG"; }
line_of() { grep -n -- "$1" "$LOG" | head -1 | cut -d: -f1; }
expect() { # expect <label> <condition via function...>
  local label="$1"; shift
  if "$@"; then ok "$label"; else bad "$label"; fi
}
refused() { [ "$status" -ne 0 ]; }
untouched() { ! called "docker compose"; }
released() { [ "$(cat "$WORK/deploy/release" 2>/dev/null)" = "$1" ]; }

fresh; run -- ;                        expect "no release named is refused" refused
fresh; run -- latest;                  expect "latest is refused" refused
fresh; run -- 1.2;                     expect "a two-part version is refused" refused
fresh; run -- v0.1.0;                  expect "a v-prefixed tag is refused (the image tag has none)" refused
fresh; run STOREQL_ENV=dev -- 0.1.0;   expect "a host that is not prod is refused" refused
fresh; run STOREQL_ENV=dev -- 0.1.0;   expect "...and touches no compose" untouched
fresh; run FAKE_NO_VOLUME=1 -- 0.1.0;  expect "a missing data volume is refused" refused
fresh; run FAKE_NO_VOLUME=1 -- 0.1.0;  expect "...before anything is pulled" untouched

fresh 0.1.0; run FAKE_PULL_FAIL=1 -- 0.1.1
expect "a failed pull is refused" refused
expect "...and no backup is taken, nothing stopped or started" bash -c "! grep -qE 'storeql-backup| stop | up ' '$LOG'"
expect "...and the release is still the old one" released 0.1.0

fresh 0.1.0; run -- --check 0.1.1
expect "--check passes when it can" test "$status" -eq 0
expect "--check pulls but touches nothing else" bash -c "grep -q ' pull' '$LOG' && ! grep -qE 'storeql-backup| stop | up ' '$LOG'"
expect "--check leaves the recorded release alone" released 0.1.0

fresh; run -- 0.1.0
expect "the first deploy onto an empty host succeeds" test "$status" -eq 0
expect "...takes no backup (there is nothing to back up)" bash -c "! grep -q 'storeql-backup' '$LOG'"
expect "...records the release" released 0.1.0

fresh 0.1.0; run -- 0.1.1
expect "an upgrade succeeds" test "$status" -eq 0
b=$(line_of "storeql-backup now"); v=$(line_of "storeql-backup verify"); s=$(line_of " stop "); u=$(line_of " up -d --remove-orphans")
expect "the backup is taken, then verified, before the edge is stopped" test -n "$b" -a -n "$v" -a -n "$s" -a "$b" -lt "$v" -a "$v" -lt "$s"
expect "the edge is stopped before the services are replaced" test "$s" -lt "$u"
expect "the services are started on the new tag, and only that tag" bash -c "grep 'up -d' '$LOG' | grep -q 'tag=0.1.1 ' && ! grep 'compose' '$LOG' | grep -qE 'tag=(0.1.0|latest|unset) '"
expect "the edge is not among the services started first" bash -c "! grep 'up -d --remove-orphans' '$LOG' | grep -qE 'gateway|caddy'"
expect "the release is recorded, and the old one kept for a rollback" bash -c "[ \"\$(cat '$WORK/deploy/release')\" = 0.1.1 ] && [ \"\$(cat '$WORK/deploy/previous')\" = 0.1.0 ]"
expect "the deploy is logged with its backup" bash -c "grep -q 'storeql-2026-10-09.dump.age' '$WORK/deploy/history.jsonl'"

fresh 0.1.0; run FAKE_BACKUP_FAIL=1 -- 0.1.1
expect "no backup, no upgrade" refused
expect "...and nothing is stopped or replaced" bash -c "! grep -qE ' stop | up ' '$LOG'"
fresh 0.1.0; run FAKE_VERIFY_FAIL=1 -- 0.1.1
expect "a backup that does not verify is no backup" refused
expect "...and nothing is stopped or replaced" bash -c "! grep -qE ' stop | up ' '$LOG'"
expect "...and the release is still the old one" released 0.1.0

fresh 0.1.0; run FAKE_UNHEALTHY=iam-svc -- 0.1.1
expect "a service that does not come up fails the deploy" refused
expect "...says which" bash -c "grep -q 'iam-svc' <<<\"\$1\"" _ "$out"
expect "...prints the line that goes back" bash -c "grep -q 'scripts/deploy-release.sh 0.1.0' <<<\"\$1\"" _ "$out"
expect "...names the backup to restore if the schema changed destructively" bash -c "grep -q 'storeql-2026-10-09.dump.age' <<<\"\$1\"" _ "$out"
expect "...never starts the edge" bash -c "! grep -qE 'up -d (caddy|gateway)' '$LOG'"
expect "...and does not record the release" released 0.1.0

fresh 0.1.0; run FAKE_SMOKE_FAIL=1 -- 0.1.1
expect "a smoke request that fails fails the deploy" refused
expect "...and does not record the release" released 0.1.0

fresh 0.1.1; run -- 0.1.1
expect "deploying what is already running does nothing" test "$status" -eq 0
expect "...touching no compose" untouched

fresh 0.1.0; run -- 0.1.1; run -- --rollback
expect "a rollback goes back to the previous release" test "$status" -eq 0
expect "...and records it" released 0.1.0
expect "...taking no new backup (the upgrade's is the one to restore)" bash -c "! grep -q 'storeql-backup' '$LOG'"
fresh; run -- --rollback
expect "a host with no previous release has nothing to roll back to" refused

echo "deploy-release: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
