# Notification centre: what a shopper was sent, and the channel rules for notices

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on notification delivery and templates · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `mkt-notification-delivery` gap 2, case MKT-35; `mkt-message-templates` gap 1, case MKT-25; the notification channel and preference items deferred in `2026-09-30-iam-notification.md` item 4 |
| **Services** | notification-svc owns the log, the read state, the staff delivery view and the delivery metrics · the shopper's channel preferences, phone verification and SMS for order notices belong to [shopper-notices](shopper-notices.md) (this page first drew them too; see Decisions) · customer-svc is asked only for a customer's contact and marketing allowance (unchanged) · the app gives the shopper an inbox and staff a per-customer delivery view |
| **Builds on** | `notification_log` (`tenant_id` nullable, `event_id`, `type`, `channel`, `recipient`, `subject`, `body`, `status`, `subject_id`, `redacted_at`, `language`, `template`; unique `(event_id, type)`; a customer's erasure removes their rows through `CustomerErased`), `RetentionSweeper` and `RetentionRunRepository`, `OrderMessages.tell` (email plus push per order event, `ChannelsIT`), `AppChannel`/`MqttChannel`, `SendResource` (marketing sends ask `allowance`), `AdminResource` (staff delivery log), `template/Catalogue` and the message-template routes, `SmsChannel` and `SmsUsageRepository` (metered, `21.10`), `PasswordChangedHandler` (account emails kept with links removed, `tenant_id` null, invisible to every business's staff) |
| **Built in** | not yet built |

## Problem

A shopper who says "I never got that text" has nothing to look at and the shop has a staff-only log they must be asked to search. The shopper cannot see what the business sent them or when; disputes about a recall notice, an order message or a marketing text are one person's word against another's. Separately, three related choices were left open: whether order notices may go by SMS, whether a shopper can switch a channel off, and whether a rewritten message template must be reviewed before it goes live.

## Outcome

- **A shopper sees, in the app, a list of what a business sent them**: when, by which channel, what it said, and whether it went. Newest first, with an unread mark.
- **A member of staff with the right to can look up what was sent to one customer**, to answer "did you send it".
- **How a shopper chooses the channels of order notices, and text messages for them, are on [shopper-notices](shopper-notices.md)** (one owner for the preference model, the verified phone and the SMS allowance). This page reads whatever they decide: a skipped send is a log row the staff can see.
- **A rewritten template that carries legal weight is reviewed by a second person before it goes live** where the business turns that on, using the platform's one approvals mechanism.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **shopper** (reads, marks read, sets channels), the **manager** or **owner** (reads a customer's deliveries, sets SMS rules), the platform (nothing new).
- **Channels:** the storefront and the app's account area; the admin Customers screen.
- **Scope:** a shopper sees only what one business sent them, per business they have an account with (their login is per business in this platform); the log's `tenant_id` and `subject_id` decide.
- **Roles that can write:** the shopper for their own read state and preferences; OWNER for the business's SMS rule; staff read a customer's log with `customers.privacy` or MANAGER, and never see another business's.
- **Sandbox tenant:** a sandbox's notices are suppressed (logged `SUPPRESSED`) and the centre shows none of them to a shopper; staff see them marked as suppressed.

## Already there

- The staff delivery log (`AdminResource`), the channel list (`GET /admin/notifications/channels`), the order notices by email and push (`ChannelsIT`), the webhook fan-out fix (30 Sep, `WebhookIT.anEventWithNoEventIdIsQueuedFromItsKeyOnceAndOneWithNoKeyIsNotQueued`), the order-line short and substituted notices (`OrderLineShortClosedHandlerTest`, `OrderLineSubstitutedHandlerTest`). Nothing here redesigns them.
- [shopper-notices](shopper-notices.md) (written in parallel, reconciled 2026-09-30): **this page owns the shopper-visible log, its endpoints (`/notifications/me/**`), the read state, masking and the staff delivery view; shopper-notices owns the preference model, the verified phone, SMS for order notices and the wording of the substitution question.** Where they differed the answers are under Decisions.

## Scope

- **In (slices in build order):**
  1. **The shopper's own history (notification-svc).**
     - `GET /notifications/me?after=&limit=` (cursor; default 20, max 100; a signed-in shopper): rows where `subject_id` is the caller's login and `tenant_id` is the caller's business, newest first. Each item: `id`, `type` and a plain `title` in words (never the code), `channel`, `at`, `status` (`SENT`, `NOT_SENT` = a delivery failure or no transport, never `SUPPRESSED`), `subject`, `body`, `read`. The recipient is shown masked (`j***@example.com`, `+44 ••• 1234`), not in full.
     - **What is shown and what is not:** order, loyalty, stored-value and recall notices show subject and body. Account notices with `tenant_id` null (password reset, password changed, marketing confirmation) show that a message was sent and when, with **no body** and no link, because those rows belong to no business and their links are secrets already removed. A row already `redacted_at` shows as such.
     - `GET /notifications/me/unread-count` and `POST /notifications/me/read` (`{ upTo }` or `{ ids }`), with `notification_reads` (append-only: `tenant_id`, `subject_id`, `notification_id`, `read_at`) so the badge is right on every device.
  2. **Staff can answer "did you send it" (notification-svc).** `GET /admin/notifications/customers/{customerId}?after=&limit=` (OWNER, MANAGER, or `customers.privacy`): the same items with the full recipient, the channel, the provider status and the failure reason kept in the log. notification-svc reads the customer's login from customer-svc through `CustomerClient` (never a join) and only for the caller's business. A manager held to stores sees a customer's messages only where the message names one of their stores (order notices carry the store), otherwise all of the customer's non-order notices; never another business.
  3. **Channels and preferences: moved to [shopper-notices](shopper-notices.md) slices 1 and 3.** That page owns `notice_preferences` (two groups, a channel choice each, the notices that can never be switched off), the code-verified phone, the business's SMS switch and types, and `sms.per-month`. This page's first draft had a flatter model (push and SMS on/off, `sms_notice_types` with a phone "the shop already holds", email never off); it is replaced, because texting a number nobody verified is not industry practice and because a shopper may reasonably prefer push to email for a status update. What stays here: every handler that sends order, loyalty or stored-value notices asks `NoticeRouting` (shopper-notices) through `OrderMessages`, and a skipped send is logged `SKIPPED_BY_PREFERENCE` or `SKIPPED_NO_CONTACT` (staff-visible, not shopper-visible).
  4. **Template review (notification-svc, on the approvals page).** A rewritten template going live is the approvals action key **`notify.template-golive`** (see `intent/approvals.md`, designed by that page). This page adds only the hook: `PUT /admin/notifications/templates/{type}` for a type that carries legal weight (recall notices, receipts, privacy and dunning wording: a fixed list in the catalogue marked `regulated`) asks the approvals mechanism when the business has switched approvals on for it; the draft stays a draft and the old wording stays live until it is approved; every other type publishes as today. Nothing here designs a second approval mechanism.
  5. **Delivery health (notification-svc).** The finding about "skipped" warnings is closed by the 30 Sep fix; what remains is a metric that separates benign skips from failures: `notification_sends_total{type,channel,result}` with result `SENT`, `NOT_SENT`, `SKIPPED_NO_CONSENT`, `SKIPPED_BY_PREFERENCE`, `SUPPRESSED_SANDBOX`, `SKIPPED_NO_CONTACT`, exposed on the existing metrics endpoint; the exception-alert metric for a run of `NOT_SENT` is named `notifications.failures` on `intent/exception-alerts.md`.
  6. **Screens.**
- **Out, on purpose:**
  - **Showing the body of an account notice with a link, ever.** Links are secrets; the shopper sees that a message was sent, not what it linked to.
  - **Editing or deleting a log row.** It is the evidence; a shopper's erasure removes rows by the existing subject rule and nothing else does.
  - **SMS for marketing outside `allowance`**, and any channel a shopper has no consent for.
  - **Guest shoppers with no login.** A guest has no `subject_id`; their notices go to the address on the order and appear nowhere in an app they do not have.
  - **A push inbox that replaces the log.** Push and email stay delivery channels; the centre reads the one log.
  - **Cross-business inbox.** A login belongs to one business here; there is no shared inbox across businesses.

## Data and flow

- **Owned by notification-svc:** `notification_reads` (append-only; the one read-state table, shopper-notices' `notice_reads` is withdrawn); `notification_log.status` gains `SUPPRESSED`, `SKIPPED_BY_PREFERENCE`, `SKIPPED_NO_CONTACT` where a row is written for a skip worth showing staff; the index `(tenant_id, subject_id, created_at DESC)` for the shopper's read.
- **Endpoints:** as in the slices, all under `/notifications/me/**` (any signed-in shopper; the login and business are from the token, never the request; the preference and phone routes under it are shopper-notices') and `/admin/notifications/**` (management). Each `/notifications/me/**` route needs its entry in the shared filter (see `target/flow-catalogue/_notes/WAVE2-BUILD.md`, shared openings).
- **Needs from other services:** customer-svc for a customer's login and contact (REST, existing client). Nothing joined.
- **Events published:** none new. Consumers: unchanged, plus the erasure path already removes the shopper's rows.
- **Retryable writes (Idempotency-Key):** none needed; marking read and setting preferences are idempotent by nature.
- **New error codes:** `404 NOTIFICATION_NOT_FOUND` (a row that is not the caller's is a 404, never a 403). The preference and SMS codes are shopper-notices'.

## Money, time and limits

- **Currency:** none. **Ledger postings:** none.
- **Dates:** `at` in UTC; the app shows them in the device's own zone.
- **Plan limits:** none here; the new `sms.per-month` allowance is shopper-notices'.

## Constraints

- Golden rules 3 (tenant and subject come from the JWT), 8 (the log and the read marks are not edited), 12 (metrics), 15.
- **Privacy:** a shopper sees only their own rows; recipients are masked; account rows carry no body; a customer's erasure removes their rows and read marks (`CustomerErased`); the retention purge (`RetentionSweeper`) applies as today.
- **Location-neutral:** titles and words are in the shopper's language from the catalogue, English as fallback; phones read in the business's countries.
- Existing behaviour unchanged: order notices by email and push stay on for everyone; SMS is opt-in by the business.

## Open questions

- [x] **Is the log the shopper's inbox?** Recommended: yes, the one log, filtered by subject, with account rows shown as "sent" without body. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **May order notices go by SMS? Can a shopper turn email order notices off?** → **answered on [shopper-notices](shopper-notices.md), which stands:** texts only for types the business chose, to a code-verified phone the shopper opted in, never for marketing; status updates may move between channels or off, but the receipt, changes to what the shopper pays or receives, return decisions, refunds and security notices always reach at least one channel (email on record). This page first said "email never off"; the sharper rule stands because a shopper should be able to prefer push for a "ready for collection" ping (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Template review?** Recommended: the approvals page's `notify.template-golive`, on for the regulated types when the business turns it on. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A shopper reads only their own business's rows for their login, newest first, cursor-paged, with masked recipients; a body appears for order, loyalty, stored-value and recall notices and not for account notices; a suppressed sandbox row is not shown. — `NotificationCentreIT.aShopperSeesOnlyTheirOwn`, `accountRowsHaveNoBody`, `sandboxRowsAreHidden`
- [ ] Unread count and mark-read agree across two sessions. — `NotificationCentreIT.readStateIsPerLogin`
- [ ] Another business's shopper, and another shopper of ours, cannot read or mark our row (`404 NOTIFICATION_NOT_FOUND`); a customer's erasure removes their rows and marks; a guest sees nothing. — `NotificationCentreIT.anotherShopperTouchesNothing`, `erasureRemovesTheCentre`
- [ ] Staff answer "did you send it" for a customer of their business only; a cashier is refused (`403`); another business's owner naming our customer gets 404; a store-held manager sees only their stores' order notices. — `NotificationCentreIT.staffReadOneCustomer`, `onlyManagementReads`, `anotherBusinessTouchesNothing`, `storeHeldManagerSeesTheirStores`
- [ ] (SMS and preference behaviour: see shopper-notices' Acceptance, `SmsOrderNoticeIT` and `NoticePreferencesIT`.)
- [ ] A skip is logged with its reason and counted in `notification_sends_total{result}`. — `NotificationMetricsIT.skipsAreCountedByReason`
- [ ] A regulated template's rewrite stays a draft, the old wording live, until the `notify.template-golive` approval; other types publish at once; where approvals are off the rewrite goes live as today. — `MessageTemplatesIT.regulatedRewriteWaitsForApproval`, `otherTypesPublishAtOnce` (with the approvals page's tests)
- [ ] k6 `notification-flow` extended: a shopper's centre after an order; `flow-guard-*` green.
- [ ] Widget tests: `notification_centre_screen_test.dart`, `notification_preferences_test.dart`, `customer_deliveries_tab_test.dart`.

## Screens

- **Storefront/app, Account, Notifications:** a list (title, channel, time, unread dot), tap for the message; a bell badge in the shell; the preferences view is shopper-notices' and sits on the same screen.
- **Admin shell, Customers, a customer's "Messages" tab:** what was sent, channel, status, and why a send did not happen.
- **Admin, Settings, Notices:** the regulated templates and their review state (the SMS types card is shopper-notices').

## Decisions

- **Reconciled with shopper-notices (2026-09-30).** Endpoints are `/notifications/me/**` (this page's spelling; shopper-notices' `/notifications/mine` is withdrawn, as are its `notice_reads` and the `preview` and `customer_id` columns it wanted on `notification_log`: the log's `subject_id` is already the shopper login and its `body` already holds the words, with none kept for account rows). The preference model, verified phone, SMS switch and plan allowance are shopper-notices'. Loyalty and stored-value notices are an `ACCOUNT` group in that page's routing: email always, push by preference, never SMS.
- **The log is the inbox** (2026-09-30, industry standard): one source, one erasure path.
- **Account notices show as "sent" with no body** (2026-09-30): their links are secrets and their rows belong to no business.
- (2026-10-02, money at the currency's own minor units) **A message says money at its currency's own minor units through `Fx.minorUnits`, rounded half up** like every other figure on the platform (the formatter's own half-even said £2.34 for 2.345); a code that is not ISO 4217 is said as it came.
