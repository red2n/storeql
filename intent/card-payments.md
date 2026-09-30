# Card payments: real card money online and at the till

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings (paying-online gaps 1-3, tender-and-payment gap 3, checkout gap 1) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), online and POS domains |
| **Services** | payment-svc owns provider connections, payment settings, intents, attempts, provider refunds and the till card rule · order-svc confirms on authorisation, holds handover for an unpaid pay-now order and cancels through the event it already publishes · tenant-svc gives the business's profile and stores (through `TenantProfiles`) · notification-svc says what happened · purchase-svc posts as it does today · the gateway opens one public webhook route · the app gets the pay step, the settings screen, the review queue and the till's card rules |
| **Builds on** | `PaymentProvider` (STRIPE, MANUAL; `RAZORPAY` a constant only), `PaymentProviders.forTenant` (sandbox → MANUAL), `StripePaymentProvider` (manual capture, webhook signature, dispute parsing), `payment_intents`, `POST /payments/intents`, `/intents/{id}/capture`, `/webhooks/{provider}`, `webhook_events` dedupe, `POST /payments/online` (single and group), `OrderPaymentGuard`, `card_terminals`/`terminal_payments`, `TerminalService`, `refundTx`/`refund_tenders`, `DisputeService.fromProvider`, settlement parsers (Stripe, Adyen, canonical), `PendingOrderSweeper`, `TenantProfiles`, `SealedSecrets`, gateway `JwtAuthFilter` webhook route, k6 `gateway-card-data-guard`, `docs/ACCOUNTING-CONNECTORS.md` and purchase-svc `client.accounting` (the driver pattern) |
| **Built in** | not yet |

## Problem

The platform cannot yet take a card online. `POST /payments/online` verifies the order against order-svc and then records a tender: the shopper's own say-so that they paid is what confirms the order, and nothing has authorised or captured a penny. Underneath, a good half of the real thing is already written and unused: a provider interface, a Stripe driver with manual capture and a verified webhook, an intent table and dispute handling. The storefront app never calls them, only one global provider can be configured for the whole deployment (so no business can bring its own account), and the pieces have gaps a real charge would hit:

- **No 3-D Secure flow the app can drive.** The intent hands back a redirect address only; there is no client token for the provider's own card fields, no return handling and no pending state.
- **No fraud screening.** Nothing watches for card-testing (a run of declines) or lets a business hold a risky payment for a person to look at before it is taken.
- **The webhook can lose a payment.** If the provider's event arrives before our intent has recorded the provider's reference, the event is judged "unknown", marked seen and never applied.
- **Capture takes the whole authorisation.** A line closed short or substituted before capture would still charge the full amount, and no provider refund call exists at all: a refund "to the original card" is only a record.
- **A sandbox or another business could reach the real provider.** `create` records the sandbox-aware provider name but calls the deployment's active provider.
- **At the till, a CARD tender can be typed in.** At a store that owns a card machine, a cashier can record a card sale with no terminal attempt behind it. That is the easy way to fake a card sale.
- **Nothing counts repeated tries.** Checkout has a duplicate-order prompt (a courtesy) but no metric for a shopper or a card tried many times.

## Outcome

- **A shopper pays by card online and the money really moves.** They pay in the provider's own card fields or on the provider's own page. The card number never reaches StoreQL, so the platform's card-data scope stays at the smallest self-assessment (PCI DSS SAQ-A).
- **Strong customer authentication (3-D Secure) happens where it is required.** The provider asks the cardholder's bank; the app shows the bank's challenge and then a pending state until the provider confirms. Where the card scheme moves liability to the bank, that is recorded on the payment.
- **The order confirms when the card is authorised and the money is taken when the goods are picked.** A cancelled or shortened order releases or reduces a hold; it does not owe a refund.
- **The provider's word is final.** A signed webhook, checked and applied once, decides what happened, whether it arrives before or after the app returns. A missed one is found by a periodic look at the provider.
- **A business chooses its own processor.** It connects its own account (Stripe or Adyen at launch), tests it, and goes live. The money settles to the business, never through StoreQL.
- **Refunds and exchanges go back to the card that paid,** through the provider, and a failed one lands in front of a person. Disputes from either provider fill the existing dispute register.
- **Risk stays the provider's job, with two controls of ours:** alerts for repeated declines and a per-business "review before capture" hold.
- **At the till, a card sale is a terminal's approved sale,** unless the owner has allowed a standalone machine at that store, in which case the machine's receipt reference is on the tender.

## Who and where

- **Personas** ([PRD §2](../PRD.md)):
  - The **shopper**, paying online, guest or signed in.
  - The **cashier**, taking a card at the till.
  - The **store manager**, who reviews held payments and refunds.
  - The **owner**, who connects the processor, sets the payment settings and allows a standalone machine.
  - The **platform administrator**, for go-live support only (never sees a secret).
- **Channels:** ONLINE (storefront on web and mobile) · POS (till) · back-office (settings, review queue, refunds, disputes).
- **Scope:** a connection and its settings are per business. The standalone-machine setting is per store. An intent belongs to one order, or to one split checkout (`groupId`). The order's own store is what a payment is attributed to.
- **Roles that can write:**
  - OWNER connects, tests, activates and disconnects a processor; sets capture timing, SCA mode, review hold, the attempt cap; allows or forbids a standalone machine at a store.
  - OWNER and MANAGER decide a held payment (capture or cancel), retry a failed refund.
  - CASHIER/MANAGER/OWNER record tenders as today, under the till rule below.
  - The shopper creates and resumes an intent for their own order only (guest: the intent id is the capability, as `OrderPaymentGuard` already says).
- **Sandbox tenant:** always the `SIMULATED` provider and never a live connection (`409 PAYMENT_SANDBOX_NO_LIVE`); it runs the whole state machine, challenge included, so a sandbox can rehearse 3-D Secure, declines, review and refunds with no money moving. This replaces the earlier rule that a sandbox pays through `MANUAL` (22.8): the guarantee, that nothing a sandbox does authorises or captures a penny anywhere, is unchanged and now enforced by the driver a sandbox can reach, not by a special case in the caller.

## Scope

- **In (slices, in build order, each buildable and testable on its own with stubs):**
  1. **The till's card rule.** No provider needed, smallest and useful at once. `POST /payments` with method CARD:
     - At a store with at least one ACTIVE registered terminal, it is refused `409 PAYMENT_CARD_NEEDS_TERMINAL` (the cashier uses `POST /payments/terminal`, whose approved attempt already writes the tender).
     - Unless the owner has set that store to allow a standalone machine. Then the tender is recorded with `entry_mode = STANDALONE` and a **required** `reference` (the machine's receipt or authorisation reference), else `400 PAYMENT_CARD_REFERENCE_REQUIRED`.
     - At a store with no terminal at all the same reference is required (a card taken on a machine that StoreQL does not see must still be findable in the acquirer's file). This is a behaviour change for existing tenants: tills and k6 fixtures send a reference.
     - Setting the permission is OWNER only, recorded append-only with who and when.
     - Metric named for the alerts page: `card_tenders.standalone_count` and `card_tenders.standalone_share` (see Data and flow).
  2. **The provider contract made complete and correct, with the `SIMULATED` driver.** Extend `PaymentProvider` (see Data and flow) and repair what a real charge would break, testable with the simulated driver and the stubbed Stripe:
     - correlate a webhook by our own intent id (carried as metadata), not only by the provider reference; an event for an intent we do not yet know is answered non-2xx once so the provider redelivers, an event that is not ours is acknowledged and ignored;
     - `create` calls the provider resolved for that business (`forTenant`), never `active()`;
     - partial capture (`amount_to_capture`) and the charge reference on the tender (not the intent id), so the settlement file matches;
     - a provider refund call and the provider-side idempotency key derived (`Ids.derived(intentId, "capture")`), never the client's own key sent through;
     - states `PROCESSING`, `REQUIRES_PAYMENT_METHOD` (a decline that may be retried), `REVIEW`, `EXPIRED` added to the machine; an append-only `payment_intent_attempts`;
     - `SIMULATED` decides its outcome from the last two minor-unit digits of the amount (a documented table: approve, decline, insufficient funds, challenge then approve, challenge then fail, require review, dispute later), serves no card form, and completes a challenge only through `POST /payments/simulated/{intentId}/complete`, which answers `404` unless the tenant's provider is SIMULATED.
  3. **Per-business provider connection and settings.** `payment_connections` (sealed credentials, per business), `payment_settings`, the connection test, the go-live gate, and the per-connection public webhook route through the gateway. The deployment-wide `storeql.payment.provider` and `storeql.payment.stripe.*` keys stay as the default connection of a single-business stack, and a business's own connection wins. The owner's Settings → Payments screen.
  4. **Online payment through the intent state machine** (replaces the self-attesting capture for a business with a real connection):
     - `POST /payments/intents` for one order or a whole split checkout;
     - the client hand-off (token, publishable key, redirect) and resume;
     - `PaymentAuthorized` confirms the order; capture at fulfilment (or at confirmation if the business chooses); `OrderFulfilled` consumed for that;
     - `POST /payments/online` stays only for a business whose connection is MANUAL, else `409 PAYMENT_USE_INTENT`;
     - the cancel, sweep and late-webhook races;
     - the intent reconciler for missed webhooks;
     - the storefront pay step on web and mobile: hosted fields or redirect, return route, pending, retry.
  5. **Adjustments, refunds, exchanges and disputes through the provider:**
     - partial capture when a line is closed short or substituted before capture (the existing `ORDER_ADJUSTMENT` refund path only after capture);
     - refund to the original card through the provider with its own status;
     - a failed provider refund goes to a person;
     - group checkout captured once at the last part;
     - Stripe disputes already parse; nothing new for them except wiring the refund/dispute webhooks to the register, which is the Adyen slice's job for Adyen.
  6. **The Adyen driver (session and redirect based).** Sessions, capture, cancel, refund, the notification batch with its HMAC and basic authentication, disputes, and its settlement matching (the Adyen settlement parser exists).
  7. **Risk:** per-attempt provider risk signals recorded; the "review before capture" hold and its queue; the attempt cap; the velocity metrics for the alerts page.
  8. **Go-live pack:** `docs/PAYMENTS.md` (setup per provider, what each needs), `.env.example` placeholders, k6 `card-payment-flow` and the widened card-data guard, web CSP, the supply-chain and gateway checks, and the go-live checklist for the user.
- **Out, on purpose:**
  - **Saved cards and "pay in one tap next time".** Decided against for now: a saved card means provider customer objects, stored-credential (cardholder-present vs merchant-initiated) rules and mandates, consent and deletion under privacy law, and a card-management screen; none was asked for and every one widens the compliance surface. When built it is provider tokens only, never a number or last-four beyond what the provider shows. Wallets (Apple Pay, Google Pay) and stored bank methods a provider offers inside its own element still appear, because the provider decides the methods, not us.
  - **StoreQL acting as a payment facilitator** (one platform account, paying businesses out). Regulated, and a different business. The business's money settles at its own provider account.
  - **Home-made fraud scoring, device fingerprinting, IP geolocation, AVS/CVC decision rules of our own.** The provider's engine (Stripe Radar, Adyen risk) and the scheme's 3-D Secure liability shift do this better, with network data we cannot have. We keep only what is cheap and ours: velocity metrics and the hold.
  - **A third and fourth driver at launch.** Razorpay (named in PRD §11; the constant exists) is an order plus client-checkout model like Stripe's and drops in behind the same interface when a business needs it; two drivers with different models are what prove the interface. This differs from PRD §11, which named Razorpay and Stripe: Adyen replaces Razorpay in the built pair because its model (sessions, redirect, batch notifications) is the one Stripe's is not, and its settlement parser is already in the repo.
  - **Charging in a display currency.** A price shown in another currency (`prc-fx-display`) is never what is charged; see Money.
  - **Recurring billing of the platform's own subscriptions.** Different money flow, not asked.
  - **Card-present through the provider's own reader SDK** (Stripe Terminal). The existing terminal vendors and their integration are unchanged.
  - **Paying a group's parts separately.** Still refused (`PAYMENT_ORDER_IN_GROUP`).

## Data and flow

### The driver interface (payment-svc, package `provider`)

Copy the accounting pattern: an interface, one class per provider that alone knows the provider's URL, headers and JSON, a `SIMULATED` driver, and a stub-backed test of each real driver's exact request. `service/` stays HTTP-free.

- `PaymentProvider` grows, per call taking the business's `Connection` (credentials opened from the seal for the call, never held):
  - `authorize` (returns the provider reference, status, and a `ClientHandoff`: `mode` CLIENT_CONFIRM or REDIRECT, `clientToken`, `publishableKey`, `redirectUrl`; the token is not stored);
  - `resume` (a fresh hand-off for an unfinished intent: Stripe retrieves the intent; Adyen makes a new session with the same merchant reference);
  - `capture(providerRef, amount, key)` with the amount honoured;
  - `cancel`;
  - `refund(providerRef, amount, key)` returning a refund reference and status;
  - `retrieve(providerRef)` (the current state, for the reconciler and a return);
  - `verifyWebhook(raw, headers, connection)` returning a list of events (Adyen batches) and `acknowledgement()` (the exact body the provider requires; Adyen wants `[accepted]`);
  - `testConnection` (a harmless authenticated read);
  - `capabilities` (`deferredCapture`, `partialCapture`, `multiCapture`, `refund`, `disputes`), so the state machine asks instead of assuming.
- `PaymentProviders` resolves by the business's active connection, then the deployment default, then MANUAL; a sandbox reaches only SIMULATED.
- **Stripe (intent-based, client-side confirmation).**
  - Base `https://api.stripe.com` (overridable for tests). `Authorization: Bearer <secret key>`, `Stripe-Version` pinned in config, `Idempotency-Key` on every POST.
  - `POST /v1/payment_intents`, form-encoded: `amount` (minor units of the currency, never assumed two decimals), `currency`, `capture_method=manual`, `automatic_payment_methods[enabled]=true`, `payment_method_options[card][request_three_d_secure]` = `automatic` (provider decides) or `any` (the business's ALWAYS), `metadata[intentId|orderId|groupId|tenantId]`, `return_url`. The response's `client_secret` is the token; the publishable key is the connection's.
  - `POST /v1/payment_intents/{id}/capture` with `amount_to_capture`; `POST /v1/payment_intents/{id}/cancel`; `POST /v1/refunds` with `payment_intent`, `amount`, `metadata[refundId]`; `GET /v1/payment_intents/{id}` to retrieve.
  - Webhook `Stripe-Signature: t=<ts>,v1=<hex>`, HMAC-SHA256 over `<t>.<raw body>` under the endpoint secret, constant-time compare, five-minute tolerance (already written, kept). Events used: `payment_intent.requires_action`, `.amount_capturable_updated` (authorised), `.succeeded`, `.payment_failed`, `.canceled`, `charge.refunded`, `refund.updated`/`refund.failed`, `charge.dispute.*` (already parsed), `review.opened`/`review.closed`.
- **Adyen (session and redirect based).**
  - Checkout base and Management/Disputes bases from the connection (Adyen's live endpoints are per-account prefixes, so never hard-coded). `X-API-Key`, `Idempotency-Key` on every POST.
  - `POST /sessions` with `merchantAccount`, `amount{value,currency}`, `reference` = our intent id, `returnUrl`, `countryCode` from the business's own store (never assumed), `shopperReference` omitted (no saved cards), and 3-D Secure requested per the SCA setting. The response's `id` and `sessionData` are the hand-off, with the connection's client key, for the provider's Drop-in on mobile and web, or a redirect where the shopper is sent away.
  - Deferred capture: the driver reports `deferredCapture` only when the request or the account is set for manual capture; the exact field (`captureDelayHours` on the session, or the account's manual-capture setting) is pinned against Adyen's documentation by the stub test. A connection whose account is not set for it forces capture at confirmation and says so on the settings screen.
  - `POST /payments/{pspReference}/captures`, `/cancels`, `/refunds` with `merchantAccount`, `amount`, `reference`.
  - Webhooks are a JSON batch `notificationItems[].NotificationRequestItem`. Each item carries `additionalData.hmacSignature`: HMAC-SHA256, key decoded from hex, over `pspReference:originalReference:merchantAccountCode:merchantReference:amount.value:amount.currency:eventCode:success`, base64; a wrong item rejects the whole delivery. The endpoint also checks the basic-authentication pair the business set. It answers exactly `[accepted]`. Events used: `AUTHORISATION`, `CAPTURE`, `CAPTURE_FAILED`, `CANCELLATION`, `REFUND`, `REFUND_FAILED`, `NOTIFICATION_OF_CHARGEBACK`, `CHARGEBACK`, `CHARGEBACK_REVERSED`, and the risk fields in the authorisation notification.
- **Stub-backed tests of exact shape** for each driver: URL, method, every header (auth, idempotency key, version), body fields including minor-unit conversion for a zero-decimal and a three-decimal currency, and the signature scheme (a good one, a tampered body, a stale timestamp, a missing header, a wrong secret). No test reaches a real provider.

### Owned by payment-svc (new tables; append-only where said)

- `payment_connections`: `id`, `tenant_id`, `provider` (STRIPE, ADYEN, SIMULATED, MANUAL), `mode` (TEST or LIVE), `label`, the account identifiers that are not secret (Adyen merchant account, live URL prefix, client key, Stripe publishable key), `credentials_sealed` and `webhook_secret_sealed` (and Adyen's basic-authentication pair, sealed) under `storeql.payment.secrets-key` through `SealedSecrets`, `status` (DRAFT → TESTED → ACTIVE, or DISCONNECTED), `tested_at`, `webhook_verified_at`, `created_by`, timestamps. One ACTIVE per business (partial unique index). Sealed columns are never in a DTO, a log or an export; the tenant-data export lists a connection by label and provider only.
- `payment_settings`, one row per business: `capture_timing` (AT_FULFILMENT default, or AT_CONFIRMATION), `sca_mode` (PROVIDER_DECIDES default, or ALWAYS; there is no "never"), `review_mode` (OFF default, ON_PROVIDER_RISK, ALL_CARD_ORDERS), `max_attempts_per_order` (empty = unlimited), who and when. Every change is also appended to `payment_setting_changes` (append-only).
- `payment_intents` (exists) gains: `group_id` (nullable; `order_id` becomes nullable; a CHECK says exactly one of the two), `connection_id`, `capture_timing` (as set when created, so a later change never re-times an open payment), `amount_adjusted` (what will be captured when a line was closed before capture; null until then), `captured_at`, `expires_at`, `risk_level`, `liability_shift`, `review_state`, `method_reported` (CARD, WALLET, UPI as the provider says, never the client). Status set: REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING, AUTHORIZED, REVIEW, CAPTURED, FAILED, CANCELLED, EXPIRED. Money is NUMERIC(18,4) with the currency stored.
- `payment_intent_attempts` (append-only): one row per try on an intent: `intent_id`, `outcome` (REQUIRES_ACTION, AUTHORIZED, DECLINED, FAILED, CHALLENGE_FAILED), `decline_code`, `three_ds` (NOT_REQUIRED, CHALLENGED_PASSED, FRICTIONLESS, FAILED, NOT_SUPPORTED), `liability_shift`, `risk_level`, `card_brand`, `funding`, `last4` (only what the provider reports; there is no column for any other digit), `provider_event_id`, `at`.
- `provider_refunds` (append-only rows, one status movement each): links `refund_tenders.id` to the provider's refund reference, `state` (PENDING, SUCCEEDED, FAILED), `failure_code`, so a failed refund is a fact a person can act on.
- `store_card_settings` (`tenant_id`, `store_id`, `standalone_allowed` default false) and its change log (append-only): payment-svc owns it because it owns the terminals. `payment_tenders` gains `entry_mode` (TERMINAL, STANDALONE, ONLINE, MANUAL, LEGACY for rows before this) and `terminal_payment_id`.
- Existing indexes lead with `tenant_id`; every new table carries `tenant_id UUID NOT NULL` and a composite index starting with it. Every uuid is a v7 with its database check.

### The online payment as a state machine

```
create ─► REQUIRES_ACTION ─(3DS passed)─► AUTHORIZED ─(capture)─► CAPTURED
   │            │                │  └─(review on)─► REVIEW ─(release)─► AUTHORIZED
   │            └─(declined)─► REQUIRES_PAYMENT_METHOD ─(retry)─► REQUIRES_ACTION / AUTHORIZED
   │                            (attempt cap reached, or provider says failed) ─► FAILED
   └ any pre-capture state ─(order cancelled / cancel)─► CANCELLED   ·  hold lapses ─► EXPIRED
```

- **Create.** `POST /payments/intents {orderId | groupId, returnUrl}` with `Idempotency-Key` (required: `400 IDEMPOTENCY_KEY_REQUIRED`). The amount and currency come from order-svc through `OrderPaymentGuard` (order ONLINE, PENDING, the caller's, for a group every part PENDING and the sum of the parts); the request's `amount` is at most checked against it (`PAYMENT_AMOUNT_MISMATCH`), never used. The row is inserted before the provider is called. A replay of the key returns the first intent with a fresh hand-off. A provider failure answers `503`/`502 PAYMENT_PROVIDER_UNAVAILABLE` and the row stays as a record.
- **Hand-off.** The response carries `status`, `mode`, `clientToken`, `publishableKey`, `redirectUrl`, `expiresAt`, and the currency and amount to show. `GET /payments/intents/{id}` never returns the token; `POST /payments/intents/{id}/resume` does, once the owner asks (a shopper who reloads the page).
- **Return and refresh.** After the challenge or redirect the app goes to its return route and calls `POST /payments/intents/{id}/refresh`, which asks the provider (`retrieve`) and applies the answer through the same idempotent transition the webhook uses, so the shopper never waits on webhook latency and never gets a second state.
- **The webhook is the source of truth.** It is verified over the raw bytes before anything is parsed, deduped by the provider's event id (recorded only after the effect, as today), and every transition is guarded on its source state so a redelivery, a reorder or a replay reaches the same place. It arrives before or after the app returns; both orders end at the same state (tested). An event for an intent we do not know is judged as described in slice 2.
- **Confirming the order.** On AUTHORIZED (and not held) payment-svc publishes `PaymentAuthorized`; order-svc confirms PENDING → CONFIRMED exactly as it does now on `PaymentCaptured`, and records `payment_state`. No tender is written: a tender stays the append-only statement that money was taken. The authorisation posts nothing to the books.
- **Capture timing, by industry standard.** Card-scheme rules ask that a card-not-present sale be charged when the goods are sent, so for a retailer with pickup and delivery the default is **authorise at checkout and capture when the order is picked and packed** (this platform's FULFILLED), for the amount actually picked. That releases a hold, not a refund, when an order is cancelled or short. A business may choose AT_CONFIRMATION (capture the moment it is authorised) where its provider account or its goods suit that. payment-svc starts consuming `OrderFulfilled` for this; the capture is idempotent on `Ids.derived(intentId, "capture")`. A group is captured **once, when its last part is fulfilled or closed**, for the picked total, and then allocated: one tender and one `PaymentCaptured` per part, as `recordOnlineGroupPayment` writes today; a driver with `multiCapture` may later capture per part, and that is a capability, not an assumption.
- **When a hold cannot be captured** (expired authorisation, an account limit, `CAPTURE_FAILED`): payment-svc publishes `PaymentCaptureFailed`. order-svc keeps the order FULFILLED and refuses its handover (`409 ORDER_PAYMENT_NOT_CAPTURED`, for a pay-now order only, never a till sale or a pay-later one) until the money is in. Staff can send the shopper a fresh payment link (a new intent for the outstanding amount) or record another tender at the handover. The metric is an alert.
- **The unpaid-order sweeper stays and stays safe.** `PendingOrderSweeper` still cancels a PENDING order after its own time-to-live, whatever the state of its intent. `OrderCancelled` makes payment-svc cancel any live intent at the provider (release the hold). A challenge completed after the sweep produces an authorisation for a cancelled order: payment-svc cancels it at once, never captures, and records why. An order awaiting a person's review is PENDING too, so the sweeper's clock applies to it as it does to any unpaid order.
- **Missed webhooks.** `IntentReconciler` (a background bean with the same enable/interval keys pattern as the other sweepers, eager-started) asks the provider about intents left in REQUIRES_ACTION, PROCESSING or AUTHORIZED beyond the provider's own settling time and applies what it hears. It also asks about a capture requested but not confirmed.
- **Reconciliation with the settlement files.** The tender's `reference` is what the settlement parsers match on (Stripe: the charge, Adyen: the PSP reference), so an online capture appears in the acquirer's file as any card sale does and the existing `SettlementRepository` matching, fees and chargeback lines apply unchanged. A test feeds a canonical and a Stripe and an Adyen file lines for online captures and expects them matched.
- **Idempotency end to end.** The client's `Idempotency-Key` guards `POST /payments/intents` (and refund and capture calls have their own); every call to a provider carries a key derived from our ids with `Ids.derived(id, "step")`, never the client's key and never a `"prefix:" + id` string.

### Refunds, adjustments, exchanges, disputes

- **Before capture, a shortened order is a smaller capture.** On `OrderLineShortClosed`/`OrderLineSubstituted` for an order whose intent is AUTHORIZED: payment-svc sets `amount_adjusted` to the order's new payable amount (read from order-svc; a substitute is never charged above the original, so it never re-authorises) and publishes **no** refund. When the new payable amount is zero (every line closed, as a [forced cancel](fulfilment-overrides.md) can leave it) the hold is **cancelled at the provider**, never captured for nothing. After capture the existing `ORDER_ADJUSTMENT` refund path runs and now goes through the provider.
- **A refund to the original card goes to the provider.** `refundTx` with method ORIGINAL on a tender that came from an intent: writes the refund tender and a `provider_refunds` row PENDING, calls `refund(...)` with a key derived from the refund's id, and applies the provider's result (`charge.refunded`, `refund.updated`, `REFUND`) once. Never more than the captured amount less what has been refunded; the card is always the one that paid (a refund is never sent to another card). Terminal refunds keep going to the original attempt as they do.
- **A refund the provider refuses** (a card closed, too old for the provider) becomes state FAILED and `PaymentRefundFailed`, shown in the back office; a manager with `sales.refund` then chooses store credit, a gift card or cash for the same amount through the refund paths that already exist. No mechanism is added for that.
- **Exchange.** The difference, when the customer is owed one, is a refund to the original card in the same way. Exchange netting is order-svc's and unchanged.
- **Disputes.** Stripe's dispute events already fill the register through `DisputeService.fromProvider`; the Adyen driver maps its chargeback notifications to the same `DisputeNotice` and implements `submitDisputeEvidence` and `acceptDispute` against Adyen's disputes API. The register, its roles and its gate are as built.

### Risk

- **Provider first.** A risk level and 3-D Secure outcome are read from the provider's event and recorded per attempt. A payment authorised with liability shift to the issuer is marked so; nothing of ours overrides a provider decline.
- **Review before capture (a per-business setting, off until set).** With `review_mode` ON_PROVIDER_RISK the payment goes to REVIEW when the provider reports elevated or worse risk, or when the provider itself puts it in review (`review.opened`); ALL_CARD_ORDERS holds every online card order. `PaymentUnderReview` is published (no order effect: the order stays PENDING and unconfirmed), and `GET /admin/payments/reviews` (cursor) lists them for OWNER/MANAGER. `POST /admin/payments/{intentId}/review {decision: RELEASE|CANCEL, note}` with `Idempotency-Key`: RELEASE moves it to AUTHORIZED and publishes `PaymentAuthorized`; CANCEL releases the hold at the provider and publishes `PaymentFailed`. The decision, who and when are kept on the intent and audited. This is a queue decision by one manager, not the approvals page's second-person mechanism; if a business wants a second person over large held payments it uses `intent/approvals.md`'s mechanism with an action key of its own (`sales.payment-review-release` in the approvals catalogue) and nothing here changes.
- **Attempt cap (a setting, unlimited until set).** With `max_attempts_per_order` set, the attempt after the cap is refused `409 PAYMENT_ATTEMPTS_EXHAUSTED` and the intent FAILED; the shopper places the order again.
- **Metrics for `intent/exception-alerts.md`** (source payment-svc unless noted; the alerts page owns thresholds, periods and delivery, and every threshold is the business's own):
  - `payments.declines` (declined attempts by one shopper login, or on one order, in a period: one metric with two subjects): the card-testing signature. (Names here are the final ones of [exception-alerts](exception-alerts.md); this page first wrote `payment.declines.per-shopper`, `.per-order`, `.rate`, `payment.review.open.count`, `payment.capture.failed.count`, `payment.card.standalone.*` and `order.placed.per-shopper`.)
  - `payments.decline_rate` (declined attempts as a share of attempts, per business, per period): a card-testing run against the whole storefront.
  - `payments.review_open` and `payments.capture_failures`.
  - `card_tenders.standalone_count` and `card_tenders.standalone_share` (from the till rule).
  - From order-svc for the checkout finding (`online/checkout.json` gap 1): `checkouts.attempts` (online orders placed by one login in a period) and `checkouts.unpaid_pending` (PENDING online orders that login holds), read from order-svc's own tables and published as its own metric; the existing pending-order prompt stays a courtesy and is not the control. Rapid repeat orders from one address are the gateway's rate limiting (`TenantRateLimitFilter`) already; the alerts page adds the count, not another limiter. A shopper login, never an IP address, is the subject (an address is personal data the platform does not keep).
  - **No `PaymentAttemptDeclined` event.** The service that owns the counted thing evaluates its own metric ([exception-alerts](exception-alerts.md)): payment-svc recounts `payment_intent_attempts` after commit and raises `ExceptionAlertRaised` itself. A raw per-attempt event would make the inbox count another service's rows, which that page rules out. `PaymentUnderReview`, `PaymentCaptureFailed` and `PaymentRefundFailed` stay: each has a consumer with something to do (the back office, order-svc, notification-svc).
- **The provider's own rules** (Radar rules, Adyen RevenueProtect, AVS and CVC policy) are set by the business in its provider dashboard; `docs/PAYMENTS.md` says where and why. Mismatched billing and shipping addresses are one of those signals, not a StoreQL rule.

### The till rule (slice 1) and terminals

- Approved terminal attempt → tender: unchanged (`TerminalService`). The tender now records `entry_mode = TERMINAL` and its `terminal_payment_id`.
- `POST /payments` CARD, at a store with an ACTIVE terminal, without the standalone permission: `409 PAYMENT_CARD_NEEDS_TERMINAL`. With the permission: `entry_mode = STANDALONE`, `reference` required (`400 PAYMENT_CARD_REFERENCE_REQUIRED`), and an audit line. At a store with no terminal: reference required, `entry_mode = STANDALONE`.
- Permission: `PUT /admin/payments/stores/{storeId}/standalone-card {allowed}` OWNER only (a manager cannot loosen a fraud control); the store must be one the business owns (`TenantProfiles.Stores`, fail closed: an unreadable store answers `503`, it does not allow); a retired or offline terminal is the reason an owner would use it, and the change log says who and when.
- A standalone tender is matched later by reference in the acquirer's settlement file (existing matching); an unmatched one is visible in the settlement report already built.
- The MANUAL and SIMULATED providers and tenants with no terminal keep their tills selling, with the reference rule only.

### Endpoints (all under `/api/v1/payment-svc`)

| Method and path | Who | Refusals |
|---|---|---|
| `POST /payments/intents` | shopper (own order) or guest with the order | 400 `IDEMPOTENCY_KEY_REQUIRED`, `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_RETURN_URL_NOT_ALLOWED`; 404 `PAYMENT_ORDER_NOT_FOUND`/`PAYMENT_GROUP_NOT_FOUND`; 409 `PAYMENT_ORDER_NOT_PAYABLE`, `PAYMENT_ORDER_IN_GROUP`, `PAYMENT_GROUP_AMOUNT_MISMATCH`; 409 `PAYMENT_NO_PROVIDER` (business has no connection, not MANUAL); 502/503 `PAYMENT_PROVIDER_UNAVAILABLE` |
| `GET /payments/intents/{id}` | owner of the order, staff | 404 `PAYMENT_INTENT_NOT_FOUND` |
| `POST /payments/intents/{id}/resume` · `/refresh` | owner of the order | 404; 409 `PAYMENT_INTENT_NOT_RESUMABLE` when finished |
| `POST /payments/intents/{id}/capture` | CASHIER/MANAGER/OWNER/PLATFORM_ADMIN (exists) | 409 `PAYMENT_INTENT_NOT_CAPTURABLE`; 409 `PAYMENT_UNDER_REVIEW` |
| `POST /payments/webhooks/{provider}/{connectionId}` | the provider, by signature only | 400 `PAYMENT_WEBHOOK_INVALID` (deliberately vague); 404 `PAYMENT_PROVIDER_UNKNOWN`; the old `/webhooks/{provider}` serves the deployment default only |
| `POST /payments/online` | as today | 409 `PAYMENT_USE_INTENT` unless the business's connection is MANUAL |
| `GET /payments/online/config` | shopper, guest | provider mode (INTENT or MANUAL), publishable key, methods; never a secret |
| `GET/PUT/DELETE /admin/payments/connection`, `POST …/test`, `POST …/activate` | OWNER | 400 `PAYMENT_CONNECTION_INVALID`; 409 `PAYMENT_CONNECTION_NOT_TESTED`, `PAYMENT_WEBHOOK_NOT_VERIFIED` (a LIVE connection needs a verified webhook first), `PAYMENT_SANDBOX_NO_LIVE` |
| `GET/PUT /admin/payments/settings` | OWNER (read: MANAGER) | 400 `VALIDATION_FAILED` |
| `GET /admin/payments/reviews` · `POST /admin/payments/{intentId}/review` | OWNER, MANAGER | 404; 409 `PAYMENT_NOT_UNDER_REVIEW` |
| `PUT /admin/payments/stores/{storeId}/standalone-card` | OWNER | 404 `STORE_NOT_FOUND`; 503 when stores cannot be read |
| `POST /payments/simulated/{intentId}/complete` | anyone holding the intent, SIMULATED only | 404 otherwise |

### Events (topic `storeql.payment.<event>`, through the outbox; consumers idempotent)

- **New:** `PaymentAuthorized` (order or group parts, amount, currency, intent) → order-svc confirms and records `payment_state`; notification-svc. `PaymentUnderReview` → back-office (the review queue) and notification-svc (tells the managers). `PaymentCaptureFailed` → order-svc (refuses handover), notification-svc (asks the shopper to pay). `PaymentAuthorizationExpired` → order-svc (`payment_state`), notification-svc. `PaymentRefundFailed` → back-office and notification-svc. None of them is an alert feed: the alert metrics `payments.review_open` and `payments.capture_failures` are counted by payment-svc from its own rows ([exception-alerts](exception-alerts.md)).
- **Existing, now real:** `PaymentCaptured` (published at capture, so the order's revenue, purchase-svc's posting and reporting keep their meaning; order-svc confirms if it has not already, which keeps MANUAL and terminal flows unchanged), `PaymentFailed`, `PaymentRefunded` (published when the provider's refund succeeds, not when it is asked for).
- **Consumed newly by payment-svc:** `OrderFulfilled` (capture), and the existing `OrderCancelled`, `OrderLineShortClosed`, `OrderLineSubstituted` handlers gain the before-capture branch.
- **Retryable writes (Idempotency-Key):** `POST /payments/intents`, `/capture`, `/review`, the provider refund (derived), `PUT` settings are idempotent by nature.

### Gateway and release

- The gateway's public webhook route (`JwtAuthFilter`, exactly the leaf under `api/payment-svc/payments/webhooks/`) grows to the one more segment `{connectionId}`: POST only, no JWT, no tenant header trusted, a body-size cap, rate-limited by address, and still nothing else under `payments/` is opened. The old single-segment route stays for the deployment default.
- The gateway's card-data guard is unchanged in rule (no card number passes) and its k6 `gateway-card-data-guard` gains cases: a PAN in an intent request, in a query string and in a header is refused; a `clientToken` is never echoed into a log line.
- Web build: a content-security policy that lets the page load and frame only the two providers' own hosts (declared in the web image's config), so the shopper's card fields sit in the provider's frame and the redirect path is the lowest-scope alternative.
- Supply chain: no new image, no new library (the Helidon web client is already used); nothing to add to the publish matrix. `scripts/supply-chain-check.py` and `scripts/vuln-scan.sh deps` stay green. Secrets: `STOREQL_PAYMENT_SECRETS_KEY` (base64, 16/24/32 bytes) in git-ignored `.env`, placeholder in `.env.example`; provider keys are never in the repo, the image or a compose file: they are entered by the owner through the connection screen and sealed.
- New error codes in one list: `PAYMENT_CARD_NEEDS_TERMINAL` 409, `PAYMENT_CARD_REFERENCE_REQUIRED` 400, `PAYMENT_USE_INTENT` 409, `PAYMENT_NO_PROVIDER` 409, `PAYMENT_ATTEMPTS_EXHAUSTED` 409, `PAYMENT_UNDER_REVIEW` 409, `PAYMENT_NOT_UNDER_REVIEW` 409, `PAYMENT_INTENT_NOT_RESUMABLE` 409, `PAYMENT_CONNECTION_INVALID` 400, `PAYMENT_CONNECTION_NOT_TESTED` 409, `PAYMENT_WEBHOOK_NOT_VERIFIED` 409, `PAYMENT_SANDBOX_NO_LIVE` 409, `ORDER_PAYMENT_NOT_CAPTURED` 409 (order-svc).

## Money, time and limits

- **Currency:** an online card payment is taken **in the order's own currency**, which is the business's, as order-svc states it; the client's amount or currency is never used. A price shown in a display currency (`prc-fx-display`: `displayCurrency` → `display`) is only shown, never authorised, captured or refunded in, and no dynamic currency conversion is offered. Minor units come from the currency (zero-, two- and three-decimal currencies are all tested), never an assumed two. A currency the connection's provider cannot take is refused `409 PAYMENT_CURRENCY_NOT_SUPPORTED` before the shopper is sent anywhere. What the acquirer settles in, and its fees, arrive in the settlement files and are converted through `FxRates` as the existing settlement code already does.
- **Ledger postings:** none for authorisation. Capture posts as a captured payment does today (purchase-svc from `PaymentCaptured`); refunds post from `PaymentRefunded` as today, now published when the provider confirms; provider fees and chargeback movements come from the settlement files as built. Confirm while building that no posting is taken on `PaymentAuthorized`.
- **Dates:** every instant UTC (`TIMESTAMPTZ`): `created_at`, `expires_at`, `captured_at`, attempt time, review time. A hold's lifetime is the provider's (Stripe's card-not-present manual capture window, Adyen's per account); the reconciler and the capture-failed path handle the end of it, no number is baked in.
- **Plan limits:** none. Payment methods a store owner has switched off stay refused as today (`enabledPaymentMethods`, fail open).

## Constraints

- **PCI DSS scope stays at SAQ-A:** card details go from the shopper's browser or app straight to the provider's hosted fields, frame or page. No endpoint, DTO, event, table, log or export of ours has a place for a card number, expiry or security code; `pan_last4` and brand are the only card facts stored, both reported by the provider. The gateway guard and its k6 suite prove it.
- **Golden rules:** 1 (order data through `OrderClient`, never a join), 3 (tenant from the JWT; a webhook carries no tenant and takes it from the intent it names, found through our own metadata), 4 (a provider's address comes from the connection, not code; stubs override the base in tests), 5 (secrets external and sealed), 6 and 7 (outbox; consumers idempotent; the webhook effect first, the seen-record after), 8 (tenders, refunds, attempts and setting changes append-only; the intent, the only mutable row here, never owns money that was taken), 11, 13, 14, 15.
- **The webhook route is public** by necessity: signature over raw bytes, constant-time compare, timestamp tolerance where the scheme has one, a vague refusal, and never a 500. A response that the provider reads as failure makes it redeliver, which the idempotent effects make safe.
- **PSD2 and other authentication law:** the platform never assumes where it applies. The business's provider decides when a challenge is required from the card and the regulation; `sca_mode` ALWAYS only asks for more. No exemption is ever requested by us.
- **Existing tenants:** a business with no connection keeps working on MANUAL exactly as today (`/payments/online` unchanged). Nothing moves to a real provider until its owner connects, tests and activates. The `PaymentIntent` rows that exist keep their statuses. Tills change only as slice 1 says.
- **Flow guards:** `flow-guard-comprehensive` and `flow-guard-runtime` must stay green; carts, orders and POS sessions are touched. k6 fixtures that record a card tender send a reference.
- **Tenant isolation:** connection, settings, intents, attempts, reviews and refunds are tenant-scoped with `tenant_id` first; another business's staff of every role, even naming our id, and a shopper of another business get 404 or empty and nothing moves.

## Open questions

- [x] Which two processors? → **Stripe (intent and client-side confirmation, built) and Adyen (session and redirect); Razorpay and others are further drivers behind the same interface** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does the business bring its own provider account? → **Yes, one connection per business, sealed credentials, settling to the business; the deployment default key set remains only for a single-business stack** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] When is the card charged? → **Authorise at checkout, capture at fulfilment for the picked amount; AT_CONFIRMATION is the business's choice; a group is captured at its last part** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What happens to the unpaid-order sweeper? → **It stays; cancelling releases the hold, and a late authorisation for a cancelled order is cancelled at once** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Where does 3-D Secure logic live? → **At the provider, on the provider's hosted flow; we request it per the business's SCA mode (provider decides by default, ALWAYS optional) and record the outcome and liability shift** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Saved cards? → **Not in this feature; when built, provider tokens only** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Fraud rules of our own? → **None beyond velocity metrics on the alerts page, an attempt cap and a review-before-capture hold; the provider's engine and liability shift come first** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who may allow a standalone machine at a store? → **OWNER only, per store, logged; reference required on every standalone card tender** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should a sandbox use MANUAL or the simulated driver? → **SIMULATED, so the whole state machine is rehearsable with no money** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What does a shopper's client see if the webhook is slow? → **A pending state; the app calls `/refresh` on return and polls with back-off** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does StoreQL's Stripe capture keep taking the whole authorisation? → **No: capture takes the adjusted or picked amount** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

Tenant isolation and refusals are marked (T) and (R).

- [ ] Slice 1. A CARD `POST /payments` at a store with an ACTIVE terminal is refused `409 PAYMENT_CARD_NEEDS_TERMINAL` (R) — `CardTenderRuleIT.cardNeedsTerminalWhereOneIsRegistered`
- [ ] With the store allowed standalone, the tender carries `STANDALONE` and its reference; without a reference `400 PAYMENT_CARD_REFERENCE_REQUIRED` (R) — `CardTenderRuleIT.standaloneNeedsReference`
- [ ] A retired terminal alone does not count as a terminal — `CardTenderRuleIT.retiredTerminalIsNoTerminal`
- [ ] A manager or cashier cannot allow a standalone machine; the change is logged with who and when (R) — `CardTenderRuleIT.onlyOwnerAllowsStandalone`
- [ ] Another business's owner cannot set or read our store's setting (T) — `CardTenderRuleIT.otherTenantCannotTouchStoreSetting`
- [ ] An approved terminal attempt still writes its tender as `TERMINAL` — `TerminalPaymentIT` (extended)
- [ ] Slice 2. A webhook arriving before the intent has its provider reference is applied through our metadata id, and one for an unknown intent of ours is answered non-2xx, not marked seen — `WebhookOrderingIT.eventBeforeReferenceIsApplied`, `WebhookOrderingIT.unknownIntentIsRedelivered`
- [ ] A sandbox business can only reach SIMULATED, whatever the deployment configures — `PaymentProvidersTest.sandboxReachesOnlySimulated`; `create` calls the tenant's provider, never `active()` — `PaymentIntentServiceTest.createUsesTenantProvider`
- [ ] Partial capture sends `amount_to_capture` and the tender's reference is the charge, matched by the settlement parser — `StripeRequestShapeTest.captureAmount`, `SettlementIT.onlineCaptureMatched`
- [ ] SIMULATED walks approve, decline, challenge-then-pass, challenge-then-fail, review and refund by amount, and its completion endpoint is 404 for a real provider (R) — `SimulatedProviderTest`
- [ ] Slice 3. A business connects Stripe, tests it, receives a first verified webhook and only then activates LIVE (R `409 PAYMENT_WEBHOOK_NOT_VERIFIED`) — `PaymentConnectionIT.liveNeedsVerifiedWebhook`
- [ ] Credentials are sealed at rest and never appear in a response, log or export — `PaymentConnectionIT.secretsNeverReturned`, `TenantDataExportIT` (extended)
- [ ] Two businesses use different providers at once, each webhook verified with its own secret; a webhook for A's connection signed with B's secret is refused (T) — `PaymentConnectionIT.perConnectionWebhookSecret`
- [ ] A sandbox cannot activate LIVE (R `409 PAYMENT_SANDBOX_NO_LIVE`) — `PaymentConnectionIT.sandboxNoLive`
- [ ] The gateway lets exactly `POST …/payments/webhooks/{provider}/{connectionId}` through without a JWT and nothing else under `payments/` — `JwtAuthFilterTest.webhookRouteExact`
- [ ] Slice 4. A shopper pays a PENDING order: intent REQUIRES_ACTION, challenge passed, AUTHORIZED, `PaymentAuthorized` confirms the order, no tender yet — `OnlineIntentIT.authorisationConfirmsOrder`
- [ ] The amount is order-svc's, never the client's: a wrong amount is `400 PAYMENT_AMOUNT_MISMATCH` and a display currency changes nothing (R) — `OnlineIntentIT.amountFromOrder`, `OnlineIntentIT.displayCurrencyNeverCharged`
- [ ] `OrderFulfilled` captures the picked amount once; a replay writes one tender — `OnlineIntentIT.captureOnFulfilmentOnce`
- [ ] AT_CONFIRMATION captures at authorisation — `OnlineIntentIT.captureAtConfirmation`
- [ ] A group is authorised once and captured at its last part with one tender and one `PaymentCaptured` per part; a part alone is `409 PAYMENT_ORDER_IN_GROUP` (R) — `GroupPaymentIT` (extended)
- [ ] Order cancelled or swept while requiring action cancels the hold; a challenge passed afterwards is cancelled at once and never captured — `OnlineIntentIT.lateAuthorisationForCancelledOrder`
- [ ] Webhook before or after the app's return reach the same state; `/refresh` applies the same transition — `OnlineIntentIT.webhookAndRefreshRace`
- [ ] A missed webhook is found by the reconciler — `IntentReconcilerTest`
- [ ] A capture that cannot be made publishes `PaymentCaptureFailed` and order-svc refuses handover `409 ORDER_PAYMENT_NOT_CAPTURED` (R) — `OnlineIntentIT.captureFailedHoldsHandover`, `OrderHandoverIT.unpaidPayNowRefused`
- [ ] `POST /payments/online` is `409 PAYMENT_USE_INTENT` for a business on a real connection (R) and unchanged on MANUAL — `OnlineIntentIT.onlineEndpointOnlyForManual`
- [ ] A shopper of another business, or another shopper, gets 404 on an intent (T) — `OnlineIntentIT.otherShopperGets404`
- [ ] A replayed `Idempotency-Key` returns the first intent and places one hold (Stripe stub sees one POST) — `OnlineIntentIT.replayPlacesOneHold`
- [ ] Widget: the pay step shows the provider fields or redirects, returns to a pending state that becomes paid or failed, and offers retry — `pay_step_test.dart`, `payment_return_screen_test.dart`
- [ ] Slice 5. A line closed short before capture captures less and publishes no refund; after capture it refunds through the provider once — `AdjustmentIT.beforeCaptureSmallerCapture`, `AdjustmentIT.afterCaptureProviderRefund`
- [ ] A refund to the original card calls the provider with a derived key, never exceeds the captured amount less refunds (R), and a failed one is FAILED with `PaymentRefundFailed` — `ProviderRefundIT`
- [ ] `PaymentRefunded` is published only when the provider confirms — `ProviderRefundIT.publishedOnConfirmation`
- [ ] A provider dispute fills the register and the Adyen one maps identically — `DisputeIT` (extended), `AdyenDisputeParsingTest`
- [ ] Slice 6. Adyen request shape (URLs, `X-API-Key`, `Idempotency-Key`, body, minor units) and its HMAC and basic-authentication checks (good, tampered, missing, wrong key) — `AdyenRequestShapeTest`, `AdyenWebhookVerificationTest`; the endpoint answers exactly `[accepted]` — `AdyenWebhookIT`
- [ ] Stripe request shape and signature (good, tampered, stale, missing) — `StripeRequestShapeTest`, `StripeWebhookVerificationTest` (exists)
- [ ] Slice 7. A run of declines past a business's `payments.declines` rule raises one alert from payment-svc's own attempts, and a sale is never slowed by it — `AttemptRiskIT.declinesRaiseTheAlert`
- [ ] With the cap set, the next attempt is `409 PAYMENT_ATTEMPTS_EXHAUSTED` (R); unset means unlimited — `AttemptRiskIT.capRefuses`
- [ ] With review on, an elevated-risk authorisation goes to REVIEW, does not confirm the order, appears in the queue; RELEASE authorises, CANCEL releases the hold; a non-manager is refused (R) — `ReviewIT`
- [ ] Only OWNER/MANAGER of that business see the queue (T) — `ReviewIT.otherTenantSeesNothing`
- [ ] Slice 8. No card number, expiry or security code is accepted or echoed by any payment route — k6 `gateway-card-data-guard` (extended)
- [ ] The whole online flow against SIMULATED, challenge included, and the till's card rule at a store with and without a terminal — k6 `card-payment-flow`
- [ ] `flow-guard-comprehensive` and `flow-guard-runtime` stay green — k6

## Screens

- **Storefront shell (web and mobile), cart payment step.** `GET /payments/online/config` decides the mode. In INTENT mode "Pay now" creates the intent (with a UUIDv7 key from `core/ids.dart`) and hands the token to a `CardPaymentLauncher` (an interface; the web implementation embeds the provider's hosted element or redirects, the mobile one uses the provider's sheet; the redirect path is the lowest scope and is always available). The app never renders a card field of its own and never sees a number. A **return route** (`/store/pay/return`) shows a pending state, calls `/refresh` once and then polls with back-off, and lands on paid, failed (with the provider's plain reason and a Retry that resumes the same intent) or "needs your bank" (challenge not finished). A split checkout shows one payment for the total. Amounts through `AppFormat.money`, the order's own currency only. Guest checkout works through the intent id.
- **Admin shell, Settings → Payments (owner).** Connect (provider, keys typed once and never shown again), Test, the webhook address to copy (with the connection id), "Webhook received" and Go live steps; capture timing, SCA mode, review mode, attempt cap; a per-store toggle "This store may take card on a standalone machine" (owner only, with the audit line). Words, not codes (`status_labels.dart`).
- **Admin shell, Payments to review (owner, manager).** The held list with risk, amount, order link, Release and Cancel with a note. Order detail shows the payment's states and its card refund status, with a failed refund and its next step.
- **Platform console:** nothing new (no secrets are visible there).
- **POS shell, tender.** With a terminal at the store, Card is the terminal's flow only; the standalone entry (machine reference required) appears only if the owner has allowed it, with the refusal reason in plain words otherwise. A tender declined by the terminal shows the terminal's own reason.
- **Design system:** every state uses `context.status.*` roles and the shared `EmptyState`/`ErrorView`/`LoadingView`; the design system's screen cards are updated for the pay step, the return states and the settings screen.

## Decisions

- **The driver interface already exists and stays;** it is extended, not replaced (per-connection, refund, retrieve, capabilities, batch webhooks). Two real drivers with different models prove it.
- **Replaced (kept for the record):** 22.8's "a sandbox is always MANUAL". It is now always SIMULATED, because the simulated driver rehearses the full state machine and the guarantee (no real money moves) holds in the driver itself. MANUAL remains for a business that has no processor. PRD §11's named pair (Razorpay, Stripe) is built as Stripe and Adyen; Razorpay is a later driver, not a redesign.
- **Authorise first, capture at the pick,** and the order confirms on authorisation. The tender, an append-only statement that money was taken, is written only at capture.
- **The provider's word is the truth, applied through one idempotent transition** used by the webhook, the return refresh and the reconciler alike.
- **The till rule lives in payment-svc** (it owns terminals) and the loosening permission is OWNER only.
