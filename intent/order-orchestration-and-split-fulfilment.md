# Order orchestration and split fulfilment across stores

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the readiness review's Omnichannel & fulfilment row · 2026-09-25 |
| **Roadmap** | Readiness Review, "Order orchestration and split fulfilment across stores", Absent (value) |
| **Services** | order-svc owns the routing decision and the split (it already chooses the delivery store); inventory-svc answers where the stock is and holds it per store; tenant-svc owns the stores, their coordinates and the delivery areas; payment-svc takes one payment for the whole checkout |
| **Builds on** | `OrderService.placeOrder` (the delivery-area store for DELIVERY, holds per line at one store), tenant-svc `GET /fulfilment/resolve` and `delivery_areas`, `stores.geo_lat`/`geo_lng`, inventory-svc reservations and `/inventory/availability`, partial fulfilment, `OrderConfirmed`/`OrderFulfilled`, payment-svc `/payments/online`, the storefront cart and checkout |
| **Built in** | the commit "feat(order,payment,inventory,cart,app): one checkout, several shops — order orchestration and split fulfilment" |

## Problem

A shopper orders four things for delivery. The store that covers their postcode has three of them; the fourth sits on a shelf in the next town. Today the order is refused ("some items just sold out") and the sale is lost, although the business holds everything the shopper wants. Nothing looks past the one store the postcode points at, the stores' coordinates are recorded and never read, and a store that is short cannot hand part of an order to another.

## Outcome

At checkout, a delivery order is routed to the stores that can fill it: the delivery-area store first, and what it cannot hold from the nearest other store that can, as few stores as possible. The shopper sees before paying that their order comes in two parts, from which stores, and pays once. Each part is an order of its own at its own store — held, confirmed, picked, fulfilled, reported and refunded exactly as an order is today — and together they are one checkout the shopper can read as one.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the online shopper; the store staff who pick each part; the manager who reads sales.
- **Channels:** ONLINE, DELIVERY. PICKUP is collected at one store and is not split (see Out).
- **Scope:** per tenant; the stores of the business that sell (type STORE; warehouses per the open question).
- **Roles that can write:** the shopper at checkout; nothing new for staff.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:**
  - A routing rule, pure and tested: given the lines, the delivery-area store and each candidate store's availability and distance, choose which store fills which line — the delivery-area store first, then the nearest candidate that holds what is left, fewest stores; a line is not split across stores unless no single store holds it (see open questions).
  - inventory-svc answers availability by store for a set of variants in one call (quantities, for the business's own use at checkout, never shown to the shopper).
  - Checkout places the order as an **order group**: one child order per store, each with its own lines, holds, total and store, linked by the group; the shopper's reply names the parts and their stores.
  - Payment for the group once; each child is confirmed from its share (see open questions); cancelling or refunding one part leaves the others.
  - The storefront shows the split before payment ("arrives in 2 parts: 3 items from Leeds, 1 from York") and the order history shows the group with its parts.
  - The cart-svc checkout mismatch the survey found (a DELIVERY order resolved to a different store never marks the cart checked out) is fixed on the way.
- **Out, on purpose:**
  - **Splitting a PICKUP order**: the shopper collects at one store; a short pickup is refused as today (or, later, moved there by transfer — a different row).
  - **Re-routing after payment** (a store that finds it cannot pick): the part is fulfilled short as today; re-routing is its own flow.
  - **Transferring stock to the delivery-area store instead of splitting**: slower, and an existing tool (transfers) a person can use by hand.
  - **Delivery charges per part**, carrier choice, shipment tracking: transport rows.
  - **Geo-polygon delivery areas**: pincode areas stay; distance is straight-line between stores' coordinates.
  - **Showing the shopper stock by store** (buy-online-pick-up-in-store availability): its own row.

## Data and flow

- **Owned by** order-svc: `order_groups` (the checkout: shopper, delivery address, total, currency, status) and `orders.group_id` (null for an order that was never split); each child order keeps its single `store_id`, so every consumer of order events keeps working unchanged.
- **Needs from other services:** availability by store for the lines (inventory-svc, REST, `GET /admin/inventory/network/stock?variants=` — see Decisions); the stores with coordinates and the delivery-area store (tenant-svc, REST, `TenantProfiles` for stores, `/fulfilment/resolve` for the area).
- **Events published:** each child's `OrderPlaced`/`OrderConfirmed`/`OrderFulfilled` as today, each carrying its `groupId`; no new consumer is required.
- **Retryable writes** (Idempotency-Key): placing the order — the whole group is created once per key; holds at several stores placed and, on a failure, all released.
- **New error codes:** `ORDER_UNFULFILLABLE` 409 (no combination of stores holds the order); `ORDER_GROUP_NOT_FOUND` 404; payment-svc `PAYMENT_GROUP_AMOUNT_MISMATCH` 400 (if one payment per group).

## Money, time and limits

- **Currency:** the order's, unchanged; each child's total in the same currency; the group total is their sum.
- **Ledger postings:** none new; each child posts as an order does.
- **Dates:** the group's placed instant; each child's as today.
- **Plan limits:** none new.

## Constraints

Database-per-service: order-svc asks inventory-svc and tenant-svc; nothing joins. Every existing consumer assumes one store per order — which is why each part is an order with one store. Holds at several stores must be all-or-nothing from the shopper's point of view. An order never split behaves exactly as today, events included. Money is BigDecimal; a group's parts add up to its total exactly (the last part takes the rounding).

## Open questions

- [x] **What is a part of a split order?** Recommended: a child order per store under an order group — every existing consumer (holds, waves, sales facts, dropship, reporting, store-scoped staff access) keeps its one-store-per-order assumption and keeps working. The alternative is shipments inside one order with a store per line, which keeps one order number but changes every consumer. → **a child order per store under an order group** (the user, 2026-09-25)
- [x] **How is a store chosen for each line?** Recommended: the delivery-area store first; what it cannot hold goes to the nearest other store (straight-line distance) that can hold all of what is left, else the fewest stores; a line is split across stores only when no one store holds it. The alternatives are stock-first (one store that holds everything, however far) or always the fewest stores regardless of distance. → **the delivery-area store first, then the nearest that holds the rest, else the fewest stores; a line split only when it must be** (the user, 2026-09-25)
- [x] **How is a split order paid?** Recommended: once for the whole group, the payment shared across the parts in proportion to their totals (the last part takes the rounding), each part confirmed from its share; a refund or cancellation of one part refunds that part's share. The alternative is a payment per part, which means several card charges for one checkout. → **once for the whole group, shared across the parts by their totals** (the user, 2026-09-25)
- [x] **Do warehouses deliver to shoppers?** Recommended: no — only stores of type STORE fill online orders; a warehouse serves its shops (depot replenishment and cross-docking). The alternative lets a warehouse fill a delivery part directly. → **no: only stores of type STORE fill online orders** (the user, 2026-09-25)

## Acceptance

- [x] The routing rule: the area store first, the nearest that holds the rest, fewest stores, a line split only when it must be; nothing fits → unfulfillable — pure `RoutingTest` (5); the world it reads — shops only, a warehouse never, a closed or out-of-reach shop passed over, an unreadable answer leaving the order where it was, dropship riding with the first part — `OrderRouterTest` (7); the money — `OrderSplitTest` (4)
- [x] A delivery order the area store can fill alone is one order, exactly as today — `SplitOrderIT.anOrderTheAreaStoreHoldsIsOneOrderAsToday`, `anOrderNeverSplitNamesNoGroup`; `OrderIT` 54 unchanged
- [x] A delivery order the area store cannot fill is placed as a group of child orders at the chosen stores, each with its lines, holds and total; the parts sum to the group — `SplitOrderIT.whatTheAreaStoreLacksComesFromTheNearestShopAsAGroupThatAddsUp`; one other shop holding it all takes it whole — `oneOtherShopHoldingItAllTakesTheWholeOrder`
- [x] No combination of stores holds it: `409 ORDER_UNFULFILLABLE`, no hold left anywhere — `SplitOrderIT.nothingThatAddsUpIsUnfulfillableAndHoldsNothing` (the warehouse's plenty unused)
- [x] The same key places the group once; a hold failing at one store releases the others — `SplitOrderIT.theSameKeyPlacesTheGroupOnce`, `aShopRefusingItsHoldReleasesWhatTheOthersHeld`
- [x] One payment confirms every part from its share; refunding one part leaves the others — payment-svc `GroupPaymentIT` (5: a tender and a `PaymentCaptured` per part, a retry replaying them, the amount, the owner and every part awaiting payment checked, a part never paid alone, a refund of one part leaving the other); both parts confirmed from one payment through the gateway — k6 `split-fulfilment-flow`
- [x] Each part's events carry the group and its own store; inventory, waves and reporting see ordinary orders — `SplitOrderIT` (each part's `OrderPlaced` names the group), inventory-svc `WaveIT` 11 untouched; each part fulfilled and deducted at its own shop — k6 `split-fulfilment-flow`
- [x] A PICKUP order is never split; another tenant sees no group — `SplitOrderIT.aPickupOrderIsNeverSplit`, `theGroupIsReadByItsShopperAndTheBusinesssStaffOnly`; the gateway and common-web admit only `GET /order-groups/{id}` to a shopper — `JwtAuthFilterTest`, `AdminAuthorizationFilterTest`
- [x] The cart is marked checked out when the delivery store differs from the cart's — cart-svc `CartCachingIT.anOnlineOrderAtAnotherStoreClosesTheShoppersCart`, `OrderPlacedHandlerTest` (2)
- [x] The storefront shows the parts before payment and the history shows the group — `split_checkout_test.dart` (5: the parts before any charge and one payment for the checkout, declining charges nothing, a one-shop order paid as before, the unfulfillable message, the history naming the delivery)
- [x] Through the gateway: a delivery order split across two stores, paid once, both parts confirmed and fulfilled at their stores — k6 `split-fulfilment-flow` 16/16 (with checkout holds on); order-crud 147, wave-flow 20, dropship-flow 21, storefront-account 44, privacy-flow 77, flow-guard-runtime 79 and flow-guard-comprehensive 110 still green

## Decisions

- **A part is placed in the order the shopper reads it** — `orders.group_part`, the delivery-area store's part `0` — rather than by id order: ids minted in one millisecond are ordered only per thread, and "3 items from Leeds, 1 from York" must not swap on a reload.
- **Every part is priced at the delivery-area store's quote.** The basket is quoted once, where the postcode put it, and the shopper pays what they were shown; a part filled by another shop keeps the quoted price even where that shop's price zone would differ.
- **Only an online delivery with holds on is routed.** A pickup, a till sale, or a rig with holds off (`reserve-enforce=false`) is never split. **A basket with a staff discount or a whole-basket offer is not split** either — placed at the area store as before and refused there if short — because sharing a discount's audit row or an offer's rows across parts to the cent is its own piece of work; an online shopper cannot give themselves a staff discount, so this touches whole-basket offers only.
- **An unreadable answer never refuses a sale.** If inventory-svc's stock by store or tenant-svc's stores cannot be read, the order is placed at the area store exactly as today and its holds decide. Only a stock answer that adds up to less than the order is `ORDER_UNFULFILLABLE`.
- **One shop other than the area store holding it all is an ordinary order at that shop**, with no group: nothing is split, so nothing needs a group.
- **Holds per part are keyed `Ids.derived(key, "store:" + storeId)`** (each line inside by index, as a single order's are). A shop refusing its hold releases the others' and the checkout fails with that shop's refusal. A retry that finds the key taken replays the first part and **frees no hold**, because the holds it met are the first placement's.
- **The first part carries the checkout's `Idempotency-Key`**, so a retry — split this time or not — finds its order the way a single order's retry does, and `findOrderByIdempotencyKey` is looked up before any hold is placed; the group's own unique key is a second guard.
- **The tax is shared from the lines' own VAT** when the quote carried it, else by subtotal, the last part taking the rounding (`OrderSplit`, pure). **A line promotion goes with the parts that hold its product**, shared by quantity (`OrderSplit.share`); a coupon is spent once, against the first part; each part counts down its own reduced-price stickers.
- **Stock by store is `GET /admin/inventory/network/stock?variants=`**, not the page's first guess of `POST /inventory/availability/by-store`: it changes nothing, so it is a GET; the quantities are the business's own and never a shopper's, so it sits with the network reads under `/admin` and order-svc asks as a storekeeper; it is bounded to 200 products.
- **The dev compose rig routes nothing unless holds are turned on.** `storeql.order.inventory.reserve-enforce` is true by default and in production, but `docker-compose.yml` turns it off (`STOREQL_ORDER_RESERVE_ENFORCE`, so an unseeded rig can check out); routing needs held stock, so the rig was left as it is and the k6 `split-fulfilment-flow` runs against order-svc started with holds on.
- **A part is an order everywhere it is counted.** tenant-svc's usage meter counts each part's `OrderPlaced`, and a business's webhook subscribers receive one per part, now carrying the `groupId` that says which belong together; both are true to what was placed.
- **Only `OrderPlaced` names the group.** `OrderConfirmed`, `OrderFulfilled` and the rest are unchanged: no consumer needs the group, and payment-svc asks order-svc for it rather than reading it off an event.
- **One payment, a tender per part.** `POST /payments/online {groupId}` checks the whole checkout (the caller's own, every part `PENDING`, the amount the total; `CARD`/`UPI`/`WALLET`), then records a tender and a `PaymentCaptured` per part — its total, its store, a key `Ids.derived(key, "part:" + orderId)` — on one transaction, so each part confirms exactly as a paid order does and a refund stays per part. **A part is never paid alone** (`PAYMENT_ORDER_IN_GROUP`), on either path.
- **Out, found while building:** a provider payment intent (SCA) for a split checkout — the storefront pays through `/payments/online`, and an intent for a group needs the provider's one charge mapped to a tender per part at its webhook; a part cannot be paid alone, so until then a split checkout is paid through `/payments/online` only. And **settlement**: the acquirer's one line for the checkout matches one part's tender by reference and shows as an amount mismatch, resolved by hand on the settlement screen.
- **A shopper who declines the parts is charged nothing**; the parts wait unpaid and lapse like any unpaid order (the stranded-order sweeper), their holds with them.
- **The cart closes on any online order the shopper places**, whichever store it went to: a shopper has one active cart in a tenant (the unique index), so the store it names is not the order's to match. A till sale naming a customer still closes only a cart at its store.
- **A request record has one constructor.** Adding a compatibility constructor to `RecordTenderRequest` made JSON-B refuse every payment body (`REQUEST_BODY_INVALID`); the new `groupId` is a plain component and the two tests that build the record pass it.
