# Consent evidence: double opt-in where the law expects it, and re-asking after silence

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on marketing consent · 2026-09-30 |
| **Roadmap** | new: flow catalogue (artifact R391n2d2cV23sdKHKUnpGc), area customer — `mkt-consent-and-marketing` gaps 1 and 2, case MKT-16 |
| **Services** | customer-svc owns consent, the confirmation, the re-ask and the evidence · tenant-svc owns the register of where the law expects a confirmation or caps a consent's age · notification-svc sends the confirmation and re-ask messages in the platform's own words and asks `allowance` before every marketing send · the app gives the shopper the confirm page and the business its settings |
| **Builds on** | `marketing_preferences` (per channel: EMAIL, SMS, PHONE, POST; basis CONSENT, SOFT_OPT_IN, NONE), `marketing_consent_log` (append-only; source SIGNUP, CHECKOUT, PREFERENCE_CENTRE, STAFF, UNSUBSCRIBE_LINK, IMPORT, PURPOSE_WITHDRAWN; `notice`; `actor_id`), `purpose_consents`/`purpose_consent_log` (`MARKETING` purpose; withdrawing it switches every channel off, `MARKETING_PURPOSE_NOT_GRANTED`), `GET /customers/{id}/marketing/allowance` (notification-svc asks before every send), the unsubscribe token (`marketing_unsubscribe_tokens`, only the hash kept), `Jurisdictions`/`DPDP` (per-purpose law), the password-reset flow's model for a platform-worded message with a single-use hashed link |
| **Built in** | not yet built |

## Problem

A single tick is the same evidence in every country. Where the law or settled practice expects a stronger proof that the owner of an address or a phone really agreed, a business holding only a tick cannot show it. And consent, once given, lasts forever: a customer who has not bought or opened anything for years still counts as agreed, and the business has no way to ask again.

## Outcome

- **Where a confirmation is expected, a channel is not on until the person confirms it.** They tick, a message goes to that address or number, and they confirm; only then can anything be sent. What was sent and when they confirmed is kept as evidence.
- **A business can ask for a confirmation whatever the law says**, for any channel it can confirm.
- **A business can decide to re-ask after a period of no contact.** Off until set. A person who does not answer stops being marketed to; a person who answers is refreshed.
- **Everything still runs under the existing `MARKETING` purpose rules:** withdrawing the purpose switches channels off, a channel cannot be switched on without it where a per-purpose law binds, and `allowance` is asked before every send.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **shopper** (ticks, confirms, is re-asked), the **owner** (sets confirmation and re-ask), the **manager** (reads the evidence), staff recording consent at the till or by phone.
- **Channels:** EMAIL and SMS can be confirmed (the confirmation goes to the address or number itself). PHONE and POST have no address to prove; they stay a recorded tick, with the evidence they have.
- **Scope:** per business, per customer, per channel.
- **Roles that can write:** the customer; staff on their behalf as today (source STAFF). Settings: OWNER.
- **Sandbox tenant:** behaves the same; notification-svc suppresses the messages.

## Scope

- **In (slices in build order):**
  1. **Double opt-in (customer-svc, notification-svc, tenant-svc).**
     - **When it is required for a channel of a business:** a `CONSENT_CONFIRMATION` obligation in tenant-svc's register naming the channel (scope: a country or regime, with a citation), read through `Jurisdictions` for every country the business trades in; **or** the business's own setting `marketing_settings.confirm_channels` (a set, empty until set). Either makes it required. The register is data and starts empty of any assertion made here; a row is added, with its citation, when a country's rule is confirmed. Until then a business that wants it, or whose counsel says the law expects it, sets it itself.
     - **Flow:** when a channel that needs confirmation is switched on (by the shopper at signup, checkout or the preference centre, or by staff), the preference is recorded as `PENDING` (not granted; `allowance` denies with reason `CONFIRMATION_PENDING`), one `marketing_confirmations` row is written (only a hash of the single-use token and a hash of the address or number it goes to), and one `MarketingConfirmationRequested` event carries the link to notification-svc. notification-svc sends it **in the platform's own words** (not a message a business can reword, so the evidence reads the same everywhere; the languages the reset email covers, English as fallback), by email to the address or by text to the number, saying who is asking, for what (which channel, which business) and that nothing is sent until the person confirms. Like a password reset link, it belongs to no catalogue.
     - **Confirming:** the link opens a page in the app that shows the same words and a button; the button calls `POST /marketing/confirm` (public route through the gateway, token in the body, never a GET, so a mail scanner that opens links confirms nothing). It spends the token once, grants the channel with basis `CONSENT`, and writes a `marketing_consent_log` row with source `CONFIRMATION` carrying the notice, the request time, the confirm time and `confirmation_id`. A newer request replaces the older token. A token that is unknown, used or superseded is `410 MARKETING_CONFIRMATION_INVALID` (never says which). A link has no invented lifetime: it is valid until used, superseded, or the channel is switched off.
     - **What is kept as evidence:** per confirmation, when it was sent, when confirmed, through which channel, to which destination (as a hash: the destination itself is in the customer's record), and the notice shown at the tick and on the confirm page. **No IP address and no device data are kept**: they are personal data the evidence does not need.
     - **Soft opt-in where the law allows it for existing customers is unchanged** (basis `SOFT_OPT_IN`), and never a substitute where confirmation is required for that channel (`allowance` says which basis it accepted).
     - **Reminder:** none by default. A `PENDING` channel stays pending; it never becomes granted by waiting.
     - A confirmation cannot be given on someone's behalf by staff: staff may start it (the message goes to the customer), never finish it.
  2. **Re-asking after silence (customer-svc, notification-svc).**
     - **Setting:** `marketing_settings.reconsent_after_months` and `reconsent_grace_days`, **both empty until the business sets both**. A business trading where the register has a `CONSENT_REFRESH_PERIOD` (a maximum age of a consent, a number and unit, with a citation) is held to the shorter of its own and the law's, and the law's applies even if the business set none (the business is told on the settings screen). None is seeded on this page's authority.
     - **What counts as contact:** the latest of the customer's last sale (from the order events customer-svc already consumes; stored as `customers.last_activity_at`), their last preference or consent action, and their last sign-in signal customer-svc receives; a reply to a re-ask counts.
     - **The sweep (daily):** for a customer whose granted CONSENT channel has had no contact for the period, customer-svc publishes one `MarketingReconsentRequested` (once per channel per silence: `reconsent_requests`, unique) with a confirmation link as above; notification-svc sends "do you still want to hear from us" in the platform's words. **Confirming refreshes the consent** (a log row with source `RECONFIRMED`). **After the grace period with no answer the channel is switched off** (source `LAPSED`, logged like any switch-off, `basis` NONE) and never again asked unless the customer opts in anew. Only CONSENT is re-asked; a soft opt-in is governed by its own rules; withdrawn channels are never asked.
     - Never for a business with a withdrawn `MARKETING` purpose, an anonymised or merged customer, a child without a guardian record (DPDP), or a channel the business itself has switched off.
  3. **The evidence view (customer-svc).** `GET /customers/{id}/marketing/evidence` (OWNER, MANAGER): per channel, the current state (GRANTED, PENDING, WITHDRAWN, LAPSED), basis, the log with each row's source and notice, and the confirmations with their times. This is what a business shows a regulator or a customer who asks "when did I agree to this". Included in the customer's export.
  4. **Screens.**
- **Out, on purpose:**
  - **Inbound SMS replies** ("reply YES"): they need a number that receives texts, a provider webhook and a carrier compliance regime; the link confirms the same thing. A provider that supports it would be a driver behind the SMS interface, with the same evidence rows.
  - **Confirming PHONE and POST.** Nothing to prove; they stay a recorded tick with the evidence there is (channel, notice, who, when).
  - **Recording IP addresses or device fingerprints as evidence.** Personal data the proof does not need; the time, the token and the destination hash are the proof.
  - **A fixed re-ask period or a fixed law for any country.** The period is the business's; the law's cap is a register row added when confirmed.
  - **Consent for non-marketing purposes** (LOYALTY, PERSONALISATION, ANALYTICS): unchanged.

## Data and flow

- **Owned by customer-svc:**
  - `marketing_preferences.basis` adds no value; the pending state is `granted = false` with a new column `pending_since` (nullable), so the existing `(basis = 'NONE') = (granted = FALSE)` check still holds (basis NONE while pending).
  - `marketing_confirmations`: id, `tenant_id`, `customer_id`, `channel`, `kind` (OPT_IN, RECONSENT), `token_hash`, `destination_hash`, `notice`, `sent_at`, `confirmed_at`, `superseded_at`. Append-only apart from the two nullable instants set once.
  - `marketing_consent_log.source` adds `CONFIRMATION`, `RECONFIRMED`, `LAPSED`; new column `confirmation_id`.
  - `marketing_settings` (per business): `confirm_channels`, `reconsent_after_months`, `reconsent_grace_days`.
  - `customers.last_activity_at`; `reconsent_requests` (unique per business, customer, channel and silence start).
- **Owned by tenant-svc:** the obligation codes `CONSENT_CONFIRMATION` (with the channel as its qualifier) and `CONSENT_REFRESH_PERIOD` (`limit_value`, `limit_unit`, the columns added by `intent/privacy-requests.md`, whichever is built first).
- **Endpoints (customer-svc):** `POST /marketing/confirm` (public); `PUT /customers/me/marketing` and `PUT /customers/{id}/marketing` change behaviour for a channel needing confirmation (answer shows `pending`); `POST /customers/me/marketing/{channel}/resend` (the shopper asks for another link); `GET`/`PUT /admin/marketing/settings` (management reads, OWNER writes); `GET /customers/{id}/marketing/evidence`. `GET /customers/{id}/marketing/allowance` gains reasons `CONFIRMATION_PENDING` and `CONSENT_LAPSED`.
- **Needs from other services:** `Jurisdictions` (countries and rules) and `TenantProfiles`. notification-svc holds its own delivery log.
- **Events published:** `MarketingConfirmationRequested` and `MarketingReconsentRequested` (`storeql.customer.marketing-confirmation-requested`, `…-reconsent-requested`: `eventId`, `tenantId`, `customerId`, `channel`, `link`, `language`, `businessName`; the link is a bearer secret, so the event is consumed and the copy in notification-svc's log has the link removed, as password-reset rows do); `MarketingConsentChanged` where it already exists carries the new sources.
- **Retryable writes (Idempotency-Key):** the confirm call is single-use by its token; the resend is rate-limited per channel (three a day per customer and channel, stated as the platform's protection against mail bombing, the same idea as the reset's three an hour).
- **New error codes:** `410 MARKETING_CONFIRMATION_INVALID`, `409 MARKETING_CHANNEL_NOT_CONFIRMABLE` (asking to confirm PHONE or POST), `429 MARKETING_CONFIRMATION_RATE_LIMITED`, `400 MARKETING_SETTINGS_INVALID` (a period without its grace, or a period shorter than the law's cap), `409 MARKETING_PURPOSE_NOT_GRANTED` (exists).

## Money, time and limits

- **Currency:** none. **Ledger postings:** none.
- **Dates:** every instant UTC; silence counts calendar months from `last_activity_at`; grace counts days from the request.
- **Plan limits:** none. (Texts sent for confirmations are metered like any text, `21.10`.)

## Constraints

- Golden rules 3 (the public confirm finds the tenant from the token's own row, never from the request), 7, 8 (the log stays append-only; a lapse is a new row), 15.
- **Privacy:** only hashes of tokens and destinations; the link removed from the log copy; no IP, no device.
- **Location-neutral:** wording per language; which channels need confirmation comes from the register or the business, never a country in code.
- **Marketing purpose rules unchanged:** `MARKETING` withdrawal cascades to channels (`PURPOSE_WITHDRAWN`); `allowance` is asked before each send and now knows PENDING and LAPSED.
- Existing consents stand as they are: a business turning confirmation on does not put existing GRANTED channels into PENDING; it applies to new grants. A re-ask period, once set, applies to existing consents by their last contact.
- The public confirm route needs the gateway allow-list and its rate limit (`storeql.gateway`), and the password-reset-style "same answer known or not" for the resend.

## Open questions

- [x] **Which channels can be confirmed?** Recommended: EMAIL and SMS, by a link in the message. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **How long is a link valid?** Recommended: until used or superseded, no invented lifetime. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Does silence after a re-ask stop marketing?** Recommended: yes, that is the point of asking; logged as `LAPSED`. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Which countries need confirmation, and what age caps a consent?** Recommended: the register's data, with citations, added when confirmed; a business can set its own regardless. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] In a business where SMS needs confirmation, ticking SMS leaves it PENDING and `allowance` says `CONFIRMATION_PENDING`; the message goes to that number; confirming grants it with a log row (source `CONFIRMATION`, notice, times, confirmation id). — `ConsentConfirmationIT.pendingUntilConfirmed`, `confirmationLeavesEvidence`
- [ ] The same channel in a business with no such rule (register or setting) is granted at the tick as today. — `ConsentConfirmationIT.noRuleNoConfirmation`
- [ ] A business can require confirmation for a channel through its own setting; the register's rule for a country applies to a business trading there; both together are required once. — `ConsentConfirmationIT.registerOrSetting`
- [ ] A link is single use, superseded by a newer one, and a wrong, used or old token is one `410 MARKETING_CONFIRMATION_INVALID` that does not say which; a GET confirms nothing. — `ConsentConfirmationIT.singleUseAndSuperseded`, `aGetConfirmsNothing`
- [ ] Staff can start but not finish a confirmation. — `ConsentConfirmationIT.staffCannotConfirmForACustomer`
- [ ] PHONE or POST cannot be confirmed (`409 MARKETING_CHANNEL_NOT_CONFIRMABLE`) and stay a recorded tick. — `ConsentConfirmationIT.phoneAndPostStayATick`
- [ ] Only hashes are stored, the notification log copy of the message has its link removed, and no IP is stored. — `ConsentConfirmationIT.onlyHashesAreKept`; notification-svc `MarketingConfirmationHandlerTest.theLinkIsNotKept`
- [ ] Re-asking is off until both period and grace are set; a period without grace, or shorter than the register's cap, is `400 MARKETING_SETTINGS_INVALID`; a register cap applies even to a business with no setting. — `ReconsentTest.*`, `ReconsentIT.offUntilSet`, `theLawCapsThePeriod`
- [ ] A granted CONSENT channel with no contact for the period is asked once; confirming refreshes it (`RECONFIRMED`); silence past the grace switches it off (`LAPSED`), and it is never asked again. A soft opt-in, a withdrawn channel and a customer with recent activity are not asked. — `ReconsentIT.askedOnceAndLapsesOnSilence`, `aReplyRefreshes`, `onlyConsentIsAsked`, `recentActivityIsNotAsked`
- [ ] A withdrawn `MARKETING` purpose stops the re-ask; an anonymised customer is never asked. — `ReconsentIT.withdrawnPurposeIsNotAsked`
- [ ] The evidence view shows state, basis, log and confirmations, is in the export, and needs OWNER or MANAGER. — `ConsentEvidenceIT.evidenceForARegulator`, `onlyManagementReads`
- [ ] Another business's staff and shoppers, of every role, touch none of it (404); a token from another business confirms nothing of ours. — `ConsentConfirmationIT.anotherBusinessTouchesNothing`, `aTokenIsBoundToItsBusiness`
- [ ] k6 `marketing-consent-flow` extended: confirm-required channel, re-ask, lapse; `flow-guard-*` green.
- [ ] Widget tests: `marketing_confirm_screen_test.dart`, `marketing_settings_card_test.dart`, `consent_evidence_screen_test.dart`.

## Screens

- **Storefront:** the preference centre shows a channel as "waiting for you to confirm" with Resend; a public confirm page (the same words as the message, one button, a clear result); a re-ask page ("keep hearing from us?").
- **Admin shell, Marketing settings:** channels that must be confirmed, the re-ask period and grace, with the law that applies to the business's countries in words. **Customers:** an Evidence tab per customer.
- **POS:** asking for a channel consent at the till shows "a message will be sent to confirm" for a confirmable channel.

## Decisions

- **Links confirm only on a button (POST)** (2026-09-30, industry standard): mail scanners open links; a GET that confirmed would grant consent nobody gave.
- **No IP address is kept** (2026-09-30): evidence is the token, the times and the notice.
