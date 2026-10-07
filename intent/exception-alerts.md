# Exception alerts: velocity and pattern checks that tell a manager, instead of a report they must remember to open

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on velocity, frequency and pattern checks · 2026-09-30 |
| **Roadmap** | new: the flow catalogue (artifact R391n2d2cV23sdKHKUnpGc) — ret-audit-trail (RET-40), ret-till-return, rpt-exceptions-report (RPT-26, RPT-27), discounts-and-promotions, container-deposit-returns (POS-121), inv-stock-adjustments-writeoffs, substitutions-and-short-lines, checkout, paying-online, plat-api-keys-and-sandbox (PLAT-114), stf-store-tasks-and-broadcasts (STF-316), plat-usage-metering-and-plan-limits (PLAT-313), cus-customer-records, plus the scan below |
| **Services** | notification-svc owns the rules, the alert inbox and the notifying · every service that owns a counted thing evaluates its own metrics and announces `ExceptionAlertRaised` (order-svc, inventory-svc, payment-svc, customer-svc, iam-svc, tenant-svc, notification-svc for its own) · common-service holds the shared rule reader and the pure window arithmetic · payment-svc's till close asks the inbox · tenant-svc answers who the managers of a store are |
| **Builds on** | order-svc `AuditTrailResource` and its six append-only logs, `ExceptionReportResource` and `journalCoverage`, `pos_void_log`; reporting-svc's sales facts; notification-svc's `Catalogue` (`Form.ALERT`), `Notifier.notifyOnce`, the `APP` channel (`notification_log` is the in-app feed), `AdminResource` feed, `RetentionSweeper`, and the existing store-addressed alerts (`ShortageAlert`, `FoodSafetyCheckFailed`/`Overdue`, `StoreTaskMissed`); tenant-svc `usage_alerts` (`Meters.Alert`, once per meter and period, platform view only) and the `StoreTaskMissed` sweep; the gateway `BruteForceFilter`; payment-svc `CashManagementService.zReport` (the end-of-day close); `TenantContext.reportStores` |
| **Built in** | not yet |

## Problem

Every one of these findings says the same thing. The data that would show a cashier refunding too much, a picker substituting the same order over and over, a till refunding container deposits all shift, or a storekeeper writing off a little stock every day is already recorded. But it is only shown on a report a manager must remember to open, after the money has gone. Today:

- **Returns, voids, discounts and no-sales** are counted per staff member on the exceptions report (`GET /admin/reports/exceptions`), pull-only. Nothing tells the manager when a cashier's count jumps. That is RET-40 and RPT-26.
- **Deposit refunds, stock adjustments, substitutions and repeated checkouts** cap only one call at a time (500 containers per refund, a price cap per substitute). Nothing looks at how many calls one person makes in a shift.
- **A store task missed once** does announce itself (`StoreTaskMissed`), but a task missed five days running looks the same as the first miss (STF-316).
- **A business nearing its plan limit** has a platform-side `usage_alerts` list and nothing for its own owner (PLAT-313).
- **An API key nobody used for months, suddenly used,** raises nothing (PLAT-114). **A customer lookup that pages through the whole customer list** is limited only by the gateway's general rate limit.
- **The end of the trading day** does not ask anybody to look at any of it (RPT-27).

The result is that loss prevention depends on a person remembering, and a tenant learns of abuse when the stock count or the till count is wrong.

## Outcome

- **A manager is told, in the app and by email, the moment a pattern crosses a line they drew.** They open an inbox of alerts, see what was counted, who it concerns and the records behind it, and acknowledge each with a note. It records who acknowledged and when.
- **The business draws the line.** For every metric it chooses the threshold and the window, and whether the rule runs at one store or all. A rule with no numbers does not exist; nothing runs until a business switches a rule on. The platform never applies a number silently. It can offer suggested starting rules a business chooses to adopt.
- **No sale, refund or stock move is ever slowed or refused by an alert.** An alert informs; blocking or a second person is the approvals page's job (`intent/approvals.md`).
- **One open alert per rule and subject.** A cashier who crosses a line and keeps going produces one alert whose count rises, not fifty messages.
- **Closing the day asks about them.** Where the business switches it on, a till cannot be closed while alerts at its store wait unacknowledged; the close screen lists them and the manager acknowledges there.
- **The owner is warned before a hard limit.** Usage crossing its warning point reaches the owner's inbox and email, not only the platform console.

## Who and where

- **Personas** ([PRD §2](../PRD.md)):
  - The **owner**, who chooses the rules, and the **store manager**, who receives, investigates and acknowledges alerts at their store.
  - The **cashier, picker and storekeeper** are the usual subjects and never see the alerts about themselves or others.
  - **Platform administrator:** sees no tenant alert content (isolation). Platform-side usage alerts stay where they are.
- **Channels:** back office (inbox, rules, and a badge in the admin shell), POS (the close screen), email. The storefront has none. Push to the manager's devices is used where the business already registered devices (existing `PushChannel`), otherwise in-app and email only.
- **Scope:** a rule runs for **one store or every store**; each store is evaluated on its own, and an alert names its store. Metrics whose subject belongs to no store (an API key, plan usage, a customer's lookups across stores) are **business-wide** alerts. A store-held manager reads alerts at their stores only (`TenantContext.reportStores`, a named store outside theirs is `403 STORE_ACCESS_DENIED`); the owner and a business-wide manager read all, including business-wide alerts.
- **Roles that can write:**
  - **Rules:** OWNER, and a MANAGER held to no store (a business-wide setting, the pattern of `storefront-settings`). Everyone else `403`.
  - **Acknowledge:** OWNER, or a MANAGER at the alert's store. Business-wide alerts: OWNER or a business-wide manager. A person cannot acknowledge an alert whose **subject is themselves** (`403 ALERT_SUBJECT_IS_CALLER`): a control an accused person can clear is no control.
  - **Read:** the same as acknowledge. Cashier and storekeeper `403` by the shared `/admin` path gate.
- **Sandbox tenant:** rules and alerts behave the same so a business can rehearse them; email is suppressed as for every sandbox notice (`TenantProfiles.Profile.sandbox()` in notification-svc, nothing leaves but in-app, logged `SUPPRESSED`).

## Scope

- **In:** numbered slices, smallest useful first; each is built and tested alone.
  1. **The alert contract and the inbox (notification-svc, common-events).** `ExceptionAlertRaised` on the shared contract; `exception_alerts` (open then acknowledged, once) and the append-only `exception_alert_occurrences`; list, one, acknowledge with note; the `EXCEPTION_ALERT` catalogue message (an ALERT form the business can reword); in-app to the managers of the store and email; the admin Alerts screen and badge. Testable with a hand-published event, so nothing else needs to exist first.
  2. **Rules and the shared evaluator (notification-svc, common-service).** `exception_rules` and its history; `GET/PUT/DELETE` rules; the metric catalogue endpoint; suggested starting rules with explicit adoption; common-service `ExceptionRules` (cached read, fails open: no rule readable means no alert) and pure `Velocity` (count, sum and share over a window; distinct count) plus `AlertRaiser` (the once-per-window guard and the outbox write). The Alert rules screen.
  3. **order-svc counted metrics.** Returns, no-receipt returns, voids, discounts (whole-sale and per line), price overrides, no-sales, deposit refunds, receipt copies, substitutions per order and per person, checkout attempts, `AWAITING_PRICE` age and expiries, shopper return requests, re-routes and forced cancels, age-check refusals, gift-card lookups that miss. Closes RET-40, RPT-26, the discount, deposit, substitution and checkout findings.
  4. **inventory-svc and payment-svc metrics.** Stock adjustments (count and value, per person and product), butchery loss, short picks, transfer discrepancies, till over/short, gift-card reload value, declined online payments and their rate, payments held for review, capture failures, standalone card tenders.
  5. **The end-of-day close (payment-svc, order-svc read).** The Z-report answers with the store's open alerts and the day's exception counts; the optional business setting that refuses a close until they are acknowledged. Closes RPT-27.
  6. **Business-wide and operational metrics.** tenant-svc: consecutive missed tasks, working-time breaches, usage warning to the owner (the only path for it). iam-svc: API key first use and use after dormancy. customer-svc: customer lookup enumeration and misses. notification-svc: webhook delivery failures and send failures. Closes STF-316, PLAT-313, PLAT-114, the customer-records finding and MKT-34.
- **Out, on purpose:**
  - **Blocking, throttling or holding a till or staff member on a threshold.** RET-40 asks for an alert; "or throttle" (RPT-26) would let a mis-set threshold stop a shop trading. Stopping something belongs to approvals (a second person, a ceiling), designed once on `intent/approvals.md`. An alert never changes what the person can do.
  - **Machine-learned or cross-tenant anomaly scoring.** A tenant's rules are its own numbers on its own data; the platform compares no tenants.
  - **Suggested numbers written on this page or applied by the platform.** The suggestion catalogue holds examples (config `storeql.alerts.suggestions.*`, reviewed by the product owner); a business adopts one by sending the numbers itself.
  - **Per-IP card-testing throttling.** Per-IP request limits stay the gateway's (`TenantRateLimitFilter`, `BruteForceFilter`, and the abuse counters of [storefront-trust](storefront-trust.md)); rules here are per shopper login, per order or per store. **There is no per-card subject:** the platform keeps only the provider's brand and last four digits ([card-payments](card-payments.md) Constraints), which identify no card, and the provider's own risk engine owns card-level patterns. (This replaces the earlier promise to add one when the card page gave a fingerprint; it gave none, on purpose.)
  - **Gateway login lockouts.** The brute-force filter has no database and no tenant rules; its "escalating lockout" finding (AUTH-212) stays with the auth page. The gateway counters are not turned into alerts here.
  - **A shopper's "points expiring" notice (LOY-28) and dunning channel escalation.** Notices to a customer or a billing address, not exceptions for a manager. They belong to loyalty and billing.
  - **PO approval waiting for a person (spend authority escalation), variances, bank-detail change, journal maker-checker, gift-card reload approval, large void approval.** Each is "needs a higher authority", the approvals page. Here a gift-card reload has only the velocity metric.
  - **Required variance note on a till close.** A field on the close request, not a pattern; `till.variance` only alerts when a close is far from expected.
  - **New-source detection for an API key.** It needs the caller's address in introspection; addresses are personal data in some places and the platform assumes none. First use and use after dormancy are enough for the finding.
  - **Editing an alert's subject or figures, or deleting one.** The inbox is a record; occurrences are append-only. Only acknowledgement changes an alert, once.
  - **A shift as a window.** Windows are rolling minutes/hours/days. "Per shift" is met by a window the business sets to its shift length; till sessions live in payment-svc and are not joined.

## Data and flow

### Who owns what, and why

- **The service that owns the counted thing evaluates its own metric.** order-svc counts returns from its own logs, inventory-svc adjustments from the movements ledger, payment-svc declines from its own payments. This obeys database-per-service (nobody reads another's tables to count) and keeps counting on data already indexed by `(tenant_id, actor, time)`. The owner then announces one common event and forgets it.
- **notification-svc owns the rules and the inbox.** Reasons, argued against the two alternatives:
  - *Not each producer:* one settings screen, one inbox and one "who is the manager of this store" answer, not seven copies that drift. A manager must not learn seven screens.
  - *Not tenant-svc:* it does know staff and stores, but it is the register of the business, and an inbox with acknowledgement is a workflow that would make it grow an unrelated area. It stays the answer to "who are the managers at this store" (`GET /admin/staff`, existing) and the owner of its own two metrics.
  - *notification-svc* already holds the in-app feed, the reword-able catalogue, email/push/SMS, retention and the sandbox rule; it already receives `ShortageAlert`, `FoodSafetyCheckFailed`, `StoreTaskMissed` in exactly this shape. The inbox adds state (open/acknowledged) that the feed never had, but it is the same delivery concern.
  - The cost: producers read the rules from notification-svc. They do it the way `Entitlements` and `FxRates` are read: a cached read (`ExceptionRules`, refreshed on a short configured interval and dropped on `ExceptionRuleChanged`), **fail-open** (rules unreadable means no rules, so no alert and no error on the sale). A missed alert is a logged warning, never a failed till.
- **Evaluation never rides the sale.** After the write commits, the service hands the subject (tenant, actor, store, metric) to a bounded in-memory queue; a worker recounts from the append-only log for that subject and window and, when the rule is crossed and none is open, raises the alert through the outbox. The queue drops oldest when full. A **periodic sweep** (interval is a technical config key, not a policy number) recounts every rule so a dropped hint is found within one interval, and metrics with no write to hang off (missed tasks, dormant keys, `AWAITING_PRICE` age, usage) are evaluated only by the sweep. The recount from the log is the truth; the hint is only speed. Evaluation never joins another service's data.
- **Cache invalidation.** A change to a rule publishes `ExceptionRuleChanged` (`storeql.alerts.exception-rule-changed`: `eventId`, `tenantId`, `metric`, `storeId`, `version`, `occurredAt`) so every `ExceptionRules` reader drops its cached copy at once; its minute of cache stays as the backstop, and it is fail-open as above.
- **De-duplication.** The producer keeps `alert_raised` (tenant, rule, subject key, last raised at, last observed) so it announces a subject at most once per window and re-announces only if an acknowledged alert is followed by a fresh crossing. The inbox is the authority: a partial unique index allows **one OPEN alert per (tenant, rule, subject key)**; a further event for it (`eventId` new) adds an occurrence and raises `observed`/`last_seen_at` without notifying again. A redelivered event does nothing (unique `event_id` in occurrences). After acknowledgement the next crossing opens a new alert.

### Rule model (notification-svc)

`exception_rules`, one per (tenant, metric, scope): `id` (v7), `tenant_id`, `metric` (key from the catalogue), `subject_kind` (one the metric allows: STAFF, STAFF_VARIANT (one person on one product, the pair as a derived key), SUPPLIER, PRODUCT, CAMPAIGN, CUSTOMER (a customer or a shopper login: the id the owning service holds), TILL, STORE, ORDER, KEY, TASK, ENDPOINT, BUSINESS), `store_id` (null = every store, evaluated per store; the store is checked through `TenantProfiles.stores`, never joined), `mode` (COUNT, SUM, SHARE, DISTINCT, MAX (the largest single value in the window) as the metric allows), `threshold` (numeric; a money threshold is in the home currency with `currency` stored), `window_minutes`, `min_sample` (for SHARE: the smallest number of the subject's sales before the share is judged, so one void in two sales is not 50%), `priority` (NORMAL or HIGH, which only changes the words and whether push is used), `enabled`, `notify_email` (default true once enabled), `created_by`, `updated_by`, timestamps. `exception_rule_history` is append-only (every create, change, switch off, with actor). **There is no row until a business creates one, and no numeric default anywhere:** `PUT` refuses a rule missing threshold or window `400 ALERT_RULE_INCOMPLETE`, a threshold that is not positive or a window outside the platform's technical bounds (config) `400 ALERT_RULE_INVALID`, a metric not in the catalogue or a mode/subject it does not allow `400 ALERT_RULE_UNKNOWN_METRIC`. **Suggested starting rules:** `GET /admin/alerts/rules/suggestions` lists example rules per metric, each labelled as an example; `POST /admin/alerts/rules/suggestions/{metric}/adopt` needs the numbers in the request body, so a rule exists only because a person sent numbers.

`exception_alerts`: `id`, `tenant_id`, `rule_id`, `metric`, `store_id` (null for business-wide), `subject_kind`, `subject_key` (an id, never a name), `observed`, `threshold`, `window_minutes` (all as at raising), `unit` (COUNT, MONEY with `currency`, PERCENT), `priority`, `status` (OPEN, ACKNOWLEDGED), `first_seen_at`, `last_seen_at`, `occurrences`, `acknowledged_by`, `acknowledged_at`, `note`, `evidence` (a small JSON list of `{kind, id}` pointing at the records: `RETURN`, `ORDER`, `ADJUSTMENT`, `TASK`…; ids only, so nothing personal is copied). `exception_alert_occurrences` (append-only): `alert_id`, `event_id` (unique), `observed`, `occurred_at`. Every table has `tenant_id` and an index starting `(tenant_id, …)`; an alert names ids only, and the app resolves names through `staffLoginsProvider`/`variantLabelsProvider`.

### The alert event

`ExceptionAlertRaised`, topic `storeql.alerts.exception-alert-raised`, through each producer's outbox on the transaction that records `alert_raised`. Fields: `eventId`, `tenantId`, `ruleId`, `metric`, `storeId` (nullable), `subjectKind`, `subjectKey`, `observed`, `threshold`, `windowMinutes`, `unit`, `currency` (when money), `evidence[]`, `occurredAt`. **Consumers:** notification-svc alone (writes the occurrence and, for a first one, the alert, then notifies); reporting-svc reads it only for its counts. It carries no name, address or amount of a person's.

### Notifying

For a first occurrence, notification-svc resolves recipients: for a store alert, the OWNER, business-wide managers and the MANAGERs assigned to that store (tenant-svc staff read, then iam-svc `staff-users` for the address); for a business-wide alert, owners and business-wide managers only. The **subject is left out** of recipients. In-app: one `notification_log` row per recipient (`APP` channel), keyed `(eventId, EXCEPTION_ALERT)` through `Notifier.notifyOnce`. Email through the existing channel when `notify_email`; push where the recipient has devices. The message is the catalogue's `EXCEPTION_ALERT` in the business's words and language, with the subject named by the app at read time (the email uses the name iam-svc gives). No second notice for an alert already open; an alert still OPEN one window after raising is repeated to the owner once (a business-wide reminder, not per message) so one that nobody read does not sit.

### Endpoints (notification-svc, `/admin/alerts`, management by path gate)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /admin/alerts?status=&storeId=&metric=&subjectKey=&after=&limit=` | OWNER, MANAGER | cursor `(last_seen_at, id)`, default 20 / max 100; store-held callers read their stores; `403 STORE_ACCESS_DENIED` |
| `GET /admin/alerts/{id}` | same | includes occurrences and evidence; another tenant's or another store's is `404` |
| `POST /admin/alerts/{id}/acknowledge` `{note}` | OWNER, MANAGER at the store | `Idempotency-Key`; `400 ALERT_NOTE_REQUIRED`; `409 ALERT_ALREADY_ACKNOWLEDGED`; `403 ALERT_SUBJECT_IS_CALLER` |
| `GET /admin/alerts/summary?storeId=` | same | `{open, byMetric}` for the badge and the close screen |
| `GET /admin/alerts/metrics` | same | the catalogue below (what can be counted, its allowed subjects, modes, unit) |
| `GET, PUT /admin/alerts/rules`, `DELETE /admin/alerts/rules/{id}`, suggestions and adopt | OWNER, business-wide MANAGER | `PUT` upserts by (metric, store); `DELETE` switches off and keeps history |
| `GET, PUT /admin/alerts/settings` | same | `closeRequiresAcknowledgement` (default false) |

Resources stay thin; logic in `service/`, DTOs both ways, no entity over HTTP.

### The metric catalogue

The rules screen and `GET /admin/alerts/metrics` are generated from this table (a catalogue class per owning service registered with common-service, and a test that every key here is in exactly one). "Subject" is what the rule may count per. Every metric counts in the window from the moment of the event; money is in the business's home currency.

| Metric key | Owning service | What is counted | Subject | Gap it closes |
|---|---|---|---|---|
| `returns.count` | order-svc | returns taken (`returns`) | staff, customer, till/store | RET-40, RPT-26, ret-till-return velocity |
| `returns.no_receipt.count` | order-svc | no-receipt returns | staff, store | RPT-26 (the no-receipt example) |
| `returns.value` | order-svc | sum refunded by returns | staff, customer, store | RET-40 |
| `voids.count`, `voids.share` | order-svc | voided sales; share of the subject's journalled sales (needs `min_sample`) | staff, store | RET-40 (void rate) |
| `discounts.count`, `discounts.share` | order-svc | discounts granted, whole-sale and per line ([line-discounts-and-price-overrides](line-discounts-and-price-overrides.md) writes both to `order_discounts` with a scope); share of sales | staff, store | discounts-and-promotions (per cashier per shift) |
| `price_overrides.count` | order-svc | prices typed over the resolved price at the till (`order_line_price_changes` kind `PRICE_OVERRIDE`) | staff, store | pos/ringing-up-a-sale (the velocity half of the line-price page) |
| `receipts.copies` | order-svc | receipt copies made or emailed (`order_receipts`) | staff, store | pos/completing-the-sale (reprints per cashier) |
| `no_sales.count` | order-svc | drawer opens without a sale (`pos_void_log` no-sale kind) | staff, store | RPT-26 report made proactive |
| `deposit_refunds.count`, `deposit_refunds.containers`, `deposit_refunds.value` | order-svc | container-deposit refund calls; containers refunded; deposit money refunded (home) | till/store, staff, customer | POS-121 |
| `substitutions.per_order` | order-svc | substitute and short-close adjustments on one order (`order_line_adjustments`) | order | substitutions-and-short-lines |
| `substitutions.count` | order-svc | substitutions and short-closes by one picker | staff, store | substitutions-and-short-lines (unusually high rate) |
| `checkouts.attempts` | order-svc | online orders placed, paid or not, by one shopper login | customer, store | online/checkout (rapid repeat orders) |
| `checkouts.unpaid_pending` | order-svc | PENDING online orders one shopper login holds at once | customer, store | online/checkout ([card-payments](card-payments.md)) |
| `orders.awaiting_price_age` | order-svc | oldest `AWAITING_PRICE` order (hours, sweep-only) | store | catalogue-mode-pricing (found by the scan) |
| `orders.price_wait_expired` | order-svc | `AWAITING_PRICE` orders cancelled by the business's own wait limit ([unit-pricing-and-listing-rules](unit-pricing-and-listing-rules.md) slice 5) | store, staff (the cashier who rang it) | catalogue-mode-pricing |
| `return_requests.count` | order-svc | shopper-made return requests ([shopper-returns](shopper-returns.md)) | customer, store (drop-off) | online/ret-online-return (return abuse) |
| `order_reroutes.count`, `order_force_cancels.count` | order-svc | manager re-routes and forced cancels of orders ([fulfilment-overrides](fulfilment-overrides.md); append-only logs `order_reroutes`, `order_force_cancels`) | staff, store | online/split-fulfilment, returns/void-order-cancel |
| `handovers.age_refusals` | order-svc | age checks refused at a collection ([proof-of-handover](proof-of-handover.md)) | staff, store | online/handover |
| `gift_cards.lookup_misses` | order-svc | gift-card lookups and redeem attempts by code that found no card | staff, store | loy-store-credit-and-gift-cards |
| `adjustments.count`, `adjustments.value` | inventory-svc | stock adjustments and write-offs from the movements ledger; sum of the cost of removals. A rule may count per person on one product (subject STAFF_VARIANT: "repeated adjustments by one person on one product") | staff, staff_variant, variant, store | inv-stock-adjustments-writeoffs |
| `yield.loss_over_expected` | inventory-svc | percentage points a butchery run's loss exceeded the template's expected loss, counted across runs (mode SUM or COUNT of runs over) | staff, store, template | inv-fresh-yield-butchery (the single big run is approvals `stock.yield-loss`) |
| `wave_picks.short_share` | inventory-svc | share of a picker's planned units left unpicked in completed waves (needs `min_sample`) | staff, store | online/picking-and-packing |
| `transfers.discrepancy_value` | inventory-svc | value at cost of transfer SHORT and DAMAGED discrepancies received ([transfer-discrepancies](transfer-discrepancies.md)) | store (receiving), route (a derived store pair) | trf-store-transfers (replaces the approval a receipt with a big loss was first given) |
| `till.variance` | payment-svc | absolute over/short at a till close: mode MAX (one close beyond the threshold), or SUM/COUNT over the window for repeated variances by one cashier (the metric [till-sessions-and-registers](till-sessions-and-registers.md) called `till.over-short-by-cashier`) | till/store, staff (who closed) | till-session-cash-control TILL-26 (the alert half; the second person on one large variance is approvals `till.close-variance`) |
| `gift_cards.reload_value` | payment-svc or order-svc (whichever holds `reloadGiftCard`, verified at build) | sum of reloads | staff, store | gift-cards-store-credit (velocity half; the ceiling is approvals) |
| `payments.declines` | payment-svc | declined or failed online payment attempts, counted from `payment_intent_attempts`. One rule per subject: a shopper login (customer) or one order (order); or per store | customer, order, store | paying-online (the card-testing signature; [card-payments](card-payments.md)'s `payment.declines.per-shopper` and `.per-order`) |
| `payments.decline_rate` | payment-svc | declined attempts as a share of attempts (needs `min_sample`); a card-testing run against the whole storefront | store, business | paying-online |
| `payments.review_open` | payment-svc | online card payments held for review and not yet decided | store, business | paying-online |
| `payments.capture_failures` | payment-svc | captures that could not be made (`PaymentCaptureFailed`) | store, business | paying-online |
| `card_tenders.standalone_count`, `card_tenders.standalone_share` | payment-svc | card tenders keyed from a standalone machine, per cashier; and their share of the store's card tenders (needs `min_sample`) | staff, store | tender-and-payment gap 3 ([card-payments](card-payments.md) slice 1) |
| `customer_lookups.count`, `customer_lookups.distinct`, `customer_lookups.misses` | customer-svc | POS phone/email/name lookups; distinct customers found; lookups that found nobody ([customer-identity](customer-identity.md) writes the `customer_lookups` log all three read) | staff, store | cus-customer-records (enumeration) |
| `api_keys.first_use`, `api_keys.dormant_use` | iam-svc | first use of a key; use after the business's own dormancy days | key | PLAT-114 |
| `tasks.missed_consecutive` | tenant-svc | consecutive trading days a task or list was missed at a store, from `StoreTaskMissed`'s sweep (the metric [workforce-rules](workforce-rules.md) called `tasks.missed-by-store`) | task (store) | STF-316 |
| `working_time.breaches` | tenant-svc | working-time breaches recorded from time entries (`working_time_breaches`, [workforce-rules](workforce-rules.md) slice 5) at a store or by one manager who published or corrected | store, staff | stf-workforce-shifts-and-time-clock |
| `store_days.unaudited_age` | payment-svc | hours since a store day closed and was not yet audited (MAX, sweep-only) | store | finance/sales-audit ([sales-audit](sales-audit.md)) |
| `audit_findings.count` | payment-svc | audit findings opened, per rule, per person the finding concerns or per store | staff, store | finance/sales-audit |
| `safe_adjustments.value` | payment-svc | sum of safe adjustments (absolute, home) ([store-cash-and-banking](store-cash-and-banking.md)) | staff, store | pos/till-session-cash-control |
| `deposits.difference_value` | payment-svc | absolute difference between a sealed bag and what the bank credited (home) | store | same |
| `gift_receipts.lookup_misses` | order-svc | gift-receipt codes looked up that found nothing (the guessing signature; [gift-receipts](gift-receipts.md)) | staff, store | pos/gift-receipts |
| `house_accounts.overdue_value` | customer-svc | sum overdue on house accounts against the business's own terms (sweep-only; nothing is held automatically) ([till-tenders-foreign-cash-and-accounts](till-tenders-foreign-cash-and-accounts.md)) | customer (the account holder), business | pos/tender-and-payment |
| `direct_deliveries.count`, `direct_deliveries.value` | purchase-svc | deliveries recorded with no order, and their value at the typed cost (home) ([shipping-notices-and-direct-deliveries](shipping-notices-and-direct-deliveries.md)) | staff, store | procurement/goods-receipt |
| `receipt_adjustments.count`, `receipt_adjustments.value` | purchase-svc | receipt corrections and their value (home) | staff, store | procurement/goods-receipt |
| `schedules.changes` | inventory-svc | pauses and changes to count or replenishment schedules ([product-groups-and-schedules](product-groups-and-schedules.md)) | staff, store | cnt-cycle-counts-stocktakes |
| `pullbacks.count`, `pullbacks.value` | inventory-svc | mass return instructions issued and their total cost ([mass-return-transfers](mass-return-transfers.md)) | staff | trf-store-transfers |
| `loss.unknown_share` | inventory-svc | share of a store's loss value that is unexplained (`UNKNOWN` kind; needs `min_sample`; [known-and-unknown-loss](known-and-unknown-loss.md)) | store | inv-stock-adjustments-writeoffs |
| `loss_reclassifications.count`, `loss_reclassifications.value` | inventory-svc | unknown losses explained as known by one person, and their value (home) | staff, store | same |
| `supplier_costs.changes` | purchase-svc | agreed-cost changes by one person ([supplier-deals-and-cost-changes](supplier-deals-and-cost-changes.md)) | staff, supplier | procurement/supplier-costs |
| `deals.writeoff_value` | purchase-svc | rebate and bill-back claims written off (home) | supplier, business | same |
| `invoice_variances.approved_value` | purchase-svc | price variance a person approved on supplier invoices (home; [purchase-price-variance](purchase-price-variance.md)) | staff, supplier | procurement/supplier-invoice-three-way-match |
| `einvoices.inbox_waiting_age` | purchase-svc | hours the oldest received e-invoice has waited for a person (sweep-only; [e-invoices-received](e-invoices-received.md)) | business | procurement/inbound-supplier-einvoice |
| `einvoices.transmission_failures` | order-svc | sales e-invoice transmissions that failed or were never settled ([e-invoices-and-e-reporting-for-sales](e-invoices-and-e-reporting-for-sales.md)) | business | sales invoicing |
| `einvoices.buyer_refusals` | order-svc | sales invoices the buyer refused or disputed | business | sales invoicing |
| `ereporting.periods_overdue` | order-svc | e-reporting periods ended and not yet accepted (sweep-only) | business | sales invoicing |
| `invoice_adjustments.count` | order-svc | price-adjustment credit notes and reissues by one person | staff, store | sales invoicing |
| `food_information.allergen_removals` | product-svc | versions of a declaration that remove a CONTAINS allergen from a variant live online ([food-information-online](food-information-online.md)) | staff, business | cat-safety-allergens-age-deposit |
| `reviews.submitted`, `reviews.one_star`, `reviews.reports` | product-svc | reviews written (per product and per shopper login); one-star reviews per product; distinct reporters on one review and reports by one person ([product-reviews-and-ratings](product-reviews-and-ratings.md)) | product, customer, business | online/storefront-browsing |
| `reviews.removal_share` | product-svc | share of one moderator's decisions that reject or hide (needs `min_sample`) | staff | same |
| `service_cases.overdue`, `service_cases.goodwill_value`, `service_cases.closed_without_action` | customer-svc | customer service cases open past a target; goodwill value issued under cases (home); share of a handler's resolutions closed without action (needs `min_sample`) ([customer-service-cases](customer-service-cases.md)) | store or business; staff | customer/cus-customer-records. **Prefix `service_cases.`, distinct from `investigations.`** |
| `segment_members.reads` | customer-svc | reads of a segment's member list by one person ([customer-segments](customer-segments.md)) | staff | customer/rpt |
| `campaign_sends.count`, `campaign_sends.unsubscribe_share`, `campaign_sends.failure_share` | notification-svc | messages a campaign sent in a window; share who unsubscribed; share that failed (needs `min_sample`; [campaigns](campaigns.md)) | campaign, business | customer/mkt-consent-and-marketing |
| `loyalty_bonus.points` | customer-svc | bonus points awarded under earning rules ([personalised-offers](personalised-offers.md)) | rule, staff, business | customer/loy-earning-and-redeeming |
| `investigations.opened`, `investigations.open_age` | notification-svc | investigation cases opened by one manager; days since the oldest still-open case was opened (sweep-only; [investigation-cases](investigation-cases.md)). **Prefix `investigations.`, distinct from `service_cases.`** | staff; business | customer/rpt-investigation-cases |
| `orders.handover_pending_age` | order-svc | hours the oldest picked-and-not-handed-over online order has waited (sweep-only; [background-work-and-stuck-items](background-work-and-stuck-items.md)) | store | online/handover |
| `wave_picks.unpicked_age` | inventory-svc | hours the oldest waiting order line has gone unpicked (sweep-only) | store | online/picking-and-packing |
| `usage.share` | tenant-svc | a meter's share of what the plan includes, at the thresholds `usage_alerts` already uses. **This is the only path for the usage warning:** tenant-svc raises `ExceptionAlertRaised` itself in the transaction that writes the `usage_alerts` row ([platform-administration](platform-administration.md) first proposed a separate `UsageThresholdReached` event; it is withdrawn) | business | PLAT-313 |
| `webhooks.delivery_failures` | notification-svc | webhook deliveries that failed, per endpoint (skips for no consent are not failures) | endpoint (business) | MKT-34 |
| `notifications.failures` | notification-svc | sends that ended `NOT_SENT` (no transport, or the provider failed), per channel ([notification-centre](notification-centre.md) named it `notification.failures`) | business | mkt-notification-delivery |

Three of these are not the business's own choice of number, and take no `exception_rules` row. `api_keys.first_use` is always on (it carries no policy) and `api_keys.dormant_use` follows the dormant period the owner sets on the key screen (iam-svc's `api_key_settings`, off until set, [api-key-controls](api-key-controls.md)); iam-svc detects both and raises the alert through the same event. `usage.share` uses the warning points tenant-svc's `usage_alerts` already raises and is announced through the same event so the owner reads it in the inbox; it cannot be switched off by a business (it is a notice about its own contract), and it invents no threshold. `tasks.missed_consecutive`'s count is the business's.

### One naming scheme, and the names other pages used (2026-09-30)

A metric key is `<thing counted>.<what about it>` in lower snake case, plural noun first (`returns.count`, `deposit_refunds.value`, `till.variance`), and appears **once** in the table above; the owner is the service that holds the log the metric counts. Pages written without sight of this one used other spellings. Each now uses the final key:

| Named elsewhere | Final metric (subject) |
|---|---|
| `payment.declines.per-shopper`, `payment.declines.per-order` (card-payments) | `payments.declines` (customer, order) |
| `payment.declines.rate`, `payment.review.open.count`, `payment.capture.failed.count` (card-payments) | `payments.decline_rate`, `payments.review_open`, `payments.capture_failures` |
| `payment.card.standalone.per-cashier`, `payment.card.standalone.share.per-store` (card-payments) | `card_tenders.standalone_count`, `card_tenders.standalone_share` |
| `order.placed.per-shopper`, `order.pending-unpaid.per-shopper` (card-payments) | `checkouts.attempts`, `checkouts.unpaid_pending` |
| `customer.lookup-misses` (customer-identity) | `customer_lookups.misses` |
| `giftcard.lookup-misses` (stored-value-lifecycle) | `gift_cards.lookup_misses` |
| `notification.failures` (notification-centre) | `notifications.failures` |
| `adjustments_by_actor_variant`, `yield_loss_over_expected` (inventory-screens) | `adjustments.count` (staff_variant), `yield.loss_over_expected` |
| `returns.requests-per-shopper` (shopper-returns) | `return_requests.count` |
| `handover.age-refusals` (proof-of-handover) | `handovers.age_refusals`; `handover.age-check-missing` is dropped: an order that needs a check cannot be handed over without one, so there is nothing to count |
| `order.reroutes-per-manager`, `order.force-cancels-per-manager`, `wave.short-pick-rate`, `order.substitutions-per-picker` (fulfilment-overrides, shopper-notices) | `order_reroutes.count`, `order_force_cancels.count`, `wave_picks.short_share`, `substitutions.count` |
| `till.over-short-by-cashier` (till-sessions-and-registers) | `till.variance` (mode SUM or COUNT per staff) |
| `deposit.refund-amount-per-session`, `receipt.reprints-per-session` (till-sessions-and-registers) | `deposit_refunds.value`, `receipts.copies` (a session is not a window; use a window set to the shift, see Out, on purpose) |
| `tasks.missed-by-store`, `workforce.working-time-breaches` (workforce-rules) | `tasks.missed_consecutive`, `working_time.breaches` |
| `orders.awaiting-price-overdue`, `orders.price-wait-expired` (unit-pricing-and-listing-rules) | `orders.awaiting_price_age`, `orders.price_wait_expired` |
| `line-discounts.per-cashier`, `price-overrides.per-cashier` (line-discounts-and-price-overrides) | `discounts.count`, `price_overrides.count` |

**Second pass (30 Sep evening): forty-two metrics added for the twenty-four new pages** (rows above, each in the scheme and with its owner). Two pages both used the prefix `cases.`: customer service cases now use **`service_cases.`** and investigation cases **`investigations.`**; `cases.handle` remains a permission. `gift_receipts.lookup_misses` follows the earlier `gift_cards.lookup_misses`; `receipts.copies` counts gift copies too. `orders.handover_pending_age` and `wave_picks.unpicked_age` are sweep-only, raised once per store.

**Named elsewhere and deliberately not an alert metric:**

- `online.checkout-failures-per-source`, `auth.registrations-per-source` ([storefront-trust](storefront-trust.md)) count by network address. The platform keeps no address and the gateway has no tenant rule store and no outbox (see Decisions), so these stay **gateway counters and Prometheus series** for the platform's operators, not rules a business writes. What a business can watch is per shopper login: `checkouts.attempts`, `payments.declines`.
- `webhook.unidentified-events` ([webhook-delivery-controls](webhook-delivery-controls.md)) is a producer regression: no business can fix an event the platform published without its id. It is the series `webhook_events_skipped_total{reason}` for operators and a contract test in `events-contract`, not a rule.

### Tenant isolation and retention

- Every table and every query starts `tenant_id = ?`. Rules and alerts of another business are `404`. A subject key in another business's alert is never resolved.
- A producer counts only its own tenant's rows; the event carries `tenantId` from the record being evaluated (a background evaluation has no JWT, so the tenant comes from the row, never from a request).
- Alerts hold ids and numbers only; erasing a customer leaves an alert with a subject id that resolves to "erased" (the same rule as `notification_log` where its `subject_id` is erased).
- **Retention:** acknowledged alerts and their occurrences are kept for the business's retention period (`GET /admin/tenant/retention`, read the way `RetentionSweeper` does; the platform picks no number); an OPEN alert is never purged. Rule history is kept as long as the rules.

### Retryable writes, error codes

Idempotency-Key on acknowledge and on rule `PUT`/adopt. New codes: `400 ALERT_RULE_INCOMPLETE`, `400 ALERT_RULE_INVALID`, `400 ALERT_RULE_UNKNOWN_METRIC`, `400 ALERT_NOTE_REQUIRED`, `403 ALERT_SUBJECT_IS_CALLER`, `403 STORE_ACCESS_DENIED` (existing), `404 ALERT_NOT_FOUND`, `409 ALERT_ALREADY_ACKNOWLEDGED`, `409 CASH_CLOSE_OPEN_ALERTS` (payment-svc, slice 5).

### The end-of-day close (slice 5)

The close is payment-svc's Z-report (`POST /admin/cash/till-sessions/{id}/close`). payment-svc asks notification-svc for `GET /admin/alerts/summary?storeId=` (short timeout, breaker, fail-open) and adds to the Z-report response `exceptions: {open, raisedToday, byMetric}`. The X-report carries the same so a manager sees it mid-day. When the business has set `closeRequiresAcknowledgement`, a close while `open > 0` at that store is `409 CASH_CLOSE_OPEN_ALERTS` naming the count; the manager acknowledges from the close screen and closes again. **Order of refusals at a till close** (this page and [till-sessions-and-registers](till-sessions-and-registers.md) both add one): not an open session or not the caller's (existing), then `400 TILL_VARIANCE_NOTE_REQUIRED`, then `409 CASH_CLOSE_OPEN_ALERTS`, and last the second person's approval `till.close-variance` (a `202`, the close waiting). If the inbox cannot be read, the close proceeds and the response says `exceptions: null` (never refuse the cash count for an unreadable inbox; the till's count is more important). This meets RPT-27: the day is reviewed as its alerts are acknowledged, with a note and a name; the exceptions report remains the pull view and its rows link to the alerts for the same actor.

## Money, time and limits

- **Currency:** money metrics (`returns.value`, `adjustments.value`, `till.variance`, `gift_cards.reload_value`) are in the business's home currency (`TenantProfiles`); a foreign-currency figure is translated through `FxRates`, and where there is no rate the metric is **not evaluated** and says so once on the rules screen (a threshold is never guessed). `NUMERIC(18,4)` and `BigDecimal` throughout, currency stored on the rule and the alert.
- **Ledger postings:** none.
- **Dates:** all instants UTC (`TIMESTAMPTZ`); windows are rolling from the moment of the event; the app shows times in the store's zone (`TenantProfiles.Stores.zoneOf`), never a zone assumed. "Trading day" counts (`tasks.missed_consecutive`, `raisedToday`) use the store's zone.
- **Plan limits:** none new. A plan may later cap the number of rules; if added it is an entitlement in `Plans.CATALOGUE` enforced by notification-svc, which owns them. Not now: no finding asked.

## Constraints

- **Golden rules pressed:** database-per-service (producers count their own rows and read the rules through a cached REST read; the inbox never reads their tables); tenant from the JWT (background evaluations take it from the row); events through the outbox and idempotent consumers (`event_id` unique, partial unique index on open alerts); append-only logs stay append-only (the counts only read them; occurrences and rule history are append-only); thin resources; UUIDv7 (`Ids.newId()`, `Ids.derived` for keys a producer makes for itself, `Ids.parse` for every id read, CHECK constraints on each new uuid and key column).
- **The sale path has no new dependency.** Evaluation is after commit, on a bounded queue, and the rule read fails open. A failing inbox, a full queue or an unreachable notification-svc must never change a sale's outcome or latency; a test proves a sale succeeds with notification-svc down.
- **Existing tenants are unchanged.** No rule exists until a business creates one; today's exceptions report, audit trail and `StoreTaskMissed`/`ShortageAlert` messages behave exactly as they do.
- **Cost.** The recount is an indexed count on `(tenant_id, actor, occurred_at)` per hint; every metric's log must have that index (a migration per service where missing, verified by an integration test's query plan check where practical). The sweep is bounded per interval by rules, not by rows.
- **No personal data in the event or the alert body.** Names are resolved by the reader who is allowed to see staff (a store-held manager sees staff at their stores only).
- **Backups:** new tables are plain, with no function-in-CHECK, so `scripts/backup-drill.sh` needs no special step; run it once at slice 1 to confirm.

## Open questions

- [x] Who owns the rules and the inbox: each producer, tenant-svc, or one place? Recommended: notification-svc. → **notification-svc owns rules, inbox and notifying; each producer evaluates its own metric and announces one common event** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should crossing a threshold block or throttle the person? Recommended: no, alert only; stopping is approvals. → **Alert only; blocking goes to `intent/approvals.md`** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What are the default thresholds and windows? Recommended: none; the business sets both, rules are off until set, suggestions are examples the business adopts by sending its own numbers. → **None; nothing applied silently** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Acknowledge with a note: required? Recommended: yes, one line, and never by the person the alert is about. → **Note required; `ALERT_SUBJECT_IS_CALLER` refuses the subject** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does the day close refuse while alerts wait? Recommended: a business setting, off until set; the close always reports the count; an unreadable inbox never blocks the cash count. → **Setting `closeRequiresAcknowledgement`, default off, fails open** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Rate or count for voids and discounts? Recommended: both, the rate only with a minimum sample the business sets, because the exceptions report warns its counts need `journalCoverage` as a denominator. → **COUNT and SHARE (`min_sample`)** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Where does the usage warning fit, since its thresholds are the platform's? Recommended: reuse the existing `usage_alerts` points, deliver to the owner through the same inbox, not switchable off. → **Announced as `usage.share` through the common event** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who is told? Recommended: the store's managers and business-wide managers and owners; owners only for business-wide alerts; never the subject. → **As stated under Notifying** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should the alert stay open forever if ignored? Recommended: no auto-close; one reminder to the owner one window later; acknowledged alerts follow retention. → **Never auto-closed; one reminder** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

Tests are named before building; tick with the real test and count when BUILT.

**Slice 1: inbox**
- [ ] An `ExceptionAlertRaised` for a store opens one alert and one in-app notice per store manager, the owner and business-wide managers, and none for the subject — `ExceptionAlertIT.raisedOpensOneAlertAndNotifiesTheStoresManagers`
- [ ] The same event delivered twice, and a second event for the same rule and subject while open, leave one alert, add an occurrence, and send no second notice — `ExceptionAlertIT.oneOpenAlertPerRuleAndSubject`, `ExceptionAlertHandlerTest.redeliveryIsIdempotent`
- [ ] After acknowledgement a fresh crossing opens a new alert — `ExceptionAlertIT.newCrossingAfterAcknowledgementOpensNewAlert`
- [ ] Acknowledging records who, when and the note; a second acknowledgement is `409 ALERT_ALREADY_ACKNOWLEDGED`; no note is `400 ALERT_NOTE_REQUIRED`; the subject acknowledging their own alert is `403 ALERT_SUBJECT_IS_CALLER` — `ExceptionAlertIT.acknowledge*`
- [ ] Another business's owner and manager, and this business's cashier and storekeeper, cannot list, read or acknowledge (404 or 403), naming our alert id and our store id; nothing changes — `ExceptionAlertIsolationIT`
- [ ] A manager held to store A does not see store B's alerts, and naming B is `403 STORE_ACCESS_DENIED`; a business-wide alert is invisible to them — `ExceptionAlertIsolationIT.storeHeldManager`
- [ ] Listing is cursor-paged newest first, default 20, max 100 — `ExceptionAlertIT.pagination`
- [ ] The EXCEPTION_ALERT message can be reworded per business (catalogue test) and honours the sandbox suppression rule — `CatalogueTest`, `ExceptionAlertIT.sandboxSendsNothingOut`
- [ ] Acknowledged alerts are purged after the business's retention, an open one never — `RetentionSweeperTest.exceptionAlerts`
- [ ] Widget: the Alerts screen lists, filters by store, opens evidence and acknowledges with a note; the admin badge shows the open count — `alerts_screen_test.dart`

**Slice 2: rules**
- [ ] No rule exists for a new business, and a sale-side recount of any metric raises nothing — `ExceptionRuleIT.nothingRunsUntilARuleIsSet`
- [ ] A rule without threshold or window is `400 ALERT_RULE_INCOMPLETE`; a non-positive threshold `400 ALERT_RULE_INVALID`; an unknown metric or a mode the metric does not allow `400 ALERT_RULE_UNKNOWN_METRIC` — `ExceptionRuleIT.refusals`
- [ ] Suggestions carry no applied number; adopting without numbers is refused, adopting with numbers creates a rule — `ExceptionRuleIT.suggestionsApplyNothingSilently`
- [ ] Every rule change writes an append-only history row with the actor; a manager held to a store cannot write (403) — `ExceptionRuleIT.historyAndRoles`
- [ ] A store on the rule that is not this business's is refused — `ExceptionRuleIsolationIT.foreignStore`
- [ ] `Velocity` counts, sums, shares (with `min_sample`) and distinct-counts correctly at the window's edges — `VelocityTest`
- [ ] `ExceptionRules` fails open when notification-svc is unreadable — `ExceptionRulesTest.failsOpen`
- [ ] Every catalogue key is in exactly one service's catalogue — `AlertMetricCatalogueTest`

**Slice 3: order-svc**
- [ ] A cashier's returns crossing a `returns.count` rule raise one alert; below it, none — `ExceptionAlertsOrderIT.returnVelocity` (RET-40, RPT-26)
- [ ] Voids and discounts by SHARE are not judged under `min_sample` — `ExceptionAlertsOrderIT.shareNeedsMinimumSample`
- [ ] A customer-subject `returns.count` rule counts across stores; a store-scoped rule counts each store apart — `ExceptionAlertsOrderIT.scopes`
- [ ] Deposit refunds by one till in the window raise `deposit_refunds.containers` — `ExceptionAlertsOrderIT.depositRefundVelocity` (POS-121)
- [ ] The same order substituted more than the rule allows raises `substitutions.per_order` naming the order — `ExceptionAlertsOrderIT.repeatedSubstitution`
- [ ] Repeated checkout attempts by one shopper raise `checkouts.attempts` and the checkout still succeeds — `ExceptionAlertsOrderIT.checkoutAttempts`
- [ ] An order awaiting a price past the rule's hours raises once from the sweep — `ExceptionAlertsOrderIT.awaitingPriceAge`
- [ ] A sale, return and void succeed and take no longer in the recorded path with notification-svc down — `ExceptionAlertsOrderIT.saleNeverWaitsOnAlerts`
- [ ] Another business's returns never count toward this one's rule — `ExceptionAlertsOrderIsolationIT`
- [ ] k6 `exception-alerts-flow`: rule set, cashier returns over the line, the manager sees and acknowledges the alert; the flow-guard suites stay green

**Slice 4: inventory-svc and payment-svc**
- [ ] Repeated small adjustments by one storekeeper cross `adjustments.count`, and their summed cost `adjustments.value` in the home currency — `AdjustmentAlertIT.velocityAndValue`
- [ ] A foreign-currency figure with no rate is not evaluated and is reported, never guessed — `AdjustmentAlertIT.noRateNoAlert`
- [ ] A close with a variance beyond `till.variance` raises an alert naming the till and who closed — `TillVarianceAlertIT`
- [ ] Declines beyond `payments.declines` for one shopper raise the alert; capture behaviour is unchanged — `PaymentDeclineAlertIT`
- [ ] Gift-card reloads over the rule's sum in the window raise `gift_cards.reload_value` — `GiftCardReloadAlertIT`
- [ ] Cross-tenant: another business's adjustments, closes and declines are never counted — the isolation ITs of each

**Slice 5: the close**
- [ ] The Z-report and X-report answer `exceptions {open, raisedToday, byMetric}` for the till's store — `TillCloseAlertsIT.reportsExceptions`
- [ ] With `closeRequiresAcknowledgement` on, a close with an open alert at the store is `409 CASH_CLOSE_OPEN_ALERTS`; after acknowledging it closes — `TillCloseAlertsIT.refusesUntilAcknowledged`
- [ ] With the setting off (default), a close is never refused; with the inbox down, it closes and answers `exceptions: null` — `TillCloseAlertsIT.failsOpen`
- [ ] Another store's open alerts do not block this store's close — `TillCloseAlertsIT.otherStoreDoesNotBlock`
- [ ] Widget: the close screen lists open alerts, acknowledges in place and closes — `till_close_alerts_test.dart`; k6 `flow-guard-runtime` still green

**Slice 6: business-wide**
- [ ] A task missed the rule's consecutive days at one store raises `tasks.missed_consecutive`, one alert for the run; a done task resets the run — `StoreTaskAlertIT` (STF-316)
- [ ] A meter crossing a usage warning point reaches the owner's inbox and email once per meter and period, and appears in the platform's list as before — `UsageAlertIT.ownerIsWarned` (PLAT-313)
- [ ] A never-used key's first use, and a use after the business's dormancy days, raise the alert; ordinary use does not — `ApiKeyAlertIT` (PLAT-114)
- [ ] A staff member looking up more distinct customers than the rule allows raises `customer_lookups.distinct`; the lookup still answers — `CustomerLookupAlertIT`
- [ ] A webhook endpoint's failures past the rule raise `webhooks.delivery_failures`; a skip for no consent counts none — `WebhookFailureAlertIT` (MKT-34)
- [ ] A business-wide alert is invisible to a store-held manager — `ExceptionAlertIsolationIT.businessWide`

## Decisions

- **2026-09-30, one inbox in notification-svc, evaluation where the data is.** Argued under Data and flow. The rejected shapes were an inbox per service (seven screens, seven definitions of "manager") and a central evaluator reading every service's logs (breaks database-per-service).
- **2026-09-30, alert only.** RPT-26 says "alert or throttle"; throttling is refused here because a mis-set number would stop a shop trading. A block or a second person is `intent/approvals.md`. This also replaces the "Return-fraud velocity alerts: their own item" line under `intent/return-controls.md` Out, on purpose: that item is this page (the old text stands there as history).
- **2026-09-30, the recount is the truth, the hint is speed.** The in-memory queue can drop; the periodic sweep and the recount from the append-only log mean a lost hint delays an alert and never loses one.
- **2026-09-30, usage and the platform's own thresholds.** `usage.share` reuses tenant-svc's existing warning points and is not switchable by the business: the platform is telling the owner about their contract, not the business drawing a line.
- **2026-09-30, gateway counters are not alerts.** The brute-force filter and per-tenant rate limits have no database, no tenant rule store and no outbox; alerts on them wait for the auth page.
- **2026-09-30, the till close is warned always, blocked only by choice.** The count of cash outranks the inbox: an unreadable inbox never refuses a close.
