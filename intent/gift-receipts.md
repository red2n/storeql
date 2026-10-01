# Gift receipts: a receipt with no prices, tied to the sale so a return finds it

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Oracle Retail audit (Phase 2 backlog line 8, "E-receipts, gift receipts, returns without a receipt", Xstore ch. 4, 5, 39; the Review's 8.8 and 8.11) under the standing instruction of 2026-09-30 · 2026-09-30 |
| **Roadmap** | new: Oracle audit backlog 8 (partial: receipts print four ways and are emailed, 09.11; returns without a receipt are built, [return-controls](return-controls.md); the gift receipt is the missing third) |
| **Services** | order-svc owns gift receipts, their codes, the lookup and the return rule · notification-svc sends a price-free copy by email · the app gives the till a *Gift receipt* action and a gift-return path, and the storefront a "this is a gift" choice |
| **Builds on** | order-svc `order_receipts` (the copy log, type PRINT, THERMAL, SAVE or EMAIL, `ReceiptResource`; [till-sessions-and-registers](till-sessions-and-registers.md) slice 9 calls it `receipt_copies` and adds masking and `ADDRESS_DIFFERS`), `GET /orders/by-receipt` (`findByReceipt`), `POST /orders/{id}/returns` (`createReturn`), the return policy and `ORDER_RETURN_NEEDS_MANAGER`, pure `ReturnValue`, refund methods STORE_CREDIT and GIFT_CARD, `fiscal_receipts` and their gapless series, [exception-alerts](exception-alerts.md) `receipts.copies` |
| **Built in** | (not yet built) |

## Problem

A person buying a present wants a receipt they can hand over that says what it is and where it came from, and does not say what it cost. Today the till prints one receipt and every line carries its price, so the buyer either hands over the price or keeps the receipt and the recipient cannot exchange it. The recipient's return then has nothing to find: the platform looks a sale up by its fiscal receipt number or short reference, which a gift receipt would not show if it were a plain copy, and the rules for a return by someone who is not the buyer (credit, not the buyer's card) are not written down anywhere. Online, a shopper who has a parcel sent to a friend has no way to say it is a gift, so the parcel carries the invoice with the price.

## Outcome

- **The cashier can print (or email) a gift receipt for a completed sale**, for the whole sale or chosen lines. It shows the store, the date, the items and quantities, a code and the last day to return by where the business has a window, and **no price, no total, no tender, no buyer**.
- **The code finds the sale.** At the till, the recipient's return is looked up by the code; the return is allowed only for the lines and quantities on that gift receipt and is refunded to **store credit or a gift card, never to the buyer's card or cash**.
- **Everything that already governs a return still applies:** condition, window, the cashier's ceiling, a manager for what is outside the policy.
- **An online shopper can mark an order as a gift** with a short message, so the packing slip that goes in the parcel carries no prices and the store can print the gift receipt with it.
- **Every gift receipt is on the record:** who made it, when, for which sale and lines; reprints are counted like any receipt copy.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **cashier** (makes and reads back gift receipts, takes the gift return), the **store manager** (any return outside the policy), the **buyer** and the **recipient** (never signed in), the **online shopper** (marks a gift at checkout).
- **Channels:** POS · back-office (an order's *Receipts*) · storefront (checkout choice).
- **Scope:** per store: the sale's store. Staff acts only at stores they are assigned to (`requireStoreAccess`, `403 STORE_ACCESS_DENIED`), exactly as for receipts today; a store outside the business or a sale that is not ours is `404`.
- **Roles that can write:** CASHIER, MANAGER, OWNER at the sale's store make a gift receipt and read a gift lookup, as they make any receipt copy; the STOREKEEPER does not (a gift receipt is a till matter, the tier the receipt route already allows is kept). A gift return is a return: the same roles and the same manager step as any return.
- **Sandbox tenant:** behaves the same; an emailed gift receipt is suppressed in a sandbox as every notice is.

## Scope

- **In:** slices in build order.
  1. **The gift receipt (order-svc, notification-svc, app).** `POST /admin/orders/{id}/gift-receipts` (`Idempotency-Key`) `{lines?: [{orderItemId, qty}], deliver: PRINT | THERMAL | SAVE | EMAIL, emailedTo?}` for a completed sale (a till sale that is paid, an online order that is fulfilled): the sale's whole quantity when `lines` is omitted, else the chosen lines and quantities within what the sale holds. It records a `gift_receipts` row with a **code**: ten characters from an alphabet with no look-alikes (0/O, 1/I/L), drawn from `SecureRandom`, unique for the business, never derived from the sale, so it cannot be guessed from a receipt number. The answer is a **price-free document** (`GiftReceiptResponse`) built server-side, not rendered from the order by the client, so a client bug cannot leak a price: the store's name and address as the business holds them, the sale's local date in the store's zone, the short order reference, each line's product name, variant label and quantity, the code (and the app draws it as a barcode), the return-by date where the business has a window (sale date plus the window; absent when it has none), and the words *gift receipt, not a tax document*, in the language of the store. It has **no money field at all**: no unit price, no line or sale total, no tax, no discount, no tender, no change, no buyer name, no loyalty number, no card digits. A gift receipt never takes a fiscal receipt number and never appears in the fiscal series. Every make or reprint also writes the receipt copy log (variant `GIFT`), so `receipts.copies` counts it; a gift copy emailed to an address other than the buyer's is expected and is **not** flagged `ADDRESS_DIFFERS`. An email goes through notification-svc with the same price-free body, in the platform's own words. Not for a voided or cancelled sale (`409 ORDER_GIFT_RECEIPT_SALE_NOT_ELIGIBLE`), nor for one not completed (same code, the reason in the message).
  2. **A return finds it, and takes it only as credit (order-svc, customer-svc, app).** `GET /orders/by-gift-receipt?code=` (staff at the caller's stores) answers the sale's **returnable lines limited to what that gift receipt lists**, with no buyer, no payment and no customer: the same shape as `by-receipt` minus what the recipient must not learn from the buyer's sale. `POST /orders/{id}/returns` takes an optional `giftCode`; with it the return may name only lines on that gift receipt and only up to the quantities on it (`409 ORDER_GIFT_LINE_NOT_ON_RECEIPT`), still within what the sale can take back. The refund is **at what the buyer paid** (pure `ReturnValue`, VAT included, so a discounted item comes back at the discounted price) and **only to store credit or a gift card**: the buyer's card, cash or account is never credited to a stranger (`409 ORDER_GIFT_CREDIT_ONLY` for ORIGINAL; the exchange path is allowed). Store credit goes to the recipient, whom the till identifies (a customer record it finds or creates, [phone-at-the-till](phone-at-the-till.md)): order-svc sets the refund's `customerId` to the recipient, so payment-svc's `PaymentRefunded` and customer-svc credit the right person; a recipient the till does not identify gets a gift card (`paidBy: RETURN`, as no-receipt returns do). The **loyalty points the sale earned** are still taken back from the buyer by the existing `OrderReturned` handling (the sale's customer), as they are for any return. The return records `gift_receipt_id` and is marked *via gift receipt* on the trail. The window counts from the sale date, or from `gift_window_days` where the business sets one (null until set, meaning the ordinary window; no number is assumed); outside it, `403 ORDER_RETURN_NEEDS_MANAGER` as for any return. A code that finds nothing is `404 ORDER_GIFT_RECEIPT_NOT_FOUND` for every reason (unknown, another business's, another store's, a voided sale's), so a code cannot be probed.
  3. **Gifts sent online (order-svc, app storefront).** Checkout accepts `gift: true` and an optional `giftMessage` (up to 500 characters, `400 ORDER_GIFT_MESSAGE_TOO_LONG`) stored on the order; the message and the flag never change price, tender, refund or the buyer's own emails. The store that picks a gift order sees *Gift: print without prices* on the pick screen and makes the gift receipt with slice 1 for the parcel; the packing slip shows the message and no money. A gift order returned by the recipient at a shop goes through slice 2; an online return by code is [shopper-returns](shopper-returns.md)'s and out here.
- **Born with its checks** (each is also an Acceptance line):
  - *Who and where:* staff at the sale's store; never the storekeeper, a shopper or another store's staff.
  - *Authority:* none new. A gift receipt moves no money and no stock; the return it leads to keeps `sales.return`, `sales.refund` and the return policy exactly as they are (no new approvals key, said here so a reviewer sees it was considered).
  - *Retry safety:* `Idempotency-Key` on creating a gift receipt (a retry answers the same code) and on the gift return (existing).
  - *Isolation:* another business's staff of every role, even naming our order id or a real code, and a shopper, get `404` and nothing moves; a store-held cashier cannot make or look up at another store.
  - *The record:* `gift_receipts` and `gift_receipt_lines` are append-only with who and when; every copy is on the receipt copy log; the return names the gift receipt.
  - *Abuse:* `receipts.copies` (existing metric) counts gift copies per cashier; new `gift_receipts.lookup_misses` (staff, store) counts codes that found nothing, the guessing signature.
  - *Refusals:* stable codes below.
  - *Location-neutral:* no currency, tax, date format or language is assumed: the date is the store's own local day, the words are the store's language, and the *not a tax document* wording is the receipt's, not a regime's.
- **Out, on purpose:**
  - **Refunding a gift return to the buyer's original tender.** The recipient is not the payer; a gift card or store credit is the standard and the only one built.
  - **Refunding at today's lower price.** Some retailers credit the lower of the price paid and the current price; StoreQL credits what was paid, as every other return does, and a business that wants the lower rule can ask for it as a policy on [return-controls](return-controls.md).
  - **A shopper's self-service return by gift code.** [shopper-returns](shopper-returns.md) owns online returns; a gift code there is its own step.
  - **A gift wrap charge, a gift-card-as-gift-receipt, a delivery-date message.** Not asked for; a gift card is [stored-value-lifecycle](stored-value-lifecycle.md).
  - **Hiding the price the till shows the recipient at a return.** The credit is the price paid and the cashier says it; only the printed receipt is price-free.

## Data and flow

- **Owned by order-svc:** `gift_receipts` (tenant, id, order, store, code unique per tenant, created by and at, idempotency key unique per tenant) and `gift_receipt_lines` (order item, qty); both append-only. `order_receipts` (the copy log) gains a `variant` (STANDARD or GIFT) and `gift_receipt_id`. `orders` gains `is_gift` and `gift_message`. `returns` gains `gift_receipt_id`. A business setting `gift_window_days` on the return policy row (nullable). Every table has `tenant_id` and an index starting `(tenant_id, …)`, code lookups by `(tenant_id, code)`.
- **Needs from other services:** the store's name, address and zone from tenant-svc (`TenantProfiles`, cached, never joined); the recipient's customer record from customer-svc (existing REST); nothing else.
- **Events published:** none new. `OrderReturned` and the refund's `PaymentRefunded` gain nothing except that the refund's `customerId` is the recipient for a gift return; the email goes through notification-svc's existing send path.
- **Retryable writes (Idempotency-Key):** create a gift receipt; the return (exists).
- **New error codes:** `404 ORDER_GIFT_RECEIPT_NOT_FOUND`, `409 ORDER_GIFT_RECEIPT_SALE_NOT_ELIGIBLE`, `400 ORDER_GIFT_LINES_INVALID`, `409 ORDER_GIFT_LINE_NOT_ON_RECEIPT`, `409 ORDER_GIFT_CREDIT_ONLY`, `400 ORDER_GIFT_MESSAGE_TOO_LONG`; existing `403 STORE_ACCESS_DENIED`, `403 ORDER_RETURN_NEEDS_MANAGER`, `404 ORDER_NOT_FOUND`.

## Money, time and limits

- **Currency:** the gift receipt shows no money. The refund is in the sale's currency at what was paid.
- **Ledger postings:** none new; the refund posts as any store-credit or gift-card refund ([return-controls](return-controls.md)).
- **Dates:** the sale date in the store's zone on the document; the return-by date is that date plus the window, in the same zone.
- **Plan limits:** none.

## Constraints

- **Golden rules:** 1; 3 (tenant from the token, store checked); 8 (gift receipts and the copy log are append-only); 10 (a price-free DTO by construction, no entity over HTTP); 11; 15.
- **Nothing leaks:** the DTO has no money type at all, and a test walks its fields and its JSON to prove no price, total or tender is present; the lookup omits the buyer and the payment.
- **Fiscal regimes:** a gift receipt takes no fiscal number, and printing it never consumes one (`GiftReceiptIT.consumesNoFiscalNumber`), so a business under a fiscal regime keeps a gapless series.
- **Personal data:** the gift message is free text tied to the order and is cleared with the order's other customer text on an erasure ([privacy-requests](privacy-requests.md), `customer_erasures`).
- **Existing tenants:** nothing changes until a cashier makes a gift receipt or a shopper ticks "gift"; `gift_window_days` is null.

## Open questions

- [x] Refund a gift return to the original tender? → **No; store credit or a gift card only** (industry standard: the recipient is not the payer, under the user's standing instruction of 2026-09-30)
- [x] At what value? → **What the buyer paid, VAT included, as every return** (industry standard: a gift receipt hides the price on paper only, under the user's standing instruction of 2026-09-30)
- [x] How does a return find a gift receipt? → **By an unguessable code, printed as text and a barcode; a miss is `404` for every reason** (industry standard: no probing, under the user's standing instruction of 2026-09-30)
- [x] Does a gift receipt take a fiscal number? → **No; it is not a tax document and is never in the fiscal series** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is the return window different for gifts? → **A business setting, null until set, meaning the ordinary window** (industry standard: no number assumed, under the user's standing instruction of 2026-09-30)
- [x] Does the buyer lose loyalty points on a gift return? → **Yes, as for any return of their sale** (industry standard: points follow the sale, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A gift receipt for a completed till sale returns a document with no price, total, tax, discount, tender, buyer or card field, and the whole-sale and chosen-lines forms both work — `GiftReceiptIT.noPricesAnywhere`, `.wholeSaleAndChosenLines`, pure `GiftReceiptDtoTest.carriesNoMoney` (walks the record's components)
- [ ] The code is ten unambiguous characters, unique for the business and not derived from the sale — pure `GiftCodeTest`, `GiftReceiptIT.codeUniqueAndNotDerived`
- [ ] A gift receipt consumes no fiscal number and is never in the fiscal series — `GiftReceiptIT.consumesNoFiscalNumber`
- [ ] A voided, cancelled or unfinished sale is refused `409 ORDER_GIFT_RECEIPT_SALE_NOT_ELIGIBLE`; lines or quantities beyond the sale are `400 ORDER_GIFT_LINES_INVALID` — `GiftReceiptIT.refusals`
- [ ] Making, reprinting and emailing a gift receipt each write the copy log with variant `GIFT`; an emailed gift copy is price-free and not flagged `ADDRESS_DIFFERS` — `GiftReceiptIT.copyLogAndEmail`, notification-svc `GiftReceiptEmailIT`
- [ ] A retried creation answers the same code — `GiftReceiptIT.retryAnswersTheSameCode`
- [ ] The code finds only the lines on the gift receipt, with no buyer, payment or customer; a code that finds nothing is `404 ORDER_GIFT_RECEIPT_NOT_FOUND` whether unknown, another business's, another store's or a voided sale's — `GiftLookupIT.onlyTheGiftedLines`, `.aMissIsAlwaysNotFound`
- [ ] A gift return takes only lines and quantities on the receipt, refunds what was paid to store credit (the recipient's) or a gift card, and refuses the original tender `409 ORDER_GIFT_CREDIT_ONLY` — `GiftReturnIT.creditOnlyAtWhatWasPaid`, `.lineNotOnTheReceipt`
- [ ] The buyer's loyalty points are taken back; the credit reaches the recipient, not the buyer — `GiftReturnIT.pointsFromTheBuyerCreditToTheRecipient` (with customer-svc)
- [ ] The ordinary controls still apply: past the window, above the ceiling or a damaged item needs a manager (`403 ORDER_RETURN_NEEDS_MANAGER`); `gift_window_days` unset means the ordinary window — `GiftReturnIT.controlsStillApply`, `.giftWindowOnlyWhenSet`
- [ ] A gift order online carries its flag and message, the pick screen offers the price-free receipt, and the flag changes no price, tender or refund — `GiftOrderIT.flagAndMessage`, `.changesNoMoney`; an over-long message is `400 ORDER_GIFT_MESSAGE_TOO_LONG`
- [ ] Another business's staff of every role, even holding a real code, and a shopper, read and return nothing (`404`/`403`); a cashier of another store is refused `403 STORE_ACCESS_DENIED`; a storekeeper cannot make one — `GiftReceiptIsolationIT`
- [ ] Repeated misses raise `gift_receipts.lookup_misses` under a rule and none without one; gift copies count toward `receipts.copies` — `GiftReceiptAlertIT`
- [ ] The gift message is cleared when the customer is erased — `GiftOrderIT.erasureClearsTheMessage`
- [ ] Widget: the till's Gift receipt dialog (whole sale or lines, print or email) and the returns screen's gift-code path, the storefront's gift choice — `gift_receipt_dialog_test.dart`, `returns_gift_code_test.dart`, `checkout_gift_test.dart`; k6 `gift-receipt-flow`; the flow guards stay green

## Screens

- **POS → Receipt:** *Gift receipt* beside Print and Email: choose the whole sale or tick lines, choose print, save or email (an address field for email); the preview shows exactly the price-free document and its barcode.
- **POS → Returns:** a *Scan gift receipt* field beside the receipt number; the found lines are limited to the gift receipt's; the refund method offers only *Store credit* (asks for the recipient's phone or name through the phone-at-the-till finder) and *Gift card*; a clear note says why the original tender is not offered.
- **Admin → Orders → an order → Receipts:** the gift receipts made, who and when, and *Print again*; **Pick screen:** *Gift: print without prices* for a gift order.
- **Storefront checkout:** a *This is a gift* checkbox and a short message field.
- Words not codes, dates through `AppFormat` in the store's zone, adaptive per UI-GUIDE §7.2; strings in the store's language.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **Gift receipts and returns.** A gift return is a return under [return-controls](return-controls.md): same window, condition and policy rules, credit only (store credit or gift card, never the original tender) and no buyer shown. [shopper-returns](shopper-returns.md) requests are the buyer's and stay untouched; a gift recipient has no login here and returns at a store. The copy log is `order_receipts` ([till-sessions-and-registers](till-sessions-and-registers.md) slice 9), and `receipts.copies` counts gift copies too.

Filled while building.

## Flow Tests entry

Area `pos`, file `target/flow-catalogue/pos/gift-receipts.json` (order 16, actors CASHIER, MANAGER, OWNER, shopper at checkout; ui: POS receipt and returns, admin order receipts, storefront checkout; api: the endpoints above). Cases, each automated before the page is BUILT:

- **happy** GR-01 a gift receipt for a till sale with no price (`GiftReceiptIT.noPricesAnywhere`); GR-02 the recipient returns by code for store credit (`GiftReturnIT.creditOnlyAtWhatWasPaid`); GR-03 an online gift order.
- **negative** GR-04 a voided sale; GR-05 a line not on the receipt; GR-06 the original tender refused; GR-07 an unknown code is `404`.
- **override** GR-08 a gift return past the window needs a manager; GR-09 a manager's step-up covers a cashier (existing approvals key `sales.return`).
- **isolation** GR-10 another business with a real code; GR-11 a cashier of another store; GR-12 a shopper and a storekeeper (`GiftReceiptIsolationIT`).
- **edge** GR-13 a partial-quantity gift receipt, returned in two visits; GR-14 a retried creation; GR-15 no fiscal number consumed; GR-16 a discounted line refunds what was paid.
- **audit** GR-17 the copy log and the return name the gift receipt; GR-18 misses raise the alert; GR-19 erasure clears the message.
