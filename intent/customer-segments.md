# Customer segments and households: saved rules a business can read, evaluated into a list

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Readiness Review row 14.3 "Customer segmentation" (missing); Oracle Customer Engagement "Segments and segment queries" (absent) and "Customer merge review and duplicate sets and households" (households) |
| **Services** | customer-svc owns segments, their rules, runs, membership, the facts they read, and households · order-svc, product-svc and tenant-svc feed facts by events and lookups, never a join · notification-svc (campaigns) and pricing-svc (offers) read a segment's members or "is this customer in it" from customer-svc · the app gives management a Segments screen |
| **Builds on** | `customers` and `customer_addresses`, `loyalty_accounts`/`loyalty_tiers`, `marketing_preferences`/`purpose_consents` and the `allowance` rule, `OrderConfirmed` (with `lines`, `storeId`, `channel`, `total`, `taxAmount`, `customerId`) already consumed by `OrderConfirmedHandler`, `OrderReturned`/`OrderVoided` already consumed by `ReturnEventsHandler`, product-svc's `ProductCategorised` and `VariantCreated` (already projected by pricing-svc `CatalogueEventHandler`), [customer-identity](customer-identity.md) (`CustomersMerged`, `duplicate_candidates`), [privacy-requests](privacy-requests.md) (`SubjectDataSpec`, `CustomerErased`), [consent-evidence](consent-evidence.md), `TenantProfiles`, `PhoneBackfillRunner` (the model of a one-off backfill), `LoyaltyExpirySweeper` (the model of a scheduled sweep), [exception-alerts](exception-alerts.md) and its `@SensitiveRead` mark from [report-integrity](report-integrity.md) |
| **Built in** | not yet built |

## Problem

A business knows a great deal about its customers and can act on none of it. "Everyone who spent more than X in the last quarter but has not been in for two months", "shoppers near our new store who bought dairy", "Gold members who agreed to email" are questions a manager asks weekly and answers today with an export and a spreadsheet, which is out of date at once and leaves no trace of who saw whom. Nothing can be aimed at a group, so every message and every offer goes to everyone or to nobody. And a household of three people sharing an address is three separate customers, so each gets the same mailing.

## Outcome

- **A segment is a saved rule written from plain building blocks** (spend, visits, last purchase, stores, categories, loyalty tier, consent, where they live) that a manager can read as a sentence. There is no score and no hidden model: every person in a segment is there because of a rule the business can quote back to them.
- **The list is worked out on a schedule the business sets and on demand**, and always says how many people are in it and **when it was computed**.
- **Ask "why is this person in it?"** and get the rule's parts, each with the person's own figure.
- **The facts come from where they live**, kept by customer-svc as a small projection fed by events; no service reads another's tables.
- **Consent is not a segment property.** A segment is who the business *knows*; whether they may be *contacted* is decided at every send (campaigns). A segment can say "has agreed to email" as a fact to aim at; it never permits a message.
- **Households** group customers who live together so a mailing can go to one person in the home, and a segment can count a home's spend once. Consent stays each person's own.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** and **marketing manager** (a MANAGER) build segments and read members; the **shopper** never sees a segment or its name.
- **Channels:** back-office only. A shopper is affected only through what a campaign or offer sends them.
- **Scope:** per business; a rule may narrow to stores (`storeIds`), and a caller held to stores may build and read only segments whose rule names only their stores, and sees members' facts only for those stores (below). Segments themselves are business assets: a store-held manager cannot change a business-wide segment (`403 BUSINESS_WIDE_ONLY`).
- **Roles that can write:** OWNER and MANAGER (business-wide for a business-wide segment; a store-held MANAGER for one confined to their stores). Reading members needs the same and is a **sensitive read** (below). CASHIER and STOREKEEPER: none.
- **Sandbox tenant:** behaves the same, on the sandbox's own customers.

## Already there

- Customers, addresses (`country`, `state`, `city`, `pincode`), the loyalty account with tier and points, marketing preferences and purposes, the `allowance` decision: all in customer-svc, all read directly.
- Order facts arrive as events customer-svc already consumes: `OrderConfirmed` (handler accrues loyalty only today), `OrderReturned` and `OrderVoided` (loyalty reversal). What is **not** kept is any per-customer history of orders, stores or categories: today only points are derived. This page adds that projection.
- The catalogue projection (variant to category path) exists in pricing-svc from `ProductCategorised`/`VariantCreated`; customer-svc needs its own copy (database-per-service).
- Duplicate flags and merges ([customer-identity](customer-identity.md)) are separate from households: two records of one person versus two people who live together.

## Scope

- **Who may do it, and where (every slice):** as under "Who and where". `requireStoreAccess` on every store named by a rule or asked for; a member list a caller may not fully see is filtered, never partly shown.
- **In (slices in build order):**
  1. **Facts (customer-svc).** A projection, per customer of the business, built only from events and the business's own records:
     - `customer_sales_facts`: per customer and **local day of the sale in the store's zone** (`TenantProfiles.Stores.zoneOf`; UTC only when unreadable): orders, spend (net of refunds, home currency), channel, store. Fed by `OrderConfirmed` once per `eventId` (spend = `total`), reduced by `OrderReturned` (the refund amount) and `OrderVoided` (the order's whole spend), idempotent per event.
     - `customer_store_facts`: per customer and store, orders, last order at. `customer_category_facts`: per customer and category id (every level of the product's path, so "Drinks" includes "Soft drinks"), spend, units, last bought at, from the order `lines` through the catalogue projection below; a return does **not** undo "bought" (a category fact says what they bought, not what they kept), and says so on the rule's help text.
     - `catalogue_variants` (customer-svc's own projection of `VariantCreated`, `ProductCategorised`: variant, category path) so a line's category is known without a call. A line whose variant is not yet projected counts in totals and in no category.
     - **Loyalty tier, points, addresses, consents, login, joined date and status** are read from customer-svc's own tables at evaluation time; nothing is copied.
     - **Backfill:** a one-off `SalesFactsBackfillRunner` (like `PhoneBackfillRunner`) reads history from order-svc through a service-to-service `GET /internal/customer-sales?after=&limit=` (cursor, ids and money only) and fills the projection once per order, so an existing business's segments are right on day one. It marks the business `facts_ready_at`; a segment computed before that says so on its size ("based on sales since <date>").
  2. **Segments and the rule (customer-svc, app).** A segment has a name, a description and a **rule**: groups of conditions joined by ALL or ANY, nested at most three deep (a technical bound). Conditions, each a typed building block with its own words:
     - **Spend** in the last N days is at least, at most or between (home currency); **Orders** in the last N days (count) is at least, at most, exactly; **Days since last order** is at least or at most, or **has never ordered**; **Joined** within the last N days or before a date.
     - **Bought from** any of these categories (in the last N days, or ever); **Bought** any of these variants; **Shopped at** any of these stores (in the last N days), or **Mostly shops at** a store (the store with most orders in the period); **Channel** ONLINE or POS or both used.
     - **Loyalty tier** is one of; **Points balance** at least or at most; **Has points expiring within** N days.
     - **Consent:** has agreed to a channel (a fact about the record, computed by the same pure function `allowance` uses, so "agreed to email" here and at send agree); **has a sign-in** or not.
     - **Lives in:** the customer's own **default address** (or any address) by country, state, city, or postcode starting with, compared as text after case and spacing are removed; no geocoding, no distance, no country assumed. ("Near a store" is by the postcode or city the business names, not by coordinates: customer addresses carry none, and inventing them would need a geocoder and a consent this platform does not have.)
     - **In household with** a member of another segment is out (below); **Not in** and **is in** another segment: allowed one level (refers by id), a cycle is refused `400 SEGMENT_CYCLE`.
     - Numbers in a rule are the business's own; nothing here supplies a default period or threshold.
     - **Two kinds:** `RULE` (above) and `LIST` (a fixed set of customers a manager picks, for "these fourteen people": add and remove by id, each a logged event). A LIST is how "a named customer" gets an offer or a message.
     - Pure `SegmentRule` (parse, validate, evaluate against one customer's facts, explain) and pure `SegmentWords` (renders the rule as a sentence, in the caller's language, from the platform's own vocabulary). Both have exhaustive unit tests. A rule with no conditions, an unknown building block or a period below 1 day is `400 SEGMENT_RULE_INVALID` with the path to the fault. **Always excluded, whatever the rule:** anonymised, merged and (for any rule that would send) customers with no record of an adult or guardian where a child rule binds (DPDP, as [consent-evidence](consent-evidence.md) does).
     - Endpoints (all management): `POST /admin/segments`, `GET /admin/segments` (with size and computed-at), `GET /admin/segments/{id}`, `PUT /admin/segments/{id}` (changes bump `rule_version`; the previous rule is kept in `segment_rule_history`), `POST /admin/segments/{id}/archive`, `POST /admin/segments/preview` (a rule not yet saved: **count only**, never names), `GET /admin/segments/{id}/explain?customerId=` (the rule's parts with that person's figures; also for a person not in it, "not because…"). A segment in use by a campaign or an offer cannot be archived (`409 SEGMENT_IN_USE`, naming which) and cannot have its rule changed without the same reset campaigns and offers apply (below).
  3. **Runs, membership and schedule (customer-svc).** `POST /admin/segments/{id}/refresh` (manual, `Idempotency-Key`) and a schedule: `refresh_every_hours` (whole number of at least 1, **null until the business sets it: manual only**). A sweep (technical interval, config) starts due runs. A run evaluates the rule over the business's customers **in bounded batches** (a technical bound) with the tenant filter first and the `(tenant_id, …)` indexes, writes `segment_members` as a **replace of the set** on one transaction at the end (so readers see the old list or the new, never half), and one append-only `segment_runs` row: rule version, started, finished, size, added, removed, `triggered_by` (SCHEDULE, MANUAL, CAMPAIGN, OFFER), `facts_ready_at` at the time. A run that fails leaves the previous membership and is recorded FAILED with the reason. Only one run per segment at once (`409 SEGMENT_RUN_IN_PROGRESS`). `GET /admin/segments/{id}/runs`. Size and `computedAt` are on every read.
  4. **Reading members, and "which segments is this person in" (customer-svc).**
     - `GET /admin/segments/{id}/members?after=&limit=` (cursor; default 20, max 100): customer id, name, and the facts the rule used; **a sensitive read** (report-integrity's `@SensitiveRead("segment-members")`, a key this page adds to that page's fixed catalogue: one access-log entry per first page with who, which segment and the `reason` query field, which this route requires; `400 SEGMENT_READ_REASON_REQUIRED`) and the `segment_members.reads` alert metric. A store-held caller sees only members with sales at their stores or none at any store, and never a customer whose only facts are elsewhere. There is **no bulk file export** in this cut: a business that needs its list in another system uses a campaign or a webhook, not a download of names.
     - `GET /internal/segments/{id}/members?after=&limit=` (service-to-service only, never routed by the gateway; ids and preferred language only; the caller is notification-svc, and it reads the members of a segment **it names in a campaign of the same business**), and `GET /internal/customers/{id}/segments` (the ids of segments the customer is in, for pricing-svc; the tenant is the caller's stamped context; nothing about the rules).
     - `GET /admin/customers/{id}/segments` (management): the customer's segments by name with the reasons, on the customer screen.
  5. **Reach, before anything is sent (customer-svc).** `GET /admin/segments/{id}/reach` answers, for the last computation, how many members are currently **contactable** by each marketing channel (EMAIL, SMS) and how many by neither, using the same pure allowance function as the send gate, plus the number with no address on record. It is a count, never a list, and is what the campaign screen shows before anyone presses Send. (The actual send asks `allowance` again per person; this is a preview.)
  6. **Households (customer-svc, app).**
     - A household is a name and a set of customers of one business who live together: `households`, `household_members` (a customer is in **at most one**). Managers create one, add and remove members by id; each change is an append-only `household_events` row (who, when, why).
     - **Suggested, never automatic:** a daily pass suggests "may live together" pairs: same postal code and same first line of address, different people (a different last name or a different date of birth), not already flagged as duplicates ([customer-identity](customer-identity.md): same name, or the phone rule, are duplicates, not households). Suggestions are `household_suggestions` a manager accepts or dismisses; a dismissed one does not return. The reason is shown in words; no score.
     - **What a household does:** (a) a campaign may be set to **one message per household** (the household's most recently active member with an allowed channel, else the first who is allowed; consent is still each person's: a household never lets a message reach someone who has not agreed, and if nobody in it has, nobody is sent); (b) a rule may use **household spend** (`Spend of the household in the last N days`) and **household size**. Points, store credit, gift cards, consents and orders stay individual; nothing is pooled.
     - A merge folds the record out of its household; erasure removes the member and, when a household has one member left, keeps it (a one-person household is valid). `CustomersMerged` and `CustomerErased` handled here.
  7. **Screens (app).**
- **Out, on purpose:**
  - **Opaque scoring, propensity, lookalike, churn models and "AI" segments.** A rule the business can read and explain to a customer is the requirement; a model that cannot be explained is not built. (Oracle's "segmentation science" is a different product.)
  - **Real-time membership.** A segment is as fresh as its last computation, shown on screen. Offers and campaigns accept that and say so; nothing pretends a member joined this minute.
  - **Geo distance and drive-time.** Customer addresses have no coordinates and geocoding needs a provider and a lawful basis; postal code, city, state and country are what a customer wrote.
  - **Conditions on things customer-svc does not hold** (product margins, stock, weather, browsing behaviour). Browsing and cart events are not collected for segments; the cart reminder is its own feature.
  - **Downloading a segment as a file.** Members are read by cursor with a reason and a trail, not exported wholesale; see slice 4.
  - **Pooled household loyalty or shared consent.** A household is a mailing and reporting convenience, not an account.
  - **Segments across businesses.** Never.

## Data and flow

- **Owned by customer-svc:**
  - `segments`: id, `tenant_id`, `name` (unique per business among live), `description`, `kind` (RULE, LIST), `rule` (JSON), `rule_version`, `store_ids` (the stores the rule names, kept beside the JSON for the store check), `refresh_every_hours` (nullable), `status` (ACTIVE, ARCHIVED), `size`, `computed_at`, `created_by`, `updated_by`, timestamps. `segment_rule_history` (append-only: rule, version, who, when).
  - `segment_members`: `tenant_id`, `segment_id`, `customer_id`, `since_run` (unique on tenant, segment, customer; index `(tenant_id, customer_id)` for "which segments"). Replaced as a set per run; not a ledger. LIST members are `segment_list_events` (append-only add or remove with who and why).
  - `segment_runs` (append-only): as above.
  - `customer_sales_facts`, `customer_store_facts`, `customer_category_facts`, `catalogue_variants`, `processed_events` use (the existing table) for idempotency, `facts_ready_at` on the business's customer settings row.
  - `households`, `household_members`, `household_events` (append-only), `household_suggestions`.
  - **SubjectDataSpec:** the fact tables and members: ERASE on `CustomerErased`; `household_events` and `segment_list_events` ANONYMISE the customer id (the who-did-it stays); `segments.rule` holds no customer data.
- **Endpoints:** as in the slices. All `/admin/segments/**` are management (path gate) with the store rules above; `/internal/**` are never routed by the gateway.
- **Needs from other services:** order-svc `GET /internal/customer-sales` (backfill only); product-svc's two events; tenant-svc `TenantProfiles` for store zones and the store list; `Jurisdictions` for the child rule.
- **Events published:** `SegmentComputed` (`storeql.customer.segment-computed`: `eventId`, `tenantId`, `segmentId`, `runId`, `size`, `computedAt`; ids and numbers only) so the app can refresh and campaigns wait for a fresh run; webhook catalogue as well. No event carries members. Consumers of the inputs: `CustomerErased`, `CustomersMerged` (fold facts: the survivor's facts add the folded record's; a household member repoints), `OrderConfirmed`, `OrderReturned`, `OrderVoided`, `VariantCreated`, `ProductCategorised`; each idempotent on `eventId` through `processed_events`.
- **Retryable writes (Idempotency-Key):** create, refresh, add to a LIST, accept a household suggestion.
- **New error codes:** `404 SEGMENT_NOT_FOUND` (also another business's), `400 SEGMENT_RULE_INVALID` (with the path), `400 SEGMENT_CYCLE`, `409 SEGMENT_IN_USE`, `409 SEGMENT_RUN_IN_PROGRESS`, `409 SEGMENT_NAME_TAKEN`, `400 SEGMENT_REFRESH_INVALID` (a non-positive interval), `404 HOUSEHOLD_NOT_FOUND`, `409 HOUSEHOLD_MEMBER_TAKEN` (already in one), `403 STORE_ACCESS_DENIED`, `403 BUSINESS_WIDE_ONLY`, `400 SEGMENT_READ_REASON_REQUIRED`, `400 IDEMPOTENCY_KEY_REQUIRED`.
- **Plan limit:** `segments.max` (the number of live segments; a new entitlement key added to `Plans.CATALOGUE` and enforced in customer-svc by `Entitlements`, fails open: no plan or unreadable = unrestricted). Adding the key adds the refusal `409 PLAN_LIMIT_REACHED` on create.

## Money, time and limits

- **Currency:** spend is in the business's home currency (orders are always charged in it; a price is only ever *shown* in another). A segment's amounts are home currency, stored with it.
- **Ledger postings:** none.
- **Dates:** the sale's day is the **store's local day** (for "in the last N days" to mean what a shopkeeper means); a period is N such days ending today in the **business's home zone**; every stored instant UTC.
- **Plan limits:** `segments.max` above.

## Constraints

- Golden rules 1 (facts by event, categories by projection, backfill by REST, never a join), 3 (every query `tenant_id` first, including the batch evaluation and the members read), 6 and 7, 8 (run history, list and household events, rule history append-only), 9, 10, 15.
- **Cost:** a run is bounded batches on indexed tables with a technical limit per interval; a business with a very large customer base is refreshed at the business's own interval, never on the request path. `preview` counts with the same bounds and a technical timeout (`503 SEGMENT_PREVIEW_BUSY`, retry).
- **Privacy:** the members read is a sensitive read with a trail; facts are erased with the customer; the projection keeps ids and numbers, no names or addresses; a segment never names a person in an event or a log line.
- **Location-neutral:** no country, currency, postcode format, language or holiday assumed; addresses compared as text; days in the store's zone; the words of a rule in the caller's language with the platform's fallback.
- **Consent unchanged:** membership never widens who may be contacted; the `MARKETING` purpose, per-channel consent, double opt-in and lapse ([consent-evidence](consent-evidence.md)) are applied at send, not here.
- Existing tenants: nothing changes until a manager creates a segment; the projection fills by the backfill.

## Open questions

- [x] **What can a rule say?** Recommended: the building blocks above, AND/OR, three deep, no free expression language, no scoring. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **How fresh is a segment?** Recommended: computed on demand and on the business's own interval (off until set); the time is always shown; a campaign refreshes just before it sends. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **May a manager download the members?** Recommended: no bulk file; cursor reads with a reason and a trail; a campaign is the way to act on them. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Are households automatic?** Recommended: suggested by a readable rule, decided by a person, individual consent unchanged. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] **Where does "location" come from?** Recommended: the customer's own address fields as text; no coordinates. → **accepted as recommended** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] `SegmentRule` evaluates each building block correctly at its edges (a sale on the last day of the period, a return reducing spend, a never-ordered customer, category paths including children); `SegmentWords` renders every block in words. — `SegmentRuleTest.*`, `SegmentWordsTest.*`
- [ ] A sale, a return and a void change the facts once per event; a redelivered event changes nothing; a line whose variant is not yet known counts in totals and no category; a return does not undo a category. — `SalesFactsIT.oncePerEvent`, `returnAndVoidReduceSpend`, `unknownVariantCountsInTotalsOnly`, `categoryIsWhatWasBought`
- [ ] The backfill fills an existing business's facts once per order and marks `facts_ready_at`; a segment computed before says "based on sales since". — `SalesFactsBackfillIT.*`
- [ ] The day of a sale is the store's local day: a late-evening sale in one zone and an early-morning one in another land on their own local days. — `SalesFactsIT.storeZoneDecidesTheDay`
- [ ] A run replaces the membership atomically, records size, added, removed and the rule version; a failed run leaves the old list; a second run at once is `409 SEGMENT_RUN_IN_PROGRESS`; the interval is off until set. — `SegmentRunIT.replacesAtomically`, `failedRunKeepsTheList`, `oneAtATime`, `offUntilSet`
- [ ] Anonymised and merged customers are never members; a child without a guardian record is excluded from a sending rule where the register binds. — `SegmentRunIT.neverErasedOrMerged`, `childRuleExclusion`
- [ ] `explain` gives each part with the person's figure, for a member and for a non-member. — `SegmentExplainIT.*`
- [ ] Preview returns a count and never names; a store-held manager previews and builds only over their stores; a business-wide segment is `403 BUSINESS_WIDE_ONLY` for them. — `SegmentAccessIT.previewIsACount`, `storeHeldManager`, `businessWideNeedsBusinessWide`
- [ ] Reading members needs a reason, is recorded, and is filtered for a store-held caller; a cashier and a storekeeper are `403`; there is no export route. — `SegmentMembersIT.sensitiveRead`, `storeHeldFilter`, `onlyManagement`
- [ ] Reach counts contactable members per channel with the same function as the send gate: a withdrawn `MARKETING` purpose, a PENDING confirmation and a lapsed channel are not counted. — `SegmentReachIT.matchesTheGate`
- [ ] A segment in use cannot be archived or have its rule changed without the dependants being reset; a cycle is `400 SEGMENT_CYCLE`. — `SegmentIT.inUse`, `cycleRefused`
- [ ] `segments.max` refuses the next segment with `409 PLAN_LIMIT_REACHED`, and fails open when the plan cannot be read. — `SegmentPlanLimitIT.*`
- [ ] A household suggestion follows the rule (same postcode and first line, different people, not a duplicate pair), is accepted or dismissed by a person, a dismissed one does not return; a customer is in at most one household; one message per household never reaches a member who has not agreed. — `HouseholdIT.suggestionRule`, `atMostOne`, `dismissedStays`; campaign test in [campaigns](campaigns.md)
- [ ] A merge folds facts and household membership into the survivor once; erasure removes the customer from facts, members and households. — `SegmentMergeIT.factsFollowTheSurvivor`, `SegmentErasureIT.*`
- [ ] Another business's staff of every role and a shopper get 404 or 403 for every segment route and never a member; `/internal/**` answers only for the caller's stamped tenant. — `SegmentIsolationIT.*`
- [ ] Every rule change, run, list edit and household change is recorded with who and when and only appended to. — `SegmentAuditIT.*`; ArchUnit `APPEND_ONLY`
- [ ] k6 `segment-flow`: sales, a rule, a run, members, reach; `flow-guard-*` green.
- [ ] Widget tests: `segments_screen_test.dart`, `segment_rule_builder_test.dart`, `households_screen_test.dart`.

## Screens

- **Admin shell, Marketing, Segments:** a list (name, the rule as a sentence, size, "computed 2 hours ago", refresh interval, used by campaigns and offers). A **rule builder** built from the block list (add a condition, choose its parts from menus and typed fields, group with ALL or ANY), showing the sentence as it forms and a live count ("about 1,240 customers"). A segment view: members (with the reason prompt), the run history, "Why is this person here?", **Reach** per channel, and Refresh.
- **Admin, Customers:** the customer's segments with reasons; a household chip; Households tab (suggestions with the reason, Accept and Dismiss; a household's members).
- **Admin, Settings, Marketing:** nothing here (settings are the campaigns page's).

## Authority and abuse

- **Approvals:** none. A segment moves no money, stock or access.
- **Alert metric (row to add on [exception-alerts](exception-alerts.md), owner customer-svc):** `segment_members.reads` (COUNT of member-list reads, subject staff): one person reading many segments' members is the scraping pattern the reason and trail are meant to expose.
- **Plan key to add:** `segments.max`.

## Flow Tests entry

Area `customer`, file `target/flow-catalogue/customer/mkt-customer-segments.json` (new flow, id prefix `SEG`, includes the household cases).

| Type | Case | Test |
|---|---|---|
| happy | Build a rule from spend, category and store, refresh, read size and computed-at, explain a member | k6 `segment-flow`; `SegmentRunIT`, `SegmentExplainIT` |
| happy | A sale then a return moves period spend once; the backfill fills history | `SalesFactsIT.*`, `SalesFactsBackfillIT` |
| happy | Accept a household suggestion; one-per-household counted once in a rule | `HouseholdIT.*` |
| negative | Invalid rule with its path; cycle; empty rule; name taken; interval 0 | `SegmentRuleTest.*`, `SegmentIT.*` |
| negative | Cashier or storekeeper on any route; store-held manager on a business-wide segment; members read with no reason | `SegmentAccessIT.*`, `SegmentMembersIT.*` |
| override | A manager archives an in-use segment after detaching its campaign; a rule change resets its dependants | `SegmentIT.inUse` |
| isolation | Another business's staff of every role and a shopper; `/internal` for another tenant | `SegmentIsolationIT.*` |
| edge | Store-zone day boundary; a variant not yet projected; a merge mid-run; a failed run keeps the list; two runs at once | `SalesFactsIT.*`, `SegmentRunIT.*`, `SegmentMergeIT` |
| audit | Rule history, run history, member reads with reasons, list and household events | `SegmentAuditIT.*`, `SegmentMembersIT.sensitiveRead` |

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **Owner of "who is in this group"** for [campaigns](campaigns.md) and [personalised-offers](personalised-offers.md); their reads are `/internal/segments/{id}/members` and `/internal/customers/{id}/segments`. The members read is the sensitive-read key `segment-members` with a required reason.

- **Rules, not scores** (2026-09-30, industry standard): a segment is a sentence the business can say to the customer it names; no model is built.
- **Segment is who we know, consent is who we may contact** (2026-09-30): membership is never a permission; every send asks `allowance`.
- **Facts by event and by projection** (2026-09-30): customer-svc keeps its own sales, store and category facts from events (plus a one-off backfill), so no service reads another's tables.
- **No file export of members** (2026-09-30): a read with a reason and a trail, or an action (campaign, offer), is the only way out.
