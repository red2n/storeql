# Cross-docking

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the readiness review's Supply chain, warehouse & logistics row · 2026-09-25 |
| **Roadmap** | Readiness Review, "Cross-docking", Absent (value). Taken after depot / DC replenishment at the user's word, because it builds on that page's serving relationships and system-raised transfers |
| **Services** | purchase-svc owns a purchase order's cross-dock allocations (which shop each part of a line is for) and announces them on the receipt · inventory-svc owns what happens at the warehouse's dock: the allocated stock never put away, raised straight into transfers to the shops · tenant-svc owns the store types, read through `TenantProfiles` |
| **Builds on** | `serving_relationships` (which shops a warehouse serves), transfers with a `source` and DRAFT/PENDING lifecycle, the putaway hook on every received batch, `purchase_orders` / `purchase_order_lines`, `GoodsReceived` (carries the receipt and the order), the warehouse's purchase proposal sized on its shops' demand, Procurement's order screen, the Inventory screen's Depot & shops tab |
| **Built in** | the commit "feat(inventory,purchase,app): straight across the dock — cross-docking" |

## Problem

A warehouse that orders for its shops still handles every case twice. The supplier's delivery is booked into the warehouse, put away on a shelf by the putaway rules, and days later picked back off the same shelf when a transfer proposal asks for it — even when the buyer knew, the day the order went out, that those forty cases were for Leeds and York. The stock sits, the shelf fills, and the shops wait for a replenishment run that only rediscovers what the buyer already decided.

## Outcome

A buyer can say, on a warehouse's purchase order, which of its shops each line is for and how much. When that delivery arrives at the warehouse, the part allocated to shops goes straight across the dock: it is not put away, and a transfer to each shop is raised at once, carrying the lot, date and cost the supplier delivered, linked to the order and the receipt it came from. What was not allocated is put away as today. A delivery that comes up short is shared among the shops as the allocation said, and the shops see it on its way the same day.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the buyer who raises the warehouse's order and allocates it; the warehouse storekeeper who receives and ships; the shop storekeeper who receives.
- **Channels:** back-office only.
- **Scope:** a purchase order delivered to a warehouse; allocations only to shops that warehouse serves.
- **Roles that can write:** allocations are the buying roles' (OWNER, MANAGER, STOREKEEPER as on the order today); the transfers raised at receipt follow `stock.transfer` at the warehouse as today.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:**
  - Allocating a line of a DRAFT purchase order delivered to a warehouse to the shops it serves: quantities per shop, never more than the line; editable until the order is submitted.
  - A "fill from the shops' needs" helper that allocates a line by the served shops' current needs (the same rule the transfer proposal uses), which the buyer can change.
  - At the goods receipt at the warehouse: the allocated quantity of each line is not put away; a transfer per shop is raised on the receipt's transaction in inventory-svc, from the batches the receipt made, carrying their lot, date and cost; each transfer names the order and the receipt (source CROSSDOCK).
  - A short receipt is shared among the shops in proportion to their allocations (the same fair share as a short warehouse); an over-receipt's extra stays at the warehouse and is put away.
  - The shop's position (depot replenishment) counts a cross-dock transfer on its way, so a replenishment run does not propose it again; the warehouse's purchase proposal counts allocated quantities on order as committed to those shops.
  - App: an Allocate action on a warehouse order's line, with "Fill from the shops' needs"; the order shows each line's allocation; the Transfers tab shows cross-dock transfers with the order they came from.
- **Out, on purpose:**
  - **Advance shipping notices and EDI**: the supplier telling the warehouse what is on the lorry before it arrives. Its own row ("EDI and advance shipping notices"), which needs a partner.
  - **Supplier pre-labelled, per-shop packs** (the supplier packing Leeds's and York's cases separately): the platform splits quantities, not physical cases.
  - **Dock scheduling, doors and outbound staging lanes**: transport and yard planning.
  - **Cross-docking without a purchase order** (a delivery arriving that nobody allocated, split on the dock by hand): the receipt is the purchase order's; a person can still raise a transfer by hand as today.
  - **Allocating to a shop the warehouse does not serve**, or from a shop's own order: refused; cross-dock is the warehouse's flow.
  - **Changing the allocation after submission**: the supplier has the order; a change is a new line or a transfer by hand.

## Data and flow

- **Owned by** purchase-svc: `purchase_order_line_allocations` (line, shop, qty). A line's allocations sum to at most its quantity.
- **Owned by** inventory-svc: `crossdock_expected` (what an order still owes each shop of each product, replaced whole per snapshot, drawn down by deliveries); on `transfer_orders`, source `CROSSDOCK` and the order and receipt ids; on `transfer_order_lines`, the `source_batch_id` a line ships first.
- **Needs from other services:** the shops a warehouse serves, and their needs for the fill helper, from inventory-svc over REST (`GET /admin/inventory/network/sourcing` and a needs read); the store types through `TenantProfiles`.
- **Events published:** `CrossDockAllocationsSet` (purchase-svc; a whole snapshot per order, keyed by the order: on submission the allocations, on rejection, cancellation or close-short an empty one); inventory-svc keeps it as what each shop is owed and consumes `GoodsReceived` as today — its `poId` finds what the order owes. `TransferOrderShipped` / `Received` as today.
- **Retryable writes** (Idempotency-Key): the receipt already is; the transfers it raises are idempotent with it (one set per `GoodsReceived` event).
- **New error codes:** `PURCHASE_ALLOCATION_NOT_A_WAREHOUSE` 400 (the order is not delivered to a warehouse); `PURCHASE_ALLOCATION_NOT_SERVED` 400 (a shop the warehouse does not serve); `PURCHASE_ALLOCATION_EXCEEDS_LINE` 400; `PURCHASE_ALLOCATION_ORDER_NOT_DRAFT` 409; `PURCHASE_ALLOCATION_STOCK_NOT_OWNED` 409 (a consignment or duty-suspended order).

## Money, time and limits

- **Currency:** none new; the transfer carries the delivered batch's cost, as transfers do.
- **Ledger postings:** none new. The receipt posts to the warehouse's GR/IR as today; a transfer is not a sale.
- **Dates:** the receipt's instant; the transfer is raised then.
- **Plan limits:** none.

## Constraints

Database-per-service: the allocation lives with the order in purchase-svc; the transfers live with the stock in inventory-svc; the event carries one to the other. Consignment and bonded stock: a consignment or duty-suspended order is refused at allocation (`PURCHASE_ALLOCATION_STOCK_NOT_OWNED`), as confirmed below. A receipt with no allocations behaves exactly as today, putaway included. Idempotent consumer: a redelivered `GoodsReceived` raises no second set of transfers.

## Open questions

- [x] **When the delivery arrives, are the cross-dock transfers ready to ship, or drafts to review?** Recommended: ready to ship (PENDING) — the buyer already decided who gets what when they allocated, and speed across the dock is the point. The alternative writes DRAFTs a person releases, as the replenishment run does. → **ready to ship: raised PENDING at receipt** (the user, 2026-09-25)
- [x] **Who decides the allocation?** Recommended: the buyer on the order, with a "fill from the shops' needs" helper they can change. The alternative allocates automatically from the shops' needs at the moment the delivery arrives, with nothing set on the order. → **the buyer on the order, with the fill-from-needs helper** (the user, 2026-09-25)
- [x] **A short delivery: who gets it?** Recommended: shared in proportion to the allocations, the remainder to the shop with the least cover (the same rule as a short warehouse). The alternative fills the allocations in the order they were entered. → **in proportion to the allocations, the remainder to the least cover** (the user, 2026-09-25)
- [x] **Can a consignment or duty-suspended order be cross-docked?** Recommended: no — refused at allocation, because consignment stock and bonded stock have their own transfer rules and duty must be released before stock leaves a bond. The alternative allows consignment (ownership rides with the transfer) and refuses only bonded. → **neither: refused at allocation** (the user, 2026-09-25)

## Acceptance

- [x] A buyer allocates a warehouse order's line to the shops it serves, replaced not added to, never more than the line, only while a draft; the fill helper allocates by the shops' needs — purchase-svc `CrossDockIT.aWarehouseOrdersLineIsAllocatedToItsShopsAndAnnouncedOnSubmission`
- [x] Refused: an order not for a warehouse `400 PURCHASE_ALLOCATION_NOT_A_WAREHOUSE`, a shop the warehouse does not serve `400 PURCHASE_ALLOCATION_NOT_SERVED`, more than the line `400 PURCHASE_ALLOCATION_EXCEEDS_LINE`, nothing `400 PURCHASE_ALLOCATION_QTY_INVALID`, a submitted order `409 PURCHASE_ALLOCATION_ORDER_NOT_DRAFT`, a consignment order `409 PURCHASE_ALLOCATION_STOCK_NOT_OWNED`, a cashier `403` — `CrossDockIT` (purchase-svc, both tests)
- [x] Submission announces the allocations (`CrossDockAllocationsSet`), cancellation an empty snapshot, an order with none nothing — `CrossDockIT` (purchase-svc)
- [x] inventory-svc replaces what is owed with each snapshot and clears it with an empty one; it is readable per order and another tenant reads none — inventory-svc `CrossDockIT.aSnapshotReplacesWhatWasOwedAndAnEmptyOneClearsIt`, `CrossDockIT.anAllocatedDelivery…`
- [x] At the warehouse's receipt the owed quantity is not put away and one PENDING transfer per shop is raised from the batch the delivery made, naming the order and the receipt; the surplus and the other lines are put away; a redelivered event raises nothing twice; shipping draws the delivered batch first — `CrossDockIT.anAllocatedDeliveryCrossesTheDockAndOnlyTheSurplusIsPutAway`
- [x] A short receipt is shared in proportion, the remainder to the least cover, the line saying so, the rest still owed — `CrossDockIT.aShortDeliveryIsSharedInProportionAndTheRestToTheLeastCover`
- [x] A shop's replenishment run counts what an order owes it as on its way — `CrossDockIT.anAllocatedDelivery…`; the warehouse's purchase proposal leaves allocated stock out of its own on-order — `ProposalService` (covered by `OrderProposalIT` and k6 `dc-replenishment-flow`)
- [x] The order screen allocates a line and fills it from needs, the line reading where it crosses to; a shop's order offers no allocation — widget `procurement_crossdock_test` (3); the Transfers tab says a transfer was cross-docked from its order
- [x] Through the gateway: allocated, refused where it must be, submitted, heard, delivered short, shared, shipped and received — k6 `cross-dock-flow` (15)

## Decisions

- **The allocations travel as a snapshot at submission, not on the receipt.** A receipt line knows its product, not its order line, and deliveries arrive in parts; carrying allocations on `GoodsReceived` would have left purchase-svc to guess what earlier parts already sent across. So purchase-svc announces the order's whole allocation when it is submitted (and an empty one when it stops being on its way), and inventory-svc is the one place that knows what is still owed — drawn down by each delivery, whatever order they come in. The events are keyed by the order, so one order's snapshots arrive in the order they were made.
- **What is owed counts as on its way to the shop.** Otherwise the replenishment run would propose, from the warehouse's shelf, the very stock the order is bringing; and the warehouse's own purchase proposal leaves allocated stock out of its on-order, since it is the shops', not the warehouse's. After a partial receipt the allocated figure there is still the order's whole allocation, floored at nothing — an over-cautious on-order, not an over-order.
- **A delivery's owed lines cross on one receipt-level transaction.** One transfer per shop with a line per product, not a transfer per line; deduped on the event as a whole. The other lines of the same receipt are received line by line as before.
- **What crosses is a batch of its own, never put away; the surplus is put away as any delivery.** A cross-dock line ships its batch first (`source_batch_id`, an ordering in the shared draw, not a copy of it), so the lot, date and cost that arrived are what leaves.
- **A short line is shared by the same rule as a short warehouse** (`DcReplenishment.share`: in proportion, remainder to the least cover), with what was not delivered still owed to each shop.
- **The consumer hands its work to a handler** (`CrossDockAllocationsHandler`), as the architecture test requires of every consumer.
