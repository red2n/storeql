# Client Onboarding & Location Mapping — Design

> **Status:** Core flow implemented (tenant-svc `/onboarding` + `/admin` tenant/store/zone/staff endpoints, the Flutter onboarding wizard). **Delivery areas:** implemented (`delivery_areas` table V6, `POST/GET/DELETE /admin/stores/{id}/delivery-areas`, `GET /fulfilment/resolve`; order-svc resolves pincode on DELIVERY; admin UI on Stores). When no areas are mapped, resolve falls back to the default/first store. Companion: [CLAUDE.md](../CLAUDE.md), [docs/API-GUIDE.md § tenant-svc](API-GUIDE.md#tenant-svc), [ARCHITECTURE §10](ARCHITECTURE.md#10-the-business-services).

---

## 1. Why this document exists

Two flows sit at the very start of every tenant's life and shape almost every other service's data:

1. **Client (tenant) onboarding** — how a business signs up, gets an owner account, a subscription/plan, and its first store.
2. **Location mapping** — the spatial model (**Tenant → Stores → Zones**) that inventory, pricing, orders, and POS are all scoped to.

Getting these right (and consistent across services) prevents the most expensive class of mistakes: tenant data leaking, stock with no home, or orders that can't be fulfilled. **inventory-svc, pricing-svc, order-svc, and the POS all assume this model — do not change it without updating them together.**

---

## 2. The location model: Tenant → Stores → Zones

```
TENANT  (a business / client)
  │  id, name, status, plan_id
  │
  ├── STORE  (a physical site — owned by tenant-svc)
  │     id, tenant_id, name, type[STORE|WAREHOUSE], code,
  │     address{line1,line2,city,state,country,pincode},
  │     geo{lat,lng}, timezone, business_hours, status, is_default
  │     │
  │     └── ZONE  (internal sub-location within a store — owned by tenant-svc)
  │           id, tenant_id, store_id, name, code,
  │           type[AISLE|RACK|SHELF|COLD_ROOM|BACK_STORE|RECEIVING|DISPLAY],
  │           status
  │
  └── STORE … (more stores/warehouses)
```

### What each level means

| Level | Meaning | Examples |
|---|---|---|
| **Tenant** | The customer business using the platform. The isolation boundary — all data is `tenant_id`-scoped. | "Sharma Grocers Pvt Ltd" |
| **Store** | A physical site where stock is held and/or sold. `STORE` = customer-facing shop; `WAREHOUSE` = stock only, no walk-in sales. | "MG Road Outlet", "Central Warehouse" |
| **Zone** | A sub-location inside a store telling you *where in the building* stock physically sits. Used to direct put-away (on receipt) and picking. | "Aisle 3", "Cold Room", "Receiving Dock", "Rack B-12" |

### Rules

- **Every tenant has ≥1 store.** Onboarding creates the first store (the **default store**, `is_default = true`).
- **Every store has ≥1 zone.** A `DEFAULT` zone is auto-created with each store so stock always has a home even before the operator maps real aisles.
- **Store `code` is unique per tenant; zone `code` is unique per store.** Codes are short, human-typed identifiers (`MGR`, `WH1`, `A3`) used by staff and on labels.
- **Inventory is scoped to `(store_id, zone_id)`.** A stock batch lives in exactly one zone of one store. Moving stock between zones/stores is a `TRANSFER` movement (append-only) in inventory-svc.
- **Storefront & POS operate in the context of one store.** Online orders resolve a **fulfilling store** (see §6 delivery mapping); POS sales are tied to the cashier's current store.
- **Ownership:** `tenant-svc` **owns** the `tenants`, `stores`, `zones` tables. Other services **reference** `store_id` / `zone_id` but must **never** join to tenant-svc's tables — they obtain store/zone facts via tenant-svc's API or by consuming `StoreCreated` / `ZoneCreated` events (database-per-service, [golden rule #1](../CLAUDE.md)).

---

## 3. Data model (tenant-svc owns these)

> Standard rules apply: UUID PKs, `tenant_id UUID NOT NULL` on tenant-scoped tables, composite index starting with `tenant_id`, timestamps `timestamptz` UTC, Flyway migrations. (`tenants` itself is the root, so `tenant_id` = its own `id`.)

```sql
-- V1__init.sql (tenant-svc)
CREATE TABLE tenants (
  id           UUID PRIMARY KEY,
  name         TEXT NOT NULL,
  legal_name   TEXT,
  status       TEXT NOT NULL DEFAULT 'PENDING',   -- PENDING|ACTIVE|SUSPENDED|CLOSED
  plan_id      UUID NOT NULL,
  country      TEXT NOT NULL,                      -- ISO-3166 alpha-2
  currency     TEXT NOT NULL,                      -- ISO-4217
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stores (
  id            UUID PRIMARY KEY,
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  name          TEXT NOT NULL,
  code          TEXT NOT NULL,                     -- unique per tenant
  type          TEXT NOT NULL DEFAULT 'STORE',     -- STORE|WAREHOUSE
  line1         TEXT, line2 TEXT, city TEXT, state TEXT,
  country       TEXT NOT NULL, pincode TEXT,
  geo_lat       NUMERIC(9,6), geo_lng NUMERIC(9,6),
  timezone      TEXT NOT NULL DEFAULT 'UTC',
  business_hours JSONB,                            -- [{day, open, close}]
  status        TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE|INACTIVE|CLOSED
  is_default    BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);
CREATE INDEX idx_stores_tenant ON stores(tenant_id, status);

CREATE TABLE zones (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  store_id    UUID NOT NULL REFERENCES stores(id),
  name        TEXT NOT NULL,
  code        TEXT NOT NULL,                       -- unique per store
  type        TEXT NOT NULL DEFAULT 'AISLE',       -- AISLE|RACK|SHELF|COLD_ROOM|BACK_STORE|RECEIVING|DISPLAY|DEFAULT
  status      TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (store_id, code)
);
CREATE INDEX idx_zones_tenant_store ON zones(tenant_id, store_id, status);

-- staff↔store assignment (also tenant-svc)
CREATE TABLE staff_assignments (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL,
  user_id     UUID NOT NULL,                       -- from iam-svc
  store_id    UUID NOT NULL REFERENCES stores(id),
  role        TEXT NOT NULL,                        -- MANAGER|STOREKEEPER|CASHIER
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, store_id, role)
);

-- optional: delivery-zone mapping for online fulfilment (see §6; can defer to a later phase)
CREATE TABLE delivery_areas (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL,
  store_id    UUID NOT NULL REFERENCES stores(id), -- which store fulfils this area
  pincode     TEXT,                                 -- simple model: by pincode
  -- geo_polygon GEOGRAPHY,                          -- advanced model (PostGIS), later
  priority    INT NOT NULL DEFAULT 100,             -- lower wins when areas overlap
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_pincode ON delivery_areas(tenant_id, pincode);
```

---

## 4. Onboarding flow (the happy path)

Goal: a new business goes from "sign up" to "ready to receive stock and sell" with minimal steps. Spans **iam-svc** (account) and **tenant-svc** (business + store + zone), coordinated by events.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 1  Owner signs up ("Start a business")                                   │
│   Client → Gateway → iam-svc  POST /auth/register/business                  │
│     {email, password, phone?}   (phone kept as typed, as a shopper's is)    │
│   iam-svc creates a STAFF user (type=STAFF, no tenant, no role) → JWT issued   │
│   publishes UserRegistered (type STAFF)                                        │
│   ── the app reads "no tenant, no role" as "set the business up" → wizard     │
│   (POST /auth/register stays the shopper's: a CUSTOMER, never the wizard)     │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEPS 2+3 as one call — what the app's setup wizard sends (29 Sep 2026)       │
│   Owner → Gateway → tenant-svc  POST /onboarding                              │
│     {businessName, legalName, country, currency, planId,                      │
│      storeName, storeCode, storeType, storeLine1…, storeTimezone}             │
│   the business, its default store and DEFAULT zone; the same events as        │
│   below. The app refreshes the token only after it, so the router cannot      │
│   leave the wizard with a business and no store. The two calls below stay     │
│   for API clients that set a business up step by step.                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 2  Create the business (tenant)                                          │
│   Owner → Gateway → tenant-svc  POST /onboarding/tenants                        │
│     {businessName, legalName, country, currency, planId}                       │
│   tenant-svc creates tenant(status=PENDING) → links owner user → status=ACTIVE │
│   publishes TenantCreated  (carries ownerUserId)                               │
│   ── iam-svc consumes TenantCreated → stamps tenant_id + OWNER role on the user │
│       (and re-issues a JWT that now contains tenant_id on next refresh)         │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 3  Create the first store (default)                                      │
│   Owner → Gateway → tenant-svc  POST /onboarding/stores                         │
│     {name, code, type, address, geo, timezone, businessHours}                  │
│   tenant-svc creates store(is_default=true)                                    │
│     AND auto-creates a DEFAULT zone for it                                      │
│   publishes StoreCreated  +  ZoneCreated(DEFAULT)                              │
│   ── inventory-svc, pricing-svc, product-svc react: a store now exists they    │
│       can scope data to (they cache store_id; they do NOT read tenant tables).  │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 4  (optional, anytime) Map real zones                                    │
│   Owner/Manager → tenant-svc  POST /admin/stores/{id}/zones  (aisles, racks…)  │
│   publishes ZoneCreated per zone                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 5  (optional, anytime) Add staff                                         │
│   Owner → iam-svc  POST /auth/admin/staff-users {email, password}             │
│     → a login made in this business (or its own again) — never anyone else's  │
│   Owner → tenant-svc  POST /admin/staff {userId, storeId, role}               │
│   publishes StaffAssigned ── iam-svc binds the role to that login only        │
├─────────────────────────────────────────────────────────────────────────────┤
│ ✔ READY: tenant ACTIVE, ≥1 store with ≥1 zone, owner can add products,         │
│   receive stock (purchase-svc GRN → inventory batches in a zone), and sell.    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Why the owner has a sign-up of its own (29 Sep 2026).** A shopper's sign-up (`POST /auth/register`) makes a CUSTOMER, and the app sends a CUSTOMER to the shop, never to the setup wizard — so a person starting a business from the sign-in card could not reach the wizard at all. The business sign-up is public like the shopper's (the gateway lets it through without a token; it is not a login, so the failed-login lockout does not count it, and the per-IP rate limit covers it as it covers every path), holds the same password policy (`GET /auth/password-policy`) and its own duplicate refusal (`409 USER_ALREADY_EXISTS` for an address another business sign-up of no business, or the platform administrator, already uses), and names nothing about a business: no tenant or role is ever read from the request. Signing in again or refreshing before the business exists keeps "no tenant, no role", so the login lands in the wizard until it is done. `TenantCreated` then stamps the tenant and grants OWNER — OWNER alone, since the login never held CUSTOMER — and only to a login that has no tenant yet or already has this one: a login another business has since taken on as staff keeps that business and is not made its owner (`OWNER_BIND_REFUSED` in the audit log). `StaffAssigned` goes further: it binds a role only to a login already in the assigning business — one made there by staff provisioning, or its owner — and stamps nobody in. tenant-svc assigns whatever user id its caller names, and a business can read a shopper's login id off its own orders: a stamp let it pull that shopper's account into its staff without their say (after which the storefront refused their token everywhere), or capture a founder's sign-up before they had set their business up. And a role row does not say which business granted it, so a login another business employs must never be bound either — a cashier who started a business of their own could otherwise assign their employed login OWNER at their own store and sign in as their employer's owner. Every such event is refused, marked processed and audited `STAFF_BIND_REFUSED`; the login is unchanged.

**A shopper's account and a business account are separate identities (as at Shopify, Square and Stripe).** One person may shop with an address and run a business with the same one. Outside any business an email is unique once per kind of login — one shopper's account (`CUSTOMER`), one business account (`STAFF`: a sign-up not yet onboarded, the platform administrator) — and inside a business once (iam-svc `V1__init.sql`: `uq_users_unbound_email` on `(type, lower(email)) WHERE tenant_id IS NULL`, `uq_users_business_email` on `(tenant_id, lower(email)) WHERE tenant_id IS NOT NULL`). A phone follows the same rule since the business sign-up takes one (iam-svc `V1__init.sql`: `uq_users_unbound_phone` on `(type, phone)`, `uq_users_business_phone` on `(tenant_id, phone)`). So:

- **Sign-up.** A person who already shops with an address or phone can start a business with it (a second, separate login); a second shopper sign-up or a second business sign-up with that address or phone is still `409 USER_ALREADY_EXISTS`. The Platform Console's assisted onboarding makes the owner with the business sign-up too, and signs in with `accountType: STAFF`.
- **Sign-in.** `POST /auth/login` takes `accountType`: the storefront sends `CUSTOMER`, the admin console and the till send `STAFF`, and nothing sent means `STAFF` (everything that runs a business signs in here; the storefront is the one place that signs a shopper in, and says so). Only the kind asked for is tried while the address holds one of it — a password that happens to open the other kind is refused — and the other kind only when it holds none, so a person with a single account signs in with it anywhere, as before. No signal existed to reuse: the storefront's sign-in goes out without its `X-Storefront-Tenant` header, and the gateway forwards that header to no service.
- **Adding staff** (`POST /auth/admin/staff-users`) finds only the business's own login with the address, or makes a new login already in the business — never takes over anyone else's. A shopper's account stays the person's own, and an unfinished business sign-up cannot be captured by a business that adds the address as staff first: the founder still completes onboarding and becomes the owner of their own business. This is the only way a login becomes staff — assign the `userId` it answers; a `StaffAssigned` naming any other login takes nobody on (above).
- **Several businesses.** Another business's login with the address refuses nothing (`409 EMAIL_IN_OTHER_TENANT` is gone): each business that takes the person on has its own login for them, as at Square and Shopify, and the admin console or the till opens whichever the password opens (newest first when two share one). The refusal made sense only while provisioning adopted the login it found; once nothing is adopted it only let whichever business added an address first hold it against every other — a login provisioned and never assigned (a plan's staff limit refusing the assignment, say) stranded the person out of every other job, and a hostile business could squat any address with one call.
- **Letting go.** A member of staff removed from their last store keeps their login in the business, holding no staff role: it opens nothing (the admin filter refuses it, the storefront refuses a staff token, single sign-on no longer finds it), the business's management no longer sees it among its staff, and provisioning the address again gives the business the same login back. It never becomes a login of no business, which would read as a business sign-up waiting for its setup wizard — with the password the business chose — and hold the address a sign-up of the person's own needs. Only a shopper's account that an earlier build's `StaffAssigned` stamped into the business (it still holds `CUSTOMER`) goes back to being that shopper's, unless its email or phone has meanwhile become another shopper's account's (`uq_users_unbound_email` / `uq_users_unbound_phone`); a business sign-up holding either is a separate identity and is never in the way.
- **A forgotten password** gives each login on the address its own link, the business sign-up's named as a business account (`STAFF`, no business yet), and each link resets only its own login.

**Onboarding completeness check** (tenant-svc exposes `GET /onboarding/status`): tenant ACTIVE ✓, default store exists ✓, default zone exists ✓, (optional) products added, (optional) staff invited. The admin console uses this to drive a setup checklist.

---

## 5. APIs (tenant-svc — onboarding & locations)

> All under the gateway. Auth: owner/manager roles. `tenant_id` always from JWT (never body). Standard response envelope.

### Onboarding
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/onboarding/tenants` | authenticated owner (pre-tenant) | Create the business; link caller as OWNER. |
| `POST` | `/onboarding/stores` | OWNER | Create the first/default store (+ DEFAULT zone). |
| `GET`  | `/onboarding/status` | OWNER/MANAGER | Setup-checklist state. |

### Stores
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/stores` | OWNER | Add a store/warehouse. |
| `GET`  | `/admin/stores` | OWNER/MANAGER | List tenant's stores. |
| `GET`  | `/admin/stores/{id}` | OWNER/MANAGER | Store detail (with zones). |
| `PUT`  | `/admin/stores/{id}` | OWNER/MANAGER | Update address/geo/hours/status. |

### Zones
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/stores/{storeId}/zones` | OWNER/MANAGER/STOREKEEPER | Add a zone. |
| `GET`  | `/admin/stores/{storeId}/zones` | staff | List zones in a store. |
| `PUT`  | `/admin/zones/{id}` | OWNER/MANAGER/STOREKEEPER | Rename / deactivate. |

### Staff
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/staff` | OWNER/MANAGER | Assign a user to a store with a role. |
| `GET`  | `/admin/staff` | OWNER/MANAGER | List assignments (tenant-scoped). |

### Delivery areas (optional, online fulfilment)
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/stores/{storeId}/delivery-areas` | OWNER/MANAGER | Map pincode(s) → this store fulfils them. |
| `GET`  | `/fulfilment/resolve?pincode=` | internal (order-svc) | Resolve which store fulfils a customer pincode. |

---

## 6. Location mapping at work (downstream usage)

How the model is actually used once onboarding is done:

- **Receiving stock (put-away):** purchase-svc records a GRN → `GoodsReceived` → inventory-svc creates a batch in `(store_id, zone_id)`. If the operator doesn't pick a zone, it lands in the store's `DEFAULT` zone.
- **POS sale:** cashier is bound to a store (via `staff_assignments`); the sale deducts stock from that store (FIFO across that store's batches).
- **Online order — fulfilling-store resolution:** at checkout for `DELIVERY`, order-svc calls tenant-svc `GET /fulfilment/resolve?pincode=` to pick the store that serves the customer's pincode (lowest `priority` wins on overlap). For `PICKUP`, the customer chose the store explicitly. Stock is then reserved at that store.
- **Reporting:** sales/inventory facts carry `store_id` (and channel), enabling per-store and cross-store reports.

> **Phasing tip:** delivery-area mapping (the `delivery_areas` table + resolver) can be **deferred**. A simple first cut: single store ⇒ it fulfils everything; multi-store ⇒ require the customer to pick a store, or map by pincode. Geo-polygon (PostGIS) resolution is a later enhancement.

---

## 7. Events (this domain)

| Event | Publisher | Key consumers | Payload essentials |
|---|---|---|---|
| `UserRegistered` | iam-svc | tenant-svc(opt), customer-svc, notification-svc | userId, email, type |
| `TenantCreated` | tenant-svc | iam-svc (stamp tenant_id + OWNER role) | tenantId, ownerUserId, plan |
| `StoreCreated` | tenant-svc | inventory-svc, pricing-svc, product-svc, reporting-svc | tenantId, storeId, type, geo, isDefault |
| `ZoneCreated` | tenant-svc | inventory-svc | tenantId, storeId, zoneId, type |
| `StaffAssigned` | tenant-svc | iam-svc (role/store claim), notification-svc | tenantId, userId, storeId, role |
| `FeatureToggled` | tenant-svc | any feature-gated service | tenantId, featureKey, enabled |

All published via the **transactional outbox**; all consumers **idempotent** (dedupe on event id). See [ARCHITECTURE §11](ARCHITECTURE.md#11-how-services-talk-to-each-other).

---

## 8. Multi-tenancy enforcement recap (do not skip)

- `tenant_id` is read from the **JWT** on every request; **reject** any request whose body/path tries to set a different `tenant_id` (treat as `403`).
- Every query in tenant-svc (and every service) filters `tenant_id` first; store/zone lookups are always `WHERE tenant_id = :jwtTenant AND ...`.
- Cross-service: a service needing store/zone info calls tenant-svc or consumes its events — **never** a DB join. It may keep a **local read-cache** of `(store_id → tenant_id, geo, status)` built from `StoreCreated`/`ZoneCreated` to avoid chatty calls; that cache is its own table, refreshed by events (CQRS projection).
- New tenant tables must follow the tenant-filter rules in [docs/coding-standards.md](coding-standards.md): `tenant_id NOT NULL`, composite index, and (when DB-level RLS is added) row-level-security policy keyed on the current tenant.

---

## 9. Open decisions (carry to [PRD §11](../PRD.md))

| # | Question | Suggested default |
|---|---|---|
| 1 | Delivery resolution: pincode-only vs geo-polygon (PostGIS)? | Pincode first; PostGIS later. |
| 2 | Can one customer shop multiple tenants' storefronts with one account, or is a customer scoped to a tenant? | Settled otherwise in the build: one shopper login (iam-svc `users`, type `CUSTOMER`, `tenant_id` NULL) signs in at any storefront, and each business keeps its own customer record of that shopper in customer-svc (`customers`, tied to the login by `login_id`, unique per business), so isolation is kept in the records, not in the login. Staff logins stay per business. |
| 3 | Self-serve onboarding (anyone signs up a business) vs admin-provisioned tenants? | Self-serve, with plan gating. |
| 4 | Auto-create only a `DEFAULT` zone, or seed common zones (Aisle/Cold/Receiving)? | DEFAULT only; let operator add real zones. |
| 5 | Is `WAREHOUSE` sellable via storefront, or stock-only? | Stock-only (not a sales channel). |
