# Dated tax rates: a rate change is a new row, effective from a date

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on tax rates (`pricing/prc-vat-tax-rates`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, pricing domain. Wave 1 verified and deferred it (`_notes/2026-09-30-product-pricing.md` §9) |
| **Services** | pricing-svc owns the rate series and resolves a rate as of a moment · purchase-svc, order-svc and the ledger read a rate through pricing-svc, never the table · the admin app shows the history |
| **Builds on** | `vat_rates` (one row per business and code, `uq_vat_rates_tenant_code`), `PUT /vat-rates/{code}` (`updateVatRate`, overwrites in place), `PricingService.rateFor` and `findVatRate(…, asOf)` (checks `created_at`, not the date the rate was in force), `tax_transactions` (each stamps its own rate), `PricingClient.findVatRates` in purchase-svc, the VAT rates tab of the admin Pricing screen, the `VAT_RATE_CHANGED` ledger entry |
| **Built in** | not built |

## Problem

A government changes a rate on a fixed day. Today the business types the new rate over the old one on that day. The row keeps one rate, so three things are wrong:

- A rate cannot be entered ahead of the day it starts. Someone has to be at the screen at midnight, or the shop charges the old rate for a day (or the new one early).
- The old rate is gone. Nobody can see what the rate was last year, and a re-quote of an earlier moment (what an old order should have carried) answers with today's rate, because the lookup only asks whether the row existed then, not what it said then.
- A backdated `effectiveFrom` is accepted and quietly moves the row's own start date. Sales already recorded stay right (each transaction stamps the rate it was charged at, so filings do not move), but the record of when the rate applied is wrong, and an auditor cannot tell.

## Outcome

- A rate is a **series**: each change is a new row that starts on a date. A rate that was in force is never edited or deleted.
- A business can enter next quarter's rate today. It starts on its date by itself; nobody needs to be there.
- Whoever prices (a till sale, an online quote, a purchase order, an as-recorded re-quote) gets the rate that was in force **at the moment it prices**, not the latest row.
- The admin VAT screen shows each code's history, the rate in force today, and any change scheduled for later, which can still be withdrawn until it starts.
- A change that would rewrite a period in which sales were already recorded under that code is refused, with the reason named.
- Nothing already recorded changes: every `tax_transactions` row, VAT return and fiscal receipt reads exactly as before the migration.

## Already there

- Each recorded tax transaction stamps its own `vat_rate`, so past filings do not move when a rate is overwritten (`PricingRepository`, `VatRateIT`). This page keeps that.
- `PricingService.rateFor(tenantId, code, asOf)` and `findVatRate(…, asOf)` already take a moment; only what they compare it with is wrong (`created_at`). The plumbing stays.
- `PRICING_VAT_RATE_NOT_CONFIGURED`, `PRICING_VAT_CODE_NOT_FOUND`, `PRICING_INVALID_RATE` stay as they are.
- `GET /vat-rates` and `PUT /vat-rates/{code}` keep their shape (the app and purchase-svc call them), so no caller breaks; what they mean is made precise below.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** and the **accountant/manager** who keep the business's tax codes; the cashier and the shopper are only affected (they are charged the rate in force).
- **Channels:** back office (the VAT rates tab); the rate is applied at the till, online, and on purchase orders and invoices.
- **Scope:** per business (tenant) and code. A rate is a national law, so a series is not per store.
- **Roles that can write:** OWNER, MANAGER, PLATFORM_ADMIN (as today). Reads: any staff role (as today).
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order (each slice ships and is tested on its own):
  1. **The series (pricing-svc).** `vat_rates` drops the single-row unique and gains a unique on (tenant, code, effective_from). A row is immutable once it has started. The migration keeps every existing row as the first row of its series and does not touch any recorded transaction. Reading resolves **the row with the latest `effective_from` not after the moment asked**; with no moment, now.
  2. **A change is a new row.** `PUT /vat-rates/{code}` (unchanged shape) now inserts the next row starting at `effectiveFrom` (default: now) instead of overwriting. A future date is a scheduled change. A date the code already has a row for is refused. A row not yet started can be withdrawn (`DELETE /vat-rates/{code}/scheduled/{id}`): it has never priced anything, so it is not yet a record.
  3. **Backdating guard.** A start date earlier than the latest tax transaction recorded under that code is refused `409 PRICING_TAX_RATE_BACKDATED`, naming that date: it would say a rate applied that the books show was not. A start date in the past with nothing recorded after it is allowed (a correction of a period nobody traded in).
  4. **Every reader asks as of the moment it prices.** pricing-svc's quote and resolve use now; the as-recorded re-quote (`AppliedPriceService`) uses the order's moment; `GET /vat-rates?asOf=<instant>` returns the one row per code in force then. purchase-svc passes its document's own date (the order date, the invoice's tax point) instead of taking the latest. order-svc prices a sale's VAT at the moment of pricing and stores the rate on the line ([line-discounts-and-price-overrides](line-discounts-and-price-overrides.md) slice 3, which also makes the tax fall with a discount); a return then refunds the recorded VAT and asks this lookup for nothing. A moment before a code's first row answers `409 PRICING_VAT_RATE_NOT_CONFIGURED`, as today.
  5. **The screen.** The VAT rates tab shows the rate in force per code, a "Scheduled" chip on a future row with a Withdraw action, and a History drawer per code (rate, exempt, from, to, who set it). The edit dialog asks for "Takes effect on" (a date, default today) and says plainly that the old rate stays on everything already sold.
  6. **The change reaches the ledger.** The existing `VAT_RATE_CHANGED` entry now carries the row's `effectiveFrom`, and a scheduled row is announced once when created and again nowhere (it starts by the clock, not by an event).
- **Out, on purpose:**
  - **Rewriting past sales when a rate is corrected late.** A wrong rate that was charged is a correction journal and a credit note, not a rate edit; recorded transactions never change.
  - **Per-store or per-region rates inside one business.** A business's tax codes are its own; place-based tax (a state or county) is a different feature and needs its own design.
  - **Fetching statutory rates from a government feed.** The platform guesses none (as with exchange rates); the business enters what its law says.
  - **A default rate.** There is none; a code with no row in force quotes nothing (`PRICING_VAT_RATE_NOT_CONFIGURED`).

## Data and flow

- **Owned by pricing-svc:** `vat_rates` (one row per code per start date; append-only once started; `created_by`, `created_at` added; unique on tenant, code, effective_from; `effective_to` is no longer written, the end of a row is the next row's start). Migration: existing rows gain `created_by` null and stay valid; `effective_from` is lowered to `created_at` where it is later than the row's first use so an as-of read before it still finds it. `product_vat_categories` is unchanged (a variant points at a code, not a rate).
- **Needs from other services:** tenant-svc, the business's home time zone through `TenantProfiles`, to turn the date typed on the screen into the instant the day begins (a tax day is a calendar day where the business is). No other.
- **Events published:** none new. The `VAT_RATE_CHANGED` ledger entry gains `effectiveFrom`.
- **Retryable writes** (Idempotency-Key): `PUT /vat-rates/{code}` (a replay must not add a second row on the same date; the unique above makes a replay a `409` naming the existing row, and the key makes it answer with the first result).
- **New error codes:** `409 PRICING_TAX_RATE_BACKDATED`, `409 PRICING_TAX_RATE_DATE_TAKEN`, `409 PRICING_TAX_RATE_STARTED` (withdrawing a row that has already started), `404 PRICING_TAX_RATE_NOT_FOUND`.
- **Endpoints:** `GET /vat-rates?asOf=` (any staff, one row per code in force), `GET /vat-rates/{code}/history` (any staff, cursor), `PUT /vat-rates/{code}` (management, a new row), `DELETE /vat-rates/{code}/scheduled/{id}` (management).

## Money, time and limits

- **Currency:** none; a rate is a fraction (`NUMERIC(5,4)`).
- **Ledger postings:** none new; VAT posted on a sale or purchase uses the rate that sale or purchase was priced at.
- **Dates:** `effective_from` is an instant (UTC). The screen takes a calendar date and sends the start of that day in the business's own zone. The as-of moment for a sale is when it was priced; for a document, its own date.
- **Plan limits:** none.

## Constraints

- Golden rules 1 (purchase-svc reads through the REST call), 8 (append-only for a started row) and 14 (UTC) bind. `tenant_id` is the first condition of every read.
- Existing tenants: no visible change until someone schedules a change. Every recorded transaction, VAT return and receipt reads as before (proved by a migration test that snapshots them).
- Location-neutral: no rate, code name or country is assumed; the UK examples in old comments (`T1`, `T5`) are only names a business chose.

## Open questions

- [x] A rate corrected the same day it started: overwrite or new row? Recommended: a new row starting now; the row before it ends there. → **new row** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a start in the past ever allowed? → **Yes, when no transaction was recorded under the code on or after that date; otherwise refused with the date named** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Which zone does "starts on 1 April" mean? → **The business's own home zone; a tax day is a calendar day where the business is** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can a scheduled change be withdrawn? → **Yes, until it starts; nothing has used it** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A change with a future date leaves today's price unchanged and starts on its date — `VatRateSeriesIT.aScheduledRateStartsOnItsDay` (clock moved)
- [ ] The as-recorded re-quote of an earlier order uses the rate in force then, not today's — `VatRateSeriesIT.anEarlierMomentReadsTheEarlierRate`
- [ ] A change never edits a started row; the history lists every row — `VatRateSeriesIT.historyKeepsEveryRate`
- [ ] A start date earlier than a recorded transaction under the code is refused `409 PRICING_TAX_RATE_BACKDATED` naming the date; nothing changes — `VatRateSeriesIT.backdatingUnderRecordedSalesIsRefused`
- [ ] The same start date twice is refused `409 PRICING_TAX_RATE_DATE_TAKEN`; a replay with the same key answers with the first result — `VatRateSeriesIT.aDateIsTakenOnce`
- [ ] A scheduled row can be withdrawn; a started one is `409 PRICING_TAX_RATE_STARTED` — `VatRateSeriesIT.onlyAFutureRowCanBeWithdrawn`
- [ ] Migration keeps every recorded `tax_transactions` row and the VAT return byte-for-byte — `VatRateMigrationIT.recordedTransactionsAreUntouched`
- [ ] purchase-svc prices an order line at the rate in force on the order's date — `PurchaseVatAsOfIT.aLineIsPricedAtItsDatesRate` (stub pricing-svc)
- [ ] The as-of pick is pure and total (latest start not after the moment; none before the first) — `VatRatesAsOfTest.*`
- [ ] Another business's OWNER/MANAGER cannot read or change our series (404); STOREKEEPER/CASHIER/CUSTOMER cannot write (403) — `VatRateSeriesIT.otherBusinessAndLowerRolesAreRefused`
- [ ] A business in a different country and zone starts its change on its own day — `VatRateSeriesIT.aChangeStartsOnTheBusinessesOwnDay`
- [ ] Flutter: the History drawer, the Scheduled chip, Withdraw and "Takes effect on" — `vat_rates_tab_test.dart`
- [ ] End to end: schedule, sell before and after the date, both receipts right — k6 `vat-rate-change-flow`

## Decisions

<!-- Filled while building. -->
