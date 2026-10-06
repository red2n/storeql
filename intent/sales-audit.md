# Sales audit: the store day opened, totalled, checked against what the tills declared, and released to the ledger only when a person has looked

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Oracle Retail audit ("Shelf-J against Oracle Retail", Phase 2 backlog line 1, RMFCS Sales Audit) and the Readiness Review's till rows, under the standing instruction of 2026-09-30 · 2026-09-30 |
| **Roadmap** | new: Oracle audit backlog 1 ("Sales audit — store day, totals, audit rules, over/short, ledger export"), a row the Review does not have, weight *must*, "a supermarket's finance team runs this every morning" |
| **Services** | payment-svc owns the store day, its totals, the declarations, the audit rules, the findings and their resolutions, and announces the release · order-svc announces the sales and voids the audit checks tenders against (its events gain fields) · purchase-svc posts the day's cash over/short at release and, only where the business chooses, holds sale postings until the day is released · tenant-svc owns the store's zone and day cut-off · the app gets a Sales audit screen |
| **Builds on** | payment-svc `till_sessions`, `cash_drops`, `cash_movements`, `z_reports`, `payment_tenders`, `refund_tenders` (with `till_session_id`, `register_id` and `store_id` from [till-sessions-and-registers](till-sessions-and-registers.md) slices 1 to 3), `CashExpectation`, `TillSessionClosed`; order-svc `pos_log_entries` (the till journal), `OrderConfirmed`, `OrderVoided`, `NoReceiptReturnRecorded`; purchase-svc `SalesPosting`, `SalesEventHandler`, `GET /sales-clearing` (per-order clearing exceptions), `PeriodControl` ([accounting-periods](accounting-periods.md)); [report-integrity](report-integrity.md) (the POS gross the audit must agree with); [approvals](approvals.md); [exception-alerts](exception-alerts.md) |
| **Built in** | (not yet built) |

## Problem

A supermarket's finance team starts every morning with the same question: *did yesterday's stores account for every penny, and is anything odd?* Oracle's Sales Audit answers it: a store day is created, every transaction and tender total is loaded, rules flag what a person must look at, each flag is resolved with a reason, and only an audited day goes on to the general ledger. StoreQL has the parts and no day. A till session closes with an over/short; a Z-report sums a store's tenders for a date; the till journal records each POS sale; a "sales clearing" report lists orders whose tenders and sale did not net to zero. But nothing says *this day is finished*, nothing sets the tenders against what each till and the card machine declared, nothing flags a refund with no sale behind it or a till that was never closed, nothing makes a person write down why a shortfall is acceptable, and the ledger takes every sale the moment it happens whether anyone has looked or not. Cash loss and quiet fraud (a void after the money was taken, a refund with no sale) are found by a stock count or a bank statement, weeks late.

## Outcome

- **A store day** (a store, and the date in the store's own zone) is a thing with a status: open while it trades, closed when its tills are closed, audited when a person has released it.
- **The day's totals are set against what was declared:** by tender, by register and by cashier. Cash is declared by each till's count at close; a card or other tender is declared by the manager from the terminal's end-of-day batch (or the total that method reports). The difference is shown line by line.
- **Audit rules flag what needs a person:** a till over or short beyond the business's own tolerance, a till never closed, a refund or void with no sale behind it, a voided sale that still holds money, a sale whose tenders do not add up, a tender a business asked to be declared and was not.
- **Every finding is resolved with a reason** by someone other than the person it is about, or it stays open. A finding that stops being true is closed by the system and says so.
- **The day is released to the ledger only when every finding is resolved.** At release the day's cash over/short is posted, the release is announced, and a business that wants the strictest form holds every sale posting for a store until its day is released. Otherwise sales post as they do today and the release is the control on the day, not a gate on the books.
- **Nothing here refuses a sale, a refund or a till close.** The audit reads what happened; it never stops trading.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **finance / audit clerk** (an owner or manager holding `sales.audit`), the **store manager** (declares the terminal totals, resolves what happened at their store), the **owner** (settings, second person on a large release). The **cashier** is the usual subject and sees none of it.
- **Channels:** back-office (Finance → Sales audit). POS none.
- **Scope:** per store and business date. A caller held to stores reads and acts at those stores only (`requireStoreAccess`, `403 STORE_ACCESS_DENIED`); a store outside the business is `404`. Settings are business-wide (`BusinessWide.require`, `403 BUSINESS_WIDE_ONLY`).
- **Roles that can write:** declarations and resolutions: OWNER, MANAGER at the store holding the new permission `sales.audit` (default for a manager; a custom role can narrow it). Release: the same, and the person who releases need not be the one who resolved. Settings: OWNER or a business-wide manager. A cashier or storekeeper `403`; a shopper `403`.
- **Sandbox tenant:** behaves the same; a sandbox's days are its own.

## Scope

- **In:** slices in build order. Slices 1 to 3 are the audit itself; 4 links it to the ledger; 5 is the optional strict form.
  1. **The store day and its totals (payment-svc, common-service).** `store_days` (one per store and date, created when the first session opens or tender is taken, status OPEN, then CLOSED when a person closes it or, for a day nobody opened by hand, when its date has ended and no session of it is open: [store-cash-and-banking](store-cash-and-banking.md) slice 1 adds the explicit open and close) and pure `BusinessDay.of(instant, zone, cutoff)` in common-service (the store's local date, the zone from `TenantProfiles.Stores.zoneOf`; the cut-off is null, meaning local midnight, until [store-cash-and-banking](store-cash-and-banking.md) slice 1 lets a store choose one; a DST day is 23 or 25 hours). Pure `DayTotals` sums the day's captured tenders and refunds by method and currency, by register and by session's cashier, reusing `CashExpectation`, and is stored as append-only, versioned `store_day_lines`. `GET /admin/sales-audit/days?storeId&from&to` and `…/{id}`. **Declarations:** `POST /admin/sales-audit/days/{id}/declarations {method, currency, amount, source, reference?}` (`TERMINAL_BATCH` or `MANUAL`; a cash declaration is never typed, it is the sum of the day's closed sessions' counts, `SESSION_COUNT`); a later one for the same method supersedes and the earlier stays in the history. The day answers each line: system, declared, difference, or "not declared".
  2. **The facts and the rules (order-svc, payment-svc).** order-svc's `OrderConfirmed` gains `cashierId`, `tillSessionId`, `registerId` and `saleAt` (nullable, added before `eventId`), and `OrderVoided` gains `total`, `currency`, `voidedBy` and `voidedAt`; payment-svc keeps a small `sale_facts` projection (order, store, business date, channel, total, tax, currency, cashier, session, register, status CONFIRMED or VOIDED) for the audit only, never a join. Pure `AuditRules` (below) runs over the day on `POST /admin/sales-audit/days/{id}/run` (also each night for closed days, by the sweep, and when a session of the day closes); each run appends an `audit_runs` row and raises new findings once (unique per day, rule and subject). Findings are readable at `GET /admin/sales-audit/days/{id}/findings`.
  3. **Resolving findings and releasing the day (payment-svc, purchase-svc).** `POST …/findings/{id}/resolve {resolution, reason}` with `EXPLAINED`, `CORRECTED` (the cause was fixed elsewhere, say a late tender recorded) or `ACCEPTED` (a loss the business takes); the reason is required; a resolution is written once. `POST …/days/{id}/release` answers `409 AUDIT_FINDINGS_OPEN` while any finding is open, and otherwise writes the day AUDITED, appends the day's totals as the final version and publishes `StoreDayAudited`. purchase-svc posts the day's **cash over/short** once (Dr *cash over and short* / Cr *cash in tills* for a shortfall, the reverse for an excess; the chart gains `6530 Cash over and short`, confirmed in purchase-svc while building), dated the business date and, in a month already closed, redated by [accounting-periods](accounting-periods.md) (event-fed, never refused). Above the business's ceiling on the amount accepted, release needs a second person: approvals action `finance.sales-audit-release` (new, below).
  4. **The ledger sees the audit (purchase-svc).** The ledger-period close checklist ([accounting-periods](accounting-periods.md) slice 1) gains *store days closed but not audited in the month*, read from payment-svc at close time (`GET /admin/sales-audit/unaudited?from&to`, management, fail-soft, so an unreadable answer shows "unknown", never blocks). The close still refuses only on order and time, as that page decided.
  5. **Hold sales postings until the day is audited (purchase-svc, payment-svc), off until the business turns it on.** A business setting `hold_sales_postings_until_audited`. From the moment it is switched on, purchase-svc parks each sale, tender, refund and no-receipt-return event of a store in `held_sales_events` (event id unique, store, business date, type, the event) instead of posting it, and on `StoreDayAudited` replays every held event of that store and date through the same posting code on one transaction. Switching it off releases everything held at once, so nothing is ever stranded. A held day shows on the checklist and as a count on the Sales audit screen. This is Oracle's model (nothing to the ledger until audited); it is a choice, because it delays the books and a store that never audits would never post.
  6. **Alerts and the screens.** Metrics on [exception-alerts](exception-alerts.md): `store_days.unaudited_age` (sweep, store) and `audit_findings.count` (per rule, per person the finding concerns or per store). Screens below.
- **Born with its checks** (each is also an Acceptance line):
  - *Who and where:* `sales.audit` (management) at a store the caller holds; settings business-wide only.
  - *Authority:* `finance.sales-audit-release` (second person above the business's ceiling on what release accepts as loss); nothing else needs approval, and no ceiling is invented.
  - *Retry safety:* `Idempotency-Key` on declarations, resolve, release and run; a retry answers the first.
  - *Isolation:* another business's staff of every role, even naming our day, finding or store id, get `404` or nothing and nothing moves; a store-held manager cannot act at another store; a shopper `403`.
  - *The record:* runs, findings, resolutions, day events and declarations are append-only; each says who and when; the trail shows release.
  - *Abuse:* the two metrics above; nobody resolves a finding whose subject is themselves.
  - *Refusals:* stable codes below.
  - *Location-neutral:* the day is the store's local day; money in the business's home currency; no tolerance, ceiling or hour is assumed.
- **Out, on purpose:**
  - **A transaction-by-transaction audit screen with a re-total of every sale.** Oracle's audit re-derives each transaction's total. StoreQL's sale total is computed and stored once by order-svc; the audit compares the day's aggregates and the tenders per sale. A per-line recompute would duplicate order-svc's pricing rules.
  - **Missing receipt numbers (a gap in the sequence).** Receipt numbers are numbered gaplessly and hash-chained by order-svc's fiscal receipts (`V16__fiscal_receipts.sql`), which already prove no gap; a second check here would say the same thing.
  - **Auditing online orders' tenders against the sale.** An online order is paid at capture through the payment intent and reconciled by the ledger's clearing report and card settlement; the audit rules cover the POS channel, and an online order appears in the totals only.
  - **Tolerances shipped as defaults.** With no tolerance set the over/short and declaration-difference rules are off; the day still shows every difference.
  - **Posting a till's over/short at the till's close.** It is posted once, at release, when a person has looked; a per-close posting would post what the audit may still explain.
  - **Automatic release.** Only a person releases a day.

## Data and flow

- **Owned by payment-svc:** `store_days` (tenant, id, store, business date, status OPEN|CLOSED|AUDITED, timestamps and who; unique on tenant, store and date) with an append-only `store_day_events`; `store_day_lines` (append-only, versioned: dimension TENDER|REGISTER|CASHIER, subject id, method, currency, sales, refunds, declared, difference, transactions); `day_declarations` (append-only: method, currency, amount, source, reference, by, at); `sale_facts` (projection); `audit_runs`, `audit_findings` (rule, subject kind and id, `concerns_user_id`, amount, currency, detail as ids only) and `audit_finding_resolutions` (all append-only; one resolution per finding); `sales_audit_settings` (per business: `declaration_methods_required`, `hold_sales_postings_until_audited`, `hold_from`). The **variance tolerance is the one on `till_settings`** ([till-sessions-and-registers](till-sessions-and-registers.md) slice 3): a business sets one number and both the close and the audit read it. Every table has `tenant_id` and an index starting `(tenant_id, …)`.
- **Owned by purchase-svc:** `held_sales_events` (a queue: released_at set once) and the posting source `CASH_OVER_SHORT` (source id = the store day).
- **Needs from other services:** the store's zone and cut-off from tenant-svc (`TenantProfiles.Stores`, cached); sales and voids from order-svc by event; nothing by join. purchase-svc reads unaudited days from payment-svc over REST for the close checklist.
- **The rules (`AuditRules`, pure, so every one is a unit test):**

  | Rule | Fires when | Needs a number |
  |---|---|---|
  | `SESSION_NOT_CLOSED` | a till session opened on the day is still open when the day ends (a missing close) | no |
  | `OVER_SHORT` | a session's or the day's cash over/short is above the tolerance, either way | tolerance |
  | `DECLARATION_DIFFERS` | a declared non-cash method's total differs from the system's by more than the tolerance | tolerance |
  | `DECLARATION_MISSING` | a method the business listed in `declaration_methods_required` has tenders and no declaration | the list |
  | `REFUND_WITHOUT_SALE` | a refund tender whose order has no confirmed sale fact at that store | no |
  | `VOID_WITHOUT_SALE` | a void whose order is unknown at the store | no |
  | `VOIDED_SALE_STILL_PAID` | a voided sale whose tenders were never refunded | no |
  | `TENDERS_DO_NOT_MATCH_SALE` | a POS sale whose captured tenders, less its refunds, differ from its total (short, over, or none) | no |
  | `MONEY_NOT_AT_A_TILL` | POS tenders with no session at a store that has registers | no |
  | `SPOT_COUNT_DIFFERS`, `SAFE_COUNT_DIFFERS`, `DEPOSIT_DIFFERS` | a till spot count, a safe count or a confirmed bank deposit ([store-cash-and-banking](store-cash-and-banking.md)) differs from what was expected by more than the tolerance, raised on the day it was counted or confirmed | tolerance |

  A finding carries the person it concerns (the session's cashier, the voider) so the resolver can be kept off it.
- **Events published:** `StoreDayClosed` (`storeql.payment.store-day-closed`), `StoreDayAudited` (`storeql.payment.store-day-audited`: tenant, store, business date, currency, cash over/short, findings accepted, released by, approval id, `eventId` last); consumers: purchase-svc (posts once per event id, replays held events). order-svc's `OrderConfirmed` and `OrderVoided` gain the fields above; consumers that ignore them are unchanged.
- **Endpoints (payment-svc, `/admin/sales-audit`, management by the path gate, then `sales.audit`):** as above; `GET/PUT /admin/sales-audit/settings` (OWNER or business-wide manager); `GET /admin/sales-audit/unaudited`.
- **Retryable writes (Idempotency-Key):** declaration, run, resolve, release, settings.
- **New error codes:** `404 AUDIT_DAY_NOT_FOUND`, `404 AUDIT_FINDING_NOT_FOUND`, `409 AUDIT_DAY_NOT_CLOSED` (run or release while a session is open), `409 AUDIT_FINDINGS_OPEN`, `409 AUDIT_DAY_RELEASED` (a declaration or resolution after release), `409 AUDIT_FINDING_RESOLVED`, `400 AUDIT_REASON_REQUIRED`, `400 AUDIT_DECLARATION_INVALID`, `403 AUDIT_SUBJECT_IS_CALLER`, `400 AUDIT_PERIOD_INVALID`; approvals' `202` and `403 AUTHORITY_EXCEEDED` on release.

## Money, time and limits

- **Currency:** the day is in the business's home currency; a tender in another currency ([till-tenders-foreign-cash-and-accounts](till-tenders-foreign-cash-and-accounts.md)) is shown in its own currency and translated only through `Fx` at the tender's own recorded rate. `NUMERIC(18,4)`, `BigDecimal`.
- **Ledger postings:** at release, once: cash over/short as above. Sale, tender and refund postings are unchanged (they post as events arrive) unless slice 5 is on. Nothing else.
- **Dates:** every instant UTC; a business date is the store's own local day (cut-off from store-cash slice 1). The parts of a day before a store is opened for trading count in the day they fall in.
- **Plan limits:** none.

## Constraints

- **Golden rules:** 1 (the sales projection is fed by events; purchase-svc reads unaudited days over REST); 3 (tenant from the JWT, store checked first); 6 and 7 (release through the outbox, replay and posting idempotent on the event id); 8 (every audit table above is append-only, a day is never re-opened: a correction after release is a new journal by a person, or the next day's finding); 13 and 14.
- **Existing tenants:** no rule runs and no day is held until a business releases its first day or turns slice 5 on. Existing days before the feature have no `store_days` row and are never audited retrospectively (a business may audit from any date forward: the screen lists days from the first row).
- **Consistency with the reports:** the audit's POS gross for a day equals [report-integrity](report-integrity.md)'s summary POS gross for that store and day (both judge the store's own day); a test proves it.
- **The sale path gets no new dependency.** The audit reads after commit; a failing audit never changes a sale's outcome.
- **Backups:** plain tables, no function in a CHECK; `scripts/backup-drill.sh` needs no special step.

## Open questions

- [x] Where does the audit live? → **payment-svc, because it already holds sessions, tenders, refunds and the Z-report, and closes the day; order-svc's facts arrive by event, purchase-svc only posts** (industry standard: the audit sits with the cash, under the user's standing instruction of 2026-09-30)
- [x] Does an audit gate the ledger? → **Not by default: sales keep posting as events arrive and release is the control on the day; a business may hold postings until release (slice 5)** (industry standard: Oracle holds, most small retailers post continuously, so the platform offers both, under the user's standing instruction of 2026-09-30)
- [x] What over/short is tolerable? → **the business's own tolerance, the same one the till close reads; unset means the rule is off, never a guessed number** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who may resolve a finding, and can the person it concerns? → **management holding `sales.audit` at the store, never the person it is about** (industry standard: segregation of duties, under the user's standing instruction of 2026-09-30)
- [x] Can a released day be re-opened? → **No; an error found later is a manual journal (a person, approvals) or a finding on the day it belongs to** (industry standard: an audited day is a closed record, under the user's standing instruction of 2026-09-30)
- [x] Is a second person needed to release? → **Only above the business's ceiling on the loss the release accepts (`finance.sales-audit-release`), off until set** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] The day's totals by tender, register and cashier equal the sum of its sessions' captured tenders and refunds, in the store's local day (a UTC+13 store, a UTC−8 store, a 23-hour DST day) — pure `DayTotalsTest`, `StoreDayIT.totalsByTenderRegisterAndCashier`, `.theStoresOwnDay`
- [ ] A declaration for a card method is compared with the system total and shows the difference; a later one supersedes and the earlier stays in the history — `DeclarationIT.laterDeclarationSupersedes`
- [ ] Each rule fires exactly when its condition holds and not otherwise, and two rules that need a number are silent with no tolerance set — pure `AuditRulesTest.<rule>`, `AuditRulesTest.noToleranceNoNumberRules`
- [ ] A run twice raises each finding once; a finding that stops being true is closed by the system with `NO_LONGER_APPLIES` — `AuditRunIT.findingsRaisedOnce`, `.systemClosesWhatNoLongerHolds`
- [ ] Refund with no sale, void after payment and a sale short of tenders each raise their finding from real events — `AuditRunIT.refundWithoutSale`, `.voidedSaleStillPaid`, `.tendersDoNotMatchSale`
- [ ] A finding is resolved with a reason and never twice; the person it concerns is refused `403 AUDIT_SUBJECT_IS_CALLER`; no reason is `400 AUDIT_REASON_REQUIRED` — `AuditResolveIT`
- [ ] Release with an open finding is `409 AUDIT_FINDINGS_OPEN` naming the count; with a session open `409 AUDIT_DAY_NOT_CLOSED`; with none open it audits the day, writes the final totals and publishes `StoreDayAudited` once — `AuditReleaseIT`
- [ ] Above the ceiling, release is kept for a second person and the maker cannot approve it — `AuditReleaseIT.approvalKeyFinanceSalesAuditRelease` (with the approvals block)
- [ ] purchase-svc posts the day's over/short once per event id, dated the business date, redated in a closed month, and posts nothing for a day with no difference — purchase-svc `CashOverShortPostingIT`, `.redatedInAClosedMonth`, `.redeliveredEventPostsOnce`
- [ ] The ledger close checklist lists unaudited closed days and says "unknown" when payment-svc is down, without refusing the close — purchase-svc `LedgerPeriodChecklistIT.unauditedDays`
- [ ] With the hold on, sale events are parked and posted on release in one transaction; switching the hold off posts everything held; with it off, posting is unchanged — purchase-svc `HeldSalesIT.parkedUntilReleased`, `.switchingOffReleasesAll`, `.offChangesNothing`
- [ ] The audit's POS gross for a day equals the sales summary's POS gross for the same store and day — `SalesAuditReconciliationIT.agreesWithTheSummary`
- [ ] Another business's staff of every role, naming our store, day or finding id, read, declare, resolve and release nothing (`404`/empty); a store-held manager cannot act at another store (`403 STORE_ACCESS_DENIED`); a cashier and a shopper are refused; a manager narrowed out of `sales.audit` is `403 PERMISSION_DENIED` — `SalesAuditIsolationIT`
- [ ] A retried declaration, resolve and release answers the first — `SalesAuditIdempotencyIT`
- [ ] Runs, findings, resolutions, declarations and day events cannot be updated or deleted through any endpoint — `SalesAuditAppendOnlyIT`
- [ ] A sale, refund and till close succeed unchanged with the audit unreadable — `SalesAuditIT.tradingNeverWaitsOnTheAudit`
- [ ] `store_days.unaudited_age` and `audit_findings.count` raise alerts under a rule and none without one — `SalesAuditAlertIT`
- [ ] Widget: the days list, the day's totals table with declared and difference, the findings with Resolve, and Release — `sales_audit_screen_test.dart`; k6 `sales-audit-flow`; the flow guards stay green

## Screens

- **Admin shell → Finance → Sales audit:** a list of store days (store, date in the store's zone, status chip *Trading / Closed / Audited*, findings open, days waiting), filter by store and status. A day opens to a **Totals** table (tender × system, declared, difference; tabs *By register*, *By cashier*), **Declare** (terminal batch total, amount, reference) beside each non-cash method, a **Findings** list (rule in words, who it concerns by name, the amount, **Resolve** with a reason), and **Release the day** (disabled with the count while findings are open; a *waiting for approval* state above the ceiling). Held-posting count shown when the hold is on. Settings: hold, methods to declare (the tolerance is on the Till settings screen).
- Words not codes (`status_labels.dart`), money through `AppFormat.money`, dates through `AppFormat`, adaptive per UI-GUIDE §7.2; widget tests for the totals table and the resolve dialog.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **One `BusinessDay`** (common-service, pure `BusinessDay.of(instant, zone, dayCutoff)`, defined by [store-cash-and-banking](store-cash-and-banking.md) slice 1) is the only definition of a store's trading day, used here, by [till-sessions-and-registers](till-sessions-and-registers.md) slice 1 (the day report), [report-integrity](report-integrity.md) slice 2, the e-reporting periods and [scheduled-reports](scheduled-reports.md). With no cut-off set it is the store's calendar day, which is what each of those pages already assumed. `sales.audit` is a permission; `finance.sales-audit-release` is the approvals key.

Filled while building.

## Flow Tests entry

Area `pos`, file `target/flow-catalogue/pos/sales-audit.json` (order 14, actors OWNER, MANAGER; ui: Finance → Sales audit; api: the endpoints above; rules: the audit rules with their codes). Its cases, each automated before the page is BUILT:

- **happy** SA-01 a day totals and releases clean (`AuditReleaseIT`); SA-02 a resolved shortfall releases and posts once (`CashOverShortPostingIT`).
- **negative** SA-03 release with open findings `409 AUDIT_FINDINGS_OPEN`; SA-04 no reason `400`; SA-05 a session left open `AUDIT_DAY_NOT_CLOSED`.
- **override** SA-06 above the ceiling a second person releases (`finance.sales-audit-release`); SA-07 hold on, postings wait then post.
- **isolation** SA-08 another business's owner and manager, our ids; SA-09 store-held manager at another store; SA-10 cashier, storekeeper, shopper (`SalesAuditIsolationIT`).
- **edge** SA-11 DST day and two zones; SA-12 no tolerance, number rules silent; SA-13 redelivered `StoreDayAudited`; SA-14 a retry (`SalesAuditIdempotencyIT`); SA-15 subject cannot resolve their own finding.
- **audit** SA-16 append-only tables; SA-17 alerts and the sensitive trail; SA-18 the audit's POS gross equals the summary's.
