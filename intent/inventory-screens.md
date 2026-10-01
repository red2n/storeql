# Inventory screens that do not exist, and the policy writes behind them

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on inventory operations (trf-move-orders TRF-23, inv-reorder-points-and-eoq, cnt-cycle-counts-stocktakes CNT-12) and the Flutter note of 30 Sep 2026 · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, inventory domain: trf-move-orders, inv-reorder-points-and-eoq; also the API-only inventory endpoints found while checking |
| **Services** | inventory-svc owns every endpoint named here · the app (Admin shell, Inventory) gets the screens · tenant-svc supplies store and zone names; product-svc the product names |
| **Builds on** | `MoveOrderResource`, `ReorderPointResource` (management-gated since 30 Sep), `SafetyStockResource`, `AbcAnalysisResource`, `PlanningConfigResource` (par levels), `KanbanResource`, `SerialResource`, `LotResource`, `CostingResource`, `PickingRuleResource`, `ReferenceDataResource`; the Inventory tabs Levels, Batches, Transfers, Movements, Thresholds, Forecast, Reduce to clear, Bond & duty, Yield & prep, Picking & putaway, Depot & shops |
| **Built in** | |

## Problem

Part of inventory-svc can only be driven by calling the API. The Flutter note of 30 Sep 2026 verified that there is no screen for cycle counts and physical inventory, move orders, or reorder-point and EOQ plans, and a search of the app for every path an inventory resource declares finds more with no reference at all: safety stock, ABC classes, par levels, kanban cards, serial numbers, lot genealogy, costing methods and accounting periods, picking rules and their assignments, source types and zone-to-ledger mappings. A manager who wants one of these asks a developer. Checking them also showed that several of these writes set policy or close the books and are open to any staff member, because `/admin/inventory/**` is the staff tier: closing an accounting period, mapping a zone to a ledger account, compiling ABC classes, setting a safety stock or a par level.

## Outcome

- **Every inventory capability the platform has is reachable by the person who owns it, in the admin app**, in words, adaptive from phone to desk, with the same look as the tabs that exist.
- **Who may do what is the same in the app and on the server.** Policy writes (things that decide how much to hold, how it is valued, or when the books close) are management's; floor work (moving, counting, picking, registering a serial) stays the storekeeper's.
- **Nothing on the screen invents a number**: reorder plans show the figures the server computed and the inputs the business set.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the storekeeper (moves, serials, counts), the store manager (plans, policy), the owner, the finance clerk (periods, mappings).
- **Channels:** back-office (Admin shell > Inventory).
- **Scope:** per store; a store-held caller sees and writes only their stores; business-wide items (source types, mappings) are management's.
- **Roles that can write:** as in each screen below. New server gates: management for the policy writes listed in slice 1.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Policy writes are management's** (inventory-svc, small, first). PLATFORM_ADMIN/OWNER/MANAGER, `403 FORBIDDEN`, checked before validation, plus store access where a store is named (`403 STORE_ACCESS_DENIED`), exactly as ROP plans and costing methods already are: `POST /accounting-periods` and `POST /accounting-periods/{id}/close`; `POST /zone-gl-mappings`; `POST/…/source-types` and their activate/deactivate; `POST /abc/compile`; `POST /safety-stock` and `/safety-stock/compute`; `PUT /par-levels`; `POST /kanban-cards` and `PUT /kanban-cards/{id}/order-modifiers`; picking rules and their assignments (`POST/DELETE /picking-rules`, zone priorities, assignments). Reads stay any staff. Reason-code creation and deactivation is management's too. Floor writes stay as they are: `POST /kanban-cards/{id}/trigger`, `/replenish`, serial register and status, lot split, merge and grade, counting, moving, picking.
  2. **Move orders screen** (Flutter, with a server change). A **Moves** section beside Transfers. The server first makes the order say what it does: today `from_zone` and `to_zone` are free text and pick draws from the store and puts down at `to_store_id` with no zone, so "zone to zone" is a label only. The request gains `fromZoneId` and `toZoneId` (tenant-svc zone ids, checked to belong to the named store; the old text fields stay readable on old orders), the pick draws from batches in the from-zone when given and places the drawn batches in the to-zone, and the response's `status` is documented as DRAFT/OPEN/COMPLETED/CANCELLED as the table has it (the DTO text says PENDING/PICKED). Pick with per-line quantities and short handling is [transfer discrepancies](transfer-discrepancies.md) slice 7. Screen: list (store, status badge, from, to, lines, created), **New move** dialog (store, from zone, to zone, product picker with quantity lines, note), row actions **Pick** and **Cancel** (with confirmation), a short pick shown as "Moved 8 of 10". `stock.transfer` to create, pick, cancel (as the API gates them); refusals in words.
  3. **Reorder plans screen** (Flutter). An Inventory tab **Reorder plans**: store picker, table of product (by name), lead time, ordering cost, holding cost, unit cost (`AppFormat.money`, home currency), average daily demand, reorder point, EOQ, and order modifiers (minimum, maximum, lot multiple). Row edit dialog (PUT rop-plans: management), order modifiers (management), **Recompute for this store** (POST compute, shows "12 plans updated"). Any staff read; only management sees the edit controls, and the server refuses others regardless. Empty plans say how to start.
  4. **Counts and physical inventory** are [stock counts](stock-counts.md) (the Counts tab). **Date watch** is [expired stock](expired-and-short-dated-stock.md). **Holds** and **Storage kinds** are [receiving controls](receiving-controls.md). Not repeated here.
  5. **Planning screens** (Flutter). A **Planning** tab with sections, each a list plus a dialog: **Safety stock** (store, product, service level shown as the server states it, computed stock; `POST /safety-stock`, `/compute`), **Par levels** (`PUT/GET /par-levels`), **ABC classes** (`GET /abc/assignments`, **Compile** for the store, filter by class), **Kanban cards** (list, create, Trigger, Replenish, order modifiers). Management edits; staff read; the kanban trigger and replenish are staff work.
  6. **Serials and lots screens** (Flutter). **Serials**: lookup by number (`GET /serials/lookup`), a serial's status and history (`GET /serials/{id}`, `/history`), register (`POST /serials/register`, staff), change status (`PUT /serials/{id}/status`, staff). **Lots**: a batch's genealogy (ancestors and descendants, `GET /lot-genealogy/batch/{id}/…`), split and merge (`POST /lots/split`, `/merge`, staff), grade (`PUT /batches/{id}/grade`), the lot's actions. Reached from a batch row in Batches and from a scan.
  7. **Costing and periods** (Flutter, management). **Costing methods** (standard cost by store and product; `PUT/GET /costing-methods`) and **Store stock periods** (list, open, close with a confirmation naming what it locks: "no further costed movements will post into this period"), in Admin > Inventory > Settings. **A month is closed for the business in Finance > Periods** ([accounting-periods](accounting-periods.md) slice 4), whose close also closes each store's stock period here through `LedgerPeriodClosed` (slice 3 of that page); this screen therefore shows a store period closed by the ledger as read-only ("closed with the books, 30 June") and keeps its own Open and Close only for a business that wants stock periods without closing the ledger. Same role rule, same server.
  8. **Picking rules and reference data** (Flutter, management). **Picking rules** (list, create, zone priorities, assignments, "what applies to this product here" from `GET /picking-rules/resolve`) inside the Picking & putaway tab. **Source types** and **Zone ledger mappings** under Inventory > Settings.
  9. **Levels show `past best before`** (small: the app already shows `expired` since wave 1, `inventory_expired_test.dart`; the `pastBestBefore` field comes with [expired-and-short-dated-stock](expired-and-short-dated-stock.md) slice 1).
- **Out, on purpose:**
  - **Redesigning the endpoints for these screens.** They are used as they are, except where slice 2 names a change; screens follow the API.
  - **An inventory "setup wizard".** Each screen has its own empty state.
  - **Deleting any of this.** Rules and assignments keep the delete they have; nothing else is made deletable.
  - **The valuation report, nominal ledger browse and trial balance.** Valuation exists in Admin > Reports (`reports_test`); the ledger browse belongs to purchase-svc's screens.
  - **Recall and shelf-space screens.** Recalls have a full screen (Admin > Recalls); the shelf gaps report has its screen (`shelf_space_screen_test`). See below.

## Data and flow

- **Owned by inventory-svc:** every table named above already exists; the only change is `move_orders.from_zone_id` and `to_zone_id` (UUID, nullable, tenant-svc's ids referenced never joined) and `move_order_lines` as in the discrepancies page. No new tables here.
- **Needs from other services:** tenant-svc: stores and zones by name (`TenantProfiles`, and the zones the app already loads for putaway); product-svc: product names via `variantLabelsProvider`. Screens use the gateway paths the app's Inventory providers already use.
- **Events published:** none new; `StockReceived`/`StockAdjusted`-style events a pick already publishes stay.
- **Retryable writes (Idempotency-Key):** create move order, pick, cancel; kanban replenish; serial register; lot split and merge (the app reuses a key on retry, as the Adjust dialog does).
- **New error codes:** `403 FORBIDDEN` on the policy writes (existing code); `422 MOVE_ORDER_ZONE_NOT_IN_STORE`; `400 IDEMPOTENCY_KEY_REQUIRED` on the retryable writes above.

## Money, time and limits

- **Currency:** the business's home currency for every cost shown; through `AppFormat.money`.
- **Ledger postings:** none from these screens.
- **Dates:** the store's own day; `AppFormat.date` and `dateTime`.
- **Plan limits:** none.

## Constraints

- Theme and adaptive standard ([UI-GUIDE §7](../docs/UI-GUIDE.md)): `PageHeader`, `AdaptiveActions`, `ScrollableTable`, `showAdaptiveSheet`, `StatusBadge`, words not codes (`status_labels.dart`), no hard-coded colour or radius; the shared `EmptyState`, `ErrorView`, `LoadingView`.
- The screen never hides authority from the server: a button hidden from a storekeeper is also refused with `403` if called.
- Existing tenants: slice 1 removes writes staff could do; k6 and app tests that used a storekeeper for these writes are moved to a manager (the same change was made for ROP plans on 30 Sep).

## Already there

- Reorder-point and EOQ plans: set, list, by-variant, compute and order modifiers are management-gated with store access, and tested (`InventoryControlsIT.ropCostInputsAreManagementsToSetAndStaffReadThePlans`, `orderModifiersAreManagementsAtThePlansStore`); costing methods likewise (`theStandardCostIsManagementsToSetAtAStoreTheyKeep`). Endpoints only; no screen.
- Move orders: create, list, get, pick, cancel need `stock.transfer` (SJ-D73); no screen.
- The stock valuation report has a screen (Admin > Reports > Stock Valuation, `reports_test`) and is management-only by the shared filter (`StoreScopeIT.valuationScopedToTheCallersStores`).
- The Adjust dialog offers reason codes (30 Sep, `inventory_adjust_reason_codes_test`).
- **Recalls** (rcl-product-recalls): the source list is country-neutral (wave 1, `RecallSourceTest`, `RecallIT.aRecallNamesItsSourceInAnyCountry`); opening, closing and cancelling are management's by path (`RecallSetupResource`, `/admin/recalls`); the till's refusal exists and is tested in order-svc (`SaleChecksIT.aRecalledProductIsRefusedAtTheTill`, `aRecalledProductIsRefusedOnlineAndNothingIsHeld`, `aRecalledLotIsRefusedWhenThePackNamesIt`, `anotherBusinesssRecallNeverBlocksThisOne`), reading `GET /admin/inventory/recalls/active` through `RecallClient`. That answers the catalogue's till-evidence gap. What is not built is the Flutter side of naming a recall in an order's return dialog and at the till's Returns screen: the endpoint (`GET /orders/recall-notices?orderId=`) exists since wave 1, and the screens, with partial-quantity resolution, are [shopper-returns](shopper-returns.md) slice 5.
- **Shelf and range** (inv-shelf-space-and-gaps): `ShelfGapResource` is deliberately read-only. Capacity comes from product-svc's planograms through `ShelfCapacityPublished`/`FixtureRetired`, so a stale facing count is corrected in product-svc, where the fixture is, and reaches inventory-svc by event; a change that cannot be applied is named, not counted done (`shelf_space_screen_test.dart:215`). Correcting a count in inventory-svc would create a second owner of the same fact, so no write endpoint is added.

## Findings handed to other pages

Named here so none is lost; the mechanism is designed once elsewhere.

| Finding | Owner page | Key or metric |
|---|---|---|
| Write-off above a value or quantity (INV-28) | [approvals](approvals.md) | action key `stock.writeoff` |
| Bond release above a value (bond gap 2) | approvals | `stock.bond-release` |
| Yield run whose loss far exceeds expected | approvals (and an exception alert) | `stock.yield-loss`; metric `yield.loss_over_expected` |
| Transfer above a value | approvals | `stock.transfer` (at release). A receipt booking a large loss is a fact, not an approval: metric `transfers.discrepancy_value` ([transfer-discrepancies](transfer-discrepancies.md)) |
| Count variance above a value | approvals | `stock.cycle-count-post` |
| Receipt with no purchase order above a ceiling | approvals | `stock.receipt-unordered` |
| Recall or withdrawal closed or cancelled by someone narrower than OWNER/MANAGER, or by kind | approvals | `stock.recall-close` (kind and scope size named in the request) |
| Markdown deeper than the ladder | approvals | `pricing.markdown-deep` |
| Repeated adjustments by one person on one product (INV velocity gap) | [exception-alerts](exception-alerts.md) | metric `adjustments.count` (per person on one product) per period |

## Open questions

- [x] Who owns policy inputs such as safety stock and par levels? → **management, as reorder-point cost inputs already are** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who may close an accounting period? → **management only, with confirmation** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What is a move order really? → **a move between stores or, within a store, between zones of it, by zone ids; the free-text zone fields are kept for old orders only** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Do all these screens ship together? → **no: in the slice order above, each testable alone** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should shelf counts be editable in inventory-svc? → **no; the planogram's owner corrects them** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [x] Each policy write in slice 1 is refused `403 FORBIDDEN` to a storekeeper and cashier and nothing is written; a manager writes; a manager held to another store is `403 STORE_ACCESS_DENIED`; staff still read — `PolicyWritesIT.policyWritesAreManagements` (one case per endpoint; written, awaiting the lead's IT run)
- [x] Another business's staff of every role naming our ids see nothing and change nothing on each of those endpoints — `PolicyWritesIT.policyWritesStayInTheirBusiness`
- [ ] A move order with zone ids draws from the from-zone and places in the to-zone; a zone of another store is `422 MOVE_ORDER_ZONE_NOT_IN_STORE` — `MoveOrderZonesIT.theMoveDrawsFromOneZoneAndPutsDownInTheOther`, `aShortFromZoneMovesNothing`, `badZonesAreRefusedAndNoZonesStillWorks`, `aMoveOrderStaysInItsBusinessAndStore`. PART: the store-membership refusal waits on the `zone_status` projection ([workforce-rules](workforce-rules.md) slice 9); until then a from-zone that holds too little is `422 INSUFFICIENT_STOCK`
- [ ] Floor writes still work for a storekeeper (kanban trigger, serial register, lot split) — `PermissionsIT.floorWritesStayStaffs`
- [ ] Move orders: list, create, pick and cancel from the screen, refusals in words — `move_orders_screen_test`
- [ ] Reorder plans: table shows computed figures; manager edits and recomputes; storekeeper sees no edit control — `rop_plans_screen_test`
- [ ] Planning, serials, lots, costing and periods, picking rules, reference data each render, send the right request and a refusal in words — `planning_screen_test`, `serials_lots_screen_test`, `costing_periods_screen_test`, `picking_rules_screen_test`, `reference_data_screen_test`
- [ ] Levels show past best before beside the existing expired — `inventory_screen_words_test`
- [ ] `flutter analyze` is clean and the adaptive layouts hold at phone width — the same widget tests at two widths
- [ ] k6 `inventory-policy-flow`: a manager sets safety stock and closes a period; a storekeeper is refused; a storekeeper moves a batch by a move order

## Screens

- **Inventory > Moves**: list, New move (adaptive sheet), Pick, Cancel.
- **Inventory > Reorder plans**: table, edit dialog, Recompute.
- **Inventory > Planning**: Safety stock, Par levels, ABC classes, Kanban.
- **Inventory > Serials** and a batch's **Lot** sheet.
- **Inventory > Settings** (management): Costing, Accounting periods, Source types, Zone ledger mappings, Reason codes.
- **Picking & putaway**: Picking rules section.
- Each names products, stores and zones in words, money in the business's currency, and quantity fields never assume a unit beyond the product's.

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet. Found while checking, not in the catalogue: accounting periods, zone-to-ledger mappings, source types, ABC compile, safety stock, par levels, kanban creation and picking-rule writes carry no role check and only the staff tier's path gate; slice 1 closes it.
- 2026-09-30 (slices 1 and 2 server, built): policy writes are management's by `ctx.requireAnyRole(PLATFORM_ADMIN, OWNER, MANAGER)` first, then the store (`requireStoreAccess`; by id where the route names a period, kanban card or STORE-scoped assignment; `scopeStore` for ABC compile and safety-stock compute so a held manager who names none acts on their own store). Reason codes, source types, picking rules and zone priorities name no store and are management's business-wide. `POST /rop-plans/compute` was documented as gated but was not: now it is. Move orders carry `fromZoneId`/`toZoneId` (V48); the pick draws only batches in the from-zone (`deductBatches(..., onlyZone, ...)`) and places the children in the to-zone; the text labels stay readable. Zone membership of a store is not knowable inside inventory-svc yet (no zone projection), so `MOVE_ORDER_ZONE_NOT_IN_STORE` is not raised; the same zone at both ends of one store is `400 MOVE_ORDER_SAME_ZONE`.
