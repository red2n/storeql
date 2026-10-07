# Personalised offers and loyalty earning rules: an offer for a segment or a named customer, and points that follow readable rules

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Readiness Review row 14.6 "Personalised offers" (missing); Oracle Customer Engagement "Promotions, offers and offer types" (covered for the engine, no audience) and "Loyalty programmes: earning rules, levels, points expiry" (partial: ledger, tiers and expiry built; earning rules missing) |
| **Services** | pricing-svc owns the offer (a promotion with an audience), who may have it at quote time, and its redemptions · customer-svc owns segments (the audience), the points-earning rules and the member's view of both · order-svc, cart-svc and the till pass identity, never a customer id typed by a shopper · the app shows offers to the shopper and at the till |
| **Builds on** | pricing-svc promotions (`promotions`, `promotion_redemptions`, `PromotionEngine`, `findExhaustedPromotions`, `POST /prices/resolve`, `POST /prices/redemptions`, `GET /promotions`, `GET /admin/promotions/windows`), [cart-lifecycle](cart-lifecycle.md) slice 3 (`promotion_reservations`), the applied-price ledger (03.12, art.6a), [line-discounts-and-price-overrides](line-discounts-and-price-overrides.md), [customer-segments](customer-segments.md), [campaigns](campaigns.md), [approvals](approvals.md) (`pricing.promotion-deep`, `customer.points-adjust`), customer-svc `LoyaltyProgramme` (tiers, multiplier), `accrueLoyaltyFromOrder` and `accrueFromOrderOnce`, `LoyaltyReversal`, `storeql.customer.loyalty.points-per-unit` (a deployment-wide config number today), [loyalty-notices](loyalty-notices.md), `OrderConfirmed` with `lines` and `storeId`, `TenantProfiles.Stores.zoneOf` |
| **Built in** | not yet built |

## Problem

Every promotion in the platform is for everybody: a percentage, a threshold, a buy-one-get-one, a coupon that anyone who has the code can type. A business cannot say "ten percent off dairy for the members who have not been in this month" or "fifteen for these fourteen customers, thank you", and cannot offer a lapsed customer a reason to return without giving it to the world. A coupon in a mailing can be forwarded to anyone. And on the earning side, a business cannot choose how many points a pound earns (it is one number in the deployment, the same for every business), cannot give double points on a category or on a slow Tuesday, and cannot keep alcohol or gift cards out of earning: the only rule is the tier's multiplier.

## Outcome

- **An offer is an ordinary promotion with an audience.** The business picks who gets it: a segment of customers it can read as a sentence, or a named list. Everyone else neither sees it nor gets it, even with the code.
- **The shopper sees their offers in the app; the cashier sees them at the till when the customer is attached,** with what is left of each. The shopper is told in the business's own words why they have it ("for our Gold members").
- **It is redeemed by the existing engine**, with the existing limits (total cap, per-customer cap, reservation against a race), and every redemption records which audience it went through.
- **No offer is priced by a rule the business cannot read.** The value is a fixed promotion the manager wrote; the audience is a segment sentence; and for any customer the business can ask "why is this offer theirs (or not)?" and get the answer.
- **Loyalty points follow rules the business sets and can read**: how many points a unit of spend earns, what is left out, and bonuses by category, product, weekday, store, tier or segment. Each order's points show how they were made.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** and **marketing manager** (a MANAGER) create offers and earning rules; the **shopper** (sees "Offers for you", redeems them online); the **cashier** (sees the attached customer's offers and remaining uses, cannot create any); the second approver where the business's rules ask.
- **Channels:** ONLINE storefront, POS (with a customer attached), back-office.
- **Scope:** per business; an offer may also be per store or channel exactly as a promotion is today. A caller held to stores may create an offer only for their stores over a segment confined to them; business-wide offers and every earning rule need a caller held to none (`403 BUSINESS_WIDE_ONLY`).
- **Roles that can write:** OWNER and MANAGER (through the existing `/admin/promotions` gate); earning rules OWNER and business-wide MANAGER. CASHIER and STOREKEEPER read the attached customer's offers at the till, nothing else.
- **Sandbox tenant:** behaves the same on the sandbox's own customers.

## Already there

- The promotion engine: seven types (`PERCENT`, `FLAT`, `BASKET_PERCENT`, `BASKET_FLAT`, `SPEND_THRESHOLD`, `BOGO`, `MIX_MATCH`), priority, exclusivity, `couponCode`, `maxRedemptions`, `maxPerCustomer` counted from the append-only redemption ledger, store and channel scope, item and category scope, activate and deactivate with reasons (`docs/API-GUIDE.md` Promotions).
- `POST /prices/resolve` takes `customerId` (used for `maxPerCustomer`), a guest has none.
- **Not there, and a finding on the way in:** the `customerId` on `POST /prices/resolve` is **supplied by the caller in the request body** (`PriceResolveResource`). That is harmless for a cap (a shopper cannot gain by naming someone else) and unacceptable for an offer (naming someone else's id would unlock their offer). Slice 1 fixes the rule.
- Earning: `accrueLoyaltyFromOrder` earns `total × points-per-unit` (the deployment's config number, default 1) and the tier's multiplier applies through `programme.multiplierFor(tier)`; expiry, tiers and their notices exist ([loyalty-notices](loyalty-notices.md)); a return or void takes points back pro rata (`LoyaltyReversal`, built 29 Sep). There is no per-business rate, no exclusion, and no rule beyond the tier multiplier.

## Scope

- **Who may do it, and where (every slice):** as under "Who and where". An offer's segment is checked as the business's own (`404` otherwise) and, for a store-held caller, confined to their stores (`409 PRICING_OFFER_SEGMENT_NOT_CONFINED`).
- **In (slices in build order):**
  1. **An audience on a promotion (pricing-svc, customer-svc).**
     - `promotions.audience_segment_id` (nullable: null is everybody, exactly as today) and `audience_label` (the shopper-facing reason in the business's words, per language through the existing template-style map, required when an audience is set: `400 PRICING_OFFER_LABEL_REQUIRED`). A promotion with an audience is **not** shown on the public `GET /promotions` list and **not** recorded in the applied-price ledger for the general storefront price (a price for some people is not the shelf price; art.6a's prior-price rule is about the price everyone is offered), so no crossed-out general price ever depends on it. Recorded as a Decision.
     - **Who is asking, and how identity is decided:** for audience promotions **only**, pricing-svc ignores any `customerId` in the body of a shopper's request. The identity is (a) a signed-in shopper: the customer resolved from the JWT's login through customer-svc (`GET /internal/customers/by-login/{loginId}`, service-to-service, cached briefly); (b) a member of staff at a till: the `customerId` the till attached, accepted only after it is confirmed to be a customer of the staff member's business (customer-svc answers the segment lookup below with "unknown customer", which means no offers); (c) a guest, or an unresolvable login: no audience offers. Public and non-audience promotions and `maxPerCustomer` behave as today.
     - **Eligibility:** for each candidate audience promotion, `findExhaustedPromotions`' sibling asks customer-svc `GET /internal/customers/{id}/segments` (the ids of segments the customer is in, one call per quote, cached ~one minute per customer, with a timeout, retry and circuit breaker) and admits the promotion only if its segment is among them. **It fails closed:** an unreadable answer means the offer is not applied and not listed (the base price stands; a giveaway is never assumed), and the quote says nothing about why. Non-audience promotions are unaffected by a customer-svc outage.
     - **Same check at reservation and redemption:** [cart-lifecycle](cart-lifecycle.md)'s reserve (`promotion_reservations`) re-checks eligibility with the same identity rule (`409 PRICING_OFFER_NOT_ELIGIBLE`, order-svc re-quotes), so a promotion cannot be reserved by someone who never qualified; a price already quoted to an eligible customer is honoured if they leave the segment before paying (the segment is as fresh as its last computation, and this is the shopper-friendly end of that).
     - `promotion_redemptions` gains nullable `audience_segment_id` (new rows only; the ledger stays append-only).
     - **Freshness is stated:** an offer reaches whoever the segment said at its last computation ([customer-segments](customer-segments.md) shows and can refresh it); nothing here pretends otherwise.
     - **The offer's own ceilings hold:** creating an audience promotion passes `pricing.promotion-deep` exactly as any promotion does; this page adds no second gate. Its audience does not change its value limits.
  2. **The customer's view (pricing-svc, customer-svc, app).**
     - `GET /promotions/mine` (a signed-in shopper; the customer is from the JWT, never the request): the audience promotions of the business whose segment the shopper is in and that are live now (window, store and channel as at the storefront's current store), each with the business's `audience_label` in the shopper's language, the offer in words (from the promotion's type and value), its end date, and **what is left for them** (`maxPerCustomer` less their redemptions; the total cap is not revealed). It never says which segment, never lists an offer they are not in, and never reveals another customer's. An unreadable segment answer lists none.
     - The cart and checkout show "Offer for you: <label>" beside a discount that came from an audience promotion, so nobody is surprised by a price that differs from a neighbour's.
     - **At the till:** `GET /admin/promotions/for-customer/{customerId}` (any staff at the store; the customer must be the business's, `404` otherwise): the same list with remaining uses, shown when a customer is attached to the sale, so the cashier can say "you have 10% off dairy this week"; applying it is simply ringing the sale, the engine does the rest.
     - The offer also reaches the customer through a [campaign](campaigns.md) that carries it (the message names the offer; the code, if one exists, only works for its audience).
  3. **Reading and explaining (pricing-svc, customer-svc, app).**
     - `GET /admin/promotions/{id}/audience/explain?customerId=` (management): yes or no, and the segment's rule parts with that customer's figures (asked of customer-svc's `explain`), so a manager can answer "why did she get it and he did not". A sensitive read of one person (recorded).
     - `GET /admin/promotions/{id}/redemptions/summary?from=&to=` (management): redemptions count and value by day and store, distinct customers, the audience's size at its last computation and its computed-at, and, where a [campaign](campaigns.md) carries it, the campaign's name. Presented as *redeemed*, never as caused by anything.
     - "Create an offer for this segment" on the segments and campaigns screens is a shortcut to `POST /admin/promotions` with the audience filled; it does not bypass a ceiling.
  4. **Earning rules (customer-svc, app).** Points follow the business's own rules, readable and previewable.
     - **Settings** (`loyalty_earning_settings`, one per business, OWNER or business-wide MANAGER): `points_per_unit` (a positive decimal; **null keeps the deployment's `storeql.customer.loyalty.points-per-unit` as today**, so no existing business changes), `basis` (TOTAL, which is today's; or NET_OF_TAX, the total less its VAT), `excluded_category_ids` and `excluded_variant_ids` (spend on these earns nothing: gift cards, alcohol, tobacco, deposits, or whatever the business or its counsel decides; the platform names none), `max_points_per_order` (null = no cap).
     - **Rules** (`loyalty_earning_rules`): a name; an **effect**, either `MULTIPLY` (spend on the matching lines earns *n* times the base rate, *n* from 1 to 10, the same bounds as a tier) or `BONUS` (a fixed number of extra points, once per order when it matches, or per matching unit; a positive whole number); and **conditions**, all of which must hold, each readable: lines in these **categories** or **variants** (categories include their children, through customer-svc's catalogue projection from [customer-segments](customer-segments.md)); on these **weekdays** and/or between two **dates** (the weekday is the **store's local weekday** at the order's time, `TenantProfiles.Stores.zoneOf`; UTC only when unreadable); at these **stores** or on this **channel**; for members of these **tiers**; for members of this **segment** (customer-svc's own, evaluated from the membership, so "double points for members who have not visited in 60 days" is one segment and one rule). No conditions on things the platform does not know.
     - **How points are worked out, in one explainable rule** (pure `Earning.compute`, exhaustively unit-tested): per matching line, points = the line's spend (per the basis, excluded lines removed) × the base rate × **the largest multiplier that applies to that line, taken from the member's tier multiplier and every matching `MULTIPLY` rule (the best of them, never compounding)**; plus each matching `BONUS` once; rounded down as earning rounds today; then the per-order cap if set. The breakdown (each line's base points, the multiplier and which rule or tier gave it, each bonus, anything capped) is stored with the order's earning in append-only `loyalty_earning_breakdowns` and shown to the member and staff. A rule outside its dates, a segment the member is not in, or an unknown category simply does not match; it never fails an order's earning.
     - **What stays the same:** earning stays idempotent per `eventId` (`accrueFromOrderOnce`); lots and expiry work as built (each bonus is part of the same lot); a return or void takes back pro rata by the refund's share of the sale, as built (`LoyaltyReversal`), bonus included; the tier is recomputed from qualifying points as built.
     - **Reading and previewing:** `GET/POST/PUT /admin/loyalty/earning/rules`, `GET/PUT /admin/loyalty/earning/settings`, `POST /admin/loyalty/earning/preview {tier?, storeId, at, lines[{variantId, qty, amount}]}` → the points and the breakdown for a sample basket, so a manager sees what a rule does **before** it is switched on; each rule has `active` and dates, and every change is a row in append-only `loyalty_earning_changes` (who, when, before and after as ids and numbers).
     - **Authority:** the action key **`customer.earning-rule`** (new; see Authority): activating or raising a rule whose multiplier or bonus is above the business's own ceiling needs a second person. No rule = none, unchanged behaviour.
     - **The member's view:** `GET /customers/me/loyalty` and the ledger entry for an order carry the `breakdown` in words ("2 points for each £1 on dairy: Double Dairy Tuesday", "Gold member ×1.5") through the same word-builder as segments; staff see it on the customer screen. The receipt's "points earned" line is the order's total.
  5. **Screens (app).**
- **Out, on purpose:**
  - **Per-customer computed prices, "dynamic" or individually optimised offers, and any offer whose value comes from a model.** An offer's value is a promotion a person wrote, its audience a segment a person can read.
  - **Offers by unique per-person codes (serialised coupons).** The audience already stops a shared code working for others; unique codes are for mailing people who are not customers, which the platform does not do.
  - **Personal price lists** (a price list bound to a customer). That is contract pricing for trade accounts, a different feature; an audience promotion is a discount, not a price.
  - **Real-time membership.** Eligibility is as of the segment's last computation.
  - **Offers to guests.** No identity, no audience; a guest gets what everyone gets.
  - **Earning on things the loyalty programme's law forbids or restricts.** The platform names no country's rule; a business lists what it excludes, and a `Jurisdictions` row could later warn, with a citation.
  - **Stacking bonus rules or compounding them with the tier.** Best of, one multiplier per line, is the rule; fixed bonuses add but never multiply.
  - **Points for non-sales events** (birthday, sign-up, reviews, referrals). Manual awards exist (management, reason, key, `manual_grants`); automated event bonuses are a separate list of triggers, each needing consent and abuse rules of its own.
  - **A change to how points expire or how tiers are reached** ([loyalty-notices](loyalty-notices.md) and the built programme stand).

## Data and flow

- **Owned by pricing-svc:** `promotions.audience_segment_id`, `promotions.audience_label` (per-language map), index `(tenant_id, audience_segment_id)`; `promotion_redemptions.audience_segment_id`. No projection of membership: the segment lookup is a call, cached briefly.
- **Owned by customer-svc:**
  - `loyalty_earning_settings` (per business): as above, with who and when. `loyalty_earning_rules`: id, `tenant_id`, `name`, `effect`, `multiplier`, `bonus_points`, `bonus_per` (ORDER or UNIT), `category_ids`, `variant_ids`, `weekdays`, `starts_on`, `ends_on`, `store_ids`, `channel`, `tiers`, `segment_id`, `active`, `created_by`, timestamps. `loyalty_earning_changes` (append-only). `loyalty_earning_breakdowns` (append-only: `tenant_id`, `customer_id`, `order_id`, `ledger_entry_id`, `lines` JSON of ids, points and rule names, unique on tenant and order).
  - The catalogue and sales projections are [customer-segments](customer-segments.md)'s slice 1 and are reused here (`catalogue_variants`); an earning rule that names a category needs it.
  - **SubjectDataSpec:** `loyalty_earning_breakdowns.customer_id` ERASE with the account on `CustomerErased` (the ledger's own rule stands: ledgers keep amounts with the link removed).
- **Endpoints:** pricing-svc `GET /promotions/mine`, `GET /admin/promotions/for-customer/{customerId}`, `GET /admin/promotions/{id}/audience/explain`, `GET /admin/promotions/{id}/redemptions/summary`, and `audienceSegmentId`/`audienceLabel` on the existing create; customer-svc `/admin/loyalty/earning/**`, `GET /internal/customers/{id}/segments`, `GET /internal/customers/by-login/{loginId}` (service to service; never routed by the gateway). `GET /promotions/mine` and its storefront use are added to the shared opening table for a signed-in shopper.
- **Needs from other services:** pricing-svc from customer-svc (segments of a customer; customer by login) with timeout, retry, breaker and **fail closed for audience promotions only**; customer-svc from `OrderConfirmed` (lines, store, channel, time), product-svc's catalogue events, `TenantProfiles` (store zones).
- **Events published:** customer-svc `LoyaltyEarned` (existing) gains `breakdown` ids and the rule names as additive fields, last in the payload; pricing-svc `PromotionChanged` (if it exists) gains `audienceSegmentId`; both go on the webhook catalogue as they are. No new topic.
- **Retryable writes (Idempotency-Key):** create or edit an audience promotion (existing rule for promotion writes), activate a rule, save settings. Earning is idempotent by `eventId`.
- **New error codes:** `400 PRICING_OFFER_LABEL_REQUIRED`, `404 PRICING_OFFER_SEGMENT_NOT_FOUND` (also another business's), `409 PRICING_OFFER_SEGMENT_NOT_CONFINED`, `409 PRICING_OFFER_NOT_ELIGIBLE` (at reserve or redemption), `400 CUSTOMER_EARNING_RULE_INVALID` (multiplier outside 1 to 10, a bonus below 1, a date range that ends before it starts, an unknown category or segment), `400 CUSTOMER_EARNING_SETTINGS_INVALID`, `404 CUSTOMER_EARNING_RULE_NOT_FOUND`, `403 BUSINESS_WIDE_ONLY`, `403 STORE_ACCESS_DENIED`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** an offer's value is in the business's home currency like any promotion; a foreign-currency display is display only. Points are quantities, not money; their deferred-revenue treatment (17.10) is unchanged and grows with the points awarded, including bonus points, as it does for every point.
- **Ledger postings:** none new. Bonus points are earned points and post through the existing loyalty liability path.
- **Dates:** rule dates and weekdays in the store's zone; offer windows as promotions today; every stored instant UTC.
- **Plan limits:** none new.

## Constraints

- Golden rules 1 (segments and customers by call, never a join; facts by event), 3 (the shopper's identity from the JWT, not the request; a till's customer verified as the business's), 6 and 7, 8, 9, 10, 11, 13, 15.
- **Fail closed for giveaways, open for the sale:** an unreadable audience answer withholds the audience offer and never blocks or reprices anything else. (Stated once beside CLAUDE.md's "limits fail open, spend authority fails closed".)
- **Consumer law is data, not code:** the shopper is always told why they have an offer (the label); a general price is never shown as reduced because of an audience offer; where a jurisdiction requires more (a personalised-price notice), it is a `Jurisdictions` row with a citation applied to a business it binds. None is asserted here.
- **Privacy:** an audience is a business's segment; the shopper never sees a segment or its name; `explain` is a recorded read; membership is read per request, not copied to pricing-svc.
- **Location-neutral:** no country, currency, weekday or language assumed; weekdays in the store's zone; labels per language.
- Existing tenants: nothing changes until a manager sets an audience or an earning setting; `points_per_unit` null keeps today's number.

## Open questions

- [x] **Who decides an offer's audience membership?** Recommended: customer-svc's segment at quote time, by call, cached briefly, failing closed for audience offers only. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **May a shopper name a customer id?** Recommended: never for an audience offer; identity from the JWT, or the till's attached customer verified as the business's. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Is an audience offer part of the general price?** Recommended: no; excluded from the public list and the applied-price ledger; shown as "Offer for you" with its reason. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **How do bonus rules combine?** Recommended: best multiplier per line, never compounding, fixed bonuses add; readable breakdown per order. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **What is the base earning rate?** Recommended: the business's own `points_per_unit`; null keeps the deployment's number so no business changes; no number is assumed by the platform. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which slice do earning rules live in?** Recommended: this page (the offers page), because a bonus by segment or category is an offer of points, uses the same segment and catalogue projection, and shares the explainability rule; [loyalty-notices](loyalty-notices.md) stays about telling members. → **this page, slice 4** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] An audience promotion applies only for a customer in its segment at the last computation: a member gets it on `POST /prices/resolve`, a non-member with the correct code gets the coupon rejected as `NOT_APPLICABLE` (never a hint that an audience exists), a guest gets nothing. — `AudiencePromotionIT.memberGetsIt`, `nonMemberWithTheCodeDoesNot`, `guestGetsNothing`; `PromotionEngineTest` unchanged
- [ ] A shopper who puts another customer's id in the request body gets that customer's **nothing**: the identity is the JWT's. — `AudiencePromotionIT.aBodyCustomerIdIsIgnored`
- [ ] At the till, the attached customer's id is accepted only if it is the business's own; another business's customer id gets no offer and no hint. — `AudiencePromotionIT.tillCustomerMustBeTheBusinesss`, `anotherBusinessesCustomerGetsNothing`
- [ ] With customer-svc unreadable, an audience offer is withheld and everything else prices as normal; no error reaches the shopper. — `AudiencePromotionIT.failsClosedForAudienceOnly`
- [ ] Reserving an audience promotion re-checks eligibility (`409 PRICING_OFFER_NOT_ELIGIBLE`); a customer who leaves the segment after quoting still gets the quoted price; the caps and the reservation race are as built. — `AudiencePromotionIT.reserveRechecks`, `quotedPriceIsHonoured`; existing `PromotionReservationIT`
- [ ] A redemption records its segment; the append-only ledger is only inserted into. — `AudiencePromotionIT.redemptionNamesTheAudience`; ArchUnit `APPEND_ONLY`
- [ ] An audience promotion is absent from the public promotions list and from the applied-price ledger, and never makes a general price "reduced". — `AudiencePromotionIT.notInThePublicListOrLedger`; `AppliedPriceIT` unchanged
- [ ] The label is required with an audience (`400 PRICING_OFFER_LABEL_REQUIRED`), shown in the shopper's language, and the segment's name is never revealed. — `AudiencePromotionIT.labelRequired`, `noSegmentNameLeaks`
- [ ] `GET /promotions/mine` lists only the caller's offers with what is left for them, none for a guest, none of another shopper's; a store-scoped or channel-scoped offer only where it applies. — `MyOffersIT.*`
- [ ] The till's `for-customer` list works for any staff at the store with the customer attached and 404s for another business's customer. — `MyOffersIT.tillList`, `anotherBusinessesCustomerIs404`
- [ ] `explain` answers yes or no with the rule parts and is recorded; the redemption summary shows counts, value, distinct customers, audience size and computed-at. — `OfferExplainIT.*`, `OfferSummaryIT.*`
- [ ] A store-held manager can create an audience offer only for their stores over a confined segment; a business-wide offer is `403 BUSINESS_WIDE_ONLY`; a cashier cannot create or edit. — `AudiencePromotionIT.storeHeldManager`, `onlyManagementCreates`
- [ ] The offer's value passes `pricing.promotion-deep` as any promotion does; the audience adds no bypass. — `AudiencePromotionIT.ceilingsStillApply`
- [ ] With no earning setting, points are exactly as today (deployment rate, tier multiplier); with a business rate, basis and exclusions, points follow them, excluded spend earns nothing, and the cap holds. — `EarningTest.*`, `EarningIT.defaultsUnchanged`, `businessRate`, `exclusions`, `orderCap`
- [ ] The best of the tier multiplier and the matching rules applies per line and never compounds; fixed bonuses add once; a rule outside its dates, on another weekday (in the store's zone), at another store, for another tier or for a segment the member is not in does not match. — `EarningTest.bestOfNeverCompounds`, `bonusOnce`, `weekdayInTheStoresZone`, `datesStoresTiersSegments`
- [ ] The order's breakdown is stored once (a redelivered event adds nothing), shown to the member and staff, and a return takes back pro rata, bonus included. — `EarningIT.breakdownOncePerOrder`, `returnTakesBackProRata`; existing `LoyaltyReversalIT`
- [ ] Preview shows the points and breakdown for a sample basket without changing anything. — `EarningIT.previewChangesNothing`
- [ ] A rule with a multiplier outside 1 to 10, a bonus below 1, an unknown category or segment, or dates reversed is `400 CUSTOMER_EARNING_RULE_INVALID`. — `EarningRuleIT.refusals`
- [ ] With a `customer.earning-rule` approval rule, a rule above the ceiling waits for a second person and is inactive until then. — `EarningApprovalIT.*`
- [ ] Only OWNER and a business-wide MANAGER set earning rules and settings; a store-held manager is `403 BUSINESS_WIDE_ONLY`; a cashier `403`. — `EarningRuleIT.onlyBusinessWide`
- [ ] Every rule and setting change is recorded with who and when and only appended to. — `EarningAuditIT.*`
- [ ] Another business's staff of every role and a shopper touch none of the offers, rules or breakdowns (404); a segment id of another business on an offer is `404 PRICING_OFFER_SEGMENT_NOT_FOUND`. — `OfferIsolationIT.*`, `EarningIsolationIT.*`
- [ ] Metric `loyalty_bonus.points` raises once per window when bonus points awarded pass the business's own rule. — `EarningAlertIT.*`
- [ ] k6 `offers-flow`: segment, audience offer, member sees and redeems it, non-member does not, earning rule with a preview and a real order; `flow-guard-*` and `loyalty-flow` stay green.
- [ ] Widget tests: `offers_for_you_test.dart`, `pos_customer_offers_test.dart`, `offer_audience_picker_test.dart`, `earning_rules_screen_test.dart`, `loyalty_breakdown_test.dart`.

## Screens

- **Storefront:** an **Offers for you** section (Account, and a quiet strip in the cart when one applies) with the label, what it is, its end date and uses left; a discount line in the cart and order labelled "Offer for you".
- **POS:** with a customer attached, an offers chip listing their live offers and remaining uses; ringing the sale applies them.
- **Admin, Marketing, Offers:** the promotions list with an Audience column and the segment's sentence; the promotion form gains **Who gets it** (everyone, a segment, a named list) and the label per language; the offer's page has the redemption summary and "Why this customer?".
- **Admin, Loyalty, Earning:** the base rate, basis and exclusions; rules with their conditions as sentences and an on/off; **Preview** with a sample basket showing the breakdown; the change history. **Customer screen:** an order's points with the breakdown.

## Authority and abuse

- **Approvals:** one new key for the catalogue table on [approvals](approvals.md): **`customer.earning-rule`** · customer-svc · shape B (second person) · measure: the rule's multiplier (a quantity) or its bonus points per order · no rule = no approval (O) · source: Oracle "Loyalty programmes: earning rules". The offer's value uses the existing `pricing.promotion-deep`; nothing else is designed here.
- **Alert metric (row to add on [exception-alerts](exception-alerts.md), owner customer-svc):** `loyalty_bonus.points` (SUM of points awarded by bonus and multiplier rules beyond the base, subject business or store): a rule set too generously is noticed within its window.
- **Abuse notes:** an audience offer cannot be unlocked by a caller's own claim (slice 1); a shared code is useless outside its audience; a per-order cap and a rule ceiling bound runaway earning.

## Flow Tests entry

Area `pricing` for the offer, file `target/flow-catalogue/pricing/promo-personalised-offers.json` (id prefix `OFR`); area `customer` for earning, file `target/flow-catalogue/customer/loy-earning-rules.json` (id prefix `ERN`); the existing `promo-promotions-and-coupons.json` and `loy-earning-and-redeeming.json` gain cross-references.

| Type | Case | Test |
|---|---|---|
| happy | Member sees and redeems an audience offer online; the cashier sees it at the till with the customer attached | k6 `offers-flow`; `AudiencePromotionIT`, `MyOffersIT` |
| happy | A double-points-on-dairy-Tuesday rule with a preview, then a real order shows its breakdown | `EarningIT.*`; k6 `offers-flow` |
| happy | A named list of one customer gets a personal offer | `AudiencePromotionIT.aListOfOne` |
| negative | Non-member with the code; guest; expired window; cap reached | `AudiencePromotionIT.*` |
| negative | Label missing; unknown segment; invalid rule (multiplier 11, bonus 0, reversed dates) | `AudiencePromotionIT.labelRequired`, `EarningRuleIT.refusals` |
| negative | Cashier creating; store-held manager on business-wide; earning by a store-held manager | `AudiencePromotionIT.onlyManagementCreates`, `EarningRuleIT.onlyBusinessWide` |
| override | A second person approves a generous earning rule; a manager sets an offer over the deep-promotion ceiling with approval | `EarningApprovalIT`, `AudiencePromotionIT.ceilingsStillApply` |
| isolation | Another business's staff and shoppers; a body-supplied customer id; another business's segment on our offer | `OfferIsolationIT`, `AudiencePromotionIT.aBodyCustomerIdIsIgnored` |
| edge | customer-svc down mid-quote; the member leaves the segment after quoting; weekday across zones; a redelivered order; a return of a bonus-earning order | `AudiencePromotionIT.*`, `EarningTest.*`, `EarningIT.*` |
| audit | Redemption records its audience; rule and setting changes recorded; explain and breakdown readable | `AudiencePromotionIT`, `EarningAuditIT`, `OfferExplainIT` |

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **Segments and earning rules.** The audience of an offer is a segment of [customer-segments](customer-segments.md) (asked as `GET /internal/customers/{id}/segments`, the one read campaigns and offers share); campaigns read `GET /internal/segments/{id}/members`. Earning rules (slice 4) are the only definition of how points are earned beyond the programme's base rate; [loyalty-notices](loyalty-notices.md) (expiry and tier notices) reads the points and tiers they produce and is otherwise unaffected. Bonus points follow the programme's expiry. The `customerId` a shopper must not supply is taken from the token at `POST /prices/resolve`, and this is listed as a defect in the plan.

- **An audience is a segment a person can read; the value is a promotion a person wrote** (2026-09-30, industry standard): no offer is priced by a rule the business cannot state to a customer.
- **Identity for an offer is never the caller's claim** (2026-09-30): a shopper's customer is resolved from the JWT, a till's from the verified attached customer; a body `customerId` still serves `maxPerCustomer` only.
- **Fail closed for giveaways** (2026-09-30): an unreadable audience answer withholds the audience offer and nothing else.
- **Audience offers are not the shelf price** (2026-09-30): absent from the public list and the applied-price ledger, always labelled "Offer for you" with the business's reason.
- **Best of, never compounding** (2026-09-30): per line one multiplier from the tier and the matching rules, fixed bonuses add once; the breakdown is stored and shown.
- **Earning rules live here** (2026-09-30): a bonus by segment or category is an offer of points and shares the segment and catalogue projections; the deployment's `points-per-unit` remains the default until a business sets its own.
