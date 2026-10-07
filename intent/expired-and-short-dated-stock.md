# Expired and short-dated stock: the date watch, disposal, use-by and best-before

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on expiry (inv-expiry-and-markdown: nothing takes expired stock off the shelf, INV-66) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, inventory domain: inv-expiry-and-markdown, inv-stock-adjustments-writeoffs |
| **Services** | inventory-svc owns dates, the watch list and disposals · product-svc owns whether a date is use-by or best-before · pricing-svc owns the markdown ladder and its cap · purchase-svc owns returns to vendor and posts write-offs · notification-svc sends the daily digest · tenant-svc's store tasks carry the "do the date check" reminder |
| **Builds on** | `inventory_batches.expiry_date`, `domain/Expiry.java` and `ExpiryDay` (the store's own day; expired stock is on hand, never available, drawn only by write-off and return to vendor: 30 Sep 2026), `ExpiryAlertSweeper` (logs only), reason code `EXPIRY`, `ReturnedToVendor` (`V15__tier1_gaps.sql`), the markdown ladder and `POST /markdowns` (pricing-svc `MarkdownResource`), tenant-svc `task_templates` (`V20__store_tasks.sql`), GS1 AI 17 / AI 15 in `ScannedCodeResponse` |
| **Built in** | |

## Problem

Today's rule is right and half finished. Expired stock stays on hand and is never sold, but nothing makes anyone deal with it. The expiry sweeper writes a log line. A storekeeper has no list of what expires tomorrow or has expired at their store, no single action that writes it off, sends it back to the supplier or marks it down, and no record of what was destroyed, by whom, and how. The rule also treats every date alike: a chilled chicken pack's use-by and a tin of beans' best-before both stop the sale, when food law in most places makes only the first a bar to sale. Markdowns are cut by a ladder, yet a storekeeper can sticker at any depth, so the ladder is only a suggestion.

## Outcome

- **Each store opens the day to a list:** what has expired and is still on the shelf, and what expires within the business's look-ahead, in the store's own day, each with quantity, value at cost, where it sits, the ladder's suggested markdown, and what has already been done to it.
- **One action per line clears it:** write it off, send it back to the vendor, mark it down (while still saleable), donate it, or set it apart to decide later. Whatever is chosen is recorded: what, how much, who, when, how it was disposed of, and who witnessed it where the business requires a witness.
- **A product says which kind of date it carries.** Use-by dates block the sale on the day after, as they do today. Best-before dates do not block the sale: the batch stays available, is shown as past best before, and the business may choose to stop selling it after some days.
- **The ladder is a ceiling on discretion**: a markdown deeper than the ladder's step for the batch's days left needs authority above the storekeeper.
- **The manager is told once a day**, at the start of the store's day, with the count and value.
- **The books follow the disposal:** a write-off or donation posts its cost as a loss.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the storekeeper (works the list), the store manager (approves deep markdowns, sees the digest, sets the look-ahead), the owner, the food-safety lead (the disposal record).
- **Channels:** back-office (admin app, Inventory); the daily digest by in-app notice and email.
- **Scope:** per store, in the store's own day; product-svc's date kind is per product.
- **Roles that can write:** `stock.adjust` to write off, donate, or set apart; `stock.transfer` is not involved; return to vendor as purchase-svc gates it today; markdown as pricing-svc gates it, and a markdown deeper than the ladder needs management (a role rule that needs no setting), and above a management ceiling, or with a second person required, the action key `pricing.markdown-deep` on [approvals](approvals.md) (the one key for this and for pricing's own deep-markdown finding). Date kind on a product: whoever edits products. Settings: management.
- **Sandbox tenant:** behaves the same; the digest is suppressed in a sandbox (notification-svc's rule).

## Scope

- **In**, in build order:
  1. **The date kind** (product-svc, inventory-svc). A product carries `dateKind`: `USE_BY` or `BEST_BEFORE`; unset means `USE_BY`, so nothing changes for any business until it says otherwise, and a non-food product with a date (medicine) keeps blocking. product-svc announces it as `VariantHandlingSet`, the one event that carries a variant's date kind, storage class and lot tracking (fields defined once, on [receiving controls](receiving-controls.md) slice 4); inventory-svc keeps it in one projection, `variant_handling`. `Expiry` (pure) becomes `sellable(date, kind, stopAfterDays, today)` and its SQL fragments read the kind: a USE_BY date passed is expired, as today; a BEST_BEFORE date passed is sellable unless the business set `best-before.stop-after-days` and that many days have gone (off until set). All draw sites already take the condition from `Expiry`, so this is one place. Levels gain `pastBestBefore` apart from `expired`.
  2. **The date watch** (inventory-svc). `GET /admin/inventory/date-watch?storeId=&after=&limit=` lists batches at the store that are expired (by use-by rules) or past best before, and those within `date-watch.days-ahead` (a business/store setting, unset means expired only), each with product, batch, lot, quantity, zone, days left in the store's day, value at cost, and its latest action. Read by any staff at the store.
  3. **Disposal, recorded** (inventory-svc). `POST /admin/inventory/date-watch/dispose` with batch, quantity, `method` (`DESTROYED`, `DONATED`, `RECYCLED`, `OTHER`), an optional `recipient` (for a donation), `note`, `witnessUserId` where `disposal.witness-required` is on. It writes off through the existing ADJUST path with reason `EXPIRY`, or `DONATED` when the method is `DONATED` ([known-and-unknown-loss](known-and-unknown-loss.md): both are KNOWN loss; a donation is not an expiry) (the `StockAdjusted` it publishes carries `origin: DISPOSAL`, so the ledger is posted once, from `StockDisposed`: the rule is under [stock-counts](stock-counts.md) slice 6), and adds a `stock_disposals` row (append-only) on the same transaction, publishing `StockDisposed`. A disposal is a write-off like any other: above the business's ceiling for the approvals action `stock.writeoff` it is a kept request (`202`) and the goods stay on the list until it is approved. Refuses more than the batch holds (`422 INSUFFICIENT_STOCK`, as any write-off), a witness who is the caller (`409 DISPOSAL_WITNESS_SAME_PERSON`), a missing witness where required (`400 DISPOSAL_WITNESS_REQUIRED`).
  4. **Set apart** (inventory-svc). `POST .../date-watch/segregate` flips the batch to `QUARANTINE` (the existing status change, which a recall never undoes) so it cannot be moved on by mistake, recorded as an action; it is put right by a disposal or by a person returning it to AVAILABLE if a date was mistyped (the existing flip, with a note).
  5. **The write-off on the ledger** (purchase-svc). Consumes `StockDisposed` once: Dr *Stock shrinkage* (the account [stock counts](stock-counts.md) adds) / Cr 1001 Stock at cost, dated the disposal; a donation posts the same, being a loss to the business.
  6. **Return to vendor from the list** (purchase-svc, app). The list's action opens the existing return-to-vendor request with the batch prefilled; the batch is drawn by RTV as it is today (expired stock may be returned). The list shows it done when `ReturnedToVendor` arrives.
  7. **The ladder as a ceiling** (pricing-svc). `POST /markdowns` refuses a discount deeper than the ladder's step for the batch's days to expiry, at the store (`403 PRICING_MARKDOWN_NEEDS_APPROVAL`) unless the caller is management; the list shows the suggested step beside each line and offers "Mark down at the suggested step" as one action. Below the ladder's own shallowest step, nothing is checked. A markdown on stock already expired by use-by is refused as today's rule requires (nothing to sell).
  8. **The daily digest** (inventory-svc, notification-svc, tenant-svc). At the start of each store's day (its zone, its opening time from tenant-svc where set, else midnight), a sweeper publishes `DateWatchDue` once per store per day (idempotent on store and date) with counts and value; notification-svc tells the store's manager (`DATE_WATCH_DAILY`). The existing sweeper stops logging alone. A business may add a `DATE_CHECK` task template to tenant-svc's store tasks so the check is on the store's daily list; no second task mechanism is built.
  9. **The screen** (Flutter). See Screens.
- **Out, on purpose:**
  - **Automatically changing an expired batch's status or writing it off.** Expiry is derived from the date, so nothing flips at midnight; destroying goods is a person's act and a recorded one. What is automatic is that it can never be sold, and that someone is told.
  - **A law-specific rule** (which foods are use-by in which country). The product says which kind its date is; the platform carries no list.
  - **Reading the kind from a scanned code.** GS1 AI 17 versus AI 15 is shown at receipt as a hint; the product's kind stays the authority, one place.
  - **Photo evidence and a regulator's disposal certificate.** The record has the fields; attachments belong to the documents feature.
  - **Selling past a use-by date under any setting.** There is no setting that allows it.

## Data and flow

- **Owned by inventory-svc:**
  - `variant_handling` (tenant_id, variant_id, date_kind, storage_class) projection from product-svc's `VariantHandlingSet`, shared with receiving-controls.
  - `stock_disposals` (append-only): id, tenant_id, store_id, batch_id, variant_id, qty NUMERIC(18,3), unit_cost/value NUMERIC(18,4) with currency, method, recipient, note, disposed_by, witness_user_id, disposed_at, movement_id.
  - `date_watch_actions` (append-only): batch, action (`DISPOSED`, `SEGREGATED`, `MARKED_DOWN`, `RETURNED_TO_VENDOR`), actor, at, reference.
  - `inventory_settings` per business/store: `date_watch_days_ahead`, `best_before_stop_after_days`, `disposal_witness_required` (all off until set).
- **Owned by product-svc:** `products.date_kind` (`USE_BY`|`BEST_BEFORE`, null = USE_BY).
- **Owned by pricing-svc:** the ceiling check on `POST /markdowns`.
- **Needs from other services:** the store's zone (`TenantProfiles`, already read through `ExpiryDay`); the ladder's step (pricing-svc `GET /markdowns/ladder`, REST with the client's timeout and fail-open: an unreadable ladder shows no suggestion and lets management's rule stand); product names via the catalogue projection.
- **Events published:** `StockDisposed` (`storeql.inventory.stock-disposed`: store, batch, variant, qty, value, currency, method, actor) → purchase-svc (posts), reporting-svc; `DateWatchDue` (`storeql.inventory.date-watch-due`) → notification-svc; product-svc's date-kind change → inventory-svc.
- **Retryable writes (Idempotency-Key):** dispose, segregate.
- **New error codes:** `400 DISPOSAL_WITNESS_REQUIRED`, `409 DISPOSAL_WITNESS_SAME_PERSON`, `422 DISPOSAL_METHOD_INVALID` (unknown method), `403 PRICING_MARKDOWN_NEEDS_APPROVAL`, `400 IDEMPOTENCY_KEY_REQUIRED`.

## Money, time and limits

- **Currency:** home currency, valued from the batch's cost; an uncosted batch is listed as uncosted, its write-off posts nothing and says so.
- **Ledger postings:** Dr Stock shrinkage / Cr 1001 at disposal.
- **Dates:** an expiry is a date, compared with the store's own day (`Expiry.at`); UTC only where the zone cannot be read. Disposal and digest instants are UTC.
- **Plan limits:** none.

## Constraints

- One rule in one place: every draw and level keeps taking its condition from `Expiry`; this page adds the kind and nothing else to the SQL.
- Append-only: `stock_disposals`, `date_watch_actions`, `stock_movements`.
- Existing tenants: no `dateKind` means USE_BY, exactly today's behaviour; no setting means no look-ahead and no best-before stop.
- Recalls and status changes are unaffected; a recalled batch is never offered for markdown or sale whatever its date.

## Already there

- The rule of 30 Sep 2026: `expiry_date` is the last day of sale in the store's zone; expired stock is on hand, never available, never drawn by a sale, wave, transfer, move order, cross-dock, yield or bond release; still drawn by a write-off and a return to vendor. `domain/Expiry.java`, `repo/ExpiryDay.java`; `ExpiryTest` and `ExpiryIT` (four tests).
- `expired` on every level answer.
- The reason code `EXPIRY` exists (`V15`); Adjust dialog offers reason codes (wave 1, `inventory_adjust_reason_codes_test`).
- The expiring-batches report and `ExpiryAlertSweeper` (logs only). Return to vendor exists (`V15__tier1_gaps.sql`, `ReturnedToVendorHandler`). The markdown ladder and `POST /markdowns` exist (`markdown-flow` k6).
- The app already shows `expired` on a level (wave 1, `inventory_expired_test.dart`); what slice 9 adds is `past best before`, the date watch and the actions.

## Open questions

- [x] Is a date use-by or best-before? → **a product attribute; unset is use-by; only use-by blocks the sale** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] May a business stop selling best-before stock after some days? → **yes, a setting, off until set** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does expired stock change status by itself? → **no; the date decides, a person disposes, and it is recorded** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is a witness needed to destroy goods? → **a business setting, off until set** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is the ladder a ceiling? → **yes; deeper needs management, and a value ceiling needs a second person via approvals** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How far ahead does the list look? → **the business's setting; unset lists only what has expired** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A BEST_BEFORE batch past its date stays available and reads `pastBestBefore`; a USE_BY one is expired — `ExpiryTest` (pure, kinds), `ExpiryIT.aBestBeforeDatePassedStillSells`
- [ ] With `best-before.stop-after-days` set, it stops after that many days — `ExpiryIT.aBestBeforeStopsAfterTheBusinessesDays`
- [ ] An unset kind behaves as before for every existing test — `ExpiryIT` (existing four)
- [ ] The watch lists expired and within-look-ahead batches in the store's own day, apart from another store's — `DateWatchIT.theListIsTheStoresOwnDay`
- [ ] Disposing writes off the batch with `EXPIRY`, adds the disposal row, publishes once; a retry with the same key does not repeat — `DateWatchIT.aDisposalIsRecordedOnce`
- [ ] A witness is required where set and cannot be the caller — `DateWatchIT.witnessRules`
- [ ] Over-disposal is `422 INSUFFICIENT_STOCK` and nothing is written — `DateWatchIT.overDisposalWritesNothing`
- [ ] purchase-svc posts Dr shrinkage / Cr 1001 once on a redelivered event — `DisposalPostingIT`
- [ ] A markdown deeper than the ladder is `403 PRICING_MARKDOWN_NEEDS_APPROVAL` for a storekeeper, allowed for a manager; the suggested step is allowed — `MarkdownIT.theLadderIsACeiling`
- [ ] The digest is published once per store per store-day, and suppressed in a sandbox — `DateWatchSweeperIT`, `DateWatchHandlerTest`
- [ ] Another business's staff naming our store or batch get 404/empty and nothing is written; a store-held caller sees only their stores — `DateWatchTenantIsolationIT`
- [ ] Widget: the list shows the suggested step and the actions in words — `date_watch_screen_test`
- [ ] k6 `expiry-flow`: receive dated stock, pass the date, see it on the list, dispose, see the ledger

## Screens

- **Admin > Inventory > Date watch** (new tab, or a section of Levels on a phone). Store picker; filter Expired / Expiring soon; rows show product by name, batch, zone, quantity, days left or days over (words, not codes), value (`AppFormat.money`), the suggested markdown, and a status badge of what was done. Row actions in an `AdaptiveActions` menu: Write off, Return to vendor, Mark down, Donate, Set apart. Write-off opens a sheet for method, recipient, witness (a staff picker when required) and a note; every refusal in words.
- **Levels**: shows `expired` and `past best before` apart from available.
- **Product edit** (Admin > Products): "Date on the pack" is "Use by" or "Best before", with one line explaining what each does at the till.
- **Settings**: look-ahead days, stop selling best-before after N days, witness required.

## Decisions

- 2026-09-30: settled by industry standard as above; nothing built yet.
- (2026-10-02, money at the currency's own minor units, industry standard) **A reduced-price sticker's five price digits are the currency's own minor units**, as GS1 price-embedded store codes carry a price: up to 999.99 in pounds or euros, ¥99,999, KWD 99.999 (`PRICING_MARKDOWN_LABEL_RANGE` past them; a yen sticker over ¥999 is no longer refused). A reduced price from a percentage is rounded half up at those units; a typed `markdownPrice` finer than the currency is refused (`400 VALIDATION_FAILED`). The ladder's percentages keep their two places.
