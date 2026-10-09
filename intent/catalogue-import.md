# Catalogue import: a supermarket's own export in, checked first, applied safely, reconciled to the penny

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the UK pilot assessment · 2026-10-09 |
| **Roadmap** | new: pilot gate, importer slices P1 to P4 in the merged plan |
| **Services** | product-svc owns the import job, its mapping, files, chunks, row results and barcode aliases · pricing-svc takes VAT categories and prices · inventory-svc takes opening stock · purchase-svc is read for supplier matching · the admin app |
| **Builds on** | product-svc `/admin/import`, `ProductService` bulk import, `product_variants` (barcode, GTIN-14), the GS1 scan, `ProductVatCategoryResource`, `PricingClient`, inventory `receive` and `insertBatch`, the recall gate and lock order, `Entitlements`, the sandbox tenant, [vat-inclusive-pricing](vat-inclusive-pricing.md) |
| **Built in** | product-svc V17 + `domain/imports`, `ImportService/Applier/Worker/Reconciler`, `ImportResource`; inventory-svc V36 + `OpeningStock*`; `feat/pilot-gate` (P1 to P3; the 25,000-row timing run and the app screens are P4) |

## Problem

A supermarket leaving another till system has 8,000 to 20,000 lines in an export. StoreQL's admin import reads a supplier CSV (it sets no barcode, a case unit, no VAT, no sold-by, no cost, flat categories); the JSON import has no VAT, price, cost or sold-by; VAT categories are set one variant per request, and an item without one is silently charged the standard rate; the REPLACE mode orphans prices and stock; and nothing loads opening stock. Without a supermarket importer the customer's first fortnight is spent typing, and every mistake is a wrong price or a wrong rate at a till.

## Outcome

The owner uploads the export once per store, maps its columns once per business, sees a dry run that names every row it would create, change, skip or refuse and why plus a go-live gap list, applies it in restart-safe chunks, and gets a reconciliation of file against loaded (rows, barcodes, prices by VAT code, stock quantity and value) that the accountant can sign. Running the second store's file reports mostly UNCHANGED products and adds only its own price and stock. Re-running a corrected file updates in place; nothing is orphaned.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the owner or head-office manager doing the cut-over; the accountant signing the reconciliation.
- **Channels:** admin (API and, later, a wizard); first runs are driven by API or k6. **Scope:** per business, one store per file.
- **Roles that can write:** OWNER or MANAGER held to no store (`BUSINESS_WIDE_ONLY` otherwise); each phase also needs what the manual act needs (`pricing.write` for VAT and prices, `stock.adjust` for stock). No new permission key.
- **Sandbox tenant:** the same code path; the rehearsal is the same file and mapping run against the business's sandbox.

## Scope

- **In:** a pure CSV reader (BOM, UTF-8 then Windows-1252 then UTF-16, delimiter sniffed, quoted newlines), a per-business named column mapping matched by header text, a mandatory dry run, restart-safe chunked apply (500 rows), update-in-place by SKU/PLU, barcode aliases (old EAN, multipack, case, PLU), VAT-code mapping through one batch call, prices in the list's tax mode, opening stock through `insertBatch` and putaway, the reconciliation report, the first-run job tables.
- **Out, on purpose:**
  - **Native `.xlsx`.** The screen says "save as CSV"; Excel's corruptions (a barcode as `5.01E+12`, a lost leading zero) are detected and refused or flagged.
  - **The import wizard in Flutter**, the rehearse-first banner and a 20,000-row timing run: the first dry run is driven by API or k6 and the real-file hardening happens during the dry run (slice P4).
  - **Creating suppliers**, per-store price zones when both stores charge the same, and allergen data beyond optional columns: a food department with no allergen data goes live `UNDECLARED` and is listed in the gap list; resolving it is a go-live task for the customer.
  - **A cost on an item with no stock**: reported "cost not held" and dropped (cost lives on a batch).

## Data and flow

- **Owned by** product-svc: `import_files`, `import_mappings`, `import_jobs`, `import_chunks`, `import_row_results` and `variant_barcode_aliases` (unique per business); inventory-svc: `opening_stock_loads`. `import_files`, `import_row_results`, `opening_stock_loads` and `variant_barcode_aliases` are append-only; the job and chunk tables are progress records. Every table has `tenant_id` first, a composite index, UUIDv7 ids and named constraints.
- **Needs from other services:** pricing-svc (VAT rates, price list, batch VAT and price calls), inventory-svc (opening stock call, summary for the job), purchase-svc (read-only supplier match): all REST, never a join, each call with a derived `Idempotency-Key` (`Ids.derived(jobId, phase:seq)`).
- **Events published:** the existing `StockReceived` for each opening batch, the product and price events the owning services already publish; no new event.
- **Retryable writes** (Idempotency-Key): create, dry-run, apply and cancel of a job; upload dedupes on the file's sha256 per business.
- **New error codes:** `IMPORT_DRY_RUN_REQUIRED` (409, apply without a dry run of the same file hash and mapping), `IMPORT_JOB_EXPIRED` (409, 24 hours after the last progress), `IMPORT_PRICE_BASIS_MISMATCH` (409), `IMPORT_VAT_CODE_UNMAPPED`, `INVENTORY_OPENING_ALREADY_LOADED` (409), plus row-level reasons in the report.

## Money, time and limits

- **Currency:** the business's own; typed money finer than its minor units is refused, never rounded. A shelf price is loaded as typed and never converted; the mapping's `priceBasis` must match the list's tax mode.
- **Ledger postings:** opening stock posts like any receipt (stock against the opening-balance account chosen by the accountant); none is added by the import itself.
- **Dates:** expiry in the mapping's date format; job times UTC.
- **Plan limits:** `PRODUCTS_MAX` is checked at the dry run (`PLAN_LIMIT_REACHED`). File up to 12 MB, up to 25,000 data rows, one APPLYING job per business.

## Constraints

- Lands before the first release tag: its schema (product V17, inventory V36) is the last fold, see [forward-only-migrations](forward-only-migrations.md). After the tag the same changes are additive files.
- Opening stock goes through `insertBatch` (recall gate, hold on arrival, putaway, duty and expiry rules) and the lock order in CLAUDE.md, never a raw multi-row insert.
- A worker acts only under the job row's tenant and the starter's `Caller` headers (no token stored). The advisory-lock key includes the tenant.
- Barcode and alias comparison is as GTIN-14, so an EAN-13 and a GTIN-14 case code match.

## Open questions

1. **Which till system and which export?** For the customer; a real export from each store, one with weighed and 2xx items, decides the final mapping. The build proceeds on a documented generic mapping.
2. **What do the customer's VAT codes mean** (letters or numbers; reduced, zero, exempt)? The dry run lists each distinct code with its row count and refuses unmapped rows; the legend comes from the customer.
3. **How are weighed items coded on the scale labels** (prefix, digits for the item, price- or weight-embedded)? A weighed item's PLU, zero-padded to the label scheme's width, is stored as its barcode; a full price- or weight-embedded code is never stored. The scheme comes from the customer's scale.
4. **Stock on hand: with expiry and unit cost? A stock take at cut-over?** Recommended: a stock take at cut-over; the import's opening stock is the starting point it corrects.
5. **Do both stores charge the same prices?** If they differ, the second store's differing prices go to a price zone list; equal prices are UNCHANGED.
6. **One bulk call for VAT categories.** → **`POST /product-vat-categories/batch`, 500 rows, per-row results, one price catch-up per call** (shared with the readiness fixer in [vat-inclusive-pricing](vat-inclusive-pricing.md)). (Claude, 2026-10-09)
7. **Which owner and which service runs the job?** → **product-svc, durable orchestration**: phases products, VAT, prices, stock, reconcile, in 500-row chunks each a row in `import_chunks`; the worker claims a job with `FOR UPDATE SKIP LOCKED` and a 60-second lease and resumes the first unfinished chunk. (by industry standard for multi-service imports without a join. Claude, 2026-10-09)

## Acceptance

- [x] The reader handles BOM, three encodings, four delimiters, quoted newlines, a barcode in exponent form (refused) and a lost leading zero (flagged) — `CsvTableTest`, `GtinTest`.
- [x] The dry run writes only `import_row_results`; it reports created, updated, unchanged, skipped and refused with row numbers and reasons, and lists the go-live gaps (no barcode, no category, stocked item with no cost, food with no allergens, unknown supplier, no VAT rate configured for a mapped code) — `ImportDryRunIT`, `DryRunTest`.
- [x] A row is refused, and nothing of it is written, for: no SKU, a conflicting repeat, a bad GTIN check digit, a barcode held by another variant or alias, an unmapped VAT code, a missing or over-precise price, a weighed item with no unit, a label-style 2xx code used as a barcode — `ImportRowsTest`, `DryRunTest`, `ImportDryRunIT`.
- [x] Apply needs a dry run of the same file hash and mapping (`IMPORT_DRY_RUN_REQUIRED`), cannot run twice at once per business, expires after 24 hours, and a killed worker resumes at the first unfinished chunk with no row written twice — `ImportApplyIT` (needs, once-at-a-time, expiry, resume after a peer was down, refusal fails the job, chunks of 500).
- [x] Re-importing a corrected file updates in place by SKU: a blank cell never erases, an identical row is UNCHANGED, nothing is orphaned (no price or stock lost) — `ImportApplyIT.reimport`.
- [x] The saved mapping applies to the second store's file by header text with no re-mapping — `ImportMappingTest` (columns in another order, spelled differently; a missing header is named, never guessed).
- [x] VAT codes map through the batch call; an unmapped code refuses its rows; a code with no configured VAT rate is a gap and the quote refusal is predicted — `ImportApplyIT.applyHappyPath`, `DryRunTest`.
- [x] A price whose basis does not match the list's tax mode is refused (`IMPORT_PRICE_BASIS_MISMATCH`); a price is never converted — `ImportApplyIT.priceBasisMismatch`.
- [x] Opening stock lands as received batches with expiry through `insertBatch` and putaway (recall gate and holds respected), once per (store, variant) per job, and a recalled lot in the file is held — `OpeningStockIT` (inventory-svc: batches, movement, event, putaway, recalled lot held, once per store and item), `ImportReconcileIT` (the STOCK phase and a lost answer).
- [x] The reconciliation report gives file against loaded per measure (rows, SKUs, barcodes, price count and sum by VAT code, stock quantity, stock value) and names every unmatched SKU — `ReconciliationTest`, `ImportReconcileIT`.
- [x] A scanned old EAN, multipack or case code finds the variant with its pack quantity; aliases are unique per business and compared as GTIN-14 — `BarcodeAliasIT`.
- [x] **Tenant isolation:** the same file in two businesses makes two files with no existence leak; the lock key includes the tenant; an alias is unique per business, not globally; a worker never acts outside its job's tenant; other businesses' staff of every role, even naming our job id, get 404 — `ImportDryRunIT`, `ImportApplyIT.isolation`, `ImportReconcileIT.whoAndWhat/perBusiness`, `OpeningStockIT.otherBusinessCannotOpenOurStore/perBusinessAndSummary`, `BarcodeAliasIT.perBusiness/noLeak`.
- [ ] A 12 MB, 25,000-row file imports within the agreed time on the stack and reconciles — k6 `catalogue-import-flow`.

## Screens

None in this tranche (the wizard is slice P4).

## Decisions

- **One import path, owned by product-svc.** The old REPLACE mode is not used by it; the legacy supplier CSV keeps its own path and is fenced from the price step when the target list is tax-inclusive.
- **Orchestration, not events:** the importer is the one place that knows the order (products, then VAT, then prices, then stock) and must report one reconciliation, so it calls the owners with derived idempotency keys and reads their summaries back.
- **Business key `(tenant, SKU)`;** only mapped, non-blank cells change a field.
- **The dry run is mandatory and its report is the artefact the customer reads.**
- **Opening stock reuses the recall gate and lock order** because the lock-order change (PR #83) makes a raw insert wrong.
- **No default VAT rate** unless the mapping names one explicitly; the importer never guesses.
- **Cost is a batch's cost.** An unstocked item has none to hold.
- **Opening stock is opened once per (store, item), ever** (`opening_stock_loads`, unique on tenant, store, variant). A wrong opening is corrected by an adjustment or a stock take, never by loading it again, because the other mistake doubles the shelf silently. The opening's identity is the **dry run's id**, so applying the same dry run again after a failure finds its own work (`REPLAYED`) and a corrected file's new dry run meets `ALREADY_OPENED` for those lines: left as they are, said so in the apply's report, and shown short by the reconciliation. This replaces "per job" in the first draft.
- **The reconciliation is read again from the systems, not kept from the apply**: the file is re-read, SKUs and barcodes are looked up in the catalogue, prices are read back from the price list, stock is read from inventory-svc's totals for the dry run. Figures are exact (no rounding), prices are summed by the file's VAT code, a line with no cost is counted apart and never valued at nothing.
- **A `lot` column** (optional, 64 characters) rides with the stock so the recall gate can hold a recalled lot at the door; a file with no lot opens batches with none.
- **Putaway is not skipped.** Opening stock goes through `insertBatch`, so a store with a default putaway rule places it at once and a store without raises one task per batch. Go-live therefore sets a store-default putaway rule first (a checklist item with the customer's zone names).
- **A scan finds an alias after the item's own barcode and before the typed SKU**; the answer says the kind and the units one scan stands for (`alias: {kind, packQty}`), and an alias of a delisted item finds nothing.

## Flow Tests entry

Adds a "Catalogue import" flow under Catalogue, pricing and promotions; cases are added with the build.
