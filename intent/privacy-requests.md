# Privacy requests: prove who is asking, count the days, keep a correction as one, act across services

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on privacy rights · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `cus-privacy-rights` gaps 1 to 3, cases CUS-36, CUS-37 |
| **Services** | customer-svc owns requests, identity checks, the due date and the rectification trail · tenant-svc owns the register of the period each law allows · notification-svc reminds the business and tells the customer of an extension · order-svc, cart-svc, iam-svc and notification-svc each export and erase what they hold · the app gives staff the queue and the request screens |
| **Builds on** | `privacy_requests` (kind ACCESS, CORRECTION, ERASURE, NOMINATION, GRIEVANCE; `due_on`; `status`), `privacy_settings.response_days` (1 to 90, default 30), `Privacy.dueOn`, `PrivacyService.open`/`resolve`, `MyPrivacyResource` (shopper opens a request), `GET /customers/{id}/export` and `DELETE /customers/{id}` (`customers.privacy` permission), `CustomerService.export` (profile, addresses, loyalty, store credit, orders through `OrderClient`; fails closed 503 `EXPORT_ORDERS_UNAVAILABLE`), `CustomerErased` (order-svc and notification-svc consume it), `Jurisdictions` and tenant-svc's `legal_obligations` (`GDPR` for the EU regime, `UK_GDPR` for GB, `DPDP`), `RetentionPurgeService`, `guardian_consents.reference` ("never a document number") |
| **Built in** | not yet built |

## Problem

A person can phone or write to a shop and say "give me everything you hold on me" or "delete me". Today whichever staff member has `customers.privacy` runs the export or the erasure on their own judgement; nothing records that the caller was who they said, so the shop can hand a stranger someone's full history, or erase the wrong person's account. The one-month answer the UK and EU laws give is not tracked at all: the queue and the due date are built around India's DPDP period, and a shop outside it has no countdown. A correction is an ordinary edit, so no one can show that a change was made because the person asked. And an export or an erasure reaches only some of the services that hold the person's data.

## Outcome

- **No export or erasure of a person is done for someone who has not been checked.** A request opened at the counter, by phone or by letter is checked first; what was checked and by whom is kept, never a copy of an identity document.
- **Every request has a due date and a countdown, from the law that binds the business.** The queue shows what is due first, what is overdue, and the business is reminded before a date passes.
- **A correction is a correction.** The trail says who asked, who changed which fields, and when; it holds no old or new personal values.
- **One customer's export and erasure reach every service that holds them**, and the business can show which services finished.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store **manager** or **owner** (holds `customers.privacy`, works the queue), the **shopper** (asks, in the app or outside it), the platform administrator only for the platform's side (see the platform privacy page; not this one).
- **Channels:** back-office. A shopper's own request from the app stays as built.
- **Scope:** per business; a request is about one customer of that business.
- **Roles that can write:** `customers.privacy` holders (OWNER, MANAGER, PLATFORM_ADMIN). Any staff may open a request on a customer's behalf and record that it came by phone or letter, but only a `customers.privacy` holder verifies, exports, erases or resolves.
- **Sandbox tenant:** behaves the same.

## Scope

- **In (slices in build order):**
  1. **The due date from the law (tenant-svc, customer-svc).**
     - tenant-svc's register gains the length of a law's answering period: `legal_obligations` rows `DATA_REQUEST_PERIOD` (a number and its unit) and `DATA_REQUEST_EXTENSION`, seeded for the regimes and countries already in the register whose law states one: the EU regime (one month, extendable by two months) and GB (the same). Where a law names no period (`DPDP` leaves it to the business, at most ninety days) there is no row and the business's own `response_days` stands. This slice adds the `limit_value` and `limit_unit` columns to `legal_obligations` that `intent/stored-value-lifecycle.md` also uses; whichever page is built first adds them.
     - `Privacy.dueOn` becomes: the earliest of the business's own `response_days` (default 30 as today) and, for each law in force where the business trades (home country and every store's country, read with `Jurisdictions.countriesTrading`), that law's period counted in calendar months from the day opened. The request stores `due_on`, and `due_basis` (the law's code, or `BUSINESS`), so the countdown says why.
     - The queue is for every business, not a DPDP feature. DTOs carry `daysLeft` and `overdue`. `GET /customers/privacy/requests` orders by due date; a filter `overdue=true`.
     - **Reminders:** setting `privacy_settings.reminder_days` (off until set). A daily sweep publishes `PrivacyRequestDueSoon` once per request; notification-svc tells the business's owners and managers in-app and by email in the platform's own words.
     - **Extension:** where the register has a `DATA_REQUEST_EXTENSION` for the request's basis, a holder may extend once, with a reason, before the date passes: `due_on` moves out by the extension and the customer is told (`PrivacyRequestExtended`, and the notice goes to the customer's own address in the platform's words). Never where no such row exists.
  2. **Who is asking (customer-svc).**
     - Every request has `channel`: `APP` (signed in as the customer), `PHONE`, `LETTER`, `IN_PERSON`, `EMAIL`, `AGENT` (someone acting for the customer).
     - A request opened from the app by the signed-in shopper is verified by that sign-in (check method `SIGNED_IN`, written automatically).
     - Any other request must have a check before export, erasure or correction is done: `POST /customers/privacy/requests/{id}/verify` records one row in `privacy_identity_checks` (append-only): method (`DETAILS_MATCHED`: what the caller said matched what is held, `CALLBACK_HELD_CONTACT`: called back or wrote back on the contact already held, `DOCUMENT_SEEN`: a document was seen over the counter, `KNOWN_LOGIN`: confirmed from the signed-in account, `AGENT_AUTHORITY`: a representative's authority was seen), outcome `VERIFIED` or `FAILED`, who checked, when, and a short note. **No number, no image, no copy of a document is ever kept**: for `DOCUMENT_SEEN` only the kind seen (photo identity, proof of address, letter of authority, other) and the note. A note that looks like a long digit run is refused (`400 PRIVACY_CHECK_NOTE_INVALID`).
     - `AGENT` requests need `AGENT_AUTHORITY` plus a check of the customer's details; a child's request needs the guardian record that already exists.
     - **The proportion follows the sensitivity:** the register of methods that is enough for a request is the business's decision, but the floor is fixed: an ERASURE or an ACCESS request cannot be settled on `DETAILS_MATCHED` alone when the channel is `PHONE`, `LETTER` or `EMAIL` and the customer has orders; it needs a second method (`CALLBACK_HELD_CONTACT` or `DOCUMENT_SEEN`). A shop that has only a name on file uses `CALLBACK_HELD_CONTACT`; where there is none, `DOCUMENT_SEEN`. A business may require a second method for every request (`privacy_settings.strict_verification`, default off).
     - The staff-side `GET /customers/{id}/export` and `DELETE /customers/{id}` now take `requestId` (the request being honoured) and are refused without a verified, open, matching request: `409 PRIVACY_IDENTITY_NOT_VERIFIED`, `404` for a request that is not this customer's or business's, `409 PRIVACY_REQUEST_KIND_MISMATCH` (an erasure needs an ERASURE request, an export an ACCESS one). Honouring the request resolves it (`RESOLVED`, with what was done) on the same transaction as the erasure. The customer's own `/me/export` and erasure through the app are unchanged.
  3. **Rectification kept as such (customer-svc).**
     - `customer_changes` (append-only): id, `tenant_id`, `customer_id`, `fields` (names only: `phone`, `dob`, and so on; **never the old or new value**, which would keep personal data an erasure must remove), `changed_by`, `changed_at`, `request_id` (nullable).
     - `PUT /customers/{id}` and the customer's own `PUT /customers/me` write one row on the same transaction as the edit. Staff may send `privacyRequestId`; it must be an open CORRECTION request of this customer and verified (else `409 PRIVACY_REQUEST_NOT_CORRECTION` / `PRIVACY_IDENTITY_NOT_VERIFIED`). Resolving a CORRECTION lists the changes made under it. A person's own edit needs no request.
     - `GET /customers/{id}/privacy/changes` (`customers.privacy`) reads the trail.
  4. **Export and erasure across services (customer-svc orchestrates, each service holds its own).**
     - **`SubjectDataSpec` (common-service, the one definition, also used by [platform-administration](platform-administration.md) for a shopper login).** Each service declares, per table, the columns that identify a person and what happens to them: ERASE the row, ANONYMISE the columns, or RETAIN with the reason (an invoice a tax law says to keep keeps its number and amounts and loses the name). A schema test fails the build when a table has a `customer_id`, `user_id`, `login_id`, `email`, `phone*` or name-like column that no declaration mentions. The export and the erasure of a service are both generated from its declaration, so a column cannot be exported and forgotten by the eraser, or the other way round. It is the sibling of `TenantDataSpec`, which does the same for a whole business.
     - **Export** grows from profile, addresses, loyalty, store credit and orders to every service's part: notification-svc (what was sent to them, without secrets), cart-svc (open carts), iam-svc (the login: address, when created, whether a factor is set, never a hash or key), payment-svc (any name or address kept on a payment, refund or payer record), reporting-svc (read models that carry a customer name), order-svc (as now, plus gift cards linked to them, return requests and records, and handover proofs as metadata), and the consent evidence customer-svc already holds (preferences, purpose consents, both logs, requests, checks). Each service answers `GET /internal/subject-data/{customerId}` (service-to-service only: the gateway never routes `/internal/**`; tenant from the caller's stamped context) with its own section, built from its `SubjectDataSpec`; customer-svc assembles, and if any section cannot be read the export fails closed (`503 EXPORT_SECTION_UNAVAILABLE`, naming the service) as it does today for orders.
     - **Erasure** keeps the existing `CustomerErased` event (ids only). Every service that holds a customer id acknowledges, each publishing **`SubjectErasureCompleted`** (`storeql.<service>.subject-erasure-completed`: `tenantId` (null for a shopper login), `subjectKind` CUSTOMER or SHOPPER_LOGIN, `subjectId`, `service`, `counts` per table (ERASE, ANONYMISE, RETAIN), `at`): order-svc and notification-svc as now, and cart-svc, iam-svc, payment-svc and reporting-svc. (This page first called the acknowledgement `CustomerErasureCompleted` and listed four services; platform-administration called it `SubjectErased` and listed five. One name, one list: every service whose `SubjectDataSpec` names a customer id.) customer-svc keeps `erasure_receipts` (append-only), and the request's page shows "erased in: customer, orders, notifications, carts, login, payments, reports". An erasure is not "done" until every service that must acknowledge has (the request stays `RESOLVED` for the customer, with a visible `erasureComplete` flag for the business).
     - What must be kept by law or for the books is kept as it is today (orders' amounts and tax records, the append-only ledgers with the person's link removed); which of those a business is bound to keep is the retention rules already in `RetentionPurgeService` and the fiscal rules, not this page.
  5. **Screens.**
- **Out, on purpose:**
  - **Keeping a copy of an identity document, or its number.** Never, and there is no setting to turn it on; where a law one day demands it that is its own feature with its own storage rules. Only the fact and the kind of check are kept.
  - **Automated identity verification services** (a document scan, a knowledge check). External parties would come in as drivers behind an interface; nothing in the findings asks for one.
  - **Keeping the old and new values of a rectified field.** The names of the fields are kept; the values would be personal data an erasure would then have to hunt down.
  - **Telling third parties a person's data was corrected or erased** (recipients outside the platform). A business does that through its own processors; the platform's webhooks carry `CustomerErased` for the systems the business has connected.
  - **A shopper's platform login** (it belongs to no business, so its controller is the platform): [platform-administration](platform-administration.md) owns its erasure on legal request and uses this page's `SubjectDataSpec` and `SubjectErasureCompleted`. A platform administrator who must erase a person from one business does it through **this page's request** (opened, checked with `AGENT_AUTHORITY` or a legal reference, honoured through `DELETE /customers/{id}?requestId=`), never around it.
  - **The platform administrator's own requests to the platform** (the platform page).
  - **A different period per request kind.** One period per request, whatever its kind, as the laws in the register state.

## Data and flow

- **Owned by customer-svc:**
  - `privacy_requests` gains `channel`, `opened_by` (the staff member, null for the customer's own), `due_basis`, `extended_at`, `extended_by`, `extension_reason`, `verified_at`, `erasure_complete`. Existing rows read as channel `APP` verified.
  - `privacy_identity_checks` (append-only): id, `tenant_id`, `request_id`, `method`, `outcome`, `document_kind` (nullable), `note`, `checked_by`, `checked_at`.
  - `customer_changes` (append-only), `erasure_receipts` (append-only): id, `tenant_id`, `customer_id`, `service`, `at`.
  - `privacy_settings` gains `reminder_days` (nullable, off), `strict_verification` (default false).
- **Owned by tenant-svc:** the two register rows and the `limit_value`/`limit_unit` columns; `GET /admin/tenant/obligations` carries them, and `Jurisdictions` exposes them (`Obligation.limitValue`, `limitUnit`).
- **Endpoints (customer-svc):**
  - `POST /customers/{id}/privacy/requests` `{ kind, channel, detail }` (any staff): open a request on the customer's behalf, unverified.
  - `POST /customers/privacy/requests/{id}/verify` `{ method, outcome, documentKind?, note? }` (`customers.privacy`).
  - `POST /customers/privacy/requests/{id}/extend` `{ reason }` (`customers.privacy`).
  - `GET /customers/{id}/privacy/changes` (`customers.privacy`).
  - Existing: `GET /customers/privacy/requests?status=&overdue=`, `POST …/resolve`, `GET /customers/{id}/export?requestId=`, `DELETE /customers/{id}?requestId=`.
- **Needs from other services:** tenant-svc's register through `Jurisdictions` and store countries through `TenantProfiles`. Each service's own subject-data section over REST (never a join).
- **Events published:** `PrivacyRequestDueSoon` (`storeql.customer.privacy-request-due-soon`: tenantId, requestId, dueOn, kind; consumer notification-svc tells owners and managers, once per request); `PrivacyRequestExtended` (…-extended: tenantId, requestId, customerId, newDueOn; consumer notification-svc tells the customer); `SubjectErasureCompleted` (`storeql.<service>.subject-erasure-completed`, above; consumer customer-svc writes the receipt once per service and customer).
- **Retryable writes (Idempotency-Key):** verify, extend and erase (a replay answers with the first).
- **New error codes:** `409 PRIVACY_IDENTITY_NOT_VERIFIED`, `409 PRIVACY_REQUEST_KIND_MISMATCH`, `409 PRIVACY_REQUEST_NOT_CORRECTION`, `409 PRIVACY_EXTENSION_NOT_ALLOWED`, `409 PRIVACY_REQUEST_SETTLED` (exists), `400 PRIVACY_CHECK_NOTE_INVALID`, `400 PRIVACY_CHANNEL_INVALID`, `503 EXPORT_SECTION_UNAVAILABLE`.

## Money, time and limits

- **Currency:** none. **Ledger postings:** none.
- **Dates:** a request's `due_on` is a calendar day from the UTC day it was opened plus the law's months (end of month clamps to the last day). Reminders run on the UTC day. Extension adds the register's months to the current `due_on`, once.
- **Plan limits:** none.

## Constraints

- Golden rules 1 (each service answers for its own data), 3, 6 and 7, 8 (checks, changes and receipts append-only), 11, 15.
- **Location-neutral:** no period assumed. The number of months comes from the register row of a country or regime where the business trades; none there means the business's own setting. A business trading in two places with two laws is held to the sooner date.
- **Existing tenants:** open requests keep their `due_on`. The new `requestId` on the staff export and erasure is a breaking change for the k6 suites (`privacy-flow`, `dpdp-flow`, and any that erase a customer); they are updated in the same slice.
- The platform's retention purge and the shopper's own account deletion (`AccountDeleted`) do not go through a request and are untouched.

## Open questions

- [x] **What counts as verifying?** Recommended: the methods above, proportionate to the channel and to what is held, and a fixed floor of two methods for an offline erasure or access request when there are orders. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **May staff still export or erase without a request?** Recommended: no; the request and its verification are the record that the law was followed. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which period does a UK or EU business get?** Recommended: one calendar month, extendable once by two months where the register says so, the earlier of it and the business's own published period. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Values of a correction?** Recommended: field names only. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A UK business's request opened today is due one calendar month on, with `dueBasis: UK_GDPR`; an EU business's likewise with `GDPR`; a business in a country with no row gets its own `response_days`; a business trading in two countries gets the earlier date. — `PrivacyDueDateTest.*`, `PrivacyDueIT.theLawBindingTheBusinessSetsTheDate`
- [ ] The queue works for a business with no DPDP, lists earliest due first, and marks overdue. — `PrivacyDueIT.queueIsForEveryBusiness`
- [ ] A reminder is published once per request when `reminder_days` is set and never when it is not. — `PrivacyDueIT.remindersOnceAndOnlyWhenSet`; notification-svc `PrivacyRequestDueSoonHandlerTest.ownersAndManagersAreToldOnce`
- [ ] An extension is allowed once, only where the register has one, with a reason, and tells the customer. — `PrivacyDueIT.extendOnceWhereTheLawAllows`, `extensionRefusedWhereNoRule` (`409 PRIVACY_EXTENSION_NOT_ALLOWED`)
- [ ] A phoned-in erasure is refused `409 PRIVACY_IDENTITY_NOT_VERIFIED` until a `VERIFIED` check exists; a customer with orders needs two methods; after that it erases and resolves the request in one transaction. — `PrivacyVerificationIT.aPhonedRequestNeedsACheck`, `ordersNeedTwoMethods`, `erasureResolvesTheRequest`
- [ ] The check keeps method, kind, outcome, who and when, and refuses a note holding a long digit run; nothing else is stored. — `PrivacyVerificationIT.noDocumentNumbersAreKept`
- [ ] A signed-in shopper's own request is verified by the sign-in. — `PrivacyVerificationIT.aSignedInRequestIsVerified`
- [ ] An export or erasure naming a request that belongs to another customer or the wrong kind is refused (`409 PRIVACY_REQUEST_KIND_MISMATCH` / `404`). — `PrivacyVerificationIT.wrongRequestIsRefused`
- [ ] A rectification under a CORRECTION request is traceable: who asked, who changed which fields, when; no value is kept; a plain edit is also recorded as a change with no request. — `RectificationIT.tracedWithNoValues`, `aPlainEditHasNoRequest`
- [ ] The export carries every service's section and refuses to be partial (`503 EXPORT_SECTION_UNAVAILABLE`, naming the service). — `PrivacyIT.theExportCarriesEveryService`, `anUnreadableServiceFailsTheExport`; order-svc, notification-svc, cart-svc and iam-svc `SubjectDataIT`
- [ ] Erasure is acknowledged by each service holding the customer, and the request shows `erasureComplete` only when all have. — `ErasureReceiptsIT.completeWhenEveryServiceHasAcknowledged`; per service `SubjectErasureIT`; cart-svc `CustomerErasedIT`, iam-svc `CustomerErasedLoginIT`; `SubjectDataSpecTest.everyPersonalColumnIsDeclared` (fails on an undeclared column)
- [ ] A cashier cannot verify, extend, export or erase (`403 FORBIDDEN`); staff of another business of every role, naming our customer or request ids, get 404 and nothing moves; a shopper cannot verify their own request. — `PrivacyVerificationIT.onlyHoldersVerify`, `anotherBusinessTouchesNothing`
- [ ] The k6 `privacy-flow` and `dpdp-flow` suites pass with the request step; `flow-guard-comprehensive` and `flow-guard-runtime` stay green.
- [ ] Widget tests: `privacy_queue_screen_test.dart`, `privacy_verify_dialog_test.dart`, `privacy_request_detail_test.dart`.

## Screens

- **Admin shell, Privacy:** a queue for every business (not just DPDP): due date with "12 days left" or "3 days overdue", the law it counts under, channel, kind. A request opens to its detail: who asked and how, the checks so far (method, who, when), a "Verify identity" dialog (method, kind of document seen, note; a line saying no number or copy is kept), Extend (where allowed), the actions Export and Erase (disabled until verified, saying why), the changes made under a correction, and the services that have erased.
- **Customers screen:** "Record a request" (phone, letter, in person) for any staff.
- **Settings:** reminder days and strict verification in the privacy settings card.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **More services now hold a customer id and so acknowledge with `SubjectErasureCompleted`:** product-svc (reviews, [product-reviews-and-ratings](product-reviews-and-ratings.md) slice 6, which replaces the plan's earlier "staff ids only" line for product-svc), notification-svc (campaign sends, [campaigns](campaigns.md)), pricing-svc (customer-linked redemptions and offer views, [personalised-offers](personalised-offers.md)), and customer-svc's own new tables (cases, house accounts, segments, households, earning breakdowns). Financial ledgers (house accounts, deal and invoice records) are declared RETAIN with the reason and the link removed.

- **No standalone export or erasure by staff any more** (2026-09-30, industry standard): a request and its check are the evidence.
- **Field names only in the correction trail** (2026-09-30, industry standard): the trail must survive an erasure.
