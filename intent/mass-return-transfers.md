# Mass return transfers: pull a product back from every shop in one instruction

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Oracle audit's "Mass return transfers" row (RMFCS inventory, graded absent, "no row") and the Return to vendor row ("mass return transfers open") · 2026-09-30 |
| **Roadmap** | new: the Oracle audit, Inventory (Supply chain, warehouse and logistics) |
| **Services** | inventory-svc owns the instruction, its per-shop tracking and the transfers · purchase-svc owns the supplier stage (vendor returns and their debit notes) · notification-svc tells the shops and the issuer · tenant-svc holds the approvals rule · the app gets the screens |
| **Builds on** | `transfer_orders` / `transfer_order_lines` and their lifecycle (`DIRECT`, INTRANSIT, `receiveTransferOrder`), [transfer-discrepancies](transfer-discrepancies.md) (a receipt counts what arrived), [depot-dc-replenishment](depot-dc-replenishment.md) (`serving_relationships`, `isWarehouse`), [recalls](inventory-screens.md) (`RecallResource`, RECALLED batches, `rcl-product-recalls`), `deductBatches` by move type, vendor returns (`vendor_returns`, per purchase order, priced at the order's prices; `ReturnedToVendor`, `ReturnedToVendorHandler`), [goods-receipt-detail](goods-receipt-detail.md) (return with condition), [product-groups-and-schedules](product-groups-and-schedules.md) (a group names the products), the approvals key `stock.transfer` |
| **Built in** | not built |

## Problem

A product must come back: a recall, a supplier's withdrawal, the end of a range, a seasonal line that is over. Today the only tool is a transfer raised by hand, one shop at a time, with the quantity guessed from a screen that may be a day old; a business with thirty shops raises thirty transfers, chases each, and has no single place that says "twenty-six shops have returned it, four have not". Returning it to the supplier is worse: a vendor return is per purchase order and per store, priced at that order's prices, and a product spread over thirty shops that were bought through a warehouse has no order at the shops at all.

## Outcome

- **One instruction names the product (or a group), the shops (all, or some) and where it goes:** the warehouse that serves each shop, or a warehouse the business chooses.
- **The platform previews before anything moves:** each shop's quantity in the condition that can come back, and the shops that are skipped, each with a reason.
- **Issuing it raises one transfer per shop** for the quantity then on hand, and tracks each shop to done: shipped, received (as counted, with any shortfall a discrepancy), nothing there, skipped.
- **The head office sees one progress view** and is told when it is complete, and when a shop is late by the business's own deadline.
- **To go on to the supplier, the returned stock is sent back from the warehouse against the supplier's orders,** with the platform proposing which orders and never guessing what it cannot match.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the area or head-office manager and the owner (issue), the shop's storekeeper (packs and ships their transfer), the warehouse storekeeper (receives), the buyer (the supplier stage), the food-safety lead (a recall pull-back).
- **Channels:** back-office (Inventory > Pull-backs; Procurement for the supplier stage).
- **Scope:** an instruction is per business; its per-shop parts are at each shop; the warehouse is a store of type WAREHOUSE.
- **Roles that can write:**
  - **Issue and cancel:** OWNER or MANAGER **and** `stock.transfer` at every shop it touches; a manager held to stores can issue only for their own stores (`403 STORE_ACCESS_DENIED`), and an instruction for every shop needs a caller held to none (`BUSINESS_WIDE_ONLY`).
  - **Ship and receive** the transfers: as any transfer (`stock.transfer` at the shop, and at the warehouse to receive).
  - **The supplier stage:** the buyers (OWNER, MANAGER, STOREKEEPER at the warehouse; as vendor returns are raised today).
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **The instruction and its preview** (inventory-svc). `POST /admin/inventory/pullbacks/preview` and the same body to create: `variantIds` or a `groupId` ([product-groups-and-schedules](product-groups-and-schedules.md)), the shops (`storeIds`, or all the shops the caller may act at), `destinationStoreId` (optional; **default is each shop's serving warehouse**; an explicit one must be a WAREHOUSE and is used for every shop), `conditions` (which stock may come back: `AVAILABLE` by default; also `DAMAGED`, `QUARANTINE`, `INSPECTION`, and `RECALLED` only when the instruction names a `recallId`), `reason` (`RECALL`, `WITHDRAWN`, `END_OF_RANGE`, `OTHER`, required, and a note), `returnBy` (a date in each shop's own zone, optional). The preview answers per shop: quantity now in the eligible conditions, and if none, the reason it is skipped: `NOTHING_TO_RETURN`, `NOT_SERVED` (no warehouse and none named), `IS_WAREHOUSE` (a warehouse is not a shop; the instruction is for shops, and a warehouse holding stock is the destination or is left alone), `NOT_TRADING`, `STORE_NOT_YOURS`. **Excluded from every pull-back and listed as such:** duty-suspended (bonded) batches (they leave bond only by a release), expired stock (it is a disposal, [expired-and-short-dated-stock](expired-and-short-dated-stock.md)), and stock held for an online order (a reservation is not stock to pull; the preview shows `heldForOrders` apart). Consignment stock rides with its ownership as transfers already carry it, and is shown apart. The preview writes nothing.
  2. **Issue** (inventory-svc). `POST /admin/inventory/pullbacks` (Idempotency-Key) records the instruction (`pullbacks`, one row per shop in `pullback_stores`) and, per shop, in that shop's own transaction, raises a **transfer** (`source = PULLBACK`, `pullback_id`, PENDING and ready to ship, since the issuer has chosen it; the lines are the quantities on hand at that moment, drawn as a new move type `PULLBACK`, which draws AVAILABLE batches first, and the other named conditions and RECALLED batches only where the instruction says so, never bonded, never expired, never held by an order). The instruction is **resumable**: a pull-back is `ISSUING` until every shop's row exists; a retry with the same key continues where it stopped (unique `(pullback_id, store_id)`), so a crash never leaves a shop uncounted or a shop's transfer made twice. A shop with nothing is `NOTHING_TO_RETURN` (a row, no transfer).
  3. **Authority** (inventory-svc, tenant-svc rule). The instruction as a whole is judged by the approvals key **`stock.pullback`** (new): measured by the total cost of the stock it would draw across all shops (home currency, from the preview) and the number of shops; a second person other than the issuer approves above the business's rule; no rule is no gate; an unreadable rule closes. An approved instruction's transfers are then created without a second `stock.transfer` check, because the approval measured the whole. Without approval or below the ceiling the issuer's own `stock.transfer` at each shop is what counts. A pull-back that names a recall is the recall's own action and is never held for want of a rule: a recall's urgency stands, and the alert below watches it.
  4. **Tracking to done** (inventory-svc). Each shop moves `TRANSFER_RAISED` → `SHIPPED` → `RECEIVED` (or `RECEIVED_SHORT` when the receipt counted less; the [discrepancy](transfer-discrepancies.md) is its own record and shows here) → `CLOSED`; `NOTHING_TO_RETURN` and `SKIPPED` are final at issue. A shop's status changes in the same transaction as its transfer's ship and receive (same service, one place). The instruction is `COMPLETE` when every shop is received, nothing to return or skipped, or was cancelled; a cancel (issuer or management) cancels the transfers not yet shipped (the existing cancel), leaves shipped ones to arrive, and closes the rest. `GET /admin/inventory/pullbacks/{id}` shows totals (asked, shipped, received, short, per shop) and `GET …/pullbacks` lists them (cursor). A pull-back naming a recall gives [recall close](inventory-screens.md)'s "units still unaccounted for" its account of where the units went, from this page's own tables.
  5. **Late shops and telling people** (inventory-svc, notification-svc). Where `returnBy` is set, a nightly sweeper announces `PullbackOverdue` once per shop when its transfer has not shipped by the end of that day in the shop's own zone; `PullbackCompleted` announces completion once. notification-svc tells the shop's managers, the warehouse's managers and the issuer (`PULLBACK_ISSUED` to each shop at issue, `PULLBACK_OVERDUE`, `PULLBACK_COMPLETED`), in words.
  6. **On to the supplier** (purchase-svc, inventory-svc). Once stock is at the warehouse, `POST /admin/inventory/pullbacks/{id}/supplier-return-proposal {supplierId}` asks purchase-svc (REST) for a **proposal**: pure `ReturnAllocation` spreads the quantity received at the warehouse over that supplier's orders received at that warehouse, newest received first, capped by each order's received-not-yet-returned quantity per variant (the ceiling `PURCHASE_RTV_OVER_RETURN` already enforces), each line at that order's price; whatever cannot be placed on an order (stock older than the records, stock made by yield, stock that arrived by transfer from elsewhere) is returned in `unplaced: [{variantId, qty}]` with the reason and is **never guessed onto an order**. A person reviews and raises the vendor returns through the existing endpoint (`POST /vendor-returns`, one per order, reason `RECALL` or `QUALITY` or `OTHER` as the instruction's reason maps), carrying `pullbackId` so the debit notes, `ReturnedToVendor` and the tracking connect; inventory-svc's existing handler draws the stock and marks the pull-back's shops `RETURNED_TO_SUPPLIER` in the quantities that left. The proposal writes nothing and never raises a return itself. Consignment stock is not returned this way (nothing is owed on it: the owner's stock goes back by a stock adjustment with the owner named, a person's act on the warehouse), and it is listed in `unplaced` as `CONSIGNMENT`.
  7. **The screens** (Flutter). See Screens.
- **Out, on purpose:**
  - **A shop returning straight to a supplier.** A vendor return is per order and per store, priced at that order; shops served by a warehouse have no order. A shop that buys direct and holds its own orders raises its own vendor return, which exists. The supplier stage here starts at the warehouse.
  - **Stopping sale at the shops while the stock is pulled.** A recall does that (`RECALLED`, the till refuses it) and a business that must stop sale otherwise uses the recall or a store-level markdown; a pull-back that also pulled the sale would surprise an end-of-range business that wants to sell down.
  - **Choosing which stock to pull by batch or lot.** The instruction is by product and condition; recalled lots come through the recall.
  - **Automatic pull-back from a rule** (for example on a discontinued product): a person issues. The catalogue's discontinue flow is [approvals](approvals.md)' `catalog.discontinue`, not this.
  - **Consignment returns as vendor returns, bonded returns, expiry disposal.** Each has its own flow (consignment settlements, bond release, disposal).
  - **A guessed price for a return.** The order's own price is used, as every vendor return.
  - **Costing the movement between the business's own stores.** Transfers post nothing, as before.

## Data and flow

- **Owned by inventory-svc:**
  - `pullbacks` (id, tenant_id, reason, note, recall_id null, group_id null, destination_store_id null, conditions, return_by null, status `ISSUING` | `ISSUED` | `COMPLETE` | `CANCELLED`, approval reference, issued_by, issued_at, cancelled_by, cancelled_at, idempotency_key) and `pullback_variants` (variant ids named).
  - `pullback_stores` (pullback_id, store_id, warehouse_id, qty_asked, qty_shipped, qty_received, qty_returned_to_supplier, status, transfer_order_id, skip_reason, updated_at); index `(tenant_id, pullback_id)`. Status changes are the only update; each change is also a row in `pullback_events` (append-only) with who or what.
  - `transfer_orders.source` gains `PULLBACK`; `pullback_id` on the transfer; `MoveType.PULLBACK` in `deductBatches`.
- **Owned by purchase-svc:** `vendor_returns` gain `pullback_id` (nullable); the proposal is computed, not stored; `ReturnAllocation` is pure.
- **Needs from other services:** stores, warehouse type, zones and trading status (`TenantProfiles`); serving relationships (own); a supplier's received-not-yet-returned per order at a warehouse (purchase-svc's own data; inventory-svc asks purchase-svc, never reads it); the recall (own). No joins.
- **Events published (outbox, `eventId` last):** `PullbackIssued` (`storeql.inventory.pullback-issued`: tenantId, pullbackId, reason, recallId, per shop storeId, warehouseId, qty, transferOrderId, issuedBy) → notification-svc, reporting-svc; `PullbackOverdue` and `PullbackCompleted` → notification-svc; `ReturnedToVendor` gains `pullbackId` (additive) → inventory-svc's handler updates the tracking, once per event. The transfers publish `TransferOrder*` unchanged; `ApprovalRequested/Decided/Expired` as the shared block.
- **Retryable writes (Idempotency-Key):** issue, cancel, the supplier proposal (read, none needed), each vendor return (existing).
- **New error codes:** `404 INVENTORY_PULLBACK_NOT_FOUND`, `409 INVENTORY_PULLBACK_NOTHING_TO_DO` (every shop skipped or empty), `422 INVENTORY_PULLBACK_DESTINATION_INVALID` (not a WAREHOUSE, or a shop named as its own destination), `400 INVENTORY_PULLBACK_REASON_REQUIRED`, `422 INVENTORY_PULLBACK_RECALL_UNKNOWN`, `409 INVENTORY_PULLBACK_NOT_CANCELLABLE`, `403 INVENTORY_PULLBACK_NOT_APPROVED`, `409 INVENTORY_PULLBACK_ISSUING` (a second, different issue while one is still issuing for the same variants), `403 STORE_ACCESS_DENIED`, `403 BUSINESS_WIDE_ONLY`, `400 IDEMPOTENCY_KEY_REQUIRED`; purchase-svc `404 PURCHASE_PULLBACK_SUPPLIER_UNKNOWN`, `422 PURCHASE_RTV_OVER_RETURN` (existing, per order).

## Money, time and limits

- **Currency:** the home currency for the preview's value (cost of the stock pulled); a vendor return is in the order's own currency, as now.
- **Ledger postings:** none for the transfers (a move between the business's own stores); the supplier stage posts through the existing vendor-return path (the debit note, and the credit note when it arrives).
- **Dates:** UTC instants recorded; `returnBy` and its overdue day judged in each shop's own zone.
- **Plan limits:** none.

## Constraints

- Append-only: `pullback_events`, `stock_movements`, `transfer_discrepancies`. The tracking row is a status projection with its own event log.
- The shops are drawn from **at issue**, not from the preview: quantities are read again in the shop's transaction, so a sale between preview and issue changes nothing wrongly (a shop that sold everything is `NOTHING_TO_RETURN`).
- Golden rule 1: purchase-svc's orders and inventory-svc's stock are asked for by REST or events; the proposal is computed from what each returns, never joined.
- Every draw obeys the rules already in `deductBatches`: no bonded, no expired, no held stock; a recall's RECALLED batches are drawn only by an instruction that names it.
- Existing tenants: nothing changes until an instruction is issued; a manual transfer is untouched.
- Never invent policy: no ceiling or deadline is named by the platform; `returnBy` and the approvals rule are the business's.

## Already there

- Manual transfers, ship, receive (as counted after [transfer-discrepancies](transfer-discrepancies.md)), cancel; depot serving relationships; recalls and RECALLED batches with a quarantine; vendor returns per order with debit notes and `ReturnedToVendorHandler`; a recall's close measures units unaccounted for.
- Not there: any instruction across shops, any per-shop tracking, any move type that can draw recalled stock into a return, any way to place stock returned from many shops on the supplier's orders.

## Open questions

- [x] Where does it go? → **the shop's serving warehouse by default, or one warehouse the issuer names; a supplier stage follows from the warehouse** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does a supplier return go from the shops directly? → **no: a vendor return is per order and per store; it starts at the warehouse** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Are the transfers held for release? → **no: the issuer's choice is the release; a large instruction needs a second person (`stock.pullback`) as a whole** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What of stock that cannot be placed on an order? → **listed as unplaced with its reason, never guessed onto an order** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does sale stop at the shops? → **only through a recall; a pull-back moves stock, not the till's rules** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What of bonded, expired and held stock? → **excluded and listed: each has its own release or disposal, and a held order is not ours to pull** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] A recall pull-back and approval? → **the recall is an urgent action and is not held for want of a rule; the alert watches its use** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] The preview shows each shop's eligible quantity by condition and the reason each skipped shop is skipped; bonded, expired and order-held stock is excluded and listed; it writes nothing — `PullbackIT.thePreviewIsHonestAndWritesNothing`, `PullbackPlanTest` (pure)
- [ ] Issuing raises one PENDING transfer per shop for the quantity then on hand, `source = PULLBACK`, drawn AVAILABLE first; a shop with none is `NOTHING_TO_RETURN` with no transfer — `PullbackIT.oneTransferPerShop`
- [ ] Quantities are read at issue, not at preview: a sale between them changes the transfer and shortens nothing wrongly — `PullbackIT.quantitiesAreReadAtIssue`
- [ ] A crash or retry mid-issue continues; the same key never raises a shop's transfer twice — `PullbackIT.issueIsResumableAndOnce`
- [ ] Default destination is each shop's serving warehouse; an explicit one must be a WAREHOUSE and not the shop itself (`422 INVENTORY_PULLBACK_DESTINATION_INVALID`); a shop with no warehouse and none named is skipped `NOT_SERVED` — `PullbackIT.destinations`
- [ ] Shipping and receiving each transfer updates its shop's row; a short receipt shows `RECEIVED_SHORT` with the discrepancy; the instruction is `COMPLETE` once every shop is done, and announces it once — `PullbackIT.trackingToDone`, `PullbackCompletedHandlerTest`
- [ ] A recall-named instruction draws RECALLED batches into the transfers; an ordinary one never does — `PullbackIT.recalledStockComesOnlyWithItsRecall`
- [ ] A late shop (past `returnBy` in its own zone) is announced once, in two zones — `PullbackOverdueSweeperIT`
- [ ] Cancel cancels unshipped transfers, leaves shipped ones, closes the rest; a complete instruction cannot be cancelled `409` — `PullbackIT.cancel`
- [ ] Above the `stock.pullback` rule a second person approves the whole and the transfers are then made with no second `stock.transfer` check; below or with no rule the issuer's own permissions decide; a recall-named instruction is not held — `PullbackIT.aLargeInstructionNeedsASecondPerson`
- [ ] The supplier proposal places the warehouse's received quantity on the supplier's orders newest first within received-not-returned per order at each order's price, lists the rest as `unplaced` (including consignment) and raises nothing — `SupplierReturnProposalIT`, `ReturnAllocationTest` (pure)
- [ ] Raising the returns carries `pullbackId`; the handler marks quantities returned once per event; over-return is `422 PURCHASE_RTV_OVER_RETURN` — `SupplierReturnProposalIT.returnsCarryThePullback`
- [ ] Who: a cashier and a storekeeper cannot issue; a manager held to some stores cannot issue for others (`403 STORE_ACCESS_DENIED`) or for every shop (`403 BUSINESS_WIDE_ONLY`) — `PermissionsIT.pullbacksAreGated`, `StoreScopeIT.pullbacksHoldToTheCallersStores`
- [ ] Isolation: another business's staff of every role and a shopper, naming our instruction, shop or recall, get 404 and nothing moves — `PullbackTenantIsolationIT`
- [ ] Retry: the same key answers with the first — `PullbackIT.retries`
- [ ] The record: who issued, approved, cancelled and when; per-shop events; nothing edited — `PullbackIT.theRecordIsAppendOnly`
- [ ] Abuse: many pull-backs by one person raise `pullbacks.count` and `pullbacks.value` — `PullbackAlertIT`
- [ ] A business in another country and zone runs the same flow with its own currency for the supplier stage — `PullbackIT.spansBusinessesInDifferentCountries`
- [ ] Widgets: the wizard (what, where, preview, issue), the progress view, the supplier stage — `pullbacks_test`, `pullback_supplier_stage_test`
- [ ] k6 `pullback-flow`: a product across three shops, preview, issue, ship, receive one short, complete, propose and raise the supplier return, a cashier refused, a rival refused

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet.
- **New approvals key** `stock.pullback` (shape B, second person; measure: total cost at the preview's figures in the home currency and the number of shops; open when no rule exists, closed when the rule cannot be read; owner inventory-svc; a recall-named instruction is not held). To be added to the [approvals](approvals.md) table when built.
- **New alert metrics** for [exception-alerts](exception-alerts.md), owned by inventory-svc: `pullbacks.count`, `pullbacks.value` (instructions issued and their cost, by one person, in a period; subjects staff, store).
- **New move type** `PULLBACK` in `deductBatches` (draws AVAILABLE, and RECALLED only with its recall).
- **Additive fields:** `ReturnedToVendor.pullbackId`.

## Screens

- **Admin > Inventory > Pull-backs** (new tab, management): a list of instructions in words (*Recall of oat milk: 26 of 30 shops done*), status badges (Issuing, In progress, Complete, Cancelled).
- **Start a pull-back** (adaptive wizard): what (product picker or a group), why (reason, note, recall picker), which shops (all, a set), where it goes (each shop's own warehouse, or choose one), the days allowed; **Preview** shows a table of shops with quantity, condition and any reason skipped, with the total value in the business's currency; **Issue** asks for a second person only where the rule says so and shows who can approve.
- **Progress view:** each shop with its status, quantity asked, shipped, received, short, the transfer link and a nudge action to remind a late shop (a notification, not a change); totals at the top.
- **Send to the supplier** (from the progress view, and Procurement > Returns): the proposal by order with quantities and prices, an *unplaced* section that says why, and Raise (per order, through the existing vendor-return dialog with `pullbackId`).
- Everything through the shared widgets and tokens; money through `AppFormat.money`; dates in each shop's zone; words not codes.

## Flow Tests entry

- **Catalogue area and file:** `target/flow-catalogue/inventory/mass-return-transfers.json` (the inventory domain, beside `trf-store-transfers` and `rcl-product-recalls`; the supplier stage cases are cross-referenced from `procurement/vendor-returns-and-credit-notes.json`). The feature is not BUILT until this entry exists and its cases are automated.
- **Cases:**
  - Happy: preview, issue, ship, receive and complete across shops (`PullbackIT.trackingToDone`, k6 `pullback-flow`); a recall pull-back draws recalled batches (`PullbackIT.recalledStockComesOnlyWithItsRecall`); the supplier proposal and returns (`SupplierReturnProposalIT`).
  - Negative: destination not a warehouse; no reason; unknown recall; nothing to do; a complete instruction cancelled; over-return on an order (`422`).
  - Override: a second person approves a large instruction; a recall is not held; an explicit destination overrides each shop's own.
  - Isolation: other business's staff of every role and a shopper; a manager held to other stores; a shop of another business named.
  - Edge: a sale between preview and issue; bonded, expired and order-held stock; consignment stock; a shop with no warehouse; a short receipt; a crash mid-issue; two zones for `returnBy`; unplaced quantities.
  - Audit: instruction, approval, cancel and per-shop events append-only; the alert on repeated instructions (`PullbackAlertIT`).
