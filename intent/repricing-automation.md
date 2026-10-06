# Repricing automation: small changes apply themselves, stale ones close themselves

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on repricing (`pricing/prc-price-zones-and-repricing`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, pricing domain. Wave 1 closed the stale-apply refusal and left automatic repricing deferred (`_notes/2026-09-30-product-pricing.md` §7, "DEFERRED without work") |
| **Services** | pricing-svc owns rules, proposals, the automatic apply and its limit · the admin app's Price zones and repricing tab shows what was applied by whom · order/cart/storefront learn only of `PriceChanged`, as for any price change |
| **Builds on** | `repricing_rules` (`floor_percent`, `max_age_days`), `repricing_proposals` (`PROPOSED`/`APPLIED`/`DISMISSED`, one open per rule and variant), pure `Repricing` (`propose`, `isStale`), `RepricingService.run/apply/dismiss`, `PricingRepository.writePriceListItemTx` and `decide`, `AppliedPriceSweeper` (the only background job in pricing-svc), the [Price zones and repricing](../CLAUDE.md) rule "a rule proposes and a person applies" |
| **Built in** | not built |

## Problem

Every proposal, however small, needs a person's click. A shop with hundreds of lines and a rival who moves a penny a week gets hundreds of proposals and either ignores them or clicks through without looking, which is worse than a limit the owner chose on purpose. Meanwhile a proposal nobody decided sits open until something removes it: `run` withdraws an open proposal only when the rule is run again and finds nothing to say, and `apply` refuses a stale one only when somebody tries it.

## Outcome

- An owner can tell one rule "apply your own proposals when the change is no more than X% of the current price". Until they set X, the rule behaves exactly as today: nothing applies itself.
- A change above X (or a rule with no X) is proposed and waits for a person, as today.
- A proposal whose rival price has aged past the rule's reach is closed by the platform on its own and says why, so the list only holds proposals still worth deciding.
- Nothing is ever priced below the rule's floor, by a person or by the rule, judged against the price the item has **at the moment the write happens**, not the price it had when the proposal was made.
- Every automatic change is recorded as the platform's (not a person's), with the rule, the rival and the old and new price, and the price history and `PriceChanged` are exactly those of a person's apply.

## Already there

- Applying a stale proposal is refused `409 PRICING_PROPOSAL_STALE`, nothing written, the proposal stays open; dismiss still works; running again refreshes the proposal (`Repricing.isStale`, `RepricingService.apply`; `RepricingTest.anObservationOlderThanTheRulesReachIsStaleTheDayItAgesOut`, `PriceZonesIT.aProposalWhoseRivalPriceHasAgedOutIsNotAppliedButCanBeDismissed`, `.aProposalExactlyAtTheRulesReachStillApplies`, `.aStaleProposalRunAgainAgainstAFreshSightingIsAppliedOnce`; wave 1 note §7).
- The floor is pure and tested at proposal time: `Repricing.propose` never goes below `floor_percent` of the current price and `.99` is reached only by rounding down (`RepricingTest`).
- A proposal is decided once (`REPRICING_PROPOSAL_DECIDED`); an apply writes through `writePriceListItemTx` on the same transaction as `PriceChanged` and the price history (`PriceZonesIT.aRivalsPriceBecomesAProposalThatAppliesIntoTheZonesListOnly`).
- Isolation and roles on apply/dismiss (other business 404, STOREKEEPER/CASHIER/CUSTOMER 403): `PriceZonesIT.anotherBusinessAndLowerRolesCannotApplyOrDismissOurProposal`.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** and the **pricing manager** who set the rule; nobody clicks for an automatic change; the shopper sees the price.
- **Channels:** back office; the price applies wherever the list applies (till, online).
- **Scope:** per rule, so per price list and zone; the limit is per rule.
- **Roles that can write:** OWNER, MANAGER (as for rules today). A CASHIER or STOREKEEPER cannot set or change the limit.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Close stale proposals by themselves (pricing-svc).** A proposal whose observation is older than the rule's `max_age_days` (pure `Repricing.isStale`, already there) becomes `EXPIRED` with `decided_at` set, no `decided_by`. The check runs in a sweeper next to `AppliedPriceSweeper` (interval a platform technical setting), and also at the start of every `run`. `GET /admin/repricing/proposals?status=EXPIRED` lists them; `apply` of one answers `409 REPRICING_PROPOSAL_DECIDED` as any decided proposal does (the stale refusal remains for the moment between staleness and the sweep). Running again against a fresh sighting opens a new proposal, as today.
  2. **The floor is judged at write time (pricing-svc).** Both `apply` and the automatic apply re-read the list's current price for the variant and refuse a price below `floor_percent` of it: `409 PRICING_PROPOSAL_BELOW_FLOOR`, nothing written, the proposal stays open. A person may still dismiss it. This closes the gap where the price moved elsewhere between proposal and apply.
  3. **The automatic-apply limit (pricing-svc).** `repricing_rules` gains `auto_apply_max_change_percent` (numeric, nullable = off; 0 to 100 when set, `400 PRICING_INVALID_LIMIT` otherwise), settable on create and edit by OWNER/MANAGER. `run` applies, on the same transaction as opening it, a proposal that is fresh, at or above the floor, a **cut** (a proposal is never a rise) and whose change is within the limit, as `APPLIED` with `applied_by = SYSTEM` (stored as `decided_by` null and `decided_mode = AUTO`), writing through `writePriceListItemTx` with `PriceChanged` and price history exactly as a person's apply. Everything else is left `PROPOSED`. A rule with no limit applies nothing.
  4. **Scheduled runs.** A rule can be run by the schedule as well as by hand: `run_every_hours` (nullable = manual only) makes the sweeper run the rule; without it, automatic apply happens only when someone runs the rule, and the owner is told so on the screen.
  5. **The change is visible (Flutter, admin).** The rule form gains "Apply changes of up to __ % by itself" (empty = off, with the sentence "Everything else waits for you"), "Run every __ hours"; the proposals list has tabs Waiting, Applied (person or "Automatic"), Dismissed, Expired; an applied row names the rival and the old and new price.
  6. **A trail a person can read.** The price history row of an automatic apply names the rule and the rival (not a blank actor), and `PriceChanged` carries `source: REPRICING_AUTO`, so the till-side audit trail, which today reads order-svc's own logs, is not touched; the proposals list is the record.
- **Out, on purpose:**
  - **Raising prices automatically.** An automatic apply is only ever a cut towards a rival; a rise (or a proposal that would be one) always needs a person.
  - **A margin floor from cost.** pricing-svc holds no cost, so the floor stays a share of the price (as the CLAUDE.md rule says); a cost-based floor would need inventory's cost by a call and its own design.
  - **The markdown ladder and per-batch stickers of expiring stock.** Those are pricing-svc's `POST /markdowns` and [expired-and-short-dated-stock](expired-and-short-dated-stock.md) (the date watch, the ladder as a ceiling, `pricing.markdown-deep`). Repricing here follows a rival's price on a price list; a batch nearing its date follows the calendar. The two never write the same thing: a markdown sticker sits on a batch, a repricing proposal on a price-list item, and a line already reduced by a sticker is not repriced again at the till ([line-discounts-and-price-overrides](line-discounts-and-price-overrides.md)).
  - **Approval of a deep cut.** A deep markdown or near-zero price is the approvals page's (`pricing.price-set`); the automatic limit is the business's own choice of how deep it is willing to go unattended, and a rule with a big limit is the owner's decision. The floor remains the hard stop.
  - **Fetching rival prices.** Sightings are still recorded by people or an import; the platform fetches none.

## Data and flow

- **Owned by pricing-svc:**
  - `repricing_rules` gains `auto_apply_max_change_percent` (nullable numeric), `run_every_hours` (nullable int), `last_run_at`.
  - `repricing_proposals.status` gains `EXPIRED`; `decided_mode` (`PERSON`, `AUTO`, `SYSTEM`) added; `decided_by` stays null for automatic and expired ones. A decided proposal is never changed again.
- **Needs from other services:** nothing new. Currency and zone come from the rule's price list.
- **Events published:** `PriceChanged` on every apply, automatic or not (unchanged); consumers are unchanged and idempotent.
- **Retryable writes (Idempotency-Key):** `POST /admin/repricing/proposals/{id}/apply` and `…/run` (a replay answers with the first result; an automatic run twice in a row finds nothing left to apply).
- **New error codes:** `409 PRICING_PROPOSAL_BELOW_FLOOR`; `400 PRICING_INVALID_LIMIT` (reused for the new field).

## Money, time and limits

- **Currency:** the price list's own; the change percent is computed on the list's currency at its minor units.
- **Ledger postings:** none.
- **Dates:** observation age is counted in UTC days as today; `decided_at` UTC.
- **Plan limits:** none. (The limit here is the business's own rule setting, off until set; it is not a plan entitlement.)

## Constraints

- Golden rules 6 (event with the write), 7 (idempotent consumers of `PriceChanged`), 8 (proposals, once decided, are never edited), 12 (metrics on automatic applies per rule).
- "A person applies" in CLAUDE.md is amended, not broken: the person is whoever set the limit, having said in advance what may happen unattended, and everything beyond the limit still waits. CLAUDE.md's one line for repricing gets a clause naming this page.
- A rule's limit changed later never rewrites past applies.

## Open questions

- [x] How big may an automatic change be? → **No number set by the platform: the business sets the percentage on each rule, and with none set nothing applies itself** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can the rule ever raise a price by itself? → **No** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What happens to a stale proposal? → **It is closed as EXPIRED by the sweeper and at each run** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Floor against which price? → **The live price when the write happens** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] With no limit set, a run applies nothing, as before — `AutoRepriceIT.noLimitMeansNothingAppliesItself`
- [ ] A cut within the limit is applied by the run itself, `PriceChanged` published once, history written, recorded as automatic — `AutoRepriceIT.aSmallCutAppliesItself`
- [ ] A cut above the limit and any rise wait as PROPOSED — `AutoRepriceIT.aBigCutWaitsForAPerson`
- [ ] Nothing below the floor is written by a person or the rule when the price moved after the proposal: `409 PRICING_PROPOSAL_BELOW_FLOOR` — `AutoRepriceIT.theFloorIsJudgedAtWriteTime`
- [ ] The sweeper marks an aged proposal EXPIRED and it can no longer be applied (`REPRICING_PROPOSAL_DECIDED`) — `AutoRepriceIT.anAgedProposalClosesItself`
- [ ] The floor and the change size are pure and tested at the edges (exactly the limit applies, one minor unit over waits) — `RepricingAutoTest.*`
- [ ] A limit outside 0 to 100 is `400 PRICING_INVALID_LIMIT`; a CASHIER or STOREKEEPER cannot set it (403) — `AutoRepriceIT.theLimitIsValidatedAndGuarded`
- [ ] Another business's OWNER/MANAGER cannot see, run, set or apply our rules (404) and nothing moves — `AutoRepriceIT.anotherBusinessMovesNothing`
- [ ] A running twice or a replayed apply changes the price once — `AutoRepriceIT.aReplayChangesOnce`
- [ ] Two businesses with different currencies (yen, euro) round the change right — `RepricingAutoTest.minorUnitsFollowTheCurrency`
- [ ] Flutter: the limit field, the Applied/Expired tabs, "Automatic" label — `price_zones_tab_test.dart`
- [ ] End to end: sighting, run, automatic apply, till price changes — k6 `repricing-flow` (extended)

## Decisions

<!-- Filled while building. -->
- (2026-10-02, money at the currency's own minor units, industry standard) **`.99` is whole units less one hundredth, at the list currency's own scale**: `x.99` in a two-decimal currency and `x.990` in a three-decimal one — the hundredths a shopper reads, the third decimal nought, as Gulf shelf prices end (KD 1.990, BD 4.990); a price a fils short of a whole dinar (`x.999`) is no `.99` and cannot be paid in the coins that circulate. A currency without hundredths (the yen, the won) has no `.99` and is rounded down to its whole units. Always reached by rounding down and never through the floor; the floor, a `NONE` proposal and the change are kept to the list currency's minor units (`Fx.minorUnits`) — `RepricingTest.aDinarNinetyNineIsPointNineNineNought`, `.theEndingIsDefinedForEveryNumberOfMinorUnits`.
- (2026-10-02, money at the currency's own minor units) **An applied proposal is written at the list currency's own scale** (`RepricingService.appliedPrice`, the list read for its currency): the proposal columns keep four places (`NUMERIC(19,4)`) and pricing-svc's `V1__init.sql` leaves `price_list_items.price` unconstrained, so writing the proposal as read put `7.9900` / `1234.0000` / `8.9900` into the list, its history and every resolved price after it. A proposal's current, rival and proposed prices are also answered at the currency's units (a rival's price seen finer, such as fuel to a tenth of a penny, as kept). `409 REPRICING_NO_PRICE_LIST` if the list has gone — `CurrencyMinorUnitsTest.anAppliedProposalIsAtItsListCurrencysOwnScale`, `PriceZonesIT.anAppliedProposalIsWholeYenAndKeepsTheDinarsFilsAsStored`.
- (2026-10-06, money at the currency's own minor units, rule by rule) **What the business sets is refused when finer than its currency; what a rival was seen to charge is kept as seen.** Refused, `400 VALIDATION_FAILED` naming the field, never rounded: a repricing rule's `UNDERCUT_AMOUNT` value (the business's own undercut, a price it sets, held to its currency like a list price) and the `value` of a `FLAT`, `BASKET_FLAT`, `SPEND_THRESHOLD` or `MIX_MATCH` promotion (typed money, as [line-discounts-and-price-overrides](line-discounts-and-price-overrides.md) holds a spend threshold); no intent page allows either finer. A percentage (`UNDERCUT_PERCENT`, `PERCENT`, `BASKET_PERCENT`) is no money. Kept: a competitor price is an observation, recorded at the column's four places (`NUMERIC(19,4)`) whatever the business's currency, so `1.4599` in pounds is accepted and read back unrounded (the list answers a sighting at the scale the POST did: the currency's units, `8.50` pounds, `1250` yen, `8.500` dinars, or as seen when finer — `PriceZonesIT.aSightingIsListedAtTheScaleItWasAnsweredAt`), a fifth place (`1.45999`) is refused where the column would round it without a word, and so are more whole digits than the column holds and a price not above zero; one bad row refuses the whole import. An earlier pass refused a rival's price finer than the currency, which contradicted the decision above that a rival's price seen finer is kept — `CurrencyMinorUnitsTest.aRivalsPriceIsKeptAsSeenAtTheColumnsFourPlaces`, `PriceZonesIT.aRivalsPriceIsKeptAsSeenAndRefusedWhereTheColumnCannotHoldIt`, `.aRivalSeenToATenthOfAPennyDrivesAProposalAtWholePenceAndIsAnsweredAsSeen`, `.aRepricingRulesAmountIsNoFinerThanTheBusinessCurrencyButAPercentageIsNoMoney`.
