#!/usr/bin/env bash
#
# Deploy a named release to a pilot or production host that runs the compose stack
# (intent/forward-only-migrations.md; docs/RELEASE-PROCESS.md "Deploying a release").
#
#   scripts/deploy-release.sh 0.1.1               upgrade this host to release 0.1.1
#   scripts/deploy-release.sh --check 0.1.1       only the checks that need no downtime (nothing is touched)
#   scripts/deploy-release.sh --rollback          back to the release this host ran before the last upgrade
#
# What it does, in this order, and stops at the first thing that is wrong:
#   1. the version is MAJOR.MINOR.PATCH (never `latest`), this host says it is prod (STOREQL_ENV), and
#      the volumes that hold the data exist;
#   2. the release's images are pulled (a failed pull changes nothing);
#   3. a backup is taken and VERIFIED (checksum, decryption, readable end to end) -- no verified backup,
#      no upgrade; the first deploy onto an empty host has nothing to back up and says so;
#   4. the edge (Caddy, the gateway, the web app) is stopped, so no customer is served half-migrated;
#   5. every other service is started on the new images; in strict mode each one migrates its own schema
#      first and is not ready until it has (a migration that fails stops that service, and this script);
#   6. when all are healthy the edge is started and a smoke request made; only then is the release
#      recorded as the one this host runs.
# A failure after step 4 never undoes anything by itself: it prints the exact rollback line and the backup
# to restore if the release changed the schema destructively (a `-- storeql:contract` migration is the
# one kind an older image cannot run on), and leaves the edge stopped.
#
# Env: STOREQL_ENV (prod; the drill uses ci with STOREQL_DEPLOY_ALLOW_NONPROD=1), COMPOSE_FILES,
#      HEALTH_WAIT_SECONDS (300), SMOKE_URL (http://localhost:8090/health), DEPLOY_DIR (.storeql-deploy).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
COMPOSE_FILES="${COMPOSE_FILES:--f docker-compose.yml -f docker-compose.prod.yml}"
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-300}"
SMOKE_URL="${SMOKE_URL:-http://localhost:8090/health}"
DEPLOY_DIR="${DEPLOY_DIR:-.storeql-deploy}"
EDGE_SERVICES="caddy gateway storeql-app"
DATA_VOLUMES="${DATA_VOLUMES:-storeql_pgdata storeql_backups}"

red() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
say() { printf '\033[1;36m%s\033[0m\n' "$*"; }
die() { red "$*"; exit 1; }
# shellcheck disable=SC2086
dc() { STOREQL_TAG="$TARGET" docker compose $COMPOSE_FILES "$@"; }

MODE=deploy
TARGET=""
for a in "$@"; do
  case "$a" in
    --check) MODE=check ;;
    --rollback) MODE=rollback ;;
    -h|--help) awk 'NR>1{if(/^#/){sub(/^# ?/,"");print}else exit}' "$0"; exit 0 ;;
    -*) die "unknown flag: $a" ;;
    *) TARGET="$a" ;;
  esac
done

env_file_value() { grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2- || true; }
HOST_ENV="${STOREQL_ENV:-$(env_file_value STOREQL_ENV)}"
CURRENT="$(cat "$DEPLOY_DIR/release" 2>/dev/null || echo none)"
PREVIOUS="$(cat "$DEPLOY_DIR/previous" 2>/dev/null || echo none)"

if [ "$MODE" = rollback ]; then
  [ "$PREVIOUS" != none ] || die "nothing to roll back to: this host has no recorded previous release"
  TARGET="$PREVIOUS"
fi
[ -n "$TARGET" ] || die "name the release: scripts/deploy-release.sh 0.1.1"
[[ "$TARGET" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "'$TARGET' is not a release (MAJOR.MINOR.PATCH, for example 0.1.1); a host never runs latest"
if [ "$HOST_ENV" != "prod" ] && [ "${STOREQL_DEPLOY_ALLOW_NONPROD:-}" != "1" ]; then
  die "STOREQL_ENV is '${HOST_ENV:-unset}': a release is deployed only to a host that says it is prod (STOREQL_ENV=prod in .env)"
fi
if [ "$MODE" = deploy ] && [ "$CURRENT" = "$TARGET" ]; then
  say "this host already runs $TARGET; nothing to do"
  exit 0
fi

say "release $TARGET (this host runs $CURRENT)"
for v in $DATA_VOLUMES; do
  docker volume inspect "$v" >/dev/null 2>&1 || die "the data volume $v does not exist; create it once with: docker volume create $v"
done

say "pulling the images of $TARGET"
dc pull --quiet >/dev/null 2>&1 || die "the images of $TARGET could not be pulled; nothing was changed"
if [ "$MODE" = check ]; then
  say "checks passed: $TARGET can be deployed (nothing was touched)"
  exit 0
fi

mkdir -p "$DEPLOY_DIR"
ARTEFACT=none
if [ "$CURRENT" = none ] || [ "${SKIP_BACKUP:-}" = 1 ] || [ "$MODE" = rollback ]; then
  say "no backup taken ($([ "$CURRENT" = none ] && echo 'first deploy onto an empty host' || echo 'a rollback uses the backup the upgrade took'))"
else
  say "taking and verifying a backup"
  ARTEFACT="$(dc exec -T backup storeql-backup now 2>/dev/null | tail -1 | tr -d '\r')"
  [ -n "$ARTEFACT" ] || die "the backup could not be taken; nothing was changed"
  dc exec -T backup storeql-backup verify "$ARTEFACT" >/dev/null 2>&1 || die "the backup $ARTEFACT did not verify; nothing was changed"
  say "backup $ARTEFACT verified"
fi
printf '{"from":"%s","to":"%s","backup":"%s","startedAt":"%s"}\n' "$CURRENT" "$TARGET" "$ARTEFACT" "$(date -u +%FT%TZ)" > "$DEPLOY_DIR/pending.json"

fail_after_stop() {
  red "$1"
  red "The edge is stopped and nothing was undone. To go back to $CURRENT:  scripts/deploy-release.sh $CURRENT   (the older images)"
  if [ "$ARTEFACT" != none ]; then
    red "If $TARGET changed the schema destructively (a '-- storeql:contract' migration), the older images cannot run on it:"
    red "restore the backup first -- storeql-backup restore $ARTEFACT (docs/BACKUP-AND-RESTORE.md)."
  fi
  exit 1
}

wait_healthy() { # wait_healthy <service...>: running, and healthy where the service has a check
  local deadline=$((SECONDS + HEALTH_WAIT_SECONDS)) bad
  while :; do
    bad="$(dc ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null | awk -v want=" $* " '
      BEGIN { n = split(want, w, " "); for (i = 1; i <= n; i++) if (w[i] != "") need[w[i]] = 1 }
      ($1 in need) && ($2 != "running" || ($3 != "" && $3 != "healthy")) { print $1 }')"
    [ -z "$bad" ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && { echo "$bad"; return 1; }
    sleep "${HEALTH_POLL_SECONDS:-3}"
  done
}

say "stopping the edge"
# shellcheck disable=SC2086
dc stop $EDGE_SERVICES >/dev/null 2>&1 || true

say "starting the services on $TARGET (each migrates its own schema first)"
INNER="$(dc config --services 2>/dev/null | grep -vxE "$(echo "$EDGE_SERVICES" | tr ' ' '|')" | tr '\n' ' ')"
[ -n "$INNER" ] || fail_after_stop "the compose file lists no services"
# shellcheck disable=SC2086
dc up -d --remove-orphans $INNER >/dev/null 2>&1 || fail_after_stop "the services could not be started on $TARGET"
bad="$(wait_healthy $INNER)" || fail_after_stop "not healthy on $TARGET after ${HEALTH_WAIT_SECONDS}s (a failed migration stops its service): $(echo "$bad" | tr '\n' ' ')"

say "starting the edge"
# shellcheck disable=SC2086
dc up -d $EDGE_SERVICES >/dev/null 2>&1 || fail_after_stop "the edge could not be started"
bad="$(wait_healthy $EDGE_SERVICES)" || fail_after_stop "the edge is not healthy: $(echo "$bad" | tr '\n' ' ')"
curl -fsS --max-time 10 -o /dev/null "$SMOKE_URL" || fail_after_stop "the smoke request to $SMOKE_URL failed"

[ "$CURRENT" != none ] && printf '%s' "$CURRENT" > "$DEPLOY_DIR/previous"
printf '%s' "$TARGET" > "$DEPLOY_DIR/release"
{ cat "$DEPLOY_DIR/pending.json"; } >> "$DEPLOY_DIR/history.jsonl"
rm -f "$DEPLOY_DIR/pending.json"
say "$TARGET is running"
