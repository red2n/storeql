# Scheduled report delivery: the report arrives on its own, on the store's own clock, to someone who may still read it

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Readiness Review row 19.11 "Scheduled report delivery"; Oracle audit (Analytics, "Scheduled report delivery", Insights; the Review's 18.10) |
| **Services** | reporting-svc owns subscriptions, the rhythm, the send-time checks and the delivery record · notification-svc words and sends the email · tenant-svc answers what a person may read right now (one new service-to-service read) · iam-svc and tenant-svc announce a person leaving · the app gets a Subscribe action and a My subscriptions screen |
| **Builds on** | reporting-svc `/admin/reports/sales/*`, `/admin/reports/inventory/*`, `ReportingService`, CSV on every report; [report-integrity](report-integrity.md) (`@SensitiveRead`, the fixed sensitive catalogue, `report_access_log`, `basis`, the store's day, frozen months); `TenantContext.reportStores`; `TenantProfiles.Stores.zoneOf`; notification-svc `Notifier.notifyOnce`, the email channel, the sandbox suppression rule and the platform's-own-words messages (as password reset and the alert access notice); tenant-svc `GET /admin/staff`; `StaffRemoved` and the deny events of [platform-administration](platform-administration.md) slice 1; the job registry of [background-work-and-stuck-items](background-work-and-stuck-items.md) |
| **Built in** | not yet built |

## Problem

A manager wants Monday's sales for last week in their inbox on Monday morning, and the owner wants the month's figures on the first. Today each one must remember to sign in and open the report, or export a CSV by hand each time. Someone who does not remember does not look, and a problem is found late. What must not happen in the name of convenience: a sensitive report (margin, the VAT return, the trial balance) sent as an email that outlives the person's job, is forwarded, or is read by someone who has since lost the right to read it.

## Outcome

- **A person subscribes to a report with its filters** (the store or stores, the channel), chooses a rhythm (daily, weekly on a named weekday, monthly on a day or the last day) and a time of day, and receives it by email on time, **in the store's own day**.
- **What arrives is safe by class.** An ordinary report can carry a few headline figures if the business allows it, and always a link. A sensitive report arrives **only as a link**: the figures are read after sign-in, where the platform already records who read them.
- **Only what they may read at that moment.** Before every send the platform checks the person is still active, still holds the role and still holds the stores. If not, nothing is sent and the subscription stops.
- **A person who leaves stops receiving.** At once when their access is removed, and in any case at the next send.
- **The owner sees every subscription** in the business and can cancel any, and each delivery leaves a record.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **manager** and **owner** (subscribe for themselves), the **owner** (oversees and cancels), the **finance user** (link-only subscriptions to the ledger reports).
- **Channels:** back-office Reports screen (Subscribe on each report, **My subscriptions**), email. The storefront and POS have none.
- **Scope:** per person, per report, per store or set of stores.
- **Roles that can write:** a person creates, changes, pauses and cancels **their own** subscriptions for reports their role may read. Recipients are only the subscriber's own login address: nobody subscribes anyone else, and there is no free-typed address. The OWNER may cancel any subscription in the business (with a reason). A settings switch for figures in email is OWNER or a business-wide manager (`BusinessWide.require`). A manager held to stores subscribes only to their stores (`TenantContext.reportStores`): naming a store outside theirs is `403 STORE_ACCESS_DENIED`; naming none means "exactly the stores I hold, as they stand at each send".
- **Sandbox tenant:** behaves the same, but nothing leaves: notification-svc keeps its in-app copy and logs `SUPPRESSED` for the email.

## Scope

- **In**, in build order:
  1. **Subscriptions and the schedule (reporting-svc).** The deliverable catalogue, the subscription, the pure `Rhythm` (next due instant in a zone, with daylight-saving gaps and repeats, month ends), the sweep in the job registry, once-only deliveries, and the send-time access check. Delivery is a link plus title; the email is in the platform's own words. Ships the value with no figures at all.
  2. **Left when they leave (reporting-svc, iam-svc, tenant-svc).** Consume the removal and deny events to stop subscriptions promptly; the send-time check stays as the backstop and needs no event to be correct.
  3. **Figures in the message for ordinary reports (reporting-svc, notification-svc).** The business setting `figuresInEmail` (default off) and a per-subscription `includeFigures`; only for reports that the catalogue marks as figure-capable and never a sensitive one. First report: the sales summary.
  4. **Owner oversight and screens (reporting-svc, app).** The owner's list of all subscriptions, cancel with a reason, delivery history; My subscriptions and the Subscribe dialog.
- **Out, on purpose:**
  - **Attaching the report as a file (CSV or PDF).** A file leaves the platform's control the moment it is sent, is kept in mail archives and forwarded, and cannot be un-sent when access is withdrawn. The link needs a live sign-in. The report's CSV stays one click behind the link.
  - **Sending to someone else, or to a typed address (an accountant, a board member).** A recipient with no login cannot be checked at each send. Such a person gets a staff login with the role they need, then subscribes themselves.
  - **Figures for sensitive reports.** Margin, valuation, the VAT return, the tax summary, the trial balance, the nominal ledger, the audit trail and the exceptions report are the fixed sensitive catalogue of [report-integrity](report-integrity.md), and this page reads the same list, so a report added there cannot be emailed as figures here.
  - **Send now.** A button that emails a report at once is an on-demand read by another door and a lever to flood an inbox. The subscriber sees the next delivery's local date and time and the delivery history.
  - **Alert-driven delivery ("tell me when a number moves").** That is [exception-alerts](exception-alerts.md).
  - **Reports served by other services with figures** (margin, staff sales): they are link-only here, because their owning service holds the numbers and the send-time read of them would put a second copy of a sensitive read in reporting-svc.

## Data and flow

- **Owned by reporting-svc** (`tenant_id` first in every index; ids v7 with the common CHECKs):
  - `report_subscriptions`: `id`, `tenant_id`, `user_id` (the subscriber, from the JWT), `report_key` (from the deliverable catalogue), `filters` (JSON validated against the report's own schema: `storeIds` or `ALL_MINE`, `channel`), `cadence` (DAILY, WEEKLY, MONTHLY), `weekday` (1 to 7, ISO, chosen explicitly), `day_of_month` (1 to 28, or LAST), `at_local_time` (required; no default), `zone` (an IANA zone; **null means the store's own zone for a single-store subscription; required for a multi-store or business-wide one**, so no zone is assumed), `period` (YESTERDAY, LAST_7_DAYS, LAST_FULL_MONTH, MONTH_TO_DATE: each judged in that zone's calendar days), `include_figures`, `status` (ACTIVE, PAUSED, STOPPED_ACCESS_LOST, CANCELLED), `status_reason`, `next_due_at` (UTC), `created_at`, `version`.
  - `report_subscription_events` (append-only): create, change, pause, resume, cancel (with who, and the owner's reason), auto-stop (with why).
  - `report_deliveries` (append-only): `id`, `tenant_id`, `subscription_id`, `due_at` (unique with the subscription: once per due instant), `outcome` (ISSUED, HELD_ACCESS_LOST, HELD_UNREADABLE, SKIPPED_MISSED), `figures_included`, `at`. What the send finally did is in notification-svc's own log (delivered, suppressed, failed).
  - `report_subscription_settings` (per business): `figures_in_email` (default false), `updated_by`, `updated_at`.
- **The deliverable catalogue** (`ScheduledReports.CATALOGUE`, code, generated to `GET …/catalogue`): per report key its title, the app route, the filters it allows, the lowest role that may read it, whether it is **sensitive** (taken from the one fixed list in common-web, not repeated), whether it is **figure-capable**, and its owning service. A test proves every gate here equals the report endpoint's own gate, and that every sensitive key is link-only.
- **Needs from other services:** tenant-svc `GET /admin/tenant/staff/{userId}/access` (service to service, short timeout, breaker, **fails closed**: an unreadable answer holds the send): `{active, tier, customRolePermissions[], storeIds | null (all stores)}`. Store zones through `TenantProfiles.Stores.zoneOf`. Names and the person's address are resolved by notification-svc from iam-svc as for every notice.
- **The send-time check, in order,** for every due subscription: person active; role meets the report's lowest role; at least one store in scope (their current stores when `ALL_MINE`, the named stores intersected with their current ones); a sensitive report additionally keeps only the link. Any failure records `HELD_ACCESS_LOST` and stops the subscription (`STOPPED_ACCESS_LOST`, reason kept); an unreadable check records `HELD_UNREADABLE` and tries again at the next tick until the next due instant, when it is `SKIPPED_MISSED` and the following due instant proceeds.
- **The rhythm.** A sweep in the shared job registry ticks every minute (a technical interval) and takes subscriptions with `next_due_at` now or earlier, claimed `FOR UPDATE SKIP LOCKED` so two replicas never send twice. `next_due_at` is computed by pure `Rhythm.nextDue(after, zone, cadence, …)`: a local time that does not exist that day (the clocks go forward) is the first instant after the gap; one that occurs twice (the clocks go back) is the first occurrence; day 29 to 31 cannot be chosen, LAST is the month's last day. **Never a backlog:** a subscription whose service was down sends only the latest due instant, and each earlier one is `SKIPPED_MISSED`.
- **What goes out.** One event `ScheduledReportDue`, topic `storeql.reporting.scheduled-report-due`, through reporting-svc's outbox on the same transaction that writes the delivery row: `eventId` (v7), `tenantId`, `subscriptionId`, `deliveryId`, `userId`, `reportKey`, `linkPath` and `linkQuery` (the app route and the filters as ids, never a token), `periodFrom`, `periodTo` (store days), `figures[]` (label and text value, only when included), `occurredAt`. Consumer: notification-svc alone. It prefixes the deployment's app base address (the one the password-reset link uses), words `SCHEDULED_REPORT` in the platform's own words in the person's language, sends by email only to the subscriber's login address once per `(eventId, SCHEDULED_REPORT)` through `Notifier.notifyOnce`, and keeps the in-app copy. The link carries no credential: opening it needs a sign-in, and the report read then goes through the usual gates.
- **Relation to the access log ([report-integrity](report-integrity.md)).** **A delivered sensitive report is a read, and here it is made one at the moment it happens: the only thing delivered is a link, and following it is the read.** The report endpoint's `@SensitiveRead` filter writes the log entry for that read exactly as for any read. So no sensitive figure ever leaves the platform without a logged read, and an email that is forwarded shows nothing to a person who cannot sign in with the right to read. `report_deliveries` records that a link was sent; the access log records who read the figures. Where a business has set `figuresInEmail`, only ordinary reports carry figures, and ordinary reads are not logged by that page's design ("Logging every report read" is Out, on purpose there).
- **Retryable writes (Idempotency-Key):** create a subscription (a retry answers with the first). Change, pause, resume and cancel act on one row by id and version and are safe to repeat.
- **Endpoints (reporting-svc, `/admin/reports/subscriptions`):**

| Method and path | Roles | Notes |
|---|---|---|
| `GET /catalogue` | OWNER, MANAGER (and the storekeeper for the reports their role reads) | deliverable reports the caller may read |
| `POST /` | same | `Idempotency-Key`; `400 REPORTING_SUBSCRIPTION_INVALID`, `400 REPORTING_SUBSCRIPTION_FILTER_INVALID`, `400 REPORTING_SUBSCRIPTION_ZONE_REQUIRED`, `403 REPORTING_REPORT_NOT_PERMITTED`, `403 STORE_ACCESS_DENIED`, `409 REPORTING_SUBSCRIPTION_LIMIT`, `409 REPORTING_FIGURES_NOT_ALLOWED` |
| `GET /?scope=mine\|all` | own; `all` OWNER | cursor `(created_at, id)`, default 20 / max 100 |
| `GET /{id}`, `GET /{id}/deliveries` | owner of the row; OWNER | another business's or another person's is `404` |
| `PUT /{id}` | the subscriber | version checked, `409 REPORTING_SUBSCRIPTION_STALE` |
| `POST /{id}/pause`, `POST /{id}/resume` | the subscriber | resume re-checks access (`403 REPORTING_REPORT_NOT_PERMITTED`) |
| `DELETE /{id}` `{reason}` | the subscriber; OWNER with `reason` | a log entry, the row stays cancelled |
| `GET /settings`, `PUT /settings` | OWNER, business-wide MANAGER | `figuresInEmail`; switching it off stops figures at the next send |

- **New error codes:** as in the table; `409 REPORTING_SUBSCRIPTION_LIMIT` is a technical bound per person (`storeql.reporting.subscriptions.max-per-user`), not a business policy.
- **Alert metrics and approvals:** none new. Nothing here can be repeated to steal or cover a loss (a person gets only what they can already read, to their own address, and sensitive figures never), and it moves no money, stock or access, so no approvals key. Failed sends already count in `notifications.failures`.

## Money, time and limits

- **Currency:** figures are the report's own, per currency as the summary groups them; nothing converted, no currency assumed.
- **Ledger postings:** none.
- **Dates:** every window is whole calendar days in the subscription's zone (the store's own, or the one the subscriber chose), the rule of [report-integrity](report-integrity.md) slice 2; a month inside a closed period is served from the frozen figures and says so ("closed, as of…"). Instants stored in UTC; weekday and day of month are the subscriber's explicit choice, so no week start is assumed.
- **Plan limits:** none.

## Constraints

- **Golden rules:** database-per-service (reads tenant-svc through REST, never a join); tenant from the JWT on every write and from the row in the sweep; events through the outbox with an `eventId`; consumers idempotent; append-only for events and deliveries; thin resources.
- **Existing tenants:** no subscription exists; nothing is sent until a person subscribes; reports behave as before.
- **The sweep never blocks a report read or a sale**, runs on the job registry (visible and pausable by an operator), and a paused sweep sends nothing and, on resuming, applies the never-a-backlog rule.
- **Personal data:** the subscription holds the person's user id and ids only; the message body holds no name but the report's. Erasure of a staff login (`SubjectDataSpec`) cancels their subscriptions and keeps the delivery rows with the id, which then reads "erased".
- **Retention:** delivery rows follow the business's retention for "messages sent"; subscription events follow the business's audit retention.
- **Location-neutral:** no zone, weekday, time format or language assumed; the message is in the person's language with the platform's English as fallback.
- **Backups:** plain tables; nothing for the drill.

## Open questions

- [x] Link or figures in the message? Recommended: a link always; figures only for an ordinary report, only when the business allows it, never for a sensitive one. → **As stated** (industry standard: email is not a secure store, so sensitive figures stay behind sign-in; under the user's standing instruction of 2026-09-30)
- [x] Is a delivered sensitive report a read? Recommended: yes, and make it one at the moment of delivery: deliver only a link, so the read is the click and the existing log records it. → **As stated** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Whose day? Recommended: the store's own zone, or the one the subscriber names for several stores; no default zone. → **As stated** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What if the service was down at the due time? Recommended: send only the latest, record the rest as skipped. → **As stated** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] May a person subscribe someone else or an outside address? Recommended: no. → **No; a person needs a login to receive** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What if the person's access is unreadable at send time? Recommended: fail closed, hold and retry, never send. → **As stated** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] `Rhythm.nextDue` is right for daily, weekly, monthly and LAST; across a gap and a repeat in two different zones, and in a zone with no daylight saving; the store's own zone follows the store — `RhythmTest`
- [ ] A subscription is created once per `Idempotency-Key`; missing time of day is `400 REPORTING_SUBSCRIPTION_INVALID`; a multi-store subscription without a zone is `400 REPORTING_SUBSCRIPTION_ZONE_REQUIRED`; an invalid filter for the report is `400 REPORTING_SUBSCRIPTION_FILTER_INVALID` — `SubscriptionIT.createAndRefusals`
- [ ] A due subscription issues exactly one delivery and one event per due instant even with two sweeps racing — `SubscriptionIT.oncePerDueInstant`
- [ ] A subscription overdue by several periods sends only the latest and records the rest `SKIPPED_MISSED` — `SubscriptionIT.neverABacklog`
- [ ] A sensitive report arrives as a link only, never with figures, even if `includeFigures` is requested (`409 REPORTING_FIGURES_NOT_ALLOWED`), and every key on the sensitive list is link-only — `DeliverableCatalogueTest.everySensitiveKeyIsLinkOnly`, `SubscriptionIT.sensitiveIsLinkOnly`
- [ ] Following the link and reading the report writes the report-integrity access-log entry; the delivery itself does not read the report — `ScheduledReportAccessLogIT.theClickIsTheRead`
- [ ] A person whose role no longer meets the report's lowest role, whose login is disabled, or who holds no stores in scope gets nothing and the subscription is `STOPPED_ACCESS_LOST` with the reason — `SubscriptionIT.accessLostStopsIt`
- [ ] An unreadable access answer sends nothing (fails closed), records `HELD_UNREADABLE`, and retries at the next tick — `SubscriptionIT.unreadableAccessHolds`
- [ ] `StaffRemoved` and the deny event stop the subscriptions of that person at that store or entirely, promptly, and the send check would have caught them anyway — `SubscriptionLeaverIT`
- [ ] `ALL_MINE` for a store-held manager means the stores held at each send; a newly assigned store appears, a removed one disappears — `SubscriptionIT.allMineTracksTheStores`
- [ ] Figures appear only when the business allows them, the subscription asks, and the report is figure-capable; switching the setting off removes them from the next send — `SubscriptionFiguresIT`
- [ ] The email goes to the subscriber's own address only, in their language, once per event; a sandbox suppresses it and keeps the in-app copy — notification-svc `ScheduledReportHandlerTest`, `ScheduledReportNoticeIT.sandboxSendsNothingOut`
- [ ] The subscriber sees only their subscriptions; the owner sees and cancels all (with a reason, on the log); a manager cannot read another manager's — `SubscriptionIsolationIT.mineAndAll`
- [ ] Another business's owner and manager and every other role, and a shopper, cannot list, read, change or cancel ours (404/403), even naming our ids; a store-held manager cannot subscribe to another store (`403 STORE_ACCESS_DENIED`) — `SubscriptionIsolationIT.otherBusiness`, `storeHeld`
- [ ] A report the person's role may not read cannot be subscribed to (`403 REPORTING_REPORT_NOT_PERMITTED`), and each catalogue gate equals the endpoint's gate — `DeliverableCatalogueTest.gatesMatchEndpoints`
- [ ] The per-person limit is `409 REPORTING_SUBSCRIPTION_LIMIT` — `SubscriptionIT.limit`
- [ ] Every create, change, pause, resume, cancel and auto-stop writes one append-only log entry with actor and reason — `SubscriptionIT.logsEveryChange`
- [ ] Widgets: Subscribe dialog (report, filters, rhythm, local time, zone picker where required, next delivery shown), My subscriptions, the owner's all-subscriptions view — `subscribe_dialog_test.dart`, `my_subscriptions_test.dart`; k6 `scheduled-reports-flow`

## Screens

- **Admin shell, Reports:** **Subscribe** on each deliverable report opens a dialog (filters carried from the screen, cadence, weekday or day of month, local time, zone where the store does not fix it, "include figures" only where allowed, and the line "Next delivery: Monday 6 October, 07:30 in Store 12's time" through `AppFormat.dateTime`).
- **Reports → My subscriptions:** each with report, filters by name, rhythm, next delivery, status in words ("Stopped: you no longer hold Store 12"), **Pause**, **Resume**, **Cancel**, and the last deliveries. Owners get **Everyone's subscriptions**. Words not codes, adaptive per UI-GUIDE §7.2.

## Flow Tests entry

Area `customer`, flow file `target/flow-catalogue/customer/rpt-scheduled-reports.json` (row 19.11). Cases:
- **Happy:** subscribe, receive on time in the store's day with a working link, figures where allowed — `SubscriptionIT`, `SubscriptionFiguresIT`, k6 `scheduled-reports-flow`.
- **Negative:** invalid rhythm, filter, missing zone, a report the role cannot read, over the limit, figures for a sensitive report — `SubscriptionIT`, `DeliverableCatalogueTest`.
- **Override:** the owner cancels someone's subscription with a reason; the business switches figures off — `SubscriptionIsolationIT.mineAndAll`, `SubscriptionFiguresIT`.
- **Isolation:** other business's staff of every role and a shopper; a store-held manager at another store; another manager's subscription — `SubscriptionIsolationIT`.
- **Edge:** daylight-saving gap and repeat, month end, missed runs, two replicas, a person who leaves, an unreadable access check, `ALL_MINE` changing — `RhythmTest`, `SubscriptionIT`, `SubscriptionLeaverIT`.
- **Audit:** every change and cancel is on the subscription's log with actor and reason; the click is in the access log; each delivery has a row — `SubscriptionIT.logsEveryChange`, `ScheduledReportAccessLogIT`.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **The report sweep registers in `Jobs`** ([background-work-and-stuck-items](background-work-and-stuck-items.md)), and a report's "day" is the store's `BusinessDay` ([store-cash-and-banking](store-cash-and-banking.md) slice 1; calendar day where the store sets no cut-off).

- **2026-09-30, a link for the sensitive, a link plus optional figures for the ordinary.** The alternative, emailing the figure and logging a "read" at send time, would make the log claim that a person read what an unknown mail server may show to anyone.
- **2026-09-30, the check is at send time and fails closed.** Events shorten the time a leaver is subscribed; only the send-time check makes it correct.
