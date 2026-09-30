# Catalogue hygiene: images, duplicates, who moved what, and the range screens

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue's open findings on the catalogue (`pricing/cat-images-and-plan-limits`, `cat-products-and-variants`, `cat-categories-and-brand`, `cat-merchandising-range-review`, `cat-safety-allergens-age-deposit`, `cat-product-lifecycle`) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, pricing domain. Wave 1 fixed cycles, named duplicate codes and the check digit, and deferred the rest (`_notes/2026-09-30-product-pricing.md` §5, §6) |
| **Services** | product-svc owns products, images, categories, the range and planogram data and every rule here · tenant-svc owns the plan and its entitlement keys · the admin app gets the screens |
| **Builds on** | `product_images` (one row per product, bytes in Postgres, under 256 KB, `images.mb.max`), `PUT /admin/products/{id}/image` (`@Consumes` image types), `CategoryRepository.updateCategory` (wave 1: one transaction, `PRODUCT_CATEGORY_CYCLE`), `variants` unique SKU and barcode, `AssortmentResource`/`MerchandisingResource` (API only), the Shelf space screen (`shelf_space_screen.dart`), `Plans.CATALOGUE` and `Entitlements` |
| **Built in** | not built |

## Problem

The catalogue is where mistakes are cheap to make and expensive to find:

- **Images are stored as uploaded.** The server only refuses anything of 256 KB or more, so the admin app has to shrink each picture, another client cannot, and the storefront downloads the full image for a small tile. There is one image per product, so there is no gallery, and therefore nothing for a plan to count.
- **Duplicates are found one write at a time.** The same GTIN under two SKUs is refused when typed, but a business that imported years of data before the rule, or that has near-copies (same name and pack under two codes), has no way to see them.
- **A category can be moved without a trace.** Who put "Frozen" under "Bakery", and when, is not recorded; the row keeps only its last `updated_at`.
- **Range reviews and planograms have no screen.** The API can draft a review, decide keep/introduce/delist per line, publish a planogram and complete a reset, but the admin app only shows fixtures and the range changes that are due.
- **An unreachable code.** The service can say `PRODUCT_IMAGE_TYPE_INVALID`, but the endpoint declares the three image types it consumes, so JAX-RS answers `415` first and the code never reaches a client.
- **A laxer age rule needs a reason.** Setting an age restriction below the business's policy floor is refused, but a decision to tighten or relax carries no recorded reason.

## Outcome

- A picture is uploaded once and the server makes the sizes the shop needs; a product can hold several images in an order up to a limit the plan sets.
- A manager can run a duplicate report and see the SKUs, barcodes and near-copies to merge or retire.
- Every category move shows who, when, from where and to where.
- A range review and a planogram can be done in the admin app.
- A wrong image type answers with the code the API promises; the age-restriction change says why.

## Already there

- Cycle refusal on a category move (`409 PRODUCT_CATEGORY_CYCLE`, a parent from another business `400 PARENT_NOT_FOUND`), racing re-parents (`CategoryTreeTest`, `CatalogGuardsIT.aCategoryCycleIsRefused`, `.racingReparentsCannotCreateACycle`, `.categoriesDoNotCrossBusinesses`).
- Named duplicates at write time: `409 PRODUCT_SKU_DUPLICATE` / `PRODUCT_BARCODE_DUPLICATE` on create, update and import (`VariantDuplicateCodeTest`, `CatalogGuardsIT.duplicateSkuAndBarcodeAreNamed`); a GTIN-shaped barcode must carry a valid check digit, `400 PRODUCT_BARCODE_INVALID` (`VariantBarcodesTest`, `CatalogGuardsIT.aGtinShapedBarcodeIsCheckedOthersAreNot`).
- A delisted variant does not scan (`CatalogGuardsIT.aDelistedVariantDoesNotScan`); the lifecycle catalogue's CAT-07 gap was stale.
- Image boundaries: exactly 256 KB is `400 PRODUCT_IMAGE_TOO_LARGE`, empty `400 PRODUCT_IMAGE_EMPTY`, another business's product `404` (`CatalogGuardsIT.imageBoundaries`); the byte plan cap `images.mb.max` (`ImageStorageCapIT`, `PlanIT`).
- The range and planogram rules on the server (only one draft per fixture, publish supersedes, own-brand delist needs a note, close produces a `RangeOutcome`, OWNER/MANAGER on every write): `MerchandisingIT`, `AssortmentIT`, `AssortmentTest`, `MerchandisingTest`.
- **Screens, partly:** the Shelf space screen (`frontends/storeql-app/lib/features/admin/shelf_space_screen.dart`, `shelf_space_screen_test.dart`) already lists fixtures and the range changes that are due. The catalogue's "no screen found" is therefore only true for reviews, clusters, planogram positions and publish, and resets.
- The per-product image count is moot today only because a product holds one image; it matters once slice 2 lets it hold several.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **catalogue manager** and **range/merchandising manager** (all of it), the **owner** (plan, approvals), the **shopper** (sees the sizes).
- **Channels:** back office; the storefront reads the image sizes.
- **Scope:** per business; range clusters and planograms per store cluster and fixture as they are.
- **Roles that can write:** OWNER, MANAGER (STOREKEEPER for images and the duplicate report's read, as today); reading the report needs management.
- **Sandbox tenant:** behaves the same.

## Scope

- **In**, in build order (each slice ships on its own):
  1. **Small fixes (product-svc).** (a) The image endpoint accepts any content type and the service checks it, so a non-image answers `400 PRODUCT_IMAGE_TYPE_INVALID` as documented (the content is also sniffed by its first bytes, so a text file sent as `image/png` is refused the same way). (b) An age-restriction setting that departs from the business's own policy floor carries a **reason** (`400 PRODUCT_AGE_RESTRICTION_REASON_REQUIRED`), kept with who and when, in the existing age-restriction record; a change is refused today only for a laxer number and that stays.
  2. **A category move is on the record (product-svc).** `category_moves` (append-only): category, old parent, new parent, actor, at. Written on the same transaction as the update in `updateCategory` only when the parent actually changes. `GET /admin/categories/{id}/history` (management, cursor) and a "History" action on the Categories screen. Renames keep `updated_at` only.
  3. **The duplicate report (product-svc).** `GET /admin/catalogue/duplicates` (management, cursor) returns groups: **same barcode under different SKUs across products' variants that predate the unique rule** (kept for data loaded before it, and any that slipped through), **same SKU differing only in case, spaces or punctuation** (a normalised comparison), and **near-copies**: same brand, same normalised name and the same measure under different products. "Near" is fixed by that stated rule, no fuzzy threshold to tune; each group names the products and variants and their status. The report only reads; nothing is merged or deleted here (a merge is a person's act with today's edit and delist). Screen: Admin → Products → "Duplicates" with links to each item.
  4. **Server-made image sizes (product-svc).** On upload the server decodes the image (JPEG, PNG; WebP through a decoder library added once in the parent pom's managed versions, per the dependencies rule) and stores the original (still under the 256 KB ceiling for the upload) plus a **thumbnail and a card size**, each with its own bytes and content type in `product_image_sizes` (append-only replace-by-delete of the whole set on a new upload, in one transaction). `GET /catalog/products/{id}/image?size=thumb|card|full` serves the size, defaulting to `full`; long cache headers with the image's version. The size widths are platform settings tied to the storefront layout, not business policy. An image that cannot be decoded is `400 PRODUCT_IMAGE_UNREADABLE`. The admin app keeps its own compression (a smaller upload is faster) but the server no longer depends on it. The storefront tile requests `thumb`, the product page `card`.
  5. **A gallery and a per-product image count limit (product-svc, tenant-svc).** A product may hold several images in an order: `product_images` moves from one row per product to a numbered set (`position`, one marked primary); the existing single-image routes keep meaning "the primary image". `POST /admin/products/{id}/images`, `PUT …/images/order`, `DELETE …/images/{imageId}`. A new plan entitlement `images.per-product.max` is added to `Plans.CATALOGUE` **with the refusal that enforces it, in product-svc** (the service that owns what is counted), through `Entitlements`: the ceiling is the plan's and is unlimited until a plan sets it (`409 PLAN_LIMIT_REACHED` naming the key, as products and megabytes do). The existing byte cap `images.mb.max` still counts every image and size's original bytes (derived sizes are not counted against a plan; they are the platform's cost of serving). Fail open when the plan is unreadable, as every limit does.
  6. **Range reviews and planograms in the admin app (Flutter).** Extend the Shelf space screen (or its neighbours under Admin → Inventory or Products): **Clusters** (create, add stores), **Reviews** (start, add lines with figures, decide KEEP/INTRODUCE/DELIST per line, the own-brand delist note field, close showing the `RangeOutcome` with what was left alone and why, abandon), **Planograms** (fixture, draft, set facings with a live shelf-capacity meter, publish showing which prior one it supersedes), **Resets** (schedule, complete). Every server refusal shown in words (`status_labels.dart`), the shell's adaptive widgets used (UI-GUIDE §7.2). No new server rule.
- **Out, on purpose:**
  - **Approval of a delist of a fast-moving line, or of a review that delists many own-brand lines at once.** One mechanism on the approvals page (action keys `catalog.discontinue` and `catalog.range-review-close`, which the approvals catalogue adopted as written; this page first spelled them `catalogue.delist-fast-moving` and `catalogue.bulk-delist`). This page adds only the range screen where the approval is asked for.
  - **Merging duplicates automatically.** The report finds; a person decides (a merge moves stock, prices and history across services).
  - **An object store or CDN.** Bytes stay in Postgres (the design comment on `product_images`); the sizes make that affordable and a CDN is an infrastructure choice.
  - **Video, 360 views or documents.** Images only.
  - **Recall `source` naming only two regulators.** A separate known follow-up (memory: recall-source-uk-only), in inventory-svc's recall data, not this page.
  - **Editing category names with history.** Only a move (a change of parent) is recorded; a rename keeps `updated_at`.

## Data and flow

- **Owned by product-svc:**
  - `category_moves` (new, append-only): id, tenant, category, from_parent, to_parent, actor, at; index on tenant first.
  - `product_image_sizes` (new): image id, size (`THUMB`, `CARD`), content type, bytes, width, height.
  - `product_images` gains `id`, `position`, `is_primary`, `version`; existing rows become position 0 and primary. The 256 KB check and `tenant_id` first index stay.
  - `age_restriction_reasons` or a `reason` on the existing age-restriction record: reason text, actor, at.
  - No new table for the duplicate report: it is a read over `variants` and `products`.
- **Owned by tenant-svc:** the `images.per-product.max` entry in `Plans.CATALOGUE` (owner listed as product-svc) and the plan-grant editing that already exists.
- **Needs from other services:** `Entitlements` (a cached read of the plan's allowances) in product-svc; nothing else. No joins.
- **Events published:** none new. The existing product/variant events are unchanged (a gallery does not change what others need; `ProductImageChanged`, if the storefront cache already listens to one, keeps firing on any primary-image change).
- **Retryable writes (Idempotency-Key):** the image uploads (a replay adds no second image: the key names the upload).
- **New error codes:** `400 PRODUCT_IMAGE_UNREADABLE`, `409 PLAN_LIMIT_REACHED` (existing code, new key `images.per-product.max`), `400 PRODUCT_AGE_RESTRICTION_REASON_REQUIRED`, `404 PRODUCT_IMAGE_NOT_FOUND` (existing), `400 PRODUCT_IMAGE_TYPE_INVALID` (now reachable).

## Money, time and limits

- **Currency:** none.
- **Ledger postings:** none.
- **Dates:** all UTC; move times and reasons in `timestamptz`.
- **Plan limits:** `images.per-product.max` (product-svc refuses; unlimited until set) next to the existing `images.mb.max`. No number is chosen by the platform.

## Constraints

- Golden rules 1 (no cross-service tables), 3 (tenant from the JWT, first condition on every query), 8 (the move log is append-only), 9 and 10 (thin resources, DTOs), 15 (validate input; the type check is by sniffing as well as the header).
- Images are read back in full by the storefront: the size a tile asks for must be the small one, or the change does nothing for shoppers. Long-lived cache headers stay; the version in the address keeps them right.
- Dependencies rule (CLAUDE.md 22.11): a WebP decoder is added once in the parent pom with its advisory checked (`scripts/vuln-scan.sh deps`), never pinned in the module.
- Existing tenants: existing images become a primary image with sizes made lazily on first read (a small job backfills them); no upload path changes for a client that sends one image.

## Open questions

- [x] What is "near" for a duplicate? → **A fixed rule, no threshold: same brand, same normalised name and measure under different products; codes equal after normalising case, spaces and punctuation** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Do derived sizes count against the plan's megabytes? → **No; only what the business uploaded counts** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How many images may a product hold? → **The plan's `images.per-product.max`, unlimited until it sets one** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should the report merge? → **No; a person merges** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Where do the range screens live? → **On the Shelf space screen and its tabs in the admin shell** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] A text file sent as `image/png` and a body of another type both answer `400 PRODUCT_IMAGE_TYPE_INVALID`; nothing is stored — `ProductImageIT.aNonImageIsRefusedByName` (CAT-60)
- [ ] An age-restriction change without a reason is `400 PRODUCT_AGE_RESTRICTION_REASON_REQUIRED`; with one it is stored with actor and time — `ProductSafetyIT.anAgeChangeNeedsAReason`
- [ ] Moving a category records who, when, from and to; a rename does not; a refused (cyclic) move records nothing — `CategoryMoveIT.aMoveIsOnTheRecord`
- [ ] The move log is append-only and tenant-first: another business's OWNER/MANAGER gets 404 and CASHIER 403 — `CategoryMoveIT.isolationAndRoles`
- [ ] The duplicate report finds the same barcode under two SKUs loaded before the rule, case-only SKU twins and near-copies, and lists nothing for a clean catalogue — `DuplicateReportIT.findsWhatWritesNoLongerAllow`, `DuplicateRuleTest.*` (pure)
- [ ] The report never crosses businesses — `DuplicateReportIT.otherBusinessesAreNeverCompared`
- [ ] An upload makes thumb and card sizes; `?size=` serves each with the right type and cache header; an undecodable image is `400 PRODUCT_IMAGE_UNREADABLE` — `ImageSizesIT.*`, `ImageSizingTest.*` (pure: aspect ratio kept, never enlarged)
- [ ] A second and third image are kept in order with one primary; the single-image routes keep serving the primary — `GalleryIT.*`
- [ ] With `images.per-product.max` set to 2 the third is `409 PLAN_LIMIT_REACHED`; with none set nothing is refused; an unreadable plan fails open — `GalleryIT.theCountLimitHolds`, `EntitlementsTest.*`
- [ ] The megabyte cap still counts uploads and not derived sizes — `ImageStorageCapIT.derivedSizesAreNotCounted`
- [ ] `images.per-product.max` appears in the plan grants and is refused for an unknown key elsewhere — `PlanIT.theNewKeyIsInTheCatalogue`
- [ ] Flutter: categories History, the Duplicates list, review (decide, note, close), planogram (capacity meter, publish), reset — `categories_history_test.dart`, `duplicates_screen_test.dart`, `range_review_screen_test.dart`, `planogram_screen_test.dart`
- [ ] End to end: import, duplicate report, review closed into range actions — k6 `catalogue-hygiene-flow`

## Decisions

<!-- Filled while building. -->
