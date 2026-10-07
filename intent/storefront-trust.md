# Storefront trust: bots and card testing, a shopper-only sign-in, the split shown first, and search-engine data

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | new: the flow catalogue's open findings, wave 2 — online/shopper-account (SHOP-17, register), online/checkout (velocity), online/split-fulfilment (preview), online/storefront-browsing (dark store, structured data, SHOP-10, SHOP-11) |
| **Services** | the gateway owns the human check and the abuse counters · iam-svc owns the sign-in rule · order-svc owns the routing preview · product-svc owns product structured data, the feed and the sitemap · tenant-svc owns the business's human-check mode and whether its storefront may be indexed · the app shows the challenge, the split and the delivery-only notice |
| **Builds on** | gateway `BruteForceFilter` (5 failures, `LOGIN_LOCKED`, 15 minutes), `RateLimitFilter`, `TenantRateLimitFilter`, `CardDataGuardFilter`, `JwtAuthFilter` (the storefront tenant from the host / `X-Storefront-Tenant`), `BodySizeFilter`; iam-svc `AuthService.login`, `User.signInCandidates`, `accountType`; order-svc `OrderRouter`, pure `Routing`, `OrderGroup`; product-svc age rules; the storefront `cart_screen.dart` split sheet; `frontends/storeql-app/web/robots.txt` |
| **Built in** | |

## Problem

- **Anyone can sign up or attack checkout at machine speed.** There is nothing but a per-IP rate limit between the internet and `/auth/register`, the password-reset request and the online payment endpoint, so a bot can create thousands of accounts, and a card-testing script can try stolen card numbers a small amount at a time against a business's checkout.
- **A staff credential works on the shopper's form.** Since 29 Sep the storefront asks for a shopper login, but an address that holds only a staff login still gets a staff token from the "one account signs in from anywhere" fallback, and an order that person then places belongs to their own business, not the one they were browsing.
- **The split arrives after the point of no return.** The shopper learns their order will come in two parcels from two shops only when the order is placed and stock is held.
- **Search engines and price comparers see nothing.** The storefront is a Flutter app (drawn on a canvas) and `robots.txt` says `Disallow: /`, so products cannot be found or listed.

## Outcome

- Sign-up, password-reset requests and online payment are protected by a proof that a person, or a browser doing honest work, is on the other end. Ordinary shoppers never see it unless the source is behaving like a bot; a business can ask for it always. No captcha vendor is assumed: the platform's own proof-of-work works everywhere, vendors plug in as drivers.
- A card-testing pattern (many declined or tiny payments from one source) is slowed to a stop at the gateway before it reaches the card processor.
- The storefront's sign-in accepts only shopper logins, the staff app only staff logins; a wrong kind answers exactly like a wrong password.
- The cart tells the shopper before they pay: "Your order will come in 2 parcels, from Shop A and Shop B", from the same routing the order will use, advisory and labelled so.
- A business that wants to be found switches on indexing; each product then has a page a crawler can read, structured data (schema.org Product with Offer), a sitemap and a feed for price-comparison services.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the shopper (challenge, split preview), the owner (human-check mode, indexing), a platform operator (thresholds, drivers), a search engine or comparison service (crawler).
- **Channels:** ONLINE storefront; the gateway for everything public.
- **Scope:** human-check mode and indexing per business; abuse thresholds are the platform's, per source.
- **Roles that can write:** OWNER sets the business's human-check mode and indexing; PLATFORM_ADMIN sets the platform driver and thresholds (config, not a screen).
- **Sandbox tenant:** the human check is `SIMULATED` (accepts a fixed token), never blocking tests; the indexing switch is refused for a sandbox (a sandbox must not appear in a search engine).

## Scope

- **Already there (verified 2026-09-30):**
  - **A dark store says it delivers only, while browsing** (wave 1, flutter note item 2): `_DeliveryOnlyNotice` in `product_list_screen.dart`, and the cart already replaces Collect/Deliver with "Delivery only from this shop"; tests `test/features/storefront/order_cancel_and_browse_delivery_only_test.dart`, `dark_store_test.dart`. Catalogue gap closed.
  - **Failed shopper logins lock out** at the gateway: `BruteForceFilter` counts 401/403 on any path ending `/login`, 5 failures then `429 LOGIN_LOCKED` with `Retry-After` (`BruteForceFilterLockoutTest.failedLoginsLockTheAccountOut`); wave 1 marked the finding stale. Slice 2 reuses the filter's counter machinery for new keys.
  - **A tenant's age rule may be stricter than the statute, never laxer** (`PRODUCT_AGE_BELOW_STATUTORY`, `FoodSafetyIT`): catalogue case SHOP-10 is already true; only the case's own automation is missing and is listed under Acceptance.
  - **A storefront sign-in asks for `accountType: CUSTOMER` and tries only shopper logins first** (`UserTest.aStorefrontSignInTriesOnlyTheShoppersAccount`).
  - **Shoppers may cancel their own PENDING order** (order-svc done, and the shared-filter entry and gateway route were added by wave 1). The other shopper POSTs of the wave-2 pages need their own entries, each added to the same filter in one change (the list is in `target/flow-catalogue/_notes/WAVE2-BUILD.md`, "Shared openings").
- **In (slices in build order):**
  1. **A shopper-only storefront sign-in (iam-svc).** `accountType: CUSTOMER` never falls back to a staff login; `accountType: STAFF` never falls back to a shopper login; both answer `401 INVALID_CREDENTIALS` after the same Argon2 work as a wrong password. A request that names no `accountType` is unchanged and deprecated in step with the API-versions policy (dated in `GET /api/versions`).
  2. **Abuse counters at the gateway (gateway).** New counters keyed by client IP, address hash and tenant: registrations, password-reset requests, and declined or errored online payment attempts. Over a threshold: first a challenge is required (slice 3), then `429 TOO_MANY_ATTEMPTS` with `Retry-After`, exactly the way `LOGIN_LOCKED` works. A business may only make thresholds stricter than the platform's. **This slice builds the one gateway framework for such counters (`AbuseCounters`, on `RateCounter`, with the address hashing of `LockoutKeys`), and it has classes:** `REGISTRATION`, `RESET`, `PAYMENT_DECLINE` here, and `LOOKUP` (existence look-ups: sign-up and single sign-on start, 20 a minute per network) defined by [sign-in-protection](sign-in-protection.md) slice 6, which uses this framework rather than a second one. A route can sit in two classes (`/auth/register` is REGISTRATION for bot volume and LOOKUP for enumeration); the stricter answer wins, and the refusal says which class fired.
  3. **The human check (gateway, tenant-svc, app).** A `HumanCheck` driver interface with `SIMULATED` and `PROOF_OF_WORK`; vendor drivers are added one at a time later, each with a stub-backed test of its exact verify request. `GET /auth/human-check/challenge` issues a signed challenge; the protected endpoints (`POST /auth/register`, `POST /auth/password/forgot`, `POST /orders` from the storefront, `POST /payments/online`) accept `X-Human-Check: <solution>` and answer `428 HUMAN_CHECK_REQUIRED` when a challenge is due. Modes per business: `OFF` (only slice 2's escalation), `ADAPTIVE` (default: a challenge only when counters say so), `ALWAYS`.
  4. **The split shown before paying (order-svc, app).** `POST /orders/routing-previews` runs pure `Routing` over the same inputs as `OrderRouter` without holding stock, and answers the parts (store name, its lines). The cart's checkout shows it before the pay button.
  5. **Search-engine data (product-svc, gateway, tenant-svc, app).** Per-product page for crawlers with schema.org JSON-LD, `sitemap.xml`, a product feed, and a per-business `robots.txt`; all off until the owner switches on indexing. The JSON-LD carries `aggregateRating` only where reviews are on, counted above zero and shown on the page ([product-reviews-and-ratings](product-reviews-and-ratings.md) slice 5), and may later carry the food declaration ([food-information-online](food-information-online.md), whose `FoodInformationConfirmed` event the crawler feed reads).
- **Out, on purpose:**
  - **Choosing one captcha vendor.** Every vendor is an agreement with a third party that sees the shopper's traffic (a privacy and consent question per country). The platform ships a vendor-free proof-of-work; vendors are drivers a business or operator may add.
  - **Device fingerprinting, risk scoring services and blocking by country.** Location-neutral by rule; the counters are behaviour, not origin.
  - **Fraud rules in the payment provider (3-D Secure rules, AVS), and chargeback handling.** The card-payments page owns them; this page only stops the flood before it gets there.
  - **Velocity *alerts* to a human, per network address.** The gateway acts inline and keeps these as Prometheus series for the platform's operators (`gateway_abuse_blocked_total{class}`, `gateway_human_check_total{result}`); it does not raise a business's alert, because it holds no address (only a hash), no tenant rule store and no outbox. What a business can be told of is per shopper login (`checkouts.attempts`, `payments.declines` on [exception-alerts](exception-alerts.md)). The earlier `online.checkout-failures-per-source` and `auth.registrations-per-source` are not alert metrics.
  - **Splitting the order differently from the router.** The preview shows what the router would do now; a manager re-routes after placement on the [fulfilment-overrides](fulfilment-overrides.md) page.
  - **Reserving stock for the preview.** It reserves nothing; stock is held only when the order is placed, so the real split may differ, and the sheet says "may".
  - **Server-side rendering of the whole storefront.** Only the crawler page for a product is HTML; shoppers use the app.
  - **A staff person shopping as a customer with their staff login.** They make a shopper login (an address may hold both kinds; the reset and sign-in rules already handle that).

## Data and flow

- **Owned by the gateway:** counters in Redis (existing `RateCounter` / `RedisClientProducer`): no tables. Config keys `storeql.gateway.abuse.*` (thresholds and windows; the values are set when built and recorded under Decisions, like the brute-force filter's) and `storeql.gateway.human-check.driver`. Challenges are stateless: a signed token carrying difficulty, expiry and the source key (HMAC under a sealed gateway secret from `.env`); a solution is single-use (a short-lived seen-set in Redis).
- **Owned by tenant-svc:** `storefront_settings` (per business, read at the public `GET /storefront/config`): `human_check` OFF / ADAPTIVE / ALWAYS (default ADAPTIVE), `indexable` (default false), with who changed it. The gateway reads them through its cached `TenantAllowances`-style read and fails to the platform default when unreadable.
- **Owned by product-svc:** no new tables. `GET /storefront/products/{id}/page` (HTML with one JSON-LD `Product`, `Offer` (price, `priceCurrency`, availability from inventory advice, fail-open to omitted), `gtin` where held (GS1-checked), brand, image link), `GET /storefront/sitemap.xml`, `GET /storefront/feed` (JSON Lines, cursor, one line per sellable variant). All 404 unless the business's `indexable` is on; only active, sellable-online products at the storefront's stores.
- **Owned by order-svc:** no new tables; `POST /orders/routing-previews {storeId, fulfilmentType, items[]}` reads the same network stock and store coordinates as `OrderRouter` through the same clients, returns `{single, parts[{storeId, storeName, lines[]}], advisory: true, stockUnreadable}`. Warehouses never appear (shops only, as the router). An unreadable stock answer returns a single part at the area store with `stockUnreadable: true`, matching "an unreadable stock answer never refuses a sale".
- **Owned by iam-svc:** no new tables; `User.signInCandidates(accountType)` loses the fallback for `CUSTOMER` and `STAFF`.
- **Needs from other services:** the gateway → tenant-svc (settings) and iam-svc JWKS as today; product-svc → inventory-svc availability (fail-open) and tenant-svc (indexing, store list) through `TenantProfiles`; order-svc → inventory-svc network stock and tenant-svc stores as `OrderRouter` does. No joins.
- **Events published:** none new. (A blocked source is a gateway metric and a log line with the source hashed, not a business event.)
- **Retryable writes (Idempotency-Key):** the routing preview is a read (POST only for the body; no key); the human-check header is not an idempotency matter; checkout and payment keep theirs.
- **New error codes:** `428 HUMAN_CHECK_REQUIRED` and `403 HUMAN_CHECK_FAILED` (gateway; body says which driver and where to fetch the challenge); `429 TOO_MANY_ATTEMPTS` (gateway, with `Retry-After`); iam-svc keeps `401 INVALID_CREDENTIALS`; `404 STOREFRONT_NOT_INDEXABLE` (product-svc pages when off); `409 TENANT_SANDBOX_NOT_INDEXABLE`.

## Money, time and limits

- **Currency:** the JSON-LD `Offer` prices in the business's currency, never a display currency.
- **Ledger postings:** none.
- **Dates:** challenge expiry and counter windows in UTC seconds/minutes; `Retry-After` in seconds; sitemap `lastmod` in UTC (W3C date-time).
- **Plan limits:** none (protection is not a paid feature). The existing per-plan request rate (`requests.per-minute`) still applies to the preview and the pages.

## Constraints

- **Golden rules:** 2 (the gateway remains the only door: no service exposes a crawler page directly); 3 (the storefront tenant from the host, as today); 5 (secrets sealed, from `.env`, never the repo); 12 (metrics for blocks and challenges, tracing on the challenge); 15 (a solution or token that does not verify is `403`, never a stack).
- **Accessibility and privacy:** proof-of-work needs no image puzzle and no third party, so it is usable with a screen reader and sends nothing off the platform; a vendor driver must state what it shares before a business enables it.
- **Location-neutral:** no country's cookie or consent law is assumed for the proof-of-work (nothing is stored on the shopper's device beyond the token); the sitemap and feed use the business's own languages and currency; no marketplace's format is preferred over schema.org.
- **Existing tenants:** ADAPTIVE means shoppers see no change until a source misbehaves; indexing off means `robots.txt` stays `Disallow: /` exactly as now; apps that omit `accountType` keep working through the dated deprecation.
- **Callers a new refusal breaks:** any client or k6 suite that signs a staff address in with `accountType: CUSTOMER`, or a shopper address with `STAFF`, will now get `401`. The build starts by grepping `k6/`, the app and the platform console for it and fixing those callers in the same change; flow-guard suites are the proof.
- **Flow guards:** `flow-guard-comprehensive` and `flow-guard-runtime` must stay green (sign-in, carts, orders).

## Open questions

- [x] **Which captcha?** Recommended: none assumed; ship a vendor-free proof-of-work and a driver interface. → **driver + proof-of-work** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Always challenge or only when suspicious?** Recommended: adaptive by default, `ALWAYS` a business choice; card-testing escalation at the gateway regardless. → **adaptive** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Should a staff login sign in on the storefront?** Recommended: no; each surface accepts only its own kind of login, and a wrong kind looks like a wrong password. → **refuse** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Should the split be shown before paying?** Recommended: yes, advisory, from the same pure routing, reserving nothing. → **yes** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Should a storefront be indexable by default?** Recommended: no; a business opts in, and a sandbox never. → **off** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Where do crawler pages live?** Recommended: server-rendered per product in product-svc, routed by the gateway on the storefront's own host (which is how the tenant is already resolved). → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [x] An address holding only a staff login, asking with `accountType: CUSTOMER`, gets `401 INVALID_CREDENTIALS` and no token, after the same work as a wrong password (no timing or wording difference); a shopper login asking as `STAFF` likewise; a person with both kinds signs in as each surface asks. — iam-svc `UserTest.aStorefrontNeverFallsBackToAStaffLogin`, `aStaffSignInNeverFallsBackToAShopper`, `AuthIT.wrongKindLooksLikeWrongPassword`; k6 `flow-guard-comprehensive` "a storefront refuses a staff address" [built in iam-svc: `AuthIT.aSignInThatNamesItsKindNeverOpensTheOther`, `UserTest.aSignInThatNamesItsKindNeverOpensTheOther`, `BusinessSignUpIT.oneAccountSignsInOnlyAsItsKindOrUnnamed`; the refusal is the empty-candidate branch that burns Argon2 like an unknown address. The request that names both kinds of one address opens each as named.]
- [ ] A request with no `accountType` behaves as before and is listed as deprecated. — `AuthIT.noAccountTypeIsUnchanged`, gateway `ApiVersionsTest`
- [ ] Registrations, reset requests and declined online payments from one source over the threshold first require a challenge and then `429 TOO_MANY_ATTEMPTS`; a different source is unaffected; a business can be stricter, not laxer. — gateway `AbuseCountersTest`, `CardTestingEscalationTest`, `TenantMayOnlyTightenTest`
- [ ] A protected endpoint with a valid proof-of-work passes; missing is `428 HUMAN_CHECK_REQUIRED` only when due (ADAPTIVE) or always (ALWAYS); a wrong, expired or replayed solution is `403 HUMAN_CHECK_FAILED`; a solution for another source is refused. — gateway `HumanCheckFilterTest.*`, `ProofOfWorkTest`
- [ ] Each vendor driver, when added, builds its exact verify request against a stub; the sandbox uses `SIMULATED`. — `<Vendor>HumanCheckStubTest`, `SimulatedHumanCheckTest`
- [ ] The routing preview shows one part where one shop can fill and several where not, with shops only, never reserves stock (inventory holds unchanged), and says `stockUnreadable` rather than failing; another business's store id gives nothing. — order-svc `RoutingPreviewIT.oneOrManyParts`, `reservesNothing`, `unreadableStockIsAdvisory`, `otherBusinessFindsNothing`; the shopper POST needs the shared-filter entry (see Decisions)
- [ ] The cart shows the split before the pay button and labels it "may change". — widget test `cart_split_preview_test.dart`
- [ ] With indexing off, the pages, sitemap and feed are `404 STOREFRONT_NOT_INDEXABLE` and `robots.txt` is unchanged; on, a product page carries valid JSON-LD (Product, Offer, price and currency, availability, GTIN when held), the sitemap lists only sellable-online products at active stores, and another business's product id is 404. — product-svc `StructuredDataIT.offByDefault`, `jsonLdIsValid`, `onlySellableProducts`, `otherBusinessFindsNothing`; gateway `RobotsTest`
- [ ] A sandbox cannot be made indexable (`409 TENANT_SANDBOX_NOT_INDEXABLE`). — tenant-svc `StorefrontSettingsIT.aSandboxIsNeverIndexable`
- [ ] Case SHOP-10 (an owner cannot set an age rule below the statute) and SHOP-11 (availability for another business's store id returns nothing, with our storefront header) are automated. — product-svc `FoodSafetyIT.aTenantMayBeStricterNeverLaxer` (existing, cited by the catalogue), inventory-svc `AvailabilityIsolationIT.anotherBusinessesStoreIdReturnsNothing`
- [ ] The storefront shows the challenge invisibly (the work runs off the UI thread) and a `429` in words. — widget tests `human_check_test.dart`, `register_rate_limited_test.dart`
- [ ] End to end: k6 `storefront-trust-flow`; `flow-guard-comprehensive` and `flow-guard-runtime` green.

## Decisions

<!-- Filled while building. -->
- (Recorded now) **The shopper-facing POST routes need the shared filter.** `AdminAuthorizationFilter` (`shared/common-web`) default-denies a non-staff POST not on `isOpenMutation`. Wave 1 made the entry for `POST /orders/{uuid}/cancel`; `POST /orders/routing-previews`, the substitution answer ([shopper-notices](shopper-notices.md)), the return-request routes ([shopper-returns](shopper-returns.md)), the payment-intent routes ([card-payments](card-payments.md)) and the rest need the same entries and the gateway checked. One change for all, made by whoever builds first.
- (2026-09-30, reconciled) **This page owns the human check** (a proof-of-work `HumanCheck` driver, no third-party vendor). [sign-in-protection](sign-in-protection.md) put "CAPTCHA" in its Out list because it read a captcha as a vendor; that line now points here, and the lookup limits share this page's counter framework. **The gateway consuming Kafka and the gateway keeping Redis counters** are the two ways the gateway departs from being a stateless proxy in these pages; each is stated once, with its reason, in [platform-administration](platform-administration.md) (Kafka status feed) and [sign-in-protection](sign-in-protection.md) (`LockoutKeys`).
- (Recorded now) **Slice 1 replaces the wave-1 "DEFERRED" on staff fallback** (iam-notification note, item 3a), which was left as a product decision touching k6 and the platform console; settled here as above, with the caller audit as the first build step.

## Screens

- **Storefront:** an invisible check on register, reset request and pay (a small "Checking…" state only when the work takes noticeable time); a `429` message in words with the wait; the split summary above *Place order*; the delivery-only notice (built).
- **Admin, business settings:** a *Storefront* card: human check (Off / Adaptive / Always), *Let search engines find my shop* (with the sandbox refusal in words).
- **No screen for the platform thresholds:** config.

- **Decision (slice 1, built):** a named `accountType` opens only that kind (`User.ofKind`); an absent one is unchanged (`User.signInCandidates`: STAFF first, the other kind only when the address holds no staff login). "Listed as deprecated" for the absent form is not built (needs the gateway's `ApiVersions`).
