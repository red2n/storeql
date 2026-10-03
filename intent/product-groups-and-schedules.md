# Product groups and a schedule that raises counts and replenishment on the business's own rhythm

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the Oracle audit's "Product groups and a scheduler" row (EICS ch. 5, graded absent, "no row on the Review"; the audit's cycle-counting note: "generated from product groups on a schedule … the scheduling and the authorisation model are not [built]") · 2026-09-30 |
| **Roadmap** | new: the Oracle audit (Phase 2 backlog, Inventory); [stock-counts](stock-counts.md) listed "automatic scheduling of cycle counts" as out, on purpose, "its own feature": this is that feature |
| **Services** | inventory-svc owns groups, schedules, the sweeper and what it raises · product-svc's categories reach it as events · purchase-svc answers which variants a supplier supplies (REST) · tenant-svc supplies stores and their zones · notification-svc tells the people who must act · the app gets the screens |
| **Builds on** | `cycle_count_headers` / `cycle_count_lines` and their `abc_classes` (`V11`; a count today takes the classes it is told and nothing more), ABC assignments (`AbcAnalysisResource`, `POST /abc/compile`), [stock-counts](stock-counts.md) (count settings, blind sheet, rounds, review), [shop-floor-replenishment-and-store-orders](shop-floor-replenishment-and-store-orders.md) (shelf lists, suggestions), `NetworkService.run` (depot proposals), the existing sweepers (`ExpiryAlertSweeper`, `FoodSafetyOverdueSweeper`, `ReservationSweeper`), `ProductCategorised` (product-svc, already read by reporting-svc), `supplier_item_codes` |
| **Built in** | not built |

## Problem

A business that wants every shelf counted regularly and its shops replenished on a rhythm must remember to do it. A cycle count is started by a person for a store and a list of ABC classes; nothing says that class A is counted often and class C seldom, nothing remembers when a product was last counted, and nothing picks the counts up on a calendar. The depot run and the shelf list are the same: a manager presses a button. Oracle's answer is a product group (a named set of products) and a scheduler that raises the work; StoreQL has neither, so counting depends on a diligent person and replenishment on a busy one.

## Outcome

- **A business names groups of products:** by category, by supplier, by ABC class, or hand-picked, or a mix.
- **It sets a rhythm** (daily, on chosen weekdays, or on a day of the month) at a time of day, and **each store's own day** decides when it is due.
- **On the day, the work appears by itself, and never posts anything:** a count sheet for the products due, a shelf pick list, a depot proposal or a suggested store order, each an ordinary object that a person then works and approves as today.
- **A count schedule counts each ABC class as often as the business says** and tracks when each product was last counted, so nothing is counted twice in a row while another is never counted.
- **Every run is on the record,** including the ones that could not run and why, and stopping a schedule is on the record too.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the store or area manager (defines groups and schedules), the owner (business-wide ones), the storekeeper and stock clerk (work what is raised), the finance clerk (sees counts post).
- **Channels:** back-office (Inventory > Schedules); a schedule raises work into the tabs that exist.
- **Scope:** groups per business; a schedule per business naming the stores it runs at (or all the stores the caller may act at); each run per store per local day.
- **Roles that can write:** management. A schedule for every store, a group used by one, or a change that touches a store the caller is not held to needs a caller held to none (`403 STORE_ACCESS_DENIED` / `BUSINESS_WIDE_ONLY` as tenant-svc's rule); a manager held to stores writes groups (they are business data, visible to all management) but schedules only for their own stores. Reading: management; the storekeepers of a store read what is scheduled at it. The sweeper acts as the platform's own system identity, never as a person, and never posts or approves.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order:
  1. **Product groups** (inventory-svc). `product_groups` (name, description, active) with any of these filters, **all of which must hold** (a group is a question, not a stored list): `categoryIds` (a category and everything under it), `supplierId`, `abcClasses` (`A`, `B`, `C`), plus `include` (hand-picked variants) that are always in and `exclude` that are always out. Categories arrive as events: inventory-svc keeps a `variant_categories` projection (`variantId`, category path leaf first) fed from product-svc's existing `ProductCategorised` (the same event reporting-svc reads; whichever page builds it first, [known and unknown loss](known-and-unknown-loss.md) slice 2 or this one, adds it once, idempotent on the event's `eventId`, a later word wins). A supplier's variants are read from purchase-svc at the time a group is used (`GET /suppliers/{id}/variants`, a small new read over `supplier_item_codes`, cached briefly); an unreadable answer means the group **is not resolved** and any run that needs it says `SKIPPED: MEMBERS_UNREADABLE` rather than counting a partial set. `GET /admin/inventory/product-groups/{id}/members?storeId=` previews the members (cursor page) and their number. Groups are business-wide; a product retired from the catalogue leaves every group at once.
  2. **Count schedules with a rhythm per ABC class** (inventory-svc). A `schedules` row of `kind = COUNT` names the stores, an optional group, the **cadence** and, for each ABC class the business wants counted, an **interval in days** (`{A: …, B: …, C: …}`; a class with no interval is not scheduled; **the platform names no interval**, nothing runs until the business sets one). The cadence is `DAILY`, `WEEKLY` on named weekdays, or `MONTHLY` on a day of the month or the last day, at a local time of day. A run for a store on its local date: the products **due** are those in the group (or all stocked products) whose class has an interval and whose `last_counted` is older than that many days in the store's day, or never counted; `last_counted` is read from inventory-svc's own posted counts and rounds (`cycle_count_lines`, `count_rounds`), so a manual count also resets it. If the business set a `max_lines` per run the oldest-counted come first and the rest wait for the next run; unset means every due product. The run creates a **cycle count** through the existing create path with `generated_by = SCHEDULE`, blind and recount rules from [stock-counts](stock-counts.md) count settings, a tolerance from the business's setting (never wider), started by no person, and the count sheet appears in the Counts tab. A run does **not** post, approve or adjust anything: posting stays `stock.adjust` and, above the ceiling, the approvals key `stock.cycle-count-post`. It skips with a stated reason if the store already has an open scheduled count for this schedule (`OPEN_COUNT`), if the store is not trading, or if the caller-independent inputs are unreadable.
  3. **Replenishment schedules** (inventory-svc). Three more kinds, same cadence rules: `SHELF_LIST` (raises the store's [shelf pick list](shop-floor-replenishment-and-store-orders.md), restricted to the group if one is set), `DEPOT_RUN` (runs `NetworkService.run` for a **warehouse**, restricted to the group; raises DRAFT transfer proposals a person releases; skips a shop with an open PROPOSAL draft exactly as a manual run does), and `STORE_ORDER_SUGGESTION` (writes a suggestion for the shop as a *saved draft* the shop's manager reviews and sends; never a request). Each raises through the same code a person's press uses, with the same guards; none sends, ships, releases or orders on its own.
  4. **The sweeper and idempotence** (inventory-svc). One sweeper (in the pattern of `ExpiryAlertSweeper`, started by an eager CDI bean) looks each minute for schedule-store pairs whose local time has passed their due time on today's local date and that have no `schedule_runs` row; a run is claimed by inserting the row (unique on `(schedule_id, store_id, local_date)`) in a transaction with the object it raises, so two replicas or a restart never raise twice and a crash mid-run leaves nothing half-made. A day the service was down through the whole day is **not back-filled**: it records `MISSED` with the reason once the next day begins, so the history is honest and the due-by-age rule catches up on the next real run. The schedule's days are judged in each store's own zone (`TenantProfiles.Stores.zoneOf`); nothing assumes a zone or a weekend.
  5. **Run now, pause, history** (inventory-svc). `POST /schedules/{id}/run` (management, Idempotency-Key) raises today's run for chosen stores at once (recorded `origin: MANUAL`, once per local day per store as any run); `POST …/pause` and `…/resume` (management; a paused schedule raises nothing and the pause shows on the record with who and why); `GET /schedules/{id}/runs` (cursor) shows each run with its outcome (`RAISED`, `SKIPPED` + reason, `MISSED`, `FAILED` + reason), the object raised, the number of products and who or what started it. Changes to a schedule (cadence, stores, group, intervals, pause) are rows in `schedule_changes` (append-only) with who, when and the old and new values.
  6. **Tell people** (notification-svc). `ScheduledWorkRaised` tells the storekeepers of the store what is waiting ("Cycle count for Aisle 3 is ready", "Shelf list ready"); `ScheduleRunFailed` tells management once per schedule per day. In-app and email by preference; a sandbox behaves the same.
  7. **The screens** (Flutter). See Screens.
- **Out, on purpose:**
  - **Auto-ticket printing (labels) and auto-adjustments.** Oracle's scheduler also prints tickets and adjusts stock. Label printing is the shelf-label feature's own; an automatic adjustment (posting a variance without a person) is refused by design here: counts are reviewed, a variance above the ceiling needs a second person, and the existing auto-approval within a tolerance already exists on a count a person started.
  - **Cron expressions and free-form calendars,** holiday calendars, and a schedule that follows the shop's opening hours. Daily, weekly and monthly at a local time cover the row; a fuller calendar is a later, separate decision.
  - **Back-filling a missed day.** A missed day is recorded, not replayed.
  - **A group that spans more than a business,** dynamic collections across tenants, and group-level pricing or promotion use: a group is inventory-svc's planning object; product-svc's categories stay product-svc's.
  - **A schedule for consignment counts, serial or lot-level counts.** As [stock-counts](stock-counts.md): counts are by product (and zone); consignment stock is counted but reported apart.
  - **Physical-inventory (full-store) scheduling as a unit.** A scheduled count is a cycle count; a full count is started by a person because it changes how the store trades.

## Data and flow

- **Owned by inventory-svc:**
  - `product_groups` (id, tenant_id, name, active, created_by, created_at), `product_group_filters` (group id, kind `CATEGORY` | `SUPPLIER` | `ABC`, value), `product_group_members` (group id, variant_id, mode `INCLUDE` | `EXCLUDE`); index `(tenant_id, …)`.
  - `variant_categories` projection (tenant_id, variant_id, category_path).
  - `schedules` (id, tenant_id, kind, name, group_id null, cadence, weekdays, month_day, local_time, store_ids, `class_intervals` (JSON of class → days), `max_lines` null, status `ACTIVE` | `PAUSED`, created_by, created_at); `schedule_runs` (append-only): schedule_id, store_id, local_date, outcome, reason, created_object_kind and id, product_count, origin `SCHEDULE` | `MANUAL`, at; unique `(schedule_id, store_id, local_date)`; `schedule_changes` (append-only).
  - `cycle_count_headers` gain `generated_by` (`PERSON` | `SCHEDULE`) and `schedule_id`; the store-order suggestion is a `store_orders` row with `status = SUGGESTION` that the shop manager sends or discards (added to [shop-floor-replenishment-and-store-orders](shop-floor-replenishment-and-store-orders.md)'s status set).
- **Needs from other services:** categories (event), a supplier's variants (purchase-svc REST), stores, their zones and trading status (`TenantProfiles`). No joins.
- **Events published (outbox, `eventId` last):** `ScheduledWorkRaised` (`storeql.inventory.scheduled-work-raised`: tenantId, scheduleId, storeId, kind, objectId, productCount) and `ScheduleRunFailed` (`storeql.inventory.schedule-run-failed`) → notification-svc; a count raised publishes what a count publishes when created (none today), nothing else. reporting-svc may read runs; none required.
- **Retryable writes (Idempotency-Key):** create/change a group or schedule, run now, pause, resume.
- **New error codes:** `404 INVENTORY_GROUP_NOT_FOUND`, `404 INVENTORY_SCHEDULE_NOT_FOUND`, `400 INVENTORY_SCHEDULE_INVALID` (a cadence with no weekday, a day of the month out of range, an interval below one day, a class other than A, B, C), `400 INVENTORY_GROUP_EMPTY_FILTER` (a group that would name every product; say so rather than count everything by accident), `409 INVENTORY_SCHEDULE_PAUSED`, `409 INVENTORY_SCHEDULE_RAN_TODAY`, `503 INVENTORY_GROUP_MEMBERS_UNREADABLE`, `403 STORE_ACCESS_DENIED`, `403 BUSINESS_WIDE_ONLY`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** none directly; the counts it raises value their variances in the home currency as [stock-counts](stock-counts.md) says.
- **Ledger postings:** none from a schedule; a posted count posts its own variance ([stock-counts](stock-counts.md) slice 6, and by kind as [known and unknown loss](known-and-unknown-loss.md) slice 3).
- **Dates:** UTC instants recorded; cadence, due time and `last_counted` judged in the **store's own zone**; a business whose stores are in different zones runs each in its own day.
- **Plan limits:** none.

## Constraints

- The sweeper is a scheduler in one process among many replicas: the unique run row is the guard, never a leader assumption.
- Pure `Schedules.due(cadence, localDate, localTime, lastRun)` and `CountDue.select(classIntervals, lastCounted, today, maxLines)` hold the arithmetic and are unit-tested in two zones.
- A schedule raises objects only through the code a person's press uses, with the same guards (open-draft skip, store trading, zone active), and posts nothing.
- Golden rule 1: categories by event, supplier membership by REST, stores and zones through `TenantProfiles`.
- Existing tenants: no group and no schedule exists, so nothing changes; existing cycle counts keep their meaning and gain only `generated_by = PERSON`.
- Never invent policy: no interval, no cap, no cadence default; unset means the class is not scheduled.

## Already there

- Cycle counts take `abc_classes` and a tolerance and are started by a person; ABC compile and assignments exist; the depot run and shelf gaps exist as actions; three sweepers exist in inventory-svc.
- Not there: any group, any schedule, any last-counted memory beyond the count lines themselves, any interval per class.

## Open questions

- [x] Are group members stored? → **no: filters plus hand-picked includes and excludes, resolved when used** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What counts as due? → **a class's interval in days since the last posted count or round in the store's own day; no interval means not scheduled, and the platform names none** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does a schedule ever post or order? → **never: it raises the object, a person works and approves it** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What of a missed day? → **recorded as missed, not replayed** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who may define one? → **management; store-held managers only for their stores** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Approval and alerts? → **no new approvals key (posting stays `stock.cycle-count-post`); an alert metric on changes to schedules, since pausing counts could hide a loss** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A group by category (with descendants), by supplier, by ABC class, hand-picked, and a mix resolves the right variants; excludes win; an empty filter is refused — `ProductGroupIT.filtersResolveTheRightMembers`, `ProductGroupTest` (pure)
- [ ] An unreadable supplier answer resolves nothing and a run says `MEMBERS_UNREADABLE` — `ProductGroupIT.anUnreadableSupplierNeverCountsAPartialSet`
- [ ] A count schedule counts class A products older than their interval and never counts a class with no interval; a manual count resets the clock; `max_lines` takes the oldest first — `CountScheduleIT.dueByClassInterval`, `CountDueTest` (pure)
- [ ] The run makes one blind cycle count `generated_by = SCHEDULE` and posts, approves and adjusts nothing — `CountScheduleIT.aRunRaisesACountAndNothingElse`
- [ ] Each store runs on its own local day and time, in two zones on both sides of midnight UTC — `ScheduleSweeperIT.eachStoreKeepsItsOwnDay`, `SchedulesTest`
- [ ] Two sweepers or a restart raise one count per store per day; a day fully missed is `MISSED` once and not replayed — `ScheduleSweeperIT.onceAndMissedIsRecorded`
- [ ] An open scheduled count, a store not trading, and an unreadable input each skip with a stated reason — `ScheduleSweeperIT.skips`
- [ ] Shelf-list, depot-run and store-order-suggestion schedules raise the same objects a person's press does with the same guards, a suggestion is only a draft, a depot proposal skips a shop with an open PROPOSAL draft — `ReplenishmentScheduleIT`
- [ ] Run now raises today's run once; pause and resume stop and restart it and are on the record with who — `ScheduleIT.runNowPauseResume`
- [ ] Who: a storekeeper and cashier are refused writes (`403 FORBIDDEN`); a manager held to stores cannot schedule another store (`403 STORE_ACCESS_DENIED`) or every store (`403 BUSINESS_WIDE_ONLY`) — `PermissionsIT.groupsAndSchedulesAreManagements`
- [ ] Isolation: another business's staff of every role and a shopper, naming our group, schedule or run, get 404 and nothing runs; a group of theirs never resolves our variants — `ScheduleTenantIsolationIT`
- [ ] Retry: the same key answers with the first for create, run-now, pause — `ScheduleIT.retries`
- [ ] The record: every change and run is a row with who or what, when and the old and new values, never edited — `ScheduleIT.theRecordIsAppendOnly`
- [ ] Abuse: repeated pauses or changes to counting by one person raise `schedules.changes` — `ScheduleAlertIT`
- [ ] The storekeepers of the store are told once when work is raised, management once a day when a run failed — `ScheduledWorkRaisedHandlerTest`, `ScheduleRunFailedHandlerTest` (notification-svc)
- [ ] A business in another country and zone counts on its own rhythm and language — `CountScheduleIT.spansBusinessesInDifferentCountries`
- [ ] Widgets: groups, schedules (build, pause, history), the Counts tab shows scheduled counts — `groups_test`, `schedules_test`
- [ ] k6 `schedule-flow`: a group and a class-A schedule, run now, the count sheet appears blind, counted by a keeper, a rival refused

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **The sweeper is a `Jobs` entry** ([background-work-and-stuck-items](background-work-and-stuck-items.md): job key `schedules.raise`, single flight, pausable by an operator, visible on the Operations screen). The `schedule_runs` unique claim per store and local day stays as designed. A schedule that raises a shelf list or a depot run calls the same services as the manual routes ([shop-floor-replenishment-and-store-orders](shop-floor-replenishment-and-store-orders.md), depot-dc-replenishment); nothing here reads a store day but through `BusinessDay`.

- 2026-09-30: settled by industry standard as above; nothing built yet.
- **New alert metric** for [exception-alerts](exception-alerts.md), owned by inventory-svc: `schedules.changes` (changes, pauses and deletions of schedules by one person or at one store in a period; subjects staff, store). No new approvals key.
- **Refines [stock-counts](stock-counts.md) "Out, on purpose"** (automatic scheduling of cycle counts): the scheduling is this page; the stock-counts text stands for everything else (a scheduled count is an ordinary blind count).
- **A new purchase-svc read** `GET /suppliers/{id}/variants` (management/service call), added when slice 1 builds.

## Screens

- **Admin > Inventory > Schedules** (new tab; management): a list of schedules in words (*Count class A every 30 days, Mondays 07:00, at Leeds and York*), status, next run in each store's day, last outcome; **New schedule** (adaptive sheet: what to raise, which group, which stores, how often and at what time, and for a count a days-between-counts field per class that is blank until the business fills it); a schedule's history with each run's outcome and the link to the count or list it raised; **Pause** and **Resume** with a reason; **Run now**.
- **Inventory > Groups** (management): create a group (categories picker, supplier picker, classes, hand-picked products by name, exclusions), a live count of members and a preview list.
- **Counts tab** ([stock-counts](stock-counts.md)): a count says *Scheduled* beside its name; the counter's sheet is unchanged.
- Everything through the shared widgets and tokens; dates and times through `AppFormat` in the store's zone; words not codes.

## Flow Tests entry

- **Catalogue area and file:** `target/flow-catalogue/inventory/product-groups-and-schedules.json` (the inventory domain, beside `cnt-cycle-counts-stocktakes`). The feature is not BUILT until this entry exists and its cases are automated.
- **Cases:**
  - Happy: a group and a class-A count schedule raise a blind count for the due products (`CountScheduleIT.aRunRaisesACountAndNothingElse`, k6 `schedule-flow`); a shelf-list and a depot schedule raise their objects (`ReplenishmentScheduleIT`).
  - Negative: an empty group filter; a bad cadence; an unreadable supplier; a paused schedule run; a run already made today (`409`).
  - Override: run now; pause and resume; a manager held to stores schedules only theirs.
  - Isolation: other business's staff of every role and a shopper; a manager held to another store; groups never cross businesses.
  - Edge: two zones either side of midnight UTC; a class with no interval; `max_lines` taking the oldest; a product retired from the catalogue; two replicas; a missed day; a manual count resetting the clock.
  - Audit: schedule changes and runs append-only (`ScheduleIT.theRecordIsAppendOnly`); the alert on repeated pauses (`ScheduleAlertIT`).
