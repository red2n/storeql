# Depot / DC replenishment separate from store replenishment

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the readiness review's Supply chain, warehouse & logistics row · 2026-09-25 |
| **Roadmap** | Readiness Review, "Depot / DC replenishment separate from store replenishment", Absent (value). "Cross-docking", the row before it, builds on this page's serving relationship and system-raised transfers (see Open questions) |
| **Services** | inventory-svc owns the serving relationships, the store replenishment proposals and the transfers they raise · purchase-svc keeps raising purchase orders, now for the warehouse on the stores' behalf and never for a store a warehouse serves · tenant-svc owns the stores and their type (STORE or WAREHOUSE), read through `TenantProfiles`, never joined |
| **Builds on** | `stores.type` (STORE / WAREHOUSE, stored today but used by nothing), `transfer_orders` / `transfer_order_lines` and their ship/receive lifecycle, `reorder_point_plans`, `demand_forecasts`, the order proposal (`POST /purchase-orders/proposals/run`, `OrderProposal`), the putaway hook on every received batch, the Inventory screen's Transfers tab and Procurement's Propose orders |
| **Built in** | the commit "feat(inventory,purchase,tenant,app): the warehouse serves the shops — depot / DC replenishment" |

## Problem

A business with a warehouse (a depot, a distribution centre) cannot run it as one. The platform lets it add a store of type WAREHOUSE, and then treats it like any shop: nothing says which shops it supplies, the order proposal buys every shop's stock straight from the supplier, and a transfer from the warehouse to a shop exists only if a person raises it by hand, line by line, from a guess. So the warehouse is either bypassed (every shop orders direct, losing the buying scale the warehouse exists for) or run from a spreadsheet beside the system. And the warehouse's own buying has nothing to go on: it has no sales of its own, so its forecast is empty.

## Outcome

A business says which warehouse serves which shops. From then on the shops' replenishment is a **transfer proposal from their warehouse**, not a purchase order: when a shop's stock position falls to its reorder point, the platform proposes a transfer of what it needs, a person reviews and releases it, and the warehouse ships it through the transfers that exist today. The warehouse's own replenishment is **a purchase order proposal for the warehouse**, sized on what the shops it serves are expected to sell, not on its own (empty) till. A shop can still buy some things direct (bread, milk, a local supplier): those lines stay on the ordinary purchase proposal for the shop.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the owner or manager who sets up the network; the buyer who runs the proposals; the warehouse storekeeper who ships; the shop storekeeper who receives.
- **Channels:** back-office only.
- **Scope:** per tenant (the network), per shop (which warehouse serves it), per shop and product (an exception: bought direct).
- **Roles that can write:** serving relationships OWNER, MANAGER; running and releasing transfer proposals needs `stock.transfer` at the warehouse (as shipping does today); the warehouse's purchase proposal is the existing buying roles.
- **Sandbox tenant:** behaves the same; nothing leaves the platform.

## Scope

- **In:**
  - A serving relationship: a shop is served by one warehouse (its default), with per-product exceptions that say "bought direct". Only a store of type WAREHOUSE can serve, and it serves shops only: not itself, not another warehouse (see Out). tenant-svc refuses a store type other than STORE or WAREHOUSE (today any string is stored).
  - A transfer proposal run for a warehouse's shops: per shop and product served by that warehouse, the same (s, Q) reorder-point rule the purchase proposal uses (stock position = available + inbound transfers not yet received; the quantity from the reorder plan and the forecast), written as DRAFT transfer orders from the warehouse to each shop, with the reason on each line, released by a person into today's PENDING → SHIPPED → RECEIVED lifecycle.
  - The purchase proposal learns the network: for a shop it proposes only what the shop buys direct; for a warehouse it proposes on the warehouse's own position against the demand of the shops it serves (their forecasts summed over the warehouse's lead time and cover), plus what is already committed to them in unreleased and unshipped transfers.
  - Transfers carry what raised them (a proposal run) so a line can be traced back, and their events name their lines (today `TransferOrderShipped`/`Received` carry none).
  - App: a Network section (which warehouse serves which shop, and the exceptions), a Replenish shops action on the warehouse with the proposed transfers to review and release, and the Propose orders dialog showing a warehouse's lines with their served-demand reason.
- **Out, on purpose:**
  - **Cross-docking** (goods received at the warehouse flowing straight out to shops without putaway): its own row; it needs this page's relationship and system-raised transfers first.
  - **A product routed to a different warehouse than the shop's default** (a chilled DC for chilled lines): decided against for now; one default per shop, exceptions are "bought direct" only.
  - **Multi-echelon chains** (a regional DC serving a local depot serving shops): one level only. A warehouse serving another warehouse is refused. Rare for the businesses this platform serves, and it multiplies every rule below.
  - **Automatic release or shipping**: a proposal is never shipped without a person, as a purchase proposal is never sent without one.
  - **Transport, routes, delivery days and vehicle loads**: the Transport and route planning row. A warehouse's lead time to a shop is one number of days.
  - **Partial shipping and short receipts on transfers**: today's all-or-nothing ship and receive stay as they are; a separate defect-sized follow-up if it hurts.
  - **Intercompany invoicing of transfers** between legal entities: exists by hand today; not automated here.
  - **Moving `ReplenishmentSuggested` / min-max and kanban onto the network**: the reorder-point plan is the one policy both proposals use; the min-max and kanban paths stay as they are.

## Data and flow

- **Owned by** inventory-svc: `serving_relationships` (tenant, shop, warehouse, lead time in days; one default per shop), `serving_exceptions` (tenant, shop, product: bought DIRECT), `transfer_proposal_runs` (who ran it, when, for which warehouse) and, on `transfer_orders`, a source (MANUAL | PROPOSAL) and the run id, with each line's reason. Transfer lifecycle gains DRAFT before PENDING (released = PENDING).
- **Needs from other services:** store types and ids from tenant-svc through `TenantProfiles` (extended to carry each store's type; cached, never joined); the purchase proposal reads the network and the shops' forecasts and committed transfers from inventory-svc over REST (as it reads plans and forecasts today).
- **Events published:** `TransferOrderShipped` / `TransferOrderReceived` gain their lines (variant, qty) so reporting-svc's supply-line projection, a stub today, can fill; `ServingChanged` if a consumer needs it (none planned: the purchase proposal reads on demand).
- **Retryable writes** (Idempotency-Key): running a transfer proposal; releasing a proposal.
- **New error codes:** `INVENTORY_SERVING_NOT_A_WAREHOUSE` 400 (the source store is not of type WAREHOUSE); `INVENTORY_SERVING_SELF` 400; `INVENTORY_SERVING_WAREHOUSE_TO_WAREHOUSE` 400; `INVENTORY_SERVING_NOT_FOUND` 404; `INVENTORY_TRANSFER_NOT_DRAFT` 409 (releasing or editing what is already released); `INVENTORY_PROPOSAL_NOTHING_SERVED` 409 (the warehouse serves no shop); `TENANT_STORE_TYPE_INVALID` 400 (tenant-svc). Store scope as everywhere (`403 STORE_ACCESS_DENIED`).

## Money, time and limits

- **Currency:** none new; transfers move stock at cost as today, and purchase proposals price as today.
- **Ledger postings:** none new. A transfer is not a sale; intercompany invoicing stays manual (Out).
- **Dates:** the run's instant; a shop's lead time from its warehouse in days; the forecast horizon the purchase proposal already uses.
- **Plan limits:** none (stores are already limited by the plan).

## Constraints

Database-per-service: the network lives in inventory-svc beside the stock it moves; tenant-svc's store types are read through `TenantProfiles`, never joined; purchase-svc reads the network over REST. Existing tenants with no serving relationship behave exactly as today: every shop buys direct, the proposal is unchanged. The transfer lifecycle today's screens and k6 suites drive (PENDING → SHIPPED → RECEIVED) is unchanged for a manual transfer. Consignment and bonded stock rules still hold when a transfer draws (`deductBatches` by move type): a proposal never plans stock that cannot be transferred.

## Open questions

- [x] **Cross-docking first, or this?** The readiness page lists Cross-docking before this row. It needs a warehouse-to-shop relationship and transfers the system raises, which this page builds. Recommended: build this page now and take Cross-docking next on top of it. The alternatives are cross-docking first (building a thinner relationship just for it) or skipping cross-docking for now. → **depot first; cross-docking next, on top of it** (the user, 2026-09-25)
- [x] **How is a shop's supply decided?** Recommended: one default warehouse per shop, with per-product exceptions that make a product "bought direct". The alternative adds exceptions that route a product to a *different* warehouse (a chilled DC for chilled lines), which is more set-up and more rules. → **one default warehouse per shop, with "bought direct" exceptions per product** (the user, 2026-09-25)
- [x] **When the warehouse has less than its shops need, who gets it?** Recommended: fair share, in proportion to each shop's need, rounded down, the remainder to the shop with the least cover. The alternatives are the shop with the least cover first, or a priority per shop the business sets. → **fair share in proportion to need, the remainder to the least cover** (the user, 2026-09-25)
- [x] **Does a proposed transfer need a person to release it?** Recommended: yes; the run writes DRAFT transfers a person reviews and releases, as a purchase proposal writes drafts. The alternative releases them straight to PENDING for the warehouse to ship. → **a person releases; the run writes DRAFT transfers** (the user, 2026-09-25)

## Acceptance

- [x] A shop served by a warehouse, below its reorder point, gets a DRAFT transfer from the warehouse for the rule's quantity with the reason on the line; a shop above it gets none; inbound unreceived transfers count in the position — `DcReplenishmentTest` (5, pure), `NetworkIT.aRunProposesDraftTransfersThatAreReleasedShippedAndReceived`
- [x] A product the shop buys direct is not proposed as a transfer and is still on the shop's purchase proposal; a served product is not on the shop's purchase proposal — `NetworkIT.aRunProposes…`, purchase-svc `OrderProposalIT.aServedShopBuysOnlyWhatItBuysDirect`
- [x] The warehouse's purchase proposal sizes on the served shops' demand over its lead time and cover, less its own position and what is committed to shops; with no lead time anywhere the product is skipped and says so — `OrderProposalIT.aWarehouseBuysForTheShopsItServes`; the sourcing it reads (demand summed, a product bought direct left out, committed) — `NetworkIT.aRunProposes…`
- [x] Short at the warehouse, the proposals share what there is in proportion to need, the remainder to the least cover, the line saying it was cut — `DcReplenishmentTest.aShortWarehouseSharesInProportionToNeed…`, `NetworkIT.aShortWarehouseSharesInProportionToNeed`
- [x] Released, a proposal is an ordinary PENDING transfer that ships and receives as today; released twice `409 INVENTORY_TRANSFER_NOT_DRAFT`; a second run while a draft waits `409 INVENTORY_PROPOSAL_OPEN`; the same key is the same run; a draft may be discarded — `NetworkIT.aRunProposes…`, `NetworkIT.aRetriedRunIsTheSameRunAndADraftMayBeDiscarded`
- [x] A store that is not a WAREHOUSE cannot serve (`400 INVENTORY_SERVING_NOT_A_WAREHOUSE`), nor itself, nor a warehouse, nor a store of another business, nor with a lead time outside 0–90; tenant-svc refuses an unknown store type (`400 TENANT_STORE_TYPE_INVALID`) and upper-cases a known one — `NetworkIT.theNetworkIsSetWithWarehousesServingShopsOnly`, tenant-svc `OnboardingIT.aStoreIsAShopOrAWarehouseAndNothingElse`; every service can tell a warehouse — common-service `TenantProfilesTest.storesKnowWhetherTheyAreWarehouses`
- [x] A keeper of one shop cannot run or release the warehouse's proposals, a cashier cannot run one, a storekeeper cannot set the network; another tenant sees no relationship and releases no transfer — `NetworkIT.runsAreTheWarehousesAndRefusedWhereTheyCannotBe`, `NetworkIT.theNetworkIsSet…`
- [x] Transfer events name their lines and both stores — `NetworkIT.aRunProposes…` (the outbox payloads)
- [x] The Depot & shops tab lists the network and the drafts with the reason on each line, Serve a shop puts the shop, the warehouse and the days, Propose transfers posts with an idempotency key and a draft is released, a cashier sees no buttons — `inventory_network_test` (4)
- [x] Through the gateway: a store type that is neither refused, a shop's reorder point computed, a shop refused as a warehouse, the warehouse serving it, one DRAFT transfer proposed with its arithmetic (a cashier refused, the same key the same run, a second run refused), the shop's purchase proposal buying none and the warehouse's buying for it, released once, shipped and received, a rival refused — k6 `dc-replenishment-flow` (25)

## Decisions

- **The transfer rule is the purchase rule without a supplier's economics.** A shop at or below its reorder point needs enough to get back to it plus what it is expected to sell over the warehouse's lead time and the cover asked for (default 7 days: shops restock from a warehouse more often than from a supplier). No EOQ, no minimum, no lot: those are a supplier's, and they bend the warehouse's own purchase order instead. The quantity rounds *up* to three decimals, so a shop is never sent a fraction less than it needs.
- **A shop's position counts every transfer on its way: proposed, released or shipped and not yet received.** Otherwise a released transfer waiting at the dock would be proposed again. And a second run is refused while a draft from the warehouse still waits (`INVENTORY_PROPOSAL_OPEN`), as the purchase proposal refuses a second draft: two drafts for the same need would double it.
- **The warehouse shares what is free: on hand, less holds, less what it has already committed in drafts and released transfers.** Short, each shop gets its need in proportion, rounded down to whole units; the rest — whole units and any weighed fraction — goes to the shop with the least cover first (days of cover = position ÷ daily demand; a shop with no demand history has endless cover and goes last). The line's reason says it was cut and why.
- **A proposed transfer is INTRANSIT.** The goods travel: shipping and receipt are separate steps, and the stock is on its way in between, which is what the shop's position counts.
- **A warehouse buys on its shops' demand, not its own.** A warehouse sells nothing, so its own demand history is empty and a reorder point computed from it is zero. For a product its shops take from it, the purchase proposal sets the reorder point to their daily demand over the warehouse's lead time (its own plan's lead time when it has one, else the supplier's quoted lead time; with neither the product is skipped and says so), the cover to their forecast, and the position to its stock less what is committed to them. The warehouse's own plan still gives the supplier's EOQ, minimum, maximum and lot. Safety stock at the warehouse is not added in this cut: the shops' reorder points already carry theirs.
- **purchase-svc reads the network before it proposes, and refuses rather than guess.** When inventory-svc cannot be read, the proposal is refused (`PURCHASE_PROPOSAL_STOCK_UNAVAILABLE`), because buying for a shop a warehouse already serves double-orders. A business with no network is answered 404 and proposes exactly as before.
- **The type check lives where the type does.** tenant-svc refuses a store type other than STORE or WAREHOUSE (`TENANT_STORE_TYPE_INVALID`; a store's type cannot be changed after it is made), and `TenantProfiles.Stores` carries which stores are warehouses to every service; inventory-svc checks a serving relationship against it and never joins tenant-svc's tables.
- **The exception record is `DirectPurchase`, not `ServingException`.** SpotBugs refused a record named like an exception that is not one; the name also says what it is — a product the shop buys direct.
- **Transfer lines are read with the tenant.** `listTransferOrderLines` filtered only by the order's id; it now filters by `tenant_id` first, as every query on tenant data must.
