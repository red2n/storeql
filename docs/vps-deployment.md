# StoreQL VPS Deployment Guide — storeql.com

This guide covers everything needed to run StoreQL on a production VPS under the domain **storeql.com**, from a bare server to a live multi-tenant platform. Read this alongside [docs/ARCHITECTURE.md](ARCHITECTURE.md) (architecture) and [docs/onboarding-and-locations.md](onboarding-and-locations.md) (tenant onboarding flow).

---

## Table of contents

1. [Multi-tenant platform overview](#1-multi-tenant-platform-overview)
2. [Server prerequisites](#2-server-prerequisites)
3. [DNS setup](#3-dns-setup)
4. [GitHub Actions setup](#4-github-actions-setup)
5. [Server setup](#5-server-setup)
6. [Deploy the stack](#6-deploy-the-stack)
7. [First-time platform bootstrap](#7-first-time-platform-bootstrap)
8. [Access URLs — local dev](#8-access-urls--local-dev)
9. [Access URLs — production (storeql.com)](#9-access-urls--production-storeqlcom)
10. [Login flows for every user type](#10-login-flows-for-every-user-type)
11. [Accessing internal ops tools in production](#11-accessing-internal-ops-tools-in-production)
12. [Ongoing operations](#12-ongoing-operations)

---

## 1. Multi-tenant platform overview

StoreQL is a **multi-tenant SaaS** platform. A **tenant** is one business (a retailer, a small chain) that has signed up to use the platform. Tenants are completely isolated — they cannot see each other's data.

```
Platform Admin (storeql.com staff)
  └── Manages all tenants on the platform

Tenant (one business)
  └── Owner / Manager            — manages their own stores, staff, catalogue
        └── Store                — a physical location the business operates
              └── Zone           — aisle/rack/area inside a store (stock location)
              └── Staff          — cashiers/stockkeepers assigned to this store

Customers (end consumers)
  └── Browse the storefront, place online orders, visit in-store POS
```

**There is one Flutter web app** (`app.storeql.com`) that serves all four interfaces:
- **Platform admin console** — manage tenants across the whole platform
- **Admin/management console** — tenant owner or manager running their business
- **POS terminal** — cashier clocking in to a store and processing sales
- **Storefront** — guest or logged-in customer shopping online

The interface the user lands on is determined by their role after login (or, for the storefront, by the `?tenant=<id>` URL parameter).

---

## 2. Server prerequisites

### Minimum spec (single-node, up to ~5 concurrent tenants)
| Resource | Minimum | Recommended |
|---|---|---|
| CPU | 2 vCPU | 4 vCPU |
| RAM | 8 GB | 16 GB |
| Disk | 40 GB SSD | 80 GB SSD |
| OS | Ubuntu 22.04 LTS | Ubuntu 24.04 LTS |
| Open ports | 22 (SSH), 80 (HTTP), 443 (HTTPS) | same |

> **Port binding security:** every service in `docker-compose.yml` binds its host port to `127.0.0.1` (localhost only), not `0.0.0.0` (all interfaces). This means none of them — gateway, storeql-app, Grafana, pgAdmin, Consul, Postgres, Redis, etc. — are reachable on the public IP at all, regardless of firewall rules. Caddy is the only process that listens on `0.0.0.0:80` and `0.0.0.0:443`, and it only forwards to `app.storeql.com` and `api.storeql.com`. Everything else is reachable via SSH tunnel only (see §11). Firewall rules blocking 8088/8090/etc. are good defence-in-depth but are not load-bearing.

### Software to install on the server

```bash
# Docker Engine + Compose plugin
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # re-login for this to take effect

# Git
sudo apt install -y git

# Verify
docker compose version   # must be v2.x
```

---

## 3. DNS setup

At your domain registrar, add these **A records** pointing to your server's public IP. Do this **before** deploying — Caddy performs an HTTP-01 ACME challenge on first startup to obtain TLS certificates.

| Record type | Host | Value |
|---|---|---|
| A | `storeql.com` | `<your-server-ip>` |
| A | `app.storeql.com` | `<your-server-ip>` |
| A | `api.storeql.com` | `<your-server-ip>` |

DNS propagation typically takes 1–10 minutes (up to 48 hours in rare cases). Verify with:

```bash
dig +short app.storeql.com
dig +short api.storeql.com
# Both should return your server IP before you run docker compose
```

---

## 4. GitHub Actions setup

The Flutter web app bakes the API gateway URL in at build time. Before pushing to `main` (or tagging a release), set this once in your GitHub repository:

**GitHub → Settings → Secrets and variables → Actions → Variables → New repository variable**

| Variable name | Value |
|---|---|
| `STOREQL_API_BASE` | `https://api.storeql.com/api` |

After this is set, every push to `main` will build `ghcr.io/red2n/storeql-web:latest` with the production gateway URL baked in. Pull the new image on the server to apply changes.

---

## 5. Server setup

```bash
# 1. Clone the repository
git clone https://github.com/red2n/storeql.git
cd storeql

# 2. Create your .env from the example
cp .env.example .env
```

Now edit `.env` and fill in every required value. At minimum:

```bash
# Secrets — generate these, never reuse dev defaults in production
STOREQL_JWT_SECRET=$(openssl rand -base64 48)          # >= 32 chars; seals iam-svc's token signing keys at rest
MQTT_PUBLISHER_PASSWORD=$(openssl rand -hex 24)       # notification-svc's broker login (letters and digits only)
STOREQL_CONFIG_TOKEN=$(openssl rand -base64 48)        # >= 32 chars
PLATFORM_ADMIN_PASSWORD=$(openssl rand -base64 24)    # save this — you'll need it to log in

# Postgres passwords (change all of them from the *_dev_change_me defaults)
POSTGRES_PASSWORD=...
IAM_DB_PASSWORD=...
TENANT_DB_PASSWORD=...
# ... all other *_DB_PASSWORD values

# Redis
REDIS_PASSWORD=...

# Caddy / Let's Encrypt
CADDY_ACME_EMAIL=ops@storeql.com     # receives certificate expiry warnings

# Server-side pricing must be true in production
STOREQL_ORDER_PRICING_ENFORCE=true
```

> **Never commit `.env`** — it is in `.gitignore`.

---

## 6. Deploy the stack

```bash
# Pull the latest production images (built by GitHub Actions on merge to main)
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull

# Start everything (Postgres, Kafka, Consul, all services, Caddy)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

# Watch startup — wait until gateway shows healthy
docker compose logs -f gateway caddy
```

Caddy will automatically obtain TLS certificates for `storeql.com`, `app.storeql.com`, and `api.storeql.com` on first startup. This takes ~30 seconds on a clean deploy.

**Verify the stack is up:**

```bash
# All containers running?
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# Gateway health
curl https://api.storeql.com/api/iam-svc/health/ready

# Web UI responding
curl -I https://app.storeql.com/healthz
```

---

## 7. First-time platform bootstrap

The `bootstrap` container in `docker-compose.yml` seeds the first **PLATFORM_ADMIN** account on a fresh database automatically. It runs once and exits. Check it ran successfully:

```bash
docker compose logs bootstrap
# Expected output: [bootstrap] Platform admin created (admin@storeql.dev)
# or:              [bootstrap] Platform admin already exists — skipped
```

The bootstrap account credentials:
- **Email:** value of `PLATFORM_ADMIN_EMAIL` in `.env` (default `admin@storeql.dev`)
- **Password:** value of `PLATFORM_ADMIN_PASSWORD` in `.env`

> **Change the admin email** in `.env` from the default before first deploy. The default is intentionally obvious so the system rejects a deploy with an unchanged placeholder.

---

## 8. Access URLs — local dev

These are the URLs you use when running `docker compose up` on your laptop (base file only, no prod overlay).

### Application interfaces

| Interface | URL | Who uses it |
|---|---|---|
| **Platform admin login** | `http://localhost:8088/#/platform/login` | storeql.com platform staff — manages all tenants |
| **Tenant admin / manager login** | `http://localhost:8088/#/login` | Owner or Manager of a specific tenant business |
| **Store staff / POS login** | `http://localhost:8088/#/login` | Cashier or Storekeeper — same screen, lands on `/pos` after login, then clocks in to a store |
| **Storefront (guest)** | `http://localhost:8088/?tenant=<tenantId>#/store/products` | End customer browsing the online shop — no login required |
| **Storefront (logged in)** | `http://localhost:8088/#/store/products` | Customer who has an account (access their order history, loyalty points) |

> The admin login and POS login use the same `/login` screen — the app routes to `/admin/dashboard` or `/pos` based on the authenticated user's role.

### API and infrastructure

| Tool | URL | Purpose |
|---|---|---|
| **Gateway (API)** | `http://localhost:8090/api` | All business API calls go through here |
| **Swagger UI (API docs)** | `http://localhost:8082` | Browse and test all service endpoints |
| **Kafka UI** | `http://localhost:8081` | Browse Kafka topics and event messages |
| **pgAdmin** | `http://localhost:5555` | PostgreSQL browser (login with `PGADMIN_EMAIL` / `PGADMIN_PASSWORD` from `.env`) |
| **Grafana** | `http://localhost:3100` | Observability dashboards (metrics + logs + traces) |
| **Consul UI** | `http://localhost:8500` | Service discovery — see which services are registered and healthy |
| **Prometheus** | `http://localhost:9090` | Raw metrics scraping UI |
| **Zipkin** | `http://localhost:9411` | Distributed request traces |
| **Postgres** | `localhost:5432` | Direct SQL access (`psql -h localhost -U storeql -d storeql`) |
| **Redis** | `localhost:6379` | Cache/rate-limit store (`redis-cli -h localhost`) |

---

## 9. Access URLs — production (storeql.com)

### Application interfaces

| Interface | URL | Who uses it |
|---|---|---|
| **Platform admin login** | `https://app.storeql.com/#/platform/login` | storeql.com platform staff |
| **Tenant admin / manager login** | `https://app.storeql.com/#/login` | Owner or Manager of a tenant business |
| **Store staff / POS login** | `https://app.storeql.com/#/login` | Cashier or Storekeeper (routes to `/pos` after login) |
| **Storefront (guest)** | `https://app.storeql.com/?tenant=<tenantId>#/store/products` | End customer — no login |
| **Storefront (logged in)** | `https://app.storeql.com/#/store/products` | Logged-in customer |
| **Bare domain redirect** | `https://storeql.com` | Redirects → `https://app.storeql.com` |

### API

| Tool | URL | Notes |
|---|---|---|
| **Gateway (API)** | `https://api.storeql.com/api` | All browser API calls route here via Caddy |
| **Swagger UI** | Not public-facing | Access via SSH tunnel — see §11 |

> **Storefront tenant URL:** when you onboard a new tenant, retrieve their `tenantId` UUID from the platform admin console or the Tenant API. Give them the URL `https://app.storeql.com/?tenant=<tenantId>#/store/products` to share with their customers.

---

## 10. Login flows for every user type

### Platform admin (storeql.com staff)

1. Go to `https://app.storeql.com/#/platform/login`
2. Enter email and password set in `PLATFORM_ADMIN_EMAIL` / `PLATFORM_ADMIN_PASSWORD`
3. Enter the six-digit code (*One more step*): the platform administrator always has a second factor (20.12). The code comes from an authenticator app holding `PLATFORM_ADMIN_TOTP_SECRET` — `scripts/platform-code.sh --setup` prints the key and an `otpauth://` link to add it once — or, on a local stack, from `scripts/platform-code.sh`, which prints the current code. A code works once; a wrong one counts as a failed sign-in.
4. Lands on the **Platform Dashboard** — lists all tenants, can suspend/reactivate them, manage platform-wide settings

### Tenant owner (first login after onboarding)

The owner registers via the standard onboarding flow (see [docs/onboarding-and-locations.md](onboarding-and-locations.md)):

1. Register at `https://app.storeql.com/#/register`
2. Create their tenant at `https://app.storeql.com/#/onboarding`
3. Future logins: `https://app.storeql.com/#/login` → lands on **Admin Dashboard**
4. From the dashboard: create stores, add zones, provision staff, seed the product catalogue

### Manager

1. Owner provisions the manager account via the admin console (or API `POST /api/iam-svc/auth/admin/staff-users`)
2. Owner assigns the manager role to a store via tenant-svc
3. Manager logs in at `https://app.storeql.com/#/login` → lands on **Admin Dashboard** (store-scoped view)

### Cashier / Storekeeper (POS)

1. Owner or manager provisions the staff account
2. Staff logs in at `https://app.storeql.com/#/login` → redirected to **POS** (role-based)
3. Staff **clocks in** by selecting their store from the list
4. POS is now active for that store session — they can process sales, manage stock (storekeeper)

### Customer (storefront)

**Guest (no account):**
- Visit `https://app.storeql.com/?tenant=<tenantId>#/store/products`
- Browse, add to cart, checkout — no login required
- Order history is stored locally on device

**Registered customer:**
- Register or log in at the storefront
- Order history, loyalty points, and saved addresses are persisted in the platform

---

## 11. Accessing internal ops tools in production

Internal tools (Grafana, pgAdmin, Kafka UI, Consul, Prometheus, Zipkin) are **not exposed to the internet** in production — they bind to `127.0.0.1` on the host only. Access them securely via an SSH tunnel from your workstation:

```bash
# Open tunnels for all ops tools in one command
ssh -N \
  -L 3100:localhost:3100 \    # Grafana
  -L 5555:localhost:5555 \    # pgAdmin
  -L 8081:localhost:8081 \    # Kafka UI
  -L 8082:localhost:8082 \    # Swagger UI
  -L 8500:localhost:8500 \    # Consul UI
  -L 9090:localhost:9090 \    # Prometheus
  -L 9411:localhost:9411 \    # Zipkin
  user@<your-server-ip>
```

Then access them at the same local-dev URLs from your workstation:

| Tool | Tunnel URL |
|---|---|
| Grafana | `http://localhost:3100` |
| pgAdmin | `http://localhost:5555` |
| Kafka UI | `http://localhost:8081` |
| Swagger UI | `http://localhost:8082` |
| Consul UI | `http://localhost:8500` |
| Prometheus | `http://localhost:9090` |
| Zipkin | `http://localhost:9411` |

**Database and Redis direct access** (also via SSH tunnel):

```bash
# Postgres
ssh -N -L 5432:localhost:5432 user@<your-server-ip>
psql -h localhost -U storeql -d storeql

# Redis
ssh -N -L 6379:localhost:6379 user@<your-server-ip>
redis-cli -h localhost -a <REDIS_PASSWORD>
```

---

## 12. Ongoing operations

### Deploy a new release

```bash
# On the server — pull the images built by GitHub Actions and restart
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

Services restart rolling (Compose restarts one at a time when images change). Caddy keeps its TLS certificates — they are stored in the `caddy_data` Docker volume.

### View logs

```bash
# All services
docker compose logs -f

# One service
docker compose logs -f gateway

# Errors only
docker compose logs --since=1h | grep -i "error\|exception\|warn"
```

### Renew TLS certificates

Caddy renews automatically before expiry (typically 30 days before the 90-day Let's Encrypt expiry). No manual action is needed. To force a renewal check:

```bash
docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
```

### Backup Postgres, and rehearse the restore

The stack backs itself up ([docs/BACKUP-AND-RESTORE.md](BACKUP-AND-RESTORE.md)): the `backup` service
dumps the database every night from one snapshot with a manifest of every table's count, encrypted to
the age public key in `STOREQL_BACKUP_RECIPIENT`, takes a base backup weekly, and Postgres archives its
WAL into the same volume so a restore can be taken to a moment (five minutes of data at risk at most,
`PG_ARCHIVE_TIMEOUT`). Before the first night with real data:

```bash
docker compose run --rm backup age-keygen            # the public key goes in .env, the identity OFF this box
# STOREQL_BACKUP_RECIPIENT=age1... in .env, then:
docker compose up -d postgres backup
docker compose run --rm backup now                    # one now, to see it work
```

Bind the `backups` volume to a disk other than the database's (`docker-compose.prod.yml`), or copy
`/backups` to object storage after each run. Grafana's `backups` alerts say when a backup is missing,
late, unencrypted, or the WAL archive is failing.

A backup is only as good as its last restore. `scripts/backup-drill.sh` rehearses the whole thing
against the running stack — backup, verify, restore into a fresh server and compare, then a recovery to
a moment — and appends the times it measured to `docs/RESTORE-REHEARSAL.md`. Run it after any change
to Postgres, the backup job or a migration that adds a table; it is what found that a restore could
fail on the UUIDv7 guards.

A single business's data is exported, erased and imported through each service's `/admin/tenant-data` and tenant-svc's `/admin/tenant/switching` instead ([API-GUIDE](API-GUIDE.md#tenant-data-export-and-leaving-2114)); a whole-database restore brings every business back together.

### Scale a service (if traffic grows)

The compose stack runs single-instance by default. To add replicas (requires a load balancer in front — Caddy does not load-balance between replicas on the same host out of the box):

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  up -d --scale iam-svc=2 --scale order-svc=2
```

> For multi-node scaling, the next step is Kubernetes. See [ARCHITECTURE §17](ARCHITECTURE.md#17-production-deployment--startup-ordering) for the production deployment model.

### Suspend / reactivate a tenant

Log in to the platform admin console at `https://app.storeql.com/#/platform/login` and use the Tenant Management UI. Suspension is propagated via a `TenantStatusChanged` Kafka event — all of that tenant's staff immediately lose the ability to log in or refresh tokens.
