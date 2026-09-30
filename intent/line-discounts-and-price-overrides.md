# Line discounts and price overrides at the till

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on POS (`pos/discounts-and-promotions`, `pos/ringing-up-a-sale`) and pricing (`pricing/prc-price-lists-and-prices`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, POS and pricing domains |
| **Services** | order-svc owns the line's price change, its audit and the receipt · pricing-svc keeps the queryable override log · inventory-svc, payment-svc, reporting read the corrected line totals · the app's till gets a line action |
| **Builds on** | `order_items.discount_amount` / `discount_reason` (added in V4 for parked sales, never read at placement), the whole-order staff discount (`authorizeDiscount`, `order_discounts`, role ceilings from `discountCeilings`), `ReturnValue` and `LineRevenue`, pricing-svc `price_overrides` and `POST /admin/price-overrides` (an audit log nothing at the till calls), the till cart screen, the audit trail (`AuditTrailRepository`) |
| **Built in** | not built |

## Problem

A cashier who wants to take something off one item has two poor choices. The only discount is on the **whole sale** (one amount, one reason), so a scuffed tin has to be discounted against everything else on the receipt, and the receipt and any later return cannot say which item it was for. And when the shelf label is wrong, or the pack is damaged, or the item was scanned at the wrong price, there is **no way to type the price actually charged**: the platform resolves the price itself and the cashier cannot change it. Shops work around it by keying the sale at a manager's login, which hides who decided. pricing-svc has an override log, but nothing at the till writes to it.

## Outcome

- A cashier can take a **percentage or an amount off a single line**, with a reason, within the ceiling their role has. The receipt shows the line at its list price, the reduction, and what was charged.
- A cashier (or supervisor) can **override the price** of a line to a typed price, for a damaged or mis-scanned item, with a required reason from a short list (plus free text). The list price stays on the line.
- Both are on the audit trail with who, when, which line and which sale, and both feed the exception report and the cashier's velocity metrics.
- VAT follows the charged price of the line. A return refunds what the customer paid for that line, not its list price.
- Above the cashier's ceiling, the sale is not lost: it asks for a higher authority through the approvals page (`sales.discount`, `sales.price-override`); the designed mechanism is [approvals](approvals.md), not repeated here.

## Already there

- The whole-sale staff discount with reason and a per-role percentage ceiling: `OrderService.authorizeDiscount` (OrderService.java:174), audited in `order_discounts`, tested in the order discount ITs. It stays and keeps its meaning; this page adds the line beside it.
- The order-level discount and the basket promotion are shared into a partial return by pure `ReturnValue` (`ReturnDiscountShareTest`, `ReturnDiscountIT`, wave 1 order-svc note §1). This page makes the line nets it shares over the post-line-discount nets.
- The database already has `order_items.discount_amount` and `discount_reason` (V4); parked sales already carry a per-line discount (`ParkedSaleService`). Nothing reads it when the order is placed.
- pricing-svc's append-only `price_overrides` table and `POST/GET /admin/price-overrides` exist. They record intent only: `overriddenBy` is caller-supplied and nothing enforces a reason or a ceiling. The catalogue's "no second approver" finding is the approvals page's.
- Catalogue-mode pricing (a manager gives every line a price after the goods are handed over) is separate and already audited as `PRICED` (`AuditTrailRepository` `TYPE_PRICED`, from the `AWAITING_PRICE → PENDING` history row), so POS-93 is answered; see [unit-pricing-and-listing-rules](unit-pricing-and-listing-rules.md) for its time limit.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **cashier** (line discount within ceiling), the **supervisor/manager** (higher ceilings, price override, approvals), the **owner** (sets ceilings, reads the audit).
- **Channels:** POS only. An online or guest order can never name its own price or discount (`ORDER_DISCOUNT_NOT_ALLOWED` as for the whole-sale discount).
- **Scope:** the store the till sells at (`ctx.requireStoreAccess`).
- **Roles that can write:** the roles that have a ceiling in `storeql.order.discount.max-percent` (a role with none may not discount, as today; the property's real name has a dot before `max`) or, for a business that has saved one, its approvals rule for `sales.discount`; a price override is the approvals key `sales.price-override`, whose rule names the tiers and their ceilings.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Line discount at placement (order-svc).** `OrderItemRequest` gains `discount` (`{percent}` or `{amount}`, one of them) and `discountReason`. The line stores `discount_amount` (the amount, however given) and reason; `line_total = qty × unit price − discount`; the subtotal is the sum of line totals. A discount cannot exceed the line (`400 ORDER_LINE_DISCOUNT_EXCEEDS_LINE`), a reason is required (`400 ORDER_DISCOUNT_REASON_REQUIRED`), a non-staff caller is `403 ORDER_DISCOUNT_NOT_ALLOWED`. A line with a markdown sticker or a promotion is not discounted again by hand (`409 ORDER_LINE_ALREADY_REDUCED`), because those stand outside further reduction.
  2. **One ceiling for all staff discounts.** The ceiling check counts line discounts and the whole-sale discount together as a share of the **gross list subtotal**, using the same per-role percentages; above it, `403 ORDER_DISCOUNT_EXCEEDS_AUTHORITY` as today, or the approvals path where that page is built. The audit row (`order_discounts`) is written per discount with a `scope` (`ORDER` or `LINE`) and the variant for a line.
  3. **VAT follows the charged line.** Tax is computed on the discounted line net at the line's own rate. **A conflict to settle in this slice:** the whole-sale staff discount today leaves VAT on the undiscounted subtotal (wave 1 note: "VAT is not reduced by a discount"); the usual rule is that a price reduction lowers the taxable amount. The whole-sale discount is therefore apportioned across the lines pro rata (largest remainder, the same shares `ReturnValue.discountShares` uses) so tax falls with it, and `ReturnValue` reads the line's recorded VAT rather than assuming an undiscounted VAT. The change applies only to sales placed after it ships; no recorded sale is rewritten. **Which rate:** the one in force at the moment the sale is priced, from [dated-tax-rates](dated-tax-rates.md) (`rateFor(…, asOf)`), stored on the line (`order_items.vat_rate`, `vat_amount`) so a later return refunds the VAT that was recorded and never re-resolves a rate. **This is a defect fix against today's code and against wave 1's note** (order-svc note §1: "VAT is not reduced by a discount in this platform (checkout charges VAT on the undiscounted subtotal)"): the code does not stand here, because tax authorities tax what the customer was charged; where a reduction is a price reduction the taxable amount falls with it, and a customer who pays 8.00 for a 10.00 item has paid VAT on 8.00. Build after dated-tax-rates slice 4.
  4. **Price override at the till (order-svc).** `OrderItemRequest` gains `priceOverride` (`{unitPrice, reasonCode, reason}`). Reason codes are `DAMAGED`, `MISSCAN`, `PRICE_MATCH`, `OTHER` (free text required for `OTHER`). The line keeps `list_unit_price` (what pricing-svc resolved) beside `unit_price` (what was charged). Refusals: `403 ORDER_PRICE_OVERRIDE_NOT_ALLOWED` (no ceiling for the role, or not staff, or a shopper order), `400 ORDER_PRICE_OVERRIDE_REASON_REQUIRED`, `400 ORDER_PRICE_OVERRIDE_INVALID` (negative, or above the list price: raising a price at the till is not an override, it is a catalogue change), `403 ORDER_PRICE_OVERRIDE_EXCEEDS_AUTHORITY` (deeper than the role's ceiling, which is a share of the list price). A zero price is allowed only as a comp and is treated as the deepest reduction.
  5. **Audit and events.** A new append-only `order_line_price_changes` row per discount or override (kind, variant, list and charged price, reduction, reason, actor, role, store). The audit trail lists them as `LINE_DISCOUNT` and `PRICE_OVERRIDE`. order-svc announces `PriceOverridden` (outbox) and pricing-svc consumes it once per (order, variant) into its `price_overrides` log, so the catalogue side's log and the till's agree; `overridden_by` is now the verified actor, not caller-supplied. `POST /admin/price-overrides` stays for back-office use but is no longer the till's route.
  6. **Receipt, returns and revenue.** The receipt prints list price, the reduction and its label, and the charged price (both the printed and the e-receipt and the fiscal record's line). `LineRevenue` and `ReturnValue` take line nets after line discounts, so revenue, commission and a partial return refund exactly what was paid for the line; the order-level discount is shared over those nets as before. `OrderConfirmed` lines carry the charged unit price and the discount so inventory margin and reporting read the right revenue.
  7. **Till screens (Flutter, POS shell).** Long-press or a "Line" action on a cart line opens a sheet: discount (percent or amount), price override, reason chips, and what the sale will show; a line shows a badge when reduced. A refusal above the ceiling shows the approvals prompt (when built) instead of failing the sale; the manager's name is recorded.
- **Out, on purpose:**
  - **The second-person approval itself** (a supervisor's PIN or a request): one mechanism, on the approvals page (`sales.discount`, `sales.price-override`).
  - **"Too many discounts by one cashier"** is the exception-alerts page's, metrics `discounts.count` and `price_overrides.count`; this page only records the rows those read.
  - **Raising a price above list.** Not an override; a catalogue or price-list change.
  - **Discounts on an online order or by a shopper.** A shopper's reductions are promotions and coupons only.
  - **Rewriting sales already recorded.** Recorded lines keep what they hold.
  - **Loyalty-earning rules on discounted lines.** Points follow the money paid (existing behaviour); nothing new.

## Data and flow

- **Owned by order-svc:**
  - `order_items` gains `list_unit_price` (numeric, null when not overridden), `discount_kind`, `override_reason_code`; `discount_amount` and `discount_reason` are now used.
  - `order_line_price_changes` (new, append-only): id, tenant, order, item, variant, store, kind (`LINE_DISCOUNT` or `PRICE_OVERRIDE`), list and charged price, reduction amount, percent of list, reason code, reason, actor, role, created_at. Index on tenant first.
  - `order_discounts` gains `scope` and `variant_id` (null for the whole sale).
  - Settings: **none of its own.** The price-override ceilings are the per-business approvals rule `sales.price-override` ([approvals](approvals.md); this page first proposed a per-deployment property `storeql.order.price-override-max-percent`, withdrawn so ceilings are the business's, not the deployment's). Because a price override is a **new capability rather than a control on an existing one**, it is closed until a rule exists: with no rule for the key, nobody may override a price (`403 ORDER_PRICE_OVERRIDE_NOT_ALLOWED`), and a rule names who may and how deep. No number is set by the platform. Refusals also carry the shared `AUTHORITY_EXCEEDED` details.
- **Needs from other services:** pricing-svc's resolve (unchanged) for the list price; the approvals service's decision once it exists. Nothing is joined.
- **Events published:** `PriceOverridden` (`storeql.order.price-overridden`: orderId, variantId, storeId, listPrice, chargedPrice, currency, reasonCode, actor). pricing-svc appends to `price_overrides` once per (order, variant); a replay adds nothing. reporting reads it for the exception report. `OrderConfirmed`/`OrderPlaced` lines gain `discountAmount`.
- **Retryable writes (Idempotency-Key):** `POST /orders` already carries the key; the line fields ride on it, so a replay answers with the first order and records the change rows once.
- **New error codes:** as listed in slices 1 and 4.

## Money, time and limits

- **Currency:** the business's home currency; amounts `NUMERIC(18,2)` rounded to the currency's minor units; percent inputs are converted to an amount by the same rounding (half up) and the amount is what is stored.
- **Ledger postings:** none new. Revenue and VAT are recognised on the charged line; a whole-sale discount is shared over lines as in slice 3.
- **Dates:** `created_at` UTC.
- **Plan limits:** none.

## Constraints

- Golden rules 3 (tenant from the JWT; the ceiling role from the JWT too), 6 (outbox), 7 (idempotent consumer), 8 (change rows append-only), 9 (thin resource; the check sits in `service/`), 10 (DTOs).
- **Never trust the request for money:** under server-side pricing the client names no unit price; `priceOverride.unitPrice` is only honoured through the checks above, and `list_unit_price` always comes from pricing-svc.
- Existing tenants: with no override ceiling configured, nobody may override, and a request with no line fields behaves exactly as today.
- The fiscal record and receipt layout must stay valid where a fiscal device is in use (`ProcessData`, `CloudTseProvider`): the line's reduction is expressed as the device's own discount field, per fiscal-driver test.

## Open questions

- [x] Does a line discount count against the same ceiling as the whole-sale discount? Recommended: yes, together. → **Yes, one ceiling on the combined share of the gross subtotal** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can a cashier raise a price? → **No** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a reason mandatory? → **Yes, a code, plus text for OTHER** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should VAT fall with a whole-sale staff discount? → **Yes; a price reduction lowers the taxable amount; applied to new sales only** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a manager's approval built here? → **No, it is the approvals page** (per the brief)

## Acceptance

- [ ] A percent and an amount on one line total the line right, and the sale's subtotal, VAT and total agree — `LineDiscountTest.*` (pure), `LineDiscountIT.aLineDiscountTotalsRight`
- [ ] A line discount above the role's combined ceiling is refused `403 ORDER_DISCOUNT_EXCEEDS_AUTHORITY`; nothing is recorded — `LineDiscountIT.ceilingCountsLineAndOrderTogether`
- [ ] No reason is `400 ORDER_DISCOUNT_REASON_REQUIRED`; a discount above the line is `400 ORDER_LINE_DISCOUNT_EXCEEDS_LINE`; a stickered line is `409 ORDER_LINE_ALREADY_REDUCED` — `LineDiscountIT.refusalsAreNamed`
- [ ] A price override records list and charged price, the reason and the actor; VAT is on the charged price — `PriceOverrideIT.aDamagedItemIsRungAtItsTypedPrice`
- [ ] An override above list, a negative one, a role with no ceiling, a shopper and an online order are refused with `ORDER_PRICE_OVERRIDE_*` codes — `PriceOverrideIT.refusalsAreNamed`
- [ ] Both appear in the audit trail with actor, line, sale — `AuditTrailIT.lineDiscountsAndOverridesAreListed`
- [ ] `PriceOverridden` lands once in pricing-svc's `price_overrides` even when delivered twice — `PriceOverrideConsumerIT.aReplayAddsOneRow`
- [ ] A partial return of a discounted line refunds what was paid, the last part taking the rounding — `ReturnDiscountIT.aLineDiscountIsRefundedAsPaid` (extends the existing ITs)
- [ ] A whole-sale discount now lowers VAT pro rata; a sale placed before the change reads as it did — `OrderDiscountVatIT.newSalesTaxTheDiscountedAmount`, `.recordedSalesAreUntouched`
- [ ] Another business's staff and a shopper cannot read or create these rows (404/403), and another business's role ceiling never applies — `LineDiscountIT.otherBusinessAndShopperMoveNothing`
- [ ] A business in another country and currency (yen scale 0) rounds right — `LineDiscountTest.aZeroDecimalCurrencyRoundsToYen`
- [ ] Flutter: line sheet, badge, refusal message, receipt shows list/reduction/charged — `pos_line_discount_test.dart`, `receipt_lines_test.dart`
- [ ] End to end: ring up, discount a line, override another, return part — k6 `pos-line-price-flow`

## Decisions

<!-- Filled while building. -->
