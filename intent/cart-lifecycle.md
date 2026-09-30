# Cart lifecycle: abandonment, stock advice, a promotion's last use, prices the client cannot set

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | new: the flow catalogue's open findings, wave 2 — online/cart (SHOP-40, cart expiry), online/checkout (client price), pricing promo gap 2 (product-pricing note item 8) |
| **Services** | cart-svc owns cart settings, abandonment and the advice shown on a cart · pricing-svc owns promotion usage and reservations · order-svc reserves at checkout and stops trusting client prices · inventory-svc answers availability · notification-svc sends the reminder · the app shows advice and the settings |
| **Builds on** | cart-svc `carts` (`ACTIVE`/`CHECKED_OUT`/`ABANDONED`, `updated_at`, one active per shopper or session), `cart_staff_actions` (wave 1), `CartService.mergeFoldsAndAbandons`; pricing-svc `promotion_redemptions` (append-only ledger), `findExhaustedPromotions`, `recordRedemption` (wave 1), `POST /prices/redemptions`; order-svc `PricingClient.recordRedemptionsQuietly`, `storeql.order.pricing.enforce`, `PendingOrderSweeper`; inventory-svc availability |
| **Built in** | |

## Problem

Four separate leaks, one flow:

- **Carts never end.** Nothing marks a cart as abandoned except a merge, so old guest carts sit forever and the business has no idea how many baskets were left.
- **The first stock check is at checkout.** A shopper can put 50 of an item with 2 in stock in a cart and learns only when paying. (By design the cart reserves nothing; V1 says so. The design is right; the silence is not.)
- **A promotion with a cap can go one over.** Wave 1 proved it: two checkouts that quote before either records both get the discount, because the redemption is recorded after payment and deliberately never fails the order.
- **The client's price can be trusted.** The cart stores the unit price the client sent, and order-svc trusts a client price whenever `storeql.order.pricing.enforce` is off (a dev switch; the default and every deployment file is on).

## Outcome

- A business sets how long an idle basket lives. After that a sweep marks it abandoned, the business can count them, a signed-in shopper who returns finds their basket back, and old ones are deleted when the business says.
- A shopper adding to the cart sees advice: "only 3 left", "out of stock here", from the store's real availability. The advice never blocks and never reserves; checkout still decides.
- A promotion or coupon with a cap can never be exceeded, whatever the timing: its last use is reserved at checkout, kept while the order stands, and released if the order is cancelled, voided or times out. A shopper who loses the race is told the offer just ran out and sees the new total before paying, never silently charged more.
- The cart's price is shown as advice too: it is what the shop would charge now, from pricing-svc, and no price the client sends is stored or charged, on any deployment that faces the internet.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the shopper (advice, promotion outcome), the owner or manager (abandonment settings, promotion caps), platform operations (the enforcement guard).
- **Channels:** ONLINE storefront. POS is unaffected (a till sale does not use a cart; the promotion reservation applies to any order that redeems a capped promotion, POS included).
- **Scope:** per business; the advice is per the store the cart names.
- **Roles that can write:** OWNER and MANAGER for the settings; shoppers and guests as today for the cart; staff assisted-shopping stays traced (wave 1).
- **Sandbox tenant:** behaves the same, including reservations; the reminder is `SUPPRESSED` there as all sandbox notices are.

## Scope

- **Already there (verified 2026-09-30):**
  - Assisted shopping is traced: append-only `cart_staff_actions` on the change's own transaction (`CartAccessTest`, `CartServiceTest.staffAddingToAShoppersCartLeavesATraceNamingWhoAndInWhatRole`, `CartCasesIT.assistedShoppingIsTraced`); SHOP-43 is closed.
  - The cart-side cases (guest session, merge, closed store, suspended tenant, isolation): `CartCasesIT`.
  - Promotion limits are counted from the ledger at quote and `recordRedemption` is once per promotion and order and refuses another business's promotion (`PromotionLimitsIT`).
  - `storeql.order.pricing.enforce` defaults to true in code, `microprofile-config.properties`, docker-compose and k8s; the client's price is ignored when it is on (`DisplayCurrencyIT.aClientPriceIsNotCharged`).
- **In (slices in build order):**
  1. **Server-priced carts (cart-svc, order-svc).** The cart ignores any `unitPrice` on add or change; a line's price is asked from pricing-svc's quote for the store when the cart is read (a "price now" shown as advice; pricing unreadable shows no price, never a stale one) and never stored as authority. Order-svc's `enforce=false` is honoured only in a `dev` or `test` profile; anywhere else the readiness probe's `config` check reports DOWN with the reason and the service logs it at startup. (Local docker-compose keeps its documented dev override.)
  2. **Stock advice on the cart (cart-svc, inventory-svc, app).** Each line answers `availability`: `IN_STOCK`, `LOW`, `OUT`, `UNKNOWN`, and for LOW a count when the business allows counts. A `client.InventoryAvailability` read (timeout, retry, breaker) with **fail-open**: unreadable is UNKNOWN. Never refuses an add, never reserves, never changes a quantity.
  3. **A capped promotion's last use, reserved (pricing-svc, order-svc).** `promotion_reservations`; reserve on the order's own placement, consume when the redemption is recorded, release on cancel, void, expiry or a failed placement.
  4. **Abandonment (cart-svc, app).** `cart_settings`, the sweep, the `CartAbandoned` event, reopening, purge.
  5. **The reminder (notification-svc, cart-svc).** A single reminder per abandonment to a signed-in shopper, only through the marketing consent gate (`allowance`), off until the business sets a delay.
- **Out, on purpose:**
  - **Reserving stock in the cart.** Stock is never reserved in a cart (V1 says so, and it stays): a reservation for every browsing session would starve real orders. The hold is placed at checkout.
  - **Blocking or capping the add.** The advice can be a step behind a sale that just happened, and one store's zero may be another's stock (split fulfilment). The refusal stays at checkout (`INVENTORY_INSUFFICIENT_STOCK`). Catalogue case SHOP-40's "refused or capped" is answered by advice-not-refusal; recorded under Decisions.
  - **Re-quoting or merging promotions across carts, loyalty-points in the cart.** Not asked.
  - **A guest reminder** (no address is held for a guest; a reminder needs a login).
  - **Cart audit of a shopper's own changes.** Only staff touching someone else's cart is traced (done).
  - **Rewriting past redemptions.** Redemptions already on the ledger keep what they recorded; a cap already exceeded stays exceeded.

## Data and flow

- **Owned by cart-svc:**
  - `cart_settings` (one per business): `abandon_after_hours` (null = off, carts never abandoned), `delete_abandoned_after_days` (null = keep), `reminder_after_hours` (null = off, must be at least the abandon hours), `show_stock_counts` (default false: words only), `low_stock_at` (null = no LOW state, only IN_STOCK / OUT), changed-by and at.
  - `carts.abandoned_at` (instant), `carts.reminded_at`. The status ABANDONED already exists.
  - A sweep (`CartSweeper`, like `PendingOrderSweeper`) per tenant that has a setting: ACTIVE carts with at least one item and `updated_at` older than the period become ABANDONED, in batches; empty carts are dropped immediately by the delete rule only. A signed-in shopper's next `GET /cart` reopens their latest abandoned cart (status back to ACTIVE, `abandoned_at` cleared) when they have no active one; the one-active-cart unique index (V3) is the guard. A guest's abandoned cart is reachable only by its session token as today.
- **Owned by pricing-svc:** `promotion_reservations`: id, tenant, promotion, order, customer (nullable for a guest), status HELD / CONSUMED / RELEASED, created, decided. Unique on (tenant, promotion, order). A reserve takes the promotion row `FOR UPDATE`, counts consumed redemptions plus HELD reservations (and per customer likewise), inserts only if under the cap, in one transaction. Reservations are history: status moves, no deletes; the redemption ledger stays append-only.
- **Needs from other services:** cart-svc → inventory-svc availability and pricing-svc quote (REST, never joins); order-svc → pricing-svc reserve/release (REST with `Idempotency-Key = Ids.derived(orderId, "promo-reserve")`); pricing-svc consumes `OrderCancelled` and `OrderVoided` (idempotent by event id) to release, and `PromotionRedeemed`'s own path to consume; notification-svc consumes `CartAbandoned`.
- **Checkout, in order:** quote → **reserve every capped promotion the order uses** → place the order and the stock hold on the order's transaction → on any later failure order-svc releases the reservations (a compensation, logged) → the redemption record then **consumes** the reservation instead of inserting anew. A promotion with no cap needs no reservation and costs no call. If a reserve is refused (`COUPON_EXHAUSTED` or `COUPON_LIMIT_REACHED` at the moment of reserving) order-svc refuses the placement `409 ORDER_PROMOTION_EXHAUSTED` with the re-quote (the basket without that offer) in the answer; nothing is charged, nothing held, nothing written.
- **Orphans:** a reservation whose order never appeared (a crash between reserve and insert) is released by a pricing-svc sweep, and pricing-svc never reads order-svc's settings to time it: the sweep releases a HELD reservation only when it is older than the platform's technical bound `storeql.pricing.reservation-orphan-hours` (set at least as large as any pending limit a business may set) **and** order-svc, asked over REST (`GET /orders/{id}` at the reservation's order id, service to service), says no such order exists. A reservation whose order exists is left to the order's own cancel, void or timeout events. (This replaces reading `storeql.order.pending-sweeper.ttl-hours` or the business's own `order_settings.pending_limit_hours` from [fulfilment-overrides](fulfilment-overrides.md): a business that lengthened its unpaid-order limit must not have a live order's use released.)
- **Cancelled after the use was consumed:** `OrderCancelled`, `OrderVoided` and `OrderForceCancelled` release a HELD reservation; where the redemption was already consumed (a paid order later cancelled or voided, or force-cancelled by a manager, [fulfilment-overrides](fulfilment-overrides.md), which ends in the returned state and publishes its own event), pricing-svc appends a compensating `REVERSED` row to `promotion_redemptions` (the ledger stays append-only, and the count of live uses drops by one), so the last use is available again. A partial return does not reverse a use. Each is once per event id.
- **Events published:** `CartAbandoned` (cart-svc, `storeql.cart.cart-abandoned`: tenant, cart, customer (nullable), store, item count, abandoned at; consumed by notification-svc for the reminder and by reporting-svc for the abandoned-basket count); `PromotionReserved`/`PromotionReleased` are internal to pricing-svc's table and need no event.
- **Retryable writes (Idempotency-Key):** the reserve and release calls (derived keys as above); the settings `PUT` is naturally repeat-safe.
- **Endpoints:** `GET/PUT /admin/carts/settings` (OWNER/MANAGER; read by any staff); `GET /admin/carts/abandoned?after=&limit=` (management; cursor: count and age, no line detail beyond item counts); `GET /cart` lines gain `availability`, `priceNow`; pricing-svc `POST /prices/reservations`, `POST /prices/reservations/release` (service-to-service and staff only, tenant from the JWT).
- **New error codes:** `409 ORDER_PROMOTION_EXHAUSTED` (order-svc, with the re-quote in `details`); `400 CART_SETTINGS_INVALID` (a period of zero or less, or a reminder before the abandon time); `400 CART_PRICE_NOT_ACCEPTED` is **not** used: a stray `unitPrice` is ignored, not refused, so older clients keep working.

## Money, time and limits

- **Currency:** the advice price is in the business's currency; a `displayCurrency` view stays display only as today.
- **Ledger postings:** none. A promotion's discount posts as it does today when the order posts.
- **Dates:** all periods in hours or days from UTC instants; the reminder sends at the shopper's local time of day only if the business's marketing settings say so (notification-svc's existing rule, none new).
- **Plan limits:** none.

## Constraints

- **Golden rules:** 1 (no joins: advice and prices by REST); 3 (tenant from the JWT, a guest's storefront tenant from the gateway); 6/7 (`CartAbandoned` through the outbox; the consumer idempotent); 8 (redemptions stay append-only; reservations only change status); 13 (money BigDecimal); 15 (settings validated).
- **Location-neutral:** no default period is assumed (off until set); the reminder is a marketing message under the `MARKETING` purpose and, where a per-purpose consent law binds (`Jurisdictions`), only after consent (`409 MARKETING_PURPOSE_NOT_GRANTED` semantics as `allowance`); no language or channel assumed.
- **Existing tenants:** no setting, no sweep, no reminder; advice appears (fail-open) and a promotion reservation is used only for promotions that have a cap, so a business with none sees no change.
- **Failure modes:** pricing-svc unreachable at reserve: for a capped promotion the placement is refused `503`-style (`ORDER_PROMOTION_CHECK_UNAVAILABLE`, retryable), because a cap is a guarantee and guessing breaks it; an uncapped basket is unaffected. This is the one place that fails **closed**; the advice and prices shown fail open.
- **Flow guards:** `flow-guard-comprehensive` and `flow-guard-runtime` must stay green (carts and orders).

## Open questions

- [x] **How long does a cart live?** Recommended: a business setting, off until set (no number assumed). → **setting, off** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Advice or refusal at add-to-cart?** Recommended: advice only; refusal at checkout. → **advice** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Reserve or tolerate overshoot?** Recommended: reserve at checkout, consume on redemption, release on cancel. This replaces the wave-1 position that the overshoot is acceptable (product-pricing item 8: "DEFERRED, needs a decision"). → **reserve** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **A lost race at checkout?** Recommended: refuse with the new total, never charge more silently. → **refuse with re-quote** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Is the reminder allowed?** Recommended: only to a signed-in shopper, once, under the marketing consent gate, off until a delay is set. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **May the client price be trusted anywhere?** Recommended: only in a dev or test profile; elsewhere the service reports itself not ready. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] The cart ignores a client price and shows the shop's; pricing unreadable shows none. — cart-svc `CartPriceIT.aClientPriceIsIgnored`, `pricingDownShowsNoPrice`
- [ ] With enforcement off outside a dev/test profile the ready probe reports DOWN naming the setting; in a test profile it is unchanged. — order-svc `PricingEnforcementReadinessTest`
- [ ] Adding 50 of an item with 2 available is accepted and the line says LOW or OUT; unreadable inventory says UNKNOWN and still accepts; counts appear only when the business allows. — `CartAdviceIT.addingMoreThanExistsIsAdvisedNotRefused`, `unreadableIsUnknown`, `countsFollowTheSetting`
- [ ] Checkout still refuses what is not there (`INVENTORY_INSUFFICIENT_STOCK`). — `OrderIT` (existing)
- [ ] Two checkouts racing for the last use of a capped promotion: exactly one places with the discount, the other is refused `409 ORDER_PROMOTION_EXHAUSTED` with a re-quote; the ledger never exceeds the cap; the same for a per-customer cap. — pricing-svc `PromotionReservationIT.twoRacingForTheLastUse`, `perCustomerCap`; order-svc `PromotionReservationCheckoutIT`
- [ ] A cancel, void, failed placement, or the pending-order timeout releases the reservation so the use is available again; a replayed event releases once. — `PromotionReservationIT.cancelReleases`, `aFailedPlacementReleases`, `aReplayReleasesOnce`, `orphansAreSwept`
- [ ] A placed order's redemption consumes its reservation, once per promotion and order. — `PromotionReservationIT.recordConsumesOnce`
- [ ] Pricing unreadable at a capped promotion's reserve refuses the placement (`ORDER_PROMOTION_CHECK_UNAVAILABLE`); an uncapped basket is unaffected. — `PromotionReservationCheckoutIT.failsClosedOnlyForCapped`
- [ ] With an abandon period, an idle cart with items becomes ABANDONED and `CartAbandoned` is announced once; an empty cart is not; with no setting nothing changes; the shopper's next read reopens it. — cart-svc `CartSweeperIT.abandonsIdleCartsOnce`, `noSettingNoSweep`, `reopensForTheShopper`
- [ ] Deleting abandoned carts follows its own setting and never touches ACTIVE ones. — `CartSweeperIT.purgeOnlyAbandoned`
- [ ] The reminder goes once, only to a signed-in shopper with marketing consent; withdrawing consent stops it. — notification-svc `CartReminderIT.onceAndOnlyWithConsent`, `withdrawnStops`, `aGuestHearsNothing`
- [ ] Another business's staff of every role and shopper cannot read or set the settings, the abandoned list or the reservations (404/403), and cannot spend our promotion's last use. — `CartSettingsIT.otherBusinessFindsNothing`, `PromotionReservationIT.anotherBusinessCannotReserveOurCoupon`
- [ ] Settings refuse zero, negative, or a reminder before the abandon time (`400 CART_SETTINGS_INVALID`). — `CartSettingsIT.badPeriodsAreRefused`
- [ ] Widget: the cart shows advice and the "offer ran out" re-quote sheet; the settings card. — `cart_advice_test.dart`, `cart_promotion_exhausted_test.dart`, `cart_settings_card_test.dart`
- [ ] End to end: k6 `cart-lifecycle-flow`; `flow-guard-comprehensive` and `flow-guard-runtime` green.

## Decisions

<!-- Filled while building. -->
- (Recorded now) **This page replaces the "overshoot by design of the saga" position** noted in `target/flow-catalogue/_notes/2026-09-30-product-pricing.md` item 8: order-svc still never fails a *paid* order for an unrecorded discount, because the reservation is made **before** the order is placed and paid, so by the time the redemption is recorded the use is already held.

## Screens

- **Storefront cart:** per line a short advice (words: "Only 3 left at this shop", "Not in stock here", nothing when fine); the price shown is "price now"; the checkout answer `ORDER_PROMOTION_EXHAUSTED` opens a sheet "This offer has just run out", shows the new total, and asks the shopper to confirm or leave.
- **Admin, business settings:** a *Carts* card (abandon after, reminder after, delete after, show stock counts, low-stock threshold); an *Abandoned baskets* count on the dashboard for management.
