# Feasibility: a system-health dashboard for the people who run a store's system

Status: analysis only, 6 Oct 2026. Not scheduled. The decided scope is in [intent/system-health-dashboard.md](../intent/system-health-dashboard.md) (CONFIRMED). Sizes below are estimates, not measurements.

## The question

An admin screen that shows, in real time, every request and response into the system, the failures, how many things wait for approval and how many completed, to tell how healthy the system is. Should it be a new service in Rust, or is that over-engineering?

**Answer: over-engineering.** A few Java changes are enough: a gateway filter, counters in Redis, a small count endpoint in the services that own the queues, and one Flutter screen.

## What the screen asks for, and where each answer lives

| Wanted | Source of truth | Answerable today? |
|---|---|---|
| Every request and response | The gateway (`ProxyResource`, the one door) | No. No access log, no per-route, per-status or per-tenant metrics, nothing stored. |
| Failures in the flow | HTTP errors at the gateway; dead outbox rows, dead-lettered Kafka events and dead webhooks in the services | No. They are written to logs only. Nothing lists the outbox dead letters, and a `.DLT` record carries no failure reason. |
| Pending approval | Six real queues (below) | Only one queue at a time. No total. |
| Completed successfully | HTTP status at the gateway; real completion is in order and payment events | Partly. There is no saga table or operation-level record. |

The six real waiting queues: purchase orders `PENDING_APPROVAL`, supplier payment runs `PROPOSED`, supplier invoices `FLAGGED`, accounting syncs `UNCERTAIN`, card refund dues `NEEDS_ATTENTION`, privacy requests `OPEN`. Returns outside policy, manual discounts and price overrides are a refusal at the till or an after-the-fact audit row, so there is nothing to count. A unified inbox is designed ([approvals](../intent/approvals.md), [background-work-and-stuck-items](../intent/background-work-and-stuck-items.md), both CONFIRMED) but not built.

## What already exists

- **Gateway:** one proxy mints `X-Request-Id` (after the filters have run), sees the upstream status and maps connect and read failures to 502, 503 and 504. Filters stamp the verified tenant, user, roles and stores. Redis already holds rate-limit and brute-force counters.
- **Operator tooling:** Prometheus, Grafana (one service-overview dashboard), Tempo and Zipkin, Loki, an OTel collector. These serve the platform operator; they carry no tenant or store label.
- **Live push:** MQTT over WebSocket from EMQX, web only, one connection per user, carrying notification-svc's shortage alerts only.
- **Admin app:** 33 admin screens in one deferred library, a fixed recipe for adding one, role gating by nav flag, an adaptive-layout standard.

## Options

| | A. Grafana only | B. Java inside the platform (chosen) | C. New Rust service |
|---|---|---|---|
| Tenant-scoped for a store admin | No | Yes | Yes |
| Business meaning (waiting work) | No | Yes | Yes |
| New runtime | No | No | Yes |
| Reuses `common-service` / `common-web` | n/a | Yes | No: Consul, health, JWT context, dedupe, outbox, problem details and migrations re-implemented |
| Supply chain (SBOM, provenance, cosign, verify script, Dependabot, vuln scan) | none | unchanged | new Cargo path through about eight files and CI |
| Quality gates (ArchUnit, PMD, checkstyle, SpotBugs, `check-golden-rules`) | n/a | apply | none apply; equivalents to be written |
| Rust toolchain on the build machine | n/a | n/a | absent; no Rust anywhere in the repo |

The workload is counters, a bounded queue and a read API, all I/O-bound. Rust's advantage (memory and latency) is not needed, and nothing in the repo shows the JVM to be unsuitable. A second backend language for one maintainer doubles the build, scan, signing and review paths. Revisit only if measured event volume demands a separate service, and then build it in Java with `scaffold-service`.

## Plan (four phases, in this order)

1. **Request counters at the gateway** (about 1 week). A request and response filter records only method, route pattern, status, error code, milliseconds, ids and tenant, on a bounded in-memory queue that drops successes when full and never blocks (virtual-thread rule). Successes become per-minute and per-hour counters in Redis per business; failures are kept in full for 24 hours, capped. Mint the request id before the filters so a refusal has one. Add a tenant-scoped read endpoint at the gateway. Never capture bodies.
2. **The screen** (about 1 week). One screen, three sections (live counters, failures, waiting work) and a detail sheet, polling every 5 seconds, a nav flag and a new permission.
3. **Waiting work** (1 to 2 weeks). A count read in reporting-svc that fans out by REST to the owning services. Some need work first: the purchase-order list has no status filter, and role gates differ per queue.
4. **Failures from the services** (about 1 week). Count dead outbox rows, dead-lettered events and dead webhooks per business. The outbox is cross-tenant by design with no tenant-led index, so this reads through a new small endpoint per service, not a query on the outbox from outside.

## Risks

- **Tenant isolation is the main correctness risk.** The business comes from the verified token only; every record is read back by it; hard cross-tenant tests are required. The gateway cannot tell which store a request named, so a store-held caller is not given the business's traffic in the first version.
- **Hot path.** The gateway sits on every request. Recording must be asynchronous and cheap; the flow-guard k6 suites cover this area and must stay green.
- **Personal data.** A failure record carries a user id and a route: keep it 24 hours and cover it in the business's erasure. No bodies, query strings or raw paths.
- **Trace context across Kafka is not confirmed** (no propagation found in the code; not tested at runtime). A trace-based "completed" would show false successes, so the first version defines completed as the HTTP result.
- **Observability gaps that do not block this:** local Prometheus scrapes 9 of 12 services; Tempo's span-metrics generator is configured with no processors; there is no Alertmanager, so alert rules fire to nowhere.
- **An approvals count can mislead:** a business that has not set purchase approval limits shows zero pending purchase orders because routing is off, not because nothing waits.

## Decisions taken (6 Oct 2026, the owner)

Shown to a permission OWNER and MANAGER hold by default and the owner can give to a custom role (the IT person or software vendor); all requests counted; failures kept 24 hours; Java, no new service; business-wide callers only in the first version; counts per minute for 24 hours and per hour for 7 days; polled every 5 seconds; waiting work counted from the six queues by REST.
