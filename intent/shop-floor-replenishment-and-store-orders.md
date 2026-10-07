# Shop-floor replenishment and the shop's own order on its warehouse

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Oracle audit's Phase 2 backlog line 7 ("In-store replenishment and store orders") · 2026-09-30 |
| **Roadmap** | Oracle rows "In-store replenishment: shop-floor pick lists, backroom, delivery bay, store runner" (SIOCS ch. 10, partial), "Store orders: manual, system-generated, quick orders" (SIOCS ch. 15, partial: "no store→DC order (08.10)"), "Inventory replenishment orders from the store" (SIOCS ch. 47, partial: "no submit to a supplier or DC"); Review rows 1.6 (shelf facings), 5.7, 7.10 (depot replenishment, built) |
| **Services** | inventory-svc owns the pick list, zone roles, the store order and the transfers they make · tenant-svc supplies stores and zones (read through `TenantProfiles`) · notification-svc tells the people concerned · the app gets the screens |
| **Builds on** | `shelf_targets` and the shelf-gap report (`ShelfGapResource`, `ShelfSpaceService`; capacity from product-svc's planograms), `par_level_configs`, kanban cards (`INTRA_ORG` with `source_store_id`), `move_orders` and their pick ([inventory-screens](inventory-screens.md) slice 2 makes them zone-aware; [transfer-discrepancies](transfer-discrepancies.md) slice 7 makes a pick short-tolerant), [wave-picking-and-directed-putaway](wave-picking-and-directed-putaway.md) (directed putaway leaves stock in a zone), [depot-dc-replenishment](depot-dc-replenishment.md) (`serving_relationships`, `serving_exceptions`, DRAFT transfers, `POST /transfers/{id}/release`, `NetworkService.run`), [replenishment-overrides](replenishment-overrides.md) (adjust a DRAFT line, `proposed_qty`), the `stock.transfer` permission and approvals key |
| **Built in** | not built |

## Problem

The platform knows how much a shelf should hold (a planogram's facings, a par level) and reports the gap, and it can move stock between zones and between stores, but nothing joins the two. A shelf that is empty while the backroom holds a pallet is a report line someone reads and then walks off to fix from memory; there is no list to hand a stock clerk, no record that the shelf was filled, and no way to see that a shelf is empty *and* the backroom is too. And a shop that needs stock from its warehouse has no way to ask: the depot run proposes for it from its reorder point, a person at the warehouse releases, and if the shop manager knows a promotion starts tomorrow the only lever is a phone call.

## Outcome

- **A stock clerk is handed a list of what to carry from the back to the shelf,** sorted so the empty shelves come first, with where each thing is, and taps each line as it is done. The move is recorded as any move is.
- **Where the back has none, the list says so and the shop can ask its warehouse.** The shop raises an order on its own warehouse; the warehouse's manager accepts it, changes the quantities or rejects it with a reason, and the shop is told.
- **A quick order is one press:** repeat a template or a previous order, or take the system's suggestion from par levels and the shelf gaps, review it and send it. The system never sends one by itself.
- **There is still one warehouse per shop.** A request goes to the warehouse that serves the shop, and never for a product the shop buys direct.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the stock clerk or storekeeper (works the list), the shop manager (raises the shop's order, sets zone roles), the warehouse manager (decides requests), the owner.
- **Channels:** back-office, and the store's phone or tablet.
- **Scope:** per store for a list and a request; per shop for its warehouse (its one serving relationship); per warehouse for the decision.
- **Roles that can write:**
  - **Generate and work a pick list:** any staff assigned to the store below CASHIER (STOREKEEPER, MANAGER, OWNER), `requireStoreAccess`; the move itself is the move order's pick, gated `stock.transfer` today, and a storekeeper holds it by default.
  - **Zone roles, templates' sharing:** management, at the store.
  - **Raise, change and cancel a store order:** STOREKEEPER, MANAGER, OWNER at the shop.
  - **Accept, change or reject a request:** `stock.transfer` at the warehouse (as release is today); a keeper of the shop only cannot.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Zone roles** (inventory-svc). Zone `type` in tenant-svc is free text (AISLE, SHELF, BACK_STORE, RECEIVING, DISPLAY, DEFAULT, …) and says what a zone *is*, not what part it plays in replenishment. inventory-svc gains `zone_roles` (tenant_id, store_id, zone_id, role `FLOOR` | `BACKROOM` | `DELIVERY_BAY`, set by management, one role per zone, `PUT /admin/inventory/zone-roles`). A zone with no role plays none; a store with no `FLOOR` zone has no shelf to replenish and says so (`INVENTORY_REPLENISH_NO_FLOOR_ZONE`). Zone ids are tenant-svc's, checked to belong to the store through the zones the app already loads (read, never joined); a zone that is not ACTIVE ([workforce-rules](workforce-rules.md) slice 9) is left out of the source side.
  2. **The shelf pick list** (inventory-svc). `POST /admin/inventory/shelf-replenishment/lists {storeId}` (Idempotency-Key; management may also pass a group, see below) builds a list for the store: for each product with a shelf target (`shelf_targets`, summed across its fixtures) or a par level, **floor position** = on hand in `FLOOR` zones plus what open lists already carry there; **gap** = target less floor position (`belowMinimum`, the planogram's minimum presentation, marks the urgent ones; a product with no stock on the floor at all comes first); **carry** = the gap, capped at what the source zones hold (`DELIVERY_BAY` first, so a fresh delivery goes straight to the shelf, then `BACKROOM`), in whole units where the product sells in units. The list is a move order of a new `kind = SHELF_REPLENISHMENT` (the existing table, its lines, its pick, its cancel), each line naming the from-zone, the to-zone, the batches the picking rule directs to (oldest expiry first as the store's rule says; expired stock is never drawn, `Expiry`), and `short_reason` where the source could not cover it. Lines that the back cannot cover at all are returned in the answer as `needsOrder: [{variantId, gap}]` (feeding slice 5). A second generation while an open list of the store holds the same product does not duplicate it (its quantity counts in the floor position). No number is invented: target and par are the business's own data; where a product has neither it is not on the list.
  3. **Working the list** (inventory-svc). `POST /move-orders/{id}/claim` (the runner takes the list; `claimed_by`, `claimed_at`; a claimed list is picked only by its claimer unless management releases it, `409 MOVE_ORDER_CLAIMED`); the existing pick with per-line quantities (transfer-discrepancies slice 7) records what was carried, the shortfall is closed short or the list stays open, and the movement is written by the pick as any move order's. Each line taps done; a batch moved to the floor keeps its lot, date and cost (`Provenance`). A list not touched for the day ends `EXPIRED` at the end of the store's own day (no number: the business's day), releasing the floor position it carried, so tomorrow's list is right.
  4. **The shop's own order on its warehouse** (inventory-svc). A `store_orders` request (`POST /admin/inventory/store-orders`, Idempotency-Key): the requesting shop (the caller must act there), lines of variant and quantity, a `neededBy` day (optional, in the shop's own zone), a note and a `source` (`MANUAL`, `QUICK`, `SUGGESTED`). The warehouse is **the shop's serving warehouse** (`serving_relationships`, one per shop): no relationship is `409 INVENTORY_STORE_ORDER_NO_WAREHOUSE`; a line for a product the shop buys direct (`serving_exceptions`) is `422 INVENTORY_STORE_ORDER_BOUGHT_DIRECT` (it belongs on a purchase order, and the response says which). The warehouse's manager sees the request and decides (`POST …/{id}/decide`, Idempotency-Key, `stock.transfer` at the warehouse): per line `accepted` with a quantity (never above what the warehouse holds free after what it has committed, `422 INVENTORY_OVERRIDE_EXCEEDS_STOCK` as [replenishment-overrides](replenishment-overrides.md) has it), or `rejected` with a required reason code (`STOCKOUT`, `PROMOTION_ELSEWHERE`, `NOT_STOCKED`, `OTHER`, business-extendable in `transaction_reason_codes`); a request with any accepted line makes **one DRAFT transfer** from the warehouse to the shop with `source = STORE_ORDER` and the request id, `proposed_qty` = what the shop asked and the set quantity = what was accepted (so [replenishment-overrides](replenishment-overrides.md)' "computed beside set" shows on the transfer), and the warehouse then releases it exactly as it releases a proposal (`stock.transfer`, and the approvals rule for `stock.transfer` if the business set one). A request that is not decided stays open; the shop may cancel it until decided; decided requests are final (a change is a new request). Rejection and acceptance tell the shop.
  5. **Quick orders and the system's suggestion** (inventory-svc). `store_order_templates` per shop (name, lines; `POST/GET/DELETE`, management or the shop's manager): creating a request from a template or from a previous request copies its lines (`source = QUICK`). `GET /admin/inventory/store-orders/suggestion?storeId=` proposes lines the way the depot rule does (par level and the shelf gaps' `needsOrder`, less what is inbound and on open requests), for the shop to edit and send (`source = SUGGESTED`): **never sent automatically**, the same rule as a purchase proposal and a depot run. The result is written nowhere until a person posts it.
  6. **Depot runs and requests do not fight** (inventory-svc, a change to depot-dc-replenishment's code). `INVENTORY_PROPOSAL_OPEN` and the run's "skip a shop with an open DRAFT" (replenishment-overrides slice 3) apply only to drafts with `source = PROPOSAL`; a `STORE_ORDER` draft is left alone, and because the shop's position already counts every transfer proposed, released or on its way, the run proposes only what is still short after the request. A person's request is never recomputed over, and neither is the run blocked by it.
  7. **Tell people** (notification-svc). `StoreOrderRequested` tells the warehouse's managers; `StoreOrderDecided` tells the requester and the shop's managers (`STORE_ORDER_REQUESTED`, `STORE_ORDER_DECIDED`), in words, in-app and email by preference.
  8. **The screens** (Flutter). See Screens.
- **Out, on purpose:**
  - **A store order to a supplier.** That is a purchase order: the shop raises a purchase proposal (existing) for what it buys direct. This page never invents a second path to a supplier, and one warehouse serves a shop.
  - **A shop ordering from a different warehouse than its own, or from another shop.** One warehouse per shop was decided in [depot-dc-replenishment](depot-dc-replenishment.md); a shop-to-shop move is an ordinary transfer.
  - **Automatic sending or accepting.** A person sends, a person decides, a person releases.
  - **Handheld or RF scanning, voice picking, robotic runners.** The list works on any phone or tablet; a scanner integration is the till's own feature.
  - **Replenishing a display from a promotional or seasonal plan.** Planograms and par levels are the source; a promotion calendar is not.
  - **Costing or charging a shop for a transfer** (intercompany). Unchanged: transfers post nothing.
  - **Changing what the planogram says a shelf holds.** Product-svc owns it and inventory-svc stays read-only there ([inventory-screens](inventory-screens.md) "Shelf and range").

## Data and flow

- **Owned by inventory-svc:**
  - `zone_roles` (tenant_id, store_id, zone_id, role, set_by, set_at), index `(tenant_id, store_id)`.
  - `move_orders` gain `kind` (`MOVE` | `SHELF_REPLENISHMENT`, default MOVE), `claimed_by`, `claimed_at`, `generated_by` (a person or the schedule, see [product-groups-and-schedules](product-groups-and-schedules.md)); `move_order_lines` gain the `short_reason` of transfer-discrepancies.
  - `store_orders` (id, tenant_id, shop_store_id, warehouse_store_id, status `REQUESTED` | `DECIDED` | `CANCELLED`, source, needed_by, note, requested_by, requested_at, decided_by, decided_at, transfer_order_id); `store_order_lines` (variant_id, requested_qty, accepted_qty null until decided, decision `ACCEPTED` | `REJECTED`, reason_code); `store_order_templates` and their lines. Decisions are rows on the lines, a decided request is not rewritten (append-only in effect: `store_order_decisions` row per decide call).
  - `transfer_orders.source` gains `STORE_ORDER` (its CHECK) and `store_order_id`.
- **Needs from other services:** stores, warehouse type, zones (`TenantProfiles`, tenant-svc REST, cached); shelf capacity (already in `shelf_targets` from `ShelfCapacityPublished`); staff names for the list from the staff-logins projection the app reads. No joins.
- **Events published (outbox, `eventId` last):** `StoreOrderRequested` (`storeql.inventory.store-order-requested`: tenantId, storeOrderId, shopStoreId, warehouseStoreId, lines, requestedBy) → notification-svc; `StoreOrderDecided` (`storeql.inventory.store-order-decided`: same, plus per line decision, accepted quantity, reason, transferOrderId, decidedBy) → notification-svc, reporting-svc. A move-order pick publishes what it publishes today; the transfer created publishes the `TransferOrder*` events unchanged.
- **Retryable writes (Idempotency-Key):** generate a list, claim, pick (existing), request, decide, cancel, template create.
- **New error codes:** `409 INVENTORY_REPLENISH_NO_FLOOR_ZONE`, `409 INVENTORY_STORE_ORDER_NO_WAREHOUSE`, `422 INVENTORY_STORE_ORDER_BOUGHT_DIRECT`, `422 INVENTORY_OVERRIDE_EXCEEDS_STOCK` (existing, reused), `409 INVENTORY_STORE_ORDER_DECIDED`, `404 INVENTORY_STORE_ORDER_NOT_FOUND`, `409 MOVE_ORDER_CLAIMED`, `400 INVENTORY_STORE_ORDER_REASON_REQUIRED` (a rejection), `400 IDEMPOTENCY_KEY_REQUIRED`; `403 STORE_ACCESS_DENIED`, `403 PERMISSION_DENIED` as everywhere.

## Money, time and limits

- **Currency:** none directly; transfer value is read at cost as today.
- **Ledger postings:** none (a move between zones or between the business's own stores posts nothing until a discrepancy, see [transfer-discrepancies](transfer-discrepancies.md)).
- **Dates:** UTC instants; `neededBy` and the end of a list's day in the store's own zone (`TenantProfiles.Stores.zoneOf`).
- **Plan limits:** none.

## Constraints

- Golden rule 1: zones, stores and planograms are read, never joined; inventory-svc keeps the roles it needs.
- The planners stay pure: list building is a pure `ShelfReplenishment.plan` (floor position, gap, carry, order) given the inputs it is handed, as `DcReplenishment` and `Waves.plan` are.
- Append-only: `stock_movements`, `store_order_decisions`. A request is never edited after it is decided.
- Existing tenants: no zone roles means no list; no serving relationship means no request; a store without either behaves as today.
- Never invent policy: nothing here needs a number; targets and pars are the business's.
- Directed putaway is unchanged: it decides where a *delivery* is put; this decides how stock is carried from where it was put to where it sells.

## Already there

- Shelf gaps against the planogram, read-only, by store (`GET /admin/inventory/reports/shelf-gaps`, `ShelfSpaceService`, `shelf_space_screen_test`), par levels (`PUT/GET /par-levels`), kanban cards including `INTRA_ORG` with a source store, move orders (create, pick, cancel, `stock.transfer`), depot proposals released by a person, `serving_relationships` and `serving_exceptions`, the sourcing read purchase-svc uses.
- Not there: any list, any zone role, any claim, any request from a shop, any template, any suggestion.

## Open questions

- [x] What is a shelf pick list? → **a move order of a kind: the existing table, lines, pick and movement record, so nothing is invented twice** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Where does stock come from and go to? → **from the delivery bay then the backroom to the floor, by roles the business gives its own zones** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a shop's order a purchase order? → **no: a request to its own warehouse that becomes a DRAFT transfer the warehouse releases** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can the system send an order by itself? → **no: it suggests, a person sends** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What of a shop with no warehouse or a direct-buy product? → **refused with the way out named: a purchase order** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does the warehouse have to accept? → **no: it accepts, changes or rejects, and a reason is required for a rejection or a cut** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Approval and alerts? → **none new: nothing moves money or leaves the business; the release stays under `stock.transfer`, and a carrying list moves stock only between the store's own zones; unexplained loss shows in counts and [known and unknown loss](known-and-unknown-loss.md)** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A list holds the gap, capped by the source, the delivery bay before the backroom, empty shelves first, oldest date first, no expired stock; a product with no shelf target and no par is not on it — `ShelfReplenishmentIT.aListCarriesTheGapFromTheBack`, `ShelfReplenishmentTest` (pure)
- [ ] A second generation does not duplicate a product on an open list; a product the back cannot cover is returned as `needsOrder` — `ShelfReplenishmentIT.noDuplicatesAndNeedsOrder`
- [ ] A store with no `FLOOR` zone is `409 INVENTORY_REPLENISH_NO_FLOOR_ZONE`; a zone of another store is refused; a non-active zone is left out — `ZoneRolesIT`
- [ ] A runner claims a list; another staff cannot pick it (`409 MOVE_ORDER_CLAIMED`); management releases; a picked line moves the batches to the floor zone with lot, date and cost kept; a short line closes short or stays open — `ShelfListIT.claimPickAndShort`
- [ ] An untouched list ends at the end of the store's day in its zone and stops counting in the position — `ShelfListIT.anUntouchedListExpires` (two zones)
- [ ] A shop's request goes to its serving warehouse only; no warehouse is `409`; a direct-buy product `422` — `StoreOrderIT.refusals`
- [ ] The warehouse accepts (within free stock), changes quantities, or rejects with a reason; acceptance makes one DRAFT transfer with `proposed_qty` and the set quantity and the request id; released as any proposal — `StoreOrderIT.aRequestBecomesADraftTheWarehouseReleases`
- [ ] More than the warehouse holds is `422 INVENTORY_OVERRIDE_EXCEEDS_STOCK`; a rejection with no reason `400`; deciding twice `409 INVENTORY_STORE_ORDER_DECIDED`; a cancel after decision is refused — `StoreOrderIT.decisions`
- [ ] A depot run is not blocked by, and does not recompute over, a store order's draft, and proposes only what is still short — `NetworkIT.aStoreOrderDraftDoesNotBlockTheRun`
- [ ] A template repeats a request; the suggestion reflects par and shelf gaps less inbound and open requests and writes nothing — `StoreOrderIT.quickOrdersAndSuggestion`
- [ ] The warehouse's managers are told of a request once, the shop of a decision once — `StoreOrderRequestedHandlerTest`, `StoreOrderDecidedHandlerTest` (notification-svc)
- [ ] Who: a cashier is refused everywhere here (`403 PERMISSION_DENIED`); a keeper of neither store `403 STORE_ACCESS_DENIED`; a shop's keeper cannot decide its own request — `PermissionsIT.shelfListsAndStoreOrdersAreGated`
- [ ] Isolation: another business's staff of every role and a shopper, naming our list, zone role, request or template, get 404 and nothing moves — `ShelfReplenishmentTenantIsolationIT`, `StoreOrderTenantIsolationIT`
- [ ] Retry: the same key answers with the first for generate, claim, request, decide — `StoreOrderIT.retriesAnswerWithTheFirst`, `ShelfListIT.retries`
- [ ] The record: requests, decisions and moves say who, when, why, and nothing is edited — `StoreOrderIT.theRecordIsAppendOnly`
- [ ] A business in another country and zone (day boundary, units, currency of the transfer value) — `ShelfReplenishmentIT.spansBusinessesInDifferentCountries`
- [ ] Widgets: the shelf list on a phone and a wider window, zone roles, the store-order flows, the warehouse's Requests — `shelf_list_test`, `zone_roles_test`, `store_orders_test`
- [ ] k6 `shelf-replenishment-flow`: roles set, list built, claimed, picked, shelf level rises; `store-order-flow`: a shop requests, the warehouse changes and releases, the shop ships/receives, a cashier refused, a rival refused

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet.
- **No new approvals key and no new alert metric:** nothing here moves money or crosses the business; release is `stock.transfer`, and an unexplained shortfall is a count's business ([known and unknown loss](known-and-unknown-loss.md)).
- **Change to [depot-dc-replenishment](depot-dc-replenishment.md) and [replenishment-overrides](replenishment-overrides.md):** the "open draft" rules (`INVENTORY_PROPOSAL_OPEN`, skip a shop with an open DRAFT) count only `source = PROPOSAL` drafts (slice 6); the old text stays valid for proposals.

## Screens

- **Admin > Inventory > Shelf** (new tab, phone-first): **Make a list** (store, optional group), the list as large tappable rows: product by name, *Take 12 from Backroom aisle 2 to Shelf 4*, an amount field prefilled, **Done** per line and **Couldn't find it** (short reason); **Take this list** (claim) and a badge with the runner's name (`staffLoginsProvider`); products the back cannot cover are shown under **Ask the warehouse** with one action to add them to a store order.
- **Inventory > Settings > Zone roles** (management): each zone of the store with a role picker.
- **Admin > Inventory > Store orders** (new tab): for a shop's staff, **New order** (product picker, quantities, needed-by day, note), **From a template**, **Suggest**, the list of the shop's orders in words (Waiting, Accepted, Partly accepted, Rejected, Cancelled) with each line's reason; for the warehouse's `stock.transfer` holders, **Requests** with Accept, Change quantity, Reject (reason picker), and a link to the DRAFT transfer in Depot & shops.
- Everything through `PageHeader`, `AdaptiveActions`, `ScrollableTable`, `showAdaptiveSheet`, `StatusBadge`, `EmptyState`, `ErrorView`, tokens; refusals read in words; quantity fields make no assumption about unit beyond the product's.

## Flow Tests entry

- **Catalogue area and file:** `target/flow-catalogue/inventory/shop-floor-replenishment-and-store-orders.json` (the inventory domain, beside `inv-shelf-space-and-gaps`, `trf-dc-replenishment`, `trf-move-orders`). The feature is not BUILT until this entry exists and its cases are automated.
- **Cases:**
  - Happy: a list is built, claimed, picked and the shelf fills (`ShelfListIT.claimPickAndShort`, k6 `shelf-replenishment-flow`); a shop requests, the warehouse changes and releases (`StoreOrderIT.aRequestBecomesADraftTheWarehouseReleases`, k6 `store-order-flow`); a quick order from a template.
  - Negative: no floor zone; no warehouse; direct-buy product; more than the warehouse holds; a rejection without a reason; a second decision; picking a claimed list.
  - Override: management releases a claimed list; the warehouse accepts fewer than asked; a store-held manager sets roles only at their stores.
  - Isolation: other business's staff of every role and a shopper; a keeper of neither store; a shop's keeper deciding its own request.
  - Edge: a product on two open lists; the day boundary in two zones; a list expiring; a warehouse with a run pending and a request in flight; a zone gone out of service.
  - Audit: requests, decisions and moves append-only and readable; the transfer shows the shop's ask beside the accepted quantity.
