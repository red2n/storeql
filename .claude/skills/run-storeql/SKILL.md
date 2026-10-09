---
name: run-storeql
description: Build, start, and drive the whole StoreQL stack (gateway, 14 microservices, Postgres/Kafka/Consul, and the Flutter web UI) via Docker Compose. Use when asked to run StoreQL, start the stack, rebuild a service, log in as platform admin, screenshot the web UI, or click through the storefront/POS/admin/platform console.
---

StoreQL is a Docker Compose stack — one Java backend (gateway + 14 Helidon MP
services) plus one Flutter web SPA (`storeql-web`), all networked together
with Postgres/Kafka/Consul/Redis. There is no standalone way to run a single
service; nothing outside `docker compose` is meaningful on its own. The web
UI is **canvas-rendered** (Flutter CanvasKit) — no real DOM elements to
click by CSS selector — so it's driven with a small hand-rolled Playwright
driver, `.claude/skills/run-storeql/driver.mjs`, using raw coordinate clicks
against screenshots instead of `chromium-cli` (not installed in this
container; this driver is the documented fallback for that case).

All paths below are relative to the repo root (`storeql/`).

## Prerequisites

Docker + Docker Compose (`docker compose`, not `docker-compose`). JDK 25 and
Maven are only needed if you're rebuilding a backend service's jar — the
stack as shipped already has built images.

```bash
docker --version && docker compose version
```

For the browser driver: Node.js and a Chrome/Chromium binary. This
container has both already:

```bash
node --version           # v24.11.1 here
ls /usr/bin/google-chrome # used directly — playwright-core does NOT bundle a browser
```

Optional, for iterative driving (see Run section) — `tmux`. Needs real
`sudo` (a plain `sudo apt-get install -y tmux` fails with "a terminal is
required to read the password" if you don't have passwordless sudo; ask the
user to run it, or use the named-pipe fallback documented below instead):

```bash
sudo apt-get install -y tmux
```

## Setup

```bash
cp .env.example .env   # dev defaults work out of the box; real secrets go here for anything beyond local dev
cd .claude/skills/run-storeql && npm install && cd ../../..   # installs playwright-core for the driver only
```

`.env` has the platform-admin bootstrap login the driver's `login` command
uses:

```bash
grep PLATFORM_ADMIN .env
# PLATFORM_ADMIN_EMAIL=admin@storeql.dev
# PLATFORM_ADMIN_PASSWORD=<random, generated per-checkout — read it from .env, don't hardcode it elsewhere>
```

## Build

```bash
export JAVA_HOME=/path/to/your/jdk-25   # only if rebuilding backend jars (or put its bin/ first on the PATH)
mvn clean install -DskipTests                        # backend: all 14 services + gateway + config
(cd frontends/storeql-app && flutter build web --release --no-web-resources-cdn \
    --dart-define=STOREQL_API_BASE=http://localhost:8090/api)   # web UI bundle
```

The web build must be re-run (and the image rebuilt, below) any time
`frontends/storeql-app/` changes — `Dockerfile.web` just copies the
pre-built `frontends/storeql-app/build/web` directory into nginx, it does not
run `flutter build` itself.

```bash
docker compose up -d --build   # infra → platform (config/discovery/gateway) → 14 services → storeql-app, health-gated
```

This command is idempotent and safe to re-run — it only rebuilds/restarts
containers whose image or config actually changed, and does not touch
named volumes (Postgres data survives). To rebuild only the web image after
a frontend-only change: `docker compose build storeql-app && docker compose up -d storeql-app`.

## Run (agent path)

Once `docker compose up -d --build` reports all services healthy, drive the
web UI with the driver:

```bash
export PLATFORM_ADMIN_EMAIL=$(grep -m1 '^PLATFORM_ADMIN_EMAIL=' .env | cut -d= -f2-)
export PLATFORM_ADMIN_PASSWORD=$(grep -m1 '^PLATFORM_ADMIN_PASSWORD=' .env | cut -d= -f2-)
node .claude/skills/run-storeql/driver.mjs <<'EOF'
launch
login
ss platform-overview
click 40 155
wait 1500
ss platform-tenants
console
quit
EOF
```

This is a **real, verified** sequence — it logs into the platform console
through the actual gateway → iam-svc → tenant-svc chain and lands on
`#/platform/overview` with live data ("All Services Healthy", tenant count),
then clicks the in-app "Tenants" nav item (not a URL edit — a real
coordinate click on the rendered sidebar) and lands on `#/platform/tenants`.
Zero console errors both times.

Screenshots land in `/tmp/storeql-shots/` (override with `SCREENSHOT_DIR`).

| command | what it does |
|---|---|
| `launch` | starts headless Chrome (`/usr/bin/google-chrome`), must run first |
| `nav <hashPath>` | e.g. `nav #/store/products` — navigates and waits for Flutter's first frame |
| `login` | full platform-admin login sequence (see Gotchas — coordinates are viewport-specific) |
| `click <x> <y>` | raw coordinate click — the ONLY way to interact, see header comment in driver.mjs |
| `type <text>` | keyboard input into whatever's currently focused |
| `press <key>` | e.g. `press Enter` |
| `wait <ms>` | fixed delay — Flutter web has no reliable "idle" signal beyond first-frame |
| `ss [name]` | screenshot to `SCREENSHOT_DIR` |
| `url` | print current location |
| `console` | dump captured console errors / pageerrors |
| `quit` / `exit` | close the browser and exit |

For iterative work (send one command, look at the result, send the next)
without relaunching Chromium each time, run it under tmux:

```bash
tmux new-session -d -s storeql -x 200 -y 50
tmux send-keys -t storeql 'node .claude/skills/run-storeql/driver.mjs' Enter
sleep 1
tmux send-keys -t storeql 'launch' Enter
sleep 2
tmux send-keys -t storeql 'nav #/store/products' Enter
sleep 2
tmux send-keys -t storeql 'ss storefront' Enter
sleep 1
tmux capture-pane -t storeql -p
# … keep sending one command + sleep + capture-pane at a time …
tmux send-keys -t storeql 'quit' Enter
tmux kill-session -t storeql
```

**If `tmux` isn't installed and you have no passwordless `sudo`** (this
container started without it — `apt-get install tmux` failed with "a
terminal is required to read the password" — until it was installed
mid-session), use a named pipe instead. Verified working identically:

```bash
mkfifo /tmp/storeql-driver.in
node .claude/skills/run-storeql/driver.mjs < /tmp/storeql-driver.in > /tmp/storeql-driver.log 2>&1 &
exec 3>/tmp/storeql-driver.in   # keep the pipe open across multiple writes in this shell
echo "launch" >&3
sleep 2
echo "nav #/store/products" >&3
sleep 2
echo "ss storefront" >&3
sleep 1
cat /tmp/storeql-driver.log     # see output so far
# … send more commands the same way …
echo "quit" >&3
exec 3>&-                      # close the pipe when done
```

**Other entry points** (all via `nav <hashPath>` once launched):
`#/login` (staff/owner login), `#/pos/cart` (in-store POS), `#/store/products`
(public storefront — needs `?tenant=<id>` before the `#`, see Gotchas),
`#/onboarding` (new-tenant wizard).

## Run (human path)

```bash
docker compose up -d --build   # same as above
```

Then open `http://localhost:8088` in a real browser. `docker compose down`
stops everything (add `-v` only if you intentionally want to wipe DB data —
tenant IDs are server-generated and change on every fresh volume).
`scripts/redeploy.sh` wraps rebuild+redeploy in one script;
`scripts/run-web.sh` runs the Flutter app in `flutter run -d web-server`
dev mode against the dockerized backend instead of the built container.

## Test

```bash
mvn test                                          # backend unit + Testcontainers integration tests
cd frontends/storeql-app && flutter test             # widget tests (67 as of this writing)
cd frontends/storeql-app && flutter analyze --fatal-infos   # static analysis, must be clean
```

---

## Gotchas

- **This app is canvas-rendered — there is no DOM to select against.**
  `document.querySelectorAll('input, textarea')` returns `0` even on a
  screen full of visible, focused-looking text fields (confirmed via
  `flt-glass-pane` presence in the DOM — that's Flutter's CanvasKit host
  element, everything inside it is drawn to a canvas). Every interaction is
  a raw `(x, y)` click. Screen layouts are NOT identical between similar
  screens — the tenant `/#/login` and `/#/platform/login` forms look nearly
  the same but their field Y-coordinates differ by ~20px (414/478/542 vs
  394/458/522 at 1280×800). Always take a fresh `ss` before clicking a
  screen you haven't driven before, don't reuse coordinates across screens.

- **The `'#loading'` div is the only reliable ready signal.** `web/index.html`
  removes it on the `flutter-first-frame` window event. `waitUntil: 'load'`
  or `'networkidle'` in `page.goto()` do NOT work as a ready signal — the
  Flutter engine keeps background connections open indefinitely, so
  `networkidle` never fires (or fires while the loading spinner is still
  up). Even after `#loading` detaches, wait another ~500ms — the event
  fires just before the frame is actually painted, and a screenshot taken
  immediately can catch a half-drawn frame.

- **readline's `'line'` event does not wait for your async handler.** A
  heredoc delivers all lines to stdin near-instantly, and Node's readline
  fires `'line'` for each one back-to-back regardless of whether the
  previous handler's promise resolved. The first version of this driver ran
  every command from a piped script *before `launch` had finished starting
  Chromium*, and every single one failed with "launch first" — including
  ones piped several lines *after* `launch`. Fixed by chaining each parsed
  command onto a shared `queue` promise instead of just `await`-ing inside
  the per-line callback. If you fork this driver, keep that pattern — it's
  not optional, it's the difference between the driver working at all
  under a piped script vs. only working when hand-typed slowly into a
  live REPL.

- **`Dockerfile.web` does not run `flutter build`.** It only
  `COPY`s `frontends/storeql-app/build/web` into the nginx image. If you edit
  frontend source and just run `docker compose build storeql-app`, you'll get
  a cached layer with the OLD build — you must run `flutter build web
  --release` yourself first, every time, then rebuild the image.

- **The storefront needs `?tenant=<uuid>` in the URL, before the `#`.**
  `nav ?tenant=<id>#/store/products` (not `nav #/store/products?tenant=...`
  — go_router reads the hash fragment as the route, but `tenant` is read
  from the real query string ahead of the `#`, per
  `TenantStatusGate`/`X-Storefront-Tenant`). There's no seeded tenant ID
  hardcoded anywhere reliable to put in this file — list current tenants
  via the platform console's Tenants screen (`login` → `click 40 155`) since
  tenant IDs are server-generated per onboarding and churn across
  `docker compose down -v` cycles.

## Troubleshooting

- **`node driver.mjs` prints commands running out of order / everything
  after `launch` fails**: see the readline Gotcha above — you're on an old
  copy of the driver without the `queue` chaining fix.
- **`.env: line NN: -Xmx512m: command not found` when you `source .env`
  directly**: some `.env` values aren't plain `KEY=value` (JVM heap flags
  embedded in a compound var) and break a naive `bash -c 'set -a; source
  .env'`. Don't source the whole file for the driver — `grep` out the two
  vars you need (see Setup/Run above).
- **`docker compose build storeql-app` succeeds but the container serves
  stale content**: you skipped the `flutter build web --release` step — see
  the Dockerfile.web Gotcha above.
- **Login click lands nowhere / form stays filled but unsubmitted**: your
  `click` coordinate missed the button. This happened during development —
  clicking `(640, 522)` on `/#/platform/login` filled both fields
  correctly but missed "Sign in" (actually at y≈542); the fields stayed
  populated with the click silently swallowed by empty canvas space. Take a
  fresh `ss` and re-measure.
