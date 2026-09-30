# Customer identity: possible duplicates, a manager's merge, guarded lookups

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on customer records · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `cus-customer-records` gaps 1 and 2, case CUS-18 |
| **Services** | customer-svc owns the customers, the duplicate marker, the merge and the lookup limits · order-svc, cart-svc, reporting-svc and iam-svc keep their own references to a customer and repoint them from one event · the app (admin and POS shells) shows the marker, the merge and the lookup |
| **Builds on** | `customers` (unique `(tenant_id, email)`, `phone`, `phone_e164` from V10), `customer_addresses`, `CustomerService.create` (409 `CUSTOMER_ALREADY_EXISTS`), `CustomerService.lookup` and `GET /customers/lookup`, `PhoneNumbers` (common-service), `loyalty_accounts`/`loyalty_ledger`/`loyalty_point_lots`, `store_credit_accounts`/`store_credit_ledger`, `marketing_preferences`/`marketing_consent_log`, `purpose_consents`/`purpose_consent_log`, `privacy_requests`, `manual_grants`, `CustomerErased`, the admin Customers screen and the till's customer lookup |
| **Built in** | not yet built |

## Problem

The platform refuses only an exact email that already exists. The same person who gave a phone number at the till one day and an email online the next is two records, each with a part of their points, their store credit and their orders. Staff cannot see it and cannot fix it. Meanwhile the till's lookup answers a phone or email with a full profile and nothing but the gateway's general per-business rate limit stands between a curious cashier and a scraped customer list.

## Outcome

- **A likely duplicate is flagged the moment it appears, with the reason in words.** "Same phone number", "same name and same address", "same name and date of birth". No score, no hidden model: a manager can read the reason and judge it.
- **Nothing is blocked.** Two people can share a phone (a family). The flag asks; it does not refuse.
- **A manager merges two records into one.** The points, store credit, orders, consents and open privacy requests end up on the record that stays, once, and the other record points at it. Nothing is lost from the books: ledgers are not rewritten, they get a matching pair of entries.
- **The till and the storefront never send the customer to the dead record.** A lookup that lands on a merged record answers with the survivor.
- **Lookups leave a trace and have a limit.** Who looked up whom (by what: phone or email, found or not) is kept. A business can cap lookups per person per minute, and a run of misses is visible to the owner.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store **manager** (reviews and merges), the **cashier** (sees the marker at the till, cannot merge), the **owner** (sets the lookup limit), the **shopper** (only affected: one account, one balance).
- **Channels:** back-office (the Customers screen) and POS (lookup, marker). The storefront is unchanged.
- **Scope:** per business. Everything is decided inside one `tenant_id`. A candidate pair never crosses businesses.
- **Roles that can write:** OWNER and MANAGER merge and dismiss; any staff may create and edit a customer as today; the lookup limit is set by OWNER.
- **Sandbox tenant:** behaves the same.

## Scope

- **In (slices in build order):**
  1. **Detection and the marker (customer-svc).** Pure `DuplicateRules` decides, for a customer just created or edited, which other ACTIVE customers of the same business it may duplicate, and why:
     - `PHONE`: the same `phone_e164`, read in the business's own countries (never a country assumed). A phone that cannot be read as valid is never matched.
     - `NAME_AND_ADDRESS`: same last name and a first name equal or within one edit (typo, or a short form when one is the start of the other), and a default or any address with the same postal code and the same first line once case, spacing and punctuation are removed.
     - `NAME_AND_DOB`: same full name (case and spacing removed) and the same date of birth.
     - Same email is already refused at creation and is not a reason here; an email that differs only in case is already the same email.
     Candidates are stored (`duplicate_candidates`); a customer's response carries `possibleDuplicates` (a count). Listing, and a manager can dismiss a pair, which is remembered so it does not return.
  2. **The merge (customer-svc, then the consumers).** A manager picks the record to keep and the one to fold in. One transaction in customer-svc does all of it, with an `Idempotency-Key`:
     - **Loyalty:** the folded record's points move to the survivor with their own expiry dates (new lots for the survivor with the same expiry; the old lots closed). The ledgers get `MERGE_OUT` and `MERGE_IN` entries; no row is edited. Lifetime points add, and the tier is recomputed by the programme's own rule. A debt (negative balance) moves as a debt.
     - **Store credit:** per currency, the same pair of ledger entries; balances add. Where [stored-value-lifecycle](stored-value-lifecycle.md) gives the accounts an expiry, the survivor's account takes the **later** of the two `expires_at` and the later `last_activity_at`: an account has no lots, so the money cannot keep two lives, and a merge (the business's own act) must never make credit lapse sooner than either holder was told. (Points keep their own lots and dates.)
     - **Consents:** each channel and purpose takes the more careful answer: a withdrawal on either record wins over a grant. The evidence logs of the folded record stay with it (append-only); the survivor gets one new log row per changed answer with source `MERGE`. A **PENDING** double opt-in confirmation ([consent-evidence](consent-evidence.md)) on the folded record is superseded (its link stops working) and the survivor's own state stands; an open re-ask (`reconsent_requests`) moves only if the survivor has none.
     - **Privacy requests:** open requests move to the survivor keeping their due dates; settled ones stay for the record.
     - **Addresses:** copied to the survivor unless the same (line 1, postcode) is already there.
     - **Profile:** the survivor keeps its own values. A field the survivor lacks (phone, date of birth) is filled from the folded record and written as one `customer_changes` row naming the fields ([privacy-requests](privacy-requests.md) slice 3; never the values); nothing the survivor has is overwritten. Both records' emails remain visible to management on the merge record.
     - **The folded record** becomes status `MERGED` with `merged_into`, `merged_at`, `merged_by`. It keeps its email (so the same address can never be created again as a new record) but its phone is cleared from search, and it drops out of every list and lookup, which answer with the survivor. It is erased on the business's normal retention schedule like any dormant record.
     - **Announced** once as `CustomersMerged` (below). order-svc repoints the orders, returns, no-receipt contacts and gift cards (`gift_cards.customer_id`) it holds for that customer; cart-svc its carts; reporting-svc its projection; iam-svc the login link. A consumer acts once per merge id.
     - **Refused** when the two records each have a sign-in (`409 CUSTOMER_MERGE_BOTH_HAVE_LOGINS`: joining two sign-ins is iam-svc's identity question and is done by one of them asking the other to be removed first), when either is ANONYMIZED or already merged (`409 CUSTOMER_MERGE_NOT_ACTIVE`), when it is the same record (`400 CUSTOMER_MERGE_SAME`), or when either belongs to another business (`404`, as any other id would).
  3. **Guarded lookups (customer-svc).** Every `GET /customers/lookup` and every search by `q=` writes an append-only `customer_lookups` row (who, when, by phone or email or name, found or not; never the value looked up, only its hash). A per-business setting `lookup_limit_per_minute`, unset means unlimited, refuses the person (not the business) past it with `429 CUSTOMER_LOOKUP_LIMITED` and `Retry-After`. This is a limit on **signed-in staff by person**, held in customer-svc; the gateway's per-network `LOOKUP` class of [sign-in-protection](sign-in-protection.md) is a different control on public existence checks, and neither replaces the other. Misses per person are the exception-alert metric `customer_lookups.misses` (see `intent/exception-alerts.md`; this page only names it). Rows are purged with the business's retention run.
  4. **Screens.**
- **Out, on purpose:**
  - **Fuzzy or phonetic scoring and machine matching.** The reasons above are the whole rule; a manager can explain every flag to a customer, and a business in a language where phonetic rules are wrong is not misled.
  - **Blocking a duplicate at creation.** Refusing a shared phone or a common name would turn away real customers at the till.
  - **Undoing a merge.** The ledgers keep both sides, so the money is traceable, but moving points back after a survivor has spent them is a support case, not a button.
  - **Merging across businesses.** Never; each business is its own world.
  - **Merging two sign-ins.** iam-svc's; see the refusal above.
  - **Same email ignoring plus-tags or dots.** Those rules belong to one mailbox provider, not to email; a business that wants it merges by hand.

## Data and flow

- **Owned by customer-svc:**
  - `duplicate_candidates`: id, `tenant_id`, `customer_a`, `customer_b` (always the lower id first, so a pair exists once; unique per tenant), `reasons` (text list of the codes above), `found_at`, `status` (OPEN, DISMISSED, MERGED), `decided_by`, `decided_at`. The status changes; this table is not a ledger.
  - `customer_merges` (append-only): id, `tenant_id`, `survivor_id`, `merged_id`, `merged_by`, `reason` (optional text), `idempotency_key` (unique per tenant), `moved` (points, store credit per currency, orders known, as a small record of what was moved), `merged_at`.
  - `customer_lookups` (append-only): id, `tenant_id`, `actor_id`, `by` (PHONE, EMAIL, NAME), `value_hash`, `found`, `at`.
  - `customers` gains `status` value MERGED, `merged_into`, `merged_at`, `merged_by`.
  - Ledger types gain `MERGE_OUT`, `MERGE_IN` (`loyalty_ledger`, `store_credit_ledger`); consent log sources gain `MERGE`.
  - Setting `lookup_limit_per_minute` (nullable, off until set) on the business's customer settings row.
- **Endpoints (customer-svc):**
  - `GET /customers/duplicates?status=&after=&limit=` (OWNER, MANAGER, PLATFORM_ADMIN): pairs with both customers' names and the reasons, newest first, cursor.
  - `GET /customers/{id}/duplicates` (any staff): the customer's open candidates, so the till can show the marker.
  - `POST /customers/duplicates/{pairId}/dismiss` (OWNER, MANAGER).
  - `POST /customers/{id}/merge` `{ intoCustomerId, reason? }` with `Idempotency-Key` (OWNER, MANAGER): `{id}` is the record folded in, the body names the survivor. Answers the survivor and what moved.
  - `PUT /customers/lookup-settings` `{ limitPerMinute|null }` (OWNER).
- **Needs from other services:** tenant-svc through `TenantProfiles` for the business's countries (phone reading). Nothing else is read across; the other services learn from the event.
- **Events published:** `CustomersMerged` (`storeql.customer.customers-merged`): `tenantId`, `mergeId`, `survivorId`, `mergedId`, `loginId` (the login now linked to the survivor, when either had one), `occurredAt`; ids only, no personal data. Consumers: order-svc repoints `customer_id` on the orders it holds (a status-history row is never touched), cart-svc repoints carts, reporting-svc moves its projection, iam-svc repoints the login link when the folded record had it. Each is idempotent on `mergeId`. `LoyaltyMerged`-style money events are not needed: purchase-svc's deferred-revenue pool is by business, not by customer, and a merge moves points inside it.
- **Retryable writes (Idempotency-Key):** the merge.
- **New error codes:** `409 CUSTOMER_MERGE_BOTH_HAVE_LOGINS`, `409 CUSTOMER_MERGE_NOT_ACTIVE`, `400 CUSTOMER_MERGE_SAME`, `404 CUSTOMER_DUPLICATE_NOT_FOUND`, `429 CUSTOMER_LOOKUP_LIMITED`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** each store-credit currency merges into the survivor's account of the same currency; nothing is converted.
- **Ledger postings:** none. purchase-svc's loyalty and store-credit liabilities are business totals; a merge moves value between two customers of one business.
- **Dates:** merged, found and lookup instants in UTC. Lot expiry dates move unchanged: a merge never extends or shortens a point's life.
- **Plan limits:** none.

## Constraints

- Golden rules 1 (no reading other services' customers), 3 (tenant first in every query, including the candidate search), 6 and 7 (outbox, idempotent consumers), 8 (ledgers and consent logs only appended to), 11 (Idempotency-Key), 15.
- The candidate search runs on the create or edit transaction and must be cheap: it uses the existing `(tenant_id, phone_e164)` index and a new `(tenant_id, name key, postcode)` index; never a table scan of the business.
- Location-neutral: the phone is read in the business's own countries; name comparison lowercases by Unicode and strips marks and spacing, with no language assumed; postal codes are compared as text.
- Privacy: `customer_lookups` never keeps the phone or email, only a hash; the export includes a customer's own lookup count only if asked (it holds nothing else personal).
- Existing businesses: a one-off pass (`DuplicateBackfillRunner`, like `PhoneBackfillRunner`) fills `duplicate_candidates` for records already there.

## Open questions

- [x] **Which rules find a duplicate?** Recommended: the three above, each shown as a reason. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Who may merge?** Recommended: OWNER and MANAGER, in their own session, named on the merge record; no second approver, because nothing is destroyed and the ledgers keep both sides. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which values does the survivor keep?** Recommended: its own; blanks are filled from the folded record; consents take the more careful answer. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **A lookup limit: what number?** Recommended: none assumed; a business sets it; the trace and the `customer_lookups.misses` metric exist whatever it is. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Two records that each have a sign-in?** Recommended: refuse; do not join identities from the customer side. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Two customers with the same phone, written differently (`+44 7…` and `07…`), are flagged `PHONE` in a business whose countries read both as one number; in a business in another country they are not. — `DuplicateRulesTest.samePhoneInTheBusinessCountries`, `DuplicatesIT.phoneMatchFollowsTheBusinessCountries`
- [ ] Same name and address (case, spacing and a one-letter typo aside) is flagged `NAME_AND_ADDRESS`; a different postcode is not; same name and date of birth is flagged `NAME_AND_DOB`. — `DuplicateRulesTest.*`
- [ ] Creating and editing are never refused for a possible duplicate; `possibleDuplicates` shows. — `DuplicatesIT.aFlagNeverBlocks`
- [ ] A dismissed pair does not come back after either record is edited. — `DuplicatesIT.dismissedPairsStayDismissed`
- [ ] Merge moves points with their expiry dates, store credit per currency, open privacy requests and addresses; the ledgers show `MERGE_OUT` and `MERGE_IN`, nothing edited. — `CustomerMergeIT.movesPointsCreditRequestsAndAddresses`, `MergeLedgerTest`
- [ ] A withdrawn channel on either record is withdrawn on the survivor, with a `MERGE` log row. — `CustomerMergeIT.aWithdrawalWins`
- [ ] A retried merge answers with the first, once. A missing key is `400 IDEMPOTENCY_KEY_REQUIRED`. — `CustomerMergeIT.aRetryMergesOnce`
- [ ] Lookup of the folded record's phone or email answers the survivor. — `CustomerMergeIT.lookupFollowsTheMerge`
- [ ] Both records with sign-ins is `409 CUSTOMER_MERGE_BOTH_HAVE_LOGINS`; an anonymized or merged record is `409 CUSTOMER_MERGE_NOT_ACTIVE`; itself `400 CUSTOMER_MERGE_SAME`; nothing moved in any of them. — `CustomerMergeIT.refusalsMoveNothing`
- [ ] A cashier and a storekeeper cannot merge or dismiss (`403 FORBIDDEN`). — `CustomerMergeIT.onlyManagementMerges`
- [ ] Another business's staff of every role, naming our customer ids, get 404 and nothing moves; a pair never spans businesses. — `CustomerMergeIT.anotherBusinessTouchesNothing`, `DuplicatesIT.pairsNeverSpanBusinesses`
- [ ] The consumers repoint once: order-svc `CustomersMergedIT.ordersFollowTheSurvivorOnce`, cart-svc `CartMergedIT.cartsFollow`, reporting-svc `CustomersMergedProjectionIT`, iam-svc `LoginLinkMergedIT`; another business's event touches nothing.
- [ ] A lookup writes a row with a hash, never the value; over the limit is `429 CUSTOMER_LOOKUP_LIMITED` with `Retry-After`; unset is unlimited; the limit is the person's, not the business's. — `CustomerLookupIT.tracedAndLimited`, `unsetIsUnlimited`, `limitIsPerPerson`
- [ ] Only OWNER sets the limit. — `CustomerLookupIT.onlyTheOwnerSetsTheLimit`
- [ ] k6 `customer-merge-flow`: two records, merge, an order's customer and points follow; `flow-guard-comprehensive` and `flow-guard-runtime` stay green.
- [ ] Widget tests: `duplicates_screen_test.dart`, `customer_merge_dialog_test.dart`, `pos_customer_marker_test.dart`.

## Screens

- **Admin shell, Customers:** a "Possible duplicates" tab (pairs side by side, the reasons in words, Merge and Dismiss); on a customer, a chip "1 possible duplicate" opening the same view. The merge dialog says what will move (points, credit per currency, open requests), asks which record stays and a reason, and shows the outcome. A settings card for the lookup limit (owner).
- **POS shell:** after a lookup or a new customer, a quiet marker "may already be on file" with the other record's name; a cashier can see it and cannot merge. A lookup refused by the limit shows the wait.

## Decisions

- **Reasons, not scores** (2026-09-30, industry standard): every flag names its rule so a person can judge and explain it.
- **The folded record keeps its email** so the address can never be created again as a new customer; lookups redirect to the survivor.
