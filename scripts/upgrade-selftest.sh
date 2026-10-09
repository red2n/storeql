#!/usr/bin/env bash
#
# Proves, on a throwaway Postgres with the same Flyway the services run, what the migration policy
# promises (intent/forward-only-migrations.md) -- with fixture release folders, so it runs before any tag
# exists and after:
#   * release N+1 migrates a database made by release N, and the data survives;
#   * release N's code still runs on the N+1 schema when the change was additive (so a rollback is just
#     the previous image), and release N's migrations still validate against it;
#   * a destructive (`storeql:contract`) release is NOT rollback-safe: the older code breaks on it, and the
#     classifier below says so -- which is why such a release rolls back by restoring the backup;
#   * a published migration that was edited is refused by Flyway (the checksum), which is why a published
#     file is frozen.
#   scripts/upgrade-selftest.sh     exit 0 only when every check passed
# Needs docker (postgres:16-alpine) and a JDK (21+); the Flyway jars come from mvn's offline repository.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
NAME="storeql-upgrade-selftest-$$"
PASS="selftest"
SCHEMA="svc"
passed=0
failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1; rm -rf "$WORK"; }
trap cleanup EXIT

# The classifier a deploy reads: a release whose migrations carry a contract marker is not rollback-safe.
rollback_safe() { ! grep -rqE '^--[[:space:]]*storeql:contract' "$1" 2>/dev/null; }

CP_FILE="$WORK/cp.txt"
if [ -n "${FLYWAY_CLASSPATH:-}" ]; then
  echo "$FLYWAY_CLASSPATH" > "$CP_FILE"
else
  (cd "$ROOT" && mvn -o -q -pl shared/common-service dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null 2>&1) \
    || { echo "could not build the Flyway classpath (mvn -o on shared/common-service)"; exit 2; }
fi
CP="$(cat "$CP_FILE")"

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD="$PASS" -p 127.0.0.1::5432 postgres:16-alpine >/dev/null || { echo "could not start postgres"; exit 2; }
PORT=""
for _ in $(seq 1 60); do
  PORT="$(docker port "$NAME" 5432/tcp 2>/dev/null | head -1 | sed 's/.*://')"
  [ -n "$PORT" ] && docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done
URL="jdbc:postgresql://127.0.0.1:${PORT}/postgres"
psql_q() { docker exec -i "$NAME" psql -U postgres -tA -v ON_ERROR_STOP=1 -c "$1" 2>&1; }
new_db() { psql_q "DROP DATABASE IF EXISTS \"$1\"" >/dev/null; psql_q "CREATE DATABASE \"$1\"" >/dev/null; }
step() { # step <db> <dir> migrate|validate
  java -cp "$CP" "$ROOT/scripts/lib/FlywayStep.java" "jdbc:postgresql://127.0.0.1:${PORT}/$1" postgres "$PASS" "$SCHEMA" "$2" "$3" 2>/dev/null
}
q() { docker exec -i "$NAME" psql -U postgres -d "$1" -tA -v ON_ERROR_STOP=1 -c "$2" 2>&1; }

# Fixture releases of one service's migration folder.
mk() { mkdir -p "$WORK/$1"; }
mk n;       printf 'CREATE TABLE svc.t (id INT PRIMARY KEY, name TEXT NOT NULL);\nINSERT INTO svc.t VALUES (1, %s);\n' "'before'" > "$WORK/n/V1__create_t.sql"
mk add;     cp "$WORK/n/V1__create_t.sql" "$WORK/add/"; printf 'ALTER TABLE svc.t ADD COLUMN note TEXT;\nCREATE TABLE svc.u (id INT PRIMARY KEY);\n' > "$WORK/add/V2__add_note.sql"
mk contract; cp "$WORK/add/"* "$WORK/contract/"; printf -- '-- storeql:contract after=v0.1.0 reason=name is replaced by note\nALTER TABLE svc.t DROP COLUMN name;\n' > "$WORK/contract/V3__drop_name.sql"
mk edited;  printf 'CREATE TABLE svc.t (id INT PRIMARY KEY, name TEXT NOT NULL, extra TEXT);\nINSERT INTO svc.t VALUES (1, %s);\n' "'before'" > "$WORK/edited/V1__create_t.sql"

# ── additive: N then N+1 ─────────────────────────────────────────────────────────────────────────
new_db add_db
out="$(step add_db "$WORK/n" migrate)"; [ $? -eq 0 ] && ok "release N migrates an empty database" || bad "release N migrates an empty database: $out"
q add_db "INSERT INTO svc.t VALUES (2, 'second')" >/dev/null
out="$(step add_db "$WORK/add" migrate)"; [ $? -eq 0 ] && ok "release N+1 migrates the database release N made" || bad "release N+1 migrates N's database: $out"
[ "$(q add_db 'SELECT count(*) FROM svc.t')" = "2" ] && ok "...and every row survives" || bad "rows survive the upgrade"
[ "$(q add_db "SELECT name FROM svc.t WHERE id = 1")" = "before" ] && ok "...unchanged" || bad "rows unchanged"
out="$(q add_db "INSERT INTO svc.t (id, name) VALUES (3, 'written by release N code')")"
echo "$out" | grep -q "INSERT 0 1" && ok "release N's code still writes on the N+1 schema (a rollback is the previous image)" || bad "N code on N+1 schema: $out"
out="$(step add_db "$WORK/n" validate)"; [ $? -eq 0 ] && ok "...and release N's own migrations still validate against it" || bad "N validates on N+1: $out"
out="$(step add_db "$WORK/add" migrate)"; [ $? -eq 0 ] && ok "migrating again is a no-op" || bad "re-migrate: $out"
rollback_safe "$WORK/add" && ok "an additive release is classified rollback-safe" || bad "additive release classified unsafe"

# ── contract: not rollback-safe ──────────────────────────────────────────────────────────────────
new_db con_db
step con_db "$WORK/n" migrate >/dev/null
out="$(step con_db "$WORK/contract" migrate)"; [ $? -eq 0 ] && ok "a contract release migrates" || bad "contract release migrates: $out"
out="$(q con_db "INSERT INTO svc.t (id, name) VALUES (9, 'written by release N code')")"
echo "$out" | grep -qi "error" && ok "...and release N's code breaks on it" || bad "N code should break on a contract release: $out"
rollback_safe "$WORK/contract" && bad "a contract release was classified rollback-safe" || ok "...which the classifier calls NOT rollback-safe (restore the backup)"

# ── why a published file is frozen ───────────────────────────────────────────────────────────────
new_db edit_db
step edit_db "$WORK/n" migrate >/dev/null
out="$(step edit_db "$WORK/edited" migrate)"
[ $? -ne 0 ] && echo "$out" | grep -qi "checksum\|validate" && ok "an edited published migration is refused by Flyway (its checksum)" || bad "edited migration should be refused: $out"

echo "upgrade: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
