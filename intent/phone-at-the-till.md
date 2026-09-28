# A phone at the till: required, optional or not asked

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the user, testing the till by hand · 2026-09-26 |
| **Roadmap** | new: the user — "either the cashier can enter or ignore based on the customer feedback" |
| **Services** | tenant-svc owns the store's choice · order-svc enforces it and keeps the number · notification-svc texts it on a recall · the app asks for it |
| **Builds on** | `stores` (beside `enabled_payment_methods`), `orders.contact_phone`, the till's customer bar and Tender screen, `RecallSaleAffectedHandler` → `RecallNoticeIssued`, customer-svc `PhoneNumbers` |
| **Built in** | 355e6012 on `test/k6-gross-margin` |

## Problem

Since 2 July every till sale must carry a registered customer or a walk-in's phone number; the README and the UI guide call it "required on every sale". The number is asked for on the Sale tab. The Tender screen, where the cashier actually finishes, only blocks with *Enter a contact phone number for this sale* and offers no field, so the cashier has to go back and find it. A shopper paying cash may not want to give a number. Data-protection law in many places, including the UK and EU GDPR and India's DPDP Act, expects a shop to collect only what a sale needs. With a queue waiting, cashiers type made-up numbers instead. The number also rarely does its one job: a recall text goes only to a strict international number such as `+919886021001`, so one typed the usual way (`98860 21001`) reaches nobody.

## Outcome

Each store says whether its till asks for a phone: **Required**, **Optional** or **Don't ask**.

- At checkout the cashier sees the phone field right there, holding whatever was typed earlier.
- Under Optional they fill it in or leave it blank, as the customer prefers.
- Under Required the sale cannot complete without a number or a registered customer.
- Under Don't ask the till shows no phone field at all.

A number that is given is read in the store's own country first, then the business's other countries. At the till, one that is not a phone anywhere the business trades is refused, so the cashier can fix it while the customer is still there. The number is kept in international form beside what was typed, so a recall text reaches the buyer.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the owner or manager makes the choice for each store; the cashier asks, or doesn't, at the till.
- **Channels:** POS. Online checkout keeps its own rule (a contact phone for pickup and delivery); its number only gains the international form.
- **Scope:** per store.
- **Roles that can write:** OWNER, MANAGER, the same people who edit the rest of a store.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:**
  - A store's choice: Required, Optional or Don't ask, on the store form beside the payment methods, with a line saying what the number is for.
  - The till's Tender screen carries the phone field whenever no registered customer is attached and the store asks. It is the same value as the Sale tab's customer bar, so typing it in either place fills both.
  - Optional says the field may be left blank. Required keeps **Complete sale** from finishing, with the field in view. Don't ask hides the field on both screens.
  - order-svc refuses a till sale at a Required store that has neither a number nor a customer.
  - order-svc reads a given number in the store's country, then the business's other countries, and keeps the international form beside the typed one.
  - A recall text uses the international form. An order placed before this change has its number read when the recall needs it. An order with no usable number falls back to the customer's own.
  - customer-svc's `PhoneNumbers` moves to common-service, so the two services read a phone one way.
- **Out, on purpose:**
  - A choice for online checkout. The shop must be able to reach a shopper about a pickup or delivery, so online keeps requiring a contact phone.
  - Refusing an online number that cannot be read. That would change the storefront; the number is kept as typed and simply not texted.
  - Asking at the till for marketing consent. The till number is for the sale and recalls only; marketing consent stays with customer-svc, where the Marketing purpose governs it.
  - Rewriting the numbers on past orders. They are read when a recall needs them, which covers every order without a backfill.
  - Receipts by text or email at the till.

## Data and flow

- **Owned by** tenant-svc: the store's till choice (Required, Optional or Don't ask), with a check.
- **Owned by** order-svc: an order's contact number in international form, beside the typed one.
- **Needs from other services:** order-svc reads the store's choice, the store's country and the business's countries through common-service `TenantProfiles`, the cached read of tenant-svc; never a join. If they can't be read, it **fails open**: an unreadable choice counts as Optional, and unreadable countries leave the number as typed, never refused.
- **Events published:** none new. `RecallNoticeIssued.buyerPhone` now carries the international form.
- **Retryable writes** (Idempotency-Key): the till sale, as today.
- **New error codes:** `409 ORDER_CONTACT_PHONE_REQUIRED` (a Required store, no number, no customer) · `400 ORDER_CONTACT_PHONE_INVALID` (a till number that reads as no phone where the business trades).

## Money, time and limits

- **Currency:** none.
- **Ledger postings:** none.
- **Dates:** none.
- **Plan limits:** none.

## Constraints

- **Location-neutral:** no country or dialling prefix is assumed. The store's own country is tried first, then the business's home country and its other stores' countries.
- **Existing tenants:** today every till sale needs a number. Which choice existing stores start on is an open question.
- **Data minimisation:** a number is taken only when the customer gives it, and used only for the sale and recalls.
- **Tenant isolation:** a store's choice is read for the caller's own business only. Another business's store id is never read, and a till sale there is refused as today.

## Open questions

- [x] Where does the choice live? Recommended: **per store**, beside its payment methods. A business can trade in more than one country, and the till already reads its store. → **per store** (the user, 2026-09-26)
- [x] Which choice do existing stores start on? Recommended: **Optional**, like new stores. Today's Required rule was failing its purpose anyway, and Optional is the data-minimising default; an owner who wants a number on every sale chooses Required. → **Optional** (the user, 2026-09-26)
- [x] A till number that is no phone where the business trades? Recommended: **refuse it at the till**, so the cashier can fix it or leave it blank while the customer is there. Never refuse one online. → **refuse at the till** (the user, 2026-09-26)
- [x] Online orders' numbers? Recommended: **keep their international form too, quietly**. They have the same recall gap, and a checkout is never refused over it. → **yes, quietly** (the user, 2026-09-26)

## Acceptance

- [x] A store is Optional unless its owner or manager chooses otherwise, and the choice is read back, on the till's store list too — `StoreTillPhoneIT.aStoreAsksOptionalUntilItsOwnerChooses`, `StoreTillPhoneIT.theOwnerOrAManagerChooses`
- [x] A cashier, storekeeper or another business's staff cannot change a store's choice, and anything but the three is refused `400 STORE_TILL_PHONE_INVALID` — `StoreTillPhoneIT.staffWhoRunTheTillCannotChooseForIt`, `StoreTillPhoneIT.anotherBusinessNeverReachesIt`, `StoreTillPhoneIT.anythingElseIsRefused`
- [x] At a Required store, a till sale with neither a number nor a customer is refused `409 ORDER_CONTACT_PHONE_REQUIRED`; with a customer, or a number, it goes through — `TillPhoneIT.aRequiredShopRefusesATillSaleWithNeither`, `TillPhoneIT.aRequiredShopTakesANumber`, `TillPhoneIT.aRequiredShopTakesACustomerInstead`, `OrderServiceTillPhoneTest` (7)
- [x] At an Optional or Don't-ask store, a till sale with no number goes through — `TillPhoneIT.optionalAndOffShopsTakeNoNumber`
- [x] A number typed the local way (`98860 21001` at a store in India) is kept as typed and as `+919886021001`; the same digits at a store in another country read as that country's — `TillPhoneIT.aRequiredShopTakesANumber`, `TillPhoneIT.aNumberIsReadInTheShopsOwnCountry`, `PhoneNumbersTest.aTillReadsTheNumberInItsOwnStoresCountryFirst`, `TillPhoneTest` (7)
- [x] A till number that reads as no phone where the business trades is refused `400 ORDER_CONTACT_PHONE_INVALID`; online, the same number is kept as typed and the order is placed — `TillPhoneIT.aNumberThatIsNoPhoneIsRefusedAtTheTillOnly`
- [x] When the store's choice or countries cannot be read, the sale goes through: the choice counts as Optional and the number is kept as typed — `OrderServiceTillPhoneTest.whatCannotBeReadRefusesNothing`, `TillPhoneIT.whatCannotBeReadRefusesNothing`
- [x] A recall reaches a walk-in who gave a locally typed number, reaches the buyer on an order from before this change, and falls back to the customer's number when the order has none usable — `RecallNoticeIT.aWalkInsNumberTypedTheUsualWayIsWhatTheirRecallTextGoesTo`, `RecallNoticeIssuedHandlerTest.anOrderNumberThatCannotBeTextedFallsBackToTheCustomersOwn`
- [x] Another business's store id is refused as today, and its choice is never read for this business — `TillPhoneIT.anotherBusinessNeverReachesThisOnesShop`
- [x] Erasure and retention blank both forms — `CustomerErasureIT.settledOrdersAreRedactedNow`, `RetentionPurgeIT` (order-svc)
- [x] The Tender screen shows the field with the Sale tab's value, lets it be left blank under Optional, blocks **Complete sale** under Required, and shows no field under Don't ask — `test/features/pos/tender_screen_test.dart` (11), `test/features/pos/cart_screen_customer_phone_test.dart` (4)
- [x] The store form offers the three choices and saves them — `test/features/admin/stores_screen_test.dart`, group *phone-at-the-till (the store form)* (3)
- [x] End to end on the running stack: all three choices, both refusals, a recall text to a locally typed number — k6 `till-phone-flow` (37 checks), with `recall-buyers` 64, `retention-flow` 75, `order-crud` 147, `flow-guard-comprehensive` 110 and `flow-guard-runtime` 79 green beside it

## Decisions

- **The choice sits on the store, beside its payment methods** (tenant-svc `stores.till_phone`, a check on the three values). The till reads it from `GET /storefront/stores`, the list a cashier may read, because `/admin/stores` is management-only. It says whether a till asks and names nobody, so it is safe on a list the storefront also serves.
- **Every store starts on Optional, existing ones included.** Today's Required-everywhere rule was failing its purpose (the next point) and asked every walk-in for data the sale does not need.
- **Why the old rule failed.** A recall text goes only to a strict international number (`SmsChannel.E164`), and a till number was kept exactly as typed. `98860 21001`, or `+91 98860 21001` with its spaces, reached nobody. So the number is now read and kept in international form (`orders.contact_phone_e164`, with a format check) beside what was typed, which stays for people to read.
- **Read in the store's own country first, then the business's home, then its other stores'.** The same digits can be a number in two countries: `06 12 34 56 78` is Dutch at a French business's Amsterdam shop and French at its Paris one. customer-svc keeps its home-first order for a person's own record. `PhoneNumbers` moved to common-service so the two services read a phone one way.
- **Refused only at the till, and only when it can be judged.** Online, a number that does not read is kept as typed and never refuses a checkout, which would change the storefront. At the till the customer is still there to correct it. With no country readable, a national number is kept as typed and never refused. A `+` number judges itself.
- **Fail open, as the dark-store and routing reads do.** order-svc reads the choice and the countries through `TenantProfiles`. Unreadable, the choice counts as Optional and the number is kept as typed; a sale is never refused for want of an answer.
- **A changed choice reaches order-svc within five minutes** (`TenantProfiles`' cache). The till reads it from tenant-svc at once, so its own check applies immediately. Only the server's lags, and only for the few minutes after a change. Not worth an event and a cache hook.
- **Don't ask refuses nothing.** The till never shows a field; a number an integration sends anyway is read and kept like any other.
- **The rule lives in order-svc's `service` package (`TillPhone`), not `domain`.** It reads through `PhoneNumbers` (common-service's `com.storeql.service`), and ArchUnit keeps `domain` free of anything in a `service` package.
- **Recalls reach old orders without a backfill.** A recall notice's `buyerPhone` is the order's international form; for an order placed before it was kept, the typed number is read when the recall comes. A number that still does not read is passed as typed, and notification-svc falls back to the customer's own E.164 whenever the order's number cannot be texted, not only when it is missing. A local form is never guessed into a number that may not be the buyer's.
- **Erasure and retention blank both forms.** They share one redaction statement (`REDACT_ORDER`) and one "still identifies" test, both extended.
- **At the till the field is on the Tender screen too,** bound to the same value as the Sale tab's customer bar, so typing in either shows in both. Refusals (Required with nothing given, a number that is no phone) are shown at the field, never a snackbar, and the sale is kept. The catalog-mode (awaiting price) path follows the same rule. It is unreachable while the till always shows prices, but must not bring the old rule back.
- **Test numbers.** Ofcom's drama range (`07700 900xxx`) is not a phone number, and the till now refuses it. The till sales in `CustomerErasureIT`, `RetentionPurgeIT`, `recall-buyers` and `retention-flow` use `07400 900xxx`, which is valid.
- **Found on the way and fixed:** the Add Store dialog's Type dropdown overflowed its half of the row by 102px on every open. It now fills the row (`isExpanded`) and ellipsizes, and its test no longer swallows the error.
- **Found on the way and not fixed:** a recall's `source` is one of SUPPLIER, FSA, FSS, INTERNAL, OTHER. FSA and FSS are UK regulators, so an Indian or Polish business names its regulator as OTHER. It is noted for a location-neutral follow-up.
