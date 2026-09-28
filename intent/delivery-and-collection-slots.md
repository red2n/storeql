# Delivery and collection slots

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the design system's proposed *DeliverySlotPicker* and the user · 2026-09-26 |
| **Roadmap** | new: the design system's *DeliverySlotPicker* ("needs slot data the backend doesn't serve yet") |
| **Services** | order-svc owns each store's windows and what they hold (a window is taken on the order's own transaction) · tenant-svc owns the stores and their time zones, read through `TenantProfiles` |
| **Builds on** | `orders` (DELIVERY and PICKUP, PENDING until paid, the pending sweeper's lapse), pincode → store (`TenantClient.resolveFulfilment`), split checkouts (`order_groups`), the Fulfilment screen's queue, the storefront cart, `OrderConfirmed`, the design system's *DeliverySlotPicker* |
| **Built in** | 577f5106 (branch `test/k6-gross-margin`, 26 Sep 2026) |

## Problem

A shopper cannot say when their delivery should come or when they will collect, so a store takes every order at once and promises nothing: vans leave half-empty in the morning and overloaded in the evening, collections pile up at the counter, and the shopper who is out when the driver calls is a failed delivery. The store has no way to say *we can do twelve deliveries between 5 and 7*.

## Outcome

A manager sets, for each store, the windows it offers — for delivery and for collection separately: the weekday, from and to in the store's own time, how many orders a window takes, and how long before it starts orders stop. At checkout the shopper sees the next seven days of that store's windows in the store's time, a full one marked *Full* and one past its cut-off left out, and picks one. The order holds the window while it waits to be paid and keeps it once paid; a window that filled up meanwhile is refused in words and the shopper picks another. Staff see the window on every order and work the Fulfilment queue in window order; the shopper sees it in their order history and in the confirmation email. A store that offers no windows checks out exactly as today.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store manager (sets windows), the online shopper (picks one), pickers and staff at the store (work to it).
- **Channels:** ONLINE only (DELIVERY and PICKUP); never a till sale.
- **Scope:** per store. A window belongs to the store that fills the order — for a delivery, the store the postcode resolves to.
- **Roles that can write:** OWNER and MANAGER set windows (a store-scoped manager at their own stores); a shopper picks one at checkout.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:**
  - order-svc: each store's windows (fulfilment type, weekday, from, to, capacity, cut-off, on/off); a read of the next seven days' windows for the storefront with what each has left; the order carrying its window; placement taking a place in the window on the order's own transaction, refused when the window is full, closed, unknown or missing where the store offers windows for that type; the window on every order answer and on `OrderConfirmed`.
  - common-service: each store's time zone in `TenantProfiles.Stores`, read from tenant-svc as the stores already are.
  - notification-svc: the confirmation email says the window when there is one.
  - The app: the storefront's slot picker (the design system's *DeliverySlotPicker*) after the shopper chooses collection or delivery; the window on the shopper's order history; *Slots* per store in the back office (management); the window on the Orders and Fulfilment screens, the Fulfilment queue in window order.
- **Out, on purpose:**
  - **A fee for a window** (or for delivery at all): a delivery charge's tax follows the goods in some countries and not in others, and it reaches refunds, the ledger, the VAT return and every report — it is its own item, not a field on a window.
  - Closing a store on one date (a holiday): windows are weekly; a manager switches a window off and on again. Dated exceptions are a later item.
  - Changing the window after the order is placed: cancel and place again.
  - Windows per postcode, driver routes and van capacity (Transport and route planning is its own row).
  - Picking waves ordered by window: the Fulfilment queue is; waves keep their own rules.

## Data and flow

- **Owned by** order-svc: `fulfilment_windows` (tenant, store, type, weekday, from, to, capacity, cut-off minutes, on/off, who set it); `orders.slot_starts_at` / `slot_ends_at` / `slot_window_id` (UTC instants of the chosen occurrence).
- **Needs from other services:** the store's time zone and country (`TenantProfiles.Stores`, REST, cached); nothing joined.
- **Events published:** `OrderConfirmed` and `OrderPlaced` carry `slotStartsAt` / `slotEndsAt` when the order has a window (notification-svc says it in the confirmation; nobody else changes).
- **Retryable writes** (Idempotency-Key): placing the order, as today (a replay returns the first order and takes no second place).
- **New error codes:** `400 ORDER_SLOT_REQUIRED` (the store offers windows for this type and none was chosen), `400 ORDER_SLOT_UNKNOWN` (no such window at the store on that day, or only one of window and start sent), `409 ORDER_SLOT_FULL`, `409 ORDER_SLOT_CLOSED` (past its cut-off or in the past), `400 ORDER_SLOT_NOT_APPLICABLE` (a window on a till sale or an in-store order), `400 ORDER_SLOT_WINDOW_INVALID` (from not before to, capacity below 1, a cut-off below 0, a weekday outside 1–7, overlapping windows of one type on one day), `404 ORDER_SLOT_WINDOW_NOT_FOUND`, `404 ORDER_SLOT_STORE_NOT_FOUND` (a store that is not the business's), `400 ORDER_SLOT_STORE_REQUIRED`, `400 ORDER_SLOT_TYPE_INVALID`.

## Money, time and limits

- **Currency:** none — windows are free in this cut (see Out, on purpose).
- **Ledger postings:** none.
- **Dates:** windows are set in the store's own time (its IANA zone from tenant-svc) and stored on the order as UTC instants; a window on a day the clocks change keeps its local times. The shopper sees the next seven days from today in the store's time.
- **Plan limits:** none.

## Constraints

Capacity is counted where orders are written: taking a place and writing the order are one transaction, with the store's window locked, so two shoppers cannot take the last place twice. An order holds its place while PENDING, CONFIRMED or being picked and handed over; a cancelled, voided or lapsed order gives it back. Multi-tenant: every window, read and count is the tenant's own; the storefront reads only the windows of its own business's stores; another business's store id is `404`. Multi-location: every time is the store's own zone; no zone, weekday or hour is assumed. Existing tenants: a store with no windows checks out exactly as before.

## Open questions

- [x] **Who sets the windows?** Recommended: an owner or manager, per store; a store-scoped manager at their own stores. → **owner or manager, per store** (the user, 2026-09-26: "I will go with your recommendation")
- [x] **Collection windows too?** Recommended: yes — delivery and collection each have their own. → **yes** (the user, 2026-09-26)
- [x] **How long is a window held?** Recommended: the order holds it from placement while it waits to be paid (the pending order's own lapse), and keeps it once paid; no separate hold. → **held by the order** (the user, 2026-09-26)
- [x] **Must a shopper pick a window?** Recommended: yes, for a fulfilment type the store offers windows for; a store without windows checks out as today. → **yes, where offered** (the user, 2026-09-26: accepted with the recommendations)
- [x] **A delivery split across shops?** Recommended: the window belongs to the checkout; every part carries it; it takes one place, at the store the postcode resolves to (whose windows the shopper chose from). → **one place, at the area store** (the user, 2026-09-26: accepted with the recommendations)
- [x] **A fee for a window?** Recommended: not in this cut (see Out, on purpose). → **no fee in this cut** (the user, 2026-09-26: accepted with the recommendations)

## Acceptance

- [x] A manager sets a store's delivery and collection windows; overlapping, back-to-front or zero-capacity windows are refused `400 ORDER_SLOT_WINDOW_INVALID` — `FulfilmentWindowsIT.aManagerSetsDeliveryAndCollectionWindowsSeparately`, `overlappingBackToFrontOrZeroCapacityWindowsAreRefused`
- [x] The storefront lists the next seven days' windows in the store's time with what each has left; a full one says so and one past its cut-off is left out — `FulfilmentWindowsIT.theStorefrontListsSevenDaysInTheStoresOwnZone`, `twoStoresInTwoZonesEachShowTheirOwnLocalTime`, `aFullWindowSaysSoAndAPastCutoffOccurrenceIsLeftOut`, pure `WindowsTest` (23: London across 29 Mar and 25 Oct 2026, New York 8 Mar, Sydney 4 Oct, Kolkata, Kathmandu, the spring gap)
- [x] Placing an order takes a place; the last place is taken once when two checkouts race; a full window is `409 ORDER_SLOT_FULL`, a past one `409 ORDER_SLOT_CLOSED`, a missing one where offered `400 ORDER_SLOT_REQUIRED` — `FulfilmentWindowsIT.placingTakesAPlaceAndTheLastPlaceIsTakenOnceUnderARace` (six threads, one place), `aPastCutOffOccurrenceIs409Closed`, `aMissingSlotWhereOfferedIs400RequiredAndAnUnknownOneIs400Unknown`
- [x] A cancelled or lapsed order gives its place back; a replayed checkout takes no second place — `FulfilmentWindowsIT.aCancelledOrderGivesItsPlaceBackAndAReplayTakesNoSecondPlace`
- [x] A store with no windows checks out exactly as before; a till sale never carries one — `FulfilmentWindowsIT.aStoreWithNoWindowsChecksOutExactlyAsBefore`, `aSlotOnAPosSaleIs400NotApplicable`, existing `OrderIT`, k6 `order-crud` 147
- [x] A split delivery's parts all carry the window and take one place at the area store — `SplitOrderSlotIT.aSplitDeliverysPartsAllCarryTheWindowAndItTakesOnePlace`
- [x] Another business's owner, manager, cashier and storekeeper — even naming our store's id — read no windows, set none and take no place; its storefront lists none of ours — `FulfilmentWindowsIT.anotherBusinesssStaffReadNoWindowsSetNoneAndTakeNoPlaceEvenNamingOurStore`, `aStoreHeldManagerCannotSetAnotherStoresWindows`, k6 `fulfilment-slots-flow`
- [x] The order answer, the shopper's history and `OrderConfirmed` carry the window; the confirmation email says it — `FulfilmentWindowsIT.theOrderAnswerTheShoppersHistoryAndTheEventsCarryTheWindow`, `EventsTest.orderPlacedCarriesTheSlotWhenTheOrderHoldsOneAndNotWhenItDoesNot`, `orderConfirmedCarriesTheSlotWhenTheOrderHoldsOneAndNotWhenItDoesNot`, notification-svc `OrderConfirmedHandlerTest` (Warsaw/pl, Kolkata/en-IN, New York/en-US)
- [x] The storefront picker shows seven days, marks full windows, and sends the chosen window; the back office sets windows per store; Orders and Fulfilment show the window, the queue in window order — widget tests `test/features/storefront/delivery_slot_picker_test.dart`, `delivery_slots_checkout_test.dart`, `order_slot_display_test.dart`, `test/features/admin/orders_slot_display_test.dart`, `fulfilment_windows_screen_test.dart`, `fulfilment_screen_test.dart`
- [x] Through the stack: windows set, one filled to capacity, the next checkout refused, another window taken, the order shows it — k6 `fulfilment-slots-flow` 81 checks (green with checkout holds off and on)

## Decisions

- **The store's own zone, from tenant-svc, with the window's copy as the fallback.** `TenantProfiles.Stores` now reads each store's `timezone` (`zoneOf`); a window keeps the zone it was set in, used when tenant-svc cannot be read at checkout or on the storefront. A window is refused when the store is not the business's or its zone is unknown. The storefront list falls back to UTC only when neither exists — and then the store has no windows (`offered: false`).
- **The server writes the local times; clients never convert.** Every order answer's `slot` carries `date`, `startTime` and `endTime` in the store's zone beside the UTC instants, and the slots read carries both — the app has no zone database, and java.time does. A window on a clock-change day keeps its local times; a local time that does not exist that day moves forward as java.time does.
- **Only capacity is decided under the lock.** `ORDER_SLOT_REQUIRED`, `…_UNKNOWN`, `…_NOT_APPLICABLE` and `…_CLOSED` are settled before the transaction, so a doomed checkout never holds the window row; inside it the row is locked `FOR UPDATE`, the occurrence re-checked, and the count taken over orders at that occurrence that are not CANCELLED or VOIDED.
- **A split checkout takes one place.** Every part carries all four slot columns (the window, both instants, the zone) and the count is `COUNT(DISTINCT coalesce(group_id, id))`; the place is claimed once per checkout, at the area store — the one the postcode resolves to, captured before routing reassigns stores.
- **A window on a till sale is refused, not ignored** (`400 ORDER_SLOT_NOT_APPLICABLE`): a client that sends one has misunderstood something, and silence would hide it. A slot at a store that offers none is `ORDER_SLOT_UNKNOWN`.
- **An occurrence that exists but has closed is `409 ORDER_SLOT_CLOSED`; one that never existed is `400 ORDER_SLOT_UNKNOWN`** — the shopper can act on the first (pick another), the second is a client's mistake.
- **Windows are switched off, not deleted,** and the admin list shows them; overlap is checked among active windows of one store, type and weekday, and touching windows (15–17, 17–19) do not overlap. An order keeps the instants it was placed with whatever later happens to its window.
- **The Fulfilment queue is in window order; waves are not.** The packed and ready queues ask `GET /orders?sort=slot` (windowless orders last, then by id, a cursor carrying both keys); *handed over today* stays in handover order; picking waves keep their own rules (Out, on purpose).
- **The storefront asks the delivery store before checkout.** The cart resolves the postcode (`GET /fulfilment/resolve`, debounced) so the picker shows the windows of the store that will fill the order — the same store order-svc resolves.
- **Test fixtures stub tenant-svc's stores with their zones directly** (`JsonStub`): common-test's `TenantSvcStub` has no zone, so the split case is its own `SplitOrderSlotIT` rather than a method of `SplitOrderIT`.
