# Webhook delivery controls: every event carries an id, sensitive events get a stronger transport, skips are counted

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on webhooks and delivery · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `mkt-webhooks` gap 1 (case MKT-45) and the unfinished half of `mkt-notification-delivery` gap 1 (case MKT-34) |
| **Services** | notification-svc owns endpoints, deliveries and **every transport control (sending addresses, mutual TLS, a pinned server CA), including those [api-key-controls](api-key-controls.md) first designed** · pricing-svc, payment-svc and order-svc publish `eventId` on the events the fan-out could not identify · the app's Webhooks screen shows the addresses and the certificate |
| **Builds on** | `webhook_endpoints` (`url`, `secret_sealed`, `events`, `enabled`, `disabled_reason`), `webhook_deliveries` (unique `(endpoint_id, event_id)`), `webhook_attempts`, `WebhookFanout`, `WebhookEventIds` (30 Sep: derives an id for `OrderPlaced` from `orderId` and `PaymentCaptured` from `paymentId`), `WebhookDeliverer` (Helidon WebClient, HTTPS on a public address, HMAC signature), `Webhooks.CATALOGUE` (`OrderPlaced` … `CustomerRegistered`), `EventPayload.base(...)` |
| **Built in** | not yet built |

## Problem

Wave 1 found the "Event skipped" warnings were real: three event types carried no `eventId`, so a business subscribed to them received nothing. notification-svc now derives an id for two of them, and (later the same day, in the wave-1 working tree) the producers were changed to carry their own: `Events.orderPlaced` and the layaway events (order-svc, `EventsEventIdTest`), `paymentCaptured` and `paymentFailed` (payment-svc), `priceChanged` and `promotionActivated` (pricing-svc). This page was written before that landed; slice 1 is therefore reduced, see below. Second, a business subscribing to payment events has HTTPS and an HMAC signature and nothing else: no fixed source address to allow-list, no client certificate its server can demand. Third, a skipped event is still only a log line; no metric tells a benign skip (nobody subscribed) from a failure.

## Outcome

- **Every event on the webhook catalogue carries its own `eventId`** from its producer, so deliveries are deduplicated on it and a redelivery is never a second event. The derived fallback becomes unused.
- **A business can lock its receiving side down**: the platform publishes the addresses it sends from, and, per endpoint, presents a client certificate the business's server can require (mutual TLS).
- **A business can require mutual TLS for the events that carry money or personal data**, and the platform will not register an endpoint for those events without it.
- **The owner sees what was skipped and why**, and operations see the counts.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** (registers endpoints, sets the rule, downloads the certificate authority), the **manager** (reads), the business's engineer on the other side.
- **Channels:** back-office (Webhooks screen); platform config for the addresses.
- **Scope:** per business, per endpoint.
- **Roles that can write:** OWNER, as today. **Sandbox tenant:** behaves the same, with the sandbox's deliveries marked.

## Scope

- **In (slices in build order):**
  1. **Producers carry `eventId`: already built by wave 1 (2026-09-30, working tree, not yet committed); what is left is the guard.** The five builders named in the first draft (`Events.priceChanged`, `promotionActivated`, `paymentCaptured`, `paymentFailed`, `orderPlaced`) already end their payload with `eventId` (`EventsEventIdTest` in order-svc reads every builder's id with `Ids.parse`). Remaining: a contract test in `events-contract`, `WebhookCatalogueCarriesEventIdsTest`, asserting that **every type on `Webhooks.CATALOGUE`** is published by a builder that carries an `eventId`, so a new event type cannot be added to the catalogue without one; `PriceChanged` becomes deliverable end to end (`WebhookIT.aPriceChangedIsDeliveredOnce`). The fan-out's derived-id fallback in `WebhookEventIds` stays as a guard and warns once per type per process, as today, if a producer ever regresses. Events already in the outbox or on the topic without an id are still delivered through the fallback where a key exists.
  2. **Skips are counted and shown (notification-svc).** `webhook_events_skipped_total{type,reason}` (reasons `NO_SUBSCRIBER`, `NO_EVENT_ID`, `NO_TENANT`, `MALFORMED`, `ENDPOINT_DISABLED`) and `webhook_deliveries_total{result}` on the metrics endpoint. `NO_EVENT_ID` and `MALFORMED` are the failures, and they are **operators' series, not a business's alert rule**: no business can fix an event the platform published without its id, so `webhook_events_skipped_total{reason="NO_EVENT_ID"}` is watched by the platform's own monitoring and prevented by the `events-contract` test of slice 1 (see [exception-alerts](exception-alerts.md), "deliberately not an alert metric"). What a business can watch is `webhooks.delivery_failures` on its own endpoints. The one warning per type stays; the rest is the metric. The owner's Webhooks screen shows, per endpoint, deliveries by result and the last failure reason it already keeps.
  3. **Where our requests come from (notification-svc).** `GET /admin/webhooks/egress` (OWNER, MANAGER) answers the addresses (or ranges) the platform delivers webhooks from, taken from configuration (`storeql.webhooks.egress-addresses`, from the deployment's `.env` / manifest, empty where the deployment cannot name them, in which case the answer says so and the owner is told mutual TLS is the stronger control; a deployment that sends through a shared cloud gateway leaves it empty rather than promise addresses it cannot keep). **A change to the list is announced to every business's owners a stated notice before it takes effect** (deployment setting `storeql.webhooks.egress-change-notice-days`; while it is empty a change is announced when made), by notification-svc in the platform's own words, in-app and by email, so a subscriber's firewall can be updated first (moved here from api-key-controls). Documentation says how to allow-list them and that the HMAC signature remains the proof of origin.
  4. **Mutual TLS per endpoint (notification-svc).** An endpoint may switch on `mtls`. The platform then presents a client certificate on every delivery to that endpoint, issued by the platform's webhook certificate authority; the business downloads the authority's public certificate (`GET /admin/webhooks/ca`) and configures its server to require certificates it signed. The client certificate identifies the business (subject carries the business id, no personal data), is issued per business, and rotates: `POST /admin/webhooks/mtls/rotate` (OWNER) issues a new one, and the old one keeps working for the overlap the deployment sets (`storeql.webhooks.mtls.overlap`, config, not a code constant). The private key is sealed like endpoint secrets (`storeql.webhooks.secrets-key`), the authority's key is a deployment secret from `.env`, never the repo. **Trusting the subscriber's server:** where the subscriber's server certificate is signed by a private authority, the owner may attach that authority's **public** certificate to the endpoint (`pinnedCaPem`, validated: parses, is a CA, not expired; never a private key), which the driver trusts for that endpoint only (moved here from api-key-controls; refused for a non-HTTPS or private-address URL, `409 WEBHOOK_MTLS_NOT_ALLOWED_FOR_URL`, since mutual TLS never relaxes the URL check). The HMAC signature stays on as well: mutual TLS proves the caller to the network layer, the signature proves the message. A delivery whose handshake fails is a failed attempt like any other and counts towards the disable-after-failures rule. The interface is `WebhookTransport` with two drivers, `Standard` (HTTPS + HMAC) and `MutualTls`, and a `SIMULATED` authority for tests; each real driver has a stub-backed test of its exact handshake, as `docs/ACCOUNTING-CONNECTORS.md` describes.
  5. **Requiring it for sensitive events.** Catalogue entries carry `sensitive` (`PaymentCaptured`, `PaymentRefunded`, `OrderReturned`, `CustomerRegistered` carry money or personal data). A business setting `webhooks.require_mtls_for_sensitive` (default **off**) makes registering or editing an endpoint for a sensitive type without `mtls` refused `409 WEBHOOK_MTLS_REQUIRED`; switching it on does not disable existing endpoints, it marks them "does not meet your rule" until fixed, and a later delivery to a non-compliant endpoint of a sensitive type is held (DEAD with reason, not sent) once the rule has been on for a full day. No number invented: the one-day grace is a stated technical constant of one delivery-day so a business is not surprised; it is shown on the screen.
  6. **Screens.**
- **Out, on purpose:**
  - **A business-supplied client certificate and private key for the platform to present** (api-key-controls' first design: the owner uploads a chain and key per endpoint). Withdrawn: the subscriber wants to verify *us*, so the identity must be the platform's; a business's private key in our store would give every endpoint its own platform identity, make rotation the business's chore and put a secret we have no need to hold at risk. The subscriber's own server certificate is theirs to pin on their side; what they may give us is the public certificate of the authority that signed it (above).
  - **IP allow-listing enforced by the platform on the business's behalf.** We do not control their firewall; we publish the addresses.
  - **Per-event signing keys.** One secret per endpoint as built; rotation exists.
  - **Delivery to private or non-HTTPS addresses.** Refused already (`WEBHOOK_URL_INVALID`) and stays refused; mTLS does not change that.

## Data and flow

- **Owned by notification-svc:** `webhook_endpoints` gains `mtls` (boolean, default false) and `pinned_ca_pem` (nullable text, public); `webhook_settings` (per business: `require_mtls_for_sensitive`, `mtls_rule_since`); `webhook_client_certs` (per business: `serial`, `cert_pem`, `key_sealed`, `issued_at`, `retire_after`), append-only in the sense that a rotation adds a row and never edits one.
- **Owned by the publishing services:** the `eventId` field on the events named in slice 1.
- **Endpoints:** `GET /admin/webhooks/egress`, `GET /admin/webhooks/ca` (OWNER, MANAGER), `POST /admin/webhooks/mtls/rotate` (OWNER, `Idempotency-Key`), `PUT /admin/webhooks/settings` (OWNER); the endpoint create and update requests gain `mtls`; the event catalogue answer gains `sensitive`.
- **Needs from other services:** none; the fan-out consumes the events it already does.
- **Events published:** none new.
- **Retryable writes (Idempotency-Key):** the rotation.
- **New error codes:** `409 WEBHOOK_MTLS_REQUIRED`, `409 WEBHOOK_MTLS_NOT_AVAILABLE` (the deployment has no authority configured), `400 WEBHOOK_CERTIFICATE_INVALID` (a pinned authority that does not parse, is not a CA or has expired), `409 WEBHOOK_MTLS_NOT_ALLOWED_FOR_URL`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** none. **Ledger postings:** none.
- **Dates:** certificate `issued_at` and validity in UTC; the overlap and validity are deployment configuration.
- **Plan limits:** none.

## Constraints

- Golden rules 5 (keys and authority from `.env`, never in the repo or an image), 6 and 7 (deliveries deduplicated on `eventId`), 12 (metrics), 15.
- **No secrets in the repo** (standing rule): the authority key, sealing key and any fixture certificate come from git-ignored `.env`; tests generate their own throwaway authority at run time.
- Existing endpoints and signatures do not change: `mtls` is off unless switched on; the payload shape gains only `eventId`, which the fan-out already reads.
- The deliverer's timeout, retry and disable-after-failures rules are unchanged.

## Open questions

- [x] **Is mTLS or an IP list the better control?** Recommended: both offered; mTLS is the strong one and the only one the platform can enforce. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which events are sensitive?** Recommended: the four named, in the catalogue as data, extendable. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Is the rule on by default?** Recommended: no; a business turns it on. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] Every type on the webhook catalogue is published with an `eventId` (the five named types already are, wave 1). — `events-contract` `WebhookCatalogueCarriesEventIdsTest`; existing `EventsEventIdTest` (order-svc), and the payment-svc and pricing-svc equivalents wave 1 wrote
- [ ] A `PriceChanged` reaches a subscribed endpoint once; a replay does not deliver twice. — notification-svc `WebhookIT.aPriceChangedIsDeliveredOnce`
- [ ] Skips are counted by reason; only `NO_EVENT_ID` and `MALFORMED` are failures. — `WebhookMetricsIT.skipsAreCountedByReason`
- [ ] The egress answer lists the configured addresses, or says none are configured. — `WebhookIT.egressAddresses`
- [ ] An `mtls` endpoint's delivery presents the business's certificate and a server that requires it accepts it; a server that requires another authority's is refused as a failed attempt. — `WebhookMutualTlsTest.exactHandshake` (stub-backed), `WebhookIT.mtlsDeliveryLands`, `aWrongAuthorityFails`
- [ ] A pinned authority is accepted for its endpoint only, a bad one is `400 WEBHOOK_CERTIFICATE_INVALID`, and a server signed by it completes the handshake while one signed by another authority fails — `WebhookMutualTlsTest.pinnedAuthority`, `WebhookIT.aPinnedCaIsPerEndpoint`
- [ ] A change to the egress list is announced to owners once, `egress-change-notice-days` before it applies — `WebhookIT.egressChangeIsAnnounced`
- [ ] Rotating issues a new certificate, both work through the overlap, and a retried rotation issues one. — `WebhookIT.rotationOverlaps`, `aRetryRotatesOnce`
- [ ] With the rule on, a sensitive endpoint without `mtls` is refused `409 WEBHOOK_MTLS_REQUIRED`; existing ones are marked non-compliant, and delivery is held after the grace. With the rule off, nothing changes. — `WebhookIT.sensitiveNeedsMtlsWhenRequired`, `ruleOffChangesNothing`
- [ ] Only OWNER sets `mtls`, the rule and rotates; a manager reads; another business's owner and manager, naming our endpoint ids, get 404 and nothing moves; no key or private material is in any answer. — `WebhookIT.onlyTheOwnerChangesTransport`, `anotherBusinessTouchesNothing`, `noPrivateMaterialInAnyAnswer`
- [ ] k6 `webhook-flow` extended for the event ids; `flow-guard-*` green.
- [ ] Widget tests: `webhooks_screen_test.dart` (egress, certificate, rule).

## Screens

- **Admin shell, Webhooks:** per endpoint a "Require client certificate (mutual TLS)" switch, the authority download and rotate action, the platform's sending addresses, the deliveries by result, and a "does not meet your rule" mark. A settings card for the sensitive-events rule with the grace stated.

## Decisions

- **Reconciled with api-key-controls (2026-09-30).** That page's slices 3 and 4 (egress list, mutual TLS by an uploaded client certificate) are withdrawn in favour of this page's slices 3 and 4, with the routes under `/admin/webhooks` (its `/admin/notifications/webhooks/...` never existed). Kept from it and moved here: the egress-change notice and the pinned server authority.
- **Events are identified by their producers** (2026-09-30, industry standard): the fan-out's derived id is a guard, not a design.
- **We publish addresses and offer mutual TLS; we do not enforce the business's firewall** (2026-09-30).
