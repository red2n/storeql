# Loyalty notices: tell a member before points lapse, and when their tier changes

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on loyalty · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `loy-tiers-and-expiry` gap 1, case LOY-28; the loyalty files' remaining open items (see Already there) |
| **Services** | customer-svc owns the lead time, the once-only notice record and the event · notification-svc words and sends it, in the business's catalogue · the app gives the owner the setting and the member the message |
| **Builds on** | `loyalty_programmes` (`expiry_months`, `qualifying_months`), `loyalty_point_lots` (`expires_at`, `remaining`), `LoyaltyExpirySweeper` → `LoyaltyProgrammeService.sweep` (publishes `LoyaltyExpired` once points are already gone and `LoyaltyTierChanged`, which nobody consumes), `myLoyalty().expiringSoon` (a fixed 30-day window on demand), notification-svc `template/Catalogue` and `OrderMessages`-style delivery (email plus push), `CustomerClient`, `TenantProfiles.Profile.sandbox()` |
| **Built in** | not yet built |

## Problem

A member's points die on a date and nothing tells them. The only warning is a field in the app's loyalty screen that the member must go and open, and it always looks 30 days ahead whatever the business wants. The first the member knows is a balance that is smaller. A tier drop is equally silent, and the tier change event that exists has no listener.

## Outcome

- **A member who has points about to lapse is told, once, ahead of time, with how many points and on what date.** How far ahead is the business's setting; off until it sets one.
- **A tier change is told too, in the business's words**, up or down.
- **The wording is the business's own** (rewordable in its catalogue, like an order notice), in the member's language.
- **The app's "expiring soon" uses the same lead time**, so the screen and the message agree.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** (sets the lead time, may reword), the **member** (receives), the manager (unchanged).
- **Channels:** email and push (the shopper's `ACCOUNT` notice group in [shopper-notices](shopper-notices.md): email always, push by the shopper's choice); never SMS.
- **Scope:** per business, per customer.
- **Roles that can write:** OWNER and MANAGER set the lead time with the rest of the programme (as `PUT /admin/loyalty/programme` does today), a manager only when held to no store (the programme covers every store: `403 BUSINESS_WIDE_ONLY` for a manager held to stores since 2 Oct 2026).
- **Sandbox tenant:** notification-svc suppresses every notice for a sandbox (logged `SUPPRESSED`); the events still flow.

## Already there

- **Reversal of points on a return or void:** built 29 Sep (`ReturnEventsConsumer`, `LoyaltyReversal`; `LoyaltyReversalIT.*`).
- **Manual earn and adjust idempotent and management-only; store credit issue management-only; redeem once per order:** built 30 Sep (`ManualGrantsIT.*`; see `target/flow-catalogue/_notes/2026-09-30-customer-cart.md`). Nothing to redesign.
- **Points expire in date order and the sweeper is rerunnable:** `LoyaltyProgrammeIT.lotsDieInOrder`.
- **The shopper can see what is expiring:** `myLoyalty().expiringSoon` (the window this page makes the business's).

## Scope

- **In (slices in build order):**
  1. **The lead time and the event (customer-svc).** `loyalty_programmes.notice_days` (nullable, off until set). The daily sweep, after expiring what is due, finds each customer whose earliest live lot expires within the lead time and who has not been told for that expiry date, writes one row in `loyalty_expiry_notices` (unique per business, customer and expiry date), and publishes one `LoyaltyExpiring` on the same transaction. **One notice per member per expiry date, listing the total that lapses that day**, not one per lot; a later date is a later notice. The event carries the amount at the moment of the sweep; the balance shown in the app is always live. A member with nothing left at the moment of the sweep is not warned. Never for a business with no programme expiry.
  2. **The notice (notification-svc).** `LoyaltyExpiringConsumer` and handler, once per `eventId`, to the member's own address by email and, where the login has a registered device, by push. A new catalogue message `LOYALTY_POINTS_EXPIRING` (with its words in the languages the catalogue already covers, English as the fallback) reading the points, the date and the business's name; the business may reword it under the existing template rules (approval, if the business turns approvals on for templates, is the `notify.template-golive` action on `intent/approvals.md`). It is an account notice about the member's own balance, not marketing: no `MARKETING` consent is needed, but it is never sent to an anonymised customer or to a member whose LOYALTY purpose is withdrawn (where a per-purpose law binds, `Jurisdictions`). Kept in `notification_log` against the customer (`subject_id`), so a customer's erasure removes it.
  3. **Tier change notice.** `LoyaltyTierChanged` gains what notification-svc needs (the new tier's name, the old one, direction, the threshold the member is now near or below), and a catalogue message `LOYALTY_TIER_CHANGED` (up and down as two forms, so the words can differ) is sent once per event, same channels and rules. A business can switch tier notices off (`loyalty_programmes.tier_notice` default on for a business that sets a notice lead time; otherwise off until the owner turns it on), so no business's members start receiving new mail without a choice being made.
  4. **The app's window follows the setting.** `myLoyalty().expiringSoon` uses `notice_days` when set (its own 30 days otherwise, as today) so the screen and the mail agree.
  5. **Screens.**
- **Out, on purpose:**
  - **SMS for loyalty notices.** Texts are metered and need a verified phone and opt-in; the answer is on [shopper-notices](shopper-notices.md) (only order notices may be texted, and only types the business chose): a loyalty notice is never a text.
  - **A "points earned" message on every sale.** The receipt already shows it; a mail per sale is marketing volume nobody asked for.
  - **Extending expiry to save points.** A manager's manual adjust with a reason already exists.
  - **A run of reminders.** One notice per expiry date; a second nag is a business's marketing choice.

## Data and flow

- **Owned by customer-svc:** `loyalty_expiry_notices` (append-only: id, `tenant_id`, `customer_id`, `expires_on` (the UTC day), `points`, `noticed_at`, unique on the first three); `loyalty_programmes` gains `notice_days` (1 to a maximum equal to the programme's own expiry, checked) and `tier_notice`. Existing programmes: both null/off.
- **Owned by notification-svc:** the two catalogue messages; the log rows (type `LOYALTY_EXPIRING`, `LOYALTY_TIER_CHANGED`).
- **Endpoint change:** `PUT /admin/loyalty/programme` accepts `noticeDays` and `tierNotice`; refusal `400 LOYALTY_NOTICE_INVALID` (zero, negative, or longer than the programme's expiry).
- **Needs from other services:** notification-svc reads the member's contact and language through `CustomerClient`; nothing is joined.
- **Events published:** `LoyaltyExpiring` (`storeql.customer.loyalty-expiring`: `eventId`, `tenantId`, `customerId`, `points`, `expiresOn`, `tierName`?), consumer notification-svc; `LoyaltyTierChanged` (existing topic, enriched), consumer notification-svc; both also on the webhook catalogue for a business's own systems.
- **Retryable writes (Idempotency-Key):** none (the programme PUT is idempotent).
- **New error codes:** `400 LOYALTY_NOTICE_INVALID`.

## Money, time and limits

- **Currency:** none (points). **Ledger postings:** none; expiry itself is unchanged and its deferred-revenue effect stands.
- **Dates:** the lot's `expires_at` in UTC; the notice shows the date in the member's business's home time zone (the store's zone at the member's usual store is not assumed); the sweep runs daily.
- **Plan limits:** none.

## Constraints

- Golden rules 6 and 7 (once per member per expiry date by a unique row; a consumer once per event), 3, 8.
- Location-neutral: words come from the catalogue in the member's language; no time zone or wording assumed.
- No number is assumed: the lead time is the business's; unset means no warning and the app keeps its 30 days.
- The consumer must not send to a login whose customer is erased or anonymised; the row is subject-tagged so erasure removes it.
- Existing tenants: nothing changes until an owner sets a lead time.

## Open questions

- [x] **One notice per lot, or per date?** Recommended: per member per expiry date, totalled. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Does it need marketing consent?** Recommended: no; it is an account notice, not marketing, but it honours a withdrawn LOYALTY purpose. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which channels?** Recommended: email and push, as order notices. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Tier-change mail on or off?** Recommended: off until the owner turns it on. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] With `notice_days` unset nothing is announced; set, a member with points expiring inside it is announced once however often the sweep runs. — `LoyaltyNoticeIT.offUntilSet`, `oneNoticePerMemberPerDate`
- [ ] A member with points expiring on two dates gets one notice for the first only until the second falls inside the lead time. — `LoyaltyNoticeTest.groupsByExpiryDate`
- [ ] A member with nothing left at the sweep is not warned. — `LoyaltyNoticeIT.nothingLeftNoNotice`
- [ ] The member is emailed once, with the points, the date and the business's name; push follows where a device is registered; a sandbox's is suppressed. — notification-svc `LoyaltyExpiringHandlerTest.aMemberIsToldOnce`, `sandboxIsSuppressed`, `ChannelsIT.loyaltyNoticeGoesByPushToo`
- [ ] A reworded template is used; an anonymised customer or a withdrawn LOYALTY purpose is not sent to. — `LoyaltyExpiringHandlerTest.theBusinessWordsWin`, `anErasedMemberHearsNothing`
- [ ] A tier change, up and down, is announced with its own wording when `tier_notice` is on, and never when off. — `LoyaltyNoticeIT.tierNoticesOnlyWhenOn`; `LoyaltyTierChangedHandlerTest.*`
- [ ] The app's expiring-soon window equals `notice_days` when set. — `LoyaltyProgrammeIT.expiringSoonFollowsTheNoticeDays`
- [ ] `notice_days` longer than the programme's expiry is `400 LOYALTY_NOTICE_INVALID`. — `LoyaltyNoticeIT.noticeMustFitTheExpiry`
- [ ] Another business's event or staff touches nothing of ours; a shopper cannot set the lead time (`403`). — `LoyaltyNoticeIT.anotherBusinessTouchesNothing`, `onlyManagementSetsIt`
- [ ] k6 `loyalty-flow` extended: set the lead time, earn, run the sweep, see one notice log row.
- [ ] Widget test `loyalty_programme_card_test.dart` (the new fields).

## Screens

- **Admin shell, Loyalty programme card:** "Warn members before points lapse: __ days before" (empty = off) and "Tell members when their tier changes" (a switch), with a plain line saying what the message will read like and where to reword it (Templates screen).
- **Storefront, My loyalty:** the "expiring soon" line uses the business's lead time.

## Decisions

- **Account notice, not marketing** (2026-09-30, industry standard): sending the member a warning about their own points needs no `MARKETING` consent; it does respect a withdrawn `LOYALTY` purpose.
- **The hand-run sweep needs a caller held to no store** (2026-10-02, industry standard, built): `POST /admin/loyalty/expiry/run` expires points and re-tiers the customers of every store, so, like setting the programme, it answers `403 BUSINESS_WIDE_ONLY` to an owner or manager held to stores, checked before anything is read or written (one `requireBusinessWide` in `LoyaltyProgrammeService` for both). An owner, a business-wide manager or the platform runs it, for its own business only; below management the role refusal (`403 FORBIDDEN`) comes first. This replaces the first build's "a store-held manager may run the sweep the hourly job would run": that job is not a caller, and a person held to some stores should not write off points at the others. The hourly sweeper is unchanged. — `LoyaltyProgrammeServiceTest.*`, `LoyaltyProgrammeIT.theHandRunSweepNeedsACallerHeldToNoStore` *(3 Oct 2026, checked against the code: the sweep has no body, so its scope is judged first; on the programme PUT the body is validated before the scope.)*
- **An erased customer is given no points by hand** (2026-10-02, industry standard, built): a manual earn or adjustment, up or down, for an erased (anonymised) customer is `409 CUSTOMER_ANONYMIZED`, a retry under an earlier key included, and writes nothing (no lot, ledger entry, grant or event). It is checked on the service and again on the write's own transaction with the customer's row held `FOR SHARE`, so an erasure cannot land in between. **The code is `CUSTOMER_ANONYMIZED`, not the `CUSTOMER_ERASED` the round-3 plan named:** customer-svc already answers every write to an erased record with `409 CUSTOMER_ANONYMIZED` (`PUT /customers/{id}`, `PUT /customers/{id}/marketing`, k6 `privacy-flow`), and one state keeps one stable code so no client maps two. A sale's own points were already skipped for an erased customer (`accrueFromOrderOnce`). — `ManualPointsErasedCustomerTest.*`, `ManualGrantsIT.anErasedCustomerIsGivenNothingByHand`
