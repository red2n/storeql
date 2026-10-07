# Basket analysis: what sells together, worked out overnight, in words a shop owner reads

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Readiness Review row 19.9 "Basket analysis and affinity"; Oracle audit (Analytics, "Basket analysis and affinity", AI Foundation; the Review's 18.8) |
| **Services** | reporting-svc owns the settings, the runs and their results and computes them · product-svc and inventory-svc own the relationships and the shelf the suggestions are read beside (they store nothing new) · order-svc publishes the lines it already publishes · the app shows the report and the suggestions |
| **Builds on** | reporting-svc `sales_line_facts` (one row per order line: order, line number, variant, store, channel, quantity, line total, `confirmed_at`), `sales_facts.voided_at`, `catalogue_variants` and `catalogue_products.category_path` (from `VariantCreated` and `ProductCategorised`), `/admin/reports/sales/by-category` (the report's shape), `TenantContext.reportStores`, `TenantProfiles.Stores.zoneOf`, [report-integrity](report-integrity.md)'s `basis` block and store's-day rule, product-svc `SUBSTITUTE` and `COMPLEMENTARY` variant relationships, inventory-svc's shelf-space screen; the job registry of [background-work-and-stuck-items](background-work-and-stuck-items.md) |
| **Built in** | not yet built |

## Problem

A grocer knows what sold. It cannot see what sold **together**: whether the customer who buys pasta also buys sauce, which item is a reason to visit and which is only added, what two products are rivals for the same basket. Range, shelf placement and "you may also need" decisions are made by memory. The platform already stores every order line for the by-category report, so the raw material is there, but it holds no question about baskets.

## Outcome

- **For each store and for the business, an owner reads what sells together:** pairs and small sets of three, how often, and in three plain measures: how many baskets had both, out of every hundred baskets with A how many also had B, and how much more often than chance that is.
- **It is worked out on a schedule, once a store's day is over**, never while someone waits and never on the till's or the storefront's path.
- **It says what it counted and what it left out**, and shows nothing a single shopper's basket could reveal.
- **It feeds decisions as a read only:** "often bought with" beside a product, "rivals" (same shelf category, rarely bought together) as substitute candidates, and "put near" beside the shelf. A person accepts a suggestion by creating the relationship in the catalogue; the platform never applies one.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** and **category or store manager** (read and act), the **buyer** (uses the suggestions).
- **Channels:** back-office Reports screen; a panel on the product screen; a hint on the shelf-space screen.
- **Scope:** per store, or the business as a whole; ONLINE and POS baskets together (a channel filter is available on read).
- **Roles that can write:** the settings and the exclusion list need OWNER or a business-wide MANAGER (`BusinessWide.require`: they shape what the whole business is shown). Reads: OWNER, MANAGER. A store-held manager reads the runs of their own stores only (`TenantContext.reportStores`): naming a store outside theirs is `403 STORE_ACCESS_DENIED`, naming none lists their stores each on its own. The business-wide run needs a caller held to none, because working it out on demand for "their stores added together" would be live computing, which this page forbids.
- **Sandbox tenant:** behaves the same, over its own sales.

## Scope

- **In**, in build order:
  1. **The analysis for one store (reporting-svc).** Pure `Affinity` (sets of one to three products from a list of baskets: count, support, confidence, lift), the settings row, the scheduled job, the run and result tables, and the reads. Testable with a fixture of baskets and no other service.
  2. **Business-wide, categories, exclusions, export.** The all-stores run (recomputed from the baskets, never by adding store results, since lift does not add), the category level, the owner's exclusion list (carrier bags, deposits, gift-card lines), CSV on every result read.
  3. **Suggestions (reporting-svc, read by product-svc and inventory-svc screens through the app).** `COMPLEMENTARY`, `SUBSTITUTE` and `ADJACENT` candidates, each marked "already related" where product-svc already has the relationship.
  4. **Screens (app).**
- **Out, on purpose:**
  - **Live or on-demand computing.** The owner cannot press "analyse now". A run is a few queries over every basket in the window; on the till's and the storefront's database that would be a cost paid at the worst moment. An operator can run the job ad hoc from the console ([background-work-and-stuck-items](background-work-and-stuck-items.md)).
  - **Personal affinity and customer segments ("customers like you").** The sales facts carry no customer, on purpose, and segmentation is the Review's row 13.2. A basket here is anonymous.
  - **Price elasticity, demand transference, forecasting.** Different science; the Oracle audit lists them apart.
  - **Applying a suggestion.** A relationship, a substitute for a shopper's short line and a shelf move each change what a customer sees; a person makes them.
  - **A recommender on the storefront.** Nothing here reaches the shopper; "you may also need" is its own storefront feature and would read these results through an API when someone asks for it.
  - **Removing returned lines from a basket.** The facts hold a refund per order, not per line, and a basket was still chosen together even when one item came back. Voided sales are excluded (they never happened). The report's `basis` says so.

## Data and flow

- **What the facts already hold, and the one limit (verified 2026-09-30).** Every line of every confirmed order is a `sales_line_facts` row with its order, variant, store, channel and confirmation time; `sales_facts` says which orders were voided; `catalogue_variants` and `catalogue_products` say which product and category a variant belongs to. That is everything a basket needs. **No event field is missing.** Two things are absent and stated on screen: which lines were later short-closed or substituted (the shopper chose the basket as ordered, which is what affinity should measure), and which lines were later returned (above). A line whose variant the catalogue projection does not know is left out and counted in `unknownLines`, so a gap in the projection is visible rather than silent.
- **Owned by reporting-svc** (tenant_id first in every index; ids v7):
  - `basket_settings` (one per business): `enabled` (default false), `window_days` (how many full store days back; no default, required to enable), `level` (PRODUCT or CATEGORY, default PRODUCT: sizes of one product do not pair with each other), `min_baskets` (the fewest baskets a set must appear in to be listed; may only raise the platform's privacy floor), `updated_by`, `updated_at`.
  - `basket_exclusions`: `product_id` (from the catalogue), `added_by`, `added_at`.
  - `basket_settings_history` (append-only): every change to the settings or the exclusion list, with who, when and the previous and new values; the audit feed reporting-svc serves for [platform-administration](platform-administration.md)'s merged view reads it.
  - `basket_runs` (append-only): `id`, `tenant_id`, `store_id` (null = the business as a whole), `level`, `window_from`, `window_to` (store days), `zone` (the zone the days were judged in), `version` (a re-run of the same window adds a higher one; readers take the latest), `baskets` (counted), `unknown_lines`, `truncated` (true when the most recent baskets alone were used because the window exceeded the technical bound), `started_at`, `finished_at`, `basis` (text, what was counted and left out).
  - `basket_sets` (append-only): `run_id`, `size` (1 to 3), `items` (sorted array of product or category ids), `baskets_with`, `support` NUMERIC(9,6).
  - `basket_rules` (append-only): `run_id`, `antecedent` (sorted ids), `consequent` (one id), `baskets_with_both`, `confidence`, `lift` NUMERIC(12,6), `support`. Only sets and rules that clear the floor and the business's `min_baskets` are stored.
- **The three measures and their words** (the page fixes the wording; `AppFormat` percentages): *support* "in 12 of every 100 baskets"; *confidence* "34 of every 100 baskets with sauce also had pasta"; *lift* "2.8 times as often as chance" (a lift of 1 is chance, below 1 is less often than chance). Ties are broken by lift, then baskets, then id, so a re-run on the same facts gives the same order and numbers.
- **The schedule.** A job `basket-analysis` in the shared background-work registry ticks hourly and runs a store's analysis once per store day, after that store's day (in its own zone, `TenantProfiles.Stores.zoneOf`, UTC and a note in `basis` where unreadable) has ended and only for a business with `enabled`. The business-wide run follows once every store has its run for the day. Idempotent per `(store, window, level)`: a repeat adds a version, not a duplicate. Bounded by two technical keys, `storeql.reporting.basket.max-baskets` and `.max-sets`, and a privacy floor `storeql.reporting.basket.privacy-floor` (a set seen fewer times is never stored, so no result can point at one shopper's purchase). All three are deployment values, not business policy.
- **Suggestions (read, slice 3).** From the latest run: `COMPLEMENTARY` = a rule with lift above 1 (more often than chance, arithmetic and not policy), ranked by lift then baskets; `SUBSTITUTE` = two products whose leading category is the same, each sold in at least `min_baskets` baskets, and whose lift is below 1, ranked by how rarely they meet ("rivals for the same basket"); `ADJACENT` = the complementary pairs, shown to the shelf screen as "often bought with". Each answer carries the numbers, the run's date and `related: true` where product-svc already has a relationship (the app reads that from product-svc, the analysis stores nothing of it).
- **Endpoints (reporting-svc, `/admin/reports/basket`):**

| Method and path | Roles | Notes |
|---|---|---|
| `GET /settings`, `PUT /settings` | OWNER, MANAGER read; OWNER, business-wide MANAGER write | `400 REPORTING_BASKET_WINDOW_INVALID` (below one day or above the technical bound), `400 REPORTING_BASKET_LEVEL_INVALID`, `400 REPORTING_BASKET_MIN_BELOW_FLOOR` |
| `GET /exclusions`, `PUT /exclusions` `{productIds[]}` | same | `422 REPORTING_BASKET_EXCLUSION_UNKNOWN` (a product the catalogue projection does not know) |
| `GET /runs?storeId=` | OWNER, MANAGER | the latest run per store and the business, with `basis`; `enabled: false` and an empty list when off |
| `GET /rules?storeId=&channel=&productId=&sort=&after=&limit=` | same | cursor, default 20 / max 100; `Accept: text/csv` supported |
| `GET /sets?storeId=&size=&after=&limit=` | same | same |
| `GET /suggestions?kind=&productId=&storeId=` | same | `kind` COMPLEMENTARY, SUBSTITUTE or ADJACENT |

  Every read answers the run's `windowFrom`, `windowTo`, `zone`, `baskets`, `basis`, `finishedAt`. No write is a retry hazard: the two `PUT`s replace by key and a repeat is the same state (`Idempotency-Key` is accepted and ignored).
- **Needs from other services:** events already consumed (no new event); variant labels are resolved by the app from product-svc, never stored here.
- **Events published:** none. **New error codes:** the four above.

## Money, time and limits

- **Currency:** none; the analysis counts baskets and lines, never amounts, so no currency is assumed.
- **Ledger postings:** none.
- **Dates:** windows are whole days in each store's own zone (the rule of [report-integrity](report-integrity.md) slice 2); instants stored in UTC; the business-wide run judges each store's baskets in that store's zone.
- **Plan limits:** none. Compute is bounded by the technical keys above, not by the plan.

## Constraints

- **Golden rules:** database-per-service (reads only reporting-svc's own projections); tenant first in every query; append-only for runs, sets and rules; thin resources; the schedule goes through the shared job registry so an operator sees it ([background-work-and-stuck-items](background-work-and-stuck-items.md)).
- **Existing tenants:** nothing runs until an owner enables it; existing reports are untouched.
- **Location-neutral:** no country, currency, language or week start assumed; product names come from product-svc in the business's own language.
- **Privacy:** results carry no customer and no basket id; the privacy floor prevents a small set identifying a shopper; the floor is enforced when storing, so no reader can lower it.
- **Backups:** plain tables; nothing for `scripts/backup-drill.sh`.

## Open questions

- [x] Which measures? Recommended: support, confidence, lift, in words. → **All three, in the words given** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Pairs only, or larger? Recommended: pairs and triples; larger sets explode in cost and the owner cannot act on them. → **Sets of one to three** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Live or scheduled? Recommended: scheduled, after the store's day. → **Once per store per day, in its own zone** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What is the smallest count worth showing? Recommended: a privacy floor as a deployment value, which the business may only raise; the platform states no business number. → **As stated** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should the platform apply a suggestion? Recommended: no. → **A person creates the relationship** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Do returns take a line out of a basket? Recommended: not at line level (the facts do not hold it); voids are out. → **As stated, and said in `basis`** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] `Affinity` gives the textbook counts, support, confidence and lift for a fixture of baskets, breaks ties deterministically, and leaves a set under the floor out — `AffinityTest`
- [ ] With the setting off nothing runs; enabling without a window is `400 REPORTING_BASKET_WINDOW_INVALID`; a `min_baskets` below the floor is `400 REPORTING_BASKET_MIN_BELOW_FLOOR` — `BasketSettingsIT`
- [ ] The job runs a store once after its own day ends (a store east and a store west of UTC finish at different instants), a second tick the same day adds nothing, and a re-run of the same window adds a version — `BasketRunIT.oncePerStoreDayInItsOwnZone`, `rerunAddsAVersion`
- [ ] Voided sales are out; lines of unknown variants are counted in `unknownLines`; excluded products never appear; the online and POS baskets both count and a channel filter narrows the read — `BasketRunIT.whatIsCounted`
- [ ] The business-wide result is recomputed from the baskets (its lift differs from any average of the store lifts in the fixture) — `BasketRunIT.businessWideIsNotASum`
- [ ] A window over the technical bound uses the latest baskets and says `truncated` — `BasketRunIT.truncationIsSaid`
- [ ] No result row exists for a set seen fewer times than the privacy floor, whatever `min_baskets` says — `BasketRunIT.privacyFloor`
- [ ] The read is never live: a request during a run answers the previous run, and no read runs the analysis — `BasketReadIT.neverLive`
- [ ] Suggestions: complementary above lift 1, rivals in one category below lift 1, and `related` where product-svc already relates them — `BasketSuggestionIT`; the app marks `related` from product-svc — `basket_suggestions_test.dart`
- [ ] A store-held manager reads their stores only; naming another is `403 STORE_ACCESS_DENIED`; the business-wide run is refused to them — `BasketIsolationIT.storeHeld`
- [ ] Another business's owner, manager and every other role, and a shopper, get nothing of ours and cannot set ours — `BasketIsolationIT.otherBusiness`
- [ ] Settings and exclusions need OWNER or a business-wide manager; a store-held manager and a cashier are `403` — `BasketIsolationIT.writeRoles`
- [ ] CSV export of rules and sets matches the JSON — `BasketReadIT.csv`
- [ ] Widget: the Reports screen shows the sentences with the run's date and `basis`, and the empty state when off — `basket_report_test.dart`; k6 `basket-analysis-flow` (sales, enable, a run, read)

## Screens

- **Admin shell, Reports → What sells together:** a store picker ("All stores" for those held to none), a period line ("Last 28 days to Tuesday 29 September, worked out overnight"), a list of pairs and triples each as one sentence and its three numbers, a search by product, an "Excluded products" editor and the enable switch with the window (owner). A `basis` line under the figures; CSV button.
- **Product screen:** a panel "Often bought with" and "Rivals on the shelf" with **Add as complementary** and **Add as substitute** (each creates the existing product-svc relationship after a confirm).
- **Shelf-space screen:** a hint "often bought with the product next to it" on a fixture line. Words not codes, adaptive per UI-GUIDE §7.2, dates through `AppFormat`.

## Flow Tests entry

Area `customer`, flow file `target/flow-catalogue/customer/rpt-basket-analysis.json` (row 19.9). Cases:
- **Happy:** enable, run overnight, read pairs and triples in words, accept a complementary suggestion — `BasketRunIT`, `BasketSuggestionIT`, k6 `basket-analysis-flow`.
- **Negative:** no window, level invalid, minimum below the floor, unknown exclusion, off returns nothing — `BasketSettingsIT`.
- **Override:** an exclusion list removes a product; a business raises its own minimum — `BasketRunIT.whatIsCounted`, `BasketSettingsIT`.
- **Isolation:** store-held manager at another store and the business-wide run, another business's staff of every role, a shopper — `BasketIsolationIT`.
- **Edge:** stores in different zones; unreadable zone falls back and says so; truncation; a set under the floor; voids; unknown variants; a re-run — `BasketRunIT`.
- **Audit:** settings changes are on the trail with actor and time; each run states its window, zone and basis — `BasketSettingsIT.changesAreOnTheTrail`, `BasketRunIT.basisIsStated`.

## Decisions

- **2026-09-30, no event change.** The lines reporting-svc already receives are enough; the two things it cannot see (later short-closes, line-level returns) are stated on screen rather than fixed by a heavier event.
- **2026-09-30, a person applies every suggestion,** the rule the repricing page also keeps for prices.
