# Report integrity: who read a sensitive report, figures frozen at close, and sales reports that agree

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on reports (`customer/rpt-dashboard-and-report-access` RPT-55, RPT-56; `customer/rpt-sales-revenue-and-tender-mix` RPT-18) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, reports (wave 2, groups F and I, "report read audit") |
| **Services** | reporting-svc owns the sales figures, their frozen day snapshots and the one report access log · order-svc owns the till journal and its staff report · inventory-svc owns valuation and margin, and freezes them at close · pricing-svc, purchase-svc and order-svc each announce a sensitive read through their own outbox · purchase-svc announces the period close ([accounting-periods](accounting-periods.md)) · the app shows the log and the "what this counts" notes |
| **Builds on** | reporting-svc `sales_facts` (with `voided_at`, `refunded_amount`, `no_receipt`), `salesSummary`, `salesByDay`, `/admin/reports/sales/*`, order-svc `pos_log_entries` and `SalesAnalyticsResource.salesByStaff`, `orders.seller_user_id`, inventory-svc `GrossMarginService`, valuation and `accounting_periods`, the audit trail (`/admin/audit`, order-svc), `TenantContext.reportStores`, `LedgerPeriodClosed` from accounting-periods, the retention screen, `TenantProfiles.Stores.zoneOf` |
| **Built in** | not built |

## Problem

- **A closed month can change.** The sales summary recomputes live, so a refund recorded in July on a June sale changes what June "now" reports, with no lock and no flag. Stock valuation and margin do the same; inventory-svc's period close flips a status and freezes nothing.
- **Nobody knows who read the margin or the VAT return.** Sensitive reports are gated by role but a read leaves no record; a compliance question ("who saw last quarter's margin before it was announced?") has no answer.
- **Two sales numbers disagree.** Sales-by-staff (order-svc, from the till journal) and the sales summary and by-day (reporting-svc, from sales facts) are documented in a source comment as not reconciling. Looking for why, they differ in five ways: (1) the staff report is POS only, the summary is every channel unless filtered; (2) the summary leaves out a voided sale and the staff report does not; (3) the summary counts returns taken without a receipt, the staff report cannot see them; (4) the staff report is bounded by exact instants, the summary by UTC days, and by-day buckets by the database session's own day: three definitions of "a day", none the store's; (5) the summary is gross, refunded and net, the staff report gross only. None of this is said on screen.

## Outcome

- **A closed month's sales, valuation and margin figures do not move.** What was read at close is what is read after it. A change recorded later is shown beside it, plainly, as a change since close.
- **The owner can see who read each sensitive report and when,** by person, report and period, and nobody can edit that record.
- **The two sales reports agree, or say plainly what each counts.** For the POS channel the staff report's total equals the summary's POS gross, and every report names its basis, its day and its exclusions.
- **A day is the store's day** in every sales report.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** (reads the log), the **manager** (reads reports), the **finance user** (closes).
- **Channels:** back-office reports and the admin Reports screen.
- **Scope:** per business; store-held managers keep their `reportStores` scope on the reports. The access log is OWNER-only and business-wide.
- **Roles that can write:** none of the reports write. The log is written by the platform, never by a user.
- **Sandbox tenant:** the same, and its log is its own.

## Scope

- **In** (slices, in build order):
  1. **Find and pin why the sales reports differ (order-svc, reporting-svc).** A test that builds a business's day with an online order, a POS sale, a voided sale, a refund and a return without a receipt, and asserts each of the five differences above by name, so the fix is measured. Each report's response gains a `basis` block saying its source, channels, whether voids and no-receipt returns are in, and how the day is judged; the app prints it under the figures.
  2. **One day means the store's day (reporting-svc, order-svc).** Windows are calendar days in each store's own zone (`TenantProfiles.Stores.zoneOf`; a business-wide read applies each store's zone to that store's sales; a store with no readable zone falls back to UTC and says so in `basis`). by-day buckets by the store's local date, not the database session's. order-svc's staff report takes the same `from`/`to` days.
  3. **Staff report reconciles at gross (order-svc).** It reads the same population as the summary's POS channel: voided sales out, returns taken without a receipt shown as their own column per cashier, the `UNATTRIBUTED` bucket kept. An invariant test proves, for any window, that the staff rows' gross plus their no-receipt returns equals the summary's POS gross. Refunds stay in the summary and are not attributed to a cashier here (a manual back-office refund is payment-svc's and the till journal cannot see it); the report says so in `basis` instead of pretending to net.
  4. **Frozen sales figures (reporting-svc).** On `LedgerPeriodClosed`, reporting-svc appends the month's per-day figures (day, store, channel, currency: sales, gross, refunded) to `sales_day_snapshots`. A read for days inside a closed or locked month is served from the snapshot and marked `frozen: true` with the close time; days outside are live; the two add up as they do today. Any difference between the snapshot and the live figure is returned as `changedSince {gross, refunded}` and shown, never applied. `LedgerPeriodReopened` supersedes the snapshot (reads go live, flagged `reopened`); the next close appends a newer version. The by-category and labour reports are analysis, not the books, and stay live (their response says `frozen: false`).
  5. **Frozen valuation and margin (inventory-svc).** On the same event inventory-svc records the closing stock valuation and margin per store and month in append-only snapshot tables, and reads for a closed month serve them with `frozen` and `changedSince`. Today's period close in inventory-svc freezes nothing, whatever its comment says; this makes it true.
  6. **Who read it (four services, reporting-svc).** A shared `@SensitiveRead("<key>")` marker in common-web and a filter that, on a successful first page of a marked report (a cursor page is the same read), writes one outbox event `SensitiveReportRead` through the serving service's own outbox (no state change needed). The keys: `margin`, `valuation` (inventory-svc), `vat-return` and `tax-summary` (pricing-svc), `trial-balance` and `nominal-ledger` (purchase-svc), `audit-trail` and `exceptions` (order-svc), **`segment-members`** ([customer-segments](customer-segments.md), customer-svc) and **`investigation-case`** ([investigation-cases](investigation-cases.md), **notification-svc, which therefore becomes a serving service** with the `SensitiveReportRead` outbox event), **`campaign-sends`** ([campaigns](campaigns.md), notification-svc, a reason required) and **`einvoice-archive`** (the archive export of [e-invoices-received](e-invoices-received.md), purchase-svc, and of [e-invoices-and-e-reporting-for-sales](e-invoices-and-e-reporting-for-sales.md), order-svc); a marked read may carry an optional **reason** (`X-Read-Reason`, kept in the log; `segment-members` requires one, `400 SEGMENT_READ_REASON_REQUIRED`), and `audit-view` (reporting-svc's own merged timeline of [platform-administration](platform-administration.md), which writes its log row directly, with no event, because it is the log's own service; a platform administrator's read of one business's view is logged against that business with the platform login as reader). The marker `@SensitiveRead` and its filter are built once in common-web; every page that adds a sensitive report adds its key to the fixed catalogue. reporting-svc consumes them from every service into one append-only `report_access_log` and serves it at `GET /admin/reports/access-log` to the OWNER, filterable by person, report and period, cursor-paged. The log is purged only by the business's retention setting.
- **Out, on purpose:**
  - **Logging every report read.** Only the marked sensitive ones; ordinary sales reads would drown the record. The list is a fixed catalogue in code, changed by a release, so it cannot be quietly shortened by a tenant.
  - **Logging denied reads.** A `403` is already a request in the gateway's logs; the log is who saw the figures.
  - **Freezing by-category, labour and other analysis reports.**
  - **Sales by seller.** Who is credited (`seller_user_id`) is a different question from who rang it up; this page reconciles the till-operator report and does not add a seller report.
  - **Per-cashier refunds.** Needs payment-svc to name the till operator on a refund; not assumed.
  - **Proration credit on a plan downgrade.** That is billing, owned by tenant-svc; it belongs to the platform page (group H, "downgrade proration"), not here.
  - **Velocity alerts on report reads.** None is asked for; a "too many reads by one person" rule would be the exceptions page's metric.

## Data and flow

- **Owned by** reporting-svc: `sales_day_snapshots` (tenant_id, month, version, day, store_id, channel, currency, sales, gross, refunded, closed_at; append-only; index starts `(tenant_id, month, …)`), `report_access_log` (tenant_id, id, user_id, role, report key, filters as text, from, to, stores, source service, read_at; append-only).
- **Owned by** inventory-svc: `valuation_snapshots` and `margin_snapshots` per store, month and version (append-only).
- **Owned by** order-svc: a `returns without receipt` column in the staff report from its own logs; no new table.
- **Needs from other services:** store zones through `TenantProfiles` (cached); `LedgerPeriodClosed` / `Reopened` events from purchase-svc (fields defined once on [accounting-periods](accounting-periods.md); `LedgerPeriodLocked` changes nothing here, a locked month reads as its closed snapshot); `SensitiveReportRead` events from the four serving services.
- **Events published:** `SensitiveReportRead` (each serving service, topic `storeql.<service>.report-read`; fields: eventId, tenantId, userId, role, reportKey, filters, from, to, stores; consumer: reporting-svc appends once per event id). Nothing else new.
- **Retryable writes:** none from a user. Consumers dedupe on the event id.
- **New error codes:** `REPORTING_ACCESS_LOG_PERIOD_INVALID` 400 (from not before to); the log is refused to a non-owner with the existing `403`. Reports gain no refusal: a frozen read is not an error.

## Money, time and limits

- **Currency:** each figure in its own currency, grouped as the summary already does; nothing converted.
- **Ledger postings:** none.
- **Dates:** the store's day (slice 2); a month is the ledger's month in the business's zone; log times are UTC instants shown in the reader's zone by the app.
- **Plan limits:** none. **Retention:** the access log is purged under the business's own retention setting and nothing else.

## Constraints

- **Existing tenants:** until a month is closed, every report reads live exactly as before, apart from the day now being the store's own (the documented change; for a UTC store nothing moves). No month has a snapshot; the log starts empty (`ReportsUnchangedIT`).
- **Golden rules:** append-only for snapshots and the log; tenant from the JWT and first in every query; no cross-service joins (the log is fed by events); consumers idempotent; `reportStores` scope kept on every frozen read.
- **Location-neutral:** no zone, weekday or currency assumed; a store whose zone cannot be read says so.

## Open questions

- [x] Are a closed month's reports immutable, or flagged? → **Both: the figures are served from what was read at close, and any later difference is shown beside them as a change since close** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Does a refund of a June sale in July change June? → **No: it is shown as a change since close, and the ledger books it in the first open month** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Which reports are sensitive? → **Margin, valuation, VAT return, tax summary, trial balance, nominal ledger, audit trail and exceptions, a fixed list** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Who reads the access log? → **The owner only; a reader cannot see or edit their own trace out of it** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Which sales numbers should reconcile? → **Gross of the POS channel, with voids out and no-receipt returns in, on the store's day; refunds are shown in the summary only and each report says what it counts** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Does the proration credit belong here? → **No: it is tenant-svc billing and belongs to the platform page** (industry standard, under the user's standing instruction of 2026-09-30).

## Acceptance

- [ ] A fixture business shows each of the five differences by name before any change — `SalesReconciliationIT.whyTheTwoReportsDiffered`
- [ ] Every sales report names its basis (source, channels, voids, no-receipt returns, how the day is judged) — `SalesReportBasisIT.everyReportSaysWhatItCounts`
- [ ] A day is the store's day: a sale at 23:30 local in a store east of UTC, and one in a store west of it, land on their own local dates in the summary, by-day and staff reports — `SalesDayIT.theStoresOwnDay`, spanning businesses in different countries
- [ ] A store whose zone cannot be read falls back to UTC and says so — `SalesDayIT.anUnreadableZoneSaysSo`
- [ ] For the POS channel the staff rows' gross plus no-receipt returns equals the summary's POS gross, for a window with a void, a no-receipt return, an unattributed sale and an online order — `SalesReconciliationIT.staffAndSummaryAgreeAtGross`
- [ ] A month closed then a refund on one of its sales: the summary for the month returns the closed figure with `frozen: true` and `changedSince.refunded` equal to the refund — `FrozenSalesIT.aLaterRefundIsShownNotApplied` (RPT-55)
- [ ] A read spanning a closed month and an open one adds the snapshot days and the live days correctly, respecting `reportStores` and channel filters — `FrozenSalesIT.aWindowAcrossTheCloseAddsUp`
- [ ] A re-opened month reads live and flagged `reopened`; a second close appends a newer version and reads that — `FrozenSalesIT.reopenSupersedesAndReclosingAppends`
- [ ] A redelivered `LedgerPeriodClosed` writes no second snapshot — `FrozenSalesIT.closeIsIdempotent`
- [ ] Valuation and margin for a closed month come from the close snapshot with `changedSince` — `FrozenValuationIT.aClosedMonthIsFrozen` (inventory-svc)
- [ ] Reading margin, the VAT return, the trial balance, valuation or the audit trail writes one access-log entry with who, what, filters and when; the second page of a cursor read does not write another — `ReportAccessLogIT.aSensitiveReadLeavesOneEntry` (RPT-56)
- [ ] An ordinary sales read leaves none — `ReportAccessLogIT.ordinaryReadsAreNotLogged`
- [ ] Only the owner reads the log; manager, storekeeper, cashier and shopper get `403`; another business's owner gets nothing of ours (empty) and cannot read our entries — `ReportAccessLogIT.onlyTheOwnerAndOnlyOursAndNoOneEditsIt`
- [ ] There is no endpoint that updates or deletes a log entry, and a redelivered event writes once — `ReportAccessLogIT.appendOnlyAndIdempotent`
- [ ] The Reports screen prints each report's basis and shows *closed, as of <date>* with any change since; an owner's *Who read what* screen lists entries — widgets `reports_basis_test.dart`, `report_access_log_screen_test.dart`; flow guards stay green, and a new k6 `report-integrity-flow`

## Screens

- **Admin shell, Reports:** under every sales figure a one-line basis (channels, voids, day, source); a *Closed, as of 30 June* chip on a frozen range with *Changed since close: refunds 40.00* beside it (money through `AppFormat.money`); the staff report shows its no-receipt returns column and a plain note "refunds are in the sales summary".
- **Admin shell, Reports, Who read what** (owner only): a table of person (by name, not id), role, report, filters, time; filters for person, report and period; cursor paging; `EmptyState` when nothing has been read.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **"A day is the store's day" is `BusinessDay`** ([store-cash-and-banking](store-cash-and-banking.md) slice 1): the calendar day in the store's zone when it sets no cut-off, the cut-off day when it does. Segment-member, campaign-send, investigation-case and e-invoice-archive reads are added to the sensitive catalogue (slice 6).

<!-- Filled while building. -->
