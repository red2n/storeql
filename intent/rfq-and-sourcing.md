# RFQ and sourcing

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the readiness review's Procurement & supplier management row · 2026-09-24 (this page written as the feature was finished; the folder arrived the same day) |
| **Roadmap** | Readiness Review, "RFQ and sourcing", Absent → Built at v165 |
| **Services** | purchase-svc owns the data · tenant-svc's FX rates are read through common-service's `FxRates`; the supplier's scorecard grade comes from purchase-svc's own scorecards |
| **Builds on** | `suppliers` (with the quoted lead time), `purchase_orders` and their lines (an award raises drafts the way a proposal or a dropship does), `supplier_deliveries` and the scorecards, the Procurement screen |
| **Built in** | 168f8c6c (the feature), the verification and regrade commit that follows it |

## Problem

A buyer who can get the same lines from several suppliers asks them by email or on the phone, gets three prices in two currencies and a lead time each, and decides from memory: the cheapest column, remembered wrong against the supplier who was late all spring. Nothing on the platform records what was asked, what came back, or why one supplier got the order, so the next buyer starts again and a later change undoes a decision nobody wrote down.

## Outcome

The buyer raises one request for the lines wanted and the suppliers to ask, records each supplier's quote as it comes back (or that they declined), sees every price side by side in the business's own money with the lowest per line marked, each bid's total ranked, and each supplier's scorecard grade beside their prices, and awards the lines. The award raises a draft purchase order per supplier at exactly the quoted prices for a person to submit as any draft. The request stays as the record of who was asked, what they said and who won.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store owner and the buyer (manager or storekeeper) in the back office.
- **Channels:** back-office only.
- **Scope:** tenant-wide; a request names the store the goods are for, which becomes the orders' store.
- **Roles that can write:** OWNER, MANAGER, STOREKEEPER (buying is warehouse and management work, not the till); any staff read.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:** raise a request (DRAFT), issue it, record a quote per supplier in their currency (terms and a price per line, replacing an earlier quote) or a decline, compare at home, award lines to suppliers who priced them, cancel with a reason, list by status.
- **Out, on purpose:**
  - Sending the request to the supplier. A supplier portal and an email are their own roadmap rows; here the buyer records what came back.
  - A closing date that closes the request by itself. `closesOn` is advisory; the buyer closes a request by awarding or cancelling it, because a quote that arrives a day late is still a quote the buyer may want.
  - Weighting price against the grade into one recommendation. The buyer reads both and decides; the platform marks the lowest and shows the grade, nothing more.
  - Re-quoting rounds, attachments, and a contract or price list written from the award.
  - Deriving a promise for the orders from the request beyond what was said: expected delivery is the day the goods are needed, else the quoted lead time from today, else nothing.

## Data and flow

- **Owned by** purchase-svc: `rfq_series`, `rfqs`, `rfq_lines`, `rfq_suppliers`, `rfq_quote_lines`, `rfq_awards` (a line awarded once). The order's `source` may now be `RFQ`.
- **Needs from other services:** the business's home currency and its FX rates, through common-service's cached `TenantProfiles` and `FxRates` (never a join). Store ids are tenant-svc's, referenced only.
- **Events published:** none. Nobody consumes a request yet; an email to the supplier on issue would be the first consumer and is out of scope.
- **Retryable writes** (Idempotency-Key): none. A request is awarded once by status; a quote replaces the earlier quote as a set.
- **New error codes:** `PURCHASE_RFQ_NOT_FOUND` 404; `PURCHASE_RFQ_LINES_REQUIRED`, `PURCHASE_RFQ_SUPPLIERS_REQUIRED`, `PURCHASE_RFQ_LINE_DUPLICATE`, `PURCHASE_RFQ_SUPPLIER_DUPLICATE`, `PURCHASE_RFQ_STATUS_INVALID`, `PURCHASE_RFQ_SUPPLIER_NOT_INVITED`, `PURCHASE_RFQ_LINE_UNKNOWN`, `PURCHASE_RFQ_QUOTE_EMPTY`, `PURCHASE_RFQ_AWARDS_REQUIRED`, `PURCHASE_RFQ_AWARD_DUPLICATE` 400; `PURCHASE_RFQ_NOT_DRAFT`, `PURCHASE_RFQ_NOT_ISSUED`, `PURCHASE_RFQ_NOT_QUOTED`, `PURCHASE_RFQ_CLOSED` 409.

## Money, time and limits

- **Currency:** a quote is in the supplier's currency (their own when unsaid); the comparison translates every price into the home currency through the rates the business keeps. A price with no rate is shown but never the lowest; a bid that cannot be added up at home is shown but never ranked. The orders raised are in the quote's currency.
- **Ledger postings:** none here; the draft orders post as any order does when received.
- **Dates:** the moments a request was raised, issued, awarded and cancelled are recorded; `neededBy` becomes the orders' expected delivery.
- **Plan limits:** none.

## Constraints

Database-per-service (rule 1): the store and the FX rates are read, never joined. The award raises orders through the same repository calls a proposal and a dropship use, so an order from an RFQ behaves as any draft. Money stays `BigDecimal`; line totals are rounded to two decimals, translations to the home currency's minor units through `Fx`.

## Open questions

- [x] Who may raise, quote and award? Recommended: the roles that may raise a purchase order (OWNER, MANAGER, STOREKEEPER). → **accepted as recommended** (built under the standing instruction to take the next roadmap row without questions, 2026-09-24)
- [x] Should the award be one transaction with the orders it raises? Recommended: raise the orders first and record the award last, so a lost race leaves cancellable drafts and never an award without its orders. → **accepted as recommended** (2026-09-24)
- [x] Should a quote with no rate at home be refused? Recommended: shown, never lowest, never ranked, so the buyer sees it and the platform guesses nothing. → **accepted as recommended** (2026-09-24)

## Acceptance

- [x] A request is raised as a numbered draft with its lines and the suppliers invited, and refused with nothing to quote for, nobody to ask, a line twice or a supplier nobody has — `RfqIT.anRfqIsRaisedIssuedQuotedAndComparedInTheBusinessesOwnMoney`
- [x] A quote before the request is issued is refused `409 PURCHASE_RFQ_NOT_ISSUED`; issued once — the same test
- [x] Two quotes in different currencies and a decline are compared at home: the lowest per line marked, totals and ranks, the decliner unranked — the same test, and `RfqTest` (pure: the euro quote at 9.35 beating ten pounds; a partial and an untranslatable bid shown but unranked)
- [x] An uninvited supplier, an unknown line, an empty quote and a cashier are refused — the same test
- [x] Another tenant cannot see the request — the same test (404 and an empty list for the other tenant)
- [x] An award gives each line to a supplier who priced it and raises one DRAFT order per supplier in their currency at the quoted price for the day the goods are needed; a line to a non-quoter is refused `409 PURCHASE_RFQ_NOT_QUOTED`; awarded once — `RfqIT.anAwardRaisesADraftOrderPerSupplierAtTheQuotedPricesInTheirMoney`
- [x] A request is cancelled with a reason, takes no more quotes, and the next is numbered after — `RfqIT.anRfqIsCancelledWithAReasonAndTakesNoMoreQuotes`
- [x] The Sourcing tab lists requests, marks the lowest per line and the ranks, awards with the lowest picked, records a quote, and shows a cashier no buttons — `sourcing_test` (5)
- [x] Through the gateway, with a euro rate the business keeps — k6 `rfq-flow` (18)

## Decisions

- **The buyer records the quote.** No supplier writes to the platform; a portal is its own row. The quote is `PUT` and replaces the earlier one as a set, so a corrected quote never leaves a stale price on a line.
- **Orders first, award last.** The award's transaction records the status and the award rows after the draft orders exist; a lost race leaves drafts a person can cancel, never an award pointing at orders that were never raised.
- **Untranslatable is shown, not guessed.** A price with no rate at home appears in the comparison with no home figure, is never the lowest and keeps its bid out of the ranking; the platform fetches no rates and guesses none (the Exchange rates convention).
- **The grade travels with the bid.** Each bid carries the supplier's scorecard grade over the last 90 days, from the row built just before this one, so price is read against the record without a second screen.
- **Fill of the orders raised is the orders' own.** An RFQ order is an ordinary draft: a person submits it, spend authority applies, and its receipt measures the supplier's delivery like any other.
