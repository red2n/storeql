# Proof of handover: a signature or photo, and the age check repeated at the counter

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | new: the flow catalogue's open findings, wave 2 — online/handover (FUL-67) |
| **Services** | order-svc owns proofs, the proof policy and the handover's age check · product-svc owns the age rules and says what a variant's rule is · notification-svc, reporting-svc read the two events (additive fields) · the app's Fulfilment screen captures |
| **Builds on** | `order_handovers` and `POST /orders/{id}/dispatch` / `collect` (ship-from-store, BUILT), `age_verifications` and `POST /pos/age-checks` (`AgeCheckResource`), product-svc `ComplianceRepository.minimumAge` and `PRODUCT_AGE_BELOW_STATUTORY`, `Entitlements.requireBytesWithin` (product images, supplier e-invoice documents), `RetentionPurgeService`, the privacy export |
| **Built in** | |

## Problem

A collected order records only a typed name, and a dispatched one only a carrier and a reference. When a shopper says "I never got it" or "someone else collected it", the business has nothing to show. And an age-restricted order (alcohol, tobacco, knives, whatever the business's rules say) is age-checked when it is *placed*, but nobody has to look at the person who walks in to collect it, so the check at the counter is a habit, not a record.

## Outcome

- **A business can require proof at handover** and staff capture it on the Fulfilment screen: a name and a signature drawn on the screen, or a photo (of the goods handed over, or the parcel packed). The proof is kept with the order, is readable by the business's staff, and is never shown to another business or another shopper.
- **An age-restricted order collected in person cannot be handed over without a fresh age check.** Staff record that they looked at the collector and whether they passed or were refused. A refusal leaves the order waiting with the refusal on the register. What is recorded is the same as at the till: the outcome, the kind of identity document seen, the cut-off date it was judged against and who did it; **never a picture or copy of the document**, and a number from it only where the business's own rule, set for the law where the store is, demands it.
- **A dispatched age-restricted order says so to the carrier.** Staff confirm the carrier was told to check age at the door; that confirmation is recorded on the handover.
- **Limits are plan limits.** How many megabytes of proof a business keeps is a plan allowance, refused by the service that holds the proofs.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): store staff (capture, check), the store manager (proof policy, reads the register), the owner, the shopper (only affected: may be asked for a signature; reads that a proof exists).
- **Channels:** back-office Fulfilment screen (web and tablet). Not the POS till: a till sale is not a handover.
- **Scope:** per store where the handover is made (a caller held to stores acts only at theirs); the proof policy is per business.
- **Roles that can write:** any staff assigned to the order's store capture proofs and record the check (the same people who may collect or dispatch); OWNER and MANAGER set the policy.
- **Sandbox tenant:** behaves the same; nothing leaves the service.

## Scope

- **Already there (verified 2026-09-30):**
  - The handover record (`order_handovers`, one per order, append-only), `collectedBy`, carrier, reference, parcels (`OrderService.collect` / `dispatch`, `HandoverIT`).
  - The age-check register and its refusal codes (`AGE_CHECK_OUTCOME_UNKNOWN`, `AGE_CHECK_REASON_REQUIRED`, `AGE_CHECK_ID_TYPE_UNKNOWN`, `AGE_CHECK_CUTOFF_REQUIRED`), the rule that a business's age rule may be stricter than the statute and never laxer (`PRODUCT_AGE_BELOW_STATUTORY`, `FoodSafetyIT`), and the no-copy-of-identity design (id *type* only). This page reuses all of it; catalogue case SHOP-10 is therefore already done.
- **In (slices in build order):**
  1. **Age re-check at collection (order-svc, product-svc, app).** `orders.age_min` (a snapshot at placement of the highest minimum age over the lines, from product-svc's rule at the store's country; null when nothing is restricted). `POST /orders/{id}/collect` on such an order requires an `ageCheck` object (outcome, id type, cut-off, exactly as `POST /pos/age-checks`); it writes the `age_verifications` row (with the order id) and the handover on one transaction. REFUSED writes the register row, does **not** hand over, and answers `409 ORDER_HANDOVER_AGE_REFUSED`; staff then take the goods back as a return (existing) or leave the order waiting.
  2. **Dispatch says so to the carrier (order-svc).** A dispatch of an order with `age_min` requires `carrierAgeCheck: true` (staff confirm the carrier's age-at-door service was asked for); recorded on the handover as `carrier_age_check`.
  3. **Proof capture (order-svc, app).** A per-business proof policy, `handover_proofs` for the bytes, size and media limits, plan limit `proofs.mb.max`, purge with retention. Collect and dispatch refuse without the required proof.
  4. **The proof a carrier confirms (order-svc).** Where a connected `Carrier` driver (see [shopper-returns](shopper-returns.md) slice 3) reports a delivery confirmation (time, receiver's name), it is attached to the handover as a `DELIVERY_CONFIRMATION` row, without an image. Nothing is polled: it is read when staff or the shopper open the order.
- **Out, on purpose:**
  - **A driver app and door-step capture for the business's own drivers.** Transport and route planning is its own row; this page covers what staff at a store capture, and what a carrier reports.
  - **A copy, scan or photograph of an identity document.** Never stored. Where a law requires a document reference to be logged (stated by the business on its own age rule), the reference (number) is kept sealed for the retention days the rule names and purged after; still no image.
  - **Biometric or face matching, and electronic identity checks.** A person looks; the platform records that they did.
  - **A collection code the shopper shows.** Recorded as an out-of-scope follow-up in ship-from-store; still out: the age check and the signature are the control.
  - **Uncollected orders lapsing and restocking.** Its own sweeper, already excluded by ship-from-store.
  - **Exception alerts for many refusals or overridden checks by one person.** Metric named for the exception-alerts page: `handovers.age_refusals` (per member of staff and store). The metric this page first also named, `handover.age-check-missing`, is dropped: an order that needed a check cannot be handed over without one, so there is nothing to count.

## Data and flow

- **Owned by order-svc:**
  - `orders.age_min` (integer, null when unrestricted) and `orders.age_rule_unread` (boolean: true when product-svc could not answer at placement).
  - `age_verifications` gains `handover_id` (optional) and is otherwise as it is.
  - `handover_proofs`: id, tenant, order, store, kind (SIGNATURE, PHOTO), media type (image/png, image/jpeg), `bytes` (BYTEA, the stack has no object store: same as product images and supplier documents), size in bytes, SHA-256, signer's name (signature only), captured by, captured at UTC, `purged_at`. Append-only; a retention purge nulls the bytes and sets `purged_at` (the same personal-data purge exception `RetentionPurgeService` already makes for order contact details).
  - `handover_proof_policy` (one row per business): `collection` and `dispatch` each NONE (default), NAME (a typed name, as today), SIGNATURE, PHOTO or EITHER; `above_amount` optional in the home currency (proof only for orders at or above it; null = every order).
  - `order_handovers` gains `carrier_age_check` (boolean), `proof_kinds` (list, denormalised for reads).
- **Needs from other services:** product-svc, at placement: the minimum age of each variant at the store's country (REST, existing `ProductClient`, timeout/breaker). Unreadable is **fail closed for the handover**: `age_rule_unread` is set and the collect asks the rule again; if product-svc still cannot answer the check is required, because a check is ten seconds and an unchecked sale of a restricted item is an offence. tenant-svc: store country and home currency through `TenantProfiles`. Plan allowance through common-service `Entitlements`.
- **Events published:** `OrderCollected` and `OrderDispatched` gain `proof` (NONE, NAME, SIGNATURE, PHOTO), `ageChecked` (boolean) — additive; notification-svc and reporting-svc ignore them, a webhook subscriber sees them. No new event.
- **Retryable writes (Idempotency-Key):** the proof upload (`POST /orders/{id}/handover-proofs`, key required: a retried camera upload must not store twice) and the existing collect / dispatch (a second is already `ORDER_ALREADY_HANDED_OVER`).
- **Endpoints:** `POST /orders/{id}/handover-proofs {kind, mediaType, dataBase64, signerName?}` (staff at the order's store; the order must be FULFILLED and not yet handed over, or handed over within the same request path: proofs attach to the order, so a photo may be taken before the confirm); `GET /orders/{id}/handover-proofs` (metadata; staff at the store and the shopper who placed the order: never the bytes for the shopper); `GET /orders/{id}/handover-proofs/{proofId}/content` (staff); `PUT/GET /admin/orders/handover-proof-policy` (OWNER/MANAGER); `POST .../collect` and `.../dispatch` extended as above.
- **New error codes:** `409 ORDER_HANDOVER_AGE_CHECK_REQUIRED`; `409 ORDER_HANDOVER_AGE_REFUSED`; `409 ORDER_HANDOVER_PROOF_REQUIRED` (names the kinds accepted); `413 ORDER_PROOF_TOO_LARGE` (over the gateway's body limit or the plan's megabytes: `PLAN_LIMIT_EXCEEDED` as `Entitlements` gives it); `415 ORDER_PROOF_MEDIA_UNSUPPORTED`; `400 ORDER_PROOF_SIGNER_REQUIRED` (a signature names who signed); `409 ORDER_HANDOVER_CARRIER_AGE_CHECK_REQUIRED`.

## Money, time and limits

- **Currency:** the proof threshold is in the business's home currency; an order in another currency is translated through `FxRates`, and without a rate proof is required (fail closed).
- **Ledger postings:** none.
- **Dates:** `captured_at` and the handover instant in UTC; the age cut-off is a date judged in the store's country as product-svc gives it.
- **Plan limits:** new entitlement `proofs.mb.max` (megabytes of handover proof kept), added to `Plans.CATALOGUE` **and** refused by order-svc through `Entitlements.requireBytesWithin` as each upload would leave the total (measured as the write would leave it). Fail-open when the allowance is unreadable, like every limit. Per-file size is bounded by the gateway's existing `BodySizeFilter`, not a new number.

## Constraints

- **Golden rules:** 3 (tenant from the JWT, store access checked first); 8 (proofs, handovers, age register append-only); 10 (a proof is a DTO with metadata; the bytes leave only through the content endpoint); 13/14 (threshold as NUMERIC, UTC).
- **Privacy:** a signature and a photo are personal data. They are listed in the privacy export as metadata (kind, time, who captured), removed by the retention purge and by a customer's erasure (`CustomerErased` handler nulls the bytes for that customer's orders), and never sent to a webhook or a message.
- **Location-neutral:** the statutory age, its cut-off and any document-reference duty come from the age rule for the store's country; the platform assumes no age, country or document type. A business in a place with no such rule has `age_min` null and is never asked.
- **Existing tenants:** policy NONE, no proofs, no new refusal for an order with no age-restricted line. An order placed before this ships has `age_min` null and is not retro-flagged; a restricted order placed earlier is only ever a manual check.
- **Media safety:** content type is sniffed (never trusted from the header); only PNG and JPEG are accepted; served with `Content-Disposition: attachment` and `nosniff`.

## Open questions

- [x] **What does the shop keep as proof?** Recommended: a signer's name with a signature image, or a photo, per the business's policy; nothing by default. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Where are the bytes kept?** Recommended: in order-svc, as product images and supplier documents are, counted against a plan allowance; move to an object store when the platform has one, without changing the API. → **in-service, plan-limited** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **A refused age check at the counter?** Recommended: no handover, the refusal on the register with its reason, the goods taken back by a return; never an automatic cancel. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Is any copy of an identity document ever kept?** Recommended: never; only the kind seen, the cut-off and the outcome, and a reference only where the business's own rule for the store's law demands one, sealed and purged. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Unreadable age rule at handover?** Recommended: require the check (fail closed): the risk is legal, the cost is seconds. → **fail closed** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A collect of an age-restricted order with no age check is refused `409 ORDER_HANDOVER_AGE_CHECK_REQUIRED`; with a PASSED check it hands over and the register row names the order. — `HandoverAgeCheckIT.collectNeedsACheck`, `aPassedCheckHandsOver`
- [ ] A REFUSED check hands nothing over (`409 ORDER_HANDOVER_AGE_REFUSED`), is on the register with its reason, and the order can still be taken back as a return. — `HandoverAgeCheckIT.aRefusalHandsNothingOver`
- [ ] A restricted order whose rule could not be read at placement still asks for the check. — `HandoverAgeCheckIT.anUnreadRuleFailsClosed`
- [ ] An order with no restricted line collects exactly as today. — `HandoverIT` (existing) unchanged
- [ ] Nothing about an identity document beyond its type, the cut-off and the outcome is stored. — `HandoverAgeCheckIT.noDocumentCopyIsKept` (schema and payload assertion); a document reference is stored sealed and purged only under a rule that names it, `HandoverAgeCheckIT.aReferenceOnlyWhereTheRuleAsks`
- [ ] Dispatch of a restricted order needs `carrierAgeCheck` (`409 ORDER_HANDOVER_CARRIER_AGE_CHECK_REQUIRED`). — `HandoverAgeCheckIT.dispatchNeedsTheCarrierAgeCheck`
- [ ] With a SIGNATURE policy, collect is refused without a proof (`409 ORDER_HANDOVER_PROOF_REQUIRED`) and succeeds with one; an over-threshold rule applies only above the amount; another currency without a rate requires proof. — `HandoverProofIT.policyIsEnforced`, `aboveAmount`, `noRateFailsClosed`
- [ ] A proof upload retried with the same key stores one; a non-image or an unsniffable type is refused (`415`). — `HandoverProofIT.aRetryStoresOnce`, `mediaIsSniffed`
- [ ] Storage over `proofs.mb.max` is refused by order-svc, and an unreadable allowance lets it through. — `HandoverProofIT.planLimitRefusesAndFailsOpen`; `PlanIT` lists the new key
- [ ] Another business's staff of every role, and other shoppers, cannot list, read or add proofs on our order (404), and the bytes are never in the shopper's own list. — `HandoverProofIT.anotherBusinessFindsNothing`, `aShopperSeesMetadataOnly`; a store-held caller at another store gets `403 STORE_ACCESS_DENIED`
- [ ] The retention purge and a customer's erasure remove the bytes and keep the row. — `HandoverProofIT.retentionAndErasureNullTheBytes`
- [ ] Fulfilment screen: the age-check section appears only for a restricted order; the signature pad and photo picker appear per policy; a refused check shows the refusal in words. — widget tests `fulfilment_collect_age_check_test.dart`, `handover_proof_capture_test.dart`, `handover_proof_policy_card_test.dart`
- [ ] End to end: k6 `handover-proof-flow`; `flow-guard-comprehensive` and `flow-guard-runtime` green.

## Decisions

<!-- Filled while building. -->

## Screens

- **Admin, Fulfilment** (the collect and dispatch dialogs): for a restricted order an *Age check* block (outcome, document type from the list, the cut-off shown from the rule, refusal reason) above the confirm; a *Signature* pad with the signer's name and a *Photo* button (camera on a device, file picker on the web), each only as the policy asks; the confirm stays disabled until what is required is present. A refusal shows what was recorded and offers *Take the goods back* (opens the return).
- **Order detail (staff):** a *Handover* card with who, when, the proof thumbnails (thumbnail loads through the content endpoint) and the age-check outcome.
- **Business settings:** a *Proof of handover* card (collection, dispatch, threshold).
- **Storefront, My Orders:** a line "Collected by <name> on <date>" (metadata only, no image).
