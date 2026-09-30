# Listing rules: unit prices where the law needs them, price-list currencies, and how long a sale may wait for a price

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on unit pricing, price lists, exchange-rate display and catalogue-mode pricing (`pricing/prc-unit-pricing`, `pricing/prc-price-lists-and-prices`, `pricing/prc-fx-display`, `pos/catalogue-mode-pricing`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, pricing and POS domains. Wave 1 verified these and deferred them (`_notes/2026-09-30-product-pricing.md` §10, §11) |
| **Services** | product-svc owns whether a product may be offered online, so the unit-price gate · pricing-svc owns price lists and their currencies · order-svc owns how long an order may wait for a price and cancels it · inventory-svc releases the stock on the cancellation · notification-svc tells the managers |
| **Builds on** | `Jurisdictions` and the `UNIT_PRICING` rule (`UnitPricing.UNIT_PRICING`), `GET /admin/unit-pricing/gaps`, `catalogue_variants.measure_*` in pricing-svc, `requireListable` and `PRODUCT_SAFETY_INFORMATION_REQUIRED` in product-svc, `profiles.currencyOr` and `FxRates.currencies`, order-svc `AWAITING_PRICE` orders (`priceOrder`, SJ-D41), `PendingOrderSweeper` (sweeps only `PENDING`) |
| **Built in** | not built |

## Problem

Three rules exist as advice, not as a stop:

1. Where a country's law requires a **unit price** beside the shelf price (per kilo, per litre), the platform computes it and reports which variants have no measure, but nothing stops a product going on sale online without one. The shopper then sees a price without the unit price the law says must be there. The safety-information rule already refuses at publish; this one does not.
2. A **price list** can be created in any valid currency code, including one the business neither trades in nor keeps a rate for, so a list can exist that nothing can display or convert.
3. A till order **waiting for a price** (goods handed over, a manager to price them later) waits for ever. The pending-order sweeper ignores it by design; an unpriced sale outstanding for days is a live liability.

## Outcome

- A business bound by a unit-pricing law cannot make a product available online unless every variant it offers can show a unit price. It is refused when it publishes, with the variants named, not discovered at the sale. A business not bound is never asked.
- A price list is only in the business's home currency or one it keeps a rate for.
- The business chooses how long a till order may wait for a price. Past the first limit it is flagged to the managers; past the second it is cancelled, its stock hold released and the loss visible. Until the business sets the limits, nothing changes.
- The manager who prices an order is on the record as before, and two managers pricing at once cannot both win (proved by a test).

## Already there

- The unit price is computed and shown beside the price everywhere the measure is declared (resolve, quote, labels, promoted prices, weighed lines) and `GET /admin/unit-pricing/gaps` lists what is missing; `required` says whether the business's jurisdiction binds it (pricing-svc `UnitPricingIT.aMeasuredItemIsQuotedWithItsUnitPrice`, `.noMeasureIsAGap`, `.whichBusinessesAreBound`, `.aWeighedLineIsUnitPricedPerKilogram`). The gate below reuses the same rule key and never re-derives law.
- An ISO 4217 check on a price list's currency, and the default to the business's own: `profiles.currencyOr` refuses anything else with `400 CURRENCY_INVALID`, and stores ` eur ` as `EUR` (`PromotionLimitsIT.aPriceListCurrencyMustBeAnIsoCode`, wave 1 §10). What is missing is the second half: the currency is one the business uses.
- **PRC-67, charging in the display currency:** already refused. order-svc resolves the order's currency from the business's own and refuses any other with `400 ORDER_CURRENCY_MISMATCH` (`OrderService.resolveCurrency`), writing nothing (`DisplayCurrencyIT.chargingInTheDisplayCurrencyIsRefused`, yen and dollar businesses; `OrderIT` line 1986). A test for the pricing-svc side (a `displayCurrency` figure is a separate field and never the charge) is added below.
- **POS-93, no audit of who priced the order:** already there. The `AWAITING_PRICE → PENDING` history row names who priced it, when and for what total, and the audit trail lists it as `PRICED` (`AuditTrailRepository`, `TYPE_PRICED`). Only a test naming it is missing.
- **POS-92, two managers pricing at once:** the code already answers it: `OrderRepository.priceOrder` takes the row `FOR UPDATE` and refuses anything not `AWAITING_PRICE` with `409 ORDER_NOT_AWAITING_PRICE`. No test races it yet.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner/catalogue manager** (publishing, price lists), the **store manager** (pricing waiting orders and alerted when one is overdue), the **shopper** (sees the unit price).
- **Channels:** back office (publish, price lists, settings), POS (waiting orders), online storefront (the unit price shown).
- **Scope:** the unit-price gate is per business (its jurisdictions); price-list currency per business; the price-wait limits per business, applied to each store's orders.
- **Roles that can write:** OWNER/MANAGER for products, price lists and the wait policy; a manager at the store prices orders (as today).
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Tests for what is already true (no production change).** `DisplayCurrencyIT`-style test in pricing-svc that a resolve or quote in a display currency carries the converted figure in a separate field and the charged one in the list's own currency (PRC-67, pricing side); `PricePricingRaceIT.twoManagersPricingAtOnce` (POS-92); the `PRICED` audit entry named in an audit test (POS-93).
  2. **A price list's currency must be one the business uses (pricing-svc).** In `createPriceList`, when a currency is named, it must be the business's home currency (`TenantProfiles`) or one it keeps a dated rate for (`FxRates.currencies(tenantId)`); otherwise `400 PRICING_LIST_CURRENCY_NOT_USED`, naming the currencies it may use. When the home currency **cannot be read** (tenant-svc down or a business tenant-svc does not know, as in `PricingIT.anUndescribedTenantIsRefusedNotGuessed`) the check is skipped and the list is created as today: the platform guesses nothing, it just does not hold back a write for a missing lookup. That is why `PricingIT` creates a EUR list with no profile at all, and it stays valid. Before this ships, every IT and k6 script that names a foreign currency must first record a rate (a fixture step); the list to fix is found by grepping `"currency"` in `PricingIT`, `PriceZonesIT`, `k6/`. No existing list is touched. A list already in a currency the business later stops keeping a rate for is not changed; it is shown with a warning on the screen.
  3. **The unit-price gate (product-svc, then pricing-svc read-only).** Where `Jurisdictions.inForce(tenant, UNIT_PRICING, today)` says the law binds any country the business trades in (the same test pricing-svc uses in `unitPriceRequired`), making a product available online (`sellableOnline` on an ACTIVE product: create, edit, import, launch and un-delist) is refused `400 PRODUCT_UNIT_PRICE_INFORMATION_REQUIRED`, the variants without a declared measure in the details, unless every variant the offer includes has a measure unit and a quantity above zero. A measure of `EA` ("each") is always available to say a variant is priced per item, so there is always a way to comply. When the jurisdiction rules cannot be read the answer is `503` and nothing is assumed, as `requireListable` does for safety information. Bulk import refuses the row, not the file (`sellableOnline false` is the escape, as for safety). The check sits in one place with the existing `requireListable`, so publishing runs both and reports all that is missing together.
  4. **What already-live offers do when a law starts to bind.** Nothing is delisted silently. Products already online keep selling; `GET /admin/unit-pricing/gaps` (management) stays the worklist, the admin Products screen shows a "Unit price missing" badge, and the next edit of a product must satisfy the gate. The shopper sees no unit price for a variant that has none (as today).
  5. **The price-wait limits (order-svc).** Two new columns on order-svc's one per-business waits row `order_settings` (owned by [fulfilment-overrides](fulfilment-overrides.md) slice 2, which also holds the unpaid-order limit; built first): `price_wait_flag_minutes` and `price_wait_cancel_minutes` (both nullable, off until set; cancel not earlier than flag; `400 ORDER_PRICE_WAIT_INVALID`). A sweeper (`AwaitingPriceSweeper`, next to `PendingOrderSweeper`, interval a platform technical setting) takes each `AWAITING_PRICE` order older than the limits. **At the first limit** it stamps `price_overdue_at` once and publishes `OrderPriceOverdue`. **At the second** it cancels the order (`AWAITING_PRICE → CANCELLED`, reason `PRICE_WAIT_EXPIRED`, actor none) through the same `transitionOrderStatus` as any cancellation, publishing `OrderCancelled` so inventory-svc releases the hold. Both are conditional updates, so a manager pricing at the same moment wins or loses cleanly (whoever's update finds `AWAITING_PRICE` first).
  6. **The overdue order is visible (notification-svc, reporting, app).** notification-svc sends `ORDER_PRICE_OVERDUE` (in-app, and email where the manager has chosen it) to the OWNER/MANAGER staff at the order's store; reporting's exception report lists overdue and expired-unpriced orders per store and cashier (metric `orders.awaiting_price_age` and `orders.price_wait_expired`, alert thresholds are the exception-alerts page's); the till and Admin → Orders show an "Overdue for a price" chip with the age. Settings are edited on the business settings screen next to the return policy.
- **Out, on purpose:**
  - **Deciding what law binds.** `Jurisdictions` decides; this page never names a country or a regulator.
  - **Refusing an existing offer at sale time for a missing unit price.** A sale is never refused over it (`unitPriceRequired` fails toward "required" only for display); refusal is at publish.
  - **Writing off the goods of a cancelled unpriced sale.** The goods left the store; the cancellation makes the loss visible in the exception report, and a person records the write-off as a stock adjustment (`stock.writeoff` through the approvals page, not here).
  - **Deleting or converting lists in a currency no longer kept.** A warning only.
  - **A number for either limit.** The business chooses; the platform sets none.

## Data and flow

- **Owned by product-svc:** nothing new stored; the gate reads variants' measure columns (product-svc owns them) and `Jurisdictions`.
- **Owned by pricing-svc:** nothing new stored; `createPriceList` reads `TenantProfiles` and `FxRates.currencies`. `GET /admin/unit-pricing/gaps` unchanged.
- **Owned by order-svc:** the two columns above on `order_settings` (one row per business, with `pending_limit_hours` from fulfilment-overrides; who changed and when are on the row); `orders.price_overdue_at` (nullable, set once). Endpoints: `GET/PUT /admin/orders/settings/price-wait` (OWNER/MANAGER), beside `/admin/orders/settings/pending-limit`. This replaces the separate `price_wait_policies` table and `price-wait-policy` route this page first named: the two waits are one settings row, one sweeper family and one screen card, because a business that sets one is setting the other.
- **Needs from other services:** product-svc reads the jurisdiction rules through `Jurisdictions` (common-service, which asks tenant-svc); pricing-svc reads tenant-svc's currency and the business's rates; inventory-svc consumes `OrderCancelled` (existing).
- **Events published:** `OrderPriceOverdue` (`storeql.order.price-overdue`: orderId, storeId, awaitingSince, total if known; consumed by notification-svc to alert and by reporting to list, both idempotent by orderId), and the existing `OrderCancelled` with reason `PRICE_WAIT_EXPIRED`.
- **Retryable writes (Idempotency-Key):** `PUT …/settings/price-wait` is a replace, naturally idempotent; the sweeper's updates are conditional, so a repeat does nothing.
- **New error codes:** `400 PRODUCT_UNIT_PRICE_INFORMATION_REQUIRED`, `503` when the rules cannot be read (existing code), `400 PRICING_LIST_CURRENCY_NOT_USED`, `400 ORDER_PRICE_WAIT_INVALID`.

## Money, time and limits

- **Currency:** the price list's own, restricted as above; nothing is converted or charged in a display currency (proved by the tests in slice 1).
- **Ledger postings:** none; a cancelled unpriced order recognised no revenue.
- **Dates:** `price_overdue_at` UTC; ages are measured from the order's `created_at`. The unit-price check uses today in UTC as pricing-svc's does.
- **Plan limits:** none.

## Constraints

- Golden rules 1 (product-svc never reads pricing's tables; pricing never reads products'), 6 and 7 (event with the write, idempotent consumers), 15 (bad input is a 400).
- Location-neutral: which countries need a unit price is `Jurisdictions`'s answer only; the wording is neutral ("a unit price").
- Existing tenants: no behavioural change until a law binds them and they publish, or they set the wait limits.
- A cancelled unpriced order must not double-release: `OrderCancelled` is idempotent in inventory-svc already.

## Open questions

- [x] What may a seller of goods the law exempts (single items, services) do? → **Declare the measure `EA` (each); the unit price is the price and there is always a way to comply** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Refuse when the rules cannot be read? → **503 at publish; nothing assumed; a sale is never refused** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What if the home currency cannot be read when a list is created? → **Skip the check and create it, as today; no guess is made** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How long may an order wait? → **The business's own setting; off until set** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does an overdue order cancel? → **Only at the business's second limit; the first only flags** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A bound business's publish without a measure is refused `400 PRODUCT_UNIT_PRICE_INFORMATION_REQUIRED` naming the variants; an `EA` measure passes — `UnitPriceListingIT.publishNeedsAMeasureWhereLawBinds`
- [ ] A business the law does not bind publishes as before — `UnitPriceListingIT.anUnboundBusinessIsNeverAsked`
- [ ] Rules unreadable: `503`, nothing assumed, nothing stored — `UnitPriceListingIT.unreadableRulesAreRefusedNotGuessed`
- [ ] An import row without a measure is refused with `sellableOnline` false as the escape; the other rows import — `UnitPriceListingIT.importRefusesTheRowNotTheFile`
- [ ] Two businesses in different countries get different answers for the same product data — `UnitPriceListingIT.lawFollowsTheBusiness`
- [ ] A list in a currency that is neither home nor rated is refused `400 PRICING_LIST_CURRENCY_NOT_USED`; home and rated ones are created; an unreadable profile skips the check — `PriceListCurrencyIT.currencyMustBeInUse`, `PricingIT.anUndescribedTenantIsRefusedNotGuessed` (unchanged)
- [ ] A display-currency figure is never the charge on the pricing side — `PricingIT.aDisplayFigureIsNeverTheCharge` (PRC-67)
- [ ] Two managers pricing one waiting order at once: exactly one wins, the other `409 ORDER_NOT_AWAITING_PRICE` — `PriceOrderRaceIT.twoManagersPricingAtOnce` (POS-92)
- [ ] The priced order shows a `PRICED` audit entry with actor and total — `AuditTrailIT.aPricedOrderIsListed` (POS-93)
- [ ] With no policy set the sweeper touches nothing; past the first limit an order is flagged once and one alert is sent; past the second it is cancelled with its hold released — `AwaitingPriceSweeperIT.flagsThenCancels`, `.noPolicyMeansNoAction`, `InventoryIT.aCancelledUnpricedOrderReleasesItsHold`
- [ ] A manager pricing at the moment of the sweep wins or loses cleanly and the order is never both priced and cancelled — `AwaitingPriceSweeperIT.pricingRacesTheSweep`
- [ ] The policy is validated (`400 ORDER_PRICE_WAIT_INVALID`) and guarded (a CASHIER cannot set it) — `PriceWaitPolicyIT.*`
- [ ] Another business's staff cannot read or change our policy or see our overdue orders (404); a store-held manager sees only their stores' — `PriceWaitPolicyIT.isolation`
- [ ] Flutter: the Unit-price-missing badge and the publish refusal, the Overdue chip, the policy fields — `products_screen_unit_price_test.dart`, `orders_price_overdue_test.dart`
- [ ] End to end: a waiting order left past both limits — k6 `catalogue-mode-flow` (extended)

## Decisions

<!-- Filled while building. -->
