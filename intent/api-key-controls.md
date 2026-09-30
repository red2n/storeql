# API key controls: an IP allow-list on a key, first-use and dormant-use notices

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue (platform/plat-api-keys-and-sandbox PLAT-113, PLAT-114; MKT-45 in the notification flows) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, platform and access domain — PLAT-113, PLAT-114. (MKT-45, webhook addresses and mutual TLS, was drawn here first and is owned by [webhook-delivery-controls](webhook-delivery-controls.md).) |
| **Services** | iam-svc owns keys, their allow-lists and the detection of first and dormant use · the gateway checks the caller's address on every keyed request · notification-svc words and delivers the notice through the [exception-alerts](exception-alerts.md) inbox · the app gets the owner's screens |
| **Builds on** | iam-svc `api_keys` (`last_used_at` kept to the minute, `store_ids`, `expires_at`), `POST /platform/api-keys/introspect`, gateway `ApiKeyIntrospector` (10-second verdict cache) and `ClientIp`, `/auth/admin/api-keys`, `GET /auth/admin/security-events`, the exception-alert contract (`ExceptionAlertRaised`, metrics `api_keys.first_use` and `api_keys.dormant_use`) |
| **Built in** | (not yet built) |

## Problem

An API key works from anywhere. A key pasted into a chat, a repository or a decommissioned server keeps working, and the owner finds out when something goes wrong. A key made and never used, or asleep for a year and then suddenly used, looks like any other.

## Outcome

- The owner limits a key to the network ranges their servers use; a call from anywhere else is refused, and the refusal is in the security trail.
- The owner is told the first time a key is used, and the first time it is used again after a long sleep, so a leaked key that wakes up is noticed.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the owner (keys), the unrestricted manager (reads).
- **Channels:** gateway (keyed calls) · back-office API keys screen.
- **Scope:** per key. Keys are the business's; an owner sees only their own.
- **Roles that can write:** OWNER only, as keys are today (`ApiKeyResource`); a manager reads the list and the trail.
- **Sandbox tenant:** the same controls, on `sqk_test_` keys. A first-use notice in a sandbox is in-app only (the sandbox sends nothing else out, per CLAUDE.md's sandbox convention).

## Scope

- **In:**
  1. **Allow-list on a key (iam-svc, gateway).** A key may name a list of ranges (single addresses or CIDR blocks, IPv4 and IPv6). Empty means from anywhere, which is how every key works today and stays until the owner sets it. Set when the key is made or later. Introspection now returns the ranges, the gateway (which already resolves the caller's address, honouring `trustForwardedHeaders`) compares on every request, so the 10-second verdict cache never lets a wrong address through and never needs the address in its key. Refused: `403 API_KEY_IP_NOT_ALLOWED`. The refusal is audited once per key per network per day (not per request: an attacker must not be able to fill the trail).
  2. **First use and dormant use (iam-svc; the notice through exception-alerts).** When introspection finds `last_used_at` empty it raises **first use**; when it is older than the business's dormant period it raises **use after dormancy**. Each writes an audit line and one `ExceptionAlertRaised` (metrics `api_keys.first_use` and `api_keys.dormant_use`, subject KEY, a business-wide alert), so the owner reads it in the one alert inbox, in-app and by email, with the key's name and prefix, the truncated network the call came from and the time. iam-svc owns the detection and the two settings (its `api_key_settings`, not an `exception_rules` row: first use is always on and the dormant period is this page's setting); the inbox, acknowledgement and delivery are [exception-alerts](exception-alerts.md)'s. This page first designed a separate `ApiKeyUsed` event and consumer; that is withdrawn so a business has one place to read such notices. The dormant period is a business setting, off until the owner sets it (a business that runs a yearly reconciliation wants 400 days; one that runs nightly wants 7): the platform assumes none. First use is always on (it costs nothing and has no policy in it).
  3. **Webhook addresses and mutual TLS: not here.** This page first designed the platform's outbound addresses and a client certificate per webhook endpoint (MKT-45). Both are owned by [webhook-delivery-controls](webhook-delivery-controls.md) slices 3 and 4 (a platform-issued certificate and an optional pinned server authority, under `/admin/webhooks`), which also took this page's egress-change notice.
- **Out, on purpose:**
  - **A referrer restriction on a key.** Referrers exist for browser-held keys; an API key is a server secret and must never be in a browser. If a business needs a browser key it needs a different credential (a publishable one), which is its own feature.
  - **Per-key rate limits and per-key scopes beyond tier and stores.** Not asked; the tenant's `requests.per-minute` allowance applies.
  - **Allow-listing by country or ASN.** A location-neutral platform assumes no country; ranges say exactly what is meant.
- **Wave 1 note.** `2026-09-30-iam-notification.md` deferred these without work (API key IP allow-list and first-use notices). Nothing here changes what wave 1 finished.

## Data and flow

- **Owned by iam-svc:** `api_keys` gains `allowed_ranges` (text array of validated CIDRs; null or empty = any), `first_used_at`, and the owner's per-business `api_key_settings` (tenant, `dormant_days` nullable, updated by/at). Append-only audit codes: `API_KEY_FIRST_USE`, `API_KEY_DORMANT_USE`, `API_KEY_IP_REFUSED`, `API_KEY_RANGES_CHANGED` (added to the allow-list in `SecurityEvent` that may show their detail: key id, prefix and the truncated network; never the key).
- **Needs from other services:** the gateway asks iam-svc (existing introspection) and now also passes nothing new; the ranges come back in the answer. notification-svc learns of key events through `ExceptionAlertRaised` (below) and resolves the owner recipients as it does for every alert, never by reading iam-svc.
- **Endpoints:** `POST /auth/admin/api-keys` gains optional `allowedRanges`; `PATCH /auth/admin/api-keys/{id}` (OWNER; only `allowedRanges` and `name`, never the role or stores of an existing key) with `Idempotency-Key`; `GET /auth/admin/api-keys` shows the ranges and `firstUsedAt`; `GET/PUT /auth/admin/api-key-settings` (dormant days). Webhook routes are [webhook-delivery-controls](webhook-delivery-controls.md)'s.
- **Events published (outbox, `eventId`):** `ExceptionAlertRaised` (`storeql.alerts.exception-alert-raised`, [exception-alerts](exception-alerts.md)) with `metric` `api_keys.first_use` or `api_keys.dormant_use`, `subjectKind` KEY, `subjectKey` the key id, `evidence` the key id, and the truncated network and time in the alert's own fields; no name, prefix or address rides in it (the app resolves the key's name and prefix at read time, as it resolves staff names). notification-svc opens the alert and notifies the owners once per event.
- **Retryable writes:** the `PATCH` takes `Idempotency-Key`.
- **New error codes:** `403 API_KEY_IP_NOT_ALLOWED`, `400 API_KEY_RANGE_INVALID` (unparseable, or a range wider than the whole address space is allowed and means "any"; more than fifty ranges on one key is `400 API_KEY_TOO_MANY_RANGES`, a technical bound to keep the introspection answer small, not a business limit).
- **Settings:** `dormant_days` per business (owner: iam-svc; **default off**, no number assumed).

## Money, time and limits

- **Currency, ledger:** none.
- **Dates:** every instant UTC; "dormant" counts whole days from `last_used_at` (kept to the minute).
- **Plan limits:** none. These are controls on something the business already has, not a new allowance; no entitlement key is added.

## Constraints

- **Golden rules:** 3 (the business is the token's; a key is looked up by its hash and its tenant is its own); 8 (audit stays append-only).
- **Never trust the address the client sends:** the gateway strips any client-sent forwarding header it does not trust (`ClientIp` and `trustForwardedHeaders` are unchanged); an allow-list is only as good as that, and the gateway test pins it.
- **Fail-open or closed?** If iam-svc cannot answer, the gateway refuses the keyed call as it does today (`Unavailable`). An allow-list is **fail-closed**: a key with ranges is never let through on an unreadable verdict.
- **Personal data:** the network in a notice is truncated (the same rule as sessions in [sign-in-protection](sign-in-protection.md)).
- **Existing keys:** unchanged until an owner sets a range.

## Open questions

- [x] Should an allow-list be required? → **no; empty means any, as today** (industry standard: opt-in network restriction, under the user's standing instruction of 2026-09-30)
- [x] How long is "dormant"? → **the business chooses; off until set** (industry standard: no number assumed, under the user's standing instruction of 2026-09-30)
- [x] Is a first use always announced? → **yes; it carries no policy and costs one message** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What is the webhook "IP allow-list", and does mutual TLS replace the signature? → **answered on [webhook-delivery-controls](webhook-delivery-controls.md)** (the platform publishes its sending addresses and offers a platform-issued client certificate; both the transport and the signature stay), under the user's standing instruction of 2026-09-30

## Acceptance

- [ ] A key with ranges is accepted from inside them and refused `403 API_KEY_IP_NOT_ALLOWED` from outside, IPv4 and IPv6, on a fresh verdict and on a cached one — gateway `ApiKeyIpAllowListTest` (cache hit, different address), iam-svc `ApiKeyRangesIT`
- [ ] A key with no ranges behaves exactly as before — `ApiKeyRangesIT.noRangesMeansAny`, existing `ApiKeyIT`, k6 `api-keys-flow`
- [ ] A client-sent forwarding header cannot defeat the list — gateway `ClientIpTest.forwardedHeaderIsNotTrustedByDefault`
- [ ] An unreadable verdict refuses a key that has ranges and behaves as today for one that has none — gateway `ApiKeyIntrospectorTest.unavailableIsClosedForARestrictedKey`
- [ ] Bad ranges and more than fifty are refused with their codes; only an owner sets them; a manager reads — `ApiKeyRangesIT.validationAndRoles`
- [ ] Another business's owner and manager cannot read or change our key's ranges, even naming our key id (404) — `ApiKeyRangesIT.otherBusinessTouchesNothing` (tenant-isolation line)
- [ ] The first use of a key raises exactly one notice; the second use raises none; a use after the business's dormant days raises one; with no dormant setting none — pure `ApiKeyUsageTest`, `ApiKeyUsageIT` (the alert is opened once per event id by exception-alerts' `ExceptionAlertIT`)
- [ ] The refusal is audited once per key, network and day, however many calls — `ApiKeyRangesIT.refusalIsAuditedOncePerDay`
- [ ] Sandbox: notices are in-app only — exception-alerts' `ExceptionAlertIT.sandboxSendsNothingOut`

## Screens

- **Back-office → API keys:** the create dialog and a key's detail get an **Allowed addresses** field (one per line, validated as typed, "Anywhere" when empty), a **First used** line, and a settings row **Tell me when a key wakes up after N days** (off by default). A key refused from an address shows "Blocked from 203.0.113.0/24 today" from the trail.
- Words not codes, dates through `AppFormat`, adaptive per UI-GUIDE §7.2.

## Decisions

Filled while building. Known ahead of time (2026-09-30, reconciled with the other wave-2 pages): the webhook halves of this page moved to [webhook-delivery-controls](webhook-delivery-controls.md) (platform-issued certificates, not uploaded ones); the first-use and dormant-use notices travel as `ExceptionAlertRaised` and are read in the [exception-alerts](exception-alerts.md) inbox rather than through a private `ApiKeyUsed` event.
