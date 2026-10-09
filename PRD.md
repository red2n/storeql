# Product Requirements Document — StoreQL

**Stock & Store Management Platform with Customer Commerce**
**Architecture:** Strict Microservices · Helidon MP · API Gateway + Service Discovery + Centralized Config

---

| | |
|---|---|
| **Document status** | Draft v1.0 |
| **Date** | 2026-06-06 |
| **Owner** | ksnavinkumar.diary@gmail.com |
| **Project codename** | StoreQL (`storeql`) |
| **Reference architecture** | [red2n/home](https://github.com/red2n/home) (Spring Cloud patterns, re-implemented in Helidon) |

---

## 1. Overview

### 1.1 Problem statement

Store owners need a single platform to manage inventory across one or more physical stores **and** sell to customers through both an online storefront and an in-store point-of-sale (POS). Most off-the-shelf tools force a choice between back-office inventory control and customer-facing commerce, and they rarely isolate data cleanly when one platform serves many independent businesses.

### 1.2 Product vision

**StoreQL** is a multi-tenant SaaS platform where:

- **Store operators** manage products, stock, batches, purchases, suppliers, and staff across multiple stores.
- **Customers** browse a public storefront and purchase any available product online.
- **Cashiers** ring up in-store sales through a POS that draws on the same live inventory.

Every business (tenant) operates in full data isolation. The system is built as a set of **strictly independent microservices** behind an **API gateway**, with **service discovery** and **centralized configuration**, following the architectural pattern established in the `red2n/home` reference repository — re-implemented on **Helidon MP (MicroProfile)**.

### 1.3 How this relates to the reference repo

`red2n/home` is a Property Management System built with **Spring Cloud** (Spring Cloud Gateway + Netflix Eureka + Spring Cloud Config + Kafka + Zipkin tracing, Java 21, multi-module Maven). StoreQL **borrows the architectural shape** of that project — a gateway module, a discovery module, a configuration module, and independent business services, each owning its own PostgreSQL data and communicating over REST + Kafka events — but implements every service on **Helidon MP** instead of Spring Boot/Spring Cloud.

| Concern | `red2n/home` (reference) | StoreQL (this project) |
|---|---|---|
| Service framework | Spring Boot 3 / Spring Web MVC | **Helidon MP 4.x** (MicroProfile) |
| API gateway | Spring Cloud Gateway | **Helidon MP gateway service** (JAX-RS reverse proxy + filters) |
| Service discovery | Netflix Eureka | **Consul** (Helidon-native discovery client) |
| Central config | Spring Cloud Config Server | **Config service** (MicroProfile Config + Git/Consul KV backend) |
| Persistence | Spring Data JPA + PostgreSQL | **JPA (EclipseLink/Hibernate) + PostgreSQL** per service |
| Eventing | spring-kafka | **Helidon Messaging (MicroProfile Reactive Messaging) + Kafka** |
| Tracing | Micrometer + Brave + Zipkin | **Helidon Tracing (OpenTelemetry) + Zipkin/Jaeger** |
| Build | Multi-module Maven, Java 21 | **Multi-module Maven, Java 21** |

> **Design rule:** keep the *same module roles and conventions* as the reference (one gateway, one discovery, one config, N business services, shared parent POM), so anyone familiar with `red2n/home` can navigate StoreQL immediately.

### 1.4 Goals

1. Multi-tenant stock and store management across multiple stores per tenant.
2. Public customer storefront — any customer can register, browse, and purchase any in-stock product.
3. In-store POS sharing one source of inventory truth with the storefront.
4. Strict microservices: each service independently deployable, owning its own schema, with no shared database.
5. Gateway + discovery + centralized config, mirroring the `red2n/home` pattern on Helidon.
6. Event-driven consistency between services via Kafka.
7. Full observability: health, metrics, distributed tracing per service.

### 1.5 Non-goals (v1)

- Mobile native apps (storefront and admin are web-first; mobile is a later phase).
- Multi-currency / multi-country tax engines beyond a configurable tax-rate model.
- Marketplace with multiple independent sellers per storefront (each tenant runs its own storefront).
- Advanced demand forecasting / ML-driven replenishment.
- Shipping-carrier integrations (v1 supports pickup + flat/zone delivery only).

---

## 2. Users & personas

| Persona | Description | Primary channel |
|---|---|---|
| **Platform Admin** | Operates the StoreQL platform; manages tenants, plans, global config. | Admin console |
| **Tenant Owner** | Owns a business; full control over their stores, staff, catalog, pricing. | Admin console |
| **Store Manager** | Runs one or more stores; manages stock, purchases, staff scheduling. | Admin console |
| **Cashier / POS operator** | Rings up in-store sales, handles returns. | POS app |
| **Storekeeper** | Receives stock, adjusts inventory, manages batches. | Admin console |
| **Customer** | General public; browses storefront, places online orders. | Public storefront |

---

## 3. Architecture

### 3.1 High-level diagram

```
                    ┌───────────────────────────┐   ┌──────────────────────┐
                    │   Public Storefront (web) │   │  Admin Console (web) │
                    │   Customer browse + buy   │   │  Owner/Manager/Staff │
                    └─────────────┬─────────────┘   └──────────┬───────────┘
                                  │                            │
                            HTTPS │                      HTTPS │           ┌────────────┐
                                  └───────────┬────────────────┘           │  POS app   │
                                              │                            └─────┬──────┘
                                              ▼                                  │
                                ┌──────────────────────────────┐                │
                                │   API GATEWAY (Helidon MP)    │◄───────────────┘
                                │  routing · authN · rate-limit │
                                │  request-id · CORS · TLS term │
                                └───────────────┬──────────────┘
                                                │  discovers upstreams via
                                                │  ┌─────────────────────────┐
                                                │  │ DISCOVERY (Consul)       │
                                                │  └─────────────────────────┘
                                                │  ┌─────────────────────────┐
                                                │  │ CONFIG SERVICE (MP Config│
                                                │  │  + Git/Consul KV backend)│
                                                │  └─────────────────────────┘
                ┌───────────────┬───────────────┼───────────────┬───────────────┬───────────────┐
                ▼               ▼               ▼               ▼               ▼               ▼
         ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐
         │  iam-svc  │  │ tenant-svc│  │product-svc│  │inventory- │  │ pricing-  │  │ order-svc │
         │ authN/Z   │  │ stores    │  │ catalog   │  │   svc     │  │   svc     │  │ checkout  │
         │ JWT, OTP  │  │ staff     │  │ categories│  │ batches   │  │ price/tax │  │ orders    │
         └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘
               │              │              │              │              │              │
         ┌─────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐
         │payment- │  │ cart-svc  │  │ purchase- │  │ customer- │  │notification│ │reporting- │
         │  svc    │  │ storefront│  │   svc     │  │   svc     │  │   -svc     │ │   svc     │
         │ gateways│  │ cart      │  │ PO/supplier│ │ profiles  │  │ sms/email  │ │ analytics │
         └─────────┘  └───────────┘  └───────────┘  └───────────┘  └───────────┘  └───────────┘
                                                │
                ════════════════════════════════╪════════════════════════════════════
                          KAFKA event bus (MicroProfile Reactive Messaging)
                ════════════════════════════════╪════════════════════════════════════
                                                │
                          Each service → its OWN PostgreSQL schema/database
                          Cross-cutting → Zipkin/Jaeger (tracing) · Prometheus (metrics)
```

### 3.2 Strict-microservices principles (non-negotiable)

1. **Database-per-service.** No service reads another service's tables. Cross-service data is obtained via REST call or a Kafka event-carried projection.
2. **Independent deployability.** Each service is its own Maven module, its own container image, its own version.
3. **Gateway is the only public entry point.** No business service is exposed directly to the internet.
4. **Discovery-driven routing.** The gateway resolves upstream instances from the discovery registry — never hardcoded hostnames in app code.
5. **Centralized, externalized config.** No environment-specific values baked into images; pulled from the config service at startup and refreshable.
6. **Async by default for side effects.** State changes that other services care about are published as Kafka events; synchronous REST is reserved for read-time composition and request/response flows.
7. **Tenant isolation everywhere.** `tenant_id` is derived from the authenticated principal (JWT), never trusted from the request body or path.

### 3.3 Infrastructure services (the "platform" trio — from `red2n/home`)

| Service | Role | Helidon implementation |
|---|---|---|
| **gateway** | Single ingress. Routes `/{service}/**` to upstreams, terminates TLS, applies CORS, injects `X-Request-Id`, validates JWT, enforces per-route rate limits, strips internal headers. | Helidon MP app using JAX-RS + a reverse-proxy/edge filter; reads route table from config; resolves targets via discovery client. |
| **discovery** | Service registry. Every business service registers on startup and sends heartbeats; gateway and services look up healthy instances. | **Consul** server (containerized) + Helidon `helidon-integrations-config` / service-registration on the client side. *(Reference uses Eureka; Consul is the idiomatic Helidon choice. Eureka remains an acceptable alternative if parity with the reference is preferred.)* |
| **config** | Centralized configuration. Serves per-service, per-profile config from a Git repo or Consul KV. Services bootstrap from it and can hot-refresh. | Helidon MP app exposing config over HTTP backed by Git/Consul KV, consumed via MicroProfile Config sources on each service. |

### 3.4 Business microservices

Each business service: **Helidon MP**, owns a PostgreSQL schema, exposes REST under the gateway, publishes/consumes Kafka events, ships health + metrics + tracing.

| # | Service | Responsibility | Owns (data) | Key events published |
|---|---|---|---|---|
| 1 | **iam-svc** | Identity & access for both staff and customers. Registration, login (password + OTP), JWT issue/refresh, roles & permissions. | users, roles, permissions, sessions, refresh tokens | `UserRegistered`, `UserLoggedIn` |
| 2 | **tenant-svc** | Tenant lifecycle, stores/warehouses, staff assignment, plan/feature flags. | tenants, stores, staff↔store, feature flags | `TenantCreated`, `StoreCreated`, `FeatureToggled` |
| 3 | **product-svc** | Product catalog: products, variants, categories, brands, attributes, barcodes, images. | products, variants, categories, brands, media refs | `ProductCreated`, `ProductUpdated`, `ProductDelisted` |
| 4 | **inventory-svc** | Stock truth: batches, quantities, stock movements, expiry, low-stock alerts, reservations. Append-only movement ledger; FIFO. | inventory_batches, stock_movements, reservations | `StockReceived`, `StockReserved`, `StockDeducted`, `LowStock`, `StockExpiringSoon` |
| 5 | **pricing-svc** | Sell prices, price lists per store/channel, promotions/discounts, tax rates, price resolution. | price_lists, prices, promotions, tax_rates | `PriceChanged`, `PromotionActivated` |
| 6 | **cart-svc** | Storefront shopping cart for customers (guest + logged-in), cart-to-order handoff. | carts, cart_items | `CartCheckedOut` |
| 7 | **order-svc** | Order orchestration for **both** channels (online + POS). Creates order, reserves stock, captures payment, confirms; handles returns/refunds. Saga coordinator. | orders, order_items, order_status_history, returns | `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, `OrderFulfilled`, `OrderReturned` |
| 8 | **payment-svc** | Payment capture/refund via gateways (Razorpay/Stripe) + cash/card at POS. Idempotent. | payments, refunds, payment_intents | `PaymentCaptured`, `PaymentFailed`, `RefundIssued` |
| 9 | **purchase-svc** | Procurement: suppliers, purchase orders, goods receipt (GRN) → feeds inventory. | suppliers, purchase_orders, grn | `PurchaseOrderCreated`, `GoodsReceived` |
| 10 | **customer-svc** | Customer profiles, addresses, loyalty points, order history projection. | customers, addresses, loyalty_ledger | `CustomerProfileUpdated`, `LoyaltyAccrued` |
| 11 | **notification-svc** | Outbound SMS / email / push. Reacts to events (order placed, low stock, OTP). | notification_log, templates | — (consumer) |
| 12 | **reporting-svc** | Read-side analytics: sales, inventory valuation, top products, GST/tax reports. Builds materialized projections from events (CQRS read model). | report projections, denormalized read tables | — (consumer) |

> **Channel sharing:** `order-svc` is deliberately channel-agnostic. An online checkout (via `cart-svc`) and an in-store POS sale both create an order through the *same* `order-svc` API, so inventory, payments, and reporting behave identically across channels. The only difference is the `channel` field (`ONLINE` | `POS`) and the fulfilment type.

### 3.5 Request flow examples

**A — Customer places an online order**

```
Customer → Gateway → cart-svc (add items)
Customer → Gateway → order-svc.checkout
   order-svc ──REST──► pricing-svc (resolve prices + tax)
   order-svc ──event─► StockReserved request → inventory-svc reserves (FIFO)
   order-svc ──REST──► payment-svc (capture)  ── PaymentCaptured ──►
   order-svc  marks OrderConfirmed ──► publishes OrderPlaced
        ├─► inventory-svc converts reservation → StockDeducted
        ├─► notification-svc emails customer
        ├─► customer-svc accrues loyalty + updates history
        └─► reporting-svc updates sales projection
```

**B — Cashier rings up an in-store sale (POS)**

```
Cashier (POS) → Gateway → order-svc.checkout {channel: POS}
   (same pipeline as above; payment-svc records CASH/CARD; fulfilment = IMMEDIATE)
```

**C — Goods received from supplier**

```
Storekeeper → Gateway → purchase-svc.receiveGRN
   purchase-svc publishes GoodsReceived
        └─► inventory-svc creates batches → StockReceived
                 └─► reporting-svc updates inventory valuation
```

### 3.6 Eventing & consistency

- **Transport:** Kafka, via MicroProfile Reactive Messaging (Helidon Messaging connector).
- **Pattern:** Choreographed sagas for cross-service workflows (checkout, GRN). `order-svc` owns the checkout saga and compensations (release reservation, void payment on failure).
- **Delivery:** at-least-once; consumers are **idempotent** (dedupe on event id / order id).
- **Outbox:** services that must publish atomically with a DB write use a transactional outbox table drained to Kafka.
- **Schema:** event payloads versioned; a shared `events-contract` module holds the Avro/JSON schemas and generated POJOs (contract only — no business logic, so it does not violate database-per-service).

---

## 4. Functional requirements

### 4.1 Tenant & store management (tenant-svc)
- Create/suspend tenants; assign a subscription plan with feature flags.
- CRUD stores/warehouses under a tenant; geo + business hours.
- Assign staff to stores with roles.

### 4.2 Identity & access (iam-svc)
- Staff login via email+password; customer login via email/phone + password or OTP.
- JWT access + refresh tokens; refresh rotation.
- Roles: `PLATFORM_ADMIN`, `OWNER`, `MANAGER`, `STOREKEEPER`, `CASHIER`, `CUSTOMER`.
- Permission checks enforced at the gateway (coarse) and per service (fine).

### 4.3 Catalog (product-svc)
- Products with variants (size/color), categories (tree), brands, attributes, barcodes/SKU, images.
- Per-tenant catalog; products can be flagged `sellable_online` and/or `sellable_pos`.

### 4.4 Inventory (inventory-svc)
- Batch tracking with expiry; per-store stock levels.
- Append-only `stock_movements` (receive, sale, adjustment, transfer, return).
- FIFO deduction (`ORDER BY expiry_date ASC NULLS LAST, created_at ASC`).
- Stock **reservation** on checkout, converted to deduction on confirm, released on cancel/timeout.
- Low-stock and expiry alerts as events.

### 4.5 Pricing (pricing-svc)
- Sell price per product/variant, per store, per channel (online vs POS may differ).
- Promotions: percentage/flat, time-bounded, min-qty.
- Configurable tax rates; price resolution endpoint returns net + tax breakdown.

### 4.6 Storefront commerce (cart-svc + order-svc)
- Public, unauthenticated **browse** of online-sellable, in-stock products.
- Guest cart + authenticated cart; merge on login.
- Checkout: address, fulfilment choice (pickup / delivery), payment.
- Order tracking and history for the customer.

### 4.7 POS (order-svc, POS client)
- Fast item lookup (barcode/scan), add to sale, apply discount, take payment (cash/card), print/email receipt.
- Returns and refunds tied to the original order.

### 4.8 Purchasing (purchase-svc)
- Suppliers CRUD; purchase orders; goods receipt that creates inventory batches.

### 4.9 Payments (payment-svc)
- Online: Razorpay/Stripe intent → capture → webhook reconciliation.
- POS: cash/card recorded; idempotent.
- Refunds for returns.

### 4.10 Customers & loyalty (customer-svc)
- Profile, multiple addresses, loyalty point accrual/redemption, order history view.

### 4.11 Notifications (notification-svc)
- OTP, order confirmation, shipping/pickup ready, low-stock alerts to staff. Email + SMS + push.

### 4.12 Reporting (reporting-svc)
- Sales (by day/store/channel/product), inventory valuation, low-stock, tax/GST summary. CSV export.

---

## 5. Non-functional requirements

| Area | Requirement |
|---|---|
| **Availability** | Gateway + core commerce path (catalog, cart, order, payment, inventory) target 99.9%. |
| **Latency** | Storefront product list p95 < 300 ms; checkout p95 < 1.5 s end-to-end. |
| **Scalability** | Each service horizontally scalable; stateless except for their DB. Discovery handles N instances. |
| **Security** | TLS everywhere; JWT; secrets from config service / vault, never in images; OWASP top-10 hardening at gateway; per-tenant data isolation enforced in app + DB. |
| **Observability** | Every service exposes `/health` (liveness+readiness), `/metrics` (Prometheus), and emits OpenTelemetry traces to Zipkin/Jaeger with propagated `X-Request-Id`/trace context. |
| **Idempotency** | All event consumers and payment operations idempotent. |
| **Data** | Database-per-service; no cross-service joins; backups per DB; PITR on order/payment DBs. |
| **Config** | 12-factor; all env-specific values from config service; hot-refresh for non-secret values. |
| **Auditability** | `stock_movements`, `order_status_history`, `payments`, `audit_log` are append-only. |

---

## 6. Technology stack

| Layer | Choice |
|---|---|
| Language / runtime | **Java 25** (LTS; jars target release 21) |
| Service framework | **Helidon MP 4.x** (MicroProfile: Config, Health, Metrics, OpenAPI, JWT-Auth, Fault Tolerance, Reactive Messaging) |
| Build | **Maven** multi-module (shared parent POM) |
| API | JAX-RS (REST), OpenAPI 3 generated per service |
| Persistence | JPA (Hibernate or EclipseLink) + **PostgreSQL 16**, one DB/schema per service; Flyway for migrations |
| Gateway | Helidon MP edge service (reverse proxy + filters) |
| Discovery | **Consul** (Eureka acceptable for reference-parity) |
| Config | Config service over MicroProfile Config (Git/Consul KV backend) |
| Eventing | **Apache Kafka** via MP Reactive Messaging |
| Cache | Redis (sessions, cart, hot catalog reads) |
| Tracing | OpenTelemetry → **Zipkin/Jaeger** (reference uses Zipkin :9411) |
| Metrics | Prometheus + Grafana |
| Containerization | Docker; `docker-compose` for local, Kubernetes for prod |
| Frontends | Web admin console + public storefront (framework TBD — React/Next or Angular); POS web app |

---

## 7. Repository & module layout

Mirrors `red2n/home` (parent POM + `gateway`/`discovery`/`config` + business services), expanded for this domain:

```
storeql/
├── pom.xml                      # parent: Java 25 build / release 21, Helidon BOM, shared plugins
├── docker-compose.yml           # postgres, kafka, zookeeper, consul, redis, zipkin, prometheus, grafana
├── PRD.md
├── README.md
│
├── platform/
│   ├── gateway/                 # Helidon MP — single ingress
│   ├── discovery/               # Consul bootstrap / registration helpers
│   └── config/                  # Helidon MP — centralized config server
│
├── services/
│   ├── iam-svc/
│   ├── tenant-svc/
│   ├── product-svc/
│   ├── inventory-svc/
│   ├── pricing-svc/
│   ├── cart-svc/
│   ├── order-svc/
│   ├── payment-svc/
│   ├── purchase-svc/
│   ├── customer-svc/
│   ├── notification-svc/
│   └── reporting-svc/
│
├── shared/
│   ├── events-contract/         # Avro/JSON event schemas + generated POJOs (contract only)
│   ├── common-web/              # shared JAX-RS filters, error envelope, request-id, tenant context
│   └── common-test/             # test fixtures, Testcontainers helpers
│
└── frontends/
    ├── admin-console/           # owner/manager/staff web app
    ├── storefront/              # public customer web app
    └── pos/                     # in-store POS web app
```

Each service module follows the same internal layout:

```
<service>/
├── pom.xml
└── src/main/
    ├── java/.../<service>/
    │   ├── api/         # JAX-RS resources (thin controllers)
    │   ├── service/     # business logic
    │   ├── domain/      # JPA entities
    │   ├── repo/        # persistence
    │   ├── messaging/   # Kafka producers/consumers (MP Reactive Messaging)
    │   └── config/      # MP Config injection
    └── resources/
        ├── META-INF/microprofile-config.properties
        └── db/migration/   # Flyway
```

---

## 8. Suggested port map (local dev only)

> **This table is a local-development convenience, not a production contract.** The sequential `8001…8012` ports exist solely so all services can run side-by-side on one machine (docker-compose) without colliding. **In production (Kubernetes) every service listens on the same internal port (`8080`); they do not collide because each runs in its own pod/network namespace, and callers address them by DNS name + discovery, never by `host:port`.** See [§9 Deployment & startup ordering](#9-deployment--startup-ordering-production). Do **not** carry the `+1` scheme into production manifests.

| Service | Port |
|---|---|
| gateway | 8080 |
| config | 8888 |
| discovery (Consul) | 8500 |
| iam-svc | 8001 |
| tenant-svc | 8002 |
| product-svc | 8003 |
| inventory-svc | 8004 |
| pricing-svc | 8005 |
| cart-svc | 8006 |
| order-svc | 8007 |
| payment-svc | 8008 |
| purchase-svc | 8009 |
| customer-svc | 8010 |
| notification-svc | 8011 |
| reporting-svc | 8012 |
| PostgreSQL | 5432 |
| Kafka | 9092 |
| Redis | 6379 |
| Zipkin | 9411 |
| Prometheus | 9090 |
| Grafana | 3000 |

---

## 9. Deployment & startup ordering (production)

> **Key principle: in production, services must NOT depend on a hand-defined startup order.** The `8001 → 8012` sequence in §8 is a port convention for local dev, not a boot sequence. In a real cluster, services crash, restart, scale, and redeploy independently and continuously — you can never guarantee that (say) `pricing-svc` is up before `order-svc`. The industry standard is to **make every service start in any order and become *ready* only when its dependencies are reachable**, using health probes, retries, and orchestration — not a sequence.

### 9.1 Why not "start services 1→12 in order"

The dependency graph is a **mesh, not a line** (see §3.5 and the call/event maps): `order-svc` needs `pricing-svc` + `inventory-svc` + `payment-svc`; `cart-svc` needs `pricing-svc` + `inventory-svc` + `product-svc`; `customer-svc` reacts to `iam-svc` events; and so on. **No single linear sequence satisfies a mesh.** Forcing one creates deadlocks and brittle coupling. This is precisely why the industry moved from "ordered startup" to "start anything, gate on readiness."

### 9.2 The replacement for ordering: health probes + resilience

| Mechanism | Question it answers | Action | In StoreQL |
|---|---|---|---|
| **Startup probe** | "Has the JVM finished booting?" | Hold off liveness checks until boot completes (slow cold starts). | `GET /health/started` |
| **Liveness probe** | "Is the process alive/not deadlocked?" | If failing → **restart** the pod. | `GET /health/live` |
| **Readiness probe** | "Can it serve traffic *right now*?" (DB + Kafka + config reachable) | If failing → **remove from load balancer** (no traffic) but **do not kill**. | `GET /health/ready` |
| **Retry + backoff** | dependency not up yet | keep retrying instead of crashing | Helidon MP Fault Tolerance `@Retry` |
| **Circuit breaker** | dependency staying down | fail fast + fallback instead of hanging | Helidon MP `@CircuitBreaker` + `@Fallback` |

With these, **all 12 business services can be deployed simultaneously**. Each reports *not ready* until its own dependencies appear, then flips to *ready*. The orchestrator (Kubernetes) routes traffic only to ready instances. No human-maintained order required.

### 9.3 What *does* have an order: infrastructure layers (stages), not individual services

Ordering exists only **between coarse stages**, enforced by **readiness gates**, deployed as separate steps in the CD pipeline / Helm hooks:

```
STAGE 0 — Stateful infrastructure (must exist & be healthy first)
          PostgreSQL · Kafka (+ Zookeeper/KRaft) · Consul · Redis · Zipkin · Prometheus
                │  gate: each passes its own healthcheck
                ▼
STAGE 1 — Platform services
          config  →  discovery layer  →  gateway
                │  gate: config must answer before apps can read settings
                ▼
STAGE 2 — Database migrations  (run-once Jobs — NOT long-running services)
          Flyway migrate per service schema
                │  gate: migration Job completes successfully
                ▼
STAGE 3 — Business services  →  DEPLOYED IN PARALLEL, ANY ORDER
          iam · tenant · product · inventory · pricing · cart ·
          order · payment · purchase · customer · notification · reporting
                │  each readiness-gated on its OWN db + kafka + config
                ▼
STAGE 4 — Frontends  (storefront · admin-console · pos)
```

- **Migrations run as Kubernetes `Job`s (or Helm `pre-install`/`pre-upgrade` hooks), never inside the app's own startup.** This keeps schema changes explicit, one-shot, and auditable, and avoids N replicas racing to migrate the same DB.
- A service starting before its DB/Kafka is up is **normal and safe** — it simply stays *not ready* and retries.

### 9.4 Production runtime facts (vs the dev port table)

| Concern | Local dev (docker-compose) | Production (Kubernetes) |
|---|---|---|
| Ports | Unique per service `8001…8012` (avoid laptop collisions) | **All services on the same `containerPort` (8080)** — isolated per pod |
| Addressing | `localhost:<port>` | **DNS via k8s `Service`** (`order-svc.storeql.svc.cluster.local`) + Consul discovery; callers never use raw `host:port` |
| Scaling | 1 instance each | **N replicas**, autoscaled (HPA) on CPU/latency/lag |
| Ordering | `depends_on: condition: service_healthy` (see docs/ARCHITECTURE.md §17) | **Stage gates + readiness probes**, no per-service order |
| Migrations | run-once script before services | **`Job` / Helm hook**, gated before STAGE 3 |
| Config & secrets | `.env` / config service | config service + **k8s Secrets / Vault**, never in images |
| Rollout | restart all | **rolling update** (or blue-green/canary) per service, independently versioned |

> **Takeaway:** delete the idea of "service N starts after service N-1." In production you deploy *infrastructure → platform → migrations → all business services in parallel → frontends*, and **readiness probes + retries make the actual order irrelevant.**

---

## 10. Delivery roadmap (phased)

### Phase 0 — Platform foundation
- Parent POM, Helidon MP skeleton service template, `common-web`, `events-contract`.
- **gateway**, **discovery (Consul)**, **config** stood up and talking.
- `docker-compose` with Postgres, Kafka, Consul, Redis, Zipkin, Prometheus, Grafana.
- One trivial service registered + routed through the gateway end-to-end, with health/metrics/tracing proven.

**Exit:** a request through the gateway reaches a discovered service, config pulled centrally, trace visible in Zipkin.

### Phase 1 — Core back-office
- **iam-svc**, **tenant-svc**, **product-svc**, **inventory-svc**, **purchase-svc**.
- Staff can log in, create a tenant + stores, add products, receive stock (GRN → batches), see inventory.

**Exit:** GoodsReceived → StockReceived event flow works; inventory reflects receipts; tenant isolation verified.

### Phase 2 — Commerce core
- **pricing-svc**, **cart-svc**, **order-svc**, **payment-svc**.
- Online checkout saga: cart → price → reserve → pay → confirm → deduct stock.
- POS path through the same `order-svc`.

**Exit:** A customer completes an online purchase and a cashier completes a POS sale; both deduct the same inventory; payments captured; compensation on failure works.

### Phase 3 — Customer experience & ops
- **customer-svc** (profiles, loyalty, history), **notification-svc**, **reporting-svc**.
- Storefront frontend, admin console, POS app.

**Exit:** Order confirmations sent; loyalty accrues; sales/inventory reports populate from events.

### Phase 4 — Hardening & scale
- Rate limiting, circuit breakers (Fault Tolerance), outbox everywhere, autoscaling, Kubernetes manifests, load tests, security review.

---

## 11. Open questions / decisions to confirm

| # | Question | Default assumption |
|---|---|---|
| 1 | Discovery: **Consul** (Helidon-idiomatic) or **Eureka** (exact reference parity)? | Consul |
| 2 | Frontend framework for storefront/admin/POS? | TBD — React/Next assumed |
| 3 | Payment provider(s) for v1? | Razorpay (India) + Stripe |
| 4 | Tax model — single configurable rate or GST slab engine? | Configurable rate, GST-slab-ready |
| 5 | Fulfilment in v1 — pickup + flat/zone delivery only? | Yes |
| 6 | Hibernate vs EclipseLink for JPA? | Hibernate |
| 7 | Should `discovery` reuse Spring Cloud Eureka server image, or run pure Consul? | Pure Consul container |

---

## Appendix A — Mapping to `red2n/home` conventions

| `red2n/home` element | StoreQL equivalent |
|---|---|
| `gateway/` (Spring Cloud Gateway) | `platform/gateway/` (Helidon MP edge) |
| `discovery/` (Eureka server) | `platform/discovery/` (Consul) |
| `configuration/` (Spring Cloud Config) | `platform/config/` (MP Config server) |
| `usermanagement/` (business svc) | `services/iam-svc/` + others |
| Parent `pom.xml`, Java 21, modules | Parent `pom.xml`, Java 21, modules |
| Actuator (health/metrics) | MP Health + MP Metrics |
| Micrometer + Brave + Zipkin (:9411) | OpenTelemetry + Zipkin (:9411) |
| spring-kafka | MP Reactive Messaging + Kafka |
| Spring Data JPA + PostgreSQL | JPA (Hibernate) + PostgreSQL, DB-per-service |
