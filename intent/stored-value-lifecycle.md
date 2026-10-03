# Stored value lifecycle: expiry, dormancy, unclaimed balances, a leaked gift card, notice before anything lapses

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on stored value · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `loy-store-credit-and-gift-cards` gaps 1 and 3, case LOY-39 (gap 2, who may issue store credit, was closed 30 Sep: see Already there) |
| **Services** | order-svc owns gift cards, their expiry, replacement and lapse · customer-svc owns store credit and its expiry, dormancy and lapse · tenant-svc owns the register of what the law allows per country · purchase-svc posts every lapse, surrender and hand-over through its existing gift-card and deferred-revenue accounting · notification-svc tells the holder before anything lapses · the app gives the business its settings and the till its replace-a-card action |
| **Builds on** | order-svc `gift_cards` (`status` ACTIVE, DEPLETED, CANCELLED; `expires_at`; redeem already refuses an expired card `GIFT_CARD_EXPIRED`), `gift_card_transactions` (append-only: ISSUE, RELOAD, REDEEM, REFUND, CANCEL), `GiftCardResource` (issue, lookup, reload, redeem, transactions); customer-svc `store_credit_accounts`, `store_credit_ledger` (type `EXPIRE` is provided for and never written), `manual_grants`; purchase-svc `DeferredRevenue` and `DeferredRevenueService` (gift-card pool, `giftCardBreakagePct`, breakage account 4031, `GiftCardLoaded`/`GiftCardRedeemed` events, `paidBy: RETURN` loads); tenant-svc `legal_obligations` and `Jurisdictions`; return-controls (refunds put value on cards and store credit) |
| **Built in** | not yet built |

## Problem

Gift cards and store credit are money the business owes. Today a card can carry an expiry only if the cashier types one at issue, store credit never expires, and nothing tells the holder anything. Where the law forbids expiry or fees a business could break it by hand; where the law makes an unclaimed balance the state's to collect a business has no way to show it did. When a card is photographed on a receipt, or a code leaks, anyone can spend it and the business can only cancel it and lose the balance. And when a card does lapse, the books recognise breakage only as a percentage estimate on spends, never as the real event.

## Outcome

- **A business decides, in its own settings, whether stored value expires or goes dormant and after how long, and can only choose what the law where it trades allows.** Nothing expires until it sets it.
- **Where a country requires unclaimed balances to be handed over, the platform gets the list and the books show the debt to the authority**, not income.
- **Nothing lapses without warning.** A holder we can reach is told before, in the business's own words.
- **A leaked gift card is replaced in one step**: a new code with the same balance and the same expiry, the old code dead and never redeemable again.
- **The books show what happened**: breakage when a balance lapses and the business keeps it, a payable when it must be handed over.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** (sets policy), the **manager** (replaces a card, reads the reports), the **cashier** (sees the expiry on a card, cannot replace), the **shopper** (told before lapse; holds a card).
- **Channels:** back-office settings and reports; POS for the replace action and to read a card's expiry; the storefront for the notice.
- **Scope:** policy is per business (store credit is per business per currency; gift cards per business, redeemable at any of its stores as today).
- **Roles that can write:** OWNER sets policy; OWNER and MANAGER replace a card and reissue.
- **Sandbox tenant:** the same; a sandbox's notices are suppressed by notification-svc as for every notice.

## Already there

- Issuing store credit by hand is management-only, needs a reason, and is recorded (`manual_grants`, 30 Sep, `ManualGrantsIT.issueRefusesBelowManager`, `managerIssuesAndItIsKept`); this closes `loy-store-credit-and-gift-cards` gap 2 and case LOY-38.
- An expired gift card cannot be redeemed (`GIFT_CARD_EXPIRED`, `OrderRepository` around line 3220) and a card can carry `expires_at` from its issue request.
- Refund-created value (store credit from a return, gift cards loaded by a return with `paidBy: RETURN`) is booked once ([return-controls](return-controls.md)).

## Scope

- **In (slices in build order):**
  1. **The register of what the law allows (tenant-svc).** New obligation codes carrying a limit, in the same `legal_obligations` table (columns `limit_value`, `limit_unit`, shared with `intent/privacy-requests.md`; whichever is built first adds them):
     - `STORED_VALUE_EXPIRY_FORBIDDEN`: no expiry may be set.
     - `STORED_VALUE_MIN_VALIDITY`: the shortest validity allowed (a number and unit).
     - `STORED_VALUE_FEES_FORBIDDEN`: no dormancy fee.
     - `STORED_VALUE_UNCLAIMED_TRANSFER`: a balance untouched this long is handed to the authority (a number and unit).
     - `STORED_VALUE_NOTICE`: notice to the holder must be given at least this long before a lapse.
     None are seeded on this page's authority; a row is added, with its citation, when a country's rule is confirmed (platform administrator, through the register's existing admin path). **Where the register has no row for a country the business trades in, the settings screen says so ("the platform holds no stored-value rule for X: check local law") and the owner must tick that they have** before a policy is saved (`acknowledgedNoRule`, kept with who and when). That is the honest default: never assume a country's law, never block a business for a law we have not recorded.
  2. **The business's policy (order-svc for gift cards, customer-svc for store credit).** One settings row each, all off until set: `expiry_months` (validity from the last load), `dormancy_months` (no use or load for this long makes the balance DORMANT), `notice_days` (lead time before a lapse), `lapse_treatment` (`KEEP_AS_BREAKAGE` or `RETURN_TO_HOLDER_AS_CREDIT`: what the business does when a balance lapses where the law does not decide). Saving is checked against the register for every country the business trades in (`Jurisdictions.countriesTrading`): an expiry where expiry is forbidden is `409 STORED_VALUE_EXPIRY_FORBIDDEN`; shorter than the minimum validity `409 STORED_VALUE_VALIDITY_TOO_SHORT`; any fee is not offered at all (this page adds no fee). The effective notice is the longer of the business's and the law's. A card's own `expires_at` set at issue stays as built, and now defaults to the policy (issue date + `expiry_months`) when none is typed, never longer than the law allows; a card issued before the policy keeps what it had. Store credit's expiry runs from its last issue or use, tracked per account (`last_activity_at`, `expires_at`), pushed out by use.
  3. **Notice before lapse.** A daily sweep in each owner service finds balances whose lapse is within the effective notice period and not yet notified, and publishes `StoredValueExpiring` once per balance per lapse date (`stored_value_notices`, unique by balance and date): `tenantId`, `kind` (STORE_CREDIT, GIFT_CARD), `holderCustomerId`, `amount`, `currency`, `lapsesOn`, `storeId` (for a card). notification-svc sends `STORED_VALUE_EXPIRING`, a message in the business's catalogue (rewordable; approval, where the business requires it, is the `notify.template-golive` action on `intent/approvals.md`), by email and push through `OrderMessages` routing (the `ACCOUNT` notice group of [shopper-notices](shopper-notices.md): email always, push by the shopper's choice, never SMS), once per event. It is an account notice about the holder's own money, so it needs no `MARKETING` consent. A store-credit holder is always a customer; a gift card is a bearer instrument, so the sweep can tell only a card **linked to a customer** (new optional `gift_cards.customer_id`, set when the card is loaded by a sale line whose order has a customer ([till-sessions-and-registers](till-sessions-and-registers.md) slice 8: online to a signed-in shopper, or at the till), or when staff link a card at the till with the customer's say-so; a merge of two customers repoints it, [customer-identity](customer-identity.md)). A card with no holder link cannot be warned: its expiry is printed on the receipt and shown at lookup, and that is said on the settings screen.
  4. **Lapse, dormancy and unclaimed balances.** The same sweeps:
     - **Lapse:** past `expires_at` the balance is zeroed by a ledger row (`EXPIRE`, for a card the new transaction type `EXPIRE`, status EXPIRED; store credit `EXPIRE` already provided for), and one `StoredValueLapsed` is published on the same transaction (below). Where `lapse_treatment` is `RETURN_TO_HOLDER_AS_CREDIT` and the holder is a known customer, the amount is instead moved to their store credit (a new `ISSUE` with reason `LAPSE`), with no income recognised.
     - **Dormant:** past `dormancy_months` with no activity the balance is marked DORMANT (visible in reports; a use wakes it). No fee is charged, ever.
     - **Unclaimed:** where the register has `STORED_VALUE_UNCLAIMED_TRANSFER` for the country the balance belongs to (the country of the store that sold the card, or of the business for store credit), a dormant balance reaching that age is SURRENDERED: zeroed by a ledger row (`SURRENDER`) and announced with disposition `UNCLAIMED`. `GET /admin/stored-value/unclaimed` (OWNER, MANAGER) lists what is held for hand-over, by country, with the holder where known, for the business to file.
  5. **Books (purchase-svc).** `StoredValueLapsed` (kind, id, amount, currency, disposition `BREAKAGE` or `UNCLAIMED`, `occurredAt`) is posted once per event: `BREAKAGE` as Dr the gift-card (or store-credit) liability, Cr 4031 breakage; `UNCLAIMED` as Dr the liability, Cr an unclaimed-balances payable account (added to the chart if missing), which is cleared when the business pays the authority through its ordinary payment. The deferred-revenue pool follows: a real lapse is recognised in full up to the liability the ledger still holds for that card (never twice) and the percentage estimate applies only to what has not yet lapsed. Decided while building against `DeferredRevenue`; the constraint is a test (`neverRecognisedTwice`).
  6. **Replace a leaked gift card (order-svc).** `POST /gift-cards/{code}/replace` `{ reason }` with `Idempotency-Key` (OWNER, MANAGER): on one transaction the old card gets a `REPLACE_OUT` transaction for its whole balance and status `REPLACED` (with `replaced_by`), the new card, with a new code, gets `REPLACE_IN` for the same balance, the same currency, the same store, and the same `expires_at` (replacing never extends life), and `replaces` pointing back. A DEPLETED, CANCELLED, EXPIRED or already replaced card is refused. The old code redeems as `409 GIFT_CARD_REPLACED` (the answer never shows the new code to whoever presents the old one; the manager sees it). Announced as `GiftCardReplaced` (tenantId, oldId, newId, amount, currency, reason): purchase-svc records it and posts nothing (the liability just changes card); it is not counted as a load in the pool. The audit trail names who replaced and why. Failed lookups and redeem attempts by code are counted per person like customer lookups (metric `gift_cards.lookup_misses` on `intent/exception-alerts.md`).
  7. **Screens.**
- **Out, on purpose:**
  - **Dormancy fees.** None are offered on any card or credit: the law forbids them in many places and no finding asked for one. If a country allows and a business wants one, that is its own feature.
  - **Escheat filing to an authority.** The platform lists what is held and books the payable; the filing and the payment are the business's own.
  - **Seeding a country's stored-value law without a confirmed citation.** The register is data; a row appears when it is confirmed.
  - **Warning a bearer of an unlinked card.** We hold no contact; see slice 3.
  - **Extending expiry as a goodwill act.** A reload restarts validity under the policy; other extensions are a manager's manual reload with a reason, as today.
  - **Store credit transferred between customers.** Not requested; merging duplicates is on `intent/customer-identity.md`.

## Data and flow

- **Owned by order-svc:** `gift_cards` gains `status` EXPIRED, REPLACED, SURRENDERED, `dormant_since`, `customer_id` (optional link), `replaced_by`, `replaces`; transaction types `EXPIRE`, `SURRENDER`, `REPLACE_OUT`, `REPLACE_IN`; `gift_card_policy` (per business: `expiry_months`, `dormancy_months`, `notice_days`, `lapse_treatment`, `acknowledged_no_rule_at/by`); `stored_value_notices` (unique by card and lapse date).
- **Owned by customer-svc:** `store_credit_policy` (the same fields), `store_credit_accounts` gains `last_activity_at`, `expires_at`, `dormant_since`; ledger types `LAPSE`-reason issue, `SURRENDER`; `stored_value_notices`.
- **Owned by tenant-svc:** the five obligation codes with `limit_value`/`limit_unit`; read by both services through `Jurisdictions`.
- **Endpoints:** `GET`/`PUT /admin/gift-cards/policy` (order-svc; GET management, PUT OWNER); `GET`/`PUT /admin/store-credit/policy` (customer-svc, same roles); `POST /gift-cards/{code}/replace`; `GET /admin/stored-value/unclaimed` (both services; the app merges the two lists); the card's response gains `expiresAt`, `dormant`, `replaced`.
- **Needs from other services:** `Jurisdictions` (tenant-svc) for the rules and the countries a business trades in; customer-svc contact through notification-svc's existing `CustomerClient` for the notice.
- **Events published:** `StoredValueExpiring` (`storeql.<order|customer>.stored-value-expiring`; consumer notification-svc); `StoredValueLapsed` (`storeql.<order|customer>.stored-value-lapsed`; consumers purchase-svc posts once per event, reporting-svc counts it); `GiftCardReplaced` (`storeql.order.gift-card-replaced`; purchase-svc records, reporting-svc). All through the outbox; each consumer once per `eventId`.
- **Retryable writes (Idempotency-Key):** replace; the two policy PUTs are idempotent by nature.
- **New error codes:** `409 STORED_VALUE_EXPIRY_FORBIDDEN`, `409 STORED_VALUE_VALIDITY_TOO_SHORT`, `409 STORED_VALUE_RULE_NOT_ACKNOWLEDGED`, `409 GIFT_CARD_REPLACED`, `409 GIFT_CARD_NOT_REPLACEABLE`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** each balance in its own currency, never converted (`NUMERIC(18,2)` as today).
- **Ledger postings:** BREAKAGE Dr gift-card or store-credit liability / Cr 4031; UNCLAIMED Dr liability / Cr unclaimed-balances payable; `RETURN_TO_HOLDER_AS_CREDIT` moves the liability from gift card to store credit (Dr / Cr the two liabilities); replacement none. Dated the day received, as sales postings are, and not refused in a closed period.
- **Dates:** expiry and dormancy count calendar months in UTC from the last load or use (a card's holder-facing date is shown in the selling store's zone); the sweep runs daily; the notice period is the longer of the business's and the law's.
- **Plan limits:** none.

## Constraints

- Golden rules 1, 3, 6 and 7 (a sweep publishes through the outbox; a rerun of the same day writes nothing new because of the unique notice and the zeroed balance), 8 (ledgers only appended; a lapse is a new row), 13, 14.
- Never assume a country's law; never a policy number in code (the settings are the business's; the register the law's).
- Existing cards and credit are untouched until a business sets a policy; the policy applies going forward and to balances by their last activity, so switching it on for an old business does not lapse a balance the same night without notice: the first sweep after a policy is set only notifies, and lapses no earlier than the notice period after the notice.
- A card's liability is per business; a replacement must not appear in the pool as a load or a redemption.

## Open questions

- [x] **Expiry defaults?** Recommended: none; the business sets it, the law caps it. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Fees?** Recommended: never offered. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **What if the register has no row for a country?** Recommended: allow, after the owner acknowledges that the platform holds no rule. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Who can replace a leaked card?** Recommended: OWNER and MANAGER with a reason, no second approver (the balance does not leave the business). → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Is breakage income or a payable when the law makes it the state's?** Recommended: a payable, decided by the register, otherwise income by the business's `lapse_treatment`. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Setting expiry in a country whose register forbids it is `409 STORED_VALUE_EXPIRY_FORBIDDEN`; shorter than the minimum `409 STORED_VALUE_VALIDITY_TOO_SHORT`; a country with no row needs the acknowledgement (`409 STORED_VALUE_RULE_NOT_ACKNOWLEDGED`); a business trading in two countries is held to both. — `StoredValuePolicyTest.*`, `StoredValuePolicyIT.theLawCapsThePolicy`, `noRuleNeedsAcknowledgement`
- [ ] With no policy set, nothing expires, goes dormant or is warned. — `StoredValueSweepIT.nothingHappensUntilSet`
- [ ] A balance within its notice period is announced once, however many times the sweep runs; the holder is emailed once, in the business's catalogue wording; an unlinked card is not. — `StoredValueSweepIT.oneNoticePerBalancePerDate`, `unlinkedCardIsNotWarned`; notification-svc `StoredValueExpiringHandlerTest.aHolderIsToldOnce`
- [ ] A lapse zeroes the balance by a ledger row and posts Dr liability / Cr 4031 once; a rerun does nothing; `RETURN_TO_HOLDER_AS_CREDIT` credits the holder's store credit and books no income. — `StoredValueSweepIT.lapseIsALedgerRow`, purchase-svc `StoredValuePostingIT.aLapseIsPostedOnce`, `neverRecognisedTwice`, `lapseAsCreditPostsNoIncome`
- [ ] A dormant balance is marked and woken by use; where the register has an unclaimed rule, it is surrendered at that age with disposition `UNCLAIMED`, listed at `/admin/stored-value/unclaimed`, and posted to the payable. — `StoredValueSweepIT.dormantAndUnclaimed`, `StoredValuePostingIT.unclaimedGoesToThePayable`
- [ ] Replacing a card moves the whole balance and the same expiry to a new code, kills the old (`409 GIFT_CARD_REPLACED`, no new code shown), posts nothing and is not a load in the pool; a retried replace answers with the first. — `GiftCardReplaceIT.balanceAndExpiryMove`, `oldCodeIsDead`, `aRetryReplacesOnce`; purchase-svc `GiftCardReplacedIT.noPosting`
- [ ] A depleted, cancelled, expired or replaced card is `409 GIFT_CARD_NOT_REPLACEABLE`. — `GiftCardReplaceIT.onlyLiveCardsAreReplaced`
- [ ] A cashier cannot replace or set policy (`403`); another business's staff of every role, naming our card code, get 404 and nothing moves. — `GiftCardReplaceIT.onlyManagementReplaces`, `anotherBusinessTouchesNothing`; customer-svc `StoreCreditPolicyIT.anotherBusinessTouchesNothing`
- [ ] Switching a policy on lapses nothing the same night: the first sweep only notifies. — `StoredValueSweepIT.aNewPolicyWarnsBeforeItLapses`
- [ ] k6 `stored-value-flow` (policy, a card replaced, a lapse), with `flow-guard-comprehensive` and `flow-guard-runtime` green; the `deferred-revenue` suite unchanged.
- [ ] Widget tests: `stored_value_policy_card_test.dart`, `gift_card_replace_dialog_test.dart`, `unclaimed_balances_screen_test.dart`, `pos_gift_card_expiry_test.dart`.

## Screens

- **Admin shell, Settings, Stored value:** two cards (gift cards, store credit): expiry, dormancy, notice, what happens on a lapse; the law for each country the business trades in, in words ("no expiry may be set", "at least 12 months"), or "the platform holds no rule for X" with the acknowledgement tick.
- **Admin, Reports:** "Unclaimed balances" by country and holder, "Lapsing soon".
- **POS shell:** a card lookup shows its expiry and warns when it is close; Replace card (manager) with a reason, showing the new code once to print or give.
- **Storefront:** the holder's store credit shows its expiry date; the notice email is the notification.

## Decisions

- (2026-09-30 evening, reconciliation) **Ledger account codes are now single** and identical on every page (the existing chart uses 1xxx assets, 2xxx liabilities, 4xxx income, 5xxx purchases, 6xxx expenses; the free numbers were checked against `Domain.java`): Here: the unclaimed-balances payable is `2340`; a goodwill card posts to `6420`. The full list: `1110` Customer accounts, `1120` Supplier rebates receivable, `1215` Cash in transit to bank, `2340` Unclaimed balances payable, `4040` Supplier promotional funding, `4050` Delivery income, `5040` Purchase rebates, `5050` Purchase price variance, `6420` Gift cards given (existing; goodwill cards), `6530` Cash over and short, `6540` Exchange differences, `6560` Bad debts, `6570` Stock shrinkage, `6571` Stock shrinkage, unexplained, `6572` Stock lost in transit. One seed adds each that is missing (idempotent, by whichever page builds first).

- **No fee, ever** (2026-09-30, industry standard).
- **A replacement never extends life** (2026-09-30): the same balance and the same expiry, so replacing cannot be used to refresh a card.
- **The register is data and starts empty** (2026-09-30): no law is asserted without a citation; the acknowledgement is the safeguard.
- (2026-10-02, store scoping of purchase-svc) **Deferred revenue for points and gift cards is the business's**: reading where it stands and setting the estimates (`/nominal-ledger/deferred-revenue`) need a caller held to no store (`403 BUSINESS_WIDE_ONLY`) — the pools cannot be split by store, so a store-held reader would see the whole business. What is deferred and recognised is rounded to the business's currency's own minor units (the currency saved with the estimates), never to two places; a point's value keeps four places (a rate) and the breakage estimates two (percentages).
- (2026-10-02, money at the currency's own minor units, industry standard) **Store credit is held and moved in its account's currency's own minor units** (ISO 4217 through `Fx.minorUnits`): an issue or a redeem finer than the currency is `400 STORE_CREDIT_AMOUNT_INVALID`, judged in the service because a request body cannot know its currency (the body keeps a four-place sanity bound); `store_credit_accounts.balance`, `store_credit_ledger.amount`/`balance_after`, `manual_grants.amount` and `loyalty_ledger.order_total` are `NUMERIC(18,4)` (customer-svc V15; at two places Postgres rounded KWD 1.125 of credit to 1.13, value from nothing); balances answer at the currency's units (`12.50` pounds, `500` yen, `1.125` dinars). Loyalty points are not money and keep their two places.
- (2026-10-02, money at the currency's own minor units, industry standard) **A gift card by hand is no finer than its currency**: issue, reload and redeem refuse an amount finer than the card's (or the order's) currency with `400 GIFT_CARD_AMOUNT_INVALID` — the code a sale's gift-card line already used — after the card and the order are found as the caller's (another business's staff, whatever store they name, get `404` first), and keep it at the currency's scale; `gift_cards`, `gift_card_transactions` and `gift_card_load_lines` keep money unconstrained (order-svc V49).
- (2026-10-02, industry standard, built) **Store credit is never issued to, nor newly spent by, an erased customer**: `POST /customers/{id}/store-credit/issue` and a new `…/store-credit/redeem` for an anonymised customer are `409 CUSTOMER_ANONYMIZED` with nothing written; a redemption already recorded for the order before the erasure still answers payment-svc's retry as it stands, so a tender is never left unrecorded against credit already taken. Why, and the same rule for points: [privacy-requests](privacy-requests.md) Decisions. The balance an erased customer leaves stays in the ledger, owed and unspendable; this page's lapse rules are what end it. — `ErasedCustomerValueTest.*`, `ManualGrantsIT.anErasedCustomerIsIssuedNoStoreCredit`, `anErasedCustomerSpendsNothingNew`
- (2026-10-02, industry standard) **A store-credit spend is once per order because the order's earlier `REDEEM` is looked for under the account's `FOR UPDATE`**, never before it (no unique key on the ledger backs it): two spends for one order fired at once take once, and every call answers the balance after that one spend. Why and the full order of locks: [privacy-requests](privacy-requests.md) Decisions. — `StoreCreditRedeemLockOrderTest.*`, `ManualGrantsIT.racingSpendsForOneOrderTakeOnce`
