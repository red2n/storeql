package com.storeql.product.service;

import static java.util.stream.Collectors.toSet;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain;
import com.storeql.product.domain.Domain.Brand;
import com.storeql.product.domain.Domain.CatalogGroup;
import com.storeql.product.domain.Domain.CatalogGroupElement;
import com.storeql.product.domain.Domain.Category;
import com.storeql.product.domain.Domain.CategorySet;
import com.storeql.product.domain.Domain.CategorySetMember;
import com.storeql.product.domain.Domain.ContainerType;
import com.storeql.product.domain.Domain.ItemAttributeGroup;
import com.storeql.product.domain.Domain.ItemAttributeGroupField;
import com.storeql.product.domain.Domain.ItemCrossReference;
import com.storeql.product.domain.Domain.ItemRelationship;
import com.storeql.product.domain.Domain.ItemRevision;
import com.storeql.product.domain.Domain.ItemTemplate;
import com.storeql.product.domain.Domain.ItemTemplateApplication;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.domain.Domain.UomClass;
import com.storeql.product.domain.Domain.UomDefinition;
import com.storeql.product.domain.Domain.UomItemConversion;
import com.storeql.product.domain.Domain.Variant;
import com.storeql.product.domain.Domain.VariantAttributeGroupValues;
import com.storeql.product.domain.Domain.VariantCatalogAssignment;
import com.storeql.product.domain.Domain.VariantCategorySetAssignment;
import com.storeql.product.domain.Domain.VariantContainerLink;
import com.storeql.product.dto.Dtos.AddCategorySetMemberRequest;
import com.storeql.product.dto.Dtos.AllergenDeclarationRequest;
import com.storeql.product.dto.Dtos.AssignCatalogGroupRequest;
import com.storeql.product.dto.Dtos.AssignVariantCategorySetRequest;
import com.storeql.product.dto.Dtos.BulkImportError;
import com.storeql.product.dto.Dtos.BulkImportRequest;
import com.storeql.product.dto.Dtos.BulkImportResult;
import com.storeql.product.dto.Dtos.ConvertResult;
import com.storeql.product.dto.Dtos.CreateBrandRequest;
import com.storeql.product.dto.Dtos.CreateCatalogGroupElementRequest;
import com.storeql.product.dto.Dtos.CreateCatalogGroupRequest;
import com.storeql.product.dto.Dtos.CreateCategoryRequest;
import com.storeql.product.dto.Dtos.CreateCategorySetRequest;
import com.storeql.product.dto.Dtos.CreateItemCrossReferenceRequest;
import com.storeql.product.dto.Dtos.CreateItemRelationshipRequest;
import com.storeql.product.dto.Dtos.CreateProductRequest;
import com.storeql.product.dto.Dtos.CreateVariantRequest;
import com.storeql.product.dto.Dtos.SetAgeRestrictionRuleRequest;
import com.storeql.product.dto.Dtos.UpdateBrandRequest;
import com.storeql.product.dto.Dtos.UpdateCatalogAssignmentRequest;
import com.storeql.product.dto.Dtos.UpdateCategoryRequest;
import com.storeql.product.dto.Dtos.UpdateCategorySetRequest;
import com.storeql.product.dto.Dtos.UpdateProductRequest;
import com.storeql.product.dto.Dtos.UpdateVariantRequest;
import com.storeql.product.dto.Dtos.VariantComplianceRequest;
import com.storeql.product.repo.BrandRepository;
import com.storeql.product.repo.CatalogGroupRepository;
import com.storeql.product.repo.CategoryRepository;
import com.storeql.product.repo.CategorySetRepository;
import com.storeql.product.repo.ComplianceRepository;
import com.storeql.product.repo.ContainerTypeRepository;
import com.storeql.product.repo.ItemAttributeGroupRepository;
import com.storeql.product.repo.ItemCrossReferenceRepository;
import com.storeql.product.repo.ItemRelationshipRepository;
import com.storeql.product.repo.ItemRevisionRepository;
import com.storeql.product.repo.ItemTemplateRepository;
import com.storeql.product.repo.ProductRepository;
import com.storeql.product.repo.UomRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Catalog business logic. Publishes catalog events via the outbox (golden rule #6). */
@ApplicationScoped
public class ProductService {

  @Inject ProductRepository repo;
  @Inject com.storeql.service.Entitlements entitlements;
  @Inject BrandRepository brandRepo;
  @Inject CategoryRepository categoryRepo;
  @Inject UomRepository uomRepo;
  @Inject ItemRevisionRepository itemRevisionRepo;
  @Inject ItemCrossReferenceRepository crossReferenceRepo;
  @Inject ItemRelationshipRepository itemRelationshipRepo;
  @Inject ItemTemplateRepository itemTemplateRepo;
  @Inject CatalogGroupRepository catalogGroupRepo;
  @Inject ContainerTypeRepository containerTypeRepo;
  @Inject ItemAttributeGroupRepository itemAttributeGroupRepo;
  @Inject CategorySetRepository categorySetRepo;
  @Inject ComplianceRepository complianceRepo;
  @Inject com.storeql.product.client.InventoryClient inventoryClient;
  @Inject com.storeql.product.client.PricingClient pricingClient;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject com.storeql.service.Jurisdictions jurisdictions;
  @Inject com.storeql.product.repo.ProductSafetyRepository safetyRepo;

  // ─────────────────────────────────────────────────────────────────── brands

  /**
   * Creates a brand.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created brand
   */
  public Brand createBrand(UUID tenantId, CreateBrandRequest req) {
    return brandRepo.createBrand(tenantId, req.name().trim());
  }

  /**
   * Reads a brand.
   *
   * @param tenantId owning tenant
   * @param id the brand to act on
   * @return the brand
   * @throws ApiException a 404 when no such brand exists in this tenant
   */
  public Brand getBrand(UUID tenantId, UUID id) {
    return brandRepo
        .findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  /**
   * Lists the tenant's brands.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<Brand> listBrands(UUID tenantId) {
    return brandRepo.listBrands(tenantId);
  }

  /**
   * Renames a brand.
   *
   * @param tenantId owning tenant
   * @param id the brand to act on
   * @param req the request body carrying the new values
   * @return the renamed brand
   * @throws ApiException a 404 when no such brand exists in this tenant
   */
  public Brand renameBrand(UUID tenantId, UUID id, UpdateBrandRequest req) {
    getBrand(tenantId, id);
    return brandRepo.updateBrand(tenantId, id, req.name().trim());
  }

  /**
   * Deactivates a brand, leaving the row in place.
   *
   * @param tenantId owning tenant
   * @param id the brand to act on
   * @return the brand in its deactivated state
   * @throws ApiException a 404 when no such brand exists in this tenant
   */
  public Brand deactivateBrand(UUID tenantId, UUID id) {
    getBrand(tenantId, id);
    return brandRepo.deactivateBrand(tenantId, id);
  }

  // ──────────────────────────────────────────────────────────────── categories

  /**
   * Creates a category.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created category
   */
  public Category createCategory(UUID tenantId, CreateCategoryRequest req) {
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    return categoryRepo.createCategory(tenantId, parentId, req.name().trim());
  }

  /**
   * Reads a category.
   *
   * @param tenantId owning tenant
   * @param id the category to act on
   * @return the category
   * @throws ApiException a 404 when no such category exists in this tenant
   */
  public Category getCategory(UUID tenantId, UUID id) {
    return categoryRepo
        .findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  /**
   * Lists the tenant's categories.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<Category> listCategories(UUID tenantId) {
    return categoryRepo.listCategories(tenantId);
  }

  /**
   * Updates a category.
   *
   * @param tenantId owning tenant
   * @param id the category to act on
   * @param req the request body carrying the new values
   * @return the updated category
   * @throws ApiException a 404 when no such category exists in this tenant
   */
  public Category updateCategory(UUID tenantId, UUID id, UpdateCategoryRequest req) {
    Category existing = getCategory(tenantId, id);
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    Category updated = categoryRepo.updateCategory(tenantId, id, req.name().trim(), parentId);
    // A category moved under a new parent changes the path of every product beneath it; the
    // whole catalogue is re-announced rather than the subtree computed, because a tenant's
    // catalogue is hundreds of products and the arithmetic is not worth being wrong about.
    if (!java.util.Objects.equals(existing.parentId(), parentId)) {
      republishCatalogue(tenantId);
    }
    return updated;
  }

  /**
   * Deactivates a category, leaving the row in place.
   *
   * @param tenantId owning tenant
   * @param id the category to act on
   * @return the category in its deactivated state
   * @throws ApiException a 404 when no such category exists in this tenant
   */
  public Category deactivateCategory(UUID tenantId, UUID id) {
    getCategory(tenantId, id);
    return categoryRepo.deactivateCategory(tenantId, id);
  }

  // ──────────────────────────────────────────────────────────────── products

  /**
   * Creates a product.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created product
   */
  public Product createProduct(UUID tenantId, CreateProductRequest req) {
    // What the business is sold decides how many products it may list (21.8). The count is asked
    // for only when its plan sets a ceiling.
    entitlements.requireRoom(
        tenantId,
        com.storeql.service.Entitlements.PRODUCTS_MAX,
        "products",
        () -> repo.countProducts(tenantId));
    UUID id = Ids.newId();
    Instant now = Instant.now();
    var safety =
        req.safetyInformation() == null
            ? null
            : SafetyInformationRules.normalise(tenantId, id, req.safetyInformation(), null, now);
    if (req.sellableOnline() == null || req.sellableOnline()) {
      requireListable(tenantId, safety);
    }
    var product =
        new Product(
            id,
            tenantId,
            req.name().trim(),
            req.description(),
            parseOptionalUuid(req.brandId(), "brandId"),
            parseOptionalUuid(req.categoryId(), "categoryId"),
            initialStatus(req.status()),
            req.sellableOnline() == null || req.sellableOnline(),
            req.sellablePos() == null || req.sellablePos(),
            now,
            now,
            launchOn(req.status(), req.launchOn()),
            null);
    var event =
        new OutboxRow(
            "ProductCreated",
            "storeql.catalog.product-created",
            tenantId,
            id,
            Events.productCreated(tenantId, id, product.name()));
    return repo.createProductWithOutbox(
        product,
        List.of(event, categorised(tenantId, id, product.categoryId(), List.of())),
        safety);
  }

  /**
   * Updates a product.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @param req the request body carrying the new values
   * @return the updated product
   * @throws ApiException a 404 when no such product exists in this tenant
   */
  public Product updateProduct(UUID tenantId, UUID productId, UpdateProductRequest req) {
    Product existing =
        repo.findProduct(tenantId, productId)
            .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No such product"));
    var updated =
        new Product(
            existing.id(),
            tenantId,
            req.name().trim(),
            req.description(),
            parseOptionalUuid(req.brandId(), "brandId"),
            parseOptionalUuid(req.categoryId(), "categoryId"),
            existing.status(),
            req.sellableOnline(),
            req.sellablePos(),
            existing.createdAt(),
            Instant.now(),
            existing.launchOn(),
            existing.discontinuedAt());
    var event =
        new OutboxRow(
            "ProductUpdated",
            "storeql.catalog.product-updated",
            tenantId,
            productId,
            Events.productUpdated(tenantId, productId, updated.status()));
    // Always announced, not only when the category changed: a consumer that missed an earlier
    // announcement catches up on the next, and a category removed is a change too.
    List<UUID> variantIds =
        repo.listVariants(tenantId, productId).stream().map(Variant::id).toList();
    return repo.updateProductWithOutbox(
        updated,
        List.of(event, categorised(tenantId, productId, updated.categoryId(), variantIds)),
        stored -> {
          if (updated.sellableOnline() && Product.STATUS_ACTIVE.equals(updated.status())) {
            requireListable(tenantId, stored);
          }
        });
  }

  // ── the catalogue event (03.8) ─────────────────────────────────────────────

  /**
   * The category path a product sits on, from its own category up to the root. Empty when it has
   * none; a cycle or a missing parent ends the walk rather than the request.
   */
  List<UUID> categoryPath(UUID tenantId, UUID categoryId) {
    List<UUID> path = new java.util.ArrayList<>();
    UUID current = categoryId;
    while (current != null && path.size() < 32 && !path.contains(current)) {
      path.add(current);
      current = categoryRepo.findCategory(tenantId, current).map(Category::parentId).orElse(null);
    }
    return path;
  }

  private OutboxRow categorised(
      UUID tenantId, UUID productId, UUID categoryId, List<UUID> variantIds) {
    return new OutboxRow(
        "ProductCategorised",
        "storeql.catalog.product-categorised",
        tenantId,
        productId,
        Events.productCategorised(
            tenantId, productId, categoryPath(tenantId, categoryId), variantIds));
  }

  /**
   * Re-announces every active product's category path and variants (03.8), for a consumer that
   * arrived after the catalogue did — pricing-svc's projection, built on this branch, starts empty
   * for a tenant whose products predate it. Also how a category moved in the tree reaches the
   * products beneath it.
   *
   * @param tenantId owning tenant
   * @return how many products were announced
   */
  public int republishCatalogue(UUID tenantId) {
    var products = repo.listCatalogueForRepublish(tenantId);
    var variantsByProduct = repo.listVariantIdsByProduct(tenantId);
    List<OutboxRow> events = new java.util.ArrayList<>(products.size());
    for (var p : products) {
      events.add(
          categorised(
              tenantId,
              p.productId(),
              p.categoryId(),
              variantsByProduct.getOrDefault(p.productId(), List.of())));
    }
    // Each variant's measure too (03.13), so pricing-svc's unit prices catch up with the catalogue.
    int announced = events.size();
    for (var v : complianceRepo.listMeasureSources(tenantId)) {
      events.add(
          new OutboxRow(
              "VariantMeasured",
              "storeql.catalog.variant-measured",
              tenantId,
              v.variantId(),
              Events.variantMeasured(
                  tenantId,
                  v.variantId(),
                  v.productId(),
                  v.soldBy(),
                  measureOf(v.soldBy(), v.netContent(), v.netContentUom(), v.catchWeight()),
                  v.version())));
    }
    if (!events.isEmpty()) repo.appendOutbox(events);
    return announced;
  }

  /** Delist a product (soft) — sets status DELISTED, publishes ProductDelisted. */
  public Product delistProduct(UUID tenantId, UUID productId) {
    Product existing =
        repo.findProduct(tenantId, productId)
            .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No such product"));
    var delisted =
        new Product(
            existing.id(),
            tenantId,
            existing.name(),
            existing.description(),
            existing.brandId(),
            existing.categoryId(),
            Product.STATUS_DELISTED,
            existing.sellableOnline(),
            existing.sellablePos(),
            existing.createdAt(),
            Instant.now(),
            existing.launchOn(),
            existing.discontinuedAt());
    var event =
        new OutboxRow(
            "ProductDelisted",
            "storeql.catalog.product-delisted",
            tenantId,
            productId,
            Events.productLifecycle(
                "ProductDelisted",
                tenantId,
                productId,
                Product.STATUS_DELISTED,
                repo.variantIdsOf(tenantId, productId)));
    return repo.updateProductWithOutbox(delisted, event);
  }

  // ── item lifecycle: new line → active → discontinued → delisted ──────────

  private static final String LIFECYCLE_TOPIC = "storeql.catalog.product-lifecycle";

  static String initialStatus(String requested) {
    if (requested == null || requested.isBlank()) return Product.STATUS_ACTIVE;
    String s = requested.trim().toUpperCase(java.util.Locale.ROOT);
    if (Product.STATUS_ACTIVE.equals(s) || Product.STATUS_NEW_LINE.equals(s)) return s;
    throw ApiException.badRequest(
        "PRODUCT_STATUS_INVALID", "a product is created ACTIVE or as a NEW_LINE, not " + s);
  }

  static java.time.LocalDate launchOn(String status, String launchOn) {
    if (launchOn == null || launchOn.isBlank()) return null;
    if (!Product.STATUS_NEW_LINE.equals(initialStatus(status))) {
      throw ApiException.badRequest(
          "PRODUCT_LAUNCH_ON_NEEDS_NEW_LINE",
          "launchOn belongs to a NEW_LINE, not a product on sale");
    }
    try {
      return java.time.LocalDate.parse(launchOn.trim());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400, "PRODUCT_LAUNCH_ON_INVALID", "launchOn must be an ISO date", List.of(), e);
    }
  }

  /**
   * Puts a new line on sale.
   *
   * @throws ApiException {@code 409 PRODUCT_LIFECYCLE_INVALID} unless the product is a NEW_LINE
   */
  public Product launchProduct(UUID tenantId, UUID productId) {
    return move(
        tenantId, productId, Product.STATUS_NEW_LINE, Product.STATUS_ACTIVE, "ProductLaunched");
  }

  /**
   * Marks a line for run-down: sold while stock lasts, never reordered.
   *
   * @throws ApiException {@code 409 PRODUCT_LIFECYCLE_INVALID} unless the product is ACTIVE
   */
  public Product discontinueProduct(UUID tenantId, UUID productId) {
    return move(
        tenantId,
        productId,
        Product.STATUS_ACTIVE,
        Product.STATUS_DISCONTINUED,
        "ProductDiscontinued");
  }

  /**
   * Brings a discontinued line back on sale and into replenishment.
   *
   * @throws ApiException {@code 409 PRODUCT_LIFECYCLE_INVALID} unless the product is DISCONTINUED
   */
  public Product reinstateProduct(UUID tenantId, UUID productId) {
    return move(
        tenantId,
        productId,
        Product.STATUS_DISCONTINUED,
        Product.STATUS_ACTIVE,
        "ProductReinstated");
  }

  private Product move(UUID tenantId, UUID productId, String from, String to, String eventType) {
    Product existing = getProduct(tenantId, productId);
    if (!from.equals(existing.status())) {
      throw ApiException.conflict(
          "PRODUCT_LIFECYCLE_INVALID",
          "a " + existing.status() + " product cannot be moved to " + to + "; it must be " + from);
    }
    Instant now = Instant.now();
    var moved =
        new Product(
            existing.id(),
            tenantId,
            existing.name(),
            existing.description(),
            existing.brandId(),
            existing.categoryId(),
            to,
            existing.sellableOnline(),
            existing.sellablePos(),
            existing.createdAt(),
            now,
            Product.STATUS_ACTIVE.equals(to) ? null : existing.launchOn(),
            Product.STATUS_DISCONTINUED.equals(to) ? now : null);
    var event =
        new OutboxRow(
            eventType,
            LIFECYCLE_TOPIC,
            tenantId,
            productId,
            Events.productLifecycle(
                eventType, tenantId, productId, to, repo.variantIdsOf(tenantId, productId)));
    return repo.updateProductWithOutbox(moved, event);
  }

  /**
   * Reads a product.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @return the product
   * @throws ApiException a 404 when no such product exists in this tenant
   */
  public Product getProduct(UUID tenantId, UUID productId) {
    return repo.findProduct(tenantId, productId)
        .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No such product"));
  }

  // ── product image ─────────────────────────────────────────────────────────

  /**
   * Hard ceiling on a stored product image: every image in the system is strictly smaller than
   * this. Thumbnails only — the stack has no object store, so bytes live in Postgres and are read
   * back in full on every storefront render.
   *
   * <p>Enforced here at the boundary and again as a CHECK constraint on {@code product_images} (see
   * V15), so the invariant holds whichever client writes — the admin app compresses to the same
   * budget before uploading, but nothing may depend on a client having done so.
   */
  public static final int MAX_IMAGE_BYTES = 256 * 1024;

  private static final java.util.Set<String> IMAGE_CONTENT_TYPES =
      java.util.Set.of("image/jpeg", "image/png", "image/webp");

  /**
   * Stores a product image after checking the product exists and the type is allowed.
   *
   * <p>The product is resolved first, so bytes are never accepted for a foreign or unknown product.
   * Only JPEG, PNG and WebP are accepted; any {@code ;charset=} suffix on the content type is
   * stripped before the check.
   *
   * @param tenantId owning tenant
   * @param productId the product to attach the image to
   * @param contentType the declared MIME type
   * @param bytes the raw image bytes
   * @throws ApiException a 404 when the product does not exist in this tenant; {@code
   *     PRODUCT_IMAGE_TYPE_INVALID} (400) when the type is not an accepted image type
   */
  public void uploadProductImage(UUID tenantId, UUID productId, String contentType, byte[] bytes) {
    getProduct(tenantId, productId); // 404 before accepting bytes for a foreign/unknown product
    String normalized =
        contentType == null ? "" : contentType.trim().toLowerCase(java.util.Locale.ROOT);
    // Strip any ;charset= suffix a client might send
    int semi = normalized.indexOf(';');
    if (semi > 0) normalized = normalized.substring(0, semi).trim();
    if (!IMAGE_CONTENT_TYPES.contains(normalized))
      throw ApiException.badRequest(
          "PRODUCT_IMAGE_TYPE_INVALID",
          "Content-Type must be image/jpeg, image/png or image/webp — got: " + contentType);
    if (bytes == null || bytes.length == 0)
      throw ApiException.badRequest("PRODUCT_IMAGE_EMPTY", "image body is empty");
    // Strictly less than the ceiling — an image of exactly MAX_IMAGE_BYTES is rejected too.
    if (bytes.length >= MAX_IMAGE_BYTES)
      throw ApiException.badRequest(
          "PRODUCT_IMAGE_TOO_LARGE",
          "image is " + bytes.length + " bytes; must be under " + MAX_IMAGE_BYTES + " (256 KB)");
    // The plan's cap on what the business keeps in images (21.11), measured as it would stand with
    // this one in: what the other products hold plus this, so replacing an image is not charged
    // twice. Refused before a byte is stored; a plan with no cap holds nothing back.
    entitlements.requireBytesWithin(
        tenantId,
        com.storeql.service.Entitlements.IMAGES_MB_MAX,
        "MB of product images",
        () -> repo.imageBytesExcept(tenantId, productId) + bytes.length);
    repo.upsertProductImage(tenantId, productId, normalized, bytes);
  }

  /**
   * Reads a product image.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @return the product image
   * @throws ApiException a 404 when no such product image exists in this tenant
   */
  public com.storeql.product.domain.Domain.ProductImage getProductImage(
      UUID tenantId, UUID productId) {
    return repo.findProductImage(tenantId, productId)
        .orElseThrow(() -> ApiException.notFound("PRODUCT_IMAGE_NOT_FOUND", "no image"));
  }

  /**
   * Deletes a product image.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @throws ApiException a 404 when no such product image exists in this tenant
   */
  public void deleteProductImage(UUID tenantId, UUID productId) {
    getProduct(tenantId, productId);
    repo.deleteProductImage(tenantId, productId);
  }

  /**
   * Lists the tenant's products.
   *
   * @param tenantId owning tenant
   * @param categoryId the category id
   * @param onlineOnly the online only
   * @param posOnly the pos only
   * @param storeId the store id
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Product> listProducts(
      UUID tenantId,
      UUID categoryId,
      boolean onlineOnly,
      boolean posOnly,
      UUID storeId,
      int limit) {
    return repo.listProducts(tenantId, categoryId, onlineOnly, posOnly, storeId, limit);
  }

  /** One page of admin products plus the opaque cursor for the next page (null when exhausted). */
  public record ProductPage(List<Product> products, String nextCursor) {}

  /**
   * Lists the tenant's products admins.
   *
   * @param tenantId owning tenant
   * @param categoryId the category id
   * @param status the status to set
   * @param afterCursor the after cursor
   * @param limit maximum rows
   * @return the matching rows
   */
  public ProductPage listProductsAdmin(
      UUID tenantId, UUID categoryId, String status, String afterCursor, int limit) {
    Instant afterCreatedAt = null;
    UUID afterId = null;
    String rawKey = com.storeql.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCreatedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = Ids.parse(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<Product> rows =
        repo.listProductsAdmin(tenantId, categoryId, status, afterCreatedAt, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new ProductPage(rows, null);
    }
    List<Product> page = rows.subList(0, limit);
    Product last = page.get(page.size() - 1);
    return new ProductPage(
        page, com.storeql.web.Cursor.encode(last.createdAt().toString() + "|" + last.id()));
  }

  /**
   * Searches the catalogue by free text, SKU or barcode, with channel and store filters.
   *
   * @param tenantId owning tenant
   * @param q free-text query, or {@code null}
   * @param sku exact SKU to match, or {@code null}
   * @param barcode exact barcode to match, or {@code null}
   * @param onlineOnly restrict to products sold online
   * @param posOnly restrict to products sold in store
   * @param storeId restrict to products in one store's assortment, or {@code null}
   * @param limit maximum rows
   * @return the matching products
   */
  public List<Product> searchProducts(
      UUID tenantId,
      String q,
      String sku,
      String barcode,
      boolean onlineOnly,
      boolean posOnly,
      UUID storeId,
      int limit) {
    return repo.searchProducts(tenantId, q, sku, barcode, onlineOnly, posOnly, storeId, limit);
  }

  /** Store ids a product is restricted to (empty = sold at all stores). */
  public List<UUID> getProductStores(UUID tenantId, UUID productId) {
    getProduct(tenantId, productId); // 404 if not in tenant
    return repo.storesForProduct(tenantId, productId);
  }

  /** Replace a product's store assortment. Empty list = sold at all stores. */
  public void setProductStores(UUID tenantId, UUID productId, List<UUID> storeIds) {
    getProduct(tenantId, productId); // 404 if not in tenant
    repo.setStoresForProduct(tenantId, productId, storeIds);
  }

  /**
   * Resolves a scanned barcode to its variant and parent product.
   *
   * <p>Matches active variants only, so a delisted line does not ring up at the till.
   *
   * @param tenantId owning tenant
   * @param barcode the scanned barcode
   * @return the variant with its product
   * @throws ApiException {@code VARIANT_NOT_FOUND} (404) when no active variant carries that
   *     barcode
   */
  public com.storeql.product.domain.Domain.VariantWithProduct findVariantByBarcode(
      UUID tenantId, String barcode) {
    var found =
        repo.findVariantByBarcode(tenantId, barcode)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "VARIANT_NOT_FOUND", "No active variant found for barcode: " + barcode));
    requireOnSale(found);
    return found;
  }

  /**
   * Item lifecycle: a new line is listed before it goes on sale, and the till says so rather than
   * selling it early or pretending it does not exist.
   *
   * <p>Shared by both lookups on purpose. A second scan path that forgot this check would sell a
   * line before its launch date, and the two paths reach the same variants.
   */
  private static void requireOnSale(com.storeql.product.domain.Domain.VariantWithProduct found) {
    if (Product.STATUS_NEW_LINE.equals(found.product().status())) {
      throw ApiException.conflict(
          "PRODUCT_NOT_ON_SALE_YET",
          found.product().launchOn() == null
              ? "this line is not on sale yet"
              : "this line goes on sale on " + found.product().launchOn());
    }
  }

  /**
   * What a scanned code found, and what the code itself carried besides the item (07.15).
   *
   * @param scan the reading, or null when the code was not a GS1 code at all — an internal code, a
   *     PLU or a shelf label, which are matched exactly and carry nothing
   */
  public record ScanResult(
      com.storeql.product.domain.Domain.VariantWithProduct found, com.storeql.gs1.Gs1Scan scan) {}

  /**
   * Looks a scanned code up, whatever it was encoded as (07.15).
   *
   * <p>GS1 Sunrise 2027 asks a till to read a GTIN from a DataMatrix or a Digital Link QR, and
   * those carry more than the GTIN: a batch, an expiry, a weight. So this reads the code first and
   * then looks up by <b>the GTIN form</b>, which is what lets a packet whose 2D code says {@code
   * 05012345678900} find the variant a shop entered as {@code 5012345678900}.
   *
   * <p>Two fallbacks, in this order, because the codes a shop scans are not all GS1 codes:
   *
   * <ol>
   *   <li>the GTIN form, when the reading produced one;
   *   <li>the barcode column exactly — which is how an internal code, a PLU and a shelf label have
   *       always worked, and must go on working.
   * </ol>
   *
   * <p>The raw string is tried even when the code <em>did</em> read as GS1: a shop is entitled to
   * have typed the whole element string into the barcode field, and refusing to find it because the
   * platform now understands the format better would be a regression for that shop.
   *
   * @throws ApiException 404 {@code VARIANT_NOT_FOUND}; 409 {@code PRODUCT_NOT_ON_SALE_YET} for a
   *     line that is listed but not yet launched
   */
  public ScanResult scan(UUID tenantId, String scanned) {
    com.storeql.gs1.Gs1Scan reading = com.storeql.gs1.Gs1Reader.read(scanned).orElse(null);
    var byGtin =
        reading == null || reading.gtin() == null
            ? java.util.Optional.<com.storeql.product.domain.Domain.VariantWithProduct>empty()
            : repo.findVariantByGtin(tenantId, reading.gtin());
    var found = byGtin.or(() -> repo.findVariantByBarcode(tenantId, scanned.trim()));
    // A code that read as GS1 but matches nothing names the GTIN in the refusal, not the raw
    // string:
    // "no variant carries GTIN 05012345678900" is something a shopkeeper can act on, where the
    // element
    // string it came in is not.
    var variant =
        found.orElseThrow(
            () ->
                ApiException.notFound(
                    "VARIANT_NOT_FOUND",
                    reading != null && reading.gtin() != null
                        ? "No active variant carries GTIN " + reading.gtin()
                        : "No active variant found for barcode: " + scanned.trim()));
    requireOnSale(variant);
    return new ScanResult(variant, reading);
  }

  /**
   * Batch-resolves variant ids to variant+product (e.g. so admin screens show names, not UUIDs).
   */
  public java.util.List<com.storeql.product.domain.Domain.VariantWithProduct> resolveVariants(
      UUID tenantId, java.util.List<UUID> ids) {
    return repo.findVariantsByIds(tenantId, ids);
  }

  /**
   * The HSN or SAC codes recorded for a set of variants (18.9).
   *
   * @param tenantId owning tenant
   * @param ids the variants
   * @return codes by variant; unclassified variants are absent
   */
  /**
   * The drinks container a variant is sold in, as a deposit return scheme reads it (09.16): a
   * material the schemes name and a volume in millilitres, both or neither.
   */
  static String depositContainerOf(String material, Integer volumeMl) {
    String m = trimUpperToNull(material);
    if (m == null && volumeMl == null) return null;
    if (m == null || volumeMl == null) {
      throw ApiException.badRequest(
          "PRODUCT_DEPOSIT_CONTAINER_INCOMPLETE",
          "depositMaterial and depositVolumeMl go together: give both or neither");
    }
    if (!Domain.VariantCompliance.DEPOSIT_MATERIALS.contains(m)) {
      throw ApiException.badRequest(
          "PRODUCT_DEPOSIT_MATERIAL_UNKNOWN",
          "depositMaterial must be one of " + Domain.VariantCompliance.DEPOSIT_MATERIALS);
    }
    if (volumeMl < 1 || volumeMl > 10_000) {
      throw ApiException.badRequest(
          "PRODUCT_DEPOSIT_VOLUME_OUT_OF_RANGE", "depositVolumeMl must be 1 to 10000");
    }
    return m;
  }

  /** The drinks containers of variants, by id, for the deposit a scheme puts on them (09.16). */
  public java.util.Map<UUID, Domain.DepositContainer> depositContainers(
      UUID tenantId, java.util.Collection<UUID> variantIds) {
    return complianceRepo.depositContainers(tenantId, variantIds);
  }

  public java.util.Map<UUID, String> hsnCodes(UUID tenantId, java.util.List<UUID> ids) {
    return complianceRepo.hsnCodes(tenantId, ids);
  }

  // ──────────────────────────────────────────────────────────────── variants

  /**
   * Creates a variant.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @param req the request body carrying the new values
   * @return the created variant
   */
  public Variant createVariant(UUID tenantId, UUID productId, CreateVariantRequest req) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    var variant =
        new Variant(
            id,
            tenantId,
            productId,
            req.sku().trim(),
            req.barcode(),
            req.manufacturerPn(),
            req.attributes(),
            req.unit(),
            Variant.STATUS_ACTIVE,
            now,
            now);
    var event =
        new OutboxRow(
            "VariantCreated",
            "storeql.catalog.variant-created",
            tenantId,
            id,
            Events.variantCreated(tenantId, id, productId, variant.sku()));
    return repo.createVariantWithOutbox(variant, event);
  }

  /**
   * Reads a variant.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the variant
   * @throws ApiException a 404 when no such variant exists in this tenant
   */
  public Variant getVariant(UUID tenantId, UUID variantId) {
    return repo.findVariant(tenantId, variantId)
        .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
  }

  /**
   * Lists the tenant's variants.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @return the matching rows
   */
  public List<Variant> listVariants(UUID tenantId, UUID productId) {
    return repo.listVariants(tenantId, productId);
  }

  /**
   * Updates a variant.
   *
   * @param tenantId owning tenant
   * @param productId the product concerned
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the updated variant
   * @throws ApiException a 404 when no such variant exists in this tenant
   */
  public Variant updateVariant(
      UUID tenantId, UUID productId, UUID variantId, UpdateVariantRequest req) {
    getVariant(tenantId, variantId);
    return repo.updateVariant(
        tenantId,
        variantId,
        req.sku().trim(),
        req.barcode(),
        req.manufacturerPn(),
        req.attributes(),
        req.unit());
  }

  /**
   * Delists a variant so it stops being sellable, leaving the row and its history in place.
   *
   * @param tenantId owning tenant
   * @param productId the parent product
   * @param variantId the variant to delist
   * @return the variant in its delisted state
   * @throws ApiException a 404 when the variant does not exist in this tenant
   */
  public Variant delistVariant(UUID tenantId, UUID productId, UUID variantId) {
    getVariant(tenantId, variantId);
    return repo.delistVariant(tenantId, variantId);
  }

  // ── Supplier / Customer Cross-References (Gap #33) ──────────────────────

  /**
   * Creates a cross reference.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the created cross reference
   * @throws ApiException a 404 when no such cross reference exists in this tenant
   */
  public ItemCrossReference createCrossReference(
      UUID tenantId, UUID variantId, CreateItemCrossReferenceRequest req) {
    String type = req.partyType().toUpperCase(java.util.Locale.ROOT);
    if (!ItemCrossReference.SUPPLIER.equals(type) && !ItemCrossReference.CUSTOMER.equals(type)) {
      throw ApiException.badRequest("INVALID_PARTY_TYPE", "partyType must be SUPPLIER or CUSTOMER");
    }
    UUID partyId = com.storeql.web.Parsing.uuid(req.partyId(), "partyId");
    getVariant(tenantId, variantId);
    return crossReferenceRepo.createCrossReference(
        new ItemCrossReference(
            Ids.newId(),
            tenantId,
            variantId,
            type,
            partyId,
            req.partyName(),
            req.crossRefNumber().trim(),
            Instant.now()));
  }

  /**
   * Lists the tenant's cross references.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param partyType the party type
   * @return the matching rows
   * @throws ApiException a 404 when no such cross reference exists in this tenant
   */
  public List<ItemCrossReference> listCrossReferences(
      UUID tenantId, UUID variantId, String partyType) {
    getVariant(tenantId, variantId);
    String type = partyType != null ? partyType.toUpperCase(java.util.Locale.ROOT) : null;
    return crossReferenceRepo.listCrossReferences(tenantId, variantId, type);
  }

  // ──────────────────────────────────────── food safety, origin, age, weight

  private static final java.util.Set<String> SOLD_BY =
      java.util.Set.of("EACH", "WEIGHT", "VOLUME", "LENGTH");

  /**
   * Lists the tenant's allergens.
   *
   * @return the matching rows
   */
  public List<Domain.Allergen> listAllergens() {
    return complianceRepo.listAllergens();
  }

  /**
   * Records a variant's complete allergen declaration.
   *
   * <p>An empty list is accepted and is meaningful: it is how "we have checked, and it contains
   * none of the fourteen" is said. That is why the status is set to DECLARED either way — the
   * difference between a declared-free product and one nobody has looked at is the entire point of
   * the status column, and it is not derivable from the row count.
   */
  public Domain.VariantCompliance declareAllergens(
      UUID tenantId, UUID variantId, AllergenDeclarationRequest req, UUID actor) {
    getVariant(tenantId, variantId);
    var valid = complianceRepo.listAllergens().stream().map(Domain.Allergen::code).collect(toSet());
    var rows = new java.util.ArrayList<Domain.VariantAllergen>();
    var seen = new java.util.HashSet<String>();
    for (var e : req.allergens()) {
      String code = e.code().trim().toUpperCase(java.util.Locale.ROOT);
      if (!valid.contains(code)) {
        throw ApiException.badRequest(
            "PRODUCT_UNKNOWN_ALLERGEN", "Not one of the fourteen regulated allergens: " + e.code());
      }
      String presence = e.presence().trim().toUpperCase(java.util.Locale.ROOT);
      if (!Domain.VariantAllergen.CONTAINS.equals(presence)
          && !Domain.VariantAllergen.MAY_CONTAIN.equals(presence)) {
        throw ApiException.badRequest(
            "PRODUCT_INVALID_PRESENCE", "presence must be CONTAINS or MAY_CONTAIN");
      }
      // Two rows for one allergen would make the declaration ambiguous, and the stricter of the
      // two is the one that matters, so it is refused rather than silently resolved.
      if (!seen.add(code)) {
        throw ApiException.badRequest(
            "PRODUCT_DUPLICATE_ALLERGEN", "Declared twice with different presence: " + code);
      }
      rows.add(new Domain.VariantAllergen(tenantId, variantId, code, presence, actor, null));
    }
    complianceRepo.replaceDeclaration(tenantId, variantId, rows, Domain.VariantCompliance.DECLARED);
    return complianceRepo.findCompliance(tenantId, variantId);
  }

  /**
   * The allergens declared against one variant.
   *
   * @param tenantId owning tenant
   * @param variantId the variant whose declaration to read
   * @return the declared allergens with their presence, empty when nothing is declared
   * @throws ApiException a 404 when the variant does not exist in this tenant
   */
  public List<Domain.VariantAllergen> allergensOf(UUID tenantId, UUID variantId) {
    getVariant(tenantId, variantId);
    return complianceRepo.listVariantAllergens(tenantId, variantId);
  }

  /**
   * Every variant declaring a given allergen — the recall question.
   *
   * @param tenantId owning tenant
   * @param code the regulated allergen code
   * @param presence restrict to {@code CONTAINS} or {@code MAY_CONTAIN}, or {@code null} for both
   * @return the matching variant ids
   */
  public List<UUID> variantsWithAllergen(UUID tenantId, String code, String presence) {
    return complianceRepo.variantsWithAllergen(
        tenantId,
        code.trim().toUpperCase(java.util.Locale.ROOT),
        presence == null ? null : presence.trim().toUpperCase(java.util.Locale.ROOT));
  }

  /**
   * Variants with no allergen declaration at all — the compliance gap list.
   *
   * <p>"Not declared" is not the same as "no allergens": these are the lines nobody has answered
   * for yet.
   *
   * @param tenantId owning tenant
   * @param limit maximum rows; clamped to 1..500
   * @return the undeclared variant ids
   */
  public List<UUID> undeclaredVariants(UUID tenantId, int limit) {
    return complianceRepo.undeclaredVariants(tenantId, Math.min(Math.max(limit, 1), 500));
  }

  /**
   * The compliance record for one variant: origin, restriction category, allergen status and
   * labelling detail.
   *
   * @param tenantId owning tenant
   * @param variantId the variant whose compliance to read
   * @return the compliance record
   * @throws ApiException {@code VARIANT_NOT_FOUND} (404) when the variant does not exist or has no
   *     compliance record
   */
  public Domain.VariantCompliance complianceOf(UUID tenantId, UUID variantId) {
    getVariant(tenantId, variantId);
    var c = complianceRepo.findCompliance(tenantId, variantId);
    if (c == null) {
      throw ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found");
    }
    return c;
  }

  /**
   * Updates a compliance.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the updated compliance
   * @throws ApiException a 404 when no such compliance exists in this tenant
   */
  public Domain.VariantCompliance updateCompliance(
      UUID tenantId, UUID variantId, VariantComplianceRequest req) {
    var variant = getVariant(tenantId, variantId);

    String origin = null;
    if (req.countryOfOrigin() != null && !req.countryOfOrigin().isBlank()) {
      origin = req.countryOfOrigin().trim().toUpperCase(java.util.Locale.ROOT);
      if (!origin.matches("[A-Z]{2}")) {
        throw ApiException.badRequest(
            "PRODUCT_INVALID_COUNTRY", "countryOfOrigin must be an ISO 3166-1 alpha-2 code");
      }
    }

    String soldBy =
        req.soldBy() == null || req.soldBy().isBlank()
            ? Domain.VariantCompliance.EACH
            : req.soldBy().trim().toUpperCase(java.util.Locale.ROOT);
    if (!SOLD_BY.contains(soldBy)) {
      throw ApiException.badRequest(
          "PRODUCT_INVALID_SOLD_BY", "soldBy must be EACH, WEIGHT, VOLUME or LENGTH");
    }

    boolean catchWeight = Boolean.TRUE.equals(req.catchWeight());
    String uom =
        req.netContentUom() == null
            ? null
            : req.netContentUom().trim().toUpperCase(java.util.Locale.ROOT);

    // Sold by weight with no unit named cannot be priced on a shelf edge, and the unit price is
    // what the Price Marking Order requires be displayed. Caught here rather than by the CHECK
    // constraint so the caller is told which field is wrong.
    if (!Domain.VariantCompliance.EACH.equals(soldBy) && uom == null && !catchWeight) {
      throw ApiException.badRequest(
          "PRODUCT_NET_CONTENT_REQUIRED",
          "An item not sold by the each needs netContentUom, or catchWeight when every item"
              + " differs");
    }
    if (uom != null && !uomRepo.definitionExists(uom)) {
      throw ApiException.badRequest("PRODUCT_UNKNOWN_UOM", "Unknown unit of measure: " + uom);
    }
    if (req.tareWeight() != null && req.tareWeight().signum() < 0) {
      throw ApiException.badRequest("PRODUCT_INVALID_TARE", "tareWeight cannot be negative");
    }

    // 18.9: the HSN or SAC code India's e-invoices name each line by, as it is printed with
    // spaces or dots between the groups of digits.
    String hsn =
        req.hsnCode() == null || req.hsnCode().isBlank()
            ? null
            : req.hsnCode().replaceAll("[\\s.]", "");
    if (hsn != null && !hsn.matches("[0-9]{4}|[0-9]{6}|[0-9]{8}")) {
      throw ApiException.badRequest(
          "PRODUCT_INVALID_HSN_CODE", "An HSN or SAC code is 4, 6 or 8 digits");
    }

    var current = complianceRepo.findCompliance(tenantId, variantId);
    String depositMaterial = depositContainerOf(req.depositMaterial(), req.depositVolumeMl());
    var updated =
        new Domain.VariantCompliance(
            variantId,
            origin,
            trimToNull(req.originDetail()),
            trimUpperToNull(req.restrictionCategory()),
            current == null ? Domain.VariantCompliance.NOT_APPLICABLE : current.allergenStatus(),
            trimToNull(req.ingredients()),
            hsn,
            soldBy,
            req.netContent(),
            uom,
            req.tareWeight(),
            catchWeight,
            depositMaterial,
            req.depositVolumeMl());
    // 03.13: the unit price is computed from this measure. Food is always sold in a quantity, so
    // where unit pricing is law a food item must say how much its price buys — a single loose item
    // states 1 EA — rather than leave the shopper a price with no unit price and no reason why.
    var measure = measureOf(soldBy, req.netContent(), uom, catchWeight);
    boolean food =
        Boolean.TRUE.equals(req.food())
            || req.food() == null
                && current != null
                && !Domain.VariantCompliance.NOT_APPLICABLE.equals(current.allergenStatus());
    if (food && measure == null && unitPricingRequired(tenantId)) {
      throw ApiException.badRequest(
          "PRODUCT_UNIT_PRICE_MEASURE_REQUIRED",
          "A food item needs its net content in a unit of weight, volume, length, area or count"
              + " (1 EA for a single loose item), so its unit price can be shown (Price Marking"
              + " Order 2004; Directive 98/6/EC art.3)");
    }
    java.util.function.LongFunction<OutboxRow> measured =
        version ->
            new OutboxRow(
                "VariantMeasured",
                "storeql.catalog.variant-measured",
                tenantId,
                variantId,
                Events.variantMeasured(
                    tenantId, variantId, variant.productId(), soldBy, measure, version));
    if (!complianceRepo.updateCompliance(tenantId, updated, measured)) {
      throw ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found");
    }

    // Whether an item is food decides whether its allergens are owed at all. Until this flag
    // existed
    // nothing ever set UNDECLARED — the column defaults to NOT_APPLICABLE, whatever V17's comment
    // says — so the allergen-gaps list an inspector asks for could never contain a row (SJ-D42).
    if (Boolean.TRUE.equals(req.food())) {
      complianceRepo.markFoodUndeclared(tenantId, variantId);
    } else if (Boolean.FALSE.equals(req.food())) {
      complianceRepo.replaceDeclaration(
          tenantId, variantId, List.of(), Domain.VariantCompliance.NOT_APPLICABLE);
    }
    return complianceRepo.findCompliance(tenantId, variantId);
  }

  /**
   * What the till must ask before selling this item in this country.
   *
   * <p>Takes the country rather than the store id on purpose: this is asked for every restricted
   * line scanned, and resolving a store to its country through tenant-svc would put a second
   * network hop in front of a queue. The caller already knows which store it is.
   */
  public Domain.AgeCheck ageCheck(UUID tenantId, UUID variantId, String country) {
    var c = complianceOf(tenantId, variantId);
    if (c.restrictionCategory() == null) {
      return null;
    }
    String cc = requireCountry(country);
    Integer age = complianceRepo.minimumAge(tenantId, cc, c.restrictionCategory());
    if (age == null) {
      // The item is restricted somewhere but this country has no rule for it. Refusing to answer
      // is safer than answering "no restriction" — a missing rule is a gap in configuration, not
      // a licence to sell.
      throw ApiException.badRequest(
          "PRODUCT_NO_AGE_RULE",
          "No age rule for " + c.restrictionCategory() + " in " + cc + "; set one before selling");
    }
    boolean override =
        complianceRepo.rulesFor(tenantId, cc).stream()
            .anyMatch(r -> r.category().equals(c.restrictionCategory()) && r.tenantId() != null);
    // A date of birth, not an age (10.8): the law's cut-off once its day has come, or the
    // tenant's own when it adopted one, whichever refuses more people.
    var cutoff =
        complianceRepo.birthCutoff(
            tenantId, cc, c.restrictionCategory(), java.time.LocalDate.now(clock));
    return new Domain.AgeCheck(
        cc,
        c.restrictionCategory(),
        age,
        override,
        cutoff == null ? null : cutoff.bornBefore(),
        cutoff != null && cutoff.tenantPolicy());
  }

  /**
   * The day rules that take effect on a date are judged by; UTC, the UK's own offset in January.
   */
  java.time.Clock clock = java.time.Clock.systemUTC();

  /**
   * A cut-off earlier than this would refuse everyone alive; it is a typing error, not a policy.
   */
  private static final java.time.LocalDate EARLIEST_CUTOFF = java.time.LocalDate.of(1900, 1, 1);

  private java.time.LocalDate tenantBornBefore(String raw, String country, String category) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    java.time.LocalDate date;
    try {
      date = java.time.LocalDate.parse(raw.trim());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400,
          "PRODUCT_INVALID_BORN_BEFORE",
          "bornBefore must be a date written yyyy-mm-dd",
          java.util.List.of(),
          e);
    }
    if (date.isBefore(EARLIEST_CUTOFF) || date.isAfter(java.time.LocalDate.now(clock))) {
      throw ApiException.badRequest(
          "PRODUCT_INVALID_BORN_BEFORE",
          "bornBefore must be a date between 1900 and today; a later one refuses nobody born yet");
    }
    var law = complianceRepo.statutoryBornBefore(country, category);
    if (law != null && date.isAfter(law)) {
      throw ApiException.badRequest(
          "PRODUCT_BORN_BEFORE_LAXER",
          "The law refuses anyone born on or after "
              + law
              + " for "
              + category
              + " in "
              + country
              + "; a tenant cut-off may be earlier, never later");
    }
    return date;
  }

  /**
   * The age-restriction rules in force for a country.
   *
   * <p>Tenant-specific rules override the platform defaults for the same category.
   *
   * @param tenantId owning tenant
   * @param country ISO country code the rules apply in
   * @return the applicable rules
   */
  public List<Domain.AgeRestrictionRule> ageRules(UUID tenantId, String country) {
    return complianceRepo.rulesFor(tenantId, requireCountry(country));
  }

  /**
   * Sets a tenant's own age rule.
   *
   * <p>It may be stricter than the statute and never laxer. A chain choosing Challenge-25 is making
   * a policy decision; a chain setting alcohol to 16 in the UK is committing an offence, and a
   * system that lets them configure it has helped.
   */
  public Domain.AgeRestrictionRule setAgeRule(
      UUID tenantId, SetAgeRestrictionRuleRequest req, UUID actor) {
    String cc = requireCountry(req.country());
    String category = req.category().trim().toUpperCase(java.util.Locale.ROOT);
    int age = req.minimumAge();
    if (age < 0 || age > 120) {
      throw ApiException.badRequest("PRODUCT_INVALID_AGE", "minimumAge must be between 0 and 120");
    }
    Integer statutory = complianceRepo.minimumAge(null, cc, category);
    if (statutory != null && age < statutory) {
      throw ApiException.badRequest(
          "PRODUCT_AGE_BELOW_STATUTORY",
          "The statutory minimum for "
              + category
              + " in "
              + cc
              + " is "
              + statutory
              + "; a tenant rule may be stricter, never laxer");
    }
    var rule =
        new Domain.AgeRestrictionRule(
            tenantId,
            cc,
            category,
            age,
            trimToNull(req.reason()),
            tenantBornBefore(req.bornBefore(), cc, category),
            null);
    complianceRepo.upsertTenantRule(rule, actor);
    return rule;
  }

  private static String requireCountry(String country) {
    if (country == null || country.isBlank()) {
      throw ApiException.badRequest("PRODUCT_COUNTRY_REQUIRED", "country is required");
    }
    String cc = country.trim().toUpperCase(java.util.Locale.ROOT);
    if (!cc.matches("[A-Z]{2}")) {
      throw ApiException.badRequest(
          "PRODUCT_INVALID_COUNTRY", "country must be an ISO 3166-1 alpha-2 code");
    }
    return cc;
  }

  private static String trimToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static String trimUpperToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * Deletes a cross reference.
   *
   * @param tenantId owning tenant
   * @param id the cross reference to act on
   * @throws ApiException a 404 when no such cross reference exists in this tenant
   */
  public void deleteCrossReference(UUID tenantId, UUID id) {
    if (!crossReferenceRepo.deleteCrossReference(tenantId, id)) {
      throw ApiException.notFound("CROSS_REF_NOT_FOUND", "Cross reference not found");
    }
  }

  // ── Item Relationships (Gap #32) ────────────────────────────────────────

  /**
   * Creates a relationship.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the created relationship
   * @throws ApiException a 404 when no such relationship exists in this tenant
   */
  public ItemRelationship createRelationship(
      UUID tenantId, UUID variantId, CreateItemRelationshipRequest req) {
    UUID relatedId = com.storeql.web.Parsing.uuid(req.relatedVariantId(), "relatedVariantId");
    String type = req.relationshipType().toUpperCase(java.util.Locale.ROOT);
    if (!ItemRelationship.SUBSTITUTE.equals(type) && !ItemRelationship.COMPLEMENTARY.equals(type)) {
      throw ApiException.badRequest(
          "INVALID_RELATIONSHIP_TYPE", "relationshipType must be SUBSTITUTE or COMPLEMENTARY");
    }
    if (variantId.equals(relatedId)) {
      throw ApiException.badRequest("SELF_RELATIONSHIP", "A variant cannot relate to itself");
    }
    getVariant(tenantId, variantId);
    getVariant(tenantId, relatedId);
    return itemRelationshipRepo.createRelationship(
        new ItemRelationship(Ids.newId(), tenantId, variantId, relatedId, type, Instant.now()));
  }

  /**
   * Lists the tenant's relationships.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the matching rows
   * @throws ApiException a 404 when no such relationship exists in this tenant
   */
  public List<ItemRelationship> listRelationships(UUID tenantId, UUID variantId) {
    getVariant(tenantId, variantId);
    return itemRelationshipRepo.listRelationships(tenantId, variantId);
  }

  /**
   * Deletes a relationship.
   *
   * @param tenantId owning tenant
   * @param id the relationship to act on
   * @throws ApiException a 404 when no such relationship exists in this tenant
   */
  public void deleteRelationship(UUID tenantId, UUID id) {
    if (!itemRelationshipRepo.deleteRelationship(tenantId, id)) {
      throw ApiException.notFound("RELATIONSHIP_NOT_FOUND", "Item relationship not found");
    }
  }

  // ---- UOM (Gap #2) ----

  /**
   * Lists the tenant's uom classes.
   *
   * @return the matching rows
   */
  public List<UomClass> listUomClasses() {
    return uomRepo.listUomClasses();
  }

  /**
   * Lists the tenant's uom definitions.
   *
   * @param classCode the class code
   * @return the matching rows
   */
  public List<UomDefinition> listUomDefinitions(String classCode) {
    return uomRepo.listUomDefinitions(classCode);
  }

  /**
   * Creates or replaces an item conversion.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param fromUom the from uom
   * @param toUom the to uom
   * @param factor the conversion factor
   * @return the stored item conversion
   */
  public UomItemConversion upsertItemConversion(
      UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal factor) {
    return uomRepo.upsertItemConversion(
        new UomItemConversion(Ids.newId(), tenantId, variantId, fromUom, toUom, factor));
  }

  /**
   * Lists the tenant's item conversions.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<UomItemConversion> listItemConversions(UUID tenantId, UUID variantId) {
    return uomRepo.listItemConversions(tenantId, variantId);
  }

  /**
   * Deletes an item conversion.
   *
   * @param tenantId owning tenant
   * @param id the item conversion to act on
   * @return whether a row was removed
   */
  public boolean deleteItemConversion(UUID tenantId, UUID id) {
    return uomRepo.deleteItemConversion(tenantId, id);
  }

  /**
   * Converts a quantity between units of measure.
   *
   * <p>Resolved in precedence order: identical units are the identity, then a variant-specific
   * factor, then the standard factor for the pair. A variant override beats the standard because "a
   * case of this" is a per-product fact, not a general one.
   *
   * @param tenantId owning tenant
   * @param variantId the variant whose own factors to prefer, or {@code null} to use only standard
   *     conversions
   * @param fromUom the unit converting from
   * @param toUom the unit converting to
   * @param qty the quantity to convert
   * @return the converted quantity with the factor used and which source supplied it
   * @throws ApiException a 400 when no conversion exists between the two units
   */
  public ConvertResult convert(
      UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal qty) {
    if (fromUom.equalsIgnoreCase(toUom)) {
      return new ConvertResult(fromUom, toUom, qty, qty, BigDecimal.ONE, "IDENTITY");
    }
    if (variantId != null) {
      var itemFactor = uomRepo.findItemConversionFactor(tenantId, variantId, fromUom, toUom);
      if (itemFactor.isPresent()) {
        BigDecimal f = itemFactor.get();
        return new ConvertResult(fromUom, toUom, qty, qty.multiply(f), f, "ITEM");
      }
    }
    var stdFactor = uomRepo.findStandardConversionFactor(fromUom, toUom);
    if (stdFactor.isPresent()) {
      BigDecimal f = stdFactor.get();
      return new ConvertResult(fromUom, toUom, qty, qty.multiply(f), f, "STANDARD");
    }
    throw new ApiException(
        404,
        "CONVERSION_NOT_FOUND",
        "No conversion from " + fromUom + " to " + toUom,
        List.of(),
        null);
  }

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  /**
   * Creates a template.
   *
   * @param tenantId owning tenant
   * @param name the name to match
   * @param description the free-text description
   * @param attributes the variant attributes as JSON
   * @return the created template
   */
  public ItemTemplate createTemplate(
      UUID tenantId, String name, String description, String attributes) {
    UUID id = Ids.newId();
    var tpl =
        new ItemTemplate(
            id, tenantId, name, description, attributes, ItemTemplate.ACTIVE, Instant.now());
    var event =
        new OutboxRow(
            "ItemTemplateCreated",
            "storeql.catalog.item-template-created",
            tenantId,
            id,
            Events.itemTemplateCreated(tenantId, id, name));
    return itemTemplateRepo.createTemplate(tpl, event);
  }

  /**
   * Reads a template.
   *
   * @param tenantId owning tenant
   * @param id the template to act on
   * @return the template
   * @throws ApiException a 404 when no such template exists in this tenant
   */
  public ItemTemplate getTemplate(UUID tenantId, UUID id) {
    return itemTemplateRepo
        .findTemplate(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
  }

  /**
   * Lists the tenant's templates.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<ItemTemplate> listTemplates(UUID tenantId) {
    return itemTemplateRepo.listTemplates(tenantId);
  }

  /**
   * Deactivates a template, leaving the row in place.
   *
   * @param tenantId owning tenant
   * @param id the template to act on
   * @return the template in its deactivated state
   * @throws ApiException a 404 when no such template exists in this tenant
   */
  public ItemTemplate deactivateTemplate(UUID tenantId, UUID id) {
    getTemplate(tenantId, id);
    return itemTemplateRepo.deactivateTemplate(tenantId, id);
  }

  /**
   * Applies an item template to a variant, publishing {@code ItemTemplateApplied}.
   *
   * @param tenantId owning tenant
   * @param variantId the variant to apply the template to
   * @param templateId the template to apply
   * @return the recorded application
   * @throws ApiException a 404 when the variant or template does not exist in this tenant
   */
  public ItemTemplateApplication applyTemplate(UUID tenantId, UUID variantId, UUID templateId) {
    var event =
        new OutboxRow(
            "ItemTemplateApplied",
            "storeql.catalog.item-template-applied",
            tenantId,
            variantId,
            Events.itemTemplateApplied(tenantId, variantId, templateId));
    return itemTemplateRepo.applyTemplate(tenantId, variantId, templateId, event);
  }

  // ── Item Revisions (Gap #12) ──────────────────────────────────────────────

  /**
   * Creates a revision.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param revision the revision number
   * @param description the free-text description
   * @param effectiveDate the effective date
   * @return the created revision
   */
  public ItemRevision createRevision(
      UUID tenantId, UUID variantId, String revision, String description, LocalDate effectiveDate) {
    UUID id = Ids.newId();
    var rev =
        new ItemRevision(
            id,
            tenantId,
            variantId,
            revision,
            description,
            effectiveDate,
            ItemRevision.ACTIVE,
            Instant.now());
    var event =
        new OutboxRow(
            "ItemRevisionCreated",
            "storeql.catalog.item-revision-created",
            tenantId,
            id,
            Events.itemRevisionCreated(tenantId, variantId, id, revision));
    return itemRevisionRepo.createRevisionWithOutbox(rev, event);
  }

  /**
   * Lists the tenant's revisions.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<ItemRevision> listRevisions(UUID tenantId, UUID variantId) {
    return itemRevisionRepo.listRevisions(tenantId, variantId);
  }

  /**
   * The variant's active revision.
   *
   * @param tenantId owning tenant
   * @param variantId the variant whose current revision to read
   * @return the active revision
   * @throws ApiException {@code REVISION_NOT_FOUND} (404) when the variant has no active revision
   */
  public ItemRevision currentRevision(UUID tenantId, UUID variantId) {
    return itemRevisionRepo
        .currentRevision(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound("REVISION_NOT_FOUND", "No active revision for this variant"));
  }

  /**
   * Reads a revision.
   *
   * @param tenantId owning tenant
   * @param revisionId the revision id
   * @return the revision
   * @throws ApiException a 404 when no such revision exists in this tenant
   */
  public ItemRevision getRevision(UUID tenantId, UUID revisionId) {
    return itemRevisionRepo
        .findRevision(tenantId, revisionId)
        .orElseThrow(() -> ApiException.notFound("REVISION_NOT_FOUND", "No such revision"));
  }

  // ── Bulk Import ──────────────────────────────────────────────────────────

  /**
   * Imports a catalogue sheet, creating categories, brands, products and variants as needed.
   *
   * <p>Two modes: {@code ADD} (the default) creates new rows and errors on a duplicate SKU, while
   * {@code REPLACE} upserts by SKU, reusing the product matched on name and category and replacing
   * its variants.
   *
   * <p>Deliberately not atomic: a bad row is collected into {@code errors} rather than rolling back
   * the sheet, so one malformed line in a large import does not discard the rest. Callers must read
   * {@code errors} — a partial import still succeeds.
   *
   * @param tenantId owning tenant
   * @param req the rows to import and the mode to import them under
   * @return counts of what was created or skipped, the imported variants, and one entry per failed
   *     row
   */
  public BulkImportResult bulkImport(UUID tenantId, BulkImportRequest req) {
    int catCreated = 0;
    int catSkipped = 0;
    int prodCreated = 0;
    int varCreated = 0;
    var errors = new java.util.ArrayList<BulkImportError>();
    var importedVariants = new java.util.ArrayList<com.storeql.product.dto.Dtos.ImportedVariant>();
    // REPLACE = upsert by SKU (reuse product by name+category, replace existing variants);
    // ADD (default) = create new (duplicate SKUs error).
    final boolean replace = req.mode() != null && "REPLACE".equalsIgnoreCase(req.mode());

    // A CSV sheet typically has far fewer distinct category/brand names than product rows — cache
    // resolved ids by name within this import so repeated rows for the same category/brand don't
    // each cost a round trip.
    var categoryIdByName = new java.util.HashMap<String, UUID>();
    var brandIdByName = new java.util.HashMap<String, UUID>();

    // ── 1. categories ────────────────────────────────────────────────────────
    if (req.categories() != null) {
      for (var c : req.categories()) {
        try {
          com.storeql.web.Validations.validate(c);
          var existingCat = categoryRepo.findCategoryByName(tenantId, c.name().trim());
          if (existingCat.isPresent()) {
            categoryIdByName.put(c.name().trim(), existingCat.get().id());
            catSkipped++;
            continue;
          }
          UUID parentId = null;
          if (c.parentName() != null && !c.parentName().isBlank()) {
            String parentName = c.parentName().trim();
            parentId = categoryIdByName.get(parentName);
            if (parentId == null) {
              parentId =
                  categoryRepo
                      .findCategoryByName(tenantId, parentName)
                      .map(cat -> cat.id())
                      .orElseThrow(
                          () ->
                              ApiException.badRequest(
                                  "PARENT_NOT_FOUND", "parent category not found: " + parentName));
            }
          }
          UUID newCategoryId =
              categoryRepo.createCategory(tenantId, parentId, c.name().trim()).id();
          categoryIdByName.put(c.name().trim(), newCategoryId);
          catCreated++;
        } catch (ApiException ae) {
          errors.add(new BulkImportError("category:" + c.name(), ae.getMessage()));
        } catch (Exception e) {
          errors.add(new BulkImportError("category:" + c.name(), "Failed to import category"));
        }
      }
    }

    // ── 2. products + variants ───────────────────────────────────────────────
    // A row carries no safety information, so where GPSR binds the business an import may add a
    // product for the till but not offer one online (01.12). Asked once for the whole import.
    Boolean safetyRequired = null;
    if (req.products() != null) {
      for (var p : req.products()) {
        try {
          com.storeql.web.Validations.validate(p);
          if (p.variants() == null || p.variants().isEmpty()) {
            errors.add(new BulkImportError("product:" + p.name(), "at least one variant required"));
            continue;
          }

          UUID categoryId = null;
          if (p.categoryName() != null && !p.categoryName().isBlank()) {
            String categoryName = p.categoryName().trim();
            categoryId = categoryIdByName.get(categoryName);
            if (categoryId == null) {
              var found = categoryRepo.findCategoryByName(tenantId, categoryName);
              categoryId = found.map(cat -> cat.id()).orElse(null);
              if (categoryId != null) categoryIdByName.put(categoryName, categoryId);
            }
          }

          UUID brandId = null;
          if (p.brandName() != null && !p.brandName().isBlank()) {
            String brandName = p.brandName().trim();
            brandId = brandIdByName.get(brandName);
            if (brandId == null) {
              brandId =
                  brandRepo
                      .findBrandByName(tenantId, brandName)
                      .map(b -> b.id())
                      .orElseGet(() -> brandRepo.createBrand(tenantId, brandName).id());
              brandIdByName.put(brandName, brandId);
            }
          }

          Instant now = Instant.now();
          // REPLACE reuses an existing product (by name + category) instead of duplicating it;
          // ADD always creates a fresh product.
          UUID productId;
          var existing =
              replace
                  ? repo.findProductByNameAndCategory(tenantId, p.name().trim(), categoryId)
                  : java.util.Optional.<com.storeql.product.domain.Domain.Product>empty();
          if (existing.isPresent()) {
            productId = existing.get().id();
          } else {
            if (p.sellableOnline() == null || p.sellableOnline()) {
              if (safetyRequired == null) safetyRequired = safetyRequired(tenantId);
              if (safetyRequired) {
                errors.add(
                    new BulkImportError(
                        "product:" + p.name(),
                        "PRODUCT_SAFETY_INFORMATION_REQUIRED: import it with sellableOnline false,"
                            + " then add its safety information before offering it online"));
                continue;
              }
            }
            productId = Ids.newId();
            var product =
                new com.storeql.product.domain.Domain.Product(
                    productId,
                    tenantId,
                    p.name().trim(),
                    p.description(),
                    brandId,
                    categoryId,
                    com.storeql.product.domain.Domain.Product.STATUS_ACTIVE,
                    p.sellableOnline() == null || p.sellableOnline(),
                    p.sellablePos() == null || p.sellablePos(),
                    now,
                    now,
                    null,
                    null);
            var productEvent =
                new OutboxRow(
                    "ProductCreated",
                    "storeql.catalog.product-created",
                    tenantId,
                    productId,
                    Events.productCreated(tenantId, productId, product.name()));
            repo.createProductWithOutbox(product, productEvent);
            prodCreated++;
          }

          // Assign to specific stores if requested (additive — never removes existing rows).
          if (p.storeIds() != null && !p.storeIds().isEmpty()) {
            var uuids =
                p.storeIds().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(UUID::fromString)
                    .toList();
            repo.addStoreAssignments(tenantId, productId, uuids);
          }

          for (var v : p.variants()) {
            try {
              com.storeql.web.Validations.validate(v);
              // REPLACE: drop any existing variant with this SKU first, so the sheet wins.
              if (replace) repo.deleteVariantBySku(tenantId, v.sku().trim());
              UUID variantId = Ids.newId();
              var variant =
                  new com.storeql.product.domain.Domain.Variant(
                      variantId,
                      tenantId,
                      productId,
                      v.sku().trim(),
                      v.barcode(),
                      v.manufacturerPn(),
                      v.attributes(),
                      v.unit(),
                      com.storeql.product.domain.Domain.Variant.STATUS_ACTIVE,
                      now,
                      now);
              var variantEvent =
                  new OutboxRow(
                      "VariantCreated",
                      "storeql.catalog.variant-created",
                      tenantId,
                      variantId,
                      Events.variantCreated(tenantId, variantId, productId, variant.sku()));
              repo.createVariantWithOutbox(variant, variantEvent);
              varCreated++;
              importedVariants.add(
                  new com.storeql.product.dto.Dtos.ImportedVariant(
                      variant.sku(), variantId.toString(), productId.toString()));
            } catch (ApiException ae) {
              errors.add(
                  new BulkImportError("variant:" + v.sku() + " on " + p.name(), ae.getMessage()));
            } catch (Exception e) {
              errors.add(
                  new BulkImportError(
                      "variant:" + v.sku() + " on " + p.name(),
                      "Failed to import variant — check SKU uniqueness"));
            }
          }
        } catch (ApiException ae) {
          errors.add(new BulkImportError("product:" + p.name(), ae.getMessage()));
        } catch (Exception e) {
          errors.add(new BulkImportError("product:" + p.name(), "Failed to import product"));
        }
      }
    }

    return new BulkImportResult(
        catCreated,
        catSkipped,
        prodCreated,
        varCreated,
        errors,
        importedVariants,
        null,
        null,
        null,
        null);
  }

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  /**
   * Creates a catalog group.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created catalog group
   */
  public CatalogGroup createCatalogGroup(UUID tenantId, CreateCatalogGroupRequest req) {
    return catalogGroupRepo.createCatalogGroup(tenantId, req.name().trim(), req.description());
  }

  /**
   * Reads a catalog group.
   *
   * @param tenantId owning tenant
   * @param id the catalog group to act on
   * @return the catalog group
   * @throws ApiException a 404 when no such catalog group exists in this tenant
   */
  public CatalogGroup getCatalogGroup(UUID tenantId, UUID id) {
    return catalogGroupRepo
        .findCatalogGroup(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATALOG_GROUP_NOT_FOUND", "Catalog group not found"));
  }

  /**
   * Lists the tenant's catalog groups.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<CatalogGroup> listCatalogGroups(UUID tenantId) {
    return catalogGroupRepo.listCatalogGroups(tenantId);
  }

  /**
   * Deactivates a catalog group, leaving the row in place.
   *
   * @param tenantId owning tenant
   * @param id the catalog group to act on
   * @return the catalog group in its deactivated state
   * @throws ApiException a 404 when no such catalog group exists in this tenant
   */
  public CatalogGroup deactivateCatalogGroup(UUID tenantId, UUID id) {
    getCatalogGroup(tenantId, id);
    return catalogGroupRepo.deactivateCatalogGroup(tenantId, id);
  }

  /**
   * Creates a catalog group element.
   *
   * @param tenantId owning tenant
   * @param groupId the group id
   * @param req the request body carrying the new values
   * @return the created catalog group element
   * @throws ApiException a 404 when no such catalog group element exists in this tenant
   */
  public CatalogGroupElement createCatalogGroupElement(
      UUID tenantId, UUID groupId, CreateCatalogGroupElementRequest req) {
    getCatalogGroup(tenantId, groupId);
    String type = req.dataType().toUpperCase(java.util.Locale.ROOT);
    if (!CatalogGroupElement.TYPE_TEXT.equals(type)
        && !CatalogGroupElement.TYPE_NUMBER.equals(type)
        && !CatalogGroupElement.TYPE_BOOLEAN.equals(type)
        && !CatalogGroupElement.TYPE_DATE.equals(type)) {
      throw ApiException.badRequest(
          "INVALID_DATA_TYPE", "dataType must be TEXT, NUMBER, BOOLEAN or DATE");
    }
    return catalogGroupRepo.createCatalogGroupElement(
        new CatalogGroupElement(
            Ids.newId(),
            tenantId,
            groupId,
            req.elementName().trim(),
            type,
            req.required(),
            req.defaultVal(),
            req.sortOrder(),
            Instant.now()));
  }

  /**
   * Lists the tenant's catalog group elements.
   *
   * @param tenantId owning tenant
   * @param groupId the group id
   * @return the matching rows
   */
  public List<CatalogGroupElement> listCatalogGroupElements(UUID tenantId, UUID groupId) {
    return catalogGroupRepo.listCatalogGroupElements(tenantId, groupId);
  }

  /**
   * Deletes a catalog group element.
   *
   * @param tenantId owning tenant
   * @param elementId the element id
   * @throws ApiException a 404 when no such catalog group element exists in this tenant
   */
  public void deleteCatalogGroupElement(UUID tenantId, UUID elementId) {
    if (!catalogGroupRepo.deleteCatalogGroupElement(tenantId, elementId)) {
      throw ApiException.notFound("ELEMENT_NOT_FOUND", "Catalog group element not found");
    }
  }

  /**
   * Assigns a catalog group.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the assignment as stored
   * @throws ApiException a 404 when no such catalog group exists in this tenant
   */
  public VariantCatalogAssignment assignCatalogGroup(
      UUID tenantId, UUID variantId, AssignCatalogGroupRequest req) {
    getVariant(tenantId, variantId);
    UUID groupId = parseOptionalUuid(req.groupId(), "groupId");
    if (groupId == null) throw ApiException.badRequest("INVALID_GROUP_ID", "groupId is required");
    getCatalogGroup(tenantId, groupId);
    return catalogGroupRepo.createCatalogAssignment(
        new VariantCatalogAssignment(
            Ids.newId(),
            tenantId,
            variantId,
            groupId,
            req.elementVals() != null ? req.elementVals() : "{}",
            Instant.now(),
            Instant.now()));
  }

  /**
   * Reads a catalog assignment.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the catalog assignment
   * @throws ApiException a 404 when no such catalog assignment exists in this tenant
   */
  public VariantCatalogAssignment getCatalogAssignment(UUID tenantId, UUID variantId) {
    return catalogGroupRepo
        .findCatalogAssignment(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant"));
  }

  /**
   * Updates a catalog assignment.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the updated catalog assignment
   */
  public VariantCatalogAssignment updateCatalogAssignment(
      UUID tenantId, UUID variantId, UpdateCatalogAssignmentRequest req) {
    return catalogGroupRepo.updateCatalogAssignment(tenantId, variantId, req.elementVals());
  }

  /**
   * Deletes a catalog assignment.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @throws ApiException a 404 when no such catalog assignment exists in this tenant
   */
  public void deleteCatalogAssignment(UUID tenantId, UUID variantId) {
    if (!catalogGroupRepo.deleteCatalogAssignment(tenantId, variantId)) {
      throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant");
    }
  }

  // ── Container Types (Gap #37) ────────────────────────────────────────────

  /**
   * Creates a container type.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created container type
   */
  public ContainerType createContainerType(
      UUID tenantId, com.storeql.product.dto.Dtos.CreateContainerTypeRequest req) {
    return containerTypeRepo.createContainerType(
        tenantId,
        req.code().trim(),
        req.name().trim(),
        req.description(),
        req.lengthMm(),
        req.widthMm(),
        req.heightMm(),
        req.maxWeightKg(),
        req.tareWeightKg(),
        req.maxUnits());
  }

  /**
   * Reads a container type.
   *
   * @param tenantId owning tenant
   * @param id the container type to act on
   * @return the container type
   * @throws ApiException a 404 when no such container type exists in this tenant
   */
  public ContainerType getContainerType(UUID tenantId, UUID id) {
    return containerTypeRepo
        .findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  /**
   * Lists the tenant's container types.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<ContainerType> listContainerTypes(UUID tenantId) {
    return containerTypeRepo.listContainerTypes(tenantId);
  }

  /**
   * Updates a container type.
   *
   * @param tenantId owning tenant
   * @param id the container type to act on
   * @param req the request body carrying the new values
   * @return the updated container type
   * @throws ApiException a 404 when no such container type exists in this tenant
   */
  public ContainerType updateContainerType(
      UUID tenantId, UUID id, com.storeql.product.dto.Dtos.UpdateContainerTypeRequest req) {
    getContainerType(tenantId, id);
    return containerTypeRepo.updateContainerType(
        tenantId,
        id,
        req.name().trim(),
        req.description(),
        req.lengthMm(),
        req.widthMm(),
        req.heightMm(),
        req.maxWeightKg(),
        req.tareWeightKg(),
        req.maxUnits());
  }

  /**
   * Deactivates a container type, leaving the row in place.
   *
   * @param tenantId owning tenant
   * @param id the container type to act on
   * @return the container type in its deactivated state
   * @throws ApiException a 404 when no such container type exists in this tenant
   */
  public ContainerType deactivateContainerType(UUID tenantId, UUID id) {
    getContainerType(tenantId, id);
    return containerTypeRepo.deactivateContainerType(tenantId, id);
  }

  /**
   * Creates a variant container link.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the created variant container link
   * @throws ApiException a 404 when no such variant container link exists in this tenant
   */
  public VariantContainerLink createVariantContainerLink(
      UUID tenantId,
      UUID variantId,
      com.storeql.product.dto.Dtos.CreateVariantContainerLinkRequest req) {
    requireVariant(tenantId, variantId);
    UUID containerTypeId = Ids.parse(req.containerTypeId());
    getContainerType(tenantId, containerTypeId);
    return containerTypeRepo.createVariantContainerLink(
        tenantId,
        variantId,
        containerTypeId,
        req.qtyPerContainer(),
        req.isPrimary() != null && req.isPrimary());
  }

  /**
   * Lists the tenant's variant container links.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<VariantContainerLink> listVariantContainerLinks(UUID tenantId, UUID variantId) {
    requireVariant(tenantId, variantId);
    return containerTypeRepo.listVariantContainerLinks(tenantId, variantId);
  }

  /**
   * Deletes a variant container link.
   *
   * @param tenantId owning tenant
   * @param id the variant container link to act on
   * @throws ApiException a 404 when no such variant container link exists in this tenant
   */
  public void deleteVariantContainerLink(UUID tenantId, UUID id) {
    if (!containerTypeRepo.deleteVariantContainerLink(tenantId, id)) {
      throw ApiException.notFound("CONTAINER_LINK_NOT_FOUND", "Container link not found");
    }
  }

  // ── Item Attribute Groups (Gap #36) ─────────────────────────────────────

  /**
   * Lists the tenant's attribute groups.
   *
   * @return the matching rows
   */
  public List<ItemAttributeGroup> listAttributeGroups() {
    return itemAttributeGroupRepo.listAttributeGroups();
  }

  /**
   * Reads an attribute group.
   *
   * @param groupCode the group code
   * @return the attribute group
   * @throws ApiException a 404 when no such attribute group exists in this tenant
   */
  public ItemAttributeGroup getAttributeGroup(String groupCode) {
    return itemAttributeGroupRepo
        .findAttributeGroup(groupCode.toUpperCase(java.util.Locale.ROOT))
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_NOT_FOUND", "Attribute group not found: " + groupCode));
  }

  /**
   * Lists the tenant's attribute group fields.
   *
   * @param groupCode the group code
   * @return the matching rows
   */
  public List<ItemAttributeGroupField> listAttributeGroupFields(String groupCode) {
    return itemAttributeGroupRepo.listAttributeGroupFields(
        groupCode.toUpperCase(java.util.Locale.ROOT));
  }

  /**
   * Creates or replaces a variant attribute group values.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param groupCode the group code
   * @param values the values to store
   * @return the stored variant attribute group values
   * @throws ApiException a 404 when no such variant attribute group values exists in this tenant
   */
  public VariantAttributeGroupValues upsertVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode, String values) {
    String code = groupCode.toUpperCase(java.util.Locale.ROOT);
    itemAttributeGroupRepo
        .findAttributeGroup(code)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_NOT_FOUND", "Unknown attribute group: " + groupCode));
    requireVariant(tenantId, variantId);
    return itemAttributeGroupRepo.upsertVariantAttributeGroupValues(
        tenantId, variantId, code, values);
  }

  /**
   * Reads a variant attribute group values.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param groupCode the group code
   * @return the variant attribute group values
   * @throws ApiException a 404 when no such variant attribute group values exists in this tenant
   */
  public VariantAttributeGroupValues getVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode) {
    String code = groupCode.toUpperCase(java.util.Locale.ROOT);
    requireVariant(tenantId, variantId);
    return itemAttributeGroupRepo
        .findVariantAttributeGroupValues(tenantId, variantId, code)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_VALUES_NOT_FOUND",
                    "No attribute group values for group " + groupCode + " on this variant"));
  }

  /**
   * Lists the tenant's variant attribute group values.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<VariantAttributeGroupValues> listVariantAttributeGroupValues(
      UUID tenantId, UUID variantId) {
    requireVariant(tenantId, variantId);
    return itemAttributeGroupRepo.listVariantAttributeGroupValues(tenantId, variantId);
  }

  /**
   * Deletes a variant attribute group values.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param groupCode the group code
   * @throws ApiException a 404 when no such variant attribute group values exists in this tenant
   */
  public void deleteVariantAttributeGroupValues(UUID tenantId, UUID variantId, String groupCode) {
    String code = groupCode.toUpperCase(java.util.Locale.ROOT);
    if (!itemAttributeGroupRepo.deleteVariantAttributeGroupValues(tenantId, variantId, code)) {
      throw ApiException.notFound(
          "ATTRIBUTE_GROUP_VALUES_NOT_FOUND",
          "No attribute group values for group " + groupCode + " on this variant");
    }
  }

  // ── Gap #39: Category sets ────────────────────────────────────────────────

  /**
   * Creates a category set.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created category set
   */
  public CategorySet createCategorySet(UUID tenantId, CreateCategorySetRequest req) {
    UUID defCat = parseOptionalUuid(req.defaultCatId(), "defaultCatId");
    return categorySetRepo.createCategorySet(
        new CategorySet(
            Ids.newId(),
            tenantId,
            req.name(),
            req.description(),
            req.purpose(),
            defCat,
            req.controlled(),
            CategorySet.ACTIVE,
            null,
            null));
  }

  /**
   * Lists the tenant's category sets.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<CategorySet> listCategorySets(UUID tenantId) {
    return categorySetRepo.listCategorySets(tenantId);
  }

  /**
   * Reads a category set.
   *
   * @param tenantId owning tenant
   * @param id the category set to act on
   * @return the category set
   * @throws ApiException a 404 when no such category set exists in this tenant
   */
  public CategorySet getCategorySet(UUID tenantId, UUID id) {
    return categorySetRepo
        .findCategorySet(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATEGORY_SET_NOT_FOUND", "Category set not found"));
  }

  /**
   * Updates a category set.
   *
   * @param tenantId owning tenant
   * @param id the category set to act on
   * @param req the request body carrying the new values
   * @return the updated category set
   * @throws ApiException a 404 when no such category set exists in this tenant
   */
  public CategorySet updateCategorySet(UUID tenantId, UUID id, UpdateCategorySetRequest req) {
    getCategorySet(tenantId, id);
    UUID defCat = parseOptionalUuid(req.defaultCatId(), "defaultCatId");
    return categorySetRepo.updateCategorySet(
        tenantId,
        id,
        req.name(),
        req.description(),
        req.purpose(),
        defCat,
        req.controlled(),
        req.status());
  }

  /**
   * Deletes a category set.
   *
   * @param tenantId owning tenant
   * @param id the category set to act on
   * @throws ApiException a 404 when no such category set exists in this tenant
   */
  public void deleteCategorySet(UUID tenantId, UUID id) {
    if (!categorySetRepo.deleteCategorySet(tenantId, id)) {
      throw ApiException.notFound("CATEGORY_SET_NOT_FOUND", "Category set not found");
    }
  }

  /**
   * Adds a category set member.
   *
   * @param tenantId owning tenant
   * @param setId the set id
   * @param req the request body carrying the new values
   * @return the added category set member
   * @throws ApiException a 404 when no such category set member exists in this tenant
   */
  public CategorySetMember addCategorySetMember(
      UUID tenantId, UUID setId, AddCategorySetMemberRequest req) {
    getCategorySet(tenantId, setId);
    UUID catId = Ids.parse(req.categoryId());
    categoryRepo
        .findCategory(tenantId, catId)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
    return categorySetRepo.addCategorySetMember(
        new CategorySetMember(Ids.newId(), tenantId, setId, catId, null));
  }

  /**
   * Lists the tenant's category set members.
   *
   * @param tenantId owning tenant
   * @param setId the set id
   * @return the matching rows
   * @throws ApiException a 404 when no such category set member exists in this tenant
   */
  public List<CategorySetMember> listCategorySetMembers(UUID tenantId, UUID setId) {
    getCategorySet(tenantId, setId);
    return categorySetRepo.listCategorySetMembers(tenantId, setId);
  }

  /**
   * Deletes a category set member.
   *
   * @param tenantId owning tenant
   * @param setId the set id
   * @param categoryId the category id
   * @throws ApiException a 404 when no such category set member exists in this tenant
   */
  public void deleteCategorySetMember(UUID tenantId, UUID setId, UUID categoryId) {
    if (!categorySetRepo.deleteCategorySetMember(tenantId, setId, categoryId)) {
      throw ApiException.notFound(
          "CATEGORY_SET_MEMBER_NOT_FOUND", "Category not a member of this set");
    }
  }

  /**
   * Assigns a variant category set.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param req the request body carrying the new values
   * @return the assignment as stored
   * @throws ApiException a 404 when no such variant category set exists in this tenant
   */
  public VariantCategorySetAssignment assignVariantCategorySet(
      UUID tenantId, UUID variantId, AssignVariantCategorySetRequest req) {
    requireVariant(tenantId, variantId);
    UUID setId = Ids.parse(req.setId());
    UUID catId = Ids.parse(req.categoryId());
    getCategorySet(tenantId, setId);
    return categorySetRepo.upsertVariantCategorySetAssignment(
        new VariantCategorySetAssignment(
            Ids.newId(), tenantId, variantId, setId, catId, null, null));
  }

  /**
   * Lists the tenant's variant category set assignments.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<VariantCategorySetAssignment> listVariantCategorySetAssignments(
      UUID tenantId, UUID variantId) {
    requireVariant(tenantId, variantId);
    return categorySetRepo.listVariantCategorySetAssignments(tenantId, variantId);
  }

  /**
   * Deletes a variant category set assignment.
   *
   * @param tenantId owning tenant
   * @param variantId the product variant concerned
   * @param setId the set id
   * @throws ApiException a 404 when no such variant category set assignment exists in this tenant
   */
  public void deleteVariantCategorySetAssignment(UUID tenantId, UUID variantId, UUID setId) {
    if (!categorySetRepo.deleteVariantCategorySetAssignment(tenantId, variantId, setId)) {
      throw ApiException.notFound(
          "CATEGORY_SET_ASSIGNMENT_NOT_FOUND", "Category set assignment not found");
    }
  }

  private void requireVariant(UUID tenantId, UUID variantId) {
    repo.findVariant(tenantId, variantId)
        .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
  }

  private static UUID parseOptionalUuid(String s, String field) {
    return s == null || s.isBlank() ? null : com.storeql.web.Parsing.uuid(s, field);
  }

  // ── Supplier CSV import ────────────────────────────────────────────────────

  /**
   * Lenient supplier catalogue import. Recognises these column headers (in any order,
   * case-insensitive):
   *
   * <ul>
   *   <li>{@code Product ID / product_id / id} → SKU; auto-generated from description if absent
   *   <li>{@code Category} → category (created if new; may be quoted)
   *   <li>{@code Product Description / description / name} → product name (required — row skipped
   *       if blank)
   *   <li>{@code Store / Store Name / store_name / outlet} → store assignment (resolved via
   *       storeNameToId)
   *   <li>{@code Quantity / Case Size / qty / packsize} → stored in attributes as caseSize
   *   <li>{@code Price / cost / trade price} → stored in attributes as tradePrice
   * </ul>
   *
   * <p>Rows that are entirely blank are skipped. No other validation is applied — whatever values
   * are present get imported as-is so the customer can correct data inside the system rather than
   * outside it.
   */
  public BulkImportResult importSupplierCsv(
      UUID tenantId,
      String rolesHeader,
      com.storeql.product.dto.Dtos.SupplierCsvImportRequest req) {
    var storeNameToId =
        req.storeNameToId() != null ? req.storeNameToId() : java.util.Map.<String, String>of();
    var parsed = parseCsvFull(req.csv(), req.mode(), storeNameToId);
    var catalogResult = bulkImport(tenantId, parsed.request());

    Integer stockReceived = null;
    List<String> stockErrors = null;
    if (req.storeId() != null
        && !req.storeId().isBlank()
        && !catalogResult.importedVariants().isEmpty()) {
      var receiveItems =
          catalogResult.importedVariants().stream()
              .filter(v -> parsed.skuQty().containsKey(v.sku()))
              .map(
                  v ->
                      new com.storeql.product.client.InventoryClient.ReceiveItem(
                          v.variantId(), parsed.skuQty().get(v.sku())))
              .toList();
      if (!receiveItems.isEmpty()) {
        var r =
            inventoryClient.batchReceive(
                tenantId, Ids.parse(req.storeId()), rolesHeader, receiveItems);
        stockReceived = r.received();
        stockErrors = r.errors().isEmpty() ? null : r.errors();
      }
    }

    Integer pricesSet = null;
    List<String> priceErrors = null;
    if (!catalogResult.importedVariants().isEmpty()) {
      var priceItems =
          catalogResult.importedVariants().stream()
              .filter(v -> parsed.skuPrice().containsKey(v.sku()))
              .map(
                  v ->
                      new com.storeql.product.client.PricingClient.PriceItem(
                          v.variantId(), parsed.skuPrice().get(v.sku())))
              .toList();
      if (!priceItems.isEmpty()) {
        String cur = profiles.currencyOr(tenantId, req.currency());
        var r = pricingClient.batchSetPrices(tenantId, cur, rolesHeader, priceItems);
        pricesSet = r.upserted();
        priceErrors = r.errors().isEmpty() ? null : r.errors();
      }
    }

    return new BulkImportResult(
        catalogResult.categoriesCreated(),
        catalogResult.categoriesSkipped(),
        catalogResult.productsCreated(),
        catalogResult.variantsCreated(),
        catalogResult.errors(),
        catalogResult.importedVariants(),
        stockReceived,
        stockErrors,
        pricesSet,
        priceErrors);
  }

  private record CsvParseResult(
      BulkImportRequest request,
      java.util.Map<String, BigDecimal> skuQty,
      java.util.Map<String, BigDecimal> skuPrice) {}

  private CsvParseResult parseCsvFull(
      String csv, String mode, java.util.Map<String, String> storeNameToId) {
    var skuQty = new java.util.HashMap<String, BigDecimal>();
    var skuPrice = new java.util.HashMap<String, BigDecimal>();
    var req = parseSupplierCsvToRequest(csv, mode, storeNameToId, skuQty, skuPrice);
    return new CsvParseResult(req, skuQty, skuPrice);
  }

  private record ProductEntry(
      String categoryName,
      java.util.List<com.storeql.product.dto.Dtos.ImportVariantRequest> variants,
      java.util.Set<String> storeIds) {}

  private BulkImportRequest parseSupplierCsvToRequest(
      String csv,
      String mode,
      java.util.Map<String, String> storeNameToId,
      java.util.Map<String, BigDecimal> outSkuQty,
      java.util.Map<String, BigDecimal> outSkuPrice) {
    var lines =
        java.util.Arrays.asList(csv.split("\\r?\\n")).stream().filter(l -> !l.isBlank()).toList();
    if (lines.size() < 2) {
      throw new ApiException(
          400, "CSV_EMPTY", "CSV must have a header and at least one data row", List.of(), null);
    }

    // findHeader returns the FIRST matching column index — duplicate headers use the first one.
    var headers = splitCsvRow(lines.get(0));
    int idxId = findHeader(headers, "product id", "product_id", "sku", "item no");
    int idxDesc = findHeader(headers, "product description", "description", "product name", "name");
    int idxCat = findHeader(headers, "category");
    int idxQty = findHeader(headers, "quantity", "qty");
    int idxPrice = findHeader(headers, "price");
    int idxStore = findHeader(headers, "store", "store name", "store_name");

    if (idxDesc < 0) {
      throw new ApiException(
          400,
          "CSV_MISSING_COLUMNS",
          "CSV must have a 'Product Description' column",
          List.of(),
          null);
    }

    var categoryNames = new java.util.LinkedHashSet<String>();
    var productMap = new java.util.LinkedHashMap<String, ProductEntry>();
    int skuCounter = 0;

    for (int i = 1; i < lines.size(); i++) {
      var cols = splitCsvRow(lines.get(i));
      String desc = col(cols, idxDesc).trim();
      if (desc.isEmpty()) continue; // only skip genuinely blank name rows

      String sku = idxId >= 0 ? col(cols, idxId).trim() : "";
      if (sku.isEmpty()) {
        // Auto-generate a stable SKU from the description so duplicate rows collapse correctly.
        skuCounter++;
        sku = "IMP-" + skuCounter;
      }

      String category = idxCat >= 0 ? col(cols, idxCat).trim() : "";
      String qtyStr = idxQty >= 0 ? col(cols, idxQty).trim() : "";
      String priceStr = idxPrice >= 0 ? col(cols, idxPrice).trim() : "";

      // Capture numeric qty / price for stock-receive and pricing steps.
      if (!qtyStr.isEmpty() && outSkuQty != null) {
        try {
          outSkuQty.put(sku, new BigDecimal(qtyStr));
        } catch (NumberFormatException ignored) {
        }
      }
      if (!priceStr.isEmpty() && outSkuPrice != null) {
        try {
          outSkuPrice.put(sku, new BigDecimal(priceStr));
        } catch (NumberFormatException ignored) {
        }
      }

      // Build attributes from whatever is present — no parsing/validation.
      String attributes = buildAttributes(qtyStr, priceStr);

      var variant =
          new com.storeql.product.dto.Dtos.ImportVariantRequest(sku, null, null, "CS", attributes);

      String storeName = idxStore >= 0 ? col(cols, idxStore).trim() : "";
      String storeId = storeName.isEmpty() ? null : storeNameToId.get(storeName);

      String key = desc + "|" + category;
      var entry = productMap.get(key);
      if (entry == null) {
        var storeIds = new java.util.LinkedHashSet<String>();
        if (storeId != null) storeIds.add(storeId);
        productMap.put(
            key, new ProductEntry(category, new java.util.ArrayList<>(List.of(variant)), storeIds));
      } else {
        entry.variants().add(variant);
        if (storeId != null) entry.storeIds().add(storeId);
      }

      if (!category.isEmpty()) categoryNames.add(category);
    }

    var categories =
        categoryNames.stream()
            .map(n -> new com.storeql.product.dto.Dtos.ImportCategoryRequest(n, null))
            .toList();

    var products =
        productMap.entrySet().stream()
            .map(
                e -> {
                  var name = e.getKey().split("\\|", 2)[0];
                  var pe = e.getValue();
                  var storeIdList =
                      pe.storeIds().isEmpty() ? null : java.util.List.copyOf(pe.storeIds());
                  return new com.storeql.product.dto.Dtos.ImportProductRequest(
                      name,
                      null,
                      pe.categoryName().isEmpty() ? null : pe.categoryName(),
                      null,
                      true,
                      true,
                      storeIdList,
                      pe.variants());
                })
            .toList();

    return new com.storeql.product.dto.Dtos.BulkImportRequest(categories, products, mode);
  }

  private static String col(java.util.List<String> cols, int idx) {
    return (idx >= 0 && idx < cols.size()) ? cols.get(idx) : "";
  }

  private static int findHeader(java.util.List<String> headers, String... names) {
    for (String name : names) {
      for (int i = 0; i < headers.size(); i++) {
        if (headers.get(i).equalsIgnoreCase(name)) return i;
      }
    }
    return -1;
  }

  private static java.util.List<String> splitCsvRow(String line) {
    var result = new java.util.ArrayList<String>();
    var sb = new StringBuilder();
    boolean inQuotes = false;
    int pos = 0;
    while (pos < line.length()) {
      char ch = line.charAt(pos);
      if (ch == '"') {
        if (inQuotes && pos + 1 < line.length() && line.charAt(pos + 1) == '"') {
          sb.append('"');
          pos += 2;
        } else {
          inQuotes = !inQuotes;
          pos++;
        }
      } else if (ch == ',' && !inQuotes) {
        result.add(sb.toString());
        sb.setLength(0);
        pos++;
      } else {
        sb.append(ch);
        pos++;
      }
    }
    result.add(sb.toString());
    return result;
  }

  private static String buildAttributes(String caseSizeStr, String priceStr) {
    var sb = new StringBuilder("{");
    if (!caseSizeStr.isEmpty()) {
      sb.append("\"caseSize\":").append(caseSizeStr.replaceAll("[^0-9.]", ""));
    }
    if (!priceStr.isEmpty()) {
      if (sb.length() > 1) sb.append(",");
      sb.append("\"tradePrice\":\"").append(priceStr.replace("\"", "")).append("\"");
    }
    sb.append("}");
    return sb.length() > 2 ? sb.toString() : null;
  }

  // ── product safety information (01.12, GPSR art.19) ────────────────────────

  /** Whether GPSR binds this business's online offers today. */
  boolean safetyRequired(UUID tenantId) {
    return jurisdictions.inForce(
        tenantId, SafetyInformationRules.GPSR_ONLINE_OFFER, java.time.LocalDate.now(clock));
  }

  /**
   * Refuses to offer a product online without what GPSR art.19 requires, where it binds the
   * business.
   *
   * @param safety the statement as it would stand, or null
   * @throws ApiException 400 {@code PRODUCT_SAFETY_INFORMATION_REQUIRED}, the missing items in the
   *     details; 503 when the jurisdiction rules cannot be read — nothing is assumed
   */
  void requireListable(UUID tenantId, com.storeql.product.domain.Domain.ProductSafety safety) {
    if (!safetyRequired(tenantId)) return;
    List<String> missing =
        SafetyInformationRules.missing(safety, manufacturerInside(tenantId, safety));
    if (!missing.isEmpty()) {
      throw new ApiException(
          400,
          "PRODUCT_SAFETY_INFORMATION_REQUIRED",
          "An online offer in this business's market must show the manufacturer, the EU"
              + " responsible person when the manufacturer is outside the EU, and any warnings"
              + " (Regulation (EU) 2023/988 art.19). Missing: "
              + String.join(", ", missing),
          missing);
    }
  }

  /** Whether the manufacturer's country is inside the regime, asked of the jurisdiction rules. */
  private boolean manufacturerInside(
      UUID tenantId, com.storeql.product.domain.Domain.ProductSafety safety) {
    return safety != null
        && safety.manufacturerCountry() != null
        && jurisdictions.inForceIn(
            tenantId,
            safety.manufacturerCountry(),
            SafetyInformationRules.GPSR_ONLINE_OFFER,
            java.time.LocalDate.now(clock));
  }

  /**
   * A product's safety statement as it stands against the law.
   *
   * @throws ApiException 404 {@code PRODUCT_NOT_FOUND}
   */
  public com.storeql.product.domain.Domain.SafetySheet safetyInformation(
      UUID tenantId, UUID productId) {
    getProduct(tenantId, productId);
    return sheet(tenantId, productId, safetyRepo.find(tenantId, productId).orElse(null));
  }

  /**
   * Replaces a product's safety statement. Refused when the product is offered online and the
   * statement would not do for that.
   *
   * @throws ApiException 404 {@code PRODUCT_NOT_FOUND}; 400 for a malformed statement or {@code
   *     PRODUCT_SAFETY_INFORMATION_REQUIRED}
   */
  public com.storeql.product.domain.Domain.SafetySheet setSafetyInformation(
      UUID tenantId,
      UUID productId,
      com.storeql.product.dto.Dtos.SafetyInformationRequest req,
      UUID actor) {
    var safety =
        SafetyInformationRules.normalise(
            tenantId,
            productId,
            req,
            actor,
            Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    safetyRepo.save(
        safety,
        listed -> {
          if (listed) requireListable(tenantId, safety);
        });
    return sheet(tenantId, productId, safety);
  }

  /**
   * Active online products that their market requires safety information for and that lack some, by
   * name; empty where the regulation does not bind the business.
   */
  public List<com.storeql.product.domain.Domain.MissingSafety> missingSafetyInformation(
      UUID tenantId) {
    if (!safetyRequired(tenantId)) return List.of();
    List<com.storeql.product.domain.Domain.MissingSafety> out = new java.util.ArrayList<>();
    for (var listed : safetyRepo.listedOnline(tenantId, 500)) {
      List<String> missing =
          SafetyInformationRules.missing(
              listed.safety(), manufacturerInside(tenantId, listed.safety()));
      if (!missing.isEmpty()) {
        out.add(
            new com.storeql.product.domain.Domain.MissingSafety(
                listed.productId(), listed.name(), missing));
      }
    }
    return out;
  }

  private com.storeql.product.domain.Domain.SafetySheet sheet(
      UUID tenantId, UUID productId, com.storeql.product.domain.Domain.ProductSafety safety) {
    boolean required = safetyRequired(tenantId);
    return new com.storeql.product.domain.Domain.SafetySheet(
        productId,
        safety,
        required,
        required
            ? SafetyInformationRules.missing(safety, manufacturerInside(tenantId, safety))
            : List.of());
  }

  // ── unit pricing (03.13) ───────────────────────────────────────────────────

  /** The measure a variant's unit price is shown per, or null when none can be stated. */
  Domain.UnitMeasure measureOf(
      String soldBy, java.math.BigDecimal netContent, String uom, boolean catchWeight) {
    return UnitMeasures.of(
        soldBy,
        netContent,
        uom,
        catchWeight,
        uomRepo::classOf,
        uomRepo::findStandardConversionFactor);
  }

  /** Whether a unit price is law for this business's offers today, by the jurisdiction rules. */
  boolean unitPricingRequired(UUID tenantId) {
    return jurisdictions.inForce(tenantId, "UNIT_PRICING", java.time.LocalDate.now(clock));
  }
}
