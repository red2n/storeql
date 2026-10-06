# Known loss and unknown loss, kept apart: from the reason code to the ledger to the shrink report

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Readiness Review's Loss prevention and shrink row "Known-loss and unknown-loss separation" (16.8, value, Missing) · 2026-09-30 |
| **Roadmap** | Review 16.8; the Oracle audit's "budgeted shrink rates" is out (below) |
| **Services** | inventory-svc owns reason codes, the movements they mark, the reclassification and the shrink report · purchase-svc posts the loss to the ledger by kind · product-svc's categories reach inventory-svc as events · notification-svc and the alerts inbox tell the people concerned · the app gets the report and the screens |
| **Builds on** | `transaction_reason_codes` (V17: `DAMAGED`, `FOUND`, `THEFT`, `EXPIRY`, `VENDOR_RETURN`, `CORRECTION`, `SAMPLING`; platform-wide with tenant-added ones; **no kind, and adjust does not validate against the table**), `stock_movements.reason_code` and `actor` (inventory-svc `V1__init.sql`), `ShrinkageResource` / `ShrinkageRepository` (`GET /admin/inventory/reports/shrinkage`, ADJUST movements only, groups by REASON, ACTOR or STORE), [stock-counts](stock-counts.md) slice 6 (`COUNT_LOSS`/`COUNT_GAIN`, `CycleCountAdjusted`, the one-cause-one-posting rule, *Stock shrinkage*), [expired-and-short-dated-stock](expired-and-short-dated-stock.md) (`StockDisposed`, method DONATED), [transfer-discrepancies](transfer-discrepancies.md) (`TRANSIT_LOSS`, `TRANSIT_DAMAGE`, `MISCOUNT_AT_SHIP`, the transit account), [goods-receipt-detail](goods-receipt-detail.md) (`RTV_OVERRIDE`), [shipping-notices-and-direct-deliveries](shipping-notices-and-direct-deliveries.md) (`RECEIPT_CORRECTION`), [exception-alerts](exception-alerts.md) (`adjustments.count/value`), the Reasons screen (`inventory_adjust_reason_codes_test`), the Admin Reports screens |
| **Built in** | not built |

## Problem

A store loses stock in two very different ways, and the platform reports them as one number. Known loss is what somebody saw and recorded: a dropped jar, an expired tray thrown out, a donation, a theft caught on camera. Unknown loss is what a count finds and nobody can explain. A business that wants to fix waste needs the first; a business that wants to find theft needs the second; a store whose loss is mostly unexplained is telling the owner something that a total never does. Today the shrinkage report groups ADJUST movements by reason, person or store, but a reason code is free text (adjust never checks it against the table, and it may be empty), nothing says which codes are known and which are not, transit loss and count variance sit in other records the report never reads, the ledger has one shrinkage account, and there is no category view, which is how a category manager thinks.

## Outcome

- **Every loss carries which kind it is:** known (damage, waste, expiry, recorded theft, donation, a transit loss on a route) or unknown (a count variance, a shortfall found at shipping, an unexplained correction). It comes from its reason code, which is now required and checked, and is fixed on the movement when it is written.
- **The shrink report splits the two,** by store and by category (and by reason, day and person), with what was lost, what was found, the cost, and the loss as a share of what the store sold; transit losses and count variances are in it, not beside it.
- **The ledger records the two apart:** known loss to *Stock shrinkage*, unknown loss to *Stock shrinkage, unexplained*.
- **An unknown loss can later be explained** by a manager who did not cause it: it moves to a known reason, on the record, and the ledger follows.
- **A store whose loss is mostly unexplained is flagged** to the owner, at a share the business chooses.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store manager and area manager (read, explain), the owner (settings, alerts), the loss-prevention lead, the finance clerk (the ledger), the storekeeper (records loss with a reason).
- **Channels:** back-office (Admin > Reports > Shrink, Inventory > Adjust, Settings > Reasons).
- **Scope:** per store, business-wide for the owner; a store-held manager reads only their stores.
- **Roles that can write:** recording a loss stays `stock.adjust` at the store (any staff who hold it) and a reason is now required; creating a business's own reason code with its kind is management's ([inventory-screens](inventory-screens.md) slice 1); **explaining an unknown loss** is OWNER or MANAGER at the store, never the person who wrote the movement; reading the report: management by the shared reports rule (`TenantContext.reportStores`).
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Reason codes carry a kind, and a reason is required** (inventory-svc). `transaction_reason_codes` gains `kind` (`KNOWN` | `UNKNOWN` | `NEUTRAL`; not null; a platform code's kind is fixed, a business's own code chooses one when it is made and cannot change it afterwards, because past movements were classed by it). `NEUTRAL` is for what is not a loss at all (goods returned to a supplier with a credit, a receipt corrected, a record fixed before a return). Seeded kinds: **KNOWN** `DAMAGED`, `EXPIRY`, `THEFT` (a theft that was recorded: seen, reported or admitted; a shortfall with no evidence is not theft, it is unknown), `SAMPLING`, `TRANSIT_LOSS`, `TRANSIT_DAMAGE`, and two new seeds `WASTE` (spoilage, spillage, prep and trim not covered by a yield template) and `DONATED`; **UNKNOWN** `COUNT_LOSS`, `COUNT_GAIN`, `FOUND`, `MISCOUNT_AT_SHIP`, `CORRECTION` (a correction nobody can explain further is unexplained until someone explains it); **NEUTRAL** `VENDOR_RETURN`, `RTV_OVERRIDE`, `RECEIPT_CORRECTION`. Adjust (`POST /admin/inventory/adjust`) and the batch adjust now **require an active reason** (`400 INVENTORY_REASON_REQUIRED`), checked against the business's and the platform's codes (`400 INVENTORY_REASON_UNKNOWN`), and write `stock_movements.loss_kind` from the code's kind **at that moment** (the movement is append-only; a code's kind never changes). Movements the engine writes take the seeded kind of their cause (a count line `COUNT_LOSS` or `COUNT_GAIN`, a disposal `EXPIRY`, or `DONATED` when the method is donated (a change to [expired-and-short-dated-stock](expired-and-short-dated-stock.md) slice 3, which wrote `EXPIRY` for every method), a transfer discrepancy its own). History with no reason is classed **UNKNOWN** by the migration, honestly: nobody said. A yield run's loss is **not** shrink: it is reported apart against expected (`GET /admin/inventory/yield/runs`).
  2. **The shrink report** (inventory-svc). `GET /admin/inventory/reports/shrinkage` is extended (the existing answer keeps its fields): `groupBy` gains `KIND`, `CATEGORY` (a category of the business's tree at a `depth` from the top, default the top level; a product with no category is `UNCATEGORISED`, shown, never dropped) and `DAY` (the store's own day); the existing REASON, ACTOR and STORE stay. A row carries known loss, unknown loss, gains found, net, all in quantity and at cost in the home currency (costless stock is counted in quantity and shown as *uncosted*, never valued at zero silently), and `lossRatePct`: loss at cost as a share of the cost of what left the shelf by sale in the same period, from the same movement ledger the gross-margin report already prices from (null where there was no sale, never zero). **Sources**: ADJUST movements with their `loss_kind` (NEUTRAL are shown apart as `neutral`, not in loss); transfer discrepancies (SHORT and DAMAGED are known loss at the shipped cost, OVER is a gain) so a route's loss is in the same view; count variances arrive as ADJUST movements already. Drill-down: the existing top-variants read takes `kind`. Categories come from inventory-svc's own `variant_categories` projection, fed by product-svc's existing `ProductCategorised` event (the same projection [product-groups-and-schedules](product-groups-and-schedules.md) slice 1 needs; whichever builds first adds it once, idempotent on the event's `eventId`, a later word wins). A store-held manager sees only their stores' rows and business-wide totals are for a caller held to none (`reportStores`).
  3. **The ledger by kind** (purchase-svc). One chart-of-accounts seed adds *Stock shrinkage, unexplained* beside *Stock shrinkage* and *Stock lost in transit* (idempotent, by whichever page builds first). `StockAdjusted` (additive `reasonCode`, `lossKind`), `CycleCountAdjusted` (each line `lossKind`), `StockDisposed` and `TransferDiscrepancyRecorded` carry the kind; purchase-svc posts each loss once per event to the account of its kind: **KNOWN** to *Stock shrinkage* (the transit loss keeps its own account, as [transfer-discrepancies](transfer-discrepancies.md) designed), **UNKNOWN** to *Stock shrinkage, unexplained*, gains the reverse; NEUTRAL posts nothing from here. This **refines [stock-counts](stock-counts.md) slice 6**, which posted every count variance to *Stock shrinkage*: count variances now post to the unexplained account, and the one-cause-one-posting rule stands unchanged. The purchase-svc write-off posting for `origin: MANUAL` that stock-counts named ("a slice that does not exist yet") is built here with the kind.
  4. **Explaining an unknown loss** (inventory-svc, purchase-svc). `POST /admin/inventory/movements/{id}/explain {reasonCode, note}` (Idempotency-Key; OWNER/MANAGER at the movement's store, **not** the person who wrote it, `403 INVENTORY_LOSS_EXPLAIN_OWN`) for an UNKNOWN **loss** (a negative movement) with a KNOWN reason (`409 INVENTORY_LOSS_NOT_UNKNOWN`, `422 INVENTORY_REASON_NOT_KNOWN`; a note is required, `400 INVENTORY_LOSS_NOTE_REQUIRED`). It writes a `loss_reclassifications` row (append-only; a movement is explained once) and publishes `LossReclassified`; the report reads the latest kind per movement, the movement itself is never rewritten. purchase-svc posts Dr *Stock shrinkage* / Cr *Stock shrinkage, unexplained* at the movement's cost, dated the explanation (redated to the first open day if that month is closed, `document_date` kept, as [accounting-periods](accounting-periods.md) says). Only unknown to known: a known loss is never turned back into unknown, and a gain is never explained (nothing to hide by it). No stock moves.
  5. **A store whose loss is mostly unexplained** ([exception-alerts](exception-alerts.md), inventory-svc). Two new metrics, below: `loss.unknown_share` (needs a `min_sample` of loss value or events, off until the business sets a rule) and `loss_reclassifications.count` / `.value` (one person explaining a lot of losses). No threshold is named by the platform.
  6. **The screens** (Flutter). See Screens.
- **Out, on purpose:**
  - **Budgeted shrink rates and a target to compare with.** Oracle has budgeted rates (RMFCS); the platform names no rate, and a business's budget is a separate finance feature. The report shows the actual rate only.
  - **Deciding whether a loss is theft.** A recorded theft is a reason a person chose with evidence; the platform never infers it, and a count variance is never called theft.
  - **Attributing an unknown loss to a shift or a person automatically.** The by-person view is the reason's recorder; a suspicion is a human's, and a pattern is an alert.
  - **Turning known into unknown, or rewriting a movement.** Append-only.
  - **Yield loss as shrink.** It is compared to its template's expected loss in its own report.
  - **Point-of-sale shortage and cash over/short.** Till variance is [till sessions](till-sessions-and-registers.md).
  - **A second person to explain a loss.** The person who wrote the movement cannot explain it, and the metric watches the rest; nothing moves in stock, and the two expense accounts net to the same total.

## Data and flow

- **Owned by inventory-svc:**
  - `transaction_reason_codes` gain `kind` (CHECK `KNOWN | UNKNOWN | NEUTRAL`), backfilled for the seeded codes; two new seeded codes `WASTE`, `DONATED` (the seeds use `Ids` in the migration like the others).
  - `stock_movements` gain `loss_kind` (nullable; set on ADJUST movements, null elsewhere), backfilled by the migration from `reason_code` (no reason: `UNKNOWN`).
  - `loss_reclassifications` (append-only): id, tenant_id, movement_id (unique with tenant), store_id, from_kind, to_reason_code, note, value at cost and currency, explained_by, explained_at; index `(tenant_id, store_id, explained_at)`.
  - `variant_categories` projection (shared with product groups).
  - The shrinkage repository reads `stock_movements`, `loss_reclassifications`, `transfer_discrepancies` and `variant_categories` (all its own).
- **Owned by purchase-svc:** the chart seed (*Stock shrinkage, unexplained*), the postings and their once-per-event guard (existing pattern keyed by `eventId`).
- **Needs from other services:** categories (event); the home currency (`TenantProfiles`); the store's zone for `DAY`. No joins.
- **Events published (outbox, `eventId` last):** `StockAdjusted` (additive `reasonCode`, `lossKind`), `CycleCountAdjusted` (additive per-line `lossKind`), `StockDisposed` (kind KNOWN by construction), `TransferDiscrepancyRecorded` (kind), `LossReclassified` (`storeql.inventory.loss-reclassified`: tenantId, movementId, storeId, variantId, qty, value, currency, fromKind, toReasonCode, explainedBy) → purchase-svc (posts once), reporting-svc; `ExceptionAlertRaised` through the shared block.
- **Retryable writes (Idempotency-Key):** adjust (existing), explain, a reason code's creation.
- **New error codes:** `400 INVENTORY_REASON_REQUIRED`, `400 INVENTORY_REASON_UNKNOWN`, `400 INVENTORY_REASON_KIND_REQUIRED` (a new business code without a kind), `409 INVENTORY_REASON_KIND_FIXED` (changing a code's kind), `404 INVENTORY_MOVEMENT_NOT_FOUND`, `409 INVENTORY_LOSS_NOT_UNKNOWN`, `422 INVENTORY_REASON_NOT_KNOWN`, `403 INVENTORY_LOSS_EXPLAIN_OWN`, `400 INVENTORY_LOSS_NOTE_REQUIRED`, `409 INVENTORY_LOSS_ALREADY_EXPLAINED`, `403 STORE_ACCESS_DENIED`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** the home currency; a loss is at the batch's cost, uncosted stock counted in quantity and flagged, never zero-valued silently.
- **Ledger postings:** loss: Dr *Stock shrinkage* (KNOWN) or *Stock shrinkage, unexplained* (UNKNOWN) / Cr 1001 Stock, at cost, on the day of the movement; a gain the reverse; an explanation Dr *Stock shrinkage* / Cr *unexplained*, dated the explanation. A transit loss stays in its own account.
- **Dates:** UTC instants; `DAY` in the store's own zone; a report period defaults as the existing one does.
- **Plan limits:** none.

## Constraints

- Append-only: `stock_movements`, `loss_reclassifications`, discrepancies. The report's "latest kind" is computed, never written back.
- A kind is fixed at write: a code's kind cannot change, so history never re-classes itself.
- Existing tenants: history with no reason reads UNKNOWN; **adjust without a reason is now refused** (behaviour change, stated: the app's Adjust dialog already offers the codes since 30 Sep; k6 fixtures and any API client must send one).
- The one-cause-one-posting rule of [stock-counts](stock-counts.md) slice 6 stands: purchase-svc posts from `CycleCountAdjusted`, `StockDisposed`, `TransferDiscrepancyRecorded`, and from `StockAdjusted` only with `origin: MANUAL`.
- Golden rule 1: categories by event; purchase-svc posts from event values.
- Never invent policy: no threshold, no target rate, no default share; unset means no alert.

## Already there

- The shrinkage report exists (`GET /admin/inventory/reports/shrinkage`, `ShrinkageResource`), tenant-first, store-scoped through the caller's stores, grouping ADJUST movements by reason, actor or store with losses and finds apart (`InventoryIT` shrinkage cases, `StoreScopeIT`); the top-variants read exists.
- Reason codes exist and are managed (`ReferenceDataResource`), a reason and actor land on each adjust movement (inventory-svc `V1__init.sql`), and the app's Adjust dialog offers the codes (`inventory_adjust_reason_codes_test`).
- Not there: a kind on a code, a check of the reason, a required reason, transit losses and count variances in the report, a category or day view, a rate, two ledger accounts, an explanation.

## Open questions

- [x] What is "known" and what "unknown"? → **known is what a person saw and recorded with a reason (damage, waste, expiry, recorded theft, donation, a transit loss on a route); unknown is a variance nobody explained (count, ship-time miscount, an unexplained correction)** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who decides a code's kind? → **the platform for its seeded codes; a business for its own, once, at creation** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a reason required? → **yes, on every adjustment, from an active code** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Do the two kinds post apart? → **yes: two expense accounts, known and unexplained** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can an unknown loss be explained later? → **yes, once, to a known reason, by a manager who did not write it, on the record; never the other way** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is there a target shrink rate? → **no: the platform names none; the report shows the actual rate against sales** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Approval and alerts? → **no approvals key (explaining moves nothing net and is barred for the movement's own writer); alerts `loss.unknown_share`, `loss_reclassifications.count`/`.value`** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Every seeded code has a kind; a business's own code needs one and cannot change it — `ReasonCodeKindIT.kindsAreSeededAndFixed`
- [ ] An adjustment with no reason is `400 INVENTORY_REASON_REQUIRED`; an unknown or inactive one `400 INVENTORY_REASON_UNKNOWN`; the movement carries the code's kind, and history with no reason reads UNKNOWN — `AdjustReasonIT`, `LossKindMigrationIT`
- [ ] A count's variance is UNKNOWN, a disposal KNOWN (a donation as `DONATED`), a transit shortfall KNOWN, a miscount at shipping UNKNOWN — `LossKindIT.theEngineClassesItsOwnMovements`
- [ ] The report splits known from unknown by store, by category (top level and deeper, uncategorised shown), by reason, day (in two zones) and person; transit losses and count variances are in it; neutral is apart — `ShrinkageReportIT.knownAndUnknownByStoreAndCategory`
- [ ] The rate is loss at cost over the cost of what was sold, null with no sales, uncosted stock flagged not zeroed — `ShrinkageReportIT.rateAndUncosted`, `ShrinkageRateTest` (pure)
- [ ] A store-held manager sees only their stores; naming another is `403 STORE_ACCESS_DENIED`; a cashier is refused — `StoreScopeIT.shrinkageHoldsToTheCallersStores`, `PermissionsIT.shrinkageIsManagements`
- [ ] purchase-svc posts a known loss to *Stock shrinkage* and an unknown to *Stock shrinkage, unexplained*, once on a redelivered event, gains reversed, neutral not posted; a count variance now posts to the unexplained account — `LossPostingIT`, `CountVariancePostingIT` (updated)
- [ ] A manager other than the writer explains an unknown loss to a known reason with a note: the report re-classes it, a reclass row is written once, purchase-svc posts Dr shrinkage / Cr unexplained once dated the explanation — `LossExplainIT.aManagerExplainsAnUnknownLoss`
- [ ] The writer is refused `403 INVENTORY_LOSS_EXPLAIN_OWN`; a known loss, a gain, a second explanation, a non-known reason and no note are each refused with their code — `LossExplainIT.refusals`
- [ ] A month closed at explanation time redates to the first open day and keeps `document_date` — `LossPostingIT.anExplanationInAClosedMonthRedates`
- [ ] Alerts: a store over the business's `loss.unknown_share` rule raises one alert per window; one person explaining many losses raises `loss_reclassifications.*`; with no rule nothing is raised — `LossAlertIT`
- [ ] Isolation: another business's staff of every role and a shopper, naming our movement, code or report store, get 404/empty and nothing moves; their codes never appear in ours — `LossTenantIsolationIT`
- [ ] Retry: the same key answers with the first on adjust, explain, code creation — `LossExplainIT.retries`
- [ ] The record: kind fixed at write, explanation append-only with who, when and why — `LossExplainIT.theRecordIsAppendOnly`
- [ ] A business in another country and zone reports and posts in its own currency and days — `ShrinkageReportIT.spansBusinessesInDifferentCountries`
- [ ] Widgets: the Reasons screen shows and sets kind; the report shows the split; Explain from a count line — `reason_codes_test`, `shrink_report_test`, `loss_explain_test`
- [ ] k6 `loss-flow`: a reason-less adjust refused, a known loss and a count variance recorded, the report splits them, a second manager explains one, the ledger shows both accounts

## Decisions

- (2026-09-30 evening, reconciliation) **Ledger account codes are now single** and identical on every page (the existing chart uses 1xxx assets, 2xxx liabilities, 4xxx income, 5xxx purchases, 6xxx expenses; the free numbers were checked against `Domain.java`): Here: *Stock shrinkage* `6570`, *Stock shrinkage, unexplained* `6571`, *Stock lost in transit* `6572`. The full list: `1110` Customer accounts, `1120` Supplier rebates receivable, `1215` Cash in transit to bank, `2340` Unclaimed balances payable, `4040` Supplier promotional funding, `4050` Delivery income, `5040` Purchase rebates, `5050` Purchase price variance, `6420` Gift cards given (existing; goodwill cards), `6530` Cash over and short, `6540` Exchange differences, `6560` Bad debts, `6570` Stock shrinkage, `6571` Stock shrinkage, unexplained, `6572` Stock lost in transit. One seed adds each that is missing (idempotent, by whichever page builds first).

- 2026-09-30: settled by industry standard as above; nothing built yet.
- **New alert metrics** for [exception-alerts](exception-alerts.md), owned by inventory-svc: `loss.unknown_share` (share of a store's loss value in the period that is unexplained; needs `min_sample`; subjects store) and `loss_reclassifications.count`, `loss_reclassifications.value` (subjects staff, store). **No new approvals key.**
- **Refines [stock-counts](stock-counts.md) slice 6** (variances post to *Stock shrinkage, unexplained*) and **[expired-and-short-dated-stock](expired-and-short-dated-stock.md) slice 3** (a donation writes with `DONATED`). The old texts stand for everything else.
- **`stock_movements.loss_kind`** is a snapshot at write; the report's latest kind is derived.

## Screens

- **Admin > Reports > Shrink** (management; the existing shrinkage report gains the split): a period and store picker; a summary of *Known loss*, *Unexplained loss*, *Found*, the rate against sales, each in the business's currency; a table grouped by store, category (with a depth control), reason, day or person, known and unexplained side by side with a share bar in the status roles (never a hard-coded colour); a row opens the movements behind it; transit losses are marked as such. An empty state says how to start (record losses with a reason).
- **Inventory > Adjust dialog** (existing): the reason is required and grouped in words (*Known: damaged, waste, expired, donated*; *Unexplained: stock found, correction*); a refusal reads in words.
- **Inventory > Counts > Review** ([stock-counts](stock-counts.md)) and the movements list: **Explain this loss** on an unexplained loss for management who did not write it: pick a known reason, add a note; shows who explained it afterwards.
- **Settings > Reasons** (management): the list with a Kind column and a Kind field when adding a code, with a warning that it cannot be changed.
- Everything through `PageHeader`, `AdaptiveActions`, `ScrollableTable`, `showAdaptiveSheet`, `StatusBadge`, `AppFormat.money`, `EmptyState`, `ErrorView`; words not codes.

## Flow Tests entry

- **Catalogue area and file:** `target/flow-catalogue/inventory/known-and-unknown-loss.json` (the inventory domain, beside `inv-stock-adjustments-writeoffs`; the Loss prevention and shrink area of the Review points to it). The feature is not BUILT until this entry exists and its cases are automated.
- **Cases:**
  - Happy: known and unknown losses recorded, the report splits them by store and category (`ShrinkageReportIT.knownAndUnknownByStoreAndCategory`, k6 `loss-flow`); the ledger posts both accounts (`LossPostingIT`); a manager explains an unknown loss (`LossExplainIT.aManagerExplainsAnUnknownLoss`).
  - Negative: no reason; unknown reason; a code without a kind; the writer explaining their own; a known loss, a gain or an already explained loss explained; a non-known target reason; no note.
  - Override: a business adds its own code with a kind; a manager explains in a closed month (redated).
  - Isolation: other business's staff of every role and a shopper; a store-held manager reading another store; codes never cross businesses.
  - Edge: history with no reason; uncosted stock; a product with no category; a store with no sales (rate null); two zones for `DAY`; a transit OVER as a gain; a redelivered event posting once.
  - Audit: kind fixed at write; explanations append-only with who and why; alerts raised by a mostly-unexplained store and by many explanations (`LossAlertIT`).
