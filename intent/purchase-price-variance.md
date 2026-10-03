# Purchase price variance: what the invoice charged against what the order expected, measured at match, posted to its own account, and what it does to the stock's cost

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Readiness Review's row 17.6 "Purchase price variance" (Finance, accounting and the general ledger, grade *missing*, value) and the Oracle audit's costing and ReIM posting rows · 2026-09-30 |
| **Roadmap** | Readiness Review 17.6 (Absent → Built when this lands); regrade the row and the three-way-match row together |
| **Services** | purchase-svc measures the variance at invoice capture, posts it, reports it, and asks inventory-svc to act · inventory-svc owns cost and decides what a variance does to the batches on hand · reporting-svc reads the events · the admin app carries the report |
| **Builds on** | `ThreeWayMatch` (`PRICE_ABOVE_ORDER`, `PRICE_BELOW_ORDER`, tolerances, the stored `variances` on `supplier_invoice_lines`), `PurchaseService.invoicePosting` (Dr GR/IR at the invoice's net, Dr VAT input, Cr Trade Creditors) and `receiptPosting` (Dr Stock / Cr GR/IR at the **order's** price), `resolveSupplierInvoice` and its reversal, the e-invoice capture path (the same match and posting), inventory-svc `revalueReceiptOnce`, `batch_cost_adjustments`, `costing_methods` (FIFO / AVERAGE), the landed-cost round trip, [supplier-deals-and-cost-changes](supplier-deals-and-cost-changes.md) (the agreed cost and the net order price), [supplier-assurance](supplier-assurance.md) (re-opening an invoice), [accounting-periods](accounting-periods.md), [approvals](approvals.md) (`purchasing.invoice-variance`) |
| **Built in** | not built |

## Problem

When a supplier's invoice charges a different price from the order, the platform notices (the match says `PRICE_ABOVE_ORDER`) and lets a person decide, but it does not **account** for the difference. The ledger shows why this matters: the receipt credits Goods Received Not Invoiced at the **order's** price, and the invoice debits it at the **invoice's** price, so every price difference is left sitting in GR/IR for ever. That account is meant to clear to nothing when every delivery has been invoiced; with price differences it never does, and finance cannot tell an unmatched delivery from a price disagreement. There is no report of which suppliers and products cost more than expected, the stock stays valued at a price the business did not pay, and a rise the supplier slipped in is a line in a match flag, not a figure anyone adds up.

## Outcome

- **Every invoiced line on an order measures its price variance:** quantity times (invoiced price less ordered price), in the order's currency, favourable or not.
- **It is posted to its own account,** Purchase Price Variance, at the moment the invoice is captured. GR/IR clears at the order's price, so **GR/IR nets to zero once every delivery is invoiced**, and what is left in it is only what is genuinely not yet invoiced.
- **The stock's cost follows what was paid,** by accounting practice: a variance on units still on the shelf is part of their cost; a variance on units already sold is cost of sales. inventory-svc, which owns cost, decides which part is which, and purchase-svc posts what it says.
- **Finance sees it:** by supplier, product and month, unfavourable and favourable apart, as a share of purchases, and down to the invoice lines.
- **The decision to approve is unchanged.** Tolerances and `purchasing.invoice-variance` still decide who may approve a flagged invoice; the ledger tells the truth either way.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **finance user** reading the report and clearing the opening balance; the **buyer** chasing a supplier; the **owner**. Nothing changes for the storekeeper or cashier.
- **Channels:** back-office.
- **Scope:** per business; each invoice line carries its order's store. A store-held manager reads only what falls to their stores (`TenantContext.reportStores`; a named store must be theirs, `403 STORE_ACCESS_DENIED`; none named is exactly their stores added together).
- **Roles that can write:** nothing new to write except two finance acts: recording a supplier's price-correction credit note (OWNER, MANAGER, STOREKEEPER as credit notes are today) and posting the one-time opening clearing journal (management with `finance.journal`, and the approvals action `finance.manual-journal`). **Read:** management (the report sits under `/admin`, so a cashier and a storekeeper are refused `403`).
- **Sandbox tenant:** behaves the same.

## Scope

- **In** (slices, in build order):
  1. **Measure and post (purchase-svc).** A pure `PriceVariance.of(matchLine)` gives, per line that is on the order, `varianceQty` (the quantity invoiced now), `perUnit` (invoiced less ordered, in the order's own precision), and `amount` (rounded to the currency's minor units, never differently from the invoice's own rounding); a line **not on the order** (`NOT_ON_ORDER`) has no expected price and no variance, and a quantity invoiced above what was received is a quantity variance, not a price one, and stays in GR/IR at the order's price as it does today. Each invoice line stores its `ordered_unit_price`, `variance_per_unit` and `variance_amount` as at capture (the order may be amended later; the figure a decision was made against never moves, the same reasoning `variances` already follows). `invoicePosting` becomes: **Dr GR/IR at quantity times the order's price, Dr Purchase Price Variance (or Cr, when favourable) for the difference, Dr VAT input, Cr Trade Creditors** for the gross; total debits still equal the invoice's net plus VAT. The variance posts for **every** line whatever the tolerance: a price inside the band is still a price paid. It is dated the invoice's date and takes the redate rule of a closed month. A REJECT reverses every line of the posting, variance included, exactly as it reverses the rest; a **re-opened** invoice ([supplier-assurance](supplier-assurance.md) slice 1) leaves it, because the liability stands. The e-invoice path captures through the same match and so gets the same posting with no change of its own.
  2. **The stock's cost (purchase-svc announces, inventory-svc decides).** After the posting, on the same transaction, `PurchasePriceVarianceRecorded` is announced for the lines whose goods **have been received** (the received-and-not-yet-invoiced quantity, oldest receipt first, so each figure names the receipt `grId` it lifts). inventory-svc's handler applies **the same rule a landed charge and a supplier rebate use, with one difference: it lifts the unit cost only of the units still on hand and answers what it moved.** For FIFO batches it adjusts the receipt's batches still holding stock by the per-unit variance (through `revalueReceiptOnce`, with source `PRICE_VARIANCE`, once per event line, recorded in `batch_cost_adjustments`); for an AVERAGE row it moves the pool by the capitalised amount over what is on hand; a batch's cost never goes below zero. It then announces `PurchasePriceVarianceCapitalised`: per line, `capitalisedAmount` (on hand) and `soldAmount` (already sold, the remainder). purchase-svc posts the capitalised part **Dr Stock / Cr Purchase Price Variance** once per event, so what stays in the variance account is exactly the part that belongs to cost of sales. A receipt inventory has not booked yet is answered 503 and redelivered, as the landed-cost handler does. **Units invoiced ahead of receipt:** their per-unit variance is kept in `price_variance_pending` (append-only rows, consumed by later rows) and announced when the goods are received, so a variance is never capitalised on stock that does not exist yet.
  3. **The report (purchase-svc).** `GET /admin/purchasing/price-variance?from&to&supplierId&variantId&groupBy=supplier|variant|month` returns unfavourable, favourable and net in the home currency, the invoice-line count, the **share of purchases** (net variance over the net invoiced in the window, absent where nothing was invoiced), and drills to `GET /admin/purchasing/price-variance/lines` (cursor: invoice, line, ordered price, invoiced price, per unit, amount, capitalised and expensed, the decision). A foreign-currency variance is translated at the rate the invoice's own posting used (`FxRates`, `Fx`); a line with no rate is listed with no home figure and left out of the totals with the count of those left out. It reads through the report-store rule above and is marked `@SensitiveRead` ([report-integrity](report-integrity.md)).
  4. **Correcting a price by credit note (purchase-svc).** A supplier who agrees the price was wrong sends a credit note. `POST /supplier-invoices/{id}/price-corrections {lines:[{variantId, perUnit}], creditNoteRef, creditNoteDate}` records it against the invoice: the amount per line may not exceed that line's recorded variance (`PURCHASE_PRICE_CORRECTION_EXCEEDS_VARIANCE`), it is posted **Dr Trade Creditors / Cr Purchase Price Variance, with the VAT share Cr VAT input**, and `PurchasePriceVarianceReversed` is announced so inventory-svc lowers the batches it had lifted, by the same rule and the mirror split. Append-only rows in `supplier_price_corrections`; a credit note is recorded once (a unique invoice-and-reference pair).
  5. **The opening balance (purchase-svc).** Invoices captured before this ships posted their differences to GR/IR and are never re-posted (the ledger is append-only). `GET /admin/purchasing/price-variance/opening` lists, per invoice line, the residue already sitting in GR/IR (invoice net less quantity times order price, as recorded), and `POST /admin/purchasing/price-variance/opening` posts **one** dated clearing journal per business (**Dr Purchase Price Variance or Cr / Cr or Dr GR/IR**) once, under `finance.manual-journal`, and never again: a business that has no history has nothing to clear. It is a proposal the person reads first; the platform does not clear it by itself.
  6. **Compared with the agreed cost (purchase-svc, read).** When [supplier-deals-and-cost-changes](supplier-deals-and-cost-changes.md) has recorded an agreed cost for the line, the report adds `orderVsAgreed`: how far the price the buyer typed on the order was from the cost in force. It is **not posted** (no invoice disagreed); it shows where the leak was the order, not the supplier.
  7. **The screens** and the alert metric (below).
- **Out, on purpose:**
  - **Standard-cost accounting and its purchase-side variance.** This platform books stock at the order's (net) price and carries no ledger standard; inventory-svc's standard cost is a costing figure for the AVERAGE method, operator-set, and is not a ledger basis. Measuring against it would make two definitions of "expected". The expected price here is what was ordered; a business that wants a standard is a later, separate row.
  - **Quantity variances and their accounts.** Invoiced-above-received stays in GR/IR and the match flags it as today.
  - **Applying the split to landed costs.** `LandedCostService` posts a landed charge wholly to Stock and lifts every batch of the receipt, sold or not. Practice would send the sold part to cost of sales as this page does; it is left as built (a recorded decision of that feature) and flagged as a follow-up: the shared inventory handler here can adopt it behind a setting, and nothing in this page depends on it.
  - **Tax on the variance.** The invoice's own VAT stands; a variance is booked net.
  - **Changing an invoice's decision from the variance.** Flag, do not block: the three-way match's tolerance and `purchasing.invoice-variance` stay the only gate.
  - **Auto-correcting a supplier's price.** The platform proposes nothing to the supplier; a credit note is recorded when it arrives.

## Data and flow

- **Owned by** purchase-svc: `supplier_invoice_lines` gain `ordered_unit_price`, `variance_per_unit`, `variance_amount`, `variance_capitalised`; new `price_variance_pending` (append-only: invoice line, receipt not yet arrived, quantity, per unit, consumed_by), `supplier_price_corrections` (append-only), `price_variance_opening` (one row per business, the journal id and who posted it). A chart-of-accounts seed adds *Purchase Price Variance* (cost of sales) if missing. `nominal_ledger_entries` are written through the existing `LedgerPosting`, `source_type` `SUPPLIER_INVOICE` (as now), `PRICE_VARIANCE_CAPITALISED`, `PRICE_CORRECTION`, `PRICE_VARIANCE_OPENING`.
- **Owned by** inventory-svc: nothing new but the handler; changes to a batch's cost are recorded in `batch_cost_adjustments` with `source_type` `PRICE_VARIANCE` / `PRICE_VARIANCE_REVERSAL`.
- **Needs from other services:** the home currency and rates (`TenantProfiles`, `FxRates`); the store's zone for the redate; nothing from another service's tables.
- **Events published** (outbox; idempotent on `eventId`):
  - `PurchasePriceVarianceRecorded` (`storeql.purchase.price-variance-recorded`): `eventId`, `tenantId`, `invoiceId`, `supplierId`, `currency`, `lines: [{grId, storeId, variantId, qty, perUnit, amount}]`, `occurredAt`. Consumers: inventory-svc (the cost), reporting-svc (the margin figures).
  - `PurchasePriceVarianceReversed` (`storeql.purchase.price-variance-reversed`, on a rejection or a price-correction credit note): the same fields, amounts as recorded (the consumer negates). Consumers as above.
  - `PurchasePriceVarianceCapitalised` (inventory-svc, `storeql.inventory.price-variance-capitalised`): `eventId`, `tenantId`, `invoiceId`, `refEventId`, `lines: [{variantId, capitalisedAmount, soldAmount}]`, `occurredAt`. Consumer: purchase-svc (posts Dr Stock / Cr Purchase Price Variance, or the mirror).
- **Endpoints** (purchase-svc): `GET /admin/purchasing/price-variance`, `GET /admin/purchasing/price-variance/lines`, `GET|POST /admin/purchasing/price-variance/opening`, `POST /supplier-invoices/{id}/price-corrections`.
- **Retryable writes** (Idempotency-Key): the price correction and the opening journal; a retry answers with the first.
- **New error codes:** `PURCHASE_PRICE_CORRECTION_EXCEEDS_VARIANCE` 422; `PURCHASE_PRICE_CORRECTION_NO_VARIANCE` 409 (the line had none); `PURCHASE_PRICE_CORRECTION_DUPLICATE` 409 (same reference twice); `PURCHASE_PRICE_CORRECTION_INVOICE_STATE` 409 (a REJECTED invoice has nothing left to correct); `PURCHASE_PRICE_VARIANCE_OPENING_DONE` 409; `PURCHASE_PRICE_VARIANCE_OPENING_NOTHING` 409; `PURCHASE_PERIOD_CLOSED` 409 (kept for the person-keyed opening journal only, naming the first open date); `STORE_ACCESS_DENIED` 403.

## Money, time and limits

- **Currency:** the order's own (an invoice on a foreign-currency order is in that currency, as the match already requires); posted to the ledger in the home currency at the rate the invoice's own posting uses, `FxRates`/`Fx`, rounded to the target's minor units; no rate means the report shows the figure untranslated and counts it apart, the posting fails closed exactly as the invoice's does.
- **Ledger postings:** the invoice: Dr GR/IR (quantity times order price), Dr or Cr Purchase Price Variance, Dr VAT input, Cr Trade Creditors. The stock's part, on inventory-svc's answer: Dr Stock / Cr Purchase Price Variance (mirror when favourable). A price-correction credit note: Dr Trade Creditors / Cr Purchase Price Variance and Cr VAT input for its share (Dr for the stock part). Rejection: the exact reverse of everything above.
- **Dates:** the invoice's date for the posting; the receipt's day is unchanged; a closed month redates event-fed postings and refuses only the person-keyed opening journal ([accounting-periods](accounting-periods.md)).
- **Plan limits:** none.

## Constraints

- **Existing tenants:** an invoice matching the order exactly posts exactly as before (variance zero, no line added); an invoice captured before the migration is never re-posted; `PurchasePriceVarianceCompatibilityIT` proves both.
- **Golden rule 1 and 6:** purchase-svc never reads or writes a batch; the cost change is inventory-svc's, driven by the event, and the round trip returns the split.
- **Golden rule 8:** invoice lines' variance columns are written at capture and never updated (except `variance_capitalised`, which is written once by the capitalised event and recorded with the event id); corrections and the opening are new rows.
- **Consumers are never refused by a period:** the capitalisation and reversal posts redate.
- **Flag, do not block** (the three-way match's principle) stands: no variance refuses an invoice.

## Open questions

- [x] Which account, and against what expected price? → **Its own account, Purchase Price Variance, measured against the price on the order; the stock is booked at the order's price so nothing else is "expected"** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] When is it posted? → **At invoice capture, for every line and every size of variance; the tolerance decides only who approves** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] What does it do to inventory cost? → **Units on hand carry it as cost; units sold carry it into cost of sales; inventory-svc decides the split and purchase-svc posts what it says** (industry standard, IAS 2 and moving-average practice, under the user's standing instruction of 2026-09-30).
- [x] Does a re-opened invoice reverse it? → **No: the liability stands; a rejection reverses it** (industry standard, consistent with [supplier-assurance](supplier-assurance.md), under the user's standing instruction of 2026-09-30).
- [x] What of the residue already in GR/IR from earlier invoices? → **A reviewed one-time clearing journal the finance person posts; the platform never rewrites the ledger** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Standard costing? → **Not built: the platform's expected price is the order's, and the standard stays a costing figure** (industry standard, under the user's standing instruction of 2026-09-30).

## Acceptance

- [ ] The pure measure: over, under, exact, `NOT_ON_ORDER`, invoiced above received, rounding to a minor unit of two and of three decimals, a negative quantity on a credit — `PriceVarianceTest`
- [ ] An invoice above the order posts Dr GR/IR at the order's price, Dr Purchase Price Variance, VAT and creditors, and total debits equal credits; below the order posts the credit variance — `PriceVarianceIT.theInvoicePostsItsVarianceToItsOwnAccount`
- [ ] After every delivery is invoiced GR/IR is zero, price differences included — `PriceVarianceIT.grirClearsToNothing`
- [ ] Every size of variance posts; a flagged invoice's decision is unchanged and a REJECT reverses the variance lines — `PriceVarianceIT.inToleranceStillPostsAndRejectionReverses`
- [ ] A re-opened invoice keeps its variance posting — `PriceVarianceIT.reopeningLeavesTheVarianceStanding`
- [ ] An e-invoice captured through the match posts the same — `SupplierEInvoiceIT.anEInvoiceAtAnotherPriceCarriesItsVariance`
- [ ] inventory-svc lifts only the units on hand for FIFO and AVERAGE, answers the split, and purchase-svc posts Dr Stock / Cr Variance for the on-hand part; stock value plus the expensed part equals the variance; a redelivered event does nothing twice — `PriceVarianceHandlerIT.liftsOnlyWhatIsOnHand`, `PriceVarianceIT.theCapitalisedPartMovesToStock`
- [ ] A batch's cost never goes below zero on a large favourable variance — `PriceVarianceHandlerIT.neverBelowZero`
- [ ] Units invoiced before they arrive keep their variance pending and it is capitalised when they are received — `PriceVarianceIT.invoicedAheadOfReceipt`
- [ ] A receipt inventory has not booked is redelivered and lands when it does — `PriceVarianceHandlerIT.waitsForTheReceipt`
- [ ] The report groups by supplier, product and month, splits unfavourable from favourable, gives the share of purchases, drills to lines, translates foreign currency at the invoice's rate and counts what has no rate — `PriceVarianceReportIT.groupsAndDrills`, `PriceVarianceReportIT.foreignCurrencyAtTheInvoicesRate`
- [ ] A store-held manager reads only their stores; a named foreign store is `403 STORE_ACCESS_DENIED` — `PriceVarianceReportIT.storeHeldManagersReadTheirStores`
- [ ] A price-correction credit note posts Dr Creditors / Cr Variance, cannot exceed the recorded variance `422`, cannot be recorded twice `409`, and inventory lowers what it lifted — `PriceCorrectionIT.creditNoteReversesWhatWasRecorded`
- [ ] The opening list shows the residue, the journal posts once under `finance.manual-journal`, a second try is `409 PURCHASE_PRICE_VARIANCE_OPENING_DONE`, a closed month refuses it naming the first open date — `PriceVarianceOpeningIT.clearsTheHistoryOnce`
- [ ] An invoice with no variance and an invoice from before the migration read as before — `PriceVarianceCompatibilityIT`
- [ ] Agreed cost against order price appears in the report and posts nothing — `PriceVarianceReportIT.orderVsAgreedIsShownNotPosted` (after the deals page)
- [ ] **Who and where:** a cashier and a storekeeper are refused the report (`403`); a storekeeper may record a price-correction credit note and may not post the opening journal; a manager narrowed out of `finance.journal` is refused it — `PermissionsIT.priceVarianceIsGated`
- [ ] **Authority:** posting the opening journal asks `finance.manual-journal`; approving a flagged invoice asks `purchasing.invoice-variance` as before — `PriceVarianceOpeningIT`, `InvoiceVarianceApprovalIT` (with the approvals page)
- [ ] **Retry safety:** a retried correction and a retried opening answer with the first and post once — `PriceCorrectionIT.aRetryPostsOnce`
- [ ] **Isolation:** another business's staff of every role and a shopper get 404 or an empty report and can record no correction against our invoices; a request naming our invoice id moves nothing — `PriceVarianceIsolationIT.anotherBusinessMovesNothing`
- [ ] **The record:** the variance, its decision, the correction and the opening each name who and when and stay append-only; the invoice detail shows the variance per line — `PriceVarianceIT.theRecordIsAppendOnly`
- [ ] **Abuse:** one person approving many over-price invoices in a window raises the alert — `PriceVarianceAlertIT.repeatedApprovalsRaiseAnAlert` (with the alerts page)
- [ ] **Location-neutral:** a business in another currency and zone with a different VAT code posts its variance in its own money at its own rate — `PriceVarianceIT.spansBusinessesInDifferentCountries`
- [ ] The Invoices tab shows variance per line and the Finance area shows the report — widgets `price_variance_test.dart`; flow guards stay green; a new k6 `price-variance-flow`

## Screens

- **Admin shell, Procurement, Invoices tab:** each line shows ordered price, invoiced price and the variance in the invoice's currency with the home figure beside it, a chip *Above order* / *Below order*, and how much went into stock and how much into cost of sales once the answer is in; a *Record a price-correction credit note* action with a reference and amounts.
- **Finance area, Reports:** *Purchase price variance* (period picker, group by supplier, product or month, unfavourable and favourable side by side, share of purchases, drill to lines, the count left out for want of a rate); a *Clear the opening balance* banner (once, with the list and a *Post the clearing journal* button that asks for approval where the rule says).
- All from `lib/shared/widgets/`; money through `AppFormat.money`; suppliers and products by name; words not codes.

## Flow Tests entry

- **Area and file:** `procurement` → `target/flow-catalogue/procurement/purchase-price-variance.json` (flow id `purchase-price-variance`, order after `supplier-invoice-three-way-match`). Not BUILT until the file exists and its cases are automated; the three-way-match file gains cross-references.
- **Cases:**
  - *Happy:* invoice above, below and equal to the order; GR/IR clears; the report by supplier, product and month; a price-correction credit note — `PriceVarianceIT`, `PriceVarianceReportIT`, `PriceCorrectionIT`, k6 `price-variance-flow`.
  - *Negative:* a correction above the variance, a duplicate correction, a correction of a rejected invoice, an opening posted twice, a foreign-currency line with no rate — the refusal codes above.
  - *Override:* an out-of-tolerance invoice approved under `purchasing.invoice-variance`; the opening journal under `finance.manual-journal` — `InvoiceVarianceApprovalIT`, `PriceVarianceOpeningIT`.
  - *Isolation:* `PriceVarianceIsolationIT` (every role, shopper, a store-held manager).
  - *Edge:* invoiced ahead of receipt, a partial receipt, a batch whose stock is all sold, AVERAGE and FIFO, a closed month, a redelivered event, a re-opened then rejected invoice — `PriceVarianceIT`, `PriceVarianceHandlerIT`.
  - *Audit:* the record of variance, decision, correction and opening — `PriceVarianceIT.theRecordIsAppendOnly`.

## Decisions

- (2026-09-30 evening, reconciliation) **Ledger account codes are now single** and identical on every page (the existing chart uses 1xxx assets, 2xxx liabilities, 4xxx income, 5xxx purchases, 6xxx expenses; the free numbers were checked against `Domain.java`): Here: *Purchase Price Variance* is `5050`. The full list: `1110` Customer accounts, `1120` Supplier rebates receivable, `1215` Cash in transit to bank, `2340` Unclaimed balances payable, `4040` Supplier promotional funding, `4050` Delivery income, `5040` Purchase rebates, `5050` Purchase price variance, `6420` Gift cards given (existing; goodwill cards), `6530` Cash over and short, `6540` Exchange differences, `6560` Bad debts, `6570` Stock shrinkage, `6571` Stock shrinkage, unexplained, `6572` Stock lost in transit. One seed adds each that is missing (idempotent, by whichever page builds first).

<!-- Filled while building. -->
