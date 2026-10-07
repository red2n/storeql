# Fulfilment overrides: re-route, re-pick, a wave closed short, cancelling a picked order, the unpaid-order limit

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | new: the flow catalogue's open findings, wave 2 — online/split-fulfilment (manual override), online/picking-and-packing (re-pick, short wave), returns/void-order-cancel (cancel past a cut-off), online/unpaid-order-sweeper-and-cancellation (TTL setting) |
| **Services** | order-svc owns re-routing, the forced cancel and the unpaid-order limit · inventory-svc owns pick exceptions, re-picks, the short-wave gate and what a cancelled order does to an open wave · payment-svc, purchase-svc, reporting-svc act on existing events unchanged · the app's Fulfilment and Waves screens and business settings |
| **Builds on** | `OrderRouter`, pure `Routing`, `order_groups`; `OrderService.cancelOrder` (`ORDER_PARTLY_FULFILLED`, `ORDER_CANNOT_CANCEL`), `cancelOwnOrder` (wave 1), `sweepExpiredPendingOrders(ttlHours, batchLimit)` and `PendingOrderSweeper` (`storeql.order.pending-sweeper.ttl-hours`, 24), `OrderRepository.adjustLine`, `createReturn`, `order_handovers`; inventory-svc `pick_waves` / `pick_wave_lines` / `pick_wave_allocations` / `wave_picked_lines`, `WaveService`, `WaveRepository.complete`, `putaway_tasks`, `OrderEventHandler` (`OrderCancelled` releases holds); `intent/order-orchestration-and-split-fulfilment.md`, `intent/wave-picking-and-directed-putaway.md`, `intent/substitutions-for-out-of-stock-online-lines.md` |
| **Built in** | |

## Problem

- **The router cannot be overruled.** It splits an order across shops, or (when stock is unreadable) places it at the area store, and nobody can move it: a shop that is about to close, is out of the thing the system believed it had, or a customer who should be served by a particular shop, all wait for a cancel and a re-order.
- **A wrong pick can only be recorded as a smaller pick.** There is no reason, no way to say "the picker took the wrong batch", and no way to redo a line before the wave completes.
- **A wave that comes back badly short completes without anyone looking.** Only "the wave is OPEN" is checked.
- **Cancelling stops dead once picking starts.** A `PARTIALLY_FULFILLED` order is a hard `409 ORDER_PARTLY_FULFILLED`; and the open wave still lists a cancelled order's allocation as a line to walk. **Correction (30 Sep evening, from wave 2 round 1): the harm this page first named, a wave deducting stock for an order that no longer exists, is not real.** Completing the wave never draws for an order that left while it was open; `WaveIT.anOrderThatLeftWhileTheWaveWasOpenIsNotDrawn` proves it. What remains is only that the picker still sees the line (slice 1).
- **The unpaid-order time limit is invisible.** Stranded pay-later orders are cancelled after a platform-wide 24 hours the owner cannot see or change.

## Outcome

- A manager can move an order that has not been picked to another shop, with a reason, and the stock hold moves with it, all or nothing.
- A picker can say why a line came up short or wrong; a supervisor can send a line back to be picked again before the wave completes.
- A wave that closes short by more than the business allows waits for a person with the right to close it.
- A manager can cancel an order that is being picked or is picked and not yet handed over: what was picked goes back to the shelf, the shopper is refunded for exactly what they did not get, and every step is recorded.
- The owner sees and changes how long an unpaid order is held; the shopper sees when theirs will lapse.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **store manager** and **owner** (re-route, forced cancel, limits), the **picker / storekeeper** (pick reasons), the **supervisor** (re-pick, close short), the **shopper** (only affected: told when an unpaid order lapses; refunded).
- **Channels:** back-office Fulfilment and Waves screens; storefront My Orders for the deadline.
- **Scope:** per order at its store; re-route needs access to both stores; waves are per store; the limit is per business.
- **Roles that can write:** management (OWNER, MANAGER) with store access for re-route and settings; the `sales.void` permission for a forced cancel, and above the caller's ceiling the approvals action key **`sales.cancel-in-pick`** (a step-up at the desk, [approvals](approvals.md)); STOREKEEPER and above for pick reasons and re-picks (`stock.transfer`, as waves already need); closing a wave beyond the tolerance needs the approvals page's action key **`stock.wave-short-close`** (proposed name; that page owns the mechanism and the final key).
- **Sandbox tenant:** behaves the same.

## Scope

- **Already there (verified 2026-09-30):**
  - A shopper cancels their own PENDING order with nothing paid (`OrderService.cancelOwnOrder`, `ShopperCancelIT`, the Fulfilment screen's staff cancel unchanged; the app button `orders_screen.dart`). The shared-filter opening for the shopper's POST was made by wave 1 (`AdminAuthorizationFilter`, id-shaped `POST /orders/{uuid}/cancel`, with the gateway's `JwtAuthFilter`; in the working tree, not yet committed): built, not outstanding.
  - Staff cancel PENDING, AWAITING_PRICE and CONFIRMED orders, and a cancel of a paid order needs `sales.void` (`CancelVoidGuardsIT.ownerCancelsAPendingOrder`, `cancelWithNoBody`, `aFulfilledOrderCannotBeCancelled`).
  - The unpaid sweeper: `PendingOrderSweeper` runs `sweepExpiredPendingOrders(ttlHours, batchLimit)` with `storeql.order.pending-sweeper.ttl-hours` (default 24, on in compose and k8s).
  - Closing a line short or substituting it, with the refund pro rata (`adjustLine`, `SubstitutionsIT`) — the building block for slice 5.
  - Waves record a lower picked quantity; `complete` only checks OPEN (`WaveService`).
- **In (slices in build order):**
  1. **A cancelled order's line is marked on the wave read (inventory-svc, small; not a defect).** *Replaces the first draft of this slice* (its text: "First a failing test, then the fix: `OrderCancelled` (and a re-route) drop the order's allocations from any OPEN wave, and `complete` skips any allocation whose order is not still awaiting ... a `putaway_tasks` row is raised", kept here as history). The deduction logic and the putaway task are **dropped**: the existing behaviour already never draws for a cancelled order. What is built is only a marker: the wave's read says, for a line whose order is no longer awaiting, `cancelled: true`, so the picker leaves it. Nothing is deducted, restated, closed or raised.
  2. **The unpaid-order limit as a business setting (order-svc, app).** `order_settings.pending_limit_hours` (null = the platform default from config); the sweeper reads it per business; an order's response and the shopper's My Orders show `expiresAt` for a PENDING order.
  3. **Pick exceptions and re-pick (inventory-svc, app).** A fixed reason catalogue; a line that was picked short or from a different batch than directed needs a reason; a supervisor can reopen a picked line before completion.
  4. **A wave closed well short (inventory-svc, app).** A per-store tolerance setting; beyond it, complete waits for approval.
  5. **Re-route an order (order-svc, inventory-svc, app).** Move an unpicked online DELIVERY order (or part of a split) to another shop.
  6. **Cancel an order that is being picked or is picked and not handed over (order-svc, inventory-svc, app).** A forced cancel that composes what already exists.
- **Out, on purpose:**
  - **Re-merging a split, or splitting a whole order after placement.** A manager re-routes each part; changing how many parcels there are is a new order (cancel and re-place). Documented in the router's own Out list ("re-routing after payment is its own flow"): this page is that flow, but only moves a part whole.
  - **Re-routing a PICKUP order.** The shopper chose the shop; the manager asks them to cancel and choose again.
  - **Re-routing to a warehouse.** A warehouse never fills a shopper's order (orchestration Decisions).
  - **Re-routing an order with any picked line or a handover.** Stock has left the shelf; use slice 6 or a transfer.
  - **Cancelling after handover.** That is a return (shopper-returns or the till); `409 ORDER_ALREADY_HANDED_OVER`.
  - **A new refund path.** The forced cancel is short-close plus return, both existing, so payment-svc, customer-svc and purchase-svc change nothing.
  - **A number for "significantly short".** The business sets its tolerance; unset means no gate.
  - **Alerts.** Metrics named for the exception-alerts page: `order_reroutes.count`, `order_force_cancels.count`, `wave_picks.short_share` (per picker), `substitutions.count`.
  - **A second approval mechanism.** The action keys `sales.cancel-in-pick` and `stock.wave-short-close` are named here and designed on `intent/approvals.md`.

## Data and flow

- **Owned by order-svc:**
  - `order_settings` (one per business, **the one row for every wait a business sets on an order**): `pending_limit_hours` (null = default; a whole number of at least 1), changed by and at; [unit-pricing-and-listing-rules](unit-pricing-and-listing-rules.md) slice 5 adds `price_wait_flag_minutes` and `price_wait_cancel_minutes` to it (the wait for a price on a till order), read by its own sweeper; the sweeper's query takes each business's own limit (same database, no join to another service).
  - `order_reroutes` (append-only): tenant, order, from store, to store, reason (required text), actor, at, `idempotency_key`. `orders.store_id` is the one column that changes, on the same transaction as the row and the event.
  - `order_force_cancels` (append-only): tenant, order, reason, actor, approver (from the approvals mechanism), short-closed lines, the return id (when goods were picked), refund amount, at.
  - `orders.expires_at` is derived at read time (created plus the limit) for PENDING orders, not stored.
- **Owned by inventory-svc:**
  - `pick_wave_lines` gains `exception_code` and `exception_note`, and `actual_batch_id` (the batch really scanned when it differs).
  - `pick_events` (append-only): tenant, wave, line, picked qty, reason, actor, at; the line's `picked_qty` stays the current reading while every reading is kept; `superseded_by` links a reopened reading.
  - `wave_settings` (per store, or business-wide when the store is null): `short_tolerance_pct` (null = no gate); `pick_waves` gains `short_approved_by`.
  - Existing `putaway_tasks` gets a `source` of `CANCELLED_PICK`, raised **only by slice 6's forced cancel** for a tote already picked (slice 1 raises none).
- **Reasons (a platform catalogue, words a picker understands, not policy numbers):** for short: NOT_FOUND, DAMAGED, EXPIRED_OR_TOO_SHORT_DATED, COUNT_WRONG, OTHER (with a note); for a different batch: WRONG_BATCH_SCANNED, BATCH_MIXED, OTHER. A line picked short or from another batch without a reason is refused.
- **Needs from other services:** re-route: tenant-svc through `TenantProfiles.Stores` (type STORE only, trading, coordinates); inventory-svc REST for the new shop's stock hold (existing reserve) and the release event for the old one. No joins.
- **The order of a re-route (`POST /orders/{id}/reroute {storeId, reason}` with `Idempotency-Key`):** verify the order (ONLINE, DELIVERY, status PENDING or CONFIRMED, not part-fulfilled, no picked line, no handover, the target a trading STORE, not already the order's store, not another part of the same group, the caller may act at both stores); if the order holds a delivery slot, re-take the same local date and times at the new store's window (locked `FOR UPDATE` as checkout does) or refuse (a slot fee already on the order, [workforce-rules](workforce-rules.md) slice 10, stands whatever the new store's window charges: a re-route never re-prices); **place the new hold first** (all-or-nothing, existing reserve); on one order-svc transaction change `store_id`, write the row and the outbox `OrderRerouted`; inventory-svc, on the event, releases the old hold and drops the order's allocation from any OPEN wave at the old store (idempotent by event id). If the new hold fails, nothing changed. If the transaction fails after the hold was placed, order-svc releases the new hold (compensation) and the retry starts clean.
- **The forced cancel (`POST /orders/{id}/force-cancel {reason}`, `Idempotency-Key`, permission `sales.void`, approvals key `sales.cancel-in-pick`):** it composes, on order-svc's own logic and in one transaction: (a) every outstanding line is short-closed with the existing `adjustLine` (refund pro rata, `OrderLineShortClosed`, inventory-svc gives the hold back and shortens the waiting line); (b) for every line that was picked and fulfilled but not handed over, a return through the same code as `createReturn` (condition SEALED by default, changeable per line by the staff member who has the goods in front of them; window and ceiling do not apply: a cancellation is not the shopper's return, and the actor holds `sales.void`), refunded to the original tender; (c) the order ends in the terminal state the returns already give (fully refunded); `order_force_cancels` and the audit trail name the actor, approver, reason and every step. An order with nothing picked and no wave lines simply uses the ordinary cancel; one with picks in an OPEN wave uses slice 1's release (no money moves for stock that never left the ledger, and a putaway task is raised for the tote). Refused after any handover (`409 ORDER_ALREADY_HANDED_OVER`), for a till sale, a voided or cancelled order.
- **Events published:** `OrderRerouted` (order-svc, `storeql.order.order-rerouted`: tenant, order, from store, to store, group part, reason code; inventory-svc moves holds and wave allocations; reporting-svc updates the order's store for forward figures; notification-svc: nothing, the shopper's delivery is unchanged except a re-taken slot, which is told by the existing order-updated notice only when the window changed). `OrderForceCancelled` (order-svc, `storeql.order.order-force-cancelled`: tenant, order, reason, actor, return id, refund amount; reporting-svc, notification-svc `ORDER_CANCELLED_BY_SHOP` type through the shopper's channels for CHANGES). The short-closes and the return announce their own existing events. `WavePickException` is not an event (a row).
- **Retryable writes (Idempotency-Key):** re-route, forced cancel, re-pick, and the complete-with-approval (existing wave complete already has a key per wave).
- **Endpoints:** order-svc `POST /orders/{id}/reroute`, `POST /orders/{id}/force-cancel`, `GET/PUT /admin/orders/settings/pending-limit`, `GET /orders/{id}/reroutes`; inventory-svc `POST /admin/inventory/waves/{id}/picks` (each line may carry `reason`, `note`, `actualBatchId`), `POST /admin/inventory/waves/{id}/lines/{lineId}/reopen {reason}`, `GET/PUT /admin/inventory/waves/settings`; wave `complete` answers `409 WAVE_SHORT_NEEDS_APPROVAL` naming the shortfall and the tolerance until approved.
- **New error codes:** order-svc `409 ORDER_REROUTE_NOT_ALLOWED` (details: not online delivery, picked, handed over, status), `409 ORDER_REROUTE_TARGET_INVALID` (a warehouse, not trading, same store, another part of the same group, no access), `409 ORDER_REROUTE_NO_STOCK` (nothing changed), `409 ORDER_REROUTE_SLOT_UNAVAILABLE`; `409 ORDER_ALREADY_HANDED_OVER` (existing code reused), `409 ORDER_FORCE_CANCEL_NOT_ALLOWED` (till sale, voided, cancelled); `400 ORDER_PENDING_LIMIT_INVALID`; inventory-svc `400 WAVE_PICK_REASON_REQUIRED`, `400 WAVE_PICK_REASON_UNKNOWN`, `409 WAVE_PICK_BATCH_INVALID` (another product, another store, off-sale or recalled), `409 WAVE_LINE_NOT_REOPENABLE` (wave completed or cancelled), `409 WAVE_SHORT_NEEDS_APPROVAL`, `400 WAVE_TOLERANCE_INVALID`.

## Money, time and limits

- **Currency:** refunds are in the order's currency by the existing paths; nothing is priced here.
- **Ledger postings:** none new: the short-closes and the return post as they do today (`OrderLineShortClosed` refunds, return-controls' returns). A re-route posts nothing.
- **Dates:** the limit is hours from the order's creation instant (UTC); `expiresAt` shows in the shopper's local time in the app only; a re-taken slot keeps its local date and times in the new store's own zone (never a zone assumed).
- **Plan limits:** none.

## Constraints

- **Golden rules:** 1 (holds by REST and events, stores through `TenantProfiles`); 3 (tenant from the JWT, store access first); 6/7 (every change announced through the outbox, consumers idempotent by event id; a replayed `OrderRerouted` moves a hold once); 8 (re-routes, force-cancels, pick events append-only); 11 (keys above); 15.
- **Consistency:** money and stock never disagree because the forced cancel uses only the two writes that already keep them consistent; the re-route places before it releases.
- **Location-neutral:** no country, currency or time zone; a delivery slot is re-taken by the store's own zone; the tolerance and limit are the business's own numbers.
- **Existing tenants:** the limit falls back to the platform's configured 24 hours (unchanged); no tolerance set means no gate; reasons are required only on a *new* short or different-batch pick, so waves already open complete as they would have; nothing already recorded is rewritten.
- **Flow guards:** `flow-guard-comprehensive` and `flow-guard-runtime` must stay green (orders, stock, waves).

## Open questions

- [x] **Can a cancel go past the first pick?** Recommended: yes, for a holder of `sales.void`, as short-close plus return of what was picked; never after handover. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Does a re-route move stock and money?** Recommended: stock hold only (new hold first, old released after); no money moves. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Who may re-route?** Recommended: management with access to both shops; every re-route recorded with a reason. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **What is a "significant" shortfall?** Recommended: the business's own share of planned units per store, off until set. → **setting, off** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Re-pick after completion?** Recommended: no; after completion a wrong pick is corrected by a stock adjustment or a return. → **before completion only** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **The unpaid-order limit's range?** Recommended: any whole number of hours from 1; unset uses the platform's default; the shopper sees the deadline. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A cancelled order's line on an OPEN wave reads `cancelled: true` and completing the wave deducts nothing for it (already so; the marker is new). — inventory-svc `WaveIT.aCancelledOrdersLineIsMarked`; existing `WaveIT.anOrderThatLeftWhileTheWaveWasOpenIsNotDrawn` (the old lines `WaveCancelledOrderIT.*` and the putaway task are withdrawn)
- [x] The business's own limit cancels an unpaid order after its hours and not before; unset uses the platform default; a value below 1 is `400 ORDER_PENDING_LIMIT_INVALID`; a PENDING order shows `expiresAt`. — order-svc `OrderWaitLimitsIT.ownLimitApplies`, `.offUntilSetAndValidated`, `.expiresAtIsShown`; `OrderSettingsTest.*`
- [x] Another business's staff cannot read or set our limit; a shopper cannot set it. — `OrderWaitLimitsIT.limitsAreNotShared`, `.whoMayReadAndChange` (a shopper, a cashier and a keeper are `403`; a manager held to stores may read, `403 BUSINESS_WIDE_ONLY` to set)
- [ ] A line picked short or from another batch without a reason is refused; with a reason it is recorded and kept as history; a different batch of another product, store or an off-sale status is refused `409 WAVE_PICK_BATCH_INVALID`. — inventory-svc `PickExceptionIT.reasonIsRequired`, `readingsAreKept`, `wrongBatchIsChecked`
- [ ] A supervisor reopens a picked line before completion; after completion `409 WAVE_LINE_NOT_REOPENABLE`; a cashier is refused. — `PickExceptionIT.reopenBeforeCompletionOnly`, `permissionsAreEnforced`
- [ ] A wave closed short beyond the store's tolerance answers `409 WAVE_SHORT_NEEDS_APPROVAL` until approved (`stock.wave-short-close`), naming who approved; within it, or with no tolerance, it completes as today. — `WaveShortIT.gateOnlyWhenSet`, `approvalCompletes`, `withinToleranceCompletes`; pure `WaveShortfallTest`
- [ ] A re-route of an unpicked online delivery order moves its hold to the new shop, all or nothing, and the old hold is released once even if the event replays; a warehouse, the same shop, a shop already holding another part of the group, a picked or handed-over order, a PICKUP order are each refused with their codes; a slot the new shop cannot offer is `409 ORDER_REROUTE_SLOT_UNAVAILABLE`; no stock there is `409 ORDER_REROUTE_NO_STOCK` with nothing changed. — order-svc `RerouteIT.movesHoldAllOrNothing`, `refusalsAreCoded`, `slotIsRetaken`, `aRetryAnswersWithTheFirst`; inventory-svc `RerouteHoldIT.oldHoldReleasedOnce`
- [ ] A re-route's actor is management with access to both stores and a reason; every re-route is on the append-only log and the audit trail. — `RerouteIT.permissionsAndTrail`
- [ ] Another business's staff of every role, another store's held manager and a shopper cannot re-route or force-cancel our order (404/403), and nothing moves. — `RerouteIT.otherBusinessFindsNothing`, `ForceCancelIT.otherBusinessFindsNothing`; consumer `RerouteHoldIT.anotherBusinessesEventTouchesNothing`
- [ ] A forced cancel of a part-picked order short-closes what is outstanding and returns what was picked, refunding exactly what was paid for the whole (never more), once per key; `sales.void` is required; after handover `409 ORDER_ALREADY_HANDED_OVER`; a till sale or an already cancelled order `409 ORDER_FORCE_CANCEL_NOT_ALLOWED`. — `ForceCancelIT.shortClosesAndReturnsAndRefundsOnce`, `needsSalesVoid`, `refusedAfterHandover`, `aRetryAnswersWithTheFirst`; payment-svc `RefundCapIT` (existing) proves the cap
- [ ] The picked goods return to the shelf: a return's stock is restocked by condition; picks inside an OPEN wave never left the ledger and create a putaway task instead. — `ForceCancelIT.pickedGoodsComeBack`; inventory-svc `ReturnDispositionIT` (existing)
- [ ] The Fulfilment and Waves screens and the limit's settings card. — widget tests `fulfilment_reroute_test.dart`, `fulfilment_force_cancel_test.dart`, `wave_pick_reason_test.dart`, `wave_close_short_test.dart`, `pending_limit_card_test.dart`
- [ ] End to end: k6 `fulfilment-overrides-flow`; `flow-guard-comprehensive` and `flow-guard-runtime` green.

## Decisions

<!-- Filled while building. -->
- (Recorded now) **Slice 6 composes existing writes rather than inventing a cancel of picked goods.** A second refund path for a fulfilled-but-not-handed-over order would need payment-svc to know what was fulfilled, and would risk refunding a picked line twice (once by `OrderCancelled`, once by a return). Short-close plus return keeps one refund path and one cap. The order therefore ends in the returned state, not CANCELLED, and the forced-cancel record and audit line say why.
- (Recorded now) **This page fills the router's "re-routing after payment is its own flow" exclusion** in `intent/order-orchestration-and-split-fulfilment.md`; that text stays, and the router itself is unchanged: it is still automatic and never refuses a sale.
- (2026-09-30, reconciled) **The action keys are the approvals page's.** This page proposed `wave.close-short` and used `sales.void` as the forced-cancel key; the catalogue names them `stock.wave-short-close` and `sales.cancel-in-pick` (`sales.void` stays the *permission* and the key of a void of a till sale). Nothing else changes.
- (2026-09-30, built) **The unpaid limit is counted from the order's last change** (`updated_at`), as the sweeper always did, not from creation as the slice first said: an order a manager prices, or a part payment, holds it afresh, and `expiresAt` on the order answer is that same instant plus the limit (absent once the order is no longer PENDING). Counting from creation would cancel an order the moment it was priced after a long wait for a price.

## Screens

- **Admin, Fulfilment, order detail:** *Re-route to another shop* (management, only where allowed: shop picker with stock shown, reason) and *Cancel this order* becoming *Cancel (already picked)* for `sales.void` holders with a summary of what will be closed, returned and refunded before confirming.
- **Admin, Waves:** the pick dialog asks a reason (and, for a different batch, which) whenever the quantity is short or the batch differs; a picked line has *Send back to pick*; completing a short wave shows the shortfall against the tolerance and, when needed, *Ask for approval*; a cancelled order's line reads "Cancelled: leave it".
- **Admin, business settings:** an *Unpaid orders* card (hours held, default shown) and a *Waves* card (short tolerance).
- **Storefront, My Orders:** a PENDING order says "We will hold this until <local time>"; a forced cancel arrives as a notice through the shopper's channels.
