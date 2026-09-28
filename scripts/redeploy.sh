#!/usr/bin/env bash
#
# Clean rebuild + redeploy of the whole StoreQL stack: infra + all API services +
# the web UI (admin/storefront/POS) + Swagger UI (API docs), all in Docker.
#
#   ./scripts/redeploy.sh                 # rebuild jars + web bundle + all storeql images
#   ./scripts/redeploy.sh --wipe-data     # ALSO drop DB/Kafka volumes (fresh data)
#   ./scripts/redeploy.sh --no-build      # skip Maven + Flutter (reuse existing artifacts)
#   ./scripts/redeploy.sh --pull          # DANGER: docker system prune -a (wipes ALL local
#                                         #   images/containers, not just StoreQL), then pull
#                                         #   fresh prebuilt storeql-* images from ghcr.io/red2n
#                                         #   (all public, no login needed) instead of building
#   ./scripts/redeploy.sh --prune-all     # DANGER: docker system prune -a (removes ALL unused
#                                         #         images on this machine, not just StoreQL)
#
# Env overrides:
#   UI_API_BASE       gateway URL baked into the web build (default http://localhost:8090/api)
#   UI_HOST_PORT      host port for the web UI (default 8088)
#   SWAGGER_UI_PORT   host port for Swagger UI / API docs (default 8082)
#   STOREQL_TAG        image tag to pull with --pull (default latest)
#
# Default behaviour: down the stack, delete the built `storeql-*` images so they
# rebuild from scratch, rebuild jars + the web bundle, rebuild images, bring
# everything back up. Data volumes are KEPT unless you pass --wipe-data.
set -euo pipefail

WIPE_DATA=false
NO_BUILD=false
PRUNE_ALL=false
PULL=false
for arg in "$@"; do
  case "$arg" in
    --wipe-data) WIPE_DATA=true ;;
    --no-build)  NO_BUILD=true ;;
    --pull)      PULL=true ;;
    --prune-all) PRUNE_ALL=true ;;
    -h|--help)   awk 'NR>1{if(/^#/){sub(/^# ?/,"");print}else exit}' "$0"; exit 0 ;;
    *) echo "Unknown flag: $arg (use --help)"; exit 1 ;;
  esac
done
if $PULL && $NO_BUILD; then
  echo "--pull and --no-build are mutually exclusive."; exit 1
fi
# --pull implies a full local docker cleanup so nothing stale lingers before the
# fresh pull from ghcr.io/red2n (see step 2's NUKE confirmation).
$PULL && PRUNE_ALL=true

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/temurin-21-jdk-amd64}"

cyan() { printf '\033[1;36m%s\033[0m\n' "$*"; }
red()  { printf '\033[1;31m%s\033[0m\n' "$*"; }
# Source IP the kernel would use to reach the outside world — a reliable way to
# find "this machine's LAN IP" without depending on interface names (wlan0/eth0/
# enp*s* all differ by box). Doesn't send any traffic, just a routing-table lookup.
lan_ip() { ip -4 route get 1.1.1.1 2>/dev/null | awk '/src/{for(i=1;i<=NF;i++) if ($i=="src") print $(i+1)}'; }

# ── 0. Bootstrap .env + required secrets ─────────────────────────────────────
# On a brand-new checkout (fresh VPS, CI runner, etc.) there is no .env at all, and
# docker-compose.yml hard-refuses to start (`${VAR:?...}`) without STOREQL_JWT_SECRET,
# STOREQL_CONFIG_TOKEN, MQTT_PUBLISHER_PASSWORD, PLATFORM_ADMIN_PASSWORD and PLATFORM_ADMIN_TOTP_SECRET. Generate whichever of these are
# missing so `redeploy.sh` works standalone on a machine that has never seen this
# repo before — no manual `cp .env.example .env` + editing required.
ENV_FILE="$ROOT/.env"
if [ ! -f "$ENV_FILE" ] && [ -f "$ROOT/.env.example" ]; then
  cyan "No .env found — creating one from .env.example…"
  cp "$ROOT/.env.example" "$ENV_FILE"
fi
env_get() { grep -m1 "^${1}=" "$ENV_FILE" 2>/dev/null | cut -d= -f2-; }
env_set() {
  if grep -q "^${1}=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${1}=.*|${1}=${2}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"
  fi
}

# The seal on iam-svc's token signing keys + config-svc shared token: process-wide infra
# secrets, not tied to any DB row, so generate once on first sight and never rotate
# automatically — changing STOREQL_JWT_SECRET strands the signing keys stored under it, and
# STOREQL_CONFIG_TOKEN is what every service uses to authenticate to config-svc.
for secret in STOREQL_JWT_SECRET STOREQL_CONFIG_TOKEN; do
  val="$(env_get "$secret")"
  if [ -z "$val" ]; then
    val="$(openssl rand -base64 48)"
    env_set "$secret" "$val"
    cyan "Generated $secret (saved to .env)."
  fi
done

# The MQTT broker's publisher password (20.15): hex, because it is written into the CSV the
# broker bootstraps its one password user from.
if [ -z "$(env_get MQTT_PUBLISHER_PASSWORD)" ]; then
  env_set MQTT_PUBLISHER_PASSWORD "$(openssl rand -hex 24)"
  cyan "Generated MQTT_PUBLISHER_PASSWORD (saved to .env)."
fi

# Platform admin credential. Bootstrap only ever creates the admin once (the endpoint
# 409s afterwards), so we must NOT rotate this on every redeploy — that would desync
# .env from the password actually stored in the DB. We only (re)generate it when
# there's no admin left to desync from: first run (no .env / empty password) or
# --wipe-data (fresh DB).
PLATFORM_ADMIN_EMAIL="$(env_get PLATFORM_ADMIN_EMAIL)"
PLATFORM_ADMIN_EMAIL="${PLATFORM_ADMIN_EMAIL:-admin@storeql.dev}"
PLATFORM_ADMIN_PASSWORD="$(env_get PLATFORM_ADMIN_PASSWORD)"
if [ -z "$PLATFORM_ADMIN_PASSWORD" ] || $WIPE_DATA; then
  PLATFORM_ADMIN_PASSWORD="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-24)"
  env_set PLATFORM_ADMIN_PASSWORD "$PLATFORM_ADMIN_PASSWORD"
  env_set PLATFORM_ADMIN_EMAIL "$PLATFORM_ADMIN_EMAIL"
  cyan "Generated a fresh platform admin password (saved to .env)."
fi

# The platform administrator's authenticator secret (20.12): generated with the password and on
# the same terms — once, and again only when the data (and so the account) is wiped.
PLATFORM_ADMIN_TOTP_SECRET="$(env_get PLATFORM_ADMIN_TOTP_SECRET)"
if [ -z "$PLATFORM_ADMIN_TOTP_SECRET" ] || $WIPE_DATA; then
  PLATFORM_ADMIN_TOTP_SECRET="$(head -c 20 /dev/urandom | base32 | tr -d '=')"
  env_set PLATFORM_ADMIN_TOTP_SECRET "$PLATFORM_ADMIN_TOTP_SECRET"
  cyan "Generated PLATFORM_ADMIN_TOTP_SECRET (saved to .env) — add it to an authenticator app."
fi

# ── 1. Tear down ─────────────────────────────────────────────────────────────
if $WIPE_DATA; then
  red "Wiping data volumes (DB, Kafka) — the dev tenant + seed data will be LOST."
  docker compose down -v --remove-orphans
else
  cyan "Stopping stack (data volumes kept)…"
  docker compose down --remove-orphans
fi

# ── 2. Remove images ─────────────────────────────────────────────────────────
if $PRUNE_ALL; then
  red "About to 'docker system prune -af' — this removes ALL unused images on"
  red "this machine (every project, not just StoreQL)."
  $PULL && red "(--pull implies this cleanup, so the images pulled next are guaranteed fresh.)"
  read -r -p "Type 'NUKE' to continue: " confirm
  [ "$confirm" = "NUKE" ] || { echo "Aborted."; exit 1; }
  docker system prune -af
else
  cyan "Removing StoreQL built images (force fresh rebuild)…"
  imgs="$(docker images 'ghcr.io/red2n/storeql-*' -q | sort -u)"
  [ -n "$imgs" ] && docker rmi -f $imgs || cyan "  (no storeql images to remove)"
fi

if $PULL; then
  cyan "Skipping Maven + web build (--pull); using prebuilt images from ghcr.io/red2n."
else
  # ── 3. Rebuild jars (API) ──────────────────────────────────────────────────
  if $NO_BUILD; then
    cyan "Skipping Maven build (--no-build); reusing existing target/ jars."
  else
    cyan "Building all modules (mvn clean install -DskipTests)…"
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1
    JAVA_HOME="$JAVA_HOME" mvn clean install -DskipTests -q
  fi

  # ── 3b. Rebuild the web UI bundle ────────────────────────────────────────
  UI_API_BASE="${UI_API_BASE:-http://localhost:8090/api}"
  # The web app's Content-Security-Policy allows the gateway origin the bundle calls (compose
  # reads UI_API_ORIGIN); a relative /api build calls its own origin and needs none.
  export UI_API_ORIGIN="${UI_API_BASE%/api}"
  case "$UI_API_ORIGIN" in /*) UI_API_ORIGIN="" ;; esac
  if $NO_BUILD; then
    cyan "Skipping web build (--no-build); reusing frontends/storeql-app/build/web."
  elif command -v flutter >/dev/null 2>&1; then
    cyan "Building web UI (flutter build web --release, API base: $UI_API_BASE)…"
    (
      cd frontends/storeql-app
      flutter pub get
      flutter build web --release --no-web-resources-cdn --dart-define=STOREQL_API_BASE="$UI_API_BASE"
    )
  else
    # No local Flutter SDK — a fresh machine shouldn't need one installed by hand.
    # Docker is already a hard requirement for this script (docker compose below),
    # so build the bundle in a throwaway Flutter container instead of failing.
    # The container step lives in scripts/lib/flutter-in-docker.sh, proved by
    # scripts/flutter-docker-selftest.sh (it once failed on git's ownership check of the SDK).
    # shellcheck source=lib/flutter-in-docker.sh
    source "$ROOT/scripts/lib/flutter-in-docker.sh"
    cyan "flutter not found on PATH — building web UI in a Docker container ($FLUTTER_IMAGE), no local install needed…"
    flutter_in_docker "$ROOT/frontends/storeql-app" "$ROOT/.cache/flutter-pub-cache" \
      "flutter pub get && flutter build web --release --no-web-resources-cdn --dart-define=STOREQL_API_BASE='$UI_API_BASE'"
  fi
fi

# ── 4. Fetch images + bring up ───────────────────────────────────────────────
if $PULL; then
  cyan "Pulling all stack images (storeql-* tag: ${STOREQL_TAG:-latest} from ghcr.io/red2n, plus infra)…"
  docker compose pull
else
  cyan "Building images…"
  docker compose build
fi
cyan "Starting stack (infra → platform → services → bootstrap)…"
docker compose up -d

# ── 5. Wait for health ───────────────────────────────────────────────────────
cyan "Waiting for services to become healthy…"
for _ in $(seq 1 60); do
  total=$(docker compose ps --services | wc -l)
  healthy=$(docker compose ps --format '{{.Status}}' | grep -c healthy || true)
  unhealthy=$(docker compose ps --format '{{.Status}}' | grep -c unhealthy || true)
  printf '\r  healthy=%s unhealthy=%s (of ~%s)   ' "$healthy" "$unhealthy" "$total"
  [ "$unhealthy" = "0" ] && [ "$healthy" -ge 20 ] && break
  sleep 5
done
echo

# ── 6. Report ────────────────────────────────────────────────────────────────
cyan "Final status:"
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
GW=$(docker compose port gateway 8080 2>/dev/null | cut -d: -f2 || echo 8090)
if curl -fsS -o /dev/null --max-time 5 "http://localhost:${GW:-8090}/health/live"; then
  cyan "✓ Gateway (API) healthy on http://localhost:${GW:-8090}"
else
  red "! Gateway not answering yet on port ${GW:-8090} — give it a few more seconds."
fi
UI=$(docker compose port storeql-app 8080 2>/dev/null | cut -d: -f2 || echo "${UI_HOST_PORT:-8088}")
if curl -fsS -o /dev/null --max-time 5 "http://localhost:${UI:-8088}/healthz"; then
  cyan "✓ Web UI on http://localhost:${UI:-8088}"
else
  red "! Web UI not answering yet on port ${UI:-8088} — give it a few more seconds."
fi
SWAGGER=$(docker compose port swagger-ui 8080 2>/dev/null | cut -d: -f2 || echo "${SWAGGER_UI_PORT:-8082}")
if curl -fsS -o /dev/null --max-time 5 "http://localhost:${SWAGGER:-8082}/"; then
  cyan "✓ Swagger UI (all service API docs) on http://localhost:${SWAGGER:-8082}"
else
  red "! Swagger UI not answering yet on port ${SWAGGER:-8082} — give it a few more seconds."
fi
$WIPE_DATA && red "Data was wiped — re-run onboarding/seed (platform admin is recreated by the bootstrap container)."

# ── 7. Testing cheat sheet ───────────────────────────────────────────────────
# All login flows share ONE web bundle (storeql-app) on $UI; go_router picks the
# screen by path/role — there is no separate "tenant" or "store" login, staff
# (owner/manager/cashier) all sign in at the same /login and land in /admin or
# /pos depending on role. POS additionally needs a clock-in (store pick) after
# signing in. Storefront is unauthenticated/guest, tenant comes from the URL.
cport() { docker compose port "$1" "$2" 2>/dev/null | cut -d: -f2; }
KAFKA_UI=$(cport kafka-ui 8080); KAFKA_UI=${KAFKA_UI:-8081}
PGADMIN=$(cport pgadmin 80); PGADMIN=${PGADMIN:-5555}
GRAFANA=$(cport grafana 3000); GRAFANA=${GRAFANA:-3100}
CONSUL=$(cport consul 8500); CONSUL=${CONSUL:-8500}
PROM=$(cport prometheus 9090); PROM=${PROM:-9090}
ZIPKIN_P=$(cport zipkin 9411); ZIPKIN_P=${ZIPKIN_P:-9411}
PG=$(cport postgres 5432); PG=${PG:-5432}
REDIS_P=$(cport redis 6379); REDIS_P=${REDIS_P:-6379}

cyan "Testing cheat sheet:"
echo "  Platform admin login     http://localhost:${UI:-8088}/#/platform/login      (${PLATFORM_ADMIN_EMAIL} / ${PLATFORM_ADMIN_PASSWORD})"
echo "    its authenticator      otpauth://totp/StoreQL:${PLATFORM_ADMIN_EMAIL}?secret=${PLATFORM_ADMIN_TOTP_SECRET}&issuer=StoreQL   (paste into an authenticator app, or: oathtool --totp -b ${PLATFORM_ADMIN_TOTP_SECRET})"
echo "  Admin / tenant login     http://localhost:${UI:-8088}/#/login               (owner/manager — same screen, lands on /admin/dashboard)"
echo "  Store staff / POS login  http://localhost:${UI:-8088}/#/login               (cashier/manager — same screen, lands on /pos, then clock in to a store)"
echo "  Storefront (guest)       http://localhost:${UI:-8088}/?tenant=<tenantId>#/store/products   (online shop, no login)"
echo
echo "  Gateway (API)            http://localhost:${GW:-8090}/api"
echo "  Swagger UI (API docs)    http://localhost:${SWAGGER:-8082}"
echo "  Kafka UI                 http://localhost:${KAFKA_UI}"
echo "  pgAdmin                  http://localhost:${PGADMIN}"
echo "  Grafana                  http://localhost:${GRAFANA}"
echo "  Consul UI                http://localhost:${CONSUL}"
echo "  Prometheus               http://localhost:${PROM}"
echo "  Zipkin                   http://localhost:${ZIPKIN_P}"
echo "  Postgres (psql/SQL)      localhost:${PG}"
echo "  Redis (redis-cli)        localhost:${REDIS_P}"

LAN_IP="$(lan_ip)"
if [ -n "$LAN_IP" ]; then
  echo
  cyan "Over Wi-Fi / LAN (phone, tablet, another PC on the same network):"
  echo "  Web UI (all logins)      http://${LAN_IP}:${UI:-8088}/#/login"
  echo "  Gateway (API)            http://${LAN_IP}:${GW:-8090}/api"
  echo "  (only these two ports are LAN-reachable — everything else above is 127.0.0.1-only)"
  BAKED_BASE="${UI_API_BASE:-http://localhost:8090/api}"
  case "$BAKED_BASE" in
    *localhost*|*127.0.0.1*)
      red "  ! Web UI was built with API base '${BAKED_BASE}' — a phone can't resolve"
      red "    'localhost' to this machine, so it won't be able to log in over Wi-Fi."
      red "    Rebuild with: UI_API_BASE=http://${LAN_IP}:${GW:-8090}/api ./scripts/redeploy.sh"
      ;;
  esac
  case ",${GATEWAY_CORS_ALLOWED_ORIGINS:-}," in
    *",http://${LAN_IP}:${UI:-8088},"*) ;;
    *)
      red "  ! The gateway only accepts browser calls from the origins in GATEWAY_CORS_ALLOWED_ORIGINS."
      red "    For Wi-Fi testing set GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:${UI:-8088},http://${LAN_IP}:${UI:-8088} in .env"
      ;;
  esac
else
  red "Could not detect a LAN IP (no default route) — skipping Wi-Fi connection info."
fi
cyan "Done."
