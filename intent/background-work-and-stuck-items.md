# Background work and stuck items: the operator's view of jobs, failed events and unpublished events, and a business's queue of orders that are stuck

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Oracle audit Phase 2 backlog line 14 "Operator surfaces: batch schedule and troubled transactions"; Oracle rows "Batch scheduling: hourly, ad-hoc, nightly; dashboard; hold, release, skip, restart" (POM), "Notifications on job failure", "Troubled transactions" (EICS ch. 9), "Error hospital / failed-message operator view" (RIB), "Held, pending, rejected-deposit and error orders queues" (OA ch. 3) |
| **Services** | common-service owns the shared blocks (job registry, failure sink, outbox diagnostics, the attention shape) that every service adopts · each service owns its own job state, failed events and attention items in its own schema · reporting-svc is the one read aggregator for the operator · notification-svc tells the operator's contact · order-svc, payment-svc, inventory-svc, purchase-svc and notification-svc own the business's stuck-item kinds · the platform console and the admin app show them |
| **Builds on** | the sweepers: order-svc `PendingOrderSweeper`, `ErasureSweeper`, `RetentionSweeper`, `EInvoiceTransportWorker`; customer-svc `LoyaltyExpirySweeper`, `RetentionSweeper`; inventory-svc `ExpiryAlertSweeper`, `FoodSafetyOverdueSweeper`, `ReservationSweeper`; product-svc `AssortmentSweeper`; pricing-svc `AppliedPriceSweeper`; tenant-svc `StoreTaskSweeper`, `SwitchingSweeper`; purchase-svc `AccountingSyncer`; notification-svc `RetentionSweeper` and the webhook retry timer; the shared `OutboxPublisher` in every service; `RetentionSweeperBase` and `RetentionSweepResource`; `KafkaEventLoop` (five attempts, then a record goes to `<topic>.DLT`, with nothing stored or shown) and `BaseKafkaConsumer`; every service's `outbox` (`published_at`); `KafkaConsumerRegistry` (failed starts only); [platform-administration](platform-administration.md) tiers, `PlatformTiers`, `PlatformAudit.record(tx, …)` and the audit-view pattern (each service serves a feed, reporting-svc merges at read, an unreachable source is named); [approvals](approvals.md)' pattern of each service serving its own list and the app merging; [exception-alerts](exception-alerts.md); [fulfilment-overrides](fulfilment-overrides.md) (`order_settings`: the unpaid-order and price-wait limits); [card-payments](card-payments.md) (review, capture-failed and refund-failed states); [webhook-delivery-controls](webhook-delivery-controls.md) |
| **Built in** | not yet built |

## Problem

The platform runs about fifteen timers and a dozen Kafka consumers, and nobody can see them. A sweeper that has stopped is found when a customer notices a stranded order. A consumer that fails five times writes a log line and sends the event to a topic nobody reads, so a business's state silently drifts from the events that should have updated it. An outbox row that Kafka refuses waits forever with no attempt count. The operator's tools are logs and a database shell. On the business side, an online order waiting for a price, an unpaid order, a payment under review and a delivery that was picked and never handed over are found only by remembering to filter the order list, and each is somebody's customer waiting.

## Outcome

- **An operator sees every scheduled job** of every service: when it last ran, how long it took, what it did (counts, never data), whether it worked, when it runs next, whether it is paused or stalled. They can **pause and resume it, run it now, or skip its next run**, each with a reason on the platform's trail.
- **An operator sees events a consumer could not process**: which consumer, which event type, which business, why it failed (a class and a stable code, never the message), how many attempts, and can **replay** it after the cause is fixed or **discard** it with a reason. Nothing is ever deleted.
- **An operator sees events not yet published** (the outbox): how many, how old, which topic, and the rows that have failed to send, and can push a publish now.
- **The operator is told** when a job is failing, an event has died or an outbox row is stuck, by the console and by email to the operator's contact, once per episode.
- **A business sees its own stuck orders as one queue** ("Needs attention"): oldest first, each opening the order, with the states that are the business's to resolve: waiting for a price, unpaid, under review, failed capture or refund, waiting to be picked, picked and not handed over, an accounting push nobody has answered.
- **Support reads all of the operator's view and changes nothing; the business never sees the platform's jobs or failed events.**

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the platform **operator** and **administrator** (act), platform **support** (read), the **store manager** and **owner** (their own queue), the **picker and cashier** (only through what a manager assigns).
- **Channels:** platform console (Operations), back-office admin (Needs attention), email to the operator's contact.
- **Scope:** jobs, failed events and the outbox are **platform-wide operational** matters: rows may name a business (id, name) but never carry its data. The attention queue is **per business, per store** (a store-held manager sees their stores' items and the business-wide ones only if held to none).
- **Roles that can write:** by platform tier. `SUPPORT` reads. `OPERATOR` runs, skips, pauses and resumes an ordinary job, replays and discards **one** event, and pushes an outbox publish. `ADMINISTRATOR`, with a fresh second factor, pauses an **essential** job (the outbox drain and the consumers). **Bulk** replay or discard needs `OPERATOR`, a fresh second factor and a reason. The business's queue is read-only for OWNER and MANAGER (acting on an item uses the order's own existing actions).
- **Sandbox tenant:** a sandbox's items appear in the platform's failed events and in its own queue like any business's; the jobs are shared with all businesses.

## Scope

- **In**, in build order; each slice is built and tested alone:
  1. **The job registry (common-service, then every service).** `Jobs`: a job registers its key, description, schedule (its existing interval key is kept, so deployments do not change) and body; the wrapper records each run, exposes the state, and applies pause and skip. Migrate the fifteen existing jobs, including the outbox drain. An ArchUnit rule stops a new scheduler being created outside `Jobs`.
  2. **The operator's jobs view (each service, reporting-svc, console).** Every service serves `GET /platform/jobs`; reporting-svc merges them (`GET /platform/operations/jobs`), naming any service that did not answer. The Operations screen.
  3. **Controls (each service).** Pause, resume, run now, skip next, for an ordinary job; essential jobs need `ADMINISTRATOR` and a fresh factor. Each on the trail, in the executing service's own transaction.
  4. **Failed events (common-service, every consuming service).** A failure sink in `KafkaEventLoop`, `failed_events` per service, list, replay, discard, bulk, metrics. The dead-letter topic stays as a backstop.
  5. **The outbox (common-service, every service).** Attempt count and last error class on unpublished rows, the stuck view, publish now, metrics.
  6. **Telling the operator (notification-svc, console).** `BackgroundWorkNeedsAttention`, the ops-contact email, the console banner, and Prometheus alert rules.
  7. **The business's queue (common-web, order-svc, payment-svc, inventory-svc, purchase-svc, notification-svc, app).** The shared `AttentionItem` shape, each owner's kinds, the app's merged Needs attention screen and two alert metrics.
- **Out, on purpose:**
  - **A general workflow engine or job builder.** The operator can control the jobs the platform has; they cannot define new ones or edit a schedule. A schedule change is a deployment value, as now.
  - **Editing, deleting or hand-publishing an outbox row.** A row Kafka rejects is a defect to fix in code; skipping it would lose an event the platform promised to deliver. An operator can publish now and see why it fails, nothing more.
  - **Showing or editing an event's payload.** The payload is a business's data. It is kept only so a replay can run it; no route returns it, so support and operators cannot read a business's trade through this door.
  - **An approvals key for pause, replay or discard.** They are reversible, idempotent and needed quickly during an incident; the tier, a reason, a fresh factor for the wide ones, the rate limit and the platform trail are the control (the approvals page keeps to actions that cannot be undone).
  - **A business seeing platform jobs or its own dead-lettered events.** They are the platform's operational state; a business is told of consequences in its own terms (the queue below, and the alerts it already has).
  - **A business-side "pause my sweeper".** A business does not run jobs; it sets the limits the jobs read (the unpaid-order limit, retention).
  - **Consumer lag and broker health.** Kafka's own metrics in Prometheus and Grafana stay the source; the console links to them and shows the platform's own counts.
  - **The gateway's timers** (`ScriptIntegrityMonitor` and the status poll). The gateway has no database, so it registers nothing; its monitor stays a metric.

## Data and flow

### The registry (slice 1)

- **Shared repeatable migration** `R__background_jobs.sql`, adopted per service through `ServiceSettings` as the approvals block is (`CREATE TABLE IF NOT EXISTS` in **that service's own schema**; the backup drill covers it; no function in a CHECK):
  - `job_state` (one row per job; mutable): `job_key`, `last_started_at`, `last_finished_at`, `last_outcome` (OK, FAILED, SKIPPED), `last_counts` (JSON of names and numbers only), `last_error_class`, `last_error_code`, `consecutive_failures`, `running_since`, `running_by`, `next_due_at`, `interval_seconds`, `paused` (bool), `paused_by`, `paused_reason`, `paused_at`, `skip_next` (bool), `skip_reason`.
  - `job_runs` (append-only): `id`, `job_key`, `started_at`, `finished_at`, `outcome`, `counts`, `error_class`, `error_code`, `trigger` (SCHEDULE or OPERATOR), `operator_id` (nullable), `reason`. Purged only by a technical keep (`storeql.jobs.runs-keep-days`), never per business.
  - Jobs are **platform-wide** (they loop the tenants) and carry no tenant column; a job that can run for one business (`tenantScoped`) takes a `tenantId` in run-now.
- **The registration** (`JobSpec`): `key` (`<service>.<name>`, lower kebab, unique), `description` (one plain sentence), `intervalSeconds` (or a daily local-time rule for jobs like the basket analysis), `essential` (the outbox drain and the consumers), `runNow` (allowed), `tenantScoped`, `heartbeat` (a high-frequency job). The body returns `JobResult(counts)`: **named counts only** ("expired: 12", "swept: 0"), so a run can never contain a business's data.
- **The wrapper** claims the run with a conditional update on `job_state` (`running_since` null or stale), so two replicas never run one job at once and a replica that loses the claim does nothing; runs the body; on an exception records FAILED with the exception's class and, for an `ApiException`, its stable code, never the message (which can hold SQL or data); increments `consecutive_failures`; on success clears it. **A high-frequency job** (`heartbeat`: the outbox drain, the e-invoice transport) updates its state in memory and flushes it at most once per `storeql.jobs.state-flush-seconds`, and writes a `job_runs` row only when it did work or failed, so a drain every second is not a row every second. A paused job's tick returns at once and writes nothing. A job whose `running_since` is older than `storeql.jobs.stall-factor` times its interval (or its last duration) is reported **STALLED**, never killed.
- **Metrics per job:** `job_last_success_timestamp_seconds`, `job_last_run_duration_seconds`, `job_runs_total{result}`, `job_interval_seconds`, `job_consecutive_failures`, `job_paused` (labels `service`, `job`; **no tenant label**).
- **The jobs that exist, and the ones wave 2 adds.** Registered in slice 1: `order.pending-orders`, `order.erasure`, `order.retention`, `order.einvoice-transport` (heartbeat), `customer.loyalty-expiry`, `customer.retention`, `inventory.expiry-alerts`, `inventory.food-safety-overdue`, `inventory.reservations`, `product.assortment`, `pricing.applied-prices`, `tenant.store-tasks`, `tenant.switching`, `purchase.accounting-sync`, `notification.retention`, `notification.webhook-retry` (heartbeat), and `<service>.outbox-drain` (essential, heartbeat) in every service. Each sweep a wave-2 page adds registers the same way, and this is a rule for those pages: the exception-alert sweeps, the approval-expiry sweep in each adopting service, scheduled-report delivery, the basket analysis, the platform daily counts, stored-value and loyalty expiry notices, the date-watch digest, transfer and acknowledgement overdue checks. **An ArchUnit rule, `SchedulersGoThroughJobsTest`,** fails a business service that creates a `ScheduledExecutorService` outside the shared `Jobs` class, so a job cannot exist that the operator cannot see. The existing `RetentionSweepResource` route keeps working and becomes run-now for the retention job.
- **Retryable writes (Idempotency-Key):** run now, replay, discard, bulk actions, and outbox publish now. Pause, resume and skip act on state by key and are safe to repeat.

### The operator's view (slices 2 and 3)

- **Each service serves** `/platform/jobs` (`GET` list and one; `POST …/{key}/run`, `/pause`, `/resume`, `/skip`), `/platform/consumers`, `/platform/failed-events`, `/platform/outbox`, from one resource base in common-service, mounted by adoption in `ServiceSettings`. Reads `SUPPORT`; the writes are in `PlatformTiers`, so a route added without a tier fails the build.
- **reporting-svc aggregates** (`GET /platform/operations/jobs|consumers|failed-events|outbox|summary`), fanning out to every business service (the list from Consul by its business-service tag; a service that does not answer, or has not adopted the block yet, is named in `meta.unavailable`), merging by next due time, then last failure, and forwarding the caller's platform token (the pattern of the audit view). It stores nothing. **The console sends every write straight to the owning service** through the gateway (`/api/v1/{service}/platform/…`), so the aggregator stays read-only and a down aggregator never blocks a fix. `GET /platform/operations/summary` gives the counts for the console badge: jobs failing, stalled, paused; dead events; oldest unpublished age.
- **Job list fields:** key, service, description, interval, next run, last run (when, how long, outcome), what it did (`counts`), consecutive failures, state (RUNNING, IDLE, PAUSED, STALLED, FAILING), the last error class and code.
- **Controls, in the executing service, each with a required reason and `PlatformAudit.record(tx, …)` in the same transaction** (`PlatformActionRecorded`, action `job.run`, `job.pause`, `job.resume`, `job.skip`, `event.replay`, `event.discard`, `outbox.publish`):
  - *Run now:* starts the body in that replica on a bounded worker and answers `202` with a `runId`; a job already running is `409 JOB_ALREADY_RUNNING`; a job flagged not runnable now is `409 JOB_NOT_RUNNABLE_NOW`; a run-now on a paused job is allowed (it is the operator's explicit act) and is recorded as an OPERATOR trigger.
  - *Skip next:* the next scheduled run is recorded SKIPPED with the operator and reason and then normal service resumes. *Pause / resume:* while paused no scheduled run happens; on resume the job runs once at its next tick, never a catch-up storm. An essential job's pause is `403 PLATFORM_TIER_INSUFFICIENT` below `ADMINISTRATOR`, and needs a fresh second factor (`403 PLATFORM_FRESH_FACTOR_REQUIRED`).
  - A platform write is rate limited (`storeql.jobs.actions-per-minute`; `429 PLATFORM_ACTION_RATE_LIMITED`).

### Failed events (slice 4)

- **A failure sink in `KafkaEventLoop`.** The loop calls an optional `FailureSink` on each failed attempt and on the dead letter; the service supplies one that writes `failed_events` in its own schema through its own datasource. The loop's behaviour (five attempts, seek back, `<topic>.DLT`) is unchanged; a sink that cannot write is logged and never blocks the loop.
- **`failed_events`** (per consuming service; mutable summary, unique `(consumer, topic, kafka_partition, kafka_offset)` so retries update one row): `id`, `tenant_id` (from the payload, null if unreadable), `consumer`, `topic`, `event_type`, `event_id` (null if the event had none), `payload` (kept only for replay, **never returned by any route**), `error_class`, `error_code`, `attempts`, `status` (RETRYING, DEAD, REPLAYED, DISCARDED), `first_failed_at`, `last_failed_at`, `dead_at`, `resolved_by`, `resolved_at`, `resolution_reason`. **`failed_event_log`** (append-only): every replay attempt (outcome, error class), discard and bulk action with the operator and reason.
- **Replay** runs the stored payload through the **same handler** in a fresh transaction, via a new `BaseKafkaConsumer.replay(topic, payload)`. Consumers are already idempotent (`processed_events`), so replaying an event that in fact went through is a no-op, and one that succeeds marks the row REPLAYED. A failure stays DEAD with the new error and answers `422 EVENT_REPLAY_FAILED`. A row not DEAD is `409 EVENT_NOT_DEAD`. **Discard** marks DISCARDED with a reason; the row and payload stay for the retention below. **Bulk** (`POST …/failed-events/replay` or `/discard` with a consumer and a since-time, at most `storeql.jobs.bulk-max` rows) needs the fresh factor and a reason.
- **What the operator sees:** consumer, event type, event id, the business (id and name), attempts, first and last failure, the error class and code in plain words, status. Not the payload, not the exception message.
- **Retention and erasure:** a resolved row is purged after a technical keep (`storeql.jobs.failed-events-keep-days`); a DEAD row is never purged. The payload holds the business's data, so `failed_events` is declared in each service's `TenantDataSpec` (erased on `TenantDataErasureDue`, left out of the owner's data export with the reason "an operational copy of events the business's own tables already hold") and its personal content in `SubjectDataSpec` as ERASE.
- **Metrics:** `consumer_dead_letters_total{service,consumer}`, `consumer_retrying{service,consumer}`, `failed_events_open{service}`.

### The outbox (slice 5)

- A shared repeatable migration adds to each service's `outbox`: `attempts` (default 0), `last_attempt_at`, `last_error_class`. The drain increments them on a send that was not confirmed, **inside its existing drain transaction**; a confirmed row is marked published as now. Nothing else about the outbox changes: no route edits or removes a row.
- **The view** (`GET /platform/outbox` per service): `pending`, `oldestAgeSeconds`, by topic, and a cursor list of rows with at least one failed attempt (id, event type, topic, business id and name, created, attempts, last error class) **without the payload**. **Publish now** (`POST /platform/outbox/publish`, OPERATOR) is run-now of the drain job. Metrics: `outbox_pending`, `outbox_oldest_age_seconds`, `outbox_failed_rows`.

### Telling the operator (slice 6)

- **`BackgroundWorkNeedsAttention`**, topic `storeql.platform.background-work-needs-attention`, through the affected service's own outbox where its DB is up: `eventId`, `service`, `kind` (JOB_FAILING, EVENT_DEAD, OUTBOX_STUCK), `subjectKey` (a job key, a consumer name or a topic), `count`, `firstSeenAt`. Raised once per open episode and re-armed when the job succeeds, the event is resolved or the row publishes. Consumer: notification-svc, in the platform's own words, by email to the deployment's operations contact (`storeql.platform.ops-contact`, from `.env`; none configured, nothing sent and the console banner is the notice). The technical thresholds (consecutive failures before JOB_FAILING, an outbox age) are deployment values.
- **Metrics are the primary path**, because a database outage stops both a job and its event: `infra/rules/background-work.yml` holds Prometheus alerts on `job_last_success_timestamp_seconds` against `job_interval_seconds`, on `consumer_dead_letters_total`, and on `outbox_oldest_age_seconds`, with the thresholds in the rule file. The console shows a banner from `/platform/operations/summary`.

### The business's own queue (slice 7)

- **One shape,** `AttentionItem` in common-web: `kind`, `id` (the order, payment, request or sync), `storeId` (nullable), `since`, `ageSeconds`, `overdue` (only where the business set a limit; never a threshold the platform invented), `summary` (safe words), `route` (where the app opens it). Each owning service serves `GET /admin/<service>/attention?kind=&storeId=&after=&limit=` (cursor, oldest first, default 20 / max 100) and `GET …/attention/summary` (counts by kind); the app's **Needs attention** screen fans out over the owners and merges, and says when one does not answer, as the approvals inbox does. Owners register their kinds in an `AttentionKinds` catalogue; a kind appears when the state it lists exists in the code (the card-payments kinds arrive with that page's slices).
- **The kinds** (owner; age counted from; overdue when):

| Kind | Owner | What | From | Overdue |
|---|---|---|---|---|
| `AWAITING_PRICE` | order-svc | orders waiting for a price | placed | past the business's price-wait limit ([fulfilment-overrides](fulfilment-overrides.md) `order_settings`), once set |
| `PENDING_UNPAID` | order-svc | online orders placed and not paid | placed | past the unpaid-order limit, once set (the sweeper cancels then) |
| `HANDOVER_PENDING` | order-svc | picked and packed, not yet dispatched or collected | fulfilled | none |
| `PARTLY_PICKED` | order-svc | part-fulfilled orders | last pick | none |
| `RETURN_REQUEST_WAITING` | order-svc | shopper return requests undecided ([shopper-returns](shopper-returns.md)) | requested | none |
| `EINVOICE_TRANSPORT_FAILED` | order-svc | a sales e-invoice the transport could not send | first failure | none |
| `PAYMENT_UNDER_REVIEW` | payment-svc | online payments held for review | held | none |
| `CAPTURE_FAILED`, `REFUND_FAILED` | payment-svc | a capture or refund that failed | failure | none |
| `AWAITING_PICK` | inventory-svc | confirmed online orders not yet in a completed wave (`awaiting_orders`) | confirmed | none |
| `ACCOUNTING_UNCERTAIN` | purchase-svc | pushes to the accounting package with no answer (`UNCERTAIN`) | push | none |
| `WEBHOOK_ENDPOINT_DISABLED` | notification-svc | an endpoint switched off after failures | disabled | none |

  The Oracle row's "rejected deposit" has no state here: a container-deposit return is a till event with no rejection; and its "error orders" are the failed-capture, failed-refund and uncertain-push kinds above (an order the router could not place still places at the area store, so no error state exists).
- **Who sees which:** OWNER and MANAGER through the shared `/admin` gate; a store-held manager sees the items at their stores (`TenantContext.reportStores`, `403 STORE_ACCESS_DENIED` for another store), an item with no store only if held to none.
- **Two new business alert metrics** (rows for the [exception-alerts](exception-alerts.md) catalogue): `orders.handover_pending_age` (order-svc, sweep-only: hours the oldest picked-and-not-handed-over online order has waited; subject store) and `wave_picks.unpicked_age` (inventory-svc, sweep-only: hours the oldest waiting order line has gone unpicked; subject store). The other kinds are covered by metrics already in the catalogue (`orders.awaiting_price_age`, `payments.review_open`, `payments.capture_failures`).
- **Retryable writes:** none (the queue is read-only). **Approvals action keys:** none new. **New error codes for the operator's side:** `404 JOB_NOT_FOUND`, `409 JOB_ALREADY_RUNNING`, `409 JOB_NOT_RUNNABLE_NOW`, `400 JOB_REASON_REQUIRED`, `403 PLATFORM_FRESH_FACTOR_REQUIRED`, `404 EVENT_NOT_FOUND`, `409 EVENT_NOT_DEAD`, `422 EVENT_REPLAY_FAILED`, `429 PLATFORM_ACTION_RATE_LIMITED`; and `403 PLATFORM_TIER_INSUFFICIENT` (existing).

## Money, time and limits

- **Currency:** none in the operator's view; the queue's summaries name amounts only through the order's own currency when a kind shows one.
- **Ledger postings:** none.
- **Dates:** UTC instants; the console shows the operator's zone and the queue shows a store's items with its own zone (`TenantProfiles.Stores.zoneOf`); an age is elapsed seconds, so no zone is assumed. Daily jobs judge their day in the store's zone as their own pages say.
- **Plan limits:** none. The technical keys above are deployment values, not business policy.

## Constraints

- **Golden rules:** database-per-service (each service's job, failure and outbox state is its own; reporting-svc reads by REST and stores nothing); tenant isolation (the platform sees a business's id and name but never its payload; the queue is per business); events through the outbox with an `eventId`; append-only for runs, the failure log and the outbox; thin resources; UUIDv7.
- **Existing behaviour is unchanged:** every job keeps its interval key and its body; `KafkaEventLoop` keeps its five attempts and `.DLT`; the outbox drain keeps claiming with `SKIP LOCKED`. The single-flight claim replaces "every replica runs every tick" for jobs; each existing sweeper is already idempotent, so this only removes duplicate work (proved per job by the registry tests).
- **The registry never blocks a business flow:** a failing state write is logged and the job still runs; a failure sink that cannot write never stops a consumer.
- **Tiers and audit:** every operator write is a platform action by tier with a reason on `platform_audit`, written in the executing service's own transaction ([platform-administration](platform-administration.md) slice 2).
- **Location-neutral:** no country, language or zone assumed; job descriptions are the platform's English, the queue's summaries are safe words the app renders in the user's language.
- **Backups:** the registry tables are plain; the drill notes them once at slice 1. A restore brings back job state and failed events as they were, which is correct (a restored DEAD event can still be replayed).

## Open questions

- [x] Who owns the registry? Recommended: a shared block in common-service that each service adopts, read by one aggregator, exactly as the audit view. → **common-service block per service, reporting-svc aggregates read-only, writes go to the owning service** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can the operator see an event's payload? Recommended: no; replay runs it without showing it. → **No route returns a payload** (industry standard: least privilege, under the user's standing instruction of 2026-09-30)
- [x] Does replay or pause need a second person? Recommended: no; a reason, the tier and the trail, and a fresh factor for the wide ones. → **As stated; no approvals key** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What is a platform screen and what is the business's? Recommended: jobs, failed events and the outbox are the platform's; stuck orders and their like are the business's. → **As stated** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What is "stuck" for a business, and when is it overdue? Recommended: list by state and age always; mark overdue only against a limit the business set. → **As stated; no threshold invented** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can an operator edit a schedule or add a job? Recommended: no; schedules are deployment values. → **No** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

**Slice 1: registry**
- [ ] A registered job's run writes state and one run row with named counts and no message text; a failure records the exception class (and an `ApiException`'s code) and increments `consecutive_failures`; a success clears it — `JobsIT.runsAreRecorded`, `JobsIT.failuresCarryNoMessage`
- [ ] Two replicas ticking together run the body once (single-flight); the loser records nothing — `JobsIT.singleFlight`
- [ ] A heartbeat job writes state at most once per flush interval and a run row only when it did work or failed — `JobsIT.heartbeatDoesNotFloodRuns`
- [ ] A job past its stall limit is reported STALLED and not killed — `JobsIT.stalledIsReported`
- [ ] Every existing sweeper is registered under its key with its interval unchanged and behaves as before — each service's existing sweeper test plus `JobsRegisteredIT`
- [ ] A scheduler created outside the shared wrapper fails the build — `SchedulersGoThroughJobsTest`
- [ ] Metrics carry no tenant label — `MetricsHaveNoTenantLabelTest` (with [operator-reporting](operator-reporting.md))

**Slices 2 and 3: view and controls**
- [ ] The aggregator merges every service's jobs, names one that does not answer and one that has not adopted the block, and stores nothing — `OperationsAggregatorIT`
- [ ] Pausing stops scheduled runs without rows, resuming runs once at the next tick (no catch-up storm), skip next records one SKIPPED with the operator, run now answers `202` and a second while running is `409 JOB_ALREADY_RUNNING` — `JobControlsIT`
- [ ] Every operator write needs a reason (`400 JOB_REASON_REQUIRED`), lands in `platform_audit` in the same transaction as the action, and is refused to `SUPPORT` (`403 PLATFORM_TIER_INSUFFICIENT`) — `JobControlsIT.tiersAndAudit`, `PlatformTiersTest.everyPlatformRouteHasATier`
- [ ] Pausing an essential job needs `ADMINISTRATOR` and a fresh second factor — `JobControlsIT.essentialNeedsAdministrator`
- [ ] Actions are rate limited (`429`) and a retry of run now with the same key starts one run — `JobControlsIT.rateAndIdempotency`
- [ ] Every business role and a shopper are refused every `/platform/**` route here, naming a business id; nothing changes — `OperationsIsolationIT`

**Slice 4: failed events**
- [ ] A consumer that fails five times leaves one `failed_events` row (RETRYING while it retries, DEAD after), the record still reaches `<topic>.DLT`, and the loop's behaviour is unchanged — `FailedEventsIT.deadAfterFiveAndStillDeadLettered`, `KafkaEventLoopTest` (existing)
- [ ] The row holds the error class and code and never the exception message; no route returns the payload — `FailedEventsIT.noMessageNoPayload`, `OperationsIsolationIT.noPayloadRoute`
- [ ] Replay runs the same handler once and marks REPLAYED; replaying an event that had gone through changes nothing; a failing replay stays DEAD with `422 EVENT_REPLAY_FAILED` — `FailedEventsIT.replay`, `ReplayIdempotencyTest` (common-test, per consumer)
- [ ] Discard needs a reason and keeps the row; bulk needs the fresh factor and is capped — `FailedEventsIT.discardAndBulk`
- [ ] A sink that cannot write never stops the loop — `FailedEventsIT.sinkFailureIsHarmless`
- [ ] Erasing a business erases its failed events; they are not in its data export and the register says why — `FailedEventsErasureIT`, `TenantDataSpecTest`

**Slice 5: outbox**
- [ ] A row that fails to send gets `attempts`, `last_attempt_at` and the error class inside the drain's transaction, a confirmed row is published, and no route edits or deletes a row — `OutboxDiagnosticsIT`
- [ ] The view answers pending, oldest age, by topic and the failed rows without payloads; publish now runs the drain job — `OutboxViewIT`

**Slice 6: telling the operator**
- [ ] A job failing past the technical count, a dead event and a stuck outbox row each raise one `BackgroundWorkNeedsAttention` per episode, re-armed on recovery — `BackgroundWorkNoticeIT`
- [ ] notification-svc emails the operations contact in the platform's words, and does nothing (no error) when none is configured — `BackgroundWorkNoticeHandlerTest`
- [ ] The Prometheus rule file parses and its named metrics exist — `RulesFileTest`

**Slice 7: the business's queue**
- [ ] Each kind lists its items oldest first with age, and `overdue` only when the business has set the limit — `AttentionIT.orderKinds`, `AttentionIT.overdueOnlyAgainstTheBusinesssLimit`
- [ ] payment-svc, inventory-svc, purchase-svc and notification-svc kinds list their items — `AttentionPaymentIT`, `AttentionInventoryIT`, `AttentionPurchaseIT`, `AttentionWebhookIT`
- [ ] A store-held manager sees their stores' items only; another store is `403 STORE_ACCESS_DENIED`; an item without a store shows only to a caller held to none — `AttentionIsolationIT.storeHeld`
- [ ] Another business's owner and manager, this business's cashier and storekeeper and a shopper get nothing of ours, naming our order ids and store ids — `AttentionIsolationIT.otherBusinessAndRoles`
- [ ] The app merges the owners' answers, names one that does not answer, and opens each item at its order — `needs_attention_test.dart`
- [ ] `orders.handover_pending_age` and `wave_picks.unpicked_age` raise once per store from their sweeps — `AttentionAlertIT`
- [ ] k6 `background-work-flow`: pause a job, run it now, a dead event replays; a stuck order appears in the queue and leaves it when resolved; flow guards stay green

## Screens

- **Platform console → Operations:** a summary strip (jobs failing, stalled, paused; dead events; oldest unpublished) and a banner when any is non-zero. **Jobs:** service, job, plain description, every, next run, last run and length, "did: expired 12", state chip; row actions **Run now**, **Skip next**, **Pause/Resume** (a reason box; a fresh-factor prompt for the wide ones; disabled with a plain reason for `SUPPORT`). **Failed events:** consumer, event type, business by name, attempts, why (words for the class and code), **Replay**, **Discard** and a bulk bar. **Outbox:** pending by service and topic, the oldest, the failing rows and **Publish now**.
- **Admin shell → Needs attention:** one list oldest first with a kind filter and store filter, each row "Order 1042, waiting for a price for 3 days" (names, not codes; ages through the shared formatter; overdue as a `StatusBadge` only where the business set a limit), opening the order; a count badge on the shell. Words not codes (`status_labels.dart`), adaptive per UI-GUIDE §7.2.

## Flow Tests entry

Area `platform`, flow file `target/flow-catalogue/platform/plat-background-work-and-stuck-items.json` (Oracle backlog line 14). The business queue is also listed in `target/flow-catalogue/online/` as `online-needs-attention.json` for the order kinds. Cases:
- **Happy:** a job runs and is seen; pause, run now, skip; a dead event is replayed; an outbox row publishes; a stuck order appears and clears — `JobsIT`, `JobControlsIT`, `FailedEventsIT.replay`, `OutboxViewIT`, `AttentionIT`, k6 `background-work-flow`.
- **Negative:** no reason, run while running, essential pause without the tier, replay of a non-dead event, a failing replay, a rate-limited burst — `JobControlsIT`, `FailedEventsIT`.
- **Override:** an operator's run now on a paused job; a bulk replay with the fresh factor; a business's own limit turning "overdue" on — `JobControlsIT`, `FailedEventsIT.discardAndBulk`, `AttentionIT.overdueOnlyAgainstTheBusinesssLimit`.
- **Isolation:** every business role and a shopper refused on `/platform/**`; no payload anywhere; another business and other roles on the queue; a store-held manager at another store — `OperationsIsolationIT`, `AttentionIsolationIT`.
- **Edge:** two replicas; a stalled job; a heartbeat job; a consumer with no tenant in the event; a sink that cannot write; an unadopted service in the aggregate — `JobsIT`, `FailedEventsIT`, `OperationsAggregatorIT`.
- **Audit:** every operator write is on `platform_audit` with tier, reason and the service; the failure log and run rows are append-only — `JobControlsIT.tiersAndAudit`, `FailedEventsIT.logIsAppendOnly`.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **This page owns three shared things every other page uses.** (1) The `Jobs` registry: **every sweeper or scheduled job any wave-2 page adds registers there** (`schedules.raise` in [product-groups-and-schedules](product-groups-and-schedules.md), the report sweep in [scheduled-reports](scheduled-reports.md), `basket-analysis`, the deal-earning sweep, the e-reporting worker and auto-transmit, `AwaitingPriceSweeper`, the case-target and segment-refresh sweeps, campaign runs, the stored-value and loyalty sweeps, the cart sweeper, the repricing sweeper), keeping its own interval key; its per-run once-only claim (a unique row) stays as each page designed it. (2) `FailureSink`, replay and outbox diagnostics. (3) `AttentionItem` and `GET /admin/<service>/attention`, whose order kinds absorb [customer-service-cases](customer-service-cases.md) slice 6's six.

- **2026-09-30, jobs are single-flight and seen.** Making every scheduler go through one wrapper is the only way an operator's list can be trusted to be complete; the ArchUnit rule keeps it so.
- **2026-09-30, no payload, ever, through the operator's door.** Replay needs the payload; showing it would turn a support tool into a way to read a business's trade. So it is kept and used, and returned by no route.
- **2026-09-30, the operator's list is read by one aggregator and written by the owner.** A down aggregator must never block a fix.
- **2026-09-30, no approvals key.** These actions are reversible and are used in incidents; the control is the tier, a reason, a fresh factor for the wide ones, the rate limit and the platform trail.
