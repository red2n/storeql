# Accounting periods: open, close and lock the ledger's months, and correct a filed tax return

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on the ledger, VAT return and tax reports (`procurement/general-ledger-period-close-trial-balance`, `procurement/vat-return-mtd`, `customer/rpt-tax-and-statutory-returns`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, procurement and finance domain (wave 2, group F). Wave 1 verified and deferred it (`_notes/2026-09-30-purchase-svc.md`, "DEFERRED") |
| **Services** | purchase-svc owns the ledger's months (open, closed, locked), what a closed month refuses and how its postings are redated · pricing-svc owns tax and the filed returns, and their corrections · inventory-svc keeps its per-store stock-valuation periods and follows the ledger's close · reporting-svc reads the close (see [report-integrity](report-integrity.md)) · the admin app gets a Finance area |
| **Builds on** | inventory-svc `accounting_periods` and `/admin/inventory/accounting-periods` (open, close; a status flip only), purchase-svc `PeriodControl`, `InventoryClient.accountingPeriods`, `PurchaseService.requireOpenPeriod` and `PURCHASE_PERIOD_CLOSED`, `nominal_ledger_entries` (`journal_id`, `source_type`, `store_id`), `GET /nominal-ledger`, `/journals`, `/trial-balance`, `/sales-clearing`, `POST /nominal-ledger/journals`, pricing-svc `MtdService`, `vat_return_submissions` (append-only, boxes as filed), `tax_transactions`, `VatSubmissionProvider`, the Trial Balance report in the admin Reports screen, `post_journal_dialog.dart` |
| **Built in** | not built |

## Problem

A finance person closing a month has no place to do it in the ledger. The only period switch is inventory-svc's, one store at a time, and all it does is flip a status that purchase-svc happens to read. So:

- **The ledger has no month of its own.** A tenant-level journal (no store) is never checked against any period. A business that never opened a stock period can never close its books.
- **A closed month either refuses the world or leaks.** A goods receipt, a supplier invoice or a return dated into a closed store month is refused `409`, so a supplier's late invoice cannot be booked at all. Sale, tender, refund and settlement postings come from events and are never refused, so they land in a closed month unannounced, dated the day they arrive.
- **Nothing proves what the month looked like at close.** There is no closing balance to compare with, no list of what was outstanding, and closing again changes nothing on record.
- **A filed tax return is a dead end.** `DELETE` is refused and there is no correction. A refund or a late supplier invoice dated inside an already-filed period is simply missing from every later return.
- **The tax total cannot be tied to the books.** The VAT return is computed in pricing-svc from `tax_transactions`; the VAT control account (2200, 2201) is in purchase-svc's ledger. Nothing compares them, and outside the UK-specific nine-box return there is no plain "tax collected" report.
- **The ledger cannot be browsed.** The trial balance report exists; the lines and one journal are API only.

## Outcome

- **A month has a status in the ledger:** open, closed or locked. Finance closes a month from one screen, with a checklist of what is still outstanding, and sees the closing balances kept.
- **Once closed, nothing new enters that month.** A document or event that belongs to it is booked on the first open day, with its own date kept beside it and marked *late*. The one thing that is refused is a journal keyed by a person, who is told the first open date.
- **Re-opening a closed month is a second person's decision** (an approvals action), and it is on the record. A locked month cannot be re-opened at all; corrections go into the next open month.
- **Filing a tax return locks the months it covers.** If something changes inside a filed period afterwards, the business sees the difference between what it filed and what the books now say, and corrects it in the next return or by a replacement filing, whichever the tax authority and the filing driver allow. The filed record is never edited.
- **A tax summary says what tax was collected and paid, by rate, and shows the ledger's VAT control account beside it** with the difference named.
- **The ledger can be browsed:** every line, one journal whole, the months and their status.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **finance user** (owner or manager) who closes, and the **owner** who locks and approves a re-open. The **storekeeper** and **cashier** are only affected (their documents are redated, never refused).
- **Channels:** back-office (admin app, Finance area). POS and online are unchanged.
- **Scope:** a ledger month is per business (the ledger has tenant-level journals, so a month is not per store). A store's stock-valuation period stays per store in inventory-svc. A month is a calendar month in the business's own zone (`TenantProfiles`); no fiscal-year start is assumed.
- **Roles that can write:** close and lock need management and a new permission `finance.periods` (an owner holds it; a manager narrowed out of it is refused `403 PERMISSION_DENIED` naming it, as `finance.journal` is). Lock is OWNER only. Re-open is an approvals action (below). Reads: management. Correcting a tax return: management with the existing tax permission the filing already uses.
- **Sandbox tenant:** behaves the same; a sandbox's SIMULATED filing driver supports both correction kinds so both can be rehearsed.

## Scope

- **In** (slices, in build order):
  1. **The ledger's months (purchase-svc).** `ledger_periods` (one row per business and month, created when first closed; a month with no row is open, as today), an append-only `ledger_period_events` history, and a `ledger_period_balances` closing trial balance kept per close (a re-close adds a new version). Close needs the previous month already closed (or none exist before it) and the month to have ended everywhere the business trades (UTC-12 fallback, so never early, as the RFQ deadline rule does). A close records a **checklist** of what is outstanding and does not refuse on it: sales clearing not at zero, receipts not yet invoiced (GR/IR), accounting pushes `UNCERTAIN` or failed, invoices `FLAGGED`, payment runs approved and unpaid. `LedgerPeriodClosed`, `LedgerPeriodReopened`, `LedgerPeriodLocked` are announced.
  2. **What a closed month does (purchase-svc).** A pure `PeriodControl.postingDate` answers with the date to post on and whether it was moved. Goods receipts, supplier invoices, credit notes, invoice rejections, vendor returns and every event-fed posting (sale, tender, refund, chargeback, card settlement, loyalty, gift card, consignment sale, duty release, dropship) are **redated** to the first open day and keep the original as `document_date`, with `redated = true`. A person-keyed **manual journal is refused** `409 PURCHASE_PERIOD_CLOSED` naming the first open date. **Every posting the other wave-2 pages add is event-fed and so takes the redate rule, never a refusal:** transit loss and its reversal ([transfer-discrepancies](transfer-discrepancies.md)), stock shrinkage from counts, disposals and manual write-offs ([stock-counts](stock-counts.md)), consignment sale corrections ([receiving-controls](receiving-controls.md)), stored-value breakage, unclaimed payables and goodwill gift cards ([stored-value-lifecycle](stored-value-lifecycle.md), [till-sessions-and-registers](till-sessions-and-registers.md)), and the slot fee ([workforce-rules](workforce-rules.md)). The same rule applies to a store's closed stock-valuation period read from inventory-svc. Unreadable periods fail open, as now.
  3. **Inventory follows (inventory-svc).** A consumer of `LedgerPeriodClosed` closes each store's still-open valuation period for the month (making none) and records the valuation as it stood; `LedgerPeriodReopened` re-opens them. The inventory-svc close endpoint stays for a business that only wants stock periods, and purchase-svc keeps reading it, so either closes the door.
  4. **The ledger browse and Finance screens (admin app, purchase-svc read additions).** Nominal ledger tab (lines, filters, one journal whole), Periods tab (months, checklist, close / lock / request re-open), a status chip on the Trial Balance report.
  5. **Filing locks months (pricing-svc → purchase-svc).** pricing-svc announces `VatReturnFiled` when a filing is accepted; purchase-svc locks each closed month wholly inside the return, and marks an open one so its close locks it. A filing that covers a month not yet closed carries the warning `PERIOD_NOT_CLOSED` (pricing-svc reads month status from purchase-svc, fail open).
  6. **Correcting a filed return (pricing-svc).** `tax_corrections`, append-only, of two kinds: `NEXT_RETURN` (the difference is carried as an adjustment line into the next return's boxes) and `REPLACEMENT` (an amended filing for the same period key). A filing driver says which it supports; a correction of an unsupported kind is refused. The suggested difference is the period recomputed now less the boxes as filed.
  7. **The tax summary (pricing-svc, ledger figure from purchase-svc).** Tax collected and paid by rate code and store, home currency, next to the ledger's VAT control accounts for the same days, with the difference and its named causes.
- **Out, on purpose:**
  - **Year-end close and retained earnings.** No equity accounts are seeded; a year-end roll is a manual journal a finance person keys. Months lock; a year is twelve months.
  - **Fiscal calendars other than calendar months, and 4-4-5 weeks.** Not assumed; a business that needs one is a later row.
  - **A supplier or customer sub-ledger close.** Only the nominal ledger has periods.
  - **UK specifics for everyone.** The nine-box return, HMRC's error-correction process and MTD apply only to a business whose filing driver is HMRC. The generic model is a correction in a later return and a replacement filing.
  - **The maker-checker and ceilings themselves.** Re-opening a period, keying a manual journal, and filing or correcting a return are approvals actions; this page names them and the approvals page designs one mechanism (`intent/approvals.md`).
  - **Editing or deleting a posted line, or a filed return.** Both stay append-only.

## Data and flow

- **Owned by** purchase-svc: `ledger_periods` (tenant_id, id, month_start date, status OPEN|CLOSED|LOCKED, closed_at, closed_by, locked_at, locked_by, vat_return_id nullable, checklist as text, version), `ledger_period_events` (append-only: OPENED|CLOSED|REOPENED|LOCKED, who, when, reason, approval reference), `ledger_period_balances` (append-only: period, version, nominal code, debit, credit). `nominal_ledger_entries` gains `document_date` date (defaults to `entry_date` for every existing row) and `redated` boolean.
- **Owned by** pricing-svc: `tax_corrections` (tenant_id, id, submission_id of the filed return, kind NEXT_RETURN|REPLACEMENT, reason, per-box difference, status PROPOSED|APPLIED|SUPERSEDED, carried_into_submission_id nullable, by, at; append-only, status changes are new rows).
- **Needs from other services:** month ends and store zones through `TenantProfiles` (cached, never joined); a store's stock-valuation periods from inventory-svc (REST, existing); the ledger's VAT control movement for the tax summary from purchase-svc (`GET /nominal-ledger/control-movement?code=&from=&to=`, new, management), read fail-soft (the ledger column is absent when unreachable, never a failed report).
- **Events published:** `LedgerPeriodClosed`, `LedgerPeriodReopened`, `LedgerPeriodLocked` (purchase-svc; one topic each by the naming rule: `storeql.purchase.ledger-period-closed`, `…-reopened`, `…-locked`). **The one definition, used by [report-integrity](report-integrity.md) and inventory-svc:** `eventId`, `tenantId`, `month` (the first day of the month as a date, in the business's zone), `zone` (the zone the month was judged in), `version` (the close's version; a re-close raises it, a reopen carries the version it reopens), `occurredAt`, `by` (user id), `approvalId` (on a reopen). Consumers: inventory-svc closes or re-opens store valuation periods and freezes its valuation and margin snapshots; reporting-svc snapshots or supersedes its sales day figures; all idempotent on the event id. `VatReturnFiled` (pricing-svc, topic `storeql.pricing.vat-return-filed`, fields: submissionId, periodKey, from, to, provider; consumer: purchase-svc locks the covered months, idempotent).
- **Endpoints** (purchase-svc): `GET /ledger/periods?from&to` (management), `GET /ledger/periods/{month}` with checklist and balances, `POST /ledger/periods/{month}/close` (`finance.periods`, `Idempotency-Key`), `POST /ledger/periods/{month}/lock` (OWNER), `POST /ledger/periods/{month}/reopen` (approvals action `finance.period-reopen`, reason required). `GET /nominal-ledger` gains `storeId`, `sourceType`, `redated` filters and returns `documentDate`, `redated`. (pricing-svc): `GET /vat-return/corrections/suggested?submissionId=`, `POST /vat-return/corrections` (action `finance.vat-return-correct`), `GET /vat-return/corrections`, `GET /admin/reports/tax/summary?from&to&storeId`.
- **Retryable writes** (Idempotency-Key): close, lock, re-open, and creating a correction. Event consumers dedupe on the event id.
- **New error codes:** `PURCHASE_PERIOD_MONTH_INVALID` 400; `PURCHASE_PERIOD_NOT_FOUND` 404; `PURCHASE_PERIOD_PREVIOUS_OPEN` 409; `PURCHASE_PERIOD_NOT_ENDED` 409; `PURCHASE_PERIOD_ALREADY_CLOSED` 409; `PURCHASE_PERIOD_NOT_CLOSED` 409 (lock or re-open of an open month); `PURCHASE_PERIOD_LOCKED` 409 (re-open of a locked month); `PURCHASE_PERIOD_CLOSED` 409 (kept, for a manual journal only); `PRICING_TAX_CORRECTION_KIND_UNSUPPORTED` 422; `PRICING_TAX_CORRECTION_NOT_FILED` 409 (correcting a period never accepted); `PRICING_TAX_CORRECTION_NOTHING_TO_CORRECT` 409; `PRICING_TAX_CORRECTION_LIMIT` 422 (see settings).

## Money, time and limits

- **Currency:** the ledger and the tax summary are in the business's home currency. A foreign-currency document is already translated at posting; nothing new.
- **Ledger postings:** none new. Redating changes `entry_date` (the posting date) only; `document_date` keeps the original.
- **Dates:** a month is judged in the business's zone; a return's instants are mapped to months in that zone. A month is closable only after it has ended everywhere the business trades.
- **Settings:** `tax.correction.next-return-max` (owner: pricing-svc, home currency, **unset = no ceiling**): the largest net tax difference the business allows itself to carry into the next return rather than replace, because some authorities cap this. A number is the business's, never the platform's. No other setting.
- **Plan limits:** none.

## Constraints

- **Existing tenants:** no month has a row, so every month is open and nothing is redated until a business closes one. Every existing ledger row reads `document_date = entry_date`, `redated = false` (migration test).
- **Append-only:** ledger lines, period events, closing balances, filed returns and corrections never update or delete. A re-close is a new version, a re-open is an event.
- **A consumer is never refused by a period** (golden rule 7): event-fed postings are redated, never `409`ed, so no event is redelivered forever.
- **Ordering:** close needs the previous month closed, so months close in order; re-open is allowed for the latest closed month only, unless a later month is also re-opened first.
- **Backups:** the new tables carry no function used in a CHECK, so `scripts/backup-drill.sh` is unaffected; the migration test still restores and reads them.

## Open questions

- [x] Where does a document dated into a closed month go? Refuse or redate? → **Redate to the first open day, keep the original date, mark it late; refuse only a manual journal, which a person can simply re-date** (industry standard, under the user's standing instruction of 2026-09-30). This **replaces** the earlier rule where a goods receipt, invoice or return dated into a closed store month was refused `409 PURCHASE_PERIOD_CLOSED` (kept above under "New error codes" for journals). Why: the goods are here and the supplier has invoiced; refusing cannot make either untrue, and the ledger posting system that "post in the next open period" is the norm.
- [x] Who owns the month? → **purchase-svc for the ledger, inventory-svc for stock valuation per store, kept in step by `LedgerPeriodClosed`** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Closed versus locked? → **Closed can be re-opened by a second person; locked cannot, and a return filed makes it locked** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] What may a close refuse on? → **Only order and time (previous month open, month not ended); everything else is a checklist kept with the close** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] How is a filed return corrected? → **Both ways authorities generally allow: a later return carries the difference, or a replacement is filed; a driver says which it can do; the filed record stays** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] What is a correction's amount? → **The period recomputed now, less the boxes as filed; a person may adjust it with a reason** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Does the tax summary need to equal the ledger? → **No; it shows both and the difference, with the causes the system can name: postings redated, refunds dated when refunded, and a tax point that differs from the posting day** (industry standard, under the user's standing instruction of 2026-09-30).

## Acceptance

- [ ] A month closes with a checklist and a closing balance kept; closing it again is refused `409 PURCHASE_PERIOD_ALREADY_CLOSED` — `LedgerPeriodIT.closingKeepsTheBalancesAndTheChecklist`
- [ ] A month cannot close before the previous one, or before it has ended — `LedgerPeriodIT.monthsCloseInOrderAndOnlyOnceEnded` (`PURCHASE_PERIOD_PREVIOUS_OPEN`, `PURCHASE_PERIOD_NOT_ENDED`)
- [ ] A supplier invoice, receipt, credit note and vendor return dated into a closed month post on the first open day, keeping `document_date` and `redated` — `PeriodPostingIT.documentsAreRedatedNotRefused`
- [ ] A sale, refund and card settlement event arriving after close post redated and are consumed once — `PeriodPostingIT.eventPostingsAreRedatedAndNeverRefused`
- [ ] A manual journal into a closed month is refused `409 PURCHASE_PERIOD_CLOSED` naming the first open date — `PeriodPostingIT.aManualJournalIsRefusedInAClosedMonth`
- [ ] A business that never closed a month sees no change; existing rows read `document_date = entry_date` — `LedgerPeriodMigrationIT.existingRowsAreUntouched`
- [ ] The pure redate rule, including a February, a year boundary and a month with no row — `PeriodControlTest.thePostingDateIsTheFirstOpenDay`
- [ ] A locked month refuses re-open `409 PURCHASE_PERIOD_LOCKED`; a re-open of an open month `409 PURCHASE_PERIOD_NOT_CLOSED` — `LedgerPeriodIT.lockedMonthsStayLocked`
- [ ] Re-opening needs the approvals action `finance.period-reopen` and is on the period history — `LedgerPeriodIT.reopeningNeedsApprovalAndIsRecorded` (with the approvals page)
- [ ] A cashier, a storekeeper and a manager narrowed out of `finance.periods` cannot close or lock (`403`); another business's owner sees no month (404/empty) and moves nothing — `LedgerPeriodIT.anotherBusinessMovesNothing`
- [ ] Inventory-svc closes and re-opens its valuation periods on the events, once per event id — `PeriodEventConsumerIT.valuationFollowsTheLedger`
- [ ] Filing a return locks the closed months wholly inside it, and a redelivered `VatReturnFiled` locks nothing twice — `VatFiledLockIT.aFiledReturnLocksItsMonths`
- [ ] A filing over an unclosed month answers with the warning `PERIOD_NOT_CLOSED` and still files — `MtdServiceTest.filingOverAnOpenMonthWarns`
- [ ] A late supplier invoice dated inside a filed period is offered as a suggested correction equal to the recomputed boxes less the filed ones — `TaxCorrectionIT.theDifferenceIsWhatChangedSinceFiling`
- [ ] A `NEXT_RETURN` correction appears as an adjustment line in the next return; a `REPLACEMENT` is refused by a driver that does not support it `422 PRICING_TAX_CORRECTION_KIND_UNSUPPORTED` — `TaxCorrectionIT.bothKindsAndTheDriversLimit`
- [ ] A correction of a period never accepted is refused `409 PRICING_TAX_CORRECTION_NOT_FILED`; the filed row is unchanged — `TaxCorrectionIT.aFiledReturnIsNeverEdited`
- [ ] Another business's corrections and filed returns are invisible (404/empty) — `TaxCorrectionIT.anotherBusinessSeesNothing`
- [ ] The tax summary lists collected and paid tax by rate and store beside the ledger control accounts with the difference, and still answers when purchase-svc is down — `TaxSummaryIT.tiesToTheLedgerAndSurvivesItsAbsence`
- [ ] The same tax summary is right for a business in a different country and currency with a different zone — `TaxSummaryIT.spansBusinessesInDifferentCountries`
- [ ] The ledger browse shows lines, a journal whole with its redated marker, and month status; the periods tab closes a month and shows the checklist — widget `ledger_screen_test.dart`, `periods_tab_test.dart`
- [ ] Flow guards stay green — k6 `flow-guard-comprehensive`, `flow-guard-runtime`; a new k6 `period-close-flow`

## Screens

- **Admin shell, Finance:** *Ledger* tab (lines with code, date, store, source, redated chip; cursor paging; tap a line to open its journal, showing document date and any reversal it links to) and *Periods* tab (one card per month: status chip, closing balance link, checklist items with counts and where to fix them; Close, Lock, and Request re-open with a reason field). Both from `lib/shared/widgets/`, words not codes, dates through `AppFormat`.
- **Reports, Trial Balance:** a status chip per range (open, closed, locked, mixed) and the closing version it read.
- **Admin, Statutory returns screen:** a *Corrections* section per filed return: the suggested difference, the kind choices the driver allows, a reason, and the carried-into link; and a *Tax summary* report with the ledger column and its difference.

## Decisions

<!-- Filled while building. -->
