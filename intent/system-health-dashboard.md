# System health: a live view of requests, failures and waiting work for the people who run the store's system

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user · 2026-10-06 |
| **Roadmap** | new: the user, "an admin screen to see the control flow in real time: each request and response, failures, how many are pending approval, how many completed" |
| **Services** | the gateway owns the request counters and the recent failures (it is the one door every request passes) · reporting-svc owns the "waiting work" read, asking each owning service for its own count through REST · every owning service answers a count of its own pending items · the admin app shows one screen |
| **Builds on** | `ProxyResource` (the one proxy, mints `X-Request-Id`), the gateway filters (`JwtAuthFilter`, `RateLimitFilter`, `TenantRateLimitFilter`, `BruteForceFilter`) and its Redis, `TenantContext` and `reportStores`, the admin shell and its nav flags, the live MQTT channel (web only); the real waiting queues: purchase orders `PENDING_APPROVAL`, payment runs `PROPOSED`, supplier invoices `FLAGGED`, accounting syncs `UNCERTAIN`, card refund dues `NEEDS_ATTENTION`, privacy requests `OPEN`; [approvals](approvals.md) and [background-work-and-stuck-items](background-work-and-stuck-items.md), which are confirmed and unbuilt |
| **Built in** | not yet built |

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
- **Owned by** reporting-svc: no table. It asks each owning service for the count of its own pending items (a small `GET …/pending-count`-style read added to purchase-svc, payment-svc and customer-svc, plus the status filters the purchase-order list lacks) and answers one list, naming any source it could not reach.
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

- [ ] Every request through the gateway is counted by route group and status class, including refused ones, and a refused request answers with a request id — `GatewayFlowRecorderTest`, `FlowRecordingIT`
- [ ] A failure appears with its request id, route pattern, status and code, and is gone after 24 hours — `FailureListTest.expiresAfter24Hours`
- [ ] No body, query string or raw path is stored — `FlowRecordingIT.recordsNoPayload`
- [ ] Another business's traffic and failures are never shown (staff of every role and a shopper get nothing, naming our ids) — `SystemHealthIsolationIT`
- [ ] A caller without the permission is refused `403 SYSTEM_HEALTH_NOT_PERMITTED`; a caller held to stores is refused `403 BUSINESS_WIDE_ONLY` — `SystemHealthAccessIT`
- [ ] A full recording queue drops a success and never blocks or slows the request — `GatewayFlowRecorderTest.fullQueueDropsSuccesses`
- [ ] Redis down: requests still pass and the screen says the figures are unavailable — `FlowRecordingIT.redisDown`
- [ ] Waiting-work counts equal the real queues, name an unreachable source, and respect each caller's stores — `PendingWorkIT`
- [ ] The screen shows counters, failures and waiting work at 390, 820 and 1180 widths and refreshes on its own — `system_health_screen_test.dart`
- [ ] The flow-guard k6 suites stay green — `flow-guard-comprehensive`, `flow-guard-runtime`

## Decisions

none yet.
