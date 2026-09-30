# Return controls: condition, window, receipt, permission, refund method, exchange

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on returns · 2026-09-29 |
| **Roadmap** | new: the flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), returns domain — ret-till-return, ret-no-receipt, ret-stock-disposition, rfd-storecredit-giftcard, exc-exchange |
| **Services** | order-svc owns returns, the return policy, receipt lookup, gift cards and exchanges · inventory-svc puts returned stock where its condition says · payment-svc refunds and announces every refund, whatever the method · customer-svc credits store credit and takes back loyalty points · purchase-svc posts it · the app gives the till a returns screen |
| **Builds on** | `returns`/`return_items` (`condition` column never used), `POST /orders/{id}/returns` (`createReturn`), `OrderReturned` (no condition, no customer), inventory-svc `receiveReturnFromOrderOnce` (always AVAILABLE), batch `material_status` (AVAILABLE/QUARANTINE/INSPECTION/DAMAGED/RECALLED), payment-svc `refundForOrderEvent` (ORIGINAL only), `PaymentRefunded` → purchase-svc `SalesEventHandler`, customer-svc `store_credit_ledger`/`loyalty_ledger`/`loyalty_point_lots` (both keyed by `order_id`), order-svc `gift_cards`/`gift_card_transactions`, `fiscal_receipts.full_number`, the audit trail, `pos_void_log`, the admin Orders *Return / Refund* dialog |
| **Built in** | slice 1 (the controls) `0f6decf3` · slice 2 (the till's Returns screen, exchanges, no-receipt returns, the gift-card tender charged first) the commit after it on `fix/flow-catalogue-findings`, 2026-09-30 |

## Problem

Returns are the till's easiest way to lose money, and today almost nothing stands in the way:

- **Any condition goes back on the shelf.** The admin screen sends every item back as `GOOD`, and even if it didn't, `OrderReturned` drops the condition. So an opened, damaged or faulty item is restocked as sellable.
- **No window.** A sale can be returned any number of months later.
- **Cashiers refund without limit.** Any staff member can return a whole sale and have the money go back. `sales.refund` is only checked on payment-svc's manual refund, never on a return.
- **Finding the sale is hard.** There is no lookup by receipt: staff need the order id, and the till has no returns screen at all.
- **Retries pay twice.** A retried return or void has no idempotency key.
- **Store credit and gift card refunds do nothing.** Choosing STORE_CREDIT or GIFT_CARD records a label but credits nobody, and posts nothing to the books.
- **Loyalty points stay.** Points earned on a returned or voided sale stay with the customer; customer-svc only listens to `OrderConfirmed`.
- **No exchange.** Swapping a size means a full refund and a new sale, so the money moves twice.

## Outcome

- **At the counter, the cashier takes the item back from the sale itself.** They scan or type the receipt number, see the lines that can still come back, and say for each item whether it is sealed, opened, damaged or faulty. Stock then goes where that condition says: back to sale, to a check, or out of sale.
- **A return inside the business's policy the cashier completes alone.** That means the sale was found, it is inside the window, it is under the cashier's limit, and the goods are sellable. Anything outside the policy needs a manager (someone holding `sales.refund`). The audit trail names both.
- **The money goes where the customer chose, and actually arrives there:**
  - Back to how they paid.
  - To their store credit (the sale must name a customer).
  - To a gift card, new or topped up.
- **The books record it.** A refund to store credit or a gift card goes to its liability account; a cash or card refund is recorded as a refund.
- **Loyalty points follow the money.** A return or void takes back the points the returned part earned.
- **An exchange is one transaction.** The returned item's value pays towards the new basket, and only the difference is paid or refunded.
- **A retry never pays twice.** A retried return or void answers with the first one.

## Who and where

- **Personas** ([PRD §2](../PRD.md)):
  - The **cashier**, for returns within policy and exchanges.
  - The **store manager**, for anything outside policy, no-receipt returns and the policy itself.
  - The **owner**, who sets the policy.
  - The **shopper** is only affected: store credit, gift card and points.
- **Channels:**
  - POS gets a returns screen and an exchange flow.
  - The back office keeps its Return / Refund dialog.
  - Online orders use the same rules; a shopper self-service return (RMA) is out of scope.
- **Scope:** the return is taken at a store the caller may act at, for a sale from any store of the business. The policy is per business.
- **Roles that can write:**
  - Any staff at the store, for a return within policy.
  - `sales.refund` holders (MANAGER, OWNER, PLATFORM_ADMIN), for everything else.
  - OWNER and MANAGER set the policy.
- **Sandbox tenant:** behaves the same (a sandbox's refunds already use the MANUAL provider).

## Scope

- **In:**
  - **The condition of each returned item is required.** Sealed goes back AVAILABLE; opened goes to INSPECTION, off sale until a person checks it; damaged and faulty go to DAMAGED. A return that settles a recall still sends its goods to RECALLED. inventory-svc applies it from `OrderReturned`.
  - **A return policy per business:**
    - a window in days from handover (collection or delivery for online orders);
    - a cashier ceiling in the business's home currency;
    - whether no-receipt returns are allowed, and their ceiling.
  - **Outside the policy, `sales.refund` is needed:**
    - past the window;
    - over the cashier ceiling;
    - no receipt;
    - a faulty-goods claim past the window. Consumer law protects faulty goods beyond any window, so a manager takes these, never refuses them outright.
  - **Receipt lookup:** by the fiscal receipt number or the short order reference printed on the receipt, at the caller's stores.
  - **No-receipt returns:**
    - manager only, capped per business;
    - refunded to store credit or a gift card only, never cash;
    - at the item's current price at that store (from pricing-svc);
    - with the customer's contact recorded.
  - **Refund methods that credit:**
    - **ORIGINAL:** as today.
    - **STORE_CREDIT:** customer-svc credits the sale's customer. It is refused when the sale names none.
    - **GIFT_CARD:** order-svc issues a new card or tops up a named one on the return's own transaction.
    - **Recorded:** payment-svc records a refund tender of each method and announces `PaymentRefunded`, so purchase-svc posts every one.
  - **Loyalty reversal:** customer-svc takes back the points earned on the returned part of a sale (pro rata to what was refunded), and all of a voided sale's points, once per event.
  - **`Idempotency-Key` on `POST /orders/{id}/returns` and `/void`:** a replay answers with the first result.
  - **Exchange at the till (changed 2026-09-30, see Decisions):** one till action returns the old items and sells the new ones together. The returned value pays the new basket directly through an EXCHANGE line that nets to zero in the books; the customer pays only the difference, or gets the difference back to how they paid. No gift card is involved.
  - **The till's gift-card tender is made sound (added 2026-09-30):** the card is charged first, with a retry key, and the payment is recorded only once the card is charged; a card without enough balance stops the tender before anything is recorded.
  - **No-receipt returns are announced by order-svc itself:** there is no sale for payment-svc to refund against, so order-svc announces the refund, and customer-svc (store credit), inventory-svc (restock by condition), purchase-svc (posting) and reporting act on that announcement.
  - **Audit:** the trail records each return's lines, quantities and conditions, and whether it needed a manager.
  - **Screens:** the till's Returns screen (find sale, lines, condition, method, exchange), the admin dialog's condition per line, and the policy on the business settings screen.
- **Out, on purpose:**
  - **Shopper self-service returns (RMA from My Orders) and carrier return labels.** They're a separate feature; the rules here are the ones it would call.
  - **A manager PIN step-up at the till.** The manager completes the return in their own session. A PIN-on-the-cashier's-screen flow is its own security feature.
  - **Return-fraud velocity alerts.** The exception report already shows returns per staff and store; alerts are their own item.
  - **Restocking fees.** No finding asked for them, and they need per-country consumer-law care.
  - **Per-category windows.** One window per business first.
  - **Writing off DAMAGED stock.** That stays the existing stock adjustment with `stock.adjust`; the return only moves the stock off sale.
  - **Supplier returns (RTV) of faulty goods.** That's purchase-svc's own flow.
  - **Rewriting past returns.** Returns already made keep what they recorded.

## Data and flow

- **Owned by order-svc:**
  - `return_policies`, one row per business: `window_days`, `cashier_ceiling`, `no_receipt_allowed`, `no_receipt_ceiling`, with who changed it and when.
  - `return_items.condition` with a CHECK: SEALED, OPENED, DAMAGED or FAULTY.
  - `returns` gains `idempotency_key` (unique per business), `approved_by` (the `sales.refund` holder when outside policy), `outside_policy` (why), `no_receipt`, `customer_contact` and `exchange_order_id`.
  - `pos_void_log` gains `idempotency_key`.
  - Returns stay append-only.
- **Needs from other services:**
  - pricing-svc: the current price at the store, for a no-receipt return (REST, existing resolve).
  - tenant-svc: the home currency, through `TenantProfiles`.
  - customer-svc: a customer's store credit, which it applies itself from the event.
  - No joins.
- **Events published:**
  - `OrderReturned` gains, per line, `condition`, plus `customerId`, `noReceipt` and `giftCardId` when relevant. inventory-svc (disposition), payment-svc (the refund of any method), customer-svc (loyalty) and reporting read it.
  - payment-svc's `PaymentRefunded` carries the method (ORIGINAL tender, STORE_CREDIT, GIFT_CARD) and the customer. customer-svc credits store credit from it, once; purchase-svc posts it.
  - customer-svc starts consuming `OrderReturned` and `OrderVoided` for loyalty.
- **Retryable writes (Idempotency-Key):** `POST /orders/{id}/returns`, `POST /orders/{id}/void`, the no-receipt return and the exchange.
- **New error codes:**
  - `403 ORDER_RETURN_NEEDS_MANAGER`, naming why: window, ceiling, no receipt or faulty past the window.
  - `400 ORDER_RETURN_CONDITION_REQUIRED`
  - `409 ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER`
  - `404 ORDER_RECEIPT_NOT_FOUND`
  - `400 IDEMPOTENCY_KEY_REQUIRED` (returns and voids)
  - `409 ORDER_NO_RECEIPT_RETURNS_OFF`
  - `422 ORDER_NO_RECEIPT_OVER_CEILING`

## Money, time and limits

- **Currency:** a refund is always in the sale's currency. The ceilings are in the home currency; a sale in another currency is translated through `FxRates` and, without a rate, needs a manager (fails closed, like spend authority).
- **Ledger postings:**
  - An ORIGINAL refund posts as today.
  - A refund to store credit or a gift card posts Dr sales returns / Cr the store-credit or gift-card liability. Confirm the accounts in purchase-svc's chart while building; add them if missing.
  - An exchange posts the return and the new sale as usual; the EXCHANGE line credits and debits an exchange clearing account that nets to zero, so no gift-card liability or breakage is touched.
  - A no-receipt refund posts Dr sales and VAT at the current price's rate / Cr the store-credit or gift-card liability.
- **Dates:**
  - The window counts calendar days in the store's own time zone, from the handover: the till sale, or the recorded collection or dispatch for online orders.
  - The return records `created_at` in UTC.
- **Plan limits:** none.

## Constraints

- **Golden rules:**
  - 1: customer-svc and inventory-svc act on events; nobody reads order-svc's tables.
  - 6 and 7: events go through the outbox and are consumed idempotently.
  - 8: returns, refunds, the loyalty ledger and the audit log stay append-only.
  - 11: Idempotency-Key.
  - 13: BigDecimal throughout.
- **Location-neutral:** no country's consumer law is assumed. The window and ceilings are the business's own settings, and the faulty-goods path never refuses outright; it asks for a manager.
- **Existing tenants:** a business with no policy row gets a default policy (recommended below), so returns keep working the day this ships, with the new checks on.
- **Flow guards:** `flow-guard-comprehensive` and `flow-guard-runtime` must stay green. Returns touch orders and POS sessions.

## Open questions

- [x] **Who may complete a return?** Recommended: any till staff for a return within policy (sale found, inside the window, under the cashier ceiling); a `sales.refund` holder for anything else, in their own session, named on the audit trail. → **accepted as recommended** (the user, 2026-09-29)
- [x] **Default policy for a business that has not set one?** Recommended: a 30-day window, no cashier ceiling until the business sets one (so returns inside the window keep working as today), and no-receipt returns off. No amount is assumed: a fixed figure would mean £100 to one business and 60p to a business that trades in yen. → **accepted as recommended** (the user, 2026-09-29)
- [x] **Conditions and where stock goes?** Recommended: SEALED → back on sale (AVAILABLE), OPENED → INSPECTION (off sale until checked), DAMAGED and FAULTY → DAMAGED (off sale; write-off stays a separate `stock.adjust` step), recall returns → RECALLED. → **accepted as recommended** (the user, 2026-09-29)
- [x] **Loyalty points already spent when the sale comes back?** Recommended: take back the points pro rata anyway; the balance may go below zero, and no redemption is allowed until it is earned back. The customer keeps what they already bought with them. → **accepted as recommended** (the user, 2026-09-29)
- [x] **No-receipt returns and exchanges: now or later?** Recommended: both in this build. No-receipt returns are manager only, at the current store price, store credit or gift card only, capped, and off by default. Exchanges go through a single-use exchange card tendered against the new basket. → **accepted as recommended** (the user, 2026-09-29)
- [x] **One build or two?** Recommended: one intent, built in two slices on the fixes branch. First the controls (condition, window, permission, receipt lookup, idempotency, refund methods that credit and post, loyalty reversal). Then the till's returns screen, no-receipt returns and exchanges. → **accepted as recommended** (the user, 2026-09-29)

## Acceptance

- [x] A sealed item returned goes back on sale; an opened one to INSPECTION; a damaged or faulty one to DAMAGED; a recall return to RECALLED. — inventory-svc `ReturnDispositionIT` (`sealedGoesBackOnSale`, `openedGoesToInspection`, `damagedAndFaultyGoToDamaged`, `aRecallReturnIsRecalled`, `aLaterSaleNeverDrawsOffSaleStock`); no-receipt goods the same way, `NoReceiptReturnIT` (inventory-svc)
- [x] A return with no condition on a line is refused `400 ORDER_RETURN_CONDITION_REQUIRED`. — `ReturnsIT.conditionIsRequiredAndOneOfFour`; an exchange too, `ExchangeIT.badRequestsAreRefused`
- [x] A cashier returning inside the window and under the ceiling succeeds. Past the window, over the ceiling, or with no receipt, they get `403 ORDER_RETURN_NEEDS_MANAGER` naming why, and nothing is refunded or restocked. A manager's same return succeeds and names them as approver. — `ReturnsIT.cashierReturnsWithinPolicy`, `pastTheWindowNeedsAManager`, `theBusinessOwnPolicyApplies`; `NoReceiptReturnIT.aCashierNeedsAManager`; `ExchangeIT.outsideThePolicyNeedsAManager`
- [x] A faulty-goods return past the window is never refused outright: a manager completes it. — `ReturnsIT.faultyGoodsPastTheWindowGoToAManager`
- [x] A sale is found by its printed fiscal number and by its short order reference at the caller's stores; a store-held caller cannot find another store's sale (`404 ORDER_RECEIPT_NOT_FOUND`). — `ReceiptLookupIT.foundByNumberAndByShortReference`, `aStoreHeldCallerCannotFindAnotherStoresSale`, `anotherBusinessFindsNothing`, `ambiguousNumbersAreRefused`
- [x] A return or void retried with the same `Idempotency-Key` answers with the first, once: one refund, one restock, one audit row. A missing key is `400 IDEMPOTENCY_KEY_REQUIRED`. — `ReturnsIT.aRetriedReturnAnswersWithTheFirst`, `racingRetriesMakeOneReturn`, `aRetriedVoidAnswersWithTheFirst`; `ExchangeIT.aRetryAnswersWithTheFirst`; `NoReceiptReturnIT.aRetryAnswersWithTheFirst`
- [x] STORE_CREDIT credits the sale's customer exactly once, and is refused `409 ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER` with no customer. GIFT_CARD creates or tops up a card that can be redeemed for the amount. — `ReturnsIT.storeCreditNeedsACustomer`, `giftCardRefundIssuesANewCard`, `giftCardRefundTopsUpANamedCard`, `aReplayedGiftCardReturnCreditsOnce`; customer-svc `StoreCreditRefundIT.storeCreditRefundCreditsOnce`; `GiftCardRedeemIT.aRedemptionChargesOnceAndIsAnnounced`; payment-svc `ReturnExchangeGiftCardIT.giftCardRedeemedRecordsOneTenderAndOneCaptureOnce`
- [x] Every refund, whatever the method, posts to the ledger: store credit and gift card to their liability accounts. — purchase-svc `SalesPostingIT.aRefundReversesRevenueVatAndTheTender`, `aReturnToStoreCreditOrGiftCardCreditsItsLiability`, `anExchangeNetsToZero`, `aNoReceiptReturnIsPostedOnce`
- [x] A return takes back the points its part of the sale earned, and a void takes back all of them, once per event, even if the balance goes below zero. — customer-svc `LoyaltyReversalIT.partialReturnTakesBackItsShare`, `voidTakesTheRest`, `spentPointsLeaveADebt`, `neverMoreThanEarned`
- [x] An exchange of a like-for-like item moves no money; a dearer one charges only the difference; a cheaper one refunds only the difference. — `ExchangeIT.likeForLikeMovesNoMoney`, `dearerChargesTheDifference`, `cheaperRefundsTheDifference`, `vatIsPartOfTheReturnedValue`; `ReturnValueTest`; payment-svc `ReturnExchangeGiftCardIT.likeForLikeExchangeMovesNoMoneyAndCallsNoProvider`, `aCheaperNewBasketAlsoRefundsTheDifferenceToTheOriginalTender`; k6 `returns-flow`
- [x] A no-receipt return is manager-only, store credit or gift card only, at the current price, and capped (`422 ORDER_NO_RECEIPT_OVER_CEILING`); it is off by default (`409 ORDER_NO_RECEIPT_RETURNS_OFF`). — `NoReceiptReturnIT.offByDefault`, `aCashierNeedsAManager`, `storeCreditAtTodaysPrice`, `giftCardIssuesANewCard`, `overTheCeiling`, `methodAndInputAreChecked`, `pricingDownIsRefused`; customer-svc `NoReceiptStoreCreditIT.creditsOnce`; reporting-svc `NoReceiptRefundIT.aNoReceiptRefundLowersTheStoresNetOnce`
- [x] Another business's staff of every role, even naming our store and order ids, cannot find, return, void or exchange our sale (404), and nothing moves. — `ReturnsIT.anotherBusinessCannotReachOurSale`, `ReceiptLookupIT.anotherBusinessFindsNothing`, `ExchangeIT.anotherBusinessCannotExchange`, `NoReceiptReturnIT.anotherBusinessCannotSeeIt`; the consumers too: `ReturnDispositionIT.anotherBusinessesEventNeverTouchesOurStock`, `ReturnExchangeGiftCardIT.anotherBusinessesExchangeEventTouchesNothing`, `NoReceiptStoreCreditIT.anotherBusinessTouchesNothing`, `NoReceiptRefundIT.anotherBusinessesRefundIsNotOurs`
- [x] The audit trail shows a return's lines, conditions and approver. — `AuditTrailIT.aReturnNamesWhoTookTheGoodsBack`; a no-receipt return on the trail, `NoReceiptReturnIT.storeCreditAtTodaysPrice`; k6 `audit-trail` (48/48)
- [x] Till: find by receipt, choose the condition per line, choose the method, and exchange. The admin dialog sends the condition it is given. — widget tests `pos_returns_screen_test.dart` (18), `orders_return_dialog_test.dart` (10), `tender_gift_card_test.dart`, `pos_receipt_code_test.dart`, `return_policy_card_test.dart`
- [x] End to end on the stack — k6 `returns-flow` 114/114; `flow-guard-comprehensive` 124/124, `flow-guard-runtime` 79/79; the full functional set green on a fresh stack (98 suites, 2026-09-30).

## Decisions

- **A return refunds what the customer paid for the line, VAT included** (2026-09-30, found building exchanges). Order lines are net and VAT is added on top, and a return refunded the net alone, so a VAT-registered business's customer got back less than they paid. The refund is now the line's net plus that quantity's share of the VAT the line was sold with (`ReturnValue.grossOf`, the sale's own VAT, never an assumed rate); `return_items.refund_amount` keeps the net figure the fiscal and commission reports read. Still open: order-level discounts and promotions are not shared back into a partial return's refund (payment-svc's cap stops any refund exceeding what was captured).
- **A gift card is charged once per sale** (2026-09-30). The redeem is keyed, and the same card and sale under a new key with the same amount answers with the first charge, so an offline sale replayed under a fresh key is never charged twice; a different amount for the same card and sale is refused (`409 GIFT_CARD_ALREADY_REDEEMED_FOR_ORDER`).
- **Exchanges are direct, not through an exchange card** (2026-09-30, the user). Replaces the confirmed "single-use exchange card tendered against the new basket" (2026-09-29). Why: spending a gift card makes purchase-svc recognise breakage, which is false for a card made and spent in seconds, and a cheaper new basket left a remainder the card design could not refund cleanly. The direct exchange pays the new basket with the returned value through an EXCHANGE line that nets to zero, settling only the difference.
- **The till's gift-card tender charges the card before recording the payment** (2026-09-30, the user). Found while designing exchanges: the payment was recorded first and the card charged after, with no retry key, so a failed charge left a sale counted as paid.
- **No-receipt returns are announced by order-svc** (2026-09-30). payment-svc refunds only against what a sale captured, and a no-receipt return has no sale, so order-svc announces the refund itself for customer-svc, inventory-svc, purchase-svc and reporting to act on.
- **A recall refund is never held by the return policy** (2026-09-29, industry standard, the user having asked for it on the open questions). A return that names a `recallNoticeId` skips the window and the cashier limit and needs no condition: refunding a product-safety recall is the business's legal duty, not a favour its policy grants, and the goods go to RECALLED whatever their state. The app's recall refund sends an `Idempotency-Key` derived from the notice (`derivedId(notice, 'recall-refund')`), so a retry replays the first refund. Proved by `RecallNoticeIT.aRecallRefundIsNeverHeldByTheReturnPolicy`.
- **Refunds to store credit or a gift card go through payment-svc like any refund.** payment-svc records a refund tender of that method (no provider call) and announces `PaymentRefunded` with `refundMethod`, `returnId`, `customerId` and the sale's `currency`. purchase-svc posts it with the existing tender mapping (GIFT_CARD → gift-card liability, STORE_CREDIT → store-credit liability), and customer-svc credits store credit from it once, in the sale's currency. One path for every refund keeps the books and the cap (never more than was captured) in one place.
- **A gift card loaded by a return announces `paidBy: RETURN`.** purchase-svc counts it in the gift-card pool but posts nothing of its own (`gift_card_loads.journal_id` may be empty only then): the refund's posting already credits the liability, and a second would owe the cardholder twice.
- **Points taken back reverse their deferral.** customer-svc announces `LoyaltyReversed`; purchase-svc moves the points' share of deferred income back to sales (the inverse of earning, at the pool's average as redemption values it). Points the customer had already spent release nothing: they are a debt on the customer's balance, which may go below zero, and new earnings pay it off first.
- **An opened return is off sale until checked.** Returned stock that is not SEALED gets its own batch (never merged into sellable stock), keeping the sale's lot, cost and expiry; INSPECTION, DAMAGED and RECALLED stock counts in neither on-hand nor available on the levels report and is never drawn.
- **Returns are staff-only**, and an unknown refund method is refused (`ORDER_RETURN_METHOD_INVALID`); the old `CASH` value is gone (use ORIGINAL).
- **The short receipt reference is the last 8 hex characters of the order id** (`Ids.shortRef`), matched with or without `#`; ambiguity is refused (`409 ORDER_RECEIPT_AMBIGUOUS`), never guessed.
