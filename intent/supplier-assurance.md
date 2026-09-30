# Supplier assurance: acknowledgement, payee check, sender check, scorecard disputes, re-opening an invoice

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on suppliers and accounts payable (`procurement/purchase-order-lifecycle`, `supplier-master-data`, `payment-run-bank-files`, `inbound-supplier-einvoice`, `supplier-lead-times-scorecards`, `supplier-invoice-three-way-match`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, procurement domain (wave 2, group F). Wave 1 deferred these (`_notes/2026-09-30-purchase-svc.md`, "DEFERRED") |
| **Services** | purchase-svc owns all of it: the acknowledgement, the payee-check result and its gate on payment, the sender check, disputes and corrections to scorecard facts, and the re-opening of an invoice · a bank's verification service is reached through a driver · the admin app carries the screens |
| **Builds on** | `purchase_orders.status`/`expected_delivery`/`submitted_at`, `suppliers` (bank fields, `bank_details_changed_at`, `einvoice_scheme`/`einvoice_id`, `vat_number`), `BANK_DETAILS_CHANGED_RECENTLY` warning and the after-approval change block in `PaymentRunService`, `supplier_einvoices` (statuses NEEDS_SUPPLIER … CAPTURED, REFUSED), `EInvoiceIntake.supplier`, `supplier_deliveries` and `SupplierScorecard`, `supplier_invoices` (MATCHED, FLAGGED, APPROVED, REJECTED), `resolveSupplierInvoice` and its reversal, `ThreeWayMatch`, the accounting-connector driver pattern (`docs/ACCOUNTING-CONNECTORS.md`) |
| **Built in** | not built |

## Problem

The buyer sends an order and never learns whether the supplier accepted it, at what date, or at what price. A supplier's bank details can be changed and the next payment run pays them, with only a warning label; nothing checks that the account name belongs to the supplier. An e-invoice from an address the business has never seen, or one that quotes a different bank account from the one on file, is treated like any other once a person picks a supplier for it, and there is no way to say "never accept anything from this sender". A supplier who thinks a late mark on their scorecard is wrong has no way to say so, and a wrong figure cannot be corrected. And an invoice approved for payment cannot be looked at again when a later return or credit note shows that it was wrong.

## Outcome

- **The buyer records the supplier's acknowledgement** of an order (accepted, changed or declined, the promised date, any price change), and sees which orders are still unacknowledged.
- **New bank details are checked against the payee's name before the first payment to them,** when the business has switched a verification service on, and payment to them waits until the answer is a match or a person accepts the risk.
- **An inbound e-invoice is checked against who it says it is from.** A sender on the business's own denylist is kept out entirely; a sender the business does not know, or an invoice that quotes different bank details from those held, stops for a person, and the bank details on file are never changed from an invoice.
- **A supplier's objection to a scorecard figure is recorded, decided, and, if upheld, corrected without editing the recorded fact.**
- **An approved invoice that is not yet paid can be re-opened** when the picture changes, and the platform suggests when it should be.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **buyer** (storekeeper or manager) recording what suppliers say, the **finance user** approving and paying, the **owner**.
- **Channels:** back-office. Every supplier-side step is **recorded by the buyer** until a supplier portal exists, as the [RFQ page](rfq-and-sourcing.md) does; a portal is out of scope here.
- **Scope:** per business (suppliers are per business); orders per store as now.
- **Roles that can write:** acknowledgement and dispute recording: OWNER, MANAGER, STOREKEEPER. Deciding a dispute, denylist, payee-check settings and re-opening an invoice: management (re-opening also needs `purchasing.invoices.decide`, as deciding does). Overriding an unverified payee and correcting a scorecard fact are approvals actions (below).
- **Sandbox tenant:** the payee check uses SIMULATED; everything else is the same.

## Scope

- **In** (slices, in build order):
  1. **Re-opening an unpaid approved invoice (purchase-svc).** `POST /supplier-invoices/{id}/reopen {reason}` moves an APPROVED or MATCHED invoice back to FLAGGED, keeping `reopened_by`, `reopened_at`, `reopen_reason` and a count. **Nothing is reversed in the ledger:** the liability stands, as the posting model already says ("posted and blocked, not held unposted"); a FLAGGED invoice is simply not payable. It is then decided again: APPROVE releases it, REJECT reverses the posting exactly as today (redated if its month is closed, see [accounting-periods](accounting-periods.md)). An invoice already paid, or sitting in an approved or sent payment run, is refused (a paid one is corrected by a credit note or a vendor return, which exist). **The platform suggests, never re-opens by itself:** when a vendor return is raised or a credit note is recorded against an order, purchase-svc re-runs the match for that order's approved, unpaid invoices and, if a variance now appears, marks them `review_reason` (shown as a chip, listed by a filter).
  2. **Supplier acknowledgement (purchase-svc).** Append-only `purchase_order_acknowledgements`: kind ACCEPTED, CHANGED or DECLINED, how the buyer heard (PHONE, EMAIL, PORTAL, OTHER), a promised date, changed lines (price or quantity), a note. `POST /purchase-orders/{id}/acknowledgements`, allowed while the order is SUBMITTED or PARTIALLY_RECEIVED. The order shows its latest acknowledgement and `acknowledged_at`. **It never changes the order:** approved prices and quantities stay as approved; a changed price is shown beside a price variance at invoice time so the person deciding sees the supplier's own word. **The scorecard keeps measuring against the original promise;** the confirmed date is displayed beside it, so a supplier cannot move the goalposts by acknowledging. A list filter shows submitted orders with no acknowledgement. No event: nothing consumes it until a portal exists.
  3. **Sender check on inbound e-invoices (purchase-svc).** A business-kept denylist, `einvoice_sender_denylist` (kind ELECTRONIC_ADDRESS or VAT_ID or LEGAL_NAME, a normalised value, a reason, who and when; a removal is a dated row, the entry is kept). A document from a listed sender is kept as `BLOCKED_SENDER`, never captured or posted, and the reason is on it. Beyond the list, each document gets sender signals compared with what the business already holds for the supplier: `ADDRESS_NOT_HELD` (the VAT number or name matches a supplier but the sending address is not the one on file), `BANK_ACCOUNT_DIFFERS` (the invoice's payment means name an account other than the supplier's), `VAT_ID_DIFFERS`. Any signal stops the document at `NEEDS_DECISION` for a person, who clears it with a reason that is kept; **the supplier's held address and bank details are never updated from a document.** Creating or editing a supplier with a denylisted address or VAT number is refused.
  4. **Scorecard disputes (purchase-svc).** `supplier_scorecard_disputes`: the supplier, a period, the metric (ON_TIME, FILL, QUALITY, INVOICE_ACCURACY) or one delivery, what the supplier says, how the buyer heard, status OPEN, UPHELD or REJECTED, the outcome note, who and when. Upholding creates an append-only `supplier_delivery_corrections` row (the delivery, the field, the recorded value, the corrected value, the reason, the dispute) and the scorecard reads the latest correction over the recorded fact, which is never edited. Cards show `disputesOpen`. A correction that improves a score is the approvals action `purchasing.scorecard-correction`.
  5. **Confirmation of payee (purchase-svc, driver).** A `PayeeVerifier` interface (given the account identifier, the name and the account type; answers MATCH, CLOSE_MATCH with the name the bank holds, NO_MATCH, NOT_SUPPORTED or UNAVAILABLE) with a **SIMULATED** driver and a first real driver behind it, stub-tested for its exact request shape as the accounting drivers are; further schemes are added the same way. A business chooses its provider in its own settings (`payee_check_settings`, default NONE, so nothing changes); credentials are sealed under `storeql.payee-check.secrets-key` and come from `.env`. When on, a check runs when bank details are saved or changed and `POST /suppliers/{id}/bank-check` runs it again; each result is an append-only `supplier_bank_checks` row bound to a fingerprint of the exact details. **The gate:** a payment run proposal holds a supplier whose current details have no MATCH (or a CLOSE_MATCH a person accepted), with the reason `PAYEE_NOT_CONFIRMED`, `PAYEE_NO_MATCH` or `PAYEE_CHECK_UNAVAILABLE`; the check **fails closed** (a spend-side control, unlike plan limits) but only for a business that switched it on. A person paying anyway needs the approvals action `finance.payee-override`. The cooling-off after a change and the second person on the change itself are `finance.bank-details-change` on the approvals page.
- **Out, on purpose:**
  - **A supplier portal, and supplier-side login.** Acknowledgements, disputes and corrections are recorded by the buyer.
  - **Sanctions or watch-list screening from a provider.** The denylist is the business's own; a list provider is a driver row of its own.
  - **Peppol network-registration lookups.** Not checked; the document's own claims are compared with what the business holds.
  - **Changing an order from an acknowledgement.** A changed price needs a new approval through the order, not a supplier's phone call.
  - **Automatically re-opening, reversing or paying.** The platform only marks and suggests.
  - **Reversing the ledger on re-open.** The liability is real until an invoice is rejected.
  - **Payment-run ceilings, invoice variance ceilings, PO escalation and approval chains.** Approvals page.

## Data and flow

- **Owned by** purchase-svc: `purchase_order_acknowledgements`, `einvoice_sender_denylist`, `supplier_scorecard_disputes`, `supplier_delivery_corrections` (append-only), `supplier_bank_checks` (append-only), `payee_check_settings`; columns `supplier_invoices.reopened_by/at`, `reopen_reason`, `reopen_count`, `review_reason`, `purchase_orders.acknowledged_at`, `supplier_einvoices.sender_signals` and `sender_review`; a status `BLOCKED_SENDER` on `supplier_einvoices` (its status CHECK is replaced in the migration).
- **Needs from other services:** a bank's verification service through the driver (HTTP, sealed credentials); nothing from another service's tables.
- **Events published:** none new. A re-opened invoice stays in the VAT return until it is rejected (the posting stands), so pricing-svc needs to hear nothing; a rejection already announces `SupplierInvoiceRejected`.
- **Retryable writes** (Idempotency-Key): acknowledgement, dispute, re-open, bank check, denylist add.
- **New error codes:** `PURCHASE_INVOICE_NOT_REOPENABLE` 409 (not APPROVED or MATCHED); `PURCHASE_INVOICE_PAID` 409; `PURCHASE_INVOICE_IN_PAYMENT_RUN` 409; `PURCHASE_ACK_KIND_INVALID` 400; `PURCHASE_ACK_ORDER_NOT_OPEN` 409; `PURCHASE_DENYLIST_KIND_INVALID` 400; `PURCHASE_DENYLIST_DUPLICATE` 409; `PURCHASE_SUPPLIER_DENYLISTED` 409; `PURCHASE_EINVOICE_BLOCKED_SENDER` 409 (matching or capturing a blocked document); `PURCHASE_EINVOICE_SIGNAL_REASON_REQUIRED` 400; `PURCHASE_DISPUTE_METRIC_INVALID` 400; `PURCHASE_DISPUTE_NOT_FOUND` 404; `PURCHASE_DISPUTE_DECIDED` 409; `PURCHASE_PAYEE_PROVIDER_UNKNOWN` 400; `PURCHASE_PAYEE_CHECK_NOT_CONFIGURED` 409; a proposal hold is not an error, it is a `held` entry with its reason.

## Money, time and limits

- **Currency:** none new; the re-opened invoice keeps its own.
- **Ledger postings:** none new. A REJECT after a re-open posts the existing reversal.
- **Dates:** every record carries its instant; an acknowledgement's promised date is a date in the store's zone.
- **Settings:** the payee-check provider (owner: purchase-svc, default NONE). Nothing else; the recency window in the existing bank-change warning stays as it is.
- **Plan limits:** none.

## Constraints

- **Existing tenants:** no acknowledgement, denylist, dispute or payee check exists, and the provider is NONE, so every payment run, e-invoice and scorecard reads as today (`SupplierAssuranceCompatibilityIT`).
- **Append-only:** acknowledgements, corrections, checks and denylist rows never update or delete; a removal or supersession is a new row.
- **Secrets:** payee-check credentials never in the repo; `.env` with placeholders in `.env.example`.
- **Never a guess:** a name that is a CLOSE_MATCH is shown with what the bank holds; only a person accepts it.

## Open questions

- [x] Does an acknowledgement change the order? → **No: it is recorded and shown; a new price needs its own approval** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Does the scorecard use the confirmed date or the original? → **The original promise; the confirmed date is shown beside it** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] What does re-opening reverse in the ledger? → **Nothing; it blocks payment, and a rejection reverses as today** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Can a paid invoice be re-opened? → **No: a credit note or a vendor return corrects it** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Is the payee check on for everyone? → **No: a business chooses its provider, and it is off until chosen** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] What happens when the check is unreachable? → **Payment to those details waits, and a person may override with a second person's approval** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] May an invoice change the bank details on file? → **Never; a change goes through the supplier's own edit, with its own controls** (industry standard, under the user's standing instruction of 2026-09-30).
- [x] Is the denylist the platform's or the business's? → **The business's own, kept with a reason** (industry standard, under the user's standing instruction of 2026-09-30).

## Acceptance

- [ ] An approved, unpaid invoice is re-opened to FLAGGED with reason and person, not payable, ledger unchanged; approving it again releases it, rejecting it reverses once — `InvoiceReopenIT.reopeningBlocksPaymentAndReversesNothing`
- [ ] A paid invoice is refused `409 PURCHASE_INVOICE_PAID`; one in a payment run `409 PURCHASE_INVOICE_IN_PAYMENT_RUN` — `InvoiceReopenIT.paidAndScheduledInvoicesAreRefused`
- [ ] A later vendor return or credit note that makes an approved invoice's billed quantity exceed what stands marks it for review and does not change its status — `InvoiceReopenIT.aLaterReturnSuggestsALookAndChangesNothing`
- [ ] The reopen needs management and `purchasing.invoices.decide`; a cashier, storekeeper and another business's owner are refused (403, 404) and nothing moves — `PermissionsIT.reopeningIsGated`, `InvoiceReopenIT.anotherBusinessMovesNothing`
- [ ] An acknowledgement is kept append-only, shows on the order, changes no price or date on it, and the scorecard still measures the original promise — `AcknowledgementIT.recordedAndNeverChangesTheOrder`, `SupplierScorecardTest.theOriginalPromiseStands`
- [ ] An acknowledgement on a DRAFT or closed order is refused `409 PURCHASE_ACK_ORDER_NOT_OPEN`; the unacknowledged filter lists the rest — `AcknowledgementIT.onlyOpenOrdersAndTheFilter`
- [ ] A document from a denylisted address or VAT number is kept as BLOCKED_SENDER and never captured; matching it is `409 PURCHASE_EINVOICE_BLOCKED_SENDER` — `EInvoiceSenderIT.aBlockedSenderIsKeptOut`
- [ ] A document from an unheld address, or quoting a different bank account, stops at NEEDS_DECISION; clearing it needs a reason; the supplier's own details are unchanged — `EInvoiceSenderIT.signalsStopTheDocumentAndChangeNoSupplier`
- [ ] A supplier cannot be created or edited with a denylisted identifier `409 PURCHASE_SUPPLIER_DENYLISTED` — `SupplierDenylistIT.creationIsRefused`
- [ ] Another business's denylist and documents are invisible — `EInvoiceSenderIT.anotherBusinessSeesNothing`
- [ ] A dispute is recorded, decided once, and an upheld one corrects the figure through a correction row while the recorded delivery is untouched — `ScorecardDisputeIT.upheldCorrectsWithoutEditing`, `SupplierScorecardTest.aCorrectionOverridesTheFact`
- [ ] A score-improving correction needs the approvals action — `ScorecardDisputeIT.aCorrectionNeedsApproval` (with the approvals page)
- [ ] With the provider NONE nothing changes; with SIMULATED a matching name lets the first payment through — `PayeeCheckIT.offMeansAsBeforeAndSimulatedMatches`
- [ ] The real driver's exact request and each answer (match, close match, no match, unsupported, unavailable) — `PayeeVerifierStubTest`
- [ ] New details with no MATCH are held in the proposal with a named reason; unreachable holds them too; an override needs the approvals action — `PayeeCheckIT.holdsUntilConfirmedAndFailsClosedWhenOn`
- [ ] A check result belongs to the exact details: changing one digit needs a new check — `PayeeCheckIT.aResultIsBoundToItsFingerprint`
- [ ] Credentials are sealed and never returned — `PayeeCheckIT.secretsAreNeverReadBack`
- [ ] A business in another country checks a payee under its own scheme and currency — `PayeeCheckIT.spansBusinessesInDifferentCountries`
- [ ] The Suppliers, Invoices and Payment runs screens show acknowledgement, signals, held payees and the reopen action — widgets `supplier_assurance_test.dart`; flow guards stay green, and a new k6 `supplier-assurance-flow`

## Screens

- **Admin shell, Procurement:** order detail gains an *Acknowledgement* section (record kind, how heard, promised date, changes) and a list chip *Awaiting supplier*. Invoices tab: a *Look again* chip and a *Re-open* action with a reason. Sourcing/Suppliers: scorecard card shows open disputes and a *Record a dispute* action; a supplier's bank section shows the latest check (match, close match with the bank's name, no match, unavailable) with *Check now*. Payment runs tab: held payees with the reason and an override request. E-invoices tab: signals on a waiting document, the denylist manager (add with reason, remove), and *Block sender* on any document.

## Decisions

<!-- Filled while building. -->
