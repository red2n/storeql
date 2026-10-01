# Operator reporting: the platform's view across businesses, counts and health and never a business's own trade

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Readiness Review row 19.10 "Cross-tenant reporting for the platform operator"; Oracle audit (Analytics, "Cross-tenant reporting for the operator", the Review's 18.9) |
| **Services** | tenant-svc owns every business-side figure here (its own register, subscriptions, invoices, dunning, meters) and a daily count snapshot · reporting-svc reads the platform's telemetry through one driver and serves the service-health view · the platform console shows both · iam-svc supplies the tiers |
| **Builds on** | tenant-svc `PlatformResource` (`/platform/tenants`), `PlanResource`, `PlatformBillingResource`, `SubscriptionService`, `DunningService` (`dunning_events`), `UsageService` (`usage_periods`, `usage_alerts` at the 80 and 100 points), `tenants`, `subscriptions`, `billing_invoices`, `tenant_plan_changes`; the platform console (`platform_dashboard_screen.dart`, `tenants_screen.dart`, `billing_screen.dart`, `plans_screen.dart`, gateway health card); Prometheus (`infra/prometheus.yml`, `infra/rules/`) and Grafana; [platform-administration](platform-administration.md) tiers (`SUPPORT` reads, `OPERATOR`, `ADMINISTRATOR`), `PlatformTiers` and its route table; [exception-alerts](exception-alerts.md)'s statement that "the platform compares no tenants"; the drivers rule (`docs/DRIVERS.md` planned in the wave-2 build order) |
| **Built in** | not yet built |

## Problem

The platform operator runs many businesses and can see almost none of it in aggregate. The console lists tenants, a plan editor and the billing screen, and the gateway's own health. It cannot answer: how many live businesses are on each plan, how many are suspended and why they cluster, who is near a limit, how much is overdue, whether new sign-ups are growing, or whether the service that is slow is slow for everyone. Answers come from Grafana by someone who knows the queries, or from a database shell. Yet the operator must **not** be given a shortcut to a business's own trade (its sales, its customers, its stock): that is the business's, and isolation is what the platform sells.

## Outcome

- **An operator opens one overview** and sees: businesses by plan, by status and by whether they are a sandbox; who is near or over a plan allowance; billing state (in good standing, overdue, in dunning, suspended for non-payment) and recurring revenue **per currency**; sign-ups, activations, suspensions and closures over time; and, for each service, its request rate, error rate and latency.
- **Every figure is a count, a plan measure or a health signal.** None is a business's sales, customers, stock, staff names or prices. A test holds that line.
- **Support can read all of it and change nothing;** the same figures serve a support call without opening any business's data.
- **Every list can be exported** (CSV), and every figure names the instant it was measured.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the platform **operator** and **administrator** (decide, act elsewhere), **support** (read only). No business role ever sees this: the shared gate refuses a tenant token on `/platform/**`.
- **Channels:** platform console only.
- **Scope:** platform-wide. A row may name a business (id, name, plan, status) because that is the platform's own register; it never carries that business's trade.
- **Roles that can write:** none. Every route here is a read; `SUPPORT` and above may call it (`PlatformTiers` lists each route as read, so a new one without a decision fails the build).
- **Sandbox tenant:** counted apart ("live" and "sandbox" are separate columns); a sandbox is left out of billing, recurring revenue and growth by default (`includeSandbox=true` adds it).

## Scope

- **In**, in build order:
  1. **Businesses (tenant-svc).** `GET /platform/reports/businesses`: counts by plan, status (PENDING, ACTIVE, SUSPENDED, CLOSED), mode (live, sandbox) and home country, and the number of stores and staff by plan band from tenant-svc's own tables. Plus trials running, trials ending soon (the trial length is the plan's own).
  2. **Billing state (tenant-svc).** `GET /platform/reports/billing`: subscriptions by state; invoices open, overdue and paid in the period, count and amount **per currency**; businesses in each dunning stage; suspended for non-payment; recurring revenue per currency (a plan's price normalised to a month, an annual price divided by twelve); the trial funnel (started, converted, lapsed).
  3. **Usage against plan (tenant-svc).** `GET /platform/reports/usage`: for each meter (orders taken, texts sent) and for the counts tenant-svc owns (stores, staff), how many businesses are below the platform's own warning point, between it and the allowance, and over; and the **named list** of businesses at or over their allowance (name, id, plan, meter, used, included, period). The two points are the 80 and 100 tenant-svc's `usage_alerts` already use, not new thresholds. Allowances owned elsewhere (products, image and document megabytes, API requests a minute) are refused by their owners and appear here as **refusal counts by key** from the telemetry (slice 5), not per business.
  4. **Growth (tenant-svc).** `GET /platform/reports/growth`: sign-ups, activations, suspensions, closures and plan changes per month (UTC), and the daily count of businesses by status from a new append-only snapshot written by a registered job. Before the first snapshot exists only sign-ups (from `created_at`) and plan changes (from `tenant_plan_changes`) are shown, and the answer says the series starts on the snapshot's first day.
  5. **Service health (reporting-svc).** `GET /platform/reports/health?window=`: per service, requests per second, error rate (5xx over all), and latency at the 50th, 95th and 99th percentile over a chosen window, plus the plan-limit refusal counts by key. It reads a **`TelemetrySource` driver** (`PROMETHEUS`, base address and credentials from `.env`, and `SIMULATED` for tests) with a stub-backed test of each real driver's exact query shape, the accounting-connectors pattern. A service the source cannot answer for is named in `meta.unavailable` and the rest answer. Read only, no tenant label anywhere.
  6. **Screens and export (app, console).** An Overview page, Businesses, Billing, Usage, Growth and Service health tabs, CSV on each list.
- **Out, on purpose:**
  - **A business's own figures in detail** (sales, revenue, customers, stock, staff, prices, orders by product). That is the tenant's data and the platform's promise; the platform compares no tenants (the alerts page's rule). If the platform's terms one day allow benchmarking, it would be a new page, opt-in per business with the consent kept as evidence, and would still show only what the business agreed. Nothing is built or hinted at here. **What is here is what the platform already holds as the seller of its own service:** the plan, the meter counts it bills on, and the invoices it issued.
  - **Per-business error or latency.** Telemetry is by service. No metric series carries a tenant label (also a cardinality rule), so a per-business view would need reading traces; support does that through the business's own request id, one request at a time.
  - **Currency totals across currencies.** The platform fetches no rates and guesses none; recurring revenue is shown per currency.
  - **Alerts to the operator on these figures.** Thresholds on health belong in Prometheus alert rules (`infra/rules/`), which already exist for the platform; the console shows current state. Job and event failures have their own page ([background-work-and-stuck-items](background-work-and-stuck-items.md)).
  - **Usage of allowances owned by other services, per business.** Their owners hold the counts; a nightly fan-out that would copy them here is a second copy to keep true. The refusal counts show whether any allowance bites.
  - **Predicting churn or scoring a business's health.** A model over a business's behaviour is the comparison this page refuses.

## Data and flow

- **Owned by tenant-svc:** `platform_daily_counts` (append-only): `day` (UTC date), `plan_id`, `status`, `mode`, `country`, `businesses` (count). One row per combination per day, written by the job `platform-daily-counts` (in the shared job registry), idempotent per day. It holds no business's identity, only counts. The reads above are queries over tenant-svc's own tables and this one; no new business table.
- **Owned by reporting-svc:** nothing stored. The health view is read at request time through `TelemetrySource` with a short cache (a technical key).
- **Needs from other services:** none over REST or events for slices 1 to 4 (tenant-svc reads itself). Slice 5 reads the telemetry backend, never another service's tables.
- **Events published / consumed:** none.
- **Endpoints** (all `GET`, `/platform/reports/…`, served through the gateway; a tenant token is refused by the shared filter; `SUPPORT` and above):

| Path | Answer | Notes |
|---|---|---|
| `/businesses?includeSandbox=` | counts by plan, status, mode, country; stores and staff by plan | tenant-svc |
| `/billing?period=` | states, overdue and paid per currency, dunning stages, recurring revenue, trial funnel | tenant-svc |
| `/usage?meter=` | distribution and the named over-allowance list | tenant-svc, cursor on the list |
| `/growth?from=&to=` | monthly and daily series | tenant-svc, range at most a technical bound (`storeql.tenant.reports.max-range-days`) |
| `/health?window=` | per-service rate, errors, latency; refusals by key | reporting-svc; `meta.unavailable` |
| each with `Accept: text/csv` | the same rows | |

  Every answer carries `asOf` (UTC instant) and the window in words. **New error codes:** `400 PLATFORM_REPORT_RANGE_INVALID` (from after to, or beyond the bound), `400 PLATFORM_REPORT_WINDOW_INVALID`, `403 PLATFORM_TIER_INSUFFICIENT` (existing) never arises for a read but is the answer to a tenant token with the existing forbidden code.
- **The line against trade data, as code.** A contract test, `OperatorReportsHaveNoTradeDataTest`, lists the fields each answer may contain (a closed set of register, plan, meter, invoice and count fields) and fails if a response carries any other. A second test, `MetricsHaveNoTenantLabelTest`, scans every service's `/metrics` output in the integration suite and fails on a `tenant` or `tenant_id` label.
- **Retryable writes:** none (all reads). **Approvals action keys:** none. **Alert metrics:** none for a business (nothing here concerns a business's staff). Platform-side alerting stays in Prometheus rules.

## Money, time and limits

- **Currency:** per currency, exactly as billed; recurring revenue is the sum of one currency's monthly-normalised prices; nothing converted, no rate assumed.
- **Ledger postings:** none (reads the platform's billing rows; posts nothing).
- **Dates:** UTC throughout, day and month boundaries in UTC (the platform's own books); the console shows them in the operator's zone. Every figure says when it was measured.
- **Plan limits:** none.

## Constraints

- **Golden rules:** database-per-service (tenant-svc reads its own; reporting-svc reads telemetry, not tables); tenant isolation is preserved by there being no route that takes a business's trade; append-only snapshot; UUIDv7; thin resources.
- **Tiers:** `SUPPORT` reads, nothing writes; `PlatformTiers.everyPlatformRouteHasATier` covers each route, so a route added without a tier decision fails the build ([platform-administration](platform-administration.md) slice 2).
- **Existing tenants:** no change; every figure is a read of what exists.
- **Cost:** the reads are indexed counts over the tenant register (thousands of rows, not millions); the snapshot job is bounded by plan × status × mode × country. Telemetry queries are bounded by window and a short cache.
- **Sandbox and real money:** sandbox businesses never enter billing figures unless asked.
- **Location-neutral:** countries and currencies are grouped as data, never listed in code; no timezone is assumed for a business.
- **Backups:** `platform_daily_counts` is plain; nothing for the drill.

## Open questions

- [x] How much may the operator see of a business? Recommended: only what the platform holds as the seller of its own service; no trade. → **Register, plan, meters, invoices, counts and service telemetry; nothing else, and a test holds it** (industry standard: data minimisation and tenant confidentiality, under the user's standing instruction of 2026-09-30)
- [x] Does support read all of it? Recommended: yes, and only read. → **`SUPPORT` reads every route here** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Where does the operator's error and latency view come from? Recommended: the platform's telemetry store, through a driver with a simulated form. → **`TelemetrySource` with `PROMETHEUS` and `SIMULATED`** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What are the usage bands? Recommended: the platform's own two warning points that already exist. → **Below the first, between the two, over** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Are revenue totals summed across currencies? Recommended: never. → **Per currency** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Businesses by plan, status, mode and country match a fixture register, and a sandbox is counted in its own column and left out of billing and growth unless asked — `OperatorBusinessesIT.countsMatchTheRegister`, `sandboxIsApart`
- [ ] Billing shows subscription states, overdue and paid per currency (no cross-currency sum), dunning stages and recurring revenue with annual prices divided by twelve — `OperatorBillingIT`
- [ ] Usage counts businesses below, between and over the platform's two existing points per meter, names those at or over, and a business exactly at a point falls on the documented side — `OperatorUsageIT`, `UsageBandsTest`
- [ ] The daily snapshot is written once per day (a second run adds nothing) and growth reads it; before the first snapshot it says the series starts then — `PlatformDailyCountsIT`, `OperatorGrowthIT`
- [ ] The health view answers rate, error rate and latency per service from the source, names an unavailable service and still answers the others; the real driver's exact query shape is proved against a stub — `TelemetryHealthIT`, `PrometheusTelemetryDriverTest`, `SimulatedTelemetryTest`
- [ ] No answer contains any field outside the closed set, and no service's metrics carry a tenant label — `OperatorReportsHaveNoTradeDataTest`, `MetricsHaveNoTenantLabelTest`
- [ ] `SUPPORT`, `OPERATOR` and `ADMINISTRATOR` read every route; every business role (owner, manager, storekeeper, cashier) and a shopper is refused, even naming a tenant id; there is no write route — `OperatorReportsAccessIT`, `PlatformTiersTest.everyPlatformRouteHasATier`
- [ ] A range from after to, or beyond the bound, is `400 PLATFORM_REPORT_RANGE_INVALID`; an unknown window `400 PLATFORM_REPORT_WINDOW_INVALID` — `OperatorReportsIT.refusals`
- [ ] CSV output equals the JSON rows — `OperatorReportsIT.csv`
- [ ] Widgets: the console Overview, Billing, Usage, Growth and Service health tabs render, show `asOf`, per-currency money through `AppFormat.money`, and an unavailable service in words — `operator_reports_test.dart`; k6 `platform-admin-flow` reads the overview as support and is refused as an owner

## Screens

- **Platform console → Overview:** tiles (live businesses, on trial, suspended, overdue, at or over a limit), each opening its tab; a service-health strip (green, slow, failing) per service.
- **Businesses:** plan by status table with sandbox and live columns and a country breakdown. **Billing:** state and dunning stage counts, overdue and recurring revenue per currency. **Usage:** a meter picker, the three bands and the named list linking to the business's register entry (the existing Businesses screen). **Growth:** a monthly chart and a daily status series. **Service health:** a window picker (last hour, day, week) and a per-service table with rate, errors and latency, and the refusal counts by key. Words not codes, dates through `AppFormat`, adaptive per UI-GUIDE §7.2; support sees the same and no action buttons.

## Flow Tests entry

Area `platform`, flow file `target/flow-catalogue/platform/plat-operator-reporting.json` (row 19.10). Cases:
- **Happy:** each of the five reads for a fixture platform, the overview, CSV — `OperatorBusinessesIT`, `OperatorBillingIT`, `OperatorUsageIT`, `OperatorGrowthIT`, `TelemetryHealthIT`.
- **Negative:** bad range and window; a telemetry source down — `OperatorReportsIT.refusals`, `TelemetryHealthIT.unavailableIsNamed`.
- **Override:** including sandbox in billing and growth; a plan-limit refusal count by key — `OperatorBusinessesIT.sandboxIsApart`, `TelemetryHealthIT`.
- **Isolation:** every business role and a shopper refused; no route takes a business's trade; no tenant label in any metric — `OperatorReportsAccessIT`, `OperatorReportsHaveNoTradeDataTest`, `MetricsHaveNoTenantLabelTest`.
- **Edge:** a business exactly at a warning point; cross-currency amounts; empty platform; the series before the first snapshot; a country with one business — `UsageBandsTest`, `OperatorBillingIT`, `OperatorGrowthIT`.
- **Audit:** every figure carries `asOf`; there is no write, so nothing to record; the platform trail is unchanged by reads — `OperatorReportsIT.asOf`, `PlatformTiersTest`.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **Operator tiers are [platform-administration](platform-administration.md)'s:** every `/platform/reports/**` route (and `/platform/jobs`, `/platform/operations/**` of [background-work-and-stuck-items](background-work-and-stuck-items.md)) is read-only for `SUPPORT`, and appears in the `PlatformTiers` route table whose test fails on an undecided route; controls on jobs and events need `OPERATOR`, essential ones `ADMINISTRATOR` with a fresh factor. Nothing here adds a tier or a second-person approval.

- **2026-09-30, counts and health, not trade.** The wave-2 alerts page already says the platform compares no tenants; this page makes the rule testable rather than a promise.
- **2026-09-30, two owners, merged by the console.** tenant-svc answers what it owns and reporting-svc answers telemetry; the console merges them, as the app merges approvals, so neither service reads the other's tables.
