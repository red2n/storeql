# Stock counts: blind counts, recounts, tolerance and the counts screens

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on counting (cnt-cycle-counts-stocktakes: no screen CNT-12, no blind count, no recount, no approval of a variance) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, inventory domain: cnt-cycle-counts-stocktakes |
| **Services** | inventory-svc owns counts, rounds, settings and the posting · purchase-svc posts the variance value to the ledger · the app gets the Counts tab |
| **Builds on** | `cycle_count_headers` / `cycle_count_lines` (`V11`), `physical_inventories` / `physical_inventory_tags` (`V13`), `CycleCountResource`, `PhysicalInventoryResource`, `CycleCountAdjusted`, `applyAdjustments`, `transaction_reason_codes` (`FOUND`, `CORRECTION`), `stock.adjust`, ABC analysis |
| **Built in** | |

## Problem

A store can count its stock only by calling the API. There is no screen. When it is called, the count sheet is created with the system quantity already filled in, so the person counting is shown the answer they are supposed to be checking. A line that is out of tolerance is just flagged; there is no recount step, no record of who recounted, and a re-entry silently overwrites the first figure. Tolerance is a number baked into the table (5 per cent) that nobody chose. Approving and posting need `stock.adjust`, which a storekeeper holds by default, so the person who counted can also approve their own variance. And a count's variance reaches no ledger.

## Outcome

- **A counter never sees what the system expects.** The count sheet shows the product, the zone and an empty field. They see nothing of the system quantity, before or after they enter their count, and nothing of a previous round.
- **A line off by more than the business's tolerance is recounted before anyone is asked to approve it.** The recount is a step of its own, done by someone other than the first counter, and every round is kept with who and when.
- **A reviewer sees both counts side by side**, the system quantity and the value of the variance, and approves, or asks for another recount, or rejects.
- **Tolerance is the business's own setting**, by percentage and by value, and when nothing is set every difference is reviewed.
- **Posting moves the stock, gives each movement its reason, and tells the ledger the value.**
- **The count is a screen**: start, count, recount, review, post, on a phone or a wider window.
- **Sales during a count do not create false variances**: the system quantity is read at the moment a count is entered, not when the sheet was made.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the storekeeper (counts, recounts), the store manager (reviews, approves, posts), the owner (sets tolerance), the finance clerk (sees the variance on the ledger).
- **Channels:** back-office (admin app, Inventory > Counts), on a phone as much as a desk.
- **Scope:** per store; a physical inventory can be narrowed to a zone. A store-held caller works only at their stores.
- **Roles that can write:** counting and recounting need any staff assigned to the store (as entering a count is today: it moves no stock); creating, approving and posting need `stock.adjust` (already so); approving a line one has counted oneself is refused; the tolerance is management's. A variance above the business's ceiling needs a second person through [approvals](approvals.md), action key `stock.cycle-count-post`, what is approved: posting a count whose net variance value is over the ceiling.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Count settings** (inventory-svc). `count_settings` per business (and optionally per store): `tolerance_pct`, `tolerance_value` (home currency), `blind` (default on), `recount_by_other` (default on). Unset tolerance means zero: every difference goes to review. The DB default of 5.00 on `cycle_count_headers.tolerance_pct` is dropped for new counts; the header takes its tolerance from the setting (a request may narrow it, never widen). `GET/PUT /admin/inventory/count-settings` (management).
  2. **Blind sheet** (inventory-svc). `GET /cycle-counts/{id}/sheet` and `GET /physical-inventories/{id}/sheet` answer lines with product, zone, unit and the line id, and never `systemQty`, `variance` or an earlier round. The existing detail answers keep their fields but drop `systemQty`/`variance` for a line that has not been counted, and for a caller who has counted it and has not been asked to review. A header made with `blind: false` (a business that chooses to) shows them as today.
  3. **System quantity read at count time.** When a count is entered, the server reads the store's on-hand (available plus reserved plus expired, never in bond, as the count's scope says) and stores it on that round. Variance is computed from it.
  4. **Rounds and recount** (inventory-svc). `count_rounds` (append-only): line, round number, counted quantity, counted by, counted at, the system quantity at that time. Entering a count adds a round. A line whose variance exceeds tolerance becomes `RECOUNT_REQUIRED` (a new status) instead of only being flagged; `POST .../lines/{lineId}/recount` adds the next round, refused from the same person as the last round (`409 COUNT_RECOUNT_SAME_PERSON`) when `recount_by_other` is on. A recount within tolerance of either of the earlier rounds moves the line to review with both rounds shown; a recount differing from both asks for a third. Physical-inventory tags follow the same rules.
  5. **Review and approve** (inventory-svc). `GET .../review` shows, to a `stock.adjust` holder who did not count the line, every round, the system quantity read at the time, the variance, its value at cost (costless stock shown as uncosted, never valued at zero silently). `POST .../approve` (existing) now takes optional per-line `acceptRound` and `reject`; a caller who counted a line cannot approve it (`403 COUNT_CANNOT_APPROVE_OWN`). `409 COUNT_LINES_NOT_READY` while any counted line still requires a recount.
  6. **Posting and its ledger value** (inventory-svc, purchase-svc). Posting (`/adjust`, `/complete`, already needing `stock.adjust` and moving batches since 29 Sep) writes each movement with reason code `COUNT_LOSS` or `COUNT_GAIN` (seeded) and the `CycleCountAdjusted` event carries per-line variance, unit cost and value in the home currency. purchase-svc consumes it once and posts: a loss Dr *Stock shrinkage* (new expense account) / Cr 1001 Stock; a gain the reverse. **One rule so that nothing is posted twice (2026-09-30, shared with [expired-and-short-dated-stock](expired-and-short-dated-stock.md) and [transfer-discrepancies](transfer-discrepancies.md)):** `StockAdjusted` gains an additive `origin` (`MANUAL`, `COUNT`, `DISPOSAL`, `YIELD`, `TRANSFER`, `RTV_OVERRIDE`). purchase-svc posts shrinkage from exactly one event per cause: `CycleCountAdjusted` for counts, `StockDisposed` for disposals, `TransferDiscrepancyRecorded` for transit loss, and from `StockAdjusted` **only when `origin` is `MANUAL`** and the delta is a loss (a plain write-off; a slice of purchase-svc that does not exist yet). The `StockAdjusted` a count, a disposal, a yield run or a return-to-vendor override publishes is not posted. The expense accounts (*Stock shrinkage*, *Stock lost in transit*) are added to the chart once, by whichever of these builds first (`PurchaseSeed`, idempotent).
  7. **The Counts tab** (Flutter). See Screens.
- **Out, on purpose:**
  - **A store freeze during a physical inventory.** Selling continues; the system quantity is read at count time (slice 3), which is what makes counting under trading accurate. A freeze is a per-business trading rule nobody asked for.
  - **Serialised and lot-by-lot counting.** Counts are by product (and zone); serial and lot reconciliation stays with lot genealogy.
  - **Handheld barcode counting as a device feature.** The count field takes typed or scanned input like any field; a scanner integration is the till's own feature.
  - **Automatic scheduling of cycle counts** (a count every week per ABC class). A person starts a count; a scheduler is its own feature.
  - **Counting another business's or a warehouse's consignment stock as ours.** Consignment stock is counted but reported apart, as valuation does today.

## Data and flow

- **Owned by inventory-svc:**
  - `count_settings` (tenant_id, store_id nullable, tolerance_pct NUMERIC(6,2) null, tolerance_value NUMERIC(18,4) null, blind boolean, recount_by_other boolean, changed_by, changed_at).
  - `count_rounds` (append-only): id, tenant_id, line kind (CYCLE or PHYSICAL) and line id, round_no, counted_qty NUMERIC(18,3), system_qty NUMERIC(18,3), counted_by, counted_at. Index (tenant_id, line_id, round_no).
  - `cycle_count_lines.status` gains `RECOUNT_REQUIRED`; `cycle_count_headers` gains `blind`, `started_by`; physical tags gain the same status.
  - Seeded platform reason codes: `COUNT_LOSS`, `COUNT_GAIN`.
- **Needs from other services:** the home currency (`TenantProfiles`); staff names for the review screen from iam-svc through the staff-logins projection the app already reads. No joins.
- **Events published:** `CycleCountAdjusted` gains per line `variantId`, `varianceQty`, `unitCost`, `value`, `currency`, and `countedBy`/`approvedBy`; consumed by reporting-svc (as now) and purchase-svc (posts once, keyed by the event id). No event for rounds (internal).
- **Retryable writes (Idempotency-Key):** create count, enter count, recount, approve, adjust/complete (a retry of a post must not post twice; today a second post is `422 CYCLE_COUNT_CLOSED`, kept).
- **New error codes:** `409 COUNT_RECOUNT_SAME_PERSON`, `403 COUNT_CANNOT_APPROVE_OWN`, `409 COUNT_LINES_NOT_READY`, `422 COUNT_NOT_RECOUNTABLE` (a line within tolerance), `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** home currency only; tolerance by value and every variance value are in it.
- **Ledger postings:** Dr Stock shrinkage / Cr 1001 for a net loss line, reverse for a gain, on the day the count was posted.
- **Dates:** UTC instants; the screen shows the store's own day and time.
- **Plan limits:** none.

## Constraints

- Append-only: `count_rounds`, `stock_movements`. A correction is a further round or a further movement.
- Tenant first in every query; store access on every read and write.
- The blind rule is enforced at the API, not by the screen: a modified client cannot read what the server does not send.
- Existing counts keep their stored tolerance and their visible quantities (`blind` false on those rows).

## Already there

- Cycle counts (`POST/GET /admin/inventory/cycle-counts`, lines, count, approve, adjust) and physical inventories (create, tags, count, complete) exist and gate creating, approving and posting on `stock.adjust` (`CycleCountResource` 68/175/199, `PhysicalInventoryResource` 65/134/183); tested by `PermissionsIT.cycleCountsAreGated`, `StoreScopeIT.cycleCountsHoldToTheCallersStores`, `StoreScopeIT.cycleCountsStayInTheirBusiness`. Entering a count needs no permission by design and moves no stock.
- Since 29 Sep 2026, completing a count really moves batches and needs `stock.adjust` (wave 1).
- Auto-approval within a per-count tolerance already exists; only the source of the tolerance and the recount are new.
- Not there: any screen, any blind sheet, any recount, any posting to the ledger.

## Open questions

- [x] Is a count blind by default? → **yes, blind unless the business turns it off** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who may recount? → **someone other than the first counter, unless the business turns that off** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What tolerance applies when none is set? → **none: every difference is reviewed; the business sets a percentage and/or a value** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] May the counter approve their own variance? → **no; a second person, with a value ceiling above which approvals applies** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How do sales during a count stay out of the variance? → **the system quantity is read when the count is entered** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] The sheet and the detail never carry `systemQty` or `variance` for an uncounted line, nor a previous round to the recounter — `CycleCountBlindIT.theSheetNeverShowsWhatTheSystemExpects`
- [ ] A count entered after a sale uses on-hand at entry time — `CycleCountBlindIT.salesDuringACountAreNotVariance`
- [ ] An out-of-tolerance line becomes RECOUNT_REQUIRED; a recount by the same person is refused `409 COUNT_RECOUNT_SAME_PERSON`; by another person adds round 2 — `CycleCountRecountIT`
- [ ] With no setting, any difference is reviewed; with a setting, within tolerance auto-approves by percentage or value — `CountSettingsIT`, `CountToleranceTest` (pure)
- [ ] A counter cannot approve their own line `403 COUNT_CANNOT_APPROVE_OWN`; a cashier is refused `PERMISSION_DENIED` — `CycleCountRecountIT`, `PermissionsIT.cycleCountsAreGated`
- [ ] Posting moves batches with `COUNT_LOSS`/`COUNT_GAIN` and publishes the value once — `CycleCountPostingIT`
- [ ] purchase-svc posts Dr shrinkage / Cr 1001 once on a redelivered event — `CountVariancePostingIT`
- [ ] Physical inventory tags follow the same blind, recount and review rules — `PhysicalInventoryBlindIT`
- [ ] Another business's staff of every role naming our count or store get 404/`STORE_ACCESS_DENIED` and nothing is read or written; their manager's settings do not touch ours — `CycleCountTenantIsolationIT`
- [ ] Widget: the sheet shows no system quantity; the review shows both rounds — `counts_screen_test`
- [ ] k6 `stock-count-flow`: create, count, recount by a second staff login, approve, post, see the stock and the ledger move

## Screens

- **Admin > Inventory > Counts** (a new tab beside Levels, Batches, Transfers). A list of counts by store and status (status badges in words: Counting, Recount needed, Waiting for review, Posted). **Start count** (adaptive sheet): store, kind (Cycle count with ABC classes, or Full count, optionally one zone); tolerance shown from the business setting and not editable above it.
- **Count sheet** (counter): a `ScrollableTable`/list of lines, product by name (`variantLabelsProvider`), zone, a numeric field, Save per line; never a system figure. A recount line is marked "Recount" and hides the first figure.
- **Review** (manager): per line both rounds, expected, difference, value (`AppFormat.money`), actions Approve / Ask for recount / Reject; **Post adjustments** with a confirmation naming the net value; refusals in words; Idempotency-Keys reused on retry (as the Adjust dialog does).
- **Settings**: tolerance percentage and value, blind, recount by another person, on the business's inventory settings (management).

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet.
