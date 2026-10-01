# Receiving controls: who supplied it, bonded arrivals held, the right zone, consignment disputes

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on receiving (inv-receiving-stock, trf-wave-picking-putaway, inv-consignment-ownership-settlement) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, inventory domain: inv-receiving-stock, inv-bond-and-duty-release, trf-wave-picking-putaway, inv-consignment-ownership-settlement |
| **Services** | inventory-svc owns receipts, holds and putaway · purchase-svc owns suppliers, consignment sales, disputes and their postings · product-svc owns a product's storage class · tenant-svc owns zones and their storage class · notification-svc tells the managers · the app gets the screens |
| **Builds on** | `POST /admin/inventory/receive` (`InventoryService.receive`), `GoodsReceivedHandler` → `receiveOnce` (which only logs a warning at a store not approved as bonded, InventoryService.java:584), `bond_approvals`, `duty_status`, batch `material_status` QUARANTINE, `putaway_rules` / `putaway_tasks` / `PutawayRepository.directTx` and `place`, tenant-svc `zones.type`, purchase-svc `consignment_sales` / `consignment_settlements`, [bonded stock](../CLAUDE.md) and [consignment](../CLAUDE.md) conventions |
| **Built in** | |

## Problem

Four small doors are open in the goods-in path.

1. **Unreceipted stock has no source.** A receipt with no purchase order can be made by any staff member for any quantity and cost, and records no supplier at all, so stock of unknown origin enters the books.
2. **A bonded delivery at the wrong store is waved through.** The direct receipt refuses a duty-suspended batch at a store not approved as bonded, but the event path (a purchase order's goods receipt) only logs a warning and accepts the supplier's word, because throwing would lose a real delivery. Duty-suspended goods then sit in a store with no bond approval.
3. **Putaway does not know what a zone is for.** A chilled product can be placed in an ambient aisle, by a rule or by a person, and nothing objects.
4. **A consignment sale cannot be queried.** The supplier says a sale was priced wrong; there is no way to record the dispute, correct the sale, or keep it out of the statement while it is argued.

## Outcome

- **Every receipt says where the goods came from**: a supplier and, if known, the delivery note. A receipt with no purchase order and no supplier is refused.
- **A bonded delivery arriving at a store with no bond approval is never lost and never sold**: it is received, held apart, and a manager is told with three ways to resolve it (approve the store as bonded, correct the duty status because the supplier was wrong, or send it back).
- **A product knows how it must be stored and a zone knows what it offers**: putaway refuses to put a chilled product in an ambient zone, unless a manager overrides with a reason on the record. Storage kinds are the business's data, not a list in code.
- **A supplier's query on a consignment sale is a recorded dispute.** The sale is held out of the statement until it is settled one way or the other, and a correction changes the money without rewriting the sale.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the storekeeper (receives, puts away), the store manager and owner (resolve holds and overrides, set storage classes), the buyer or finance clerk (disputes and corrections), the supplier (only affected).
- **Channels:** back-office (admin app: Inventory, Procurement).
- **Scope:** per store for receipts and holds; per business for storage classes and consignment; per zone for its class.
- **Roles that can write:** any staff at the store to receive (as now); management to resolve a bond hold, override a zone mismatch, and manage storage classes; management (already the whole `/admin/consignment/**` path) to dispute and correct a consignment sale. A receipt without a purchase order above a value/quantity ceiling needs a second person through [approvals](approvals.md), action key `stock.receipt-unordered`, what is approved: the receipt itself, which enters the books once approved.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Source on an unreceipted receipt** (inventory-svc). `POST /admin/inventory/receive` and `/receive/batch` take `supplierId` (purchase-svc's supplier, referenced, never joined), `deliveryNote` and, for stock that did not come from a supplier, `sourceType` (`FOUND`, `GIFT`, `SAMPLE`, `OTHER`, with a note). A plain OWNED receipt names one of the two or is refused `400 INVENTORY_RECEIPT_SOURCE_REQUIRED`. The supplier is checked through purchase-svc (REST, with the client's timeout); an unknown supplier is `404 INVENTORY_SUPPLIER_UNKNOWN`; an unreadable answer stores the id as unverified and lets the receipt through, because a dock must not stop on a network fault. The batch carries `supplier_id`, `delivery_note`, `source_type`; a consignment receipt already names its supplier (`owner_supplier_id`) and is unchanged. The ceiling above which a receipt with no order waits for a second person is named on [approvals](approvals.md) (`stock.receipt-unordered`).
  2. **A bonded arrival at an unapproved store is held** (inventory-svc). `receiveOnce` no longer only warns. The batch is received with `duty_status = DUTY_SUSPENDED` as the supplier said and `material_status = QUARANTINE` (never available, never drawn by a sale, hold, wave or transfer, as any quarantined batch), and a `receiving_holds` row `BOND_NOT_APPROVED` is raised; `ReceivingHoldRaised` is published. Nothing throws in the consumer: a real delivery is never lost from the books. Managers are told by notification-svc. **With [goods-receipt-detail](goods-receipt-detail.md)** (both change `GoodsReceivedHandler`): a damaged part of the same line is a `DAMAGED` batch whatever its duty status and is never part of the hold; the hold applies to the good part's batches, and a hold's resolution moves only batches that are `QUARANTINE` because of the hold.
  3. **Resolving a bond hold** (inventory-svc). `POST /admin/inventory/receiving-holds/{id}/resolve` (management, store access), with `APPROVED_BONDED` (the store now has a live `bond_approvals` row: the batch flips to AVAILABLE and stays suspended, in bond), `DUTY_PAID` (the supplier was wrong: `duty_status` becomes DUTY_PAID, the batch AVAILABLE, with a required note, and purchase-svc is told through the corrected `GoodsReceived`-style fact so the order's record is right), or `RETURN` (the batch is drawn as RTV and purchase-svc's return flow is opened). Each resolution is a row (append-only). `GET /admin/inventory/receiving-holds` lists open holds at the caller's stores.
  4. **Storage classes as data** (tenant-svc, product-svc, inventory-svc). tenant-svc gains `storage_classes` per business (key, label, `also_holds` list of keys: a chilled zone may also hold ambient goods if the business says so), seeded for a new business with `AMBIENT`, `CHILLED`, `FROZEN`, `HAZARDOUS` as editable data, no temperatures (the food-safety checks own temperature limits). A zone gains `storage_class` (null = no rule); product-svc gains `products.storage_class` (null = no rule). inventory-svc reads the product's class from one projection, `variant_handling`, fed by **`VariantHandlingSet`** (`storeql.product.variant-handling-set`; the single definition, shared with [expired stock](expired-and-short-dated-stock.md) slice 1 and goods-receipt-detail): `eventId`, `tenantId`, `productId`, `variantId` (one event per variant of the product, so a product-level attribute reaches every variant), `dateKind` (`USE_BY`, `BEST_BEFORE` or absent = `USE_BY`), `storageClass` (a key or absent = no rule), `tracking` (`NONE`, `LOT`, `LOT_EXPIRY`; purchase-svc reads it by REST as [goods-receipt-detail](goods-receipt-detail.md) says, the projection carries it for inventory-svc's own checks), `occurredAt`; published on any change of the three and once per variant by a backfill run on the day it ships. The zone's class and status come from the **`ZoneStatusChanged`** projection ([workforce-rules](workforce-rules.md) slice 9, which carries `storageClass` too: one zone projection, not a second REST read), failing open (no rule) when a zone is not yet projected.
  5. **Putaway refuses the wrong kind** (inventory-svc). When a zone is set on a batch, by a `putaway_rules` match, by `place`, by a receipt naming a zone, or by a move order's target, the product's class must equal the zone's class or be listed in the zone class's `also_holds`; otherwise `422 INVENTORY_ZONE_WRONG_KIND`. Creating a `putaway_rules` entry that would place a product in a wrong-kind zone is refused the same way. Management may pass `overrideZoneKind: true` with a reason; recorded in `putaway_overrides` (append-only) with who and why. Either side unset is no check.
  6. **Consignment sale disputes** (purchase-svc). `POST /admin/consignment/sales/{id}/dispute` (reason, the supplier's claimed unit price or quantity) opens a `consignment_sale_disputes` row (append-only, with status rows for resolution). While open the sale is left out of any new settlement statement; naming it in a statement is `409 PURCHASE_CONSIGNMENT_SALE_DISPUTED`. `POST .../disputes/{id}/resolve` either `UPHELD` (a `consignment_sale_adjustments` row (append-only) with the corrected price or quantity, and the ledger posts the difference: a reduction Dr 2100 Trade Creditors / Cr 5010 Consignment Purchases, an increase the reverse, dated the resolution) or `REJECTED` (the sale stands and re-enters the next statement). The sale row itself is never rewritten; a statement's totals are the sale plus its adjustments. A sale already settled cannot be disputed (`409 PURCHASE_CONSIGNMENT_SALE_SETTLED`); a correction after settlement is a credit note on the supplier's next statement (an adjustment dated later).
  7. **The screens** (Flutter). See Screens.
- **Out, on purpose:**
  - **Physical goods-in inspection and QA sampling on receipt.** A separate quality feature; this page holds and refuses, it does not schedule inspections.
  - **Deciding a product's storage class for the business.** Products start with no class (no check); the business sets them, in bulk from the catalogue import.
  - **Temperature limits per class.** They live in food-safety checks already; a class is a compatibility rule, not a limit.
  - **Stopping a receipt at the till of goods that do not match a delivery note's quantities.** That is the purchase order's three-way match.
  - **Deriving duty.** The platform still derives none; management's rates stand.
  - **Supplier-facing dispute portal.** The buyer records the query; a supplier login is not built.

## Data and flow

- **Owned by inventory-svc:**
  - `inventory_batches` gains `supplier_id` (UUID, purchase-svc's, referenced), `supplier_verified` (boolean), `delivery_note`, `source_type`.
  - `receiving_holds`: id, tenant_id, store_id, batch_id, reason (`BOND_NOT_APPROVED`), status (`OPEN`|`RESOLVED`), raised_at; and `receiving_hold_resolutions` (append-only): hold_id, resolution, note, by, at.
  - `putaway_overrides` (append-only): batch_id, zone_id, product_class, zone_class, reason, by, at.
  - `variant_handling` projection: variant_id, date_kind, storage_class.
- **Owned by tenant-svc:** `storage_classes` and `zones.storage_class`. **Owned by product-svc:** `products.storage_class`, published with `dateKind` as `VariantHandlingSet`.
- **Owned by purchase-svc:** `consignment_sale_disputes`, `consignment_sale_adjustments`.
- **Needs from other services:** the supplier's existence (purchase-svc REST); the zone's class (tenant-svc REST, cached); the product's class (event). No joins.
- **Events published:** `ReceivingHoldRaised` (`storeql.inventory.receiving-hold-raised`) → notification-svc (`RECEIVING_HOLD`); `ReceivingHoldResolved` → purchase-svc when the resolution is `DUTY_PAID` or `RETURN`. `VariantHandlingSet` (`storeql.product.variant-handling-set`) → inventory-svc. Existing `StockReceived` and `GoodsReceived` unchanged.
- **Retryable writes (Idempotency-Key):** receive (already), resolve hold, resolve dispute, place with override.
- **New error codes:** `400 INVENTORY_RECEIPT_SOURCE_REQUIRED`, `404 INVENTORY_SUPPLIER_UNKNOWN`, `422 INVENTORY_ZONE_WRONG_KIND`, `409 INVENTORY_HOLD_RESOLVED`, `400 INVENTORY_HOLD_NOTE_REQUIRED` (for `DUTY_PAID`), `409 PURCHASE_CONSIGNMENT_SALE_DISPUTED`, `409 PURCHASE_CONSIGNMENT_SALE_SETTLED`, `404 PURCHASE_CONSIGNMENT_DISPUTE_NOT_FOUND`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** the receipt's cost is in the business's currency as now; a consignment correction is in the sale's own currency as recorded.
- **Ledger postings:** a receipt posts nothing new. A consignment correction posts the difference between the sale's posting and the corrected one (Dr 2100 / Cr 5010 for a reduction), once, in the same transaction as the adjustment.
- **Dates:** UTC instants.
- **Plan limits:** none.

## Constraints

- **Never lose a delivery:** an event consumer does not throw for a business rule; it holds (already the reason the warning exists).
- Append-only: adjustments, disputes' resolutions, hold resolutions, overrides, movements.
- Existing tenants: no storage class anywhere means no zone check; a manual receipt from an old client without a source is refused, so this needs the app updated in the same release (the receive dialog gains the field).
- Consignment stays ours to report apart; a correction never touches the stock ledger, only the money owed.

## Already there

- The direct receipt refuses a duty-suspended batch at a store not approved as bonded: `400 INVENTORY_STORE_NOT_BONDED` (InventoryService.java:231; `BondIT.dutySuspendedStockIsHeldOnlyInBondAndNeverSold` line 188 and the ended-approval case line 386). The batch, adjust, return, transfer, yield, cross-dock and putaway paths cannot make suspended stock (inventory-svc note, 30 Sep).
- Approving a store as bonded and setting duty rates are management-only, and releasing duty-suspended stock needs `stock.adjust` at the store (wave 1, `BondIT.aReleaseNeedsStockAdjustAtAStoreTheCallerKeeps`, `PermissionsIT.bondReleaseIsGated`).
- Consignment endpoints are management-only through the path (`AdminAuthorizationFilter.requiresManagement`: `/admin/consignment/**` is not on the staff-admin list); the catalogue's doubt is answered, and `ConsignmentIT` should name that mechanism (added here as an acceptance line).
- Consignment sales, statements and the `ConsignmentStockSold` posting exist (purchase-svc `ConsignmentResource`, `consignment_sales`, `consignment_settlements`).
- Directed putaway exists (rules, tasks, place; `PutawayIT`) with no zone-kind check; zones have a shape `type` (AISLE, RACK, COLD_ROOM, …) that says what they are, not what they may hold, so a class is added rather than inferred from it.

## Open questions

- [x] What does a bonded arrival at an unapproved store become? → **received and held in quarantine with a task for a manager, never refused and never sold** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How is a hold resolved? → **approve the store as bonded, correct the duty status with a reason, or return to the supplier** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Must an unreceipted receipt name a source? → **yes: a supplier, or a stated non-supplier source** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Are storage kinds a fixed list? → **no: business data, seeded with common kinds, with no rule where unset** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can a manager put a product in a zone of the wrong kind? → **yes with a reason, recorded** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a disputed consignment sale settled meanwhile? → **no; it waits out of the statement** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A manual OWNED receipt with no supplier and no source is `400 INVENTORY_RECEIPT_SOURCE_REQUIRED`; with a supplier the batch carries it — `InventoryIT.anUnreceiptedReceiptNamesItsSource`
- [ ] An unknown supplier is `404`; an unreadable purchase-svc lets it through unverified — `InventoryIT.supplierChecks`
- [ ] A suspended arrival by event at an unapproved store is received quarantined, never available, a hold is raised, one event, once on redelivery — `BondIT.aBondedArrivalAtAnUnapprovedStoreIsHeld`
- [ ] Each resolution works: approving the store frees it in bond; duty-paid frees it as paid and needs a note; return draws it — `ReceivingHoldIT`
- [ ] A cashier cannot resolve a hold (`403`); another business's staff naming our hold get 404 and nothing moves — `ReceivingHoldIT.anotherBusinessCannotResolve`
- [ ] A chilled product into an ambient zone is `422 INVENTORY_ZONE_WRONG_KIND` by place, by rule, by receipt zone and by move order; a class that lists it accepts it; unset either side is no check — `PutawayIT.zoneKinds`, `ZoneKindTest` (pure)
- [ ] A management override is recorded with reason; a storekeeper's is refused — `PutawayIT.aManagerOverridesAZoneKind`
- [ ] A dispute holds the sale out of the statement; upholding posts the difference once; rejecting returns it; a settled sale cannot be disputed — `ConsignmentDisputeIT`
- [ ] A consignment endpoint is refused to every non-management role by the shared filter — `ConsignmentIT.consignmentIsManagementOnly` (names the path mechanism)
- [ ] Another business's staff see and change none of our disputes — `ConsignmentDisputeIT.anotherBusinessSeesNothing`
- [ ] Widget: receive dialog requires the source; the holds list resolves — `inventory_receive_test`, `receiving_holds_test`
- [ ] k6 `bond-flow`: a suspended receipt at an unapproved store is held, resolved by approval, then sellable only after release

## Screens

- **Admin > Inventory > Receive** (existing dialog): "Where did it come from?" with a supplier picker (purchase-svc's suppliers by name) or "Not from a supplier" and its kind; delivery note field. A refusal reads in words.
- **Admin > Inventory > Holds** (new small tab, badge on the Inventory shell when open): rows with product, quantity, store, why in words ("This shop is not approved to hold goods before duty is paid"), and actions Approve shop as bonded, Correct to duty paid (note required), Return to supplier.
- **Admin > Business settings > Storage kinds** (management): list, add, rename, mark which kinds a kind also holds; **Zones** (tenant-svc's existing zone screen) gets a "Stores" kind dropdown; **Products** gets "Must be kept" with the same list.
- **Picking & putaway** (existing tab): a refused placement says why and, for management, offers "Place anyway" with a reason.
- **Procurement > Consignment** (existing statements area): a sale's row gains "Query this sale", a dispute sheet (reason, the supplier's price or quantity), and Resolve for management; disputed sales are marked and not selectable for a statement.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **One path per kind of delivery.** A supplier's delivery with no order goes through [shipping-notices-and-direct-deliveries](shipping-notices-and-direct-deliveries.md) slice 2 (purchase-svc `POST /direct-deliveries`, for a supplier flagged `direct_delivery_allowed`, cost typed, booked to GR/IR through `GoodsReceived`). Slice 1 here stays the inventory-side rule for a direct receipt (`POST /admin/inventory/receive`): it needs a source, and where that source is a supplier flagged for direct deliveries it is refused `409 INVENTORY_USE_DIRECT_DELIVERY` and points to that route, so a supplier's goods are never booked by two doors. Both enforce the one approvals key `stock.receipt-unordered`.

- 2026-09-30: settled by industry standard as above; nothing built yet.
