# Transfer discrepancies: what actually arrived, and stock in transit

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on transfers (trf-store-transfers TRF-13, trf-move-orders, trf-cross-dock) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, inventory domain: trf-store-transfers, trf-move-orders |
| **Services** | inventory-svc owns transfers, move orders, the discrepancy record and the in-transit figure · purchase-svc posts the loss to the ledger · notification-svc tells the people concerned · reporting-svc reads the events · the app gets the receiving screen |
| **Builds on** | `transfer_orders` / `transfer_order_lines` (`shipped_qty`, `received_qty`), `receiveTransferOrder` (sets `received_qty = shipped_qty`), `TransferOrderShipped/Received`, `move_orders` / `move_order_lines` (`picked_qty`), `CROSSDOCK` transfers ([cross-docking](cross-docking.md)), `DcReplenishment` transfers ([depot-dc-replenishment](depot-dc-replenishment.md)), batch `material_status` DAMAGED, `transaction_reason_codes`, ledger account 1001 Stock |
| **Built in** | |

## Problem

A store receiving a transfer can only say "it all arrived". The receipt copies the shipped quantity into the received quantity for every line, so ten shipped and eight on the pallet is eight units missing from the books nowhere: the sending store's stock fell by ten, the receiving store's rose by ten, and the two extra units exist only on paper until a stocktake finds them. The fix today is a later adjustment that has lost its link to the transfer, so nobody can tell transit loss from shrinkage, claim it from a carrier, or see which route loses stock. Between "shipped" and "received" the stock is on no shelf and on no report except the transfer header. A move order has the same blind spot: it moves the full requested quantity or refuses the whole pick.

## Outcome

- **A receiving store records what it counted:** per line, how many arrived good, how many arrived damaged, and why any is short or over. Saying nothing still means "all as shipped", so the quick path stays one press.
- **The difference is a record, not a guess.** Short, over and damaged each become a discrepancy that names the transfer, the line, the batches shipped, the quantity, the value at cost, who counted, and why.
- **A short quantity is booked as in-transit loss, once, at the shipped cost, through the ledger**, not as anonymous shrinkage. It can be resolved later: the units turn up (received then), a carrier or vendor claim is noted, or it stays written off.
- **An over-receipt is accepted, flagged, and told to the sending store** (they may be short elsewhere); it arrives under the shipped lot's date and cost.
- **Damaged goods arrive as damaged stock**, off sale, in the receiving store's books until someone writes them off through the existing stock adjustment.
- **Both stores' managers are told the same day.**
- **Stock in transit is visible as such:** each store sees what is on its way in, valued, with who shipped it, when, and (if given) the carrier, a reference and the date expected. A shipment that has been out longer than the business allows is flagged.
- **A move order picked short records the quantity actually moved**, and the rest is closed short or stays open for a later pick.
- **Cross-dock and depot transfers follow the same receipt.**

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the storekeeper at the receiving shop (records), the store manager at either end (is told, resolves), the owner (sets the business's settings), the finance clerk (sees the loss on the ledger).
- **Channels:** back-office (admin app, Inventory > Transfers).
- **Scope:** per transfer, at the receiving store; a caller must be able to act at that store (`requireAnyStoreAccess`, as receipt does now). Resolution: management at either store.
- **Roles that can write:** `stock.transfer` to receive and to ship with a carrier; OWNER/MANAGER (management) to resolve a discrepancy or write one off; **a large loss is not held for a second person:** the goods have already arrived short, so a receipt is always recorded (refusing to would leave real stock outside the books). A large or repeated loss is instead an alert, `transfers.discrepancy_value` on [exception-alerts](exception-alerts.md) (this page first named the approvals key `stock.transfer` for it; that key covers only releasing a transfer, see [approvals](approvals.md) Decisions).
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Receive with counts** (inventory-svc). `POST /transfers/{id}/receive` accepts an optional list of lines, each with `receivedQty`, `damagedQty`, `reasonCode` and `note`; no body means as shipped. The quantities land as batches (good under the shipped lots and costs, damaged as DAMAGED); `received_qty` records the real figure. Refuses an unknown line, a negative figure, a repeated line. Idempotency-Key required.
  2. **The discrepancy record** (inventory-svc). `transfer_discrepancies` (append-only) with a kind SHORT, OVER or DAMAGED. `TransferDiscrepancyRecorded` published on the receipt's transaction; the `TransferOrderReceived` event gains per line `receivedQty`, `damagedQty`.
  3. **In-transit loss on the ledger** (purchase-svc). Consumes the event; posts each SHORT once: Dr *Stock lost in transit* (a new expense account, seeded with the other platform accounts) / Cr 1001 Stock, at the cost of the shipped batches (home currency), dated the receipt. A discrepancy resolved as FOUND posts the reverse. An OVER posts the opposite way (Dr 1001 / Cr the same account), so the two net.
  4. **Tell people** (notification-svc). The managers of both stores get `TRANSFER_DISCREPANCY` (in-app; email where their preferences say so), naming the route, the product in words, quantity, value and reason.
  5. **Resolution** (inventory-svc). `POST /transfer-discrepancies/{id}/resolve` with `FOUND` (the units are received now, as a further receipt under the transfer), `CLAIMED` (a carrier or vendor claim reference, no stock effect) or `WRITTEN_OFF` (closes it; nothing more is posted, the loss was booked at receipt). Each is a row in `transfer_discrepancy_resolutions` (append-only); a discrepancy is resolved once.
  6. **In transit, seen** (inventory-svc, app). Ship accepts optional `carrier`, `reference`, `expectedAt`. The level answers gain `inTransit` at the destination store (quantity, and value on the valuation report, apart from `onHand`, the way `inBond` is apart). `GET /transfers/in-transit?storeId=` lists them by age. A setting `transfers.overdue-days` (off until set) makes a nightly sweeper publish `TransferOverdue` once per transfer, which notification-svc tells the receiving manager.
  7. **Move orders picked short** (inventory-svc). `POST /move-orders/{id}/pick` accepts per-line `pickedQty` (default the request). What was picked moves, the shortfall is recorded on the line (`short_qty`, reason) and the order either closes `COMPLETED_SHORT` or, if the caller says `keepOpen`, stays OPEN for the remainder. Nothing is lost (the stock never left the store's books in a partial move), so no ledger posting; the shortfall is a fact for the planner.
  8. **The receiving screen** (Flutter, see Screens).
- **Out, on purpose:**
  - **A carrier's tracking feed or ETA from a carrier API.** The carrier and reference are typed; a carrier integration is a separate driver feature ([ship-from-store](ship-from-store-and-dark-store-picking.md) records the same for orders).
  - **A separate "transit" warehouse or location.** Stock in transit is a figure and a list, not a place; putting it in a pseudo-store would break `store_id` scoping.
  - **Supplier short deliveries.** That is the purchase order's receipt (purchase-svc), not a transfer.
  - **Changing what was shipped.** The ship step is a fact; a mistake at the sending end is corrected by a stock adjustment there.
  - **Re-planning a cross-dock share after a short receipt.** The share was decided at the warehouse's receipt; the shop's own count is a discrepancy here, and the warehouse's owed line is not reopened.

## Data and flow

- **Owned by inventory-svc:**
  - `transfer_order_lines` gains `damaged_qty` (NUMERIC(18,3), default 0) and `over_qty` derived; `received_qty` is now set from the count.
  - `transfer_orders` gains `carrier`, `reference`, `expected_at` (TIMESTAMPTZ), `received_by` (UUID).
  - `transfer_discrepancies` (append-only): id, tenant_id, transfer_order_id, line_id, variant_id, kind, qty, unit_cost and value (NUMERIC(18,4), home currency named), reason_code, note, recorded_by, recorded_at. Composite index (tenant_id, transfer_order_id) and (tenant_id, recorded_at).
  - `transfer_discrepancy_resolutions` (append-only): discrepancy_id (unique), resolution, reference, resolved_by, resolved_at.
  - `move_order_lines.short_qty`, `short_reason_code`; move order status gains `COMPLETED_SHORT`.
  - Seeded reason codes (platform-wide, `transaction_reason_codes`): `TRANSIT_LOSS`, `TRANSIT_DAMAGE`, `MISCOUNT_AT_SHIP`.
- **Needs from other services:** the home currency through `TenantProfiles`; product names for the notification through the catalogue projection notification-svc already reads. No joins.
- **Events published:** `TransferDiscrepancyRecorded` (`storeql.inventory.transfer-discrepancy-recorded`: tenantId, transferOrderId, fromStoreId, toStoreId, lines of kind/variantId/qty/value/currency, recordedBy) consumed by purchase-svc (posts once, keyed by the event id), notification-svc (tells) and reporting-svc (a discrepancy report by route). `TransferDiscrepancyResolved` (same fields plus resolution) consumed by purchase-svc (FOUND reverses the posting). `TransferOverdue` consumed by notification-svc. `TransferOrderReceived` gains fields, no field removed.
- **Retryable writes (Idempotency-Key):** receive, resolve, move-order pick.
- **New error codes:** `422 TRANSFER_RECEIPT_LINE_UNKNOWN`, `422 TRANSFER_RECEIPT_QTY_INVALID` (negative, or good + damaged exceeding shipped when over-receipt is not explained by a reason), `409 TRANSFER_DISCREPANCY_RESOLVED`, `404 TRANSFER_DISCREPANCY_NOT_FOUND`, `422 MOVE_ORDER_PICK_QTY_INVALID`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** the home currency of the business; a loss is valued from the shipped batches' cost (`Provenance.Drawn`), and a shipment with no recorded cost is valued at nothing and says so (uncosted), never guessed.
- **Ledger postings:** SHORT: Dr Stock lost in transit / Cr 1001 Stock at receipt; FOUND reverses; OVER the opposite; DAMAGED posts nothing until written off (the existing adjustment path).
- **Dates:** the receipt's instant is recorded in UTC; the overdue days count from `shipped_at`; the list shows the store's own day.
- **Plan limits:** none.

## Constraints

- Append-only: `stock_movements`, `transfer_discrepancies`, resolutions. A correction is a new row.
- Database-per-service: purchase-svc never reads inventory tables; it posts from the event's values.
- A DIRECT transfer moves on ship and has no in-transit; the count applies to INTRANSIT only, unchanged (`TRANSFER_ORDER_DIRECT_AUTO_RECEIVED`).
- Existing tenants: an old client that sends no body behaves as today.

## Already there

- Transfers exist with ship/receive/cancel/release, gated by `stock.transfer` and store access (`TransferOrderResource`); receipt reads back each source batch's lot, date and cost (`InventoryRepository.receiveTransferOrder`, 2909) so the arriving stock keeps them.
- Move orders exist with `picked_qty` on the line (`V7__move_orders.sql`); pick is gated `stock.transfer` (`MoveOrderResource`, SJ-D73).
- The Transfers tab in the admin app lists transfers.
- What is not there: any count at receipt, any in-transit figure, any loss posting.

## Open questions

- [x] Who bears a transit loss? → **the business's ledger, booked once to a stock-lost-in-transit account with both stores named on the record; attribution by route on the report, not by a per-store charge** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What if more arrives than was shipped? → **accepted, flagged as OVER, told to the sender, valued at the shipped cost; never refused, since refusing leaves real stock outside the books** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How is a damaged arrival treated? → **it arrives as DAMAGED stock (off sale) and is written off by the existing adjustment; the discrepancy record links the two by the transfer** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is there a value or age ceiling for a loss? → **the overdue days are the business's setting `transfers.overdue-days`, off until set; a large loss raises an alert (`transfers.discrepancy_value`), not an approval** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What does a move order picked short do to the remainder? → **the picker chooses: close short, or keep open** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Receiving 8 of 10 books received 8, a SHORT discrepancy of 2 at the shipped cost, and 8 units at the destination under the shipped lot — `TransferDiscrepancyIT.aShortReceiptRecordsWhatArrived`
- [ ] No body still receives all as shipped, and the second receipt is refused — `TransferDiscrepancyIT.noBodyReceivesAsShipped`
- [ ] Damaged units arrive DAMAGED, not available — `TransferDiscrepancyIT.damagedArrivesOffSale`
- [ ] An over-receipt is accepted and flagged OVER — `TransferDiscrepancyIT.overReceiptIsAcceptedAndFlagged`
- [ ] The loss is posted once, Dr loss / Cr 1001, on a redelivered event too; FOUND reverses it — `TransferLossPostingIT` (purchase-svc)
- [ ] Managers of both stores are told once — `TransferDiscrepancyHandlerTest` (notification-svc)
- [ ] In-transit appears at the destination store in levels and valuation, apart from on hand; leaves it on receipt — `TransferInTransitIT.aShipmentIsOnItsWayUntilReceived`
- [ ] An overdue shipment is announced once — `TransferOverdueSweeperIT`
- [ ] A move order picked short moves the counted quantity and closes short or stays open — `MoveOrderIT.aShortPickRecordsWhatMoved`
- [ ] A cross-dock and a depot transfer receive with the same counts — `CrossDockIT.aShopReceivesACrossDockShort`, `NetworkIT.aDepotTransferReceivesShort`
- [ ] Another business's staff of every role, naming our transfer, get 404 and nothing moves; a keeper of neither store gets `403 STORE_ACCESS_DENIED`; a cashier `403 PERMISSION_DENIED` — `TransferDiscrepancyIT.anotherBusinessOrStoreCannotReceiveOrResolve`
- [ ] A resolved discrepancy is refused a second resolution `409 TRANSFER_DISCREPANCY_RESOLVED` — `TransferDiscrepancyIT.resolvedOnce`
- [ ] k6 `transfer-flow` ships, receives short, resolves FOUND and sees the ledger net to zero

## Screens

- **Admin > Inventory > Transfers** (existing tab). A shipped transfer's row opens a receiving sheet: a table of lines (product by name, shipped, a "Received good" field defaulting to shipped, a "Damaged" field, a reason dropdown from the reason codes and a note shown when the line differs). Sends the counts with an Idempotency-Key; a refusal reads in words.
- A **"On its way"** filter and badge on the list, with age and the carrier/reference; overdue in the status colour role, never a hard-coded colour.
- **Discrepancies** section on the transfer's detail: kind, quantity, value through `AppFormat.money`, resolution actions for management (Found, Claimed, Written off).
- **Ship dialog** gains carrier, reference, expected date.
- Move orders screens are on [inventory-screens](inventory-screens.md) and send the per-line picked quantity.

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet.
