# Shopper notices: the shopper's own channels, a notification centre, and asking before a substitute

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | new: the flow catalogue's open findings, wave 2 — online/notifications-to-shopper (channels, preferences), online/substitutions-and-short-lines (consent per substitution) |
| **Services** | notification-svc owns notice preferences, phone verification and SMS use (the shopper-visible log and its read state are [notification-centre](notification-centre.md)'s) · order-svc owns the substitution proposal and the shopper's choice at checkout · customer-svc keeps the shopper's marketing consent (unchanged, kept apart) · the app gives the shopper Account → Notifications |
| **Builds on** | notification-svc `OrderMessages.tell`, `Catalogue` (`ORDER_CONFIRMED`, `ORDER_READY_FOR_COLLECTION`, `ORDER_DISPATCHED`, `ORDER_LINE_SHORT`, `ORDER_LINE_SUBSTITUTED`), `SmsChannel`, `PushChannel`, `push_devices`, `notification_log`, `MarketingConsentClient`; customer-svc consent purposes; order-svc `orders.allow_substitutions`, `OrderRepository.adjustLine`, `SubstitutePrice`, `order_line_adjustments`; `intent/substitutions-for-out-of-stock-online-lines.md` |
| **Built in** | |

## Problem

- **Order notices go by email, and by push to a registered device; that is all.** A shopper waiting for a delivery cannot choose the phone, and a business cannot text "ready for collection" even where the shopper would love it.
- **A shopper has no say over order notices.** They cannot stop a push while keeping the email, and there is no statement of which notices are always sent and which can be turned off. Marketing consent is already governed apart; order notices have no home.
- **The shopper has no record.** Once an email is deleted or a push swiped away, nothing in the app lists what the shop told them.
- **Substitution is all or nothing.** The shopper says at checkout "allow substitutions" once; some shoppers want to be asked about each one.

## Outcome

- A shopper picks, per kind of notice, where it reaches them: email, push, and (where the business offers it and the shopper has verified a phone) text.
- Which notices can never be switched off is stated in the app and enforced by the server: what changes what they pay or receive, and their receipt, always reaches them by at least one channel.
- The app lists every notice the shop sent them, newest first, with what it said, by which channel, and whether it was delivered; tapping opens the order.
- A shopper who asked to be asked gets a notice for each proposed substitute, with the picture, the price (never more than the original) and Accept / No thanks; no answer in time closes the line short. Nothing is ever substituted for a shopper who asked to be asked without their yes.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **shopper** (preferences, centre, substitution answers); the **picker / store staff** (propose a substitute and see the answer); the **owner** (turns SMS and asking on, sets the answer window).
- **Channels:** ONLINE storefront and its notices. Not staff notices, not marketing (own gate), not the platform's account mail (password reset stays email only by its own page).
- **Scope:** per shopper login at a business (a shopper who shops at two businesses has two sets, since a business's notices are its own); business switches per business.
- **Roles that can write:** the shopper for their own preferences (role CUSTOMER, own login only); OWNER and MANAGER for the business switches; store staff propose substitutes as today.
- **Sandbox tenant:** notices are logged `SUPPRESSED`, nothing leaves but in-app (today's rule); the centre still lists them, marked as sandbox; proposals work fully.

## Scope

- **Already there (verified 2026-09-30):**
  - Short and substituted notices exist and are tested: `OrderLineShortClosedHandler` / `OrderLineSubstitutedHandler` (types `ORDER_LINE_SHORT`, `ORDER_LINE_SUBSTITUTED`), `OrderLineShortClosedHandlerTest.theShopperIsToldOnceWhatTheyWillNotGetAndWhatGoesBack`, `OrderLineSubstitutedHandlerTest.theShopperIsToldOnceWhatReplacedWhatAndTheDifferenceBack`. The catalogue finding that they were missing was unverified and is stale.
  - Every order notice (confirmed, ready, dispatched, short, substituted) already goes by EMAIL and, once per event, by PUSH when the login has a device (`OrderMessages.tell`, `ChannelsIT`).
  - `SmsChannel` exists (E.164 recipient, length guard, the deployment's `SmsProvider`, and `SmsUsageRepository` metering each text to the business through tenant-svc, 21.10).
  - Marketing consent is its own purpose with its own gate (`allowance`, `PURPOSE_WITHDRAWN`); this page never touches it.
- **In (slices in build order):**
  1. **Preferences and the routing rule (notification-svc, app).** `notice_preferences`; pure `NoticeRouting` decides the channels for a notice from the shopper's choices and the mandatory set; `OrderMessages.tell` asks it. Email and push only in this slice.
  2. **The notification centre: owned by [notification-centre](notification-centre.md) slice 1** (list, unread count, mark read, masking, account rows shown without a body). This page adds only that the routing of slice 1 writes the log rows it reads, and that the app opens it from Account and from a push.
  3. **Text messages for order notices (notification-svc, tenant-svc, app).** Phone verification by a one-time code, a business switch, a plan allowance, SMS as a third channel in `NoticeRouting`.
  4. **Asking before each substitute (order-svc, notification-svc, app).** The `ASK` choice, the proposal, the answer, the window, the outcome.
- **Out, on purpose:**
  - **Turning off a receipt or a change notice.** A confirmation that is the shopper's receipt, a short-close or substitution (it changes what they get and what they are refunded), a return decision or a refund must reach them: they may pick the channel, never zero channels. Recorded in the answer to the first open question.
  - **Per-notice-type toggles beyond the two groups below.** Two groups (status updates; changes) with a channel choice each is what shoppers understand and what a business can explain.
  - **Marketing preferences here.** They stay under customer-svc's purposes and the marketing screens.
  - **WhatsApp or other chat channels, and voice.** Each needs a provider agreement and templates; the `NotificationChannel` interface admits them later.
  - **Guest shoppers' preferences.** A guest has no login: they get email at the address they gave, as today, with no centre.
  - **Reading a shopper's centre from the back office.** Staff see the business's log as today (`notification_log`); the centre is the shopper's own view.
  - **Auto-substituting when the shopper asked to be asked** (already excluded by the substitutions page): no answer means short close, never a silent swap. Substituting on a wave's pick list stays excluded too.

## Data and flow

- **Owned by notification-svc:**
  - `notice_preferences`: tenant, login (the shopper login, from the JWT), group STATUS (ready for collection, dispatched), CHANGES (short, substituted, cancelled by the shop after picking began ([fulfilment-overrides](fulfilment-overrides.md) `ORDER_CANCELLED_BY_SHOP`), return decisions) or ACCOUNT (points and stored-value expiry, [loyalty-notices](loyalty-notices.md) and [stored-value-lifecycle](stored-value-lifecycle.md): email always, push by choice, **never SMS**), channel EMAIL / PUSH / SMS, enabled, changed at. Absent row = the defaults: EMAIL and PUSH on, SMS off. The receipt (`ORDER_CONFIRMED`) and refunds go to the email on record whatever is chosen. Every change is also an append-only row in `notice_preference_log` (who, when, from, to, source: the app), which is the record of an SMS opt-in.
  - `verified_phones`: tenant, customer, `phone_e164` (read through `PhoneNumbers` in the business's own countries, first valid reading), verified at; `phone_verifications`: code hash (never the code), attempts, expiry, request count (rate-limited like password-reset's links).
  - **No new columns on `notification_log` and no `notice_reads` here:** the log's `subject_id` is already the shopper login and its `body` the words (none kept for account rows), and the one read-state table is `notification_reads` on [notification-centre](notification-centre.md). (This page first proposed `preview`, `customer_id` and `notice_reads`; withdrawn.)
  - `sms_settings` (per business): `order_notices` (off by default: texts are metered to the business) and `types`, the subset the owner allows of READY, DISPATCHED, SHORT, SUBSTITUTED and the substitution question (empty until the owner sets it; never the receipt, never marketing), and the plan allowance below. This absorbs [notification-centre](notification-centre.md)'s `sms_notice_types`, and covers the confirmation texts of [consent-evidence](consent-evidence.md), which are metered the same way.
- **Owned by order-svc:**
  - `orders.substitution_mode`: NONE, ALLOW (today's `allow_substitutions` = true), ASK. The boolean stays for existing readers and is derived (`ALLOW` or `ASK` = true where substitutes may be proposed); the mode is read from the order, never from the request after checkout.
  - `substitution_proposals`: id, tenant, order, item (the original line), substitute variant, proposed unit price and the price the shopper would pay (from pure `SubstitutePrice`, computed when proposed), status PROPOSED / ACCEPTED / DECLINED / EXPIRED, proposed by, `expires_at`, answered at and by. Append-only in effect: one status move, recorded in `order_line_adjustments`' style history; never deleted.
  - `substitution_settings` (per business): `ask_enabled` (off), `ask_window_minutes` (null = asking cannot be enabled: the window is required), `on_no_answer` = SHORT_CLOSE (the only value in this cut).
- **Needs from other services:** notification-svc reads the shopper's verified phone and languages from its own tables and the shopper's login link and email from customer-svc (existing `CustomerClient`); order-svc reads the business's switch through its own settings; the app reads product images from product-svc as today.
- **Events published:** `OrderLineSubstitutionProposed` (order-svc, `storeql.order.order-line-substitution-proposed`: tenant, order, customer, proposal, original variant/name, substitute variant/name, price, the difference, expires at; notification-svc sends `ORDER_SUBSTITUTION_QUESTION` once per event by the shopper's CHANGES channels); `OrderLineSubstitutionAnswered` (accepted or declined or expired) is folded into the existing `OrderLineSubstituted` / `OrderLineShortClosed` that the adjustment publishes, so inventory-svc, payment-svc and the rest change nothing. notification-svc emits nothing new.
- **What a proposal does to the order:** while a proposal is PROPOSED the original line stays outstanding: the order is PARTIALLY_FULFILLED (or waits FULFILLED), a delivery is not dispatched (dispatch needs FULFILLED, already the rule), and other lines are picked and fulfilled as normal. ACCEPTED runs the existing `adjustLine` substitute path (priced at the order's store, capped at the original's gross unit price, inserted picked, the difference refunded once). DECLINED, or EXPIRED by a sweep at `expires_at`, runs the existing short-close (refund pro rata). The picker sees the state on the Fulfilment screen and can withdraw a proposal (`409 ORDER_SUBSTITUTION_ANSWERED` once answered).
- **Retryable writes (Idempotency-Key):** the shopper's answer (`POST /orders/{id}/substitution-proposals/{pid}/answer`: a replay answers the first), the picker's proposal, the phone-verify request (rate-limited, not keyed), preference `PUT`s (naturally repeat-safe).
- **Endpoints:** `GET/PUT /notifications/me/preferences` (CUSTOMER, own); `POST /notifications/me/phone/verify`, `POST /notifications/me/phone/confirm {code}`, `DELETE /notifications/me/phone`; the list, unread count and mark-read routes are notification-centre's `/notifications/me`, `/notifications/me/unread-count`, `/notifications/me/read`; `GET/PUT /admin/notifications/sms-settings` (OWNER/MANAGER); order-svc `POST /orders/{id}/lines/{variantId}/substitution-proposals` (picker: staff at the order's store), `GET /orders/{id}/substitution-proposals` (shopper of the order; staff at the store), `POST .../{pid}/answer` (the shopper, needs `POST /orders/{uuid}/substitution-proposals/{uuid}/answer` opened in the shared filter for a shopper, like the cancel route wave 1 found), `GET/PUT /admin/orders/substitution-settings`. The checkout body carries `substitutionMode` (`allowSubstitutions` still accepted and mapped).
- **New error codes:** `400 NOTIFICATION_PREFERENCE_INVALID` (an unknown group or channel); `409 NOTIFICATION_CHANNEL_REQUIRED` (a change that would leave a mandatory notice with no channel); `409 NOTIFICATION_SMS_NOT_OFFERED` (the business has SMS off); `409 NOTIFICATION_PHONE_NOT_VERIFIED`; `400 NOTIFICATION_CODE_INVALID` / `409 NOTIFICATION_CODE_EXPIRED` / `429 NOTIFICATION_CODE_TOO_MANY`; `409 ORDER_SUBSTITUTION_ASK_OFF` (the business has not turned asking on when checkout names ASK: the checkout is refused with a message, not silently downgraded); `409 ORDER_SUBSTITUTION_NOT_ASKED` (a proposal on an order that is ALLOW or NONE: the picker uses the ordinary substitute); `409 ORDER_SUBSTITUTION_PENDING` (a second proposal for the same line); `409 ORDER_SUBSTITUTION_ANSWERED`; `404 ORDER_SUBSTITUTION_NOT_FOUND` (also another shopper's or business's).

## Money, time and limits

- **Currency:** the proposal shows the price in the order's currency; the difference is refunded by the existing path. Nothing is charged more.
- **Ledger postings:** none new (an accepted substitute and a short-close post as they do today).
- **Dates:** `expires_at` = proposed at plus the business's window, UTC; the notice shows it in the shopper's language and the store's zone (never a zone assumed).
- **Plan limits:** new entitlement `sms.per-month` (texts sent a calendar month), added to `Plans.CATALOGUE` and refused by notification-svc, which owns the sending, as it would leave the count; over the allowance the SMS is `SUPPRESSED` with reason `PLAN_LIMIT` and the email/push still goes (an order notice is never lost to a text limit). Fail-open when the allowance cannot be read, as every limit. Cost per text is the business's provider's; not modelled.

## Constraints

- **Golden rules:** 3 (tenant and login from the JWT; a preference names neither); 6/7 (proposal and its consequences through the outbox; notices once per event id); 8 (logs, reads, proposal history append-only); 10; 15.
- **Consent kept apart:** SMS for order notices rests on the shopper's own opt-in and a verified phone (the preference log is the record), **not** on marketing consent; withdrawing MARKETING neither switches order texts off nor may a marketing message ride on an order text (the template catalogue has no marketing form in these types).
- **Location-neutral:** numbers are read through `PhoneNumbers` in the business's own countries; language is the shopper's own, the platform's fallback otherwise; no country's SMS sender rule is assumed: the deployment's `SmsProvider` carries any sender registration its carrier needs (the platform's operator, not this page, answers for it). The mandatory set is a *platform* rule about what changes what a person pays or receives, not a statute.
- **Existing tenants and shoppers:** nothing changes on the day this ships (email and push on, SMS off, ASK unavailable). Existing notices, preferences and orders are untouched; `allow_substitutions` keeps its meaning.
- **Volumes:** the centre is cursor-paginated and index-led by (tenant, customer, time).

## Open questions

- [x] **Can order notices be switched off?** Recommended: status updates may be moved between channels or off; the receipt, changes to what they pay or receive, return decisions, refunds and security notices always reach at least one channel. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **What consent does an order text need?** Recommended: the shopper's own opt-in in the app plus a code-verified phone; recorded; kept apart from marketing. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Who pays for texts?** Recommended: the business (each text is metered to it, 21.10, and limited by a plan allowance), sent through the deployment's provider, off until the owner enables it; the shopper is never charged. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **What if no answer to a substitution question?** Recommended: close the line short after the business's window, refund pro rata; never substitute unasked. → **short close** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Can asking be on without a window?** Recommended: no; the owner must set the window, so a shopper is never left waiting with no end. → **window required** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Limit on substitutions per order?** Recommended: none by count (a substitute stands in for one short line, and the price cap and the shopper's own choice are the controls); a picker who substitutes unusually often is an exception-report matter, metric `substitutions.count` for the exception-alerts page. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Where does the centre's text come from?** Recommended: the rendered message with links removed, kept on the log row; deleted by the existing retention sweep. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] With the defaults a shopper is told by email and push as today; turning push off for STATUS stops the push for a ready or dispatched notice and nothing else. — notification-svc `NoticeRoutingTest` (pure), `NoticePreferencesIT.pushOffStopsStatusPushOnly`
- [ ] The receipt and a short or substituted notice reach the email on record even with every channel switched off for that group; the change that would leave none is refused `409 NOTIFICATION_CHANNEL_REQUIRED`. — `NoticePreferencesIT.mandatoryNoticesAlwaysReach`, `NoticeRoutingTest.neverZeroChannels`
- [ ] Every preference change is on the append-only log; withdrawing marketing changes no order-notice preference. — `NoticePreferencesIT.changesAreLogged`, `marketingWithdrawalLeavesOrderNoticesAlone`
- [ ] The centre (notification-centre's, tested there: `NotificationCentreIT.aShopperSeesOnlyTheirOwn`, `accountRowsHaveNoBody`, `readStateIsPerLogin`, `anotherShopperTouchesNothing`) lists what this page's routing sent, with no link or secret; a guest and another shopper see none.
- [ ] A phone is verified by a code (hashed, expiring, attempt-limited, rate-limited); an unverified phone never receives a text; a number is read in the business's own countries. — `PhoneVerificationIT.*`, `SmsChannelIT.anUnverifiedPhoneIsNeverTexted`
- [ ] With SMS off for the business, choosing SMS is refused `409 NOTIFICATION_SMS_NOT_OFFERED`; on, the allowed types go by text once per event; over `sms.per-month` the text is `SUPPRESSED` and the email still goes; an unreadable allowance lets it through; the sandbox sends nothing. — `SmsOrderNoticeIT.*`, `PlanIT` lists `sms.per-month`
- [ ] With ASK, a picker's proposal notifies the shopper and holds the line; Accept substitutes at no more than the original's price and refunds the difference once; No thanks or the window's end short-closes and refunds pro rata; the order is FULFILLED only when every line is picked or closed; a delivery is not dispatched while a proposal stands. — order-svc `SubstitutionProposalIT.acceptSubstitutes`, `declineShortCloses`, `noAnswerShortCloses`, `aStandingProposalHoldsFulfilled`, `refundedOnce`; pure `SubstitutionProposalsTest`
- [ ] A proposal on an ALLOW or NONE order is refused `409 ORDER_SUBSTITUTION_NOT_ASKED`; asking without the business's switch or window is refused at checkout `409 ORDER_SUBSTITUTION_ASK_OFF`; a second proposal for a line, or an answer after the first, is refused; a retried answer answers with the first. — `SubstitutionProposalIT.refusalsAreCoded`, `aRetriedAnswerAnswersWithTheFirst`
- [ ] Another shopper, another business's staff of every role, and a store-held caller at another store cannot see, propose or answer (404/403), and nothing moves. — `SubstitutionProposalIT.otherShopperAndOtherBusinessFindNothing`; the consumers `SubstitutionQuestionHandlerTest.anotherBusinessesEventTellsNobody`
- [ ] Account → Notifications shows the two groups and the channel choices with the always-on notices stated; the centre lists and opens an order; the substitution sheet shows original, substitute, price and the deadline. — widget tests `notice_preferences_test.dart`, `notification_centre_test.dart`, `substitution_question_sheet_test.dart`, `checkout_substitution_mode_test.dart`
- [ ] End to end: k6 `shopper-notices-flow`; `flow-guard-comprehensive` and `flow-guard-runtime` green.

## Decisions

<!-- Filled while building. -->
- (Recorded now) **This page replaces one line of "Out, on purpose" in `intent/substitutions-for-out-of-stock-online-lines.md`**, which read: "a shopper approving or refusing a swap in the app before handover: the choice is per order at checkout, and refusal is at handover. An in-app 'refuse before it ships' is a later refinement." That text stays; slice 4 is that refinement, offered as a third checkout choice (ASK) so the blanket ALLOW and NONE behave exactly as built. Refusal at handover and "the picker decides" for ALLOW orders are unchanged.
- (2026-09-30, reconciled) **Slice 2 and the endpoint spellings belong to [notification-centre](notification-centre.md);** its `/notifications/me/**` and `notification_reads` stand. This page's preference model stands over that page's flatter one (channel choice per group, mandatory notices reach at least one channel). Whichever page is built first builds the shared `OrderMessages` routing hook; the other adds only its own rows.
- (Recorded now) **The catalogue's "no explicit handler for short/substituted" is stale** (handlers exist and are tested), and "SMS/push not confirmed" is half true: push exists, SMS did not and is slice 3, which wave 1 (iam-notification note, item 4) left as a product decision now settled.

## Screens

- **Storefront, Account → Notifications:** two groups (Order updates; Changes to your order) each with Email / Push / Text switches; a line stating what is always sent; *Add a phone number* → code entry; below them the list of notices with an unread dot and a tap through to the order (notification-centre's list); a push opens the same order.
- **Storefront, order:** the substitution sheet (a card per proposal with the original, the offered item and price, "You will not pay more than …", the time left, Accept / No thanks). The checkout offers *Ask me about each swap* only when the business enabled it.
- **Admin, Fulfilment:** a *Suggest a substitute* action on an ASK order's short line (existing suggestions), the proposal's state and a withdraw action.
- **Admin, business settings:** an *Order texts* card and a *Substitution questions* card (on/off and the window).
