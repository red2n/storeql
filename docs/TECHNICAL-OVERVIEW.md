# StoreQL — Technical Overview

> **Who this is for:** business stakeholders evaluating StoreQL, and technical reviewers (partners, auditors, new engineers) who want the standards and architecture summarized in one place before diving into the deep docs. Each section pairs a plain-language explanation with the technical detail behind it.
>
> Deeper references: [README.md](../README.md) (full product tour) · [PRD.md](../PRD.md) (requirements & roadmap) · [ARCHITECTURE.md](ARCHITECTURE.md) (engineering reference) · [coding-standards.md](coding-standards.md) (enforced rules).

---

## 1. What StoreQL is, in one paragraph

StoreQL is a multi-tenant SaaS platform for running a retail business: stock, stores, and sales — online and in person — for as many independent businesses as want to use it. Each business (a "tenant") gets its own catalog, stock, staff, and customers, fully isolated from every other business on the platform, while sharing the same underlying application. A sale rung up at a till and a sale placed on the public storefront go through the exact same order, payment, and inventory logic, so a business never has to reconcile two systems that disagree about what's in stock.

## 2. Current status

Not a prototype or a design-phase project — a working platform:

| | |
|---|---|
| Business services | 12, each with its own database |
| Platform services | Gateway, service discovery (Consul), central config |
| REST endpoints | ~290 |
| Database migrations | 70+ (Flyway, version-controlled) |
| Frontend | 1 Flutter codebase, 4 experiences (storefront, POS, admin console, platform console) |
| Independent audits | 2 completed (API/UI standards review + security/correctness review) — see [ARCHITECTURE.md §18](ARCHITECTURE.md#18-security--hardening-posture) |

## 3. The standards that govern every change

These aren't aspirational guidelines — they're enforced on every service and checked on every code change. This is what "built to a standard" means concretely in StoreQL.

### 3.1 Data isolation between customers (multi-tenancy)

**In plain terms:** every business's data is walled off from every other business's, at the database level, on every single request — not just in the UI.

**Technically:** `tenant_id` is read once, from the authenticated JWT, at the gateway. It is never trusted from a request body, query string, or URL — a client cannot claim to be a different tenant. Every tenant-owned table carries `tenant_id NOT NULL` and every query on it filters by `tenant_id` as the *first* `WHERE` condition, backed by a composite index that leads with it. Identity headers arriving at internal services are stripped and re-stamped by the gateway, so a compromised or misbehaving downstream call can't spoof a tenant either.

### 3.2 One capability, one service, one database

**In plain terms:** inventory, orders, payments, and customers are handled by separate, independently deployable pieces of the system rather than one giant program — so a bug or outage in one area doesn't take the rest down, and each area can be scaled or changed on its own schedule.

**Technically:** strict microservices (database-per-service) on Helidon MP / Java 25. No service ever reads another service's tables or does a cross-service SQL join. Data needed from another service is fetched via its REST API or consumed from its Kafka events — the same rule a completely external integration partner would have to follow.

### 3.3 A single, controlled entry point

**In plain terms:** the outside world only ever talks to one address. Individual services are never directly reachable from the internet.

**Technically:** the API Gateway is the only public door. It handles authentication, rate-limiting, and routes to a Consul-resolved allowlist of the 12 business services. Services register themselves with Consul on boot; callers resolve a live address at call time rather than hardcoding `host:port` — so the system tolerates instances moving, restarting, or scaling without a config change.

### 3.4 Reliable messaging between services (no lost or duplicated events)

**In plain terms:** when something happens in one part of the system (e.g. "an order was placed"), other parts that care are told reliably — never silently dropped, and never accidentally processed twice (which would otherwise mean double-charging a customer or double-deducting stock).

**Technically:** state changes are published via a transactional **outbox** — the database write and the event write happen in the same transaction, so a crash between the two is impossible. All Kafka topics follow `storeql.<domain>.<event>` naming with `PascalCase` past-tense event names (e.g. `OrderPlaced`). Every consumer is required to be **idempotent** — processing the same event twice must produce the same effect as once.

### 3.5 Financial and data correctness

**In plain terms:** money is never approximated, and records that matter for accounting or audit are never edited or deleted after the fact — only appended to.

**Technically:** money is always `BigDecimal` in code / `NUMERIC` in Postgres, never a floating-point type — floating point cannot represent currency exactly and would eventually cause reconciliation errors. All timestamps are stored in UTC (`timestamptz`) and converted to a local timezone only at the UI edge. Tables like `stock_movements`, `order_status_history`, `payments`, `refunds`, and `loyalty_ledger` are append-only — a correction is a new row, never an `UPDATE` or `DELETE` on history. Money-affecting paths (refund caps, overpayment checks, return-quantity caps) run inside locked transactions rather than check-then-act, closing the door on concurrent double-spend.

### 3.6 Code quality and maintainability (SOLID)

**In plain terms:** the codebase is structured so that a change in one area (say, a new payment method) doesn't ripple unpredictably into unrelated areas, and any engineer — not just the original author — can safely extend it.

**Technically:** every service follows the same four-layer shape (`api/` → `service/` → `repo/` → `messaging/`), each with exactly one job — API layer never touches SQL, service layer never touches HTTP, and so on. Interfaces stay narrow (1–3 methods), infrastructure clients are injected via CDI rather than constructed inline, and status/type branching is pushed into domain objects rather than accumulating `if/else` chains. Full rules: [coding-standards.md §2](coding-standards.md#2-solid-principles).

### 3.7 SQL safety

**In plain terms:** database queries can't silently return the wrong data, leak another tenant's records, or wipe a table by accident.

**Technically:** `SELECT *` is forbidden — every query names its columns explicitly. Every query, including `UPDATE`/`DELETE`, must carry a `WHERE` clause; an unbounded `SELECT` against a tenant table or a bare `UPDATE table SET ...` is a rejected change, not a style nit. Full rules: [coding-standards.md §1](coding-standards.md#1-sql-rules).

### 3.8 A predictable, contract-first API

**In plain terms:** every API response looks the same shape no matter which service answers it, so integrators and the frontend team can build against a single, predictable pattern instead of learning 12 different conventions.

**Technically:** every response follows the envelope `{ "data": ..., "error": ..., "meta": { "requestId", "nextCursor" } }`. Errors carry correct HTTP status codes plus a stable machine-readable `code` (e.g. `INVENTORY_INSUFFICIENT_STOCK`) and never leak a stack trace or raw SQL. Pagination is cursor-based only (`?after=&limit=`, default 20 / max 100) — no page-number pagination, which doesn't hold up under concurrent writes. JPA entities are never returned over HTTP; DTOs are the only contract. Retryable writes (checkout, payment capture, stock receipt) require an `Idempotency-Key` so a network retry can't duplicate the effect.

### 3.9 Operability by default

**In plain terms:** every part of the system reports its own health and performance, so problems are visible before a customer notices — and the platform can restart or redeploy pieces in any order without a fragile, manually-sequenced startup.

**Technically:** every service exposes three health probes (started/live/ready — `ready` checks its database, Kafka, and config connectivity), Prometheus metrics, and distributed tracing. Services are designed to start in **any order** and gate on readiness (retry + circuit-breaker) rather than depending on a fixed boot sequence — the only real ordering is at the *stage* level: infrastructure → platform services → one-off DB migrations → all business services in parallel → frontends.

### 3.10 Tested, not just written

**In plain terms:** changes are verified against real dependencies before they're considered done, not just "it compiles."

**Technically:** unit tests cover `service/`-layer logic; Testcontainers-backed integration tests exercise the core flow against a real Postgres and Kafka (not mocks); ArchUnit rules mechanically enforce the layering rules in §3.6. SpotBugs, PMD, and a formatter run as quality gates in the build.

## 4. Technology stack

| Layer | Technology |
|---|---|
| Backend runtime | Java 25 (jars target release 21), Helidon MP 4.x (MicroProfile/CDI/JAX-RS) |
| Database | PostgreSQL, one schema per service, pooled via PgBouncer, migrated with Flyway |
| Messaging | Apache Kafka (KRaft mode), transactional outbox pattern |
| Service discovery | Consul |
| Config | Centralized config service (no secrets or env-specific values in code/images) |
| Observability | Prometheus + Grafana (metrics), Zipkin/Tempo (tracing), Loki (logs) |
| Frontend | Flutter (web + Android/iOS targets), Riverpod 2.x, go_router, dio — one codebase, 4 shells |
| Testing | JUnit 5, Testcontainers, ArchUnit, SpotBugs, PMD |

## 5. Security posture (summary)

Verified by two independent audits against the actual code, not just the design docs:

- No SQL injection — every query is a bound `PreparedStatement`.
- Tenant identity is never trusted from the client; only the gateway-verified JWT.
- Passwords use Argon2id with timing-equalized responses (resists account-enumeration timing attacks).
- Refresh tokens are opaque, hashed at rest, rotated, and reuse triggers family-wide revocation.
- Production secrets fail fast rather than silently falling back to repo-public development defaults.

Known open items are tracked transparently rather than hidden: API versioning (no `/v1` prefix yet) and machine-readable OpenAPI generation are documented as not-yet-done. Full detail: [ARCHITECTURE.md §18](ARCHITECTURE.md#18-security--hardening-posture).

## 6. Where to go next

- Want the product story (personas, workflows, screens)? → [README.md](../README.md)
- Want requirements, scope, and roadmap? → [PRD.md](../PRD.md)
- Want the engineering reference (service catalog, data flow, deployment model)? → [ARCHITECTURE.md](ARCHITECTURE.md)
- Want the exact enforced coding rules? → [coding-standards.md](coding-standards.md)
- Want the full REST API surface? → [API-GUIDE.md](API-GUIDE.md)
