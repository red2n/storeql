# Shopper returns: request from My Orders, drop-off or carrier label, stock linked to its return, recall quantities

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | new: the flow catalogue's open findings, wave 2 — online/ret-online-return (RET-43), returns/ret-recall-settlement, returns/ret-stock-disposition |
| **Services** | order-svc owns return requests, the return policy's self-service settings and the carrier connections and labels · inventory-svc owns the link from a return to the stock it made · payment-svc, customer-svc, purchase-svc act on the existing `OrderReturned` / `PaymentRefunded` unchanged · notification-svc tells the shopper · the app gives the shopper a Return action and staff a Received screen |
| **Builds on** | `returns` / `return_items` and `createReturn` (return-controls, BUILT), `return_policies`, `order_handovers`, `recall_notices` / `resolveByReturnTx`, inventory-svc `receiveReturnFromOrderOnce` and batch `material_status`, notification-svc `Catalogue`, the storefront `orders_screen.dart`, `docs/ACCOUNTING-CONNECTORS.md` (the driver pattern) |
| **Built in** | |

## Problem

A shopper who wants to send something back has to phone or visit: My Orders is read-only, and every return is filed by staff in the admin Orders screen the moment they hear about it. Two things follow. There is no "awaiting the parcel" state, so a refund is either made before the goods are back or the shopper waits for someone to remember. And for a delivered order nobody has a label to give the shopper. Behind the counter, a manager who wants to quarantine a bad return cannot get from "Return #X" to the batch it made (the movement carries only `RET-<shortRef>`), and an auditor cannot show that a return's goods were later written off rather than quietly resold. A recall-driven return resolves the whole notice whatever was brought back, and the return dialog does not offer the notice.

## Outcome

- **The shopper starts it.** On any order they can return, My Orders offers *Return items*: which lines and how many, why (from a fixed list of reasons), the condition as they see it (sealed, opened, damaged, faulty), a note, and how they would like to be paid (back to how they paid, or store credit). They see at once whether the shop accepts it, needs a person to look, or cannot take it, in words.
- **The policy decides what is offered.** The business's return policy (window, faulty goods never refused outright, recall never held) is the same one the till uses. A business that has not turned self-service on offers nothing; nobody is surprised.
- **Bringing it back is a choice.** The default is drop-off at any of the business's stores (a reference and a code to show). Where the business has connected a carrier, and the order was delivered, the shopper may instead print a return label.
- **The refund waits for the goods.** Nothing moves at request time. Staff at a store scan or type the reference when the parcel or person arrives, confirm what is actually in the box and its condition, and complete the return through the existing return controls. The refund, stock disposition, loyalty reversal and books are exactly today's.
- **A manager can jump from a return to its stock.** From the return, the batches it made, their current status, and what has happened to them since (sold, written off, moved) in one view.
- **A recall notice resolves only when its goods are back.** A partial return of a recalled line leaves the notice open and says how many are still out; staff see the notice offered in the return dialog and at the till.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **shopper** (starts and follows a request); the **store staff** (receive, confirm condition, complete); the **store manager** (looks at outside-policy requests, disposition); the **owner** (turns self-service and carriers on).
- **Channels:** ONLINE storefront (request), back-office and POS (receive), notices by the shopper's chosen channels ([shopper-notices](shopper-notices.md)).
- **Scope:** the request belongs to the order's business; drop-off is at any store of that business (as a return already may be); the policy is per business; a label is per business connection.
- **Roles that can write:** the shopper who placed the order (role CUSTOMER, `orders.login_id`) requests and cancels; any staff at a store receive and complete; a `sales.refund` holder decides a request that needs review, exactly as for a till return; OWNER and MANAGER set the self-service settings and connect a carrier.
- **Sandbox tenant:** behaves the same; the `Carrier` driver is forced to `SIMULATED` and no label is ever bought.

## Scope

- **Already there (verified 2026-09-30):**
  - The return itself, its conditions, window, cashier ceiling, manager rule, refund methods, loyalty reversal, exchanges and no-receipt returns: `intent/return-controls.md`, BUILT. This page only adds a way in and a wait.
  - `GET /orders/recall-notices?orderId=` (wave 1, `RecallNoticeIT.anOrdersNoticesAreListedAcrossRecallsForTheReturnDialogAndOnlyToStaffAtItsStore`): the endpoint the return dialog needs to offer a notice. The Flutter side is the only part missing (slice 5).
  - A recall return already skips the window and the ceiling, needs no condition and sends goods to RECALLED (return-controls Decisions).
  - Shopper reads their own returns (`GET` own 200, neighbour 404, `POST` 403: `ReturnGuardsIT.aShopperReadsButCannotReturn`); that stays: a shopper never files a return directly, only a request.
- **In (slices in build order):**
  1. **The request and its policy (order-svc, app).** `return_requests` and lines; the shopper's `POST`, the eligibility answer, cancel, and the shopper's list and detail. States REQUESTED, NEEDS_REVIEW, APPROVED, DECLINED, RECEIVED (a return exists), CANCELLED, EXPIRED. Self-service settings on the return policy (off by default). Staff see requests in the admin Orders screen and at the till's Returns screen, and decide NEEDS_REVIEW ones. Notices: `ReturnRequested`, `ReturnRequestDecided` (notification-svc types added to the catalogue).
  2. **Receive and complete (order-svc, app).** `POST /orders/return-requests/{id}/receive` by staff: lines actually received, condition confirmed per line (the shopper's condition is only a claim), then the same code path as `createReturn` (window, ceiling and manager rules apply on the confirmed facts, refund method as chosen). The return is linked to the request (`returns.request_id`). A request past its "send by" date (a policy setting, unset = never) becomes EXPIRED by a sweep.
  2b. **Drop-off reference.** The request carries `Ids.shortRef` and a QR of the request id; the till's Returns screen finds a request by it (like the receipt lookup: business-wide, store-held callers see all requests for drop-off since goods may be handed in at any store, but only their store's completed returns).
  3. **Carrier labels (order-svc).** A `Carrier` interface (`client.carrier`), drivers `SIMULATED` and stub-tested real ones, one connection per business (`carrier_connections`, credentials sealed under `storeql.carriers.secrets-key`, from `.env`). Offered only for an order with a recorded DISPATCHED handover, a connected carrier and the policy's `labels` on. `return_labels` keeps carrier, tracking reference, label URL (the carrier's; the document itself is not stored) and the carrier's cost where it reports one. The same interface answers `track(reference)` on demand for the shopper's and staff's view; no polling worker.
  4. **A return linked to its stock (inventory-svc, app).** `return_dispositions` written on the `OrderReturned` handling's own transaction: one row per returned line naming the batch made, the movement, the condition and the return id. `GET /admin/inventory/returns/{returnId}` answers the batches with their current material status and every later movement on them. Status changes and write-offs of such a batch carry `sourceReturnId` in `MaterialStatusChanged` / `StockAdjusted`, so the chain is readable both ways. The admin return detail gets *Stock from this return*.
  5. **Recall quantities (order-svc, app).** `recall_notice_returns` (append-only) counts what returned goods each notice has received per line; the notice resolves REFUNDED only when every affected line is covered; the return dialog and the till's Returns screen offer the order's open notices and send `recallNoticeId`.
- **Out, on purpose:**
  - **Charging the shopper for the return label, or a restocking fee.** Both reach refunds, VAT and consumer law that differ by country; a label is the business's cost or the shopper's own drop-off. Return-controls already keeps restocking fees out.
  - **Refunding before the goods arrive (advance refund on a carrier scan).** Some retailers do; it is a risk choice for a later, per-business setting. Here the refund waits for staff to receive.
  - **Exchange requests from the app.** A shopper asks for a return; an exchange stays a till action (return-controls).
  - **Return labels for a PICKUP order, or an order with no handover.** The goods never left; drop-off is the way.
  - **Polling carriers, tracking webhooks, pickup scheduling and label PDFs kept by us.** Transport and route planning is its own row.
  - **Return-fraud velocity alerts.** Metric named for the exception-alerts page: `return_requests.count` (per period), which that page alerts on. Not designed here.
  - **A second approval mechanism.** A request that needs review is decided by a `sales.refund` holder, as in the till; anything above a ceiling uses the approvals page's mechanism (action key `sales.refund`).
  - **Writing off DAMAGED returned stock.** Stays `stock.adjust` / `stock.writeoff` (approvals page); this page only links it to its return.

## Data and flow

- **Owned by order-svc:**
  - `return_requests`: id, tenant, order, requester login, status, `reason`, `note`, `method` (ORIGINAL or STORE_CREDIT), `route` (DROP_OFF or LABEL), `recall_notice_id` (optional), `needs_review_reasons` (list), `decided_by`, `decided_at`, `send_by` (instant, null when the policy has none), `return_id` (once received), `idempotency_key` (unique per tenant), created/updated in UTC. Status moves are recorded in append-only `return_request_history`.
  - `return_request_lines`: request, order item, variant, quantity, claimed condition, `received_qty` and `confirmed_condition` filled at receipt.
  - `return_policies` gains `self_service` (off), `labels` (off), `auto_approve_within_policy` (off: every request waits for a person unless turned on), `send_by_days` (null = no deadline).
  - `carrier_connections`: tenant, carrier key (a row in `Carriers.CATALOGUE`, with a `ck_carrier_provider` check), sealed credentials, status, `origin_store_id` optional; `return_labels`: request, connection, tracking reference, label URL, cost and currency (optional), issued at, void flag. Labels are append-only; voiding writes a new row.
  - `recall_notice_returns`: tenant, notice, return, variant, qty (append-only).
  - `returns` gains `request_id`. Returns stay append-only.
- **Owned by inventory-svc:** `return_dispositions` (tenant, return id, order id, variant, batch id, movement id, condition, created at) written once per return line (unique on return and line, so a replayed event adds nothing).
- **Needs from other services:** tenant-svc home currency and store list through `TenantProfiles` (drop-off store names); notification-svc learns from events; carriers over HTTP through the driver only (`service/` stays HTTP-free). No joins: the admin view calls inventory-svc's endpoint by return id.
- **Events published:** `ReturnRequested` and `ReturnRequestDecided` (order-svc, topic `storeql.order.return-requested`, `...return-request-decided`; fields tenant, request, order, customer, status, reasons; notification-svc sends the shopper's notice once per event and tells staff of a NEEDS_REVIEW request in-app). `OrderReturned` gains `requestId` (additive). `MaterialStatusChanged` and `StockAdjusted` gain `sourceReturnId` (additive). `RecallNoticeUpdated` (existing name, if present, else `RecallNoticeReturned`) carries returned-so-far.
- **Retryable writes (Idempotency-Key):** the shopper's `POST .../return-requests` (replay answers the first), `receive`, and label purchase (the carrier's own idempotency, else a derived key `Ids.derived(requestId, "label")`; an unanswered purchase with no carrier key is `UNCERTAIN` and waits for a person, as accounting pushes do).
- **Endpoints:** `POST /orders/{id}/return-requests` (CUSTOMER of the order); `GET /orders/return-requests?status=&after=&limit=` (shopper: own; staff: business, cursor); `GET /orders/return-requests/{id}`; `POST .../{id}/cancel` (shopper, while REQUESTED/NEEDS_REVIEW/APPROVED); `POST .../{id}/decision {approve, note}` (`sales.refund`); `POST .../{id}/label` (shopper, route LABEL, APPROVED); `POST .../{id}/receive` (staff); `PUT /admin/orders/return-policy` extended; `PUT/GET /admin/orders/carriers` (OWNER/MANAGER); inventory-svc `GET /admin/inventory/returns/{returnId}`.
- **New error codes:**
  - `409 ORDER_RETURN_SELF_SERVICE_OFF` (the business has not turned it on).
  - `409 ORDER_RETURN_REQUEST_NOT_ELIGIBLE` with `details` naming why: not handed over, order voided/cancelled, nothing left to return, an open request already covers the line.
  - `404 ORDER_RETURN_REQUEST_NOT_FOUND` (also another business's, another shopper's).
  - `409 ORDER_RETURN_REQUEST_CLOSED` (already decided, received, cancelled or expired).
  - `409 ORDER_RETURN_LABEL_NOT_OFFERED` (no carrier, PICKUP order, no handover, labels off) and `502 ORDER_RETURN_LABEL_UNAVAILABLE` (the carrier failed; the shopper is told to drop off instead).
  - `400 ORDER_RETURN_REASON_UNKNOWN`, existing `ORDER_RETURN_CONDITION_REQUIRED`, `RETURN_QTY_EXCEEDS_PURCHASED`.
  - `409 RECALL_NOTICE_LINE_MISMATCH` (a returned variant is not on the notice) and `409 RECALL_NOTICE_QTY_EXCEEDED` (more than the notice's affected quantity).

## Money, time and limits

- **Currency:** the refund is in the sale's currency by the existing return path; nothing new is priced. A label's cost (if the carrier says) is recorded in the carrier's currency for information and never charged to anyone.
- **Ledger postings:** none new. The refund posts as any return's does (`PaymentRefunded`); a label cost is not posted (it is the business's carrier invoice, an accounts-payable matter).
- **Dates:** the window counts from the handover in the store's zone as return-controls fixes; the request records `created_at` in UTC; `send_by` = the decision time plus the policy's days, in UTC. The window is checked when the request is made **and** when staff receive: a request made inside the window is honoured on receipt within its `send_by`, never refused for the days spent in the post.
- **Plan limits:** none.

## Constraints

- **Golden rules:** 1 (inventory-svc is asked by REST, never joined); 3 (tenant and the shopper's login from the JWT; a request names neither); 6/7 (events through the outbox, consumers idempotent by event id); 8 (requests' history, labels, dispositions and notice returns are append-only); 10 (DTOs); 11 (keys above); 15 (a shopper's lines, quantities and reasons validated: quantity at most what is still returnable).
- **Location-neutral:** reasons are a fixed platform list of words a shopper picks (not law); no country's cooling-off period is assumed: the window is the business's own, and the rule "faulty goods are never refused outright, only sent to review" is return-controls'. A business in a place with a statutory withdrawal right sets its window accordingly.
- **Existing tenants:** self-service off, labels off: nothing changes on the day this ships. Existing returns keep what they recorded.
- **Flow guards:** `flow-guard-comprehensive` and `flow-guard-runtime` must stay green (orders and stock touched).
- **Tenant isolation:** every read and write of a request filters by tenant first; a shopper reads only requests of orders their login placed.

## Open questions

- [x] **Does the refund wait for the goods?** Recommended: yes; refund on receipt, on the staff-confirmed condition. → **yes, on receipt** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Who approves a request?** Recommended: within policy, auto-approved only if the business turned that on; otherwise a person; outside policy, needs review by a `sales.refund` holder; faulty past the window and recall are never declined by the system. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Default route?** Recommended: drop-off at a store; a label only where a carrier is connected and the order was delivered. → **drop-off default** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Who pays the label?** Recommended: never the shopper through us; the business's own carrier account. → **business's account** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which carriers?** Recommended: `SIMULATED` plus real drivers added one row at a time, each with a stub test of its exact request shape; none is named or assumed. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Partial recall returns?** Recommended: the notice stays open until every affected line is covered; staff may still settle a notice by hand as today. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Disposition audit?** Recommended: link by return id both ways; the write-off itself stays a stock adjustment with its own approval. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A shopper requests a return for delivered lines and sees the outcome in words; nothing is refunded or restocked. — order-svc `ReturnRequestIT.aShopperRequestsAndNothingMoves`
- [ ] With self-service off the request is refused `409 ORDER_RETURN_SELF_SERVICE_OFF`. — `ReturnRequestIT.offByDefault`
- [ ] A request for an order not handed over, or for more than is left, is refused `409 ORDER_RETURN_REQUEST_NOT_ELIGIBLE` naming why. — `ReturnRequestIT.eligibilityIsNamed`
- [ ] Past the window the request goes to NEEDS_REVIEW; faulty past the window and a recall notice are never DECLINED by the system. — `ReturnRequestIT.outsidePolicyGoesToReview`, `faultyPastTheWindowIsNeverDeclined`, pure `ReturnRequestsTest`
- [ ] A retried request answers with the first. — `ReturnRequestIT.aRetryAnswersWithTheFirst`
- [ ] Staff receive it: confirmed condition drives stock (opened to INSPECTION, damaged to DAMAGED); refund and loyalty reversal happen once; a request made inside the window is honoured on receipt after it. — `ReturnRequestIT.receiveCompletesThroughTheReturnControls`, `honouredInTransit`; inventory-svc `ReturnDispositionIT` (existing) unchanged
- [ ] A request past `send_by` becomes EXPIRED and can no longer be received (`409 ORDER_RETURN_REQUEST_CLOSED`). — `ReturnRequestIT.expiresBySweep`
- [ ] A label is offered only after a DISPATCHED handover with a connected carrier; a carrier failure is `502 ORDER_RETURN_LABEL_UNAVAILABLE` and drop-off still works; an unanswered purchase is UNCERTAIN, never bought twice. — `ReturnLabelIT.*`
- [ ] Each real carrier driver builds its exact request against a stub. — `<Carrier>DriverStubTest` per driver; `SimulatedCarrierTest`
- [ ] Another business's staff of every role and shopper, and another shopper of ours, see and act on nothing (404), nothing moves. — `ReturnRequestIT.otherBusinessAndOtherShopperFindNothing`; consumer-side `ReturnDispositionIT.anotherBusinessesEventNeverTouchesOurStock` (existing)
- [ ] From a return a manager reaches its batches, their status and later movements; a write-off of such a batch shows `sourceReturnId`. — inventory-svc `ReturnLinkIT.aReturnLeadsToItsBatchesAndWhatHappenedNext`, `aReplayedEventAddsNoDisposition`; another business gets 404 `ReturnLinkIT.anotherBusinessFindsNothing`
- [ ] A part-return of a recalled line leaves the notice open with the quantity outstanding; the last part resolves it; a variant off the notice is `409 RECALL_NOTICE_LINE_MISMATCH`; more than affected is `409 RECALL_NOTICE_QTY_EXCEEDED`. — `RecallNoticeIT.aPartReturnLeavesTheNoticeOpen`, `theLastPartResolvesIt`, `aLineOffTheNoticeIsRefused`
- [ ] The shopper's Return action, the staff Received screen, the return dialog's recall picker and the till's drop-off lookup. — widget tests `orders_return_request_test.dart`, `return_requests_screen_test.dart`, `orders_return_dialog_recall_test.dart`, `pos_returns_dropoff_test.dart`
- [ ] End to end: k6 `shopper-returns-flow`; `flow-guard-comprehensive` and `flow-guard-runtime` green.

## Decisions

<!-- Filled while building. -->
- (Recorded now) **The Return control's "shopper self-service returns and carrier labels" exclusion is replaced by this page.** `intent/return-controls.md` kept them out as "a separate feature; the rules here are the ones it would call". This is that feature and it calls those rules unchanged; nothing in return-controls is edited.

## Screens

- **Storefront, My Orders** (`orders_screen.dart`): a *Return items* action on an order that was collected or dispatched and has lines left. A sheet (`showAdaptiveSheet`): lines with quantity steppers, reason, condition, note, method, then route (drop-off shows the stores by name; label appears only when offered). Afterwards the order card shows the request's state in words (`status_labels.dart`) with *Cancel request*, the drop-off code, or *Print label* with tracking.
- **Admin, Orders:** a *Return requests* tab (filters by status); the request view with the shopper's claim beside a *Received* form (quantity and condition per line, then the existing method choice); the order's Return / Refund dialog offers the recall notice from `?orderId=`; the return detail shows *Stock from this return* linking to the batch.
- **POS, Returns screen:** find by drop-off code or reference; the same Received form; recall notice offered.
- **Business settings:** the return policy card gains self-service, labels, auto-approve and send-by; a *Carriers* card connects a carrier (credentials write-only).
