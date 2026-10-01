# Goods-receipt detail: good, damaged and refused quantities, lot and expiry, delivery note, and what goes back

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on receiving and vendor returns (`procurement/goods-receipt`, `procurement/vendor-returns-and-credit-notes`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, procurement domain (wave 2, group F). Wave 1 deferred it ("goods-receipt damaged/lot capture; proof of delivery", `_notes/2026-09-30-purchase-svc.md`) |
| **Services** | purchase-svc owns the receipt, its line detail, its documents and the vendor return · inventory-svc turns each part of a line into the right batch and never puts damaged stock on sale · product-svc says which products carry a lot and an expiry · tenant-svc holds the plan's allowance · the app's Receiving screen captures it |
| **Builds on** | `goods_receipts` / `goods_receipt_lines` (`qty_received` only), `POST /goods-receipts`, `GoodsReceived` (lines already carry `batchNo`, `costPrice`, `expiryDate` for inventory-svc's `GoodsReceivedHandler`; purchase-svc never fills the first and last), inventory batch `material_status` (AVAILABLE, QUARANTINE, INSPECTION, DAMAGED, RECALLED; only AVAILABLE counts as stock), `vendor_returns` / `vendor_return_lines`, `ReturnedToVendor`, `PURCHASE_RTV_INSUFFICIENT_STOCK`, the supplier-scorecard quality figure, `Entitlements.requireBytesWithin` and `documents.mb.max`, return-controls' condition words (sealed, opened, damaged, faulty) |
| **Built in** | not built |

## Problem

A delivery arrives with some units crushed, some the wrong item and some past their date. The storekeeper has one number to type, the quantity, so the whole delivery is booked as good stock: it is sellable, valued and owed to the supplier in full. Damage is dealt with afterwards, if at all, through a return-to-vendor that is a separate, later act and cannot say what condition the goods were in. Lot numbers and expiry dates, which fresh and regulated goods need on the shelf and in a recall, are not asked for at the door; inventory-svc has room for them and purchase-svc never sends them. There is nowhere to record the supplier's delivery note number or keep a photo of it. And a vendor return larger than the system's on-hand figure is refused with no way for a manager to say "the shelf is right, the count is wrong".

## Outcome

- **On each receipt line the storekeeper says how many arrived, and how many of those are damaged or refused.** The rest is good.
- **Damaged stock is booked and owed but never sold.** It sits in the store as a damaged batch, and one tap raises a vendor return for it.
- **Refused stock never becomes ours.** A refused quantity is on the receipt as evidence, is not booked, not invoiceable, and leaves the order line still owed.
- **A product that carries a lot and an expiry cannot be received without them.** They reach the batch, so a recall or a first-expiring-first-out pick works from day one.
- **The receipt carries the supplier's delivery note number and any photo or scan of it,** within the business's plan.
- **A vendor return says what condition the goods were in.** A return larger than the shelf can go through as a documented override by someone whose authority covers it.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **storekeeper** receiving, the **store manager** overriding, the **buyer** chasing a supplier.
- **Channels:** back-office and the store's receiving screen.
- **Scope:** per store; the receipt names the store (already checked against the order's store).
- **Roles that can write:** receiving stays as it is (any staff at the store). Raising a vendor return stays warehouse or management. The on-hand override is the approvals action `purchasing.vendor-return-beyond-onhand` (shape A: a manager within their ceiling does it in their own session, with a reason; nobody below the rule's tiers may). Reading documents: management and the receiving staff at the store.
- **Sandbox tenant:** behaves the same.

## Scope

- **In** (slices, in build order):
  1. **Good, damaged, refused (purchase-svc, inventory-svc).** A receipt line says `qtyReceived` (everything that came off the vehicle, as today), `qtyDamaged` and `qtyRefused` (both default 0, never negative, together not above `qtyReceived`), a defect reason when either is above 0, and good is what remains. A request that sends only `qtyReceived` reads exactly as before. **Counts toward the order:** good plus damaged (both are owned goods); a refused quantity does not, so the order line stays open and the scorecard's fill rate sees it. **Books:** good plus damaged are booked to stock at the order's price and to GR/IR, so the liability is real; a refusal posts nothing. `GoodsReceived` keeps `qty` as the good part (older consumers, cross-dock and consignment are unaffected) and adds `qtyDamaged`. inventory-svc makes a second batch for the damaged part with `material_status = DAMAGED`, which never counts as available and is never drawn by a hold or a sale. Refused quantities are not sent to inventory-svc at all.
  2. **Lot and expiry at the door (product-svc, purchase-svc).** product-svc gains a per-variant `tracking` of NONE, LOT or LOT_EXPIRY (default NONE, so nothing changes until a business sets it), read by purchase-svc through product-svc's REST (cached, fail open: an unreadable answer never blocks a receipt). A tracked line carries `lots: [{lotNo, expiryDate?, qty, condition GOOD|DAMAGED}]` that partition the accepted quantity; the lot number becomes the batch's `batchNo` and the expiry its `expiryDate` (inventory-svc already reads both). Refusals name the variant. A business may also set a minimum remaining shelf life (`receiving.min-shelf-life-days`, off until set): a lot with less is refused unless that quantity is marked refused.
  3. **Delivery note and documents (purchase-svc).** `deliveryNoteRef` on the receipt (text), and any number of attached documents (PDF, PNG or JPEG) kept append-only with a checksum; the same file twice is the same document. A second receipt for the same supplier with the same note reference is accepted with the warning `DELIVERY_NOTE_SEEN`, never refused (suppliers reuse them). The bytes count toward the business's `documents.mb.max` allowance (below).
  4. **Vendor return from the receipt, with condition (purchase-svc, inventory-svc).** `vendor_return_lines.condition` (SEALED, OPENED, DAMAGED, FAULTY, as return-controls names them) and a link to the receipt line it came from. `POST /goods-receipts/{id}/return-damaged` raises the return for a receipt's damaged quantities in one call (reason DAMAGED, condition DAMAGED), once per receipt line. `ReturnedToVendor` carries each line's condition; inventory-svc draws a DAMAGED line from the store's damaged batches and every other line from sellable ones. The on-hand check for a damaged line reads damaged on-hand.
  5. **A return larger than the shelf (purchase-svc, inventory-svc).** A return line may carry `onHandOverrideReason`. With the approvals action `purchasing.vendor-return-beyond-onhand` granted, the return proceeds; `ReturnedToVendor` marks the line `onHandOverride`, and inventory-svc first records the difference as a count-correction adjustment up (reason `RTV_OVERRIDE`, naming the return) and then draws, so the stock record stays truthful. The received-not-yet-returned ceiling (`PURCHASE_RTV_OVER_RETURN`) has no override.
  6. **The scorecard sees it.** The quality part counts damaged and refused quantities at receipt, without counting a unit again when it is later returned.
- **Out, on purpose:**
  - **A signature capture or a carrier's proof-of-delivery for what we send.** That is the shopper-and-online page; here a signed note is just a document.
  - **Serial numbers at the door.** Serial tracking is inventory-svc's own (`serial_numbers`).
  - **Counting refused goods as stock in a holding location.** A refused delivery is not received; if the business takes the goods in and finds them faulty later, they were damaged, and there is a return for that.
  - **Automatically raising the vendor return.** The person taps it; a credit note still closes the return through the existing flow.
  - **Inspection tasks and sampling plans.** An inspection status exists on the batch; a workflow for it is not this page.
  - **A default shelf-life number.** The platform names none.

## Data and flow

- **Owned by** purchase-svc: `goods_receipt_lines` gain `qty_damaged`, `qty_refused`, `defect_reason`; new `goods_receipt_lots` (line, lot number, expiry, qty, condition); `goods_receipts.delivery_note_ref`; new `goods_receipt_documents` (tenant_id, receipt_id, file name, content type, size, sha256, bytes; append-only); `vendor_return_lines` gain `condition`, `receipt_line_id`, `on_hand_override_reason`. Every table keeps `tenant_id` first in its index.
- **Owned by** product-svc: `tracking` on the variant.
- **Needs from other services:** a variant's tracking from product-svc (REST, cached, fail open); on-hand by material status from inventory-svc (REST, existing `onHand` gains a status filter); the plan's allowance from tenant-svc through `Entitlements`.
- **Events published:** `GoodsReceived` (additive: line `qtyDamaged`, `batchNo`, `expiryDate` now filled; per-lot lines are sent as separate lines of the same variant so inventory-svc's per-line dedupe still holds); `ReturnedToVendor` (additive: line `condition`, `onHandOverride`). Consumers: inventory-svc creates the damaged batch and draws by condition; purchase-svc's own posting and scorecard read the same data. A redelivered event does the same as once.
- **Retryable writes** (Idempotency-Key): the receipt (existing), `return-damaged`, a document upload (also by checksum).
- **New error codes:** `PURCHASE_GRN_SPLIT_INVALID` 400 (damaged plus refused above received); `PURCHASE_GRN_DEFECT_REASON_REQUIRED` 400; `PURCHASE_GRN_LOT_REQUIRED` 400; `PURCHASE_GRN_EXPIRY_REQUIRED` 400; `PURCHASE_GRN_LOTS_MISMATCH` 400 (lots do not add up to the accepted quantity); `PURCHASE_GRN_EXPIRED_ON_ARRIVAL` 422 (an expiry before the receipt day, unless refused); `PURCHASE_GRN_SHELF_LIFE_SHORT` 422 (only when the setting is on); `PURCHASE_GRN_DOCUMENT_TYPE` 415; `PURCHASE_GRN_NOT_FOUND` 404; the plan-limit refusal `Entitlements` already raises (with `MB of purchasing documents`); `PURCHASE_RTV_NO_DAMAGED_STOCK` 409 (nothing damaged to return, or already returned); `PURCHASE_RTV_CONDITION_UNKNOWN` 400; `PURCHASE_RTV_OVERRIDE_NOT_APPROVED` 403.

## Money, time and limits

- **Currency:** the order's own; nothing new.
- **Ledger postings:** the receipt posts Dr Stock / Cr GR/IR for good plus damaged at the order's price, as now; a refusal posts nothing. A damaged return then posts through the existing vendor-return path (debit note, and the credit note when it arrives).
- **Dates:** the receipt's instant, as now; expiry is a date, judged in the store's zone.
- **Plan limits:** the existing `documents.mb.max` allowance now covers **all purchasing documents**, supplier e-invoices and receipt documents, spent from one pool by purchase-svc through `Entitlements.requireBytesWithin`, measured as the write would leave it. tenant-svc's `Plans.CATALOGUE` label changes from "Supplier e-invoice documents (MB)" to "Purchasing documents (MB)"; the key is unchanged, so no plan is edited. Fails open, as limits do. **A document delivered by a receiving network is never refused by this allowance** ([e-invoices-received](e-invoices-received.md) slice 4: kept, flagged `allowance_exceeded`, the owner told); only an upload is. A per-file safety cap is a service config value equal to the e-invoice upload's, not a policy number.

## Constraints

- **Existing tenants:** a receipt with only `qtyReceived` behaves byte-for-byte as today (`ReceiptCompatibilityIT`); no product has a tracking until someone sets one.
- **Append-only:** receipts, receipt lots and documents are never edited; a mistaken receipt is corrected by a return or an adjustment, as now.
- **Tenant from the JWT;** DTOs both ways; the document is never served to another business.
- **Golden rule 1:** product-svc's tracking and inventory-svc's on-hand are read by REST, never joined.

## Open questions

- [x] Is a refused quantity received? → **No: a refused delivery is not booked and leaves the order line open; a damaged one is received and owed, then returned** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Does a damaged unit count toward the order's received quantity? → **Yes, it is owned goods; it counts as received and its return credits the supplier** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Which products need a lot and an expiry? → **Those the business marks LOT or LOT_EXPIRY in the catalogue; none by default** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] What of an expired-on-arrival lot? → **Refused unless the person marks that quantity refused** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Is there a minimum shelf life? → **A business setting, off until set; the platform names no number** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Where does the document allowance come from? → **The existing `documents.mb.max`, one pool for purchasing documents** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Who may return more than the shelf holds? → **A holder of the approvals authority `purchasing.vendor-return-beyond-onhand` (a manager by default once the business sets the rule), with a reason recorded, and the stock record corrected first. This page first asked for a second person's approval; the approvals page settled it as shape A because a dock is waiting and the override only corrects the stock record to what the shelf already shows. The catalogue allows no second-person mode for this key** (industry standard, under the user's standing instruction of 2026-09-30). The mechanism is `intent/approvals.md`; the action key here is `purchasing.vendor-return-beyond-onhand`.

## Acceptance

- [ ] A receipt of 10 with 2 damaged and 1 refused books 9, makes a good batch of 7 and a DAMAGED batch of 2, and leaves the order line owing the 1 — `GoodsReceiptDetailIT.aSplitLineBooksGoodAndDamagedAndOwesTheRefused`
- [ ] A damaged batch is never available, never drawn by a hold or a sale — `DamagedBatchIT.neverAvailableAndNeverDrawn` (inventory-svc)
- [ ] A refused quantity posts nothing and reaches inventory-svc not at all — `GoodsReceiptDetailIT.aRefusalPostsNothing`
- [ ] A request with only `qtyReceived` is exactly as before — `ReceiptCompatibilityIT.anOldRequestReadsAsBefore`
- [ ] Damaged plus refused above received is refused `400 PURCHASE_GRN_SPLIT_INVALID`; a defect reason is required — `GoodsReceiptDetailIT.splitRefusals`
- [ ] A LOT_EXPIRY variant without a lot or an expiry is refused (`PURCHASE_GRN_LOT_REQUIRED`, `PURCHASE_GRN_EXPIRY_REQUIRED`); lots that do not add up `PURCHASE_GRN_LOTS_MISMATCH`; the batch carries the lot and expiry — `GoodsReceiptLotIT.trackedProductsNeedTheirLot`
- [ ] Product-svc unreachable never blocks a receipt — `GoodsReceiptLotIT.anUnreadableTrackingFailsOpen`
- [ ] An expired lot is refused `422 PURCHASE_GRN_EXPIRED_ON_ARRIVAL` unless marked refused; the shelf-life setting off refuses nothing, on refuses the short lot — `GoodsReceiptLotIT.expiredAndShortLots`
- [ ] The delivery note reference and a scan are kept with the receipt; the same file twice is one document; a reused reference warns and is accepted — `GoodsReceiptDocumentIT.theNoteAndItsScan`
- [ ] A document over the plan's allowance is refused with the plan-limit refusal, and an e-invoice and a receipt document spend the same pool — `GoodsReceiptDocumentIT.oneAllowanceForAllPurchasingDocuments`
- [ ] A type that is not PDF, PNG or JPEG is refused `415 PURCHASE_GRN_DOCUMENT_TYPE` — `GoodsReceiptDocumentIT.documentTypes`
- [ ] One tap returns the damaged quantities, once; the debit note is for them and stock leaves the damaged batch — `VendorReturnIT.damagedStockIsReturnedOnceFromTheDamagedBatch`
- [ ] A return line carries its condition; an unknown one is `400 PURCHASE_RTV_CONDITION_UNKNOWN` — `VendorReturnIT.conditionIsKept`
- [ ] A return above on-hand is refused as now; with the approved override the shortfall is first adjusted up with reason `RTV_OVERRIDE`, then drawn; the received-not-returned ceiling still holds — `VendorReturnIT.onHandOverrideNeedsApprovalAndAdjustsFirst`
- [ ] The scorecard's quality counts damaged and refused at receipt and does not count the same units again on return — `SupplierScorecardTest.damagedAtReceiptCountsOnce`
- [ ] Another business's staff of every role cannot read a receipt's documents or return its damaged stock (404, nothing moved) — `GoodsReceiptDocumentIT.anotherBusinessesStaffOfEveryRoleAreShutOut`
- [ ] A business in another country receives, with a lot and an expiry, in its own currency and store zone — `GoodsReceiptLotIT.spansBusinessesInDifferentCountries`
- [ ] The Receiving screen captures the split, lots and the note; widget `receiving_screen_test.dart`; flow guards stay green (k6 `flow-guard-comprehensive`, a new `receiving-detail-flow`)

## Screens

- **Admin shell, Procurement, Receipts tab** (and the store's receiving view): per line, three quantity fields (arrived, damaged, refused), a defect reason picker when either is above 0, and for a tracked product a lot list (lot number, expiry date picker, quantity, condition); a delivery note reference field and an *Attach photo or scan* action showing the remaining allowance; `DELIVERY_NOTE_SEEN` as a banner. After saving, a *Return damaged stock* button on the receipt. The vendor return dialog gains a condition per line and, for a manager, an override reason field that asks for approval. `EmptyState`, `ErrorView`, tokens and widgets from `lib/shared/widgets/`; words not codes.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **A receipt corrected afterwards is [shipping-notices-and-direct-deliveries](shipping-notices-and-direct-deliveries.md) slice 7** (`goods_receipt_adjustments`, signed quantity, reason, `stock.receipt-correct`); this page's receipts stay append-only and its "return or adjustment" line points there.

<!-- Filled while building. -->
