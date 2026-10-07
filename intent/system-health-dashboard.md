# System health: a live view of requests, failures and waiting work for the people who run the store's system

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the user · 2026-10-06 |
| **Roadmap** | new: the user, "an admin screen to see the control flow in real time: each request and response, failures, how many are pending approval, how many completed" |
| **Services** | the gateway owns the request counters and the recent failures (it is the one door every request passes) · reporting-svc owns the "waiting work" read, asking each owning service for its own count through REST · every owning service answers a count of its own pending items · the admin app shows one screen |
| **Builds on** | `ProxyResource` (the one proxy, mints `X-Request-Id`), the gateway filters (`JwtAuthFilter`, `RateLimitFilter`, `TenantRateLimitFilter`, `BruteForceFilter`) and its Redis, `TenantContext` and `reportStores`, the admin shell and its nav flags, the live MQTT channel (web only); the real waiting queues: purchase orders `PENDING_APPROVAL`, payment runs `PROPOSED`, supplier invoices `FLAGGED`, accounting syncs `UNCERTAIN`, card refund dues `NEEDS_ATTENTION`, privacy requests `OPEN`; [approvals](approvals.md) and [background-work-and-stuck-items](background-work-and-stuck-items.md), which are confirmed and unbuilt |
| **Built in** | 7 Oct 2026 · branch `fix/stack-verification-and-lock-order` · live: flow guards 124/79, `system-health-flow` 52, full k6 run 96 of 99 passing (the other three are explained in `k6/README.md`) · PR: see the branch |

## Problem

Nobody who runs a business's system can tell, at a glance, whether it is healthy. A request that fails leaves a log line on a machine only a developer reads. A refused request (a bad token, a rate limit, a locked login) leaves nothing a business can see. Work waiting for a person (a purchase order over a ceiling, a payment run, a flagged supplier invoice, a card refund a till could not put back) sits in six different screens with no total. The IT person or the software vendor who looks after the system for the owner finds out from a shopper.

## Outcome

- A person the owner trusts opens one screen and sees **how healthy the system is right now**: requests in the last minutes and hours, how many succeeded, how many failed, and the failure rate, for **every** request the business's people and systems send, grouped by what it does (orders, payments, stock, and so on), never by raw address.
- They see **each failure** from the last 24 hours with when, what it was doing, who sent it, the answer's stable code and a request id they can quote; nothing older.
- They see **how many things are waiting for a person**, by kind, each opening the screen that settles it.
- The screen updates on its own every few seconds. It never shows another business's traffic.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store manager, the owner, the IT person or the software vendor the owner has given access to. Not the cashier or storekeeper.
- **Channels:** back-office (admin shell). Online and POS traffic are counted; nothing changes for shoppers or tills.
- **Scope:** per business. Which people see it is the owner's choice: it is a permission that OWNER and MANAGER hold by default and the owner may give to a custom role (the IT person), or take from a manager.
- **Roles that can write:** none. The screen only reads; failures cannot be edited or cleared.
- **Sandbox tenant:** behaves the same; a sandbox's own traffic is counted for the sandbox only.

## Scope

- **In:** every request that reaches the gateway for the business, including the ones the gateway itself refuses (401, 403, 429, 413); counts by minute and hour; the last 24 hours of failures; counts of waiting work from the six real queues; the screen and its refresh.
- **Out, on purpose:**
  - **Request and response bodies, query strings and raw addresses.** They hold card data and personal data (the gateway has `CardDataGuardFilter` and `LogScrubber` for that reason). Only the method, the route's pattern, the status, the stable error code, the time taken, the ids and the business are recorded.
  - **Platform-wide health** (every business, the infrastructure, JVMs): that is the operator's, served by Grafana, Tempo and Prometheus, and by [background-work-and-stuck-items](background-work-and-stuck-items.md). A business never sees it.
  - **Replay, retry, discard or resolve from this screen.** It points to the screen that does it.
  - **The unified approvals inbox** and the failed-event and job views: confirmed in their own pages, built separately. This page reads only the six queues named above, and is replaced by the inbox's count when that is built.
  - **A new service, and Rust.** The counters live in the gateway and Redis, the waiting-work read in reporting-svc. Both are Java in the existing build.
  - **A failure history beyond 24 hours.**
  - **A flow trace per business operation** (a checkout traced across services and events). Trace context across Kafka is not confirmed, so "completed" means the HTTP request succeeded; the business outcome is read from the waiting-work counts and the existing screens.

## Data and flow

- **Owned by** the gateway: per-minute and per-hour counters in Redis per business (route group × status class), and a capped list of the last 24 hours of failures per business. A failure record holds the time, request id, route pattern, method, status, error code, the user and the milliseconds taken. Nothing is stored in Postgres and no new table exists.
- **Owned by** reporting-svc: no table. It asks each owning service for the count of its own pending items (`GET /admin/pending-work` in purchase-svc, payment-svc and customer-svc, each counted directly from that service's own tables, no list endpoint added) and answers one list, naming any source it could not reach. A count is bounded: each service reads at most 1000 rows of a queue (`PendingWorkCount.CAP`) and says so (`capped`); reporting-svc keeps the assembled report per business for a few seconds.
- **Needs from other services:** the counts above, by REST, each service answering from its own tables for the caller's business and stores (golden rule 1).
- **Events published:** none. The gateway records on a bounded in-memory queue and writes to Redis in the background; if the queue is full a success is dropped and counted, a failure is never dropped before it is queued.
- **Retryable writes** (Idempotency-Key): none; every call reads.
- **New error codes:** `403 SYSTEM_HEALTH_NOT_PERMITTED` for a caller without the permission. Existing `STORE_ACCESS_DENIED` and `BUSINESS_WIDE_ONLY` apply where a store-held caller asks for the whole business.

## Money, time and limits

- **Currency:** none shown.
- **Ledger postings:** none.
- **Dates:** times are instants in UTC and shown in the business's own zone by the app. Counters are per minute for the last 24 hours and per hour for the last 7 days.
- **Plan limits:** none.

## Constraints

- **Golden rules pressed on:** 3 (the business comes from the verified token, never a parameter), 1 (counts by REST, never a join), 9 (the filter is thin), the virtual-thread rule (no blocking work inside a monitor, a queue that drops rather than waits), 15.
- **The gateway is the hot path of every request.** Recording is asynchronous and cheap, and the flow-guard k6 suites must stay green.
- **Refused requests have no request id today** because it is minted after the filters; it must be minted first so a refusal can be listed and quoted.
- **A request does not name its store to the gateway.** A caller held to stores is therefore not given the business's traffic (see the open question).
- **Failure records carry a user id and a route, so they are personal data:** kept 24 hours and covered by the business's erasure.
- **Redis can be down:** the screen says the figures are unavailable; requests are never refused because of it.
- **The local Prometheus scrapes 9 of 12 services; this feature does not depend on it.**

## Open questions

- [x] Who sees it? Recommended: a permission, held by OWNER and MANAGER by default, grantable to a custom role. → **the manager, which may be an IT person or the software vendor, as the store owner prefers** (the user, 2026-10-06)
- [x] Which requests? Recommended: all. → **all requests; the screen tells how healthy the system is** (the user, 2026-10-06)
- [x] How long are failures kept? Recommended: 24 hours. → **24 hours** (the user, 2026-10-06)
- [x] Build it as a new service, in Rust? Recommended: no, a few Java changes. → **the few Java changes** (the user, 2026-10-06)
- [x] A store-held manager: what do they see? The gateway cannot tell which store a request was for. Recommended: only a caller held to no store sees the screen in the first version. → **business-wide callers only** (the user, 2026-10-06)
- [x] How long are the counts kept? Recommended: per minute for 24 hours, per hour for 7 days. → **accepted** (the user, 2026-10-06)
- [x] How does it update? Recommended: the app polls every 5 seconds (works on every platform); push later. → **poll every 5 seconds** (the user, 2026-10-06)
- [x] Waiting work in the first version: Recommended: count the six real queues by REST and say so, and swap in the approvals inbox's count when that is built. → **accepted** (the user, 2026-10-06)

## Acceptance

- [x] Every request through the gateway is counted by route group and status class, including refused ones, and a refused request answers with a request id — `GatewayFlowRecorderTest`, `FlowRecordingIT`
- [x] A failure appears with its request id, route pattern, status and code, and is gone after 24 hours — `FailureListTest.expiresAfter24Hours`
- [x] No body, query string or raw path is stored — `FlowRecordingIT.recordsNoPayload`
- [x] Another business's traffic and failures are never shown (staff of every role and a shopper get nothing, naming our ids) — `SystemHealthIsolationIT`
- [x] A caller without the permission is refused `403 SYSTEM_HEALTH_NOT_PERMITTED`; a caller held to stores is refused `403 BUSINESS_WIDE_ONLY` — `SystemHealthAccessIT`
- [x] A full recording queue drops a success and never blocks or slows the request — `GatewayFlowRecorderTest.fullQueueDropsSuccesses`
- [x] Redis down: requests still pass and the screen says the figures are unavailable — `FlowRecordingIT.redisDown`
- [x] Waiting-work counts equal the real queues up to the cap, name an unreachable source (null, never zero), and a caller held to stores is refused — `PendingWorkIT` (purchase-, payment-, customer- and reporting-svc)
- [x] A count never reads more than 1000 rows of a queue, and a count at the cap says "1000 or more" — `PendingWorkIT.aQueueOverTheCapAnswersTheCap`, `everyCountStopsReadingAtTheCap`, the Flutter `system_health_screen_test.dart`
- [x] Any number of open dashboards of one business cost one fan-out per period; a refused caller never sees a cached figure; another business never gets it; an unreachable source is never cached — `WaitingWorkCacheTest`, `PendingWorkCacheIT`
- [x] A request id comes back on a refusal and on a service's error, with the error's code in `X-Error-Code` — `ProblemsTest`, `FlowRecordingIT`, `k6/system-health-flow.js`
- [x] The screen's own reads (`/api/v1/system-health/*` and the waiting-work read) are not counted — `FlowRecordingIT`, `RoutePattern` tests, `k6/system-health-flow.js`
- [x] The screen shows counters, failures and waiting work at 390, 820 and 1180 widths and refreshes on its own — `system_health_screen_test.dart`
- [x] The flow-guard k6 suites stay green — `flow-guard-comprehensive`, `flow-guard-runtime`
- [x] End to end through the gateway on the real stack — `k6/system-health-flow.js`

## Decisions

- **Failed, client, ok** (`FlowOutcome`): a request *failed* when it ended in a 5xx or was refused (401, 403, 413, 429); any other 4xx is a *client* answer (a normal business answer such as 404, 409, 422) and is counted apart; the rest are *ok*. The failure rate is failed over total.
- **The request id is minted before every filter** (`RequestIdFilter`, priority 10) and a value a client sends is overwritten, never trusted; `ProxyResource` forwards the same id upstream. Every `/api/**` answer carries one, a refusal included.
- **Who a request belongs to** comes from request properties `JwtAuthFilter` sets from the verified token (or API key, or the guest storefront once `TenantStatusGate` has passed it), never from `X-Tenant-Id`/`X-User-Id`, which until then are whatever the client wrote. A request refused before its business is known (a bad token, the address rate limit) is counted for the operator under no business (`flow:m:-`) and is never returned to any business. **Accepted risk:** guest storefront traffic is attributed to the shop named in `X-Storefront-Tenant`, the same trust `TenantRateLimitFilter` already places in it, so an anonymous caller can add counts and 5xx/413/429 entries to an active shop's view by naming it; it leaks nothing.
- **What is stored** is the method, the route *pattern* (any path segment that is not lower-case words becomes `{id}`; at most twelve segments), the group (the routable service, `system-health` or `versions`, else `other`), the status, the stable code, the milliseconds, the request id and the verified business and caller. Never a body, a query string or a raw path. The code of a relayed error is read from an `X-Error-Code` header that common-web's `ProblemResponseFilter` now sets on every error answer; the gateway never reads or buffers a relayed body.
- **Redis**: `flow:m:<tenant>:<minute>` (24 h) and `flow:h:<tenant>:<hour>` (7 d) hashes of `<group>|<outcome>`; `flow:f:<tenant>` a sorted set (score = the instant in microseconds, so a cursor stays stable while new failures arrive) capped at 500 and trimmed to 24 h on write and read. One Lua script each way; a counter and its expiry are set in the same step.
- **The queue never blocks a request.** Bounded (10 000); a full queue drops a success or client answer and counts it, a failure evicts the oldest success; one background virtual thread writes batches; Redis away means records are discarded and the writer backs off, never that a request waits. `available: false` on the read.
- **The screen's own reads are not counted** — `/api/v1/system-health/*` and the waiting-work read through reporting-svc (versioned and the unversioned alias the app uses): with a dashboard open they would be most of a quiet shop's requests and make every window look healthy. Only that one reporting route; every other reporting-svc route is counted.
- **Erasure.** The gateway has no Kafka and no database, so it cannot hear a business's erasure. The failure entries (which hold a user id) expire by age within 24 hours; counters hold no personal data and expire in 24 hours and 7 days. A Kafka consumer in the gateway or a REST hook from tenant-svc would make it immediate; not built.
- **Access** is one rule, common-web `SystemHealthAccess` (the permission first, then a caller held to no store), used by the gateway from the verified headers and by reporting-svc and the three owners from their `TenantContext`. A shopper or a token with no staff role is refused `403 FORBIDDEN` by the shared role filter before the permission is looked at; staff below a manager and a manager narrowed away from it are `SYSTEM_HEALTH_NOT_PERMITTED`. Served on `/api/v1` alone.
- **Waiting work.** The built read is `GET /admin/pending-work` in each owning service (not a `pending-count` beside the list). A count that cannot be had is `null` and named, never zero. `approvalsRouted` is a deployment-wide setting (`storeql.purchase.approval.limits`), so the purchase-order note says so. **The cost of polling is bounded two ways:** each count reads at most 1000 rows (`count(*)` over `SELECT 1 … LIMIT 1000`, the status a literal so the partial index matches; a count at the cap is "1000 or more", `capped`), and reporting-svc keeps a business's assembled report 10 seconds (`cache-millis`; only if every source answered; one reading at a time per business; the access check runs before the cache). `count(*)` and `count(1)` are the same work in PostgreSQL; what costs is the rows visited, which the cap bounds.
- **Indexes** were folded into the `CREATE TABLE` of the migrations that make the tables (DEV rule): `idx_accounting_syncs_tenant_status`, `idx_card_refund_dues_tenant_state`, `idx_payment_runs_tenant_proposed` (partial, on the waiting status). A local database that ran the old migrations is reset (`docker compose down -v`), and `scripts/backup-drill.sh` is re-run.
- **The app** reads waiting work through the unversioned alias like every other call it makes, and maps each kind to the app's real routes (procurement tabs by `?tab=`, `/admin/integrations`, `/admin/privacy`; card refunds have no screen, so the tile is not a link). reporting-svc's `opens` carries the same routes and is advisory.
- **Dev gateway:** the local compose allows 15 failed sign-ins from one address (`GATEWAY_BRUTE_FORCE_MAX_FAILURES`; production keeps 5) because a successful sign-in clears only its own account's count, and `iam-crud` / `mfa-flow` check several wrong passwords.
