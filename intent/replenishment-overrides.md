# Replenishment overrides: a person's hand on the proposal, the share and the wave

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on depot, cross-dock and wave picking (trf-dc-replenishment, trf-cross-dock, trf-wave-picking-putaway) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, inventory domain: trf-dc-replenishment, trf-cross-dock, trf-wave-picking-putaway |
| **Services** | inventory-svc owns proposals, cross-dock shares, waves and every override of them · notification-svc tells a shop of a changed allocation · the app gets the screens |
| **Builds on** | [depot-dc-replenishment](depot-dc-replenishment.md) (`DcReplenishment.share`, DRAFT transfers, `POST /transfers/{id}/release`, `NetworkService.run`), [cross-docking](cross-docking.md) (`CrossDockService.receive`, `crossdock_expected`, `CROSSDOCK` transfers), [wave-picking-and-directed-putaway](wave-picking-and-directed-putaway.md) (`Waves.plan`, `pick_wave_lines.walk_order`, `pick_wave_allocations`), `transfer_order_lines`, the Depot & shops and Picking & putaway tabs |
| **Built in** | |

## Problem

The three planners are pure functions that a person cannot argue with. A depot proposal shares a short warehouse fairly and the only lever is release or cancel-and-rebuild. A cross-dock delivery is split among shops by the same fairness function, inside the receipt's own transaction, so the manager who knows one shop has a promotion tomorrow learns of the split after the transfers exist. A wave's walk order is fixed when it is built, so a click-and-collect order about to be late cannot be pulled forward. A shop that sees a smaller allocation than it expected has nobody to tell. When a person does work around the planner today (edit-then-cancel-and-recreate), nothing records that it happened.

## Outcome

- **A manager can change the quantities of a DRAFT depot proposal** before releasing it, line by line, with a reason. The proposal keeps what the planner computed beside what the person set, and the transfer carries who changed it.
- **A manager can say, before a delivery is received, how a short cross-dock delivery is shared**: a priority shop, or explicit quantities. When the delivery is received, the shares follow the instruction; without one, the fair share runs as before.
- **A shop can flag that its allocation is short** on a DRAFT or PENDING transfer or a cross-dock share, with a note. The warehouse's manager sees the flag on the transfer and may adjust or answer.
- **A picker or manager can pull an order forward in an OPEN wave**, or move a pick line, and the wave records who moved it and why.
- **Every change is recorded with who, when and why, is shown next to what was computed, and is never silently undone**: a later planner run does not recompute over it.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the warehouse or depot manager (adjusts proposals and shares), the shop manager (flags a short), the picker and the store manager (re-sequence a wave).
- **Channels:** back-office (admin app: Inventory > Depot & shops, Transfers, Picking & putaway).
- **Scope:** a proposal and share by the warehouse, a flag by the shop it concerns, a wave by its store.
- **Roles that can write:** `stock.transfer` at the warehouse (as propose and release need today) to adjust a proposal, set a cross-dock share or re-sequence a wave; any staff assigned to the shop to flag its own allocation. A reason is required for any change beyond the planner's figure.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Adjust a DRAFT proposal** (inventory-svc). `PATCH /admin/inventory/transfers/{id}/lines/{lineId}` while the transfer is DRAFT: a new `requestedQty` (zero removes the line; an increase above the warehouse's available stock for the product is refused, `422 INVENTORY_OVERRIDE_EXCEEDS_STOCK`), `reasonCode`, `note`. The line keeps `proposed_qty` (what the planner said) and `override_by`, `override_at`. A transfer with every line at zero is cancelled by the caller, not silently. Release then works as today.
  2. **The record** (inventory-svc). `replenishment_overrides` (append-only): kind (`PROPOSAL_LINE`, `CROSSDOCK_SHARE`, `WAVE_SEQUENCE`), the subject id, what was computed, what was set, reason, who, when. A change is a new row; the last wins, the history stays.
  3. **A run never recomputes over a person** (inventory-svc). `NetworkService.run` skips a shop that already has an open DRAFT (as `openDrafts` counts them) and says so in its answer with `skipped: [{shopId, reason: OPEN_DRAFT | OVERRIDDEN}]`; a person cancels a DRAFT to have it re-proposed. Nothing is replaced behind an override.
  4. **Set a cross-dock share before receipt** (inventory-svc). `PUT /admin/inventory/crossdock/allocations/{orderId}/shares` names, for an expected delivery, either a `priorityShopId` (that shop's owed line fills first, the remainder shared fairly by `DcReplenishment.share`) or explicit `quantities` per shop and product (which cannot exceed each shop's owed quantity, nor in total the delivery's, checked again at receipt against what really arrived). `CrossDockService.receive` reads the instruction, applies it when present, and records on the created transfer's lines how it was decided (`share_basis`: `FAIR` | `OVERRIDE`) and by whom. An instruction is used once, then marked used.
  5. **A shop flags a short allocation** (inventory-svc, notification-svc). `POST /transfers/{id}/flag` with a note (DRAFT, PENDING or a cross-dock transfer before it ships); adds a `transfer_flags` row (append-only) and publishes `TransferFlagged`; notification-svc tells the warehouse's managers (`TRANSFER_ALLOCATION_FLAGGED`). The warehouse answers with a note or adjusts (slice 1 or 4); the flag is closed by the answer, not deleted.
  6. **Re-sequence a wave** (inventory-svc). `POST /admin/inventory/waves/{id}/resequence` while the wave is OPEN: either `pullForwardOrderId` (that order's lines, and the batches the walk visits for them, move to the front of the walk) or an explicit ordered list of line ids. It rewrites `walk_order` and adds an override row; picks recorded already keep their place; a completed or cancelled wave is `409 INVENTORY_WAVE_NOT_OPEN`. The allocation to batches and the deduction are untouched: only the order the picker walks changes.
  7. **The screens** (Flutter). See Screens.
- **Out, on purpose:**
  - **Overriding which batches an order is allocated from.** That is the picking rule's job (FEFO, FIFO, zone priority); a picker taking a different batch records the pick against it, as today.
  - **Increasing a proposal beyond the warehouse's stock or a share beyond what is owed.** That would ship stock that is not there or shorten another shop.
  - **A second-person approval on an override.** It is a reasoned, recorded and reversible (before release) change by someone already trusted with `stock.transfer`; the value ceiling above which a release itself needs a second person is on [approvals](approvals.md), action key `stock.transfer`.
  - **Automatic prioritisation** (promotion calendars, service levels). The planner stays fair and pure; a person supplies priority.
  - **Overriding the reorder point.** That is management's setting on reorder plans.

## Data and flow

- **Owned by inventory-svc:**
  - `transfer_order_lines` gains `proposed_qty` (NUMERIC(18,3), the planner's figure, null for hand-made transfers), `override_by`, `override_at`, `share_basis`.
  - `replenishment_overrides` (append-only): id, tenant_id, store_id (the warehouse), kind, subject_id, computed_qty, set_qty, reason_code, note, changed_by, changed_at. Index (tenant_id, kind, subject_id, changed_at).
  - `crossdock_share_instructions`: order_id, priority_shop_id, quantities (per shop/variant rows in a child table), set_by, set_at, used_at.
  - `transfer_flags` (append-only): transfer_id, shop store id, note, flagged_by, flagged_at, answered_note, answered_by, answered_at.
  - `pick_wave_lines.walk_order` is rewritten; each rewrite is an override row.
  - Seeded reason codes: `PROMOTION`, `STOCKOUT_AT_SHOP`, `CUSTOMER_PROMISE`, `LATE_ORDER`, `OTHER`.
- **Needs from other services:** none new (cross-dock allocations already arrive from purchase-svc; shop names from `TenantProfiles.Stores`).
- **Events published:** `TransferFlagged` (`storeql.inventory.transfer-flagged`) → notification-svc. `TransferOrder*` events are unchanged. No event for wave sequence (internal); `WavePicked` unchanged.
- **Retryable writes (Idempotency-Key):** adjust a line, set a share, resequence, flag.
- **New error codes:** `422 INVENTORY_OVERRIDE_EXCEEDS_STOCK`, `422 INVENTORY_SHARE_EXCEEDS_OWED`, `409 INVENTORY_TRANSFER_NOT_DRAFT` (existing, reused), `409 INVENTORY_WAVE_NOT_OPEN`, `404 INVENTORY_TRANSFER_NOT_FOUND`, `400 INVENTORY_OVERRIDE_REASON_REQUIRED`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** none directly; the transfer's value is read at cost as today.
- **Ledger postings:** none (a transfer between a business's own stores posts nothing until a discrepancy, see [transfer-discrepancies](transfer-discrepancies.md)).
- **Dates:** UTC instants recorded; screens show the store's own day.
- **Plan limits:** none.

## Constraints

- The planners stay pure and stateless: `DcReplenishment` and `Waves.plan` gain no person-dependent input beyond the instruction row the caller brings.
- Never silent: an override is visible on the record, shown beside the computed figure in every screen and export.
- Append-only: overrides, flags and movements. Undoing an override is a further override back to any figure (the computed one included).
- Cross-dock's receipt-level transaction stays one transaction; the instruction is read inside it, so a share is never half-applied.

## Already there

- Depot proposals are DRAFT transfers released by a person (`NetworkService.release`, `stock.transfer` at the source, `PermissionsIT.depotTransfersAreGated`; `NetworkIT`), serving relationships and buy-direct exceptions are management-only and tested (`NetworkIT.theNetworkIsWrittenByStaffWhoMayAndOnlyInTheirOwnBusiness`).
- Cross-dock creates PENDING `CROSSDOCK` transfers at receipt with `DcReplenishment.share` for shortfall (`CrossDockService.receive`, `CrossDockIT`).
- Waves store `walk_order` per line and can be completed or cancelled (`WaveRepository`, `WaveIT`).
- Not there: any change of quantities, shares or walk order, any flag from a shop, any record of overrides.

## Open questions

- [x] Who may adjust a proposal? → **whoever may propose and release it: `stock.transfer` at the warehouse; a reason is required** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] May an override exceed the stock? → **no; it is refused** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What happens to an override when the planner runs again? → **it is left alone; the run says it skipped the shop** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does an override need a second person? → **no; it is recorded and reversible before release** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What can a shop do about a short allocation? → **flag it with a note, and be answered on the record** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Changing a DRAFT line records the planner's figure, the new one, who and why; releasing ships the new one — `ReplenishmentOverrideIT.aManagerAdjustsADraftLine`
- [ ] More than the warehouse holds is `422 INVENTORY_OVERRIDE_EXCEEDS_STOCK`; a missing reason `400 INVENTORY_OVERRIDE_REASON_REQUIRED`; a released transfer `409 INVENTORY_TRANSFER_NOT_DRAFT` — same test class
- [ ] A run skips a shop with an open DRAFT and says why — `NetworkIT.aRunNeverRecomputesOverAnOverride`
- [ ] A priority shop fills first at a short cross-dock receipt; explicit quantities are applied; no instruction runs the fair share as before — `CrossDockIT.aShareInstructionIsFollowedAtReceipt`, `DcReplenishmentTest` (unchanged)
- [ ] A share above what a shop is owed is refused `422 INVENTORY_SHARE_EXCEEDS_OWED` — `CrossDockIT`
- [ ] A shop's flag reaches the warehouse manager once — `TransferFlagIT`, `TransferFlaggedHandlerTest` (notification-svc)
- [ ] Pulling an order forward rewrites the walk, keeps picks recorded, records who, and a completed wave is refused `409 INVENTORY_WAVE_NOT_OPEN` — `WaveIT.aWaveCanBeResequenced`
- [ ] A cashier and a keeper of another store are refused (`PERMISSION_DENIED`, `STORE_ACCESS_DENIED`); another business's staff of every role get 404 and nothing changes — `ReplenishmentOverrideIT.anotherBusinessCannotOverride`
- [ ] Widget: the proposal shows computed beside set — `depot_overrides_test`
- [ ] k6 `depot-flow`: propose, adjust, release, ship, see the recorded override

## Screens

- **Depot & shops** (existing tab): a DRAFT proposal's lines become editable: a quantity field that shows the planner's figure ("Proposed 40") and a reason dropdown appearing when the figure changes; save sends the patch with an Idempotency-Key; a line at zero says it will be removed.
- **Transfers** detail: "Allocation looks short" for the receiving shop's staff, with a note; the warehouse sees the flag with Answer and Adjust actions.
- **Depot & shops > Deliveries expected**: for a warehouse order with allocations, "How to share a short delivery" (Fair, Priority to a shop, or set quantities) with a table of shops and owed quantities.
- **Picking & putaway** (existing tab): an OPEN wave gets "Do this order first" per order and drag or move-up per line; the history of changes is shown under the wave.
- Everything reads in words (reason names, shop names), money and quantities as elsewhere; on a phone the tables scroll (`ScrollableTable`) and actions use `AdaptiveActions`.

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet.
