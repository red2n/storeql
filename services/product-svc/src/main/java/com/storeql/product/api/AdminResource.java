package com.storeql.product.api;

import com.storeql.ids.Ids;
import com.storeql.product.dto.Dtos.AddCategorySetMemberRequest;
import com.storeql.product.dto.Dtos.AgeRestrictionRuleResponse;
import com.storeql.product.dto.Dtos.AllergenDeclarationRequest;
import com.storeql.product.dto.Dtos.AssignCatalogGroupRequest;
import com.storeql.product.dto.Dtos.AssignVariantCategorySetRequest;
import com.storeql.product.dto.Dtos.BrandResponse;
import com.storeql.product.dto.Dtos.BulkImportRequest;
import com.storeql.product.dto.Dtos.BulkImportResult;
import com.storeql.product.dto.Dtos.CatalogAssignmentResponse;
import com.storeql.product.dto.Dtos.CatalogGroupResponse;
import com.storeql.product.dto.Dtos.CategoryResponse;
import com.storeql.product.dto.Dtos.CategorySetMemberResponse;
import com.storeql.product.dto.Dtos.CategorySetResponse;
import com.storeql.product.dto.Dtos.ContainerTypeResponse;
import com.storeql.product.dto.Dtos.ConvertResult;
import com.storeql.product.dto.Dtos.CreateBrandRequest;
import com.storeql.product.dto.Dtos.CreateCatalogGroupElementRequest;
import com.storeql.product.dto.Dtos.CreateCatalogGroupRequest;
import com.storeql.product.dto.Dtos.CreateCategoryRequest;
import com.storeql.product.dto.Dtos.CreateCategorySetRequest;
import com.storeql.product.dto.Dtos.CreateContainerTypeRequest;
import com.storeql.product.dto.Dtos.CreateItemCrossReferenceRequest;
import com.storeql.product.dto.Dtos.CreateItemRelationshipRequest;
import com.storeql.product.dto.Dtos.CreateItemTemplateRequest;
import com.storeql.product.dto.Dtos.CreateProductRequest;
import com.storeql.product.dto.Dtos.CreateRevisionRequest;
import com.storeql.product.dto.Dtos.CreateVariantContainerLinkRequest;
import com.storeql.product.dto.Dtos.CreateVariantRequest;
import com.storeql.product.dto.Dtos.ItemAttributeGroupResponse;
import com.storeql.product.dto.Dtos.ItemCrossReferenceResponse;
import com.storeql.product.dto.Dtos.ItemRelationshipResponse;
import com.storeql.product.dto.Dtos.ItemRevisionResponse;
import com.storeql.product.dto.Dtos.ItemTemplateApplicationResponse;
import com.storeql.product.dto.Dtos.ItemTemplateResponse;
import com.storeql.product.dto.Dtos.ProductResponse;
import com.storeql.product.dto.Dtos.ProductStoresRequest;
import com.storeql.product.dto.Dtos.SetAgeRestrictionRuleRequest;
import com.storeql.product.dto.Dtos.UomClassResponse;
import com.storeql.product.dto.Dtos.UomDefinitionResponse;
import com.storeql.product.dto.Dtos.UomItemConversionRequest;
import com.storeql.product.dto.Dtos.UomItemConversionResponse;
import com.storeql.product.dto.Dtos.UpdateBrandRequest;
import com.storeql.product.dto.Dtos.UpdateCatalogAssignmentRequest;
import com.storeql.product.dto.Dtos.UpdateCategoryRequest;
import com.storeql.product.dto.Dtos.UpdateCategorySetRequest;
import com.storeql.product.dto.Dtos.UpdateContainerTypeRequest;
import com.storeql.product.dto.Dtos.UpdateProductRequest;
import com.storeql.product.dto.Dtos.UpdateVariantRequest;
import com.storeql.product.dto.Dtos.UpsertVariantAttributeGroupRequest;
import com.storeql.product.dto.Dtos.VariantAttributeGroupValuesResponse;
import com.storeql.product.dto.Dtos.VariantCategorySetAssignmentResponse;
import com.storeql.product.dto.Dtos.VariantComplianceRequest;
import com.storeql.product.dto.Dtos.VariantComplianceResponse;
import com.storeql.product.dto.Dtos.VariantContainerLinkResponse;
import com.storeql.product.dto.Dtos.VariantResponse;
import com.storeql.product.dto.Dtos.VariantScanResponse;
import com.storeql.product.mapper.Mappers;
import com.storeql.product.service.ProductService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Admin catalog CRUD. Tenant-scoped (tenantId from context). */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject ProductService service;
  @Inject TenantContext ctx;

  // ── brands ───────────────────────────────────────────────────────────────

  /**
   * Creates a brand.
   *
   * @param req the request body
   * @return brand created ({@code 201})
   */
  @Operation(summary = "Create a brand")
  @APIResponse(responseCode = "201", description = "Brand created")
  @Tag(name = "Brands")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining brands is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/brands")
  public Response createBrand(CreateBrandRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining brands");
    Validations.validate(req);
    return created(Mappers.toBrand(service.createBrand(ctx.requireTenantId(), req)));
  }

  /** Lists brands. */
  @Operation(summary = "List brands")
  @Tag(name = "Brands")
  @APIResponse(responseCode = "200", description = "List brands")
  @GET
  @Path("/brands")
  public ApiResponse<List<BrandResponse>> listBrands() {
    return ApiResponse.ok(
        service.listBrands(ctx.requireTenantId()).stream().map(Mappers::toBrand).toList());
  }

  /**
   * Gets a brand by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} brand not found
   */
  @Operation(summary = "Get a brand by id")
  @APIResponse(responseCode = "404", description = "Brand not found")
  @Tag(name = "Brands")
  @GET
  @Path("/brands/{id}")
  public ApiResponse<BrandResponse> getBrand(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toBrand(service.getBrand(ctx.requireTenantId(), id)));
  }

  /**
   * Renames a brand.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} brand not found
   */
  @Operation(summary = "Rename a brand")
  @APIResponse(responseCode = "404", description = "Brand not found")
  @Tag(name = "Brands")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining brands is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @PUT
  @Path("/brands/{id}")
  public ApiResponse<BrandResponse> updateBrand(@PathParam("id") UUID id, UpdateBrandRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining brands");
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toBrand(service.renameBrand(ctx.requireTenantId(), id, req)));
  }

  /**
   * Deactivates a brand.
   *
   * <p>Soft delete: sets the brand's status to inactive.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} brand not found
   */
  @Operation(
      summary = "Deactivate a brand",
      description = "Soft delete: sets the brand's status to inactive.")
  @APIResponse(responseCode = "404", description = "Brand not found")
  @Tag(name = "Brands")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining brands is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/brands/{id}")
  public ApiResponse<BrandResponse> deactivateBrand(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining brands");
    return ApiResponse.ok(Mappers.toBrand(service.deactivateBrand(ctx.requireTenantId(), id)));
  }

  // ── categories ───────────────────────────────────────────────────────────

  /**
   * Creates a category.
   *
   * @param req the request body
   * @return category created ({@code 201})
   */
  @Operation(summary = "Create a category")
  @APIResponse(responseCode = "201", description = "Category created")
  @Tag(name = "Categories")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining categories is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/categories")
  public Response createCategory(CreateCategoryRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining categories");
    Validations.validate(req);
    return created(Mappers.toCategory(service.createCategory(ctx.requireTenantId(), req)));
  }

  /** Lists categories. */
  @Operation(summary = "List categories")
  @Tag(name = "Categories")
  @APIResponse(responseCode = "200", description = "List categories")
  @GET
  @Path("/categories")
  public ApiResponse<List<CategoryResponse>> listCategories() {
    return ApiResponse.ok(
        service.listCategories(ctx.requireTenantId()).stream().map(Mappers::toCategory).toList());
  }

  /**
   * Gets a category by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category not found
   */
  @Operation(summary = "Get a category by id")
  @APIResponse(responseCode = "404", description = "Category not found")
  @Tag(name = "Categories")
  @GET
  @Path("/categories/{id}")
  public ApiResponse<CategoryResponse> getCategory(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toCategory(service.getCategory(ctx.requireTenantId(), id)));
  }

  /**
   * Updates a category.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} category not found
   */
  @Operation(summary = "Update a category")
  @APIResponse(responseCode = "404", description = "Category not found")
  @Tag(name = "Categories")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining categories is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @PUT
  @Path("/categories/{id}")
  public ApiResponse<CategoryResponse> updateCategory(
      @PathParam("id") UUID id, UpdateCategoryRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining categories");
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toCategory(service.updateCategory(ctx.requireTenantId(), id, req)));
  }

  /**
   * Deactivates a category.
   *
   * <p>Soft delete: sets the category's status to inactive.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category not found
   */
  @Operation(
      summary = "Deactivate a category",
      description = "Soft delete: sets the category's status to inactive.")
  @APIResponse(responseCode = "404", description = "Category not found")
  @Tag(name = "Categories")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining categories is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/categories/{id}")
  public ApiResponse<CategoryResponse> deactivateCategory(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining categories");
    return ApiResponse.ok(
        Mappers.toCategory(service.deactivateCategory(ctx.requireTenantId(), id)));
  }

  // ── products ─────────────────────────────────────────────────────────────

  /**
   * Creates a product.
   *
   * <p>Each store id is read as a UUIDv7 here ({@code 400 INVALID_UUID}), before the service is
   * asked; whether each store is the business's, and the caller's, and where a product naming none
   * is sold, is the service's: see {@code ProductService.createProduct}.
   *
   * @param req the request body
   * @return product created ({@code 201})
   */
  @Operation(
      summary = "Create a product",
      description =
          "Sold at the stores storeIds names, or at every store when it names none. Every store"
              + " named must be one of this business's and, for a manager held to stores, one of"
              + " theirs. A manager held to stores never creates one sold at every store, on"
              + " shelves they do not keep: naming none, theirs is sold at the stores they are held"
              + " to, all of them (GET /admin/products/{id}/stores says which). An owner or a manager of"
              + " the whole business creates one sold everywhere. Nothing is written by a refusal.")
  @APIResponse(responseCode = "201", description = "Product created")
  @APIResponse(
      responseCode = "400",
      description =
          "A required field missing or a null entry in storeIds (VALIDATION_FAILED), a store or"
              + " brand or category id that is not a UUIDv7 (INVALID_UUID), or a listing rule"
              + " (PRODUCT_SAFETY_INFORMATION_REQUIRED, …)")
  @APIResponse(
      responseCode = "403",
      description = "A store of this business the caller is not held to (STORE_ACCESS_DENIED)")
  @APIResponse(
      responseCode = "404",
      description = "A store that is not one of this business's (PRODUCT_STORE_NOT_FOUND)")
  @APIResponse(
      responseCode = "503",
      description =
          "Stores are named and tenant-svc cannot say which are the business's"
              + " (TENANT_STORES_UNAVAILABLE)")
  @Tag(name = "Products")
  @POST
  @Path("/products")
  public Response createProduct(CreateProductRequest req) {
    Validations.validate(req); // a hole in storeIds is named, not read as an id
    List<UUID> storeIds =
        req.storeIds() == null
            ? List.of()
            : req.storeIds().stream()
                .map(s -> com.storeql.web.Parsing.uuid(s.strip(), "storeIds"))
                .toList();
    return created(Mappers.toProduct(service.createProduct(ctx, req, storeIds)));
  }

  /**
   * Admin list — returns all statuses; optional ?status= and ?category= filters.
   * ?after=<cursor>&limit=1-100 (default 20) for pagination — previously capped at one page with no
   * way to reach the rest of a tenant's catalog.
   */
  @Operation(
      summary = "Re-announce the catalogue",
      description =
          "Publishes ProductCategorised for every active product — its category path and its"
              + " variants — so a service whose projection arrived after the catalogue did"
              + " (pricing-svc, for category-scoped promotions) can catch up. Management only."
              + " Returns how many products were announced.")
  @APIResponse(responseCode = "200", description = "Announced")
  @Tag(name = "Products")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Re-announcing the catalogue is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/products/republish-catalogue")
  public ApiResponse<com.storeql.product.dto.Dtos.CatalogueRepublishResponse> republishCatalogue() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Re-announcing the catalogue");
    int announced = service.republishCatalogue(ctx.requireTenantId());
    return ApiResponse.ok(new com.storeql.product.dto.Dtos.CatalogueRepublishResponse(announced));
  }

  @Operation(
      summary = "List products (admin)",
      description =
          "Returns products in all statuses; optional ?status= and ?category= filters."
              + " Cursor-paginated via ?after=&limit= (1-100, default 20).")
  @APIResponse(responseCode = "400", description = "Malformed pagination cursor")
  @Tag(name = "Products")
  @GET
  @Path("/products")
  public ApiResponse<List<ProductResponse>> listProductsAdmin(
      @QueryParam("category") String category,
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID categoryId = parseOptional(category, "category");
    int clamped = Cursor.clampLimit(limit);
    var page = service.listProductsAdmin(tenantId, categoryId, status, after, clamped);
    return ApiResponse.ok(
        page.products().stream().map(Mappers::toProduct).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Gets a product by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such product
   */
  @Operation(summary = "Get a product by id")
  @APIResponse(responseCode = "404", description = "No such product")
  @Tag(name = "Products")
  @GET
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> getProduct(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toProduct(service.getProduct(ctx.requireTenantId(), id)));
  }

  /**
   * Updates a product.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} no such product
   */
  @Operation(summary = "Update a product")
  @APIResponse(responseCode = "404", description = "No such product")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @Tag(name = "Products")
  @PUT
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> updateProduct(
      @PathParam("id") UUID id, UpdateProductRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toProduct(service.updateProduct(ctx, id, req)));
  }

  /**
   * Delists a product.
   *
   * <p>Soft delete: sets status DELISTED and publishes ProductDelisted. The line goes from the shop
   * and the till at every store, so it is management's, and of the whole business, as discontinuing
   * is: an owner, or a manager held to no store. Who may is asked before the line is looked up.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 403 FORBIDDEN} below management; {@code 403
   *     BUSINESS_WIDE_ONLY} for a manager held to stores; {@code 404} no such product in this
   *     business
   */
  @Operation(
      summary = "Delist a product",
      description =
          "Soft delete: sets status DELISTED and publishes ProductDelisted. The line leaves the shop"
              + " and the till, and inventory-svc takes its variants out of the low-stock report"
              + " and the planning run, at every store. For an owner or a manager of the whole"
              + " business, whatever the line's range: who may is judged before the line is looked"
              + " up.")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY),"
              + " a line of their own included: the line is taken off sale at every store, which is"
              + " the whole business's to decide. Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such product in this business")
  @Tag(name = "Products")
  @DELETE
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> delistProduct(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toProduct(service.delistProduct(ctx, id)));
  }

  /**
   * Puts a new line on sale (item lifecycle: NEW_LINE → ACTIVE).
   *
   * <p>The line goes on sale at every store it is sold at, so it is management's, and of the whole
   * business, as the other moves of its lifecycle are: an owner, or a manager held to no store. Who
   * may is asked before the line is looked up.
   *
   * @param id the product
   * @throws com.storeql.web.ApiException {@code 403 FORBIDDEN} below management; {@code 403
   *     BUSINESS_WIDE_ONLY} for a manager held to stores; {@code 404} no such product in this
   *     business; {@code 409} unless the product is a NEW_LINE
   */
  @Operation(
      summary = "Launch a new line",
      description =
          "NEW_LINE → ACTIVE: the shop lists it and the till sells it from now, at every store it"
              + " is sold at. Publishes ProductLaunched with the variants it covers. For an owner"
              + " or a manager of the whole business, whatever the line's range: who may is judged"
              + " before the line is looked up.")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY),"
              + " a new line of their own included: the line goes on sale at every store it is"
              + " sold at, which is the whole business's to decide. Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such product in this business")
  @APIResponse(responseCode = "409", description = "Not a NEW_LINE")
  @Tag(name = "Products")
  @POST
  @Path("/products/{id}/launch")
  public ApiResponse<ProductResponse> launchProduct(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toProduct(service.launchProduct(ctx, id)));
  }

  /**
   * Marks a line for run-down (item lifecycle: ACTIVE → DISCONTINUED).
   *
   * <p>A change to the line at every store, so it is management's, and of the whole business: an
   * owner, or a manager held to no store. Who may is asked before the line is looked up, so the
   * answer is the same whatever the id names.
   *
   * @param id the product
   * @throws com.storeql.web.ApiException {@code 403 FORBIDDEN} below management; {@code 403
   *     BUSINESS_WIDE_ONLY} for a manager held to stores; {@code 404} no such product in this
   *     business; {@code 409} unless the product is ACTIVE
   */
  @Operation(
      summary = "Discontinue a line",
      description =
          "ACTIVE → DISCONTINUED: sold while stock lasts, never reordered — inventory-svc takes its"
              + " variants out of the low-stock report and the planning run, at every store."
              + " Publishes ProductDiscontinued with the variants it covers. For an owner or a"
              + " manager of the whole business: who may is judged before the line is looked up.")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " the line stops being reordered at every store, which is the whole business's"
              + " to decide. Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such product in this business")
  @APIResponse(responseCode = "409", description = "Not ACTIVE")
  @Tag(name = "Products")
  @POST
  @Path("/products/{id}/discontinue")
  public ApiResponse<ProductResponse> discontinueProduct(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toProduct(service.discontinueProduct(ctx, id)));
  }

  /**
   * Brings a discontinued line back (item lifecycle: DISCONTINUED → ACTIVE).
   *
   * <p>It undoes a discontinue, the whole business's decision, and puts the line back into
   * replenishment at every store, so it is the whole business's too: an owner, or a manager held to
   * no store. Who may is asked before the line is looked up.
   *
   * @param id the product
   * @throws com.storeql.web.ApiException {@code 403 FORBIDDEN} below management; {@code 403
   *     BUSINESS_WIDE_ONLY} for a manager held to stores; {@code 404} no such product in this
   *     business; {@code 409} unless the product is DISCONTINUED
   */
  @Operation(
      summary = "Reinstate a discontinued line",
      description =
          "DISCONTINUED → ACTIVE: back on sale and back into replenishment at every store."
              + " Publishes ProductReinstated. For an owner or a manager of the whole business,"
              + " whatever the line's range: who may is judged before the line is looked up.")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " it undoes a discontinue, which is the whole business's to decide. Nothing"
              + " changes.")
  @APIResponse(responseCode = "404", description = "No such product in this business")
  @APIResponse(responseCode = "409", description = "Not DISCONTINUED")
  @Tag(name = "Products")
  @POST
  @Path("/products/{id}/reinstate")
  public ApiResponse<ProductResponse> reinstateProduct(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toProduct(service.reinstateProduct(ctx, id)));
  }

  // ── product image ──────────────────────────────────────────────────────────

  /**
   * Upload/replace the product's primary image. Raw body (not multipart): the admin app PUTs the
   * bytes with the image's own Content-Type (image/jpeg | image/png | image/webp), strictly under
   * 256 KB. The admin app downscales and re-encodes to that budget client-side; the cap here is
   * what makes it an invariant rather than a convention.
   */
  @Operation(
      summary = "Upload or replace a product's primary image",
      description =
          "Raw body (not multipart): PUT the bytes with the image's own Content-Type"
              + " (image/jpeg | image/png | image/webp), strictly under 256 KB.")
  @APIResponse(
      responseCode = "400",
      description = "Content-Type not an accepted image type, body empty, or 256 KB or larger")
  @APIResponse(responseCode = "404", description = "No such product")
  @Tag(name = "Product Images")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/{id}/image")
  @Consumes({"image/jpeg", "image/png", "image/webp"})
  public ApiResponse<String> uploadProductImage(
      @PathParam("id") UUID id,
      @jakarta.ws.rs.HeaderParam("Content-Type") String contentType,
      byte[] body) {
    service.requireLineHeld(ctx, id);
    service.uploadProductImage(ctx.requireTenantId(), id, contentType, body);
    return ApiResponse.ok("uploaded");
  }

  /**
   * Removes a product's primary image.
   *
   * @param id the id (path parameter)
   */
  @Operation(summary = "Remove a product's primary image")
  @Tag(name = "Product Images")
  @APIResponse(responseCode = "200", description = "Remove a product's primary image")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/{id}/image")
  public ApiResponse<String> deleteProductImage(@PathParam("id") UUID id) {
    service.requireLineHeld(ctx, id);
    service.deleteProductImage(ctx.requireTenantId(), id);
    return ApiResponse.ok("deleted");
  }

  // ── per-store assortment ───────────────────────────────────────────────────

  /** Store ids this product is sold at. Empty list = sold at all stores. */
  @Operation(
      summary = "Get a product's store assortment",
      description = "Store ids this product is sold at. Empty list means sold at all stores.")
  @APIResponse(responseCode = "404", description = "No such product")
  @Tag(name = "Store Assortment")
  @GET
  @Path("/products/{id}/stores")
  public ApiResponse<List<String>> getProductStores(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        service.getProductStores(ctx.requireTenantId(), id).stream().map(UUID::toString).toList());
  }

  /**
   * Replace the product's store assortment. Empty/absent list = sold at all stores.
   *
   * <p>Each id is read as a UUIDv7 here ({@code 400 INVALID_UUID}), before the service is asked;
   * whether each store is the business's, and the caller's to change, is the service's: see {@code
   * ProductService.setProductStores}.
   */
  @Operation(
      summary = "Replace a product's store assortment",
      description =
          "Empty/absent list means sold at all stores. Every store named must be one of this"
              + " business's. A manager held to stores may add or take away only their own"
              + " stores (stores left as they were are not asked about), and may not move a"
              + " product to or from \"sold at all stores\". Nothing is changed by a refusal. A"
              + " store named twice is kept once; the answer is the stores now held.")
  @APIResponse(
      responseCode = "400",
      description =
          "A store id that is not a UUIDv7 (INVALID_UUID), or a null entry in storeIds"
              + " (VALIDATION_FAILED)")
  @APIResponse(
      responseCode = "403",
      description =
          "The change adds or takes away a store the caller is not held to (STORE_ACCESS_DENIED),"
              + " or moves the product to or from every store, by a caller held to stores, which"
              + " is the whole business's to decide (BUSINESS_WIDE_ONLY)")
  @APIResponse(
      responseCode = "404",
      description =
          "No such product (PRODUCT_NOT_FOUND), or a store that is not one of this business's"
              + " (PRODUCT_STORE_NOT_FOUND)")
  @APIResponse(
      responseCode = "503",
      description =
          "Stores are named and tenant-svc cannot say which are the business's"
              + " (TENANT_STORES_UNAVAILABLE)")
  @Tag(name = "Store Assortment")
  @PUT
  @Path("/products/{id}/stores")
  public ApiResponse<List<String>> setProductStores(
      @PathParam("id") UUID id, ProductStoresRequest req) {
    List<UUID> ids = List.of();
    if (req != null && req.storeIds() != null) {
      Validations.validate(req); // a hole in the list is named, not read as an id
      ids =
          req.storeIds().stream()
              .map(s -> com.storeql.web.Parsing.uuid(s.strip(), "storeIds"))
              .toList();
    }
    return ApiResponse.ok(
        service.setProductStores(ctx, id, ids).stream().map(UUID::toString).toList());
  }

  // ── variants ─────────────────────────────────────────────────────────────

  /**
   * Creates a variant under a product.
   *
   * @param productId the product id (path parameter)
   * @param req the request body
   * @return variant created ({@code 201})
   */
  @Operation(summary = "Create a variant under a product")
  @APIResponse(responseCode = "201", description = "Variant created")
  @Tag(name = "Variants")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/{id}/variants")
  public Response createVariant(@PathParam("id") UUID productId, CreateVariantRequest req) {
    service.requireLineHeld(ctx, productId);
    Validations.validate(req);
    return created(Mappers.toVariant(service.createVariant(ctx, productId, req)));
  }

  /**
   * Lists a product's variants (admin).
   *
   * @param productId the product id (path parameter)
   */
  @Operation(summary = "List a product's variants (admin)")
  @Tag(name = "Variants")
  @APIResponse(responseCode = "200", description = "List a product's variants (admin)")
  @GET
  @Path("/products/{id}/variants")
  public ApiResponse<List<VariantResponse>> listVariants(@PathParam("id") UUID productId) {
    return ApiResponse.ok(
        service.listVariants(ctx.requireTenantId(), productId).stream()
            .map(Mappers::toVariant)
            .toList());
  }

  /**
   * Batch-resolves variant ids to name + SKU + product context so admin screens (e.g. inventory)
   * can show human-readable labels instead of raw variant UUIDs. {@code ?ids=a,b,c} (max 200);
   * unknown ids are simply omitted from the result.
   */
  @Operation(
      summary = "Batch-resolve variant ids",
      description =
          "Resolves up to 200 variant ids (?ids=a,b,c) to name/SKU/product context so admin"
              + " screens can show human-readable labels instead of raw UUIDs. Unknown ids are"
              + " simply omitted from the result.")
  @Tag(name = "Variants")
  @APIResponse(responseCode = "200", description = "Batch-resolve variant ids")
  @GET
  @Path("/products/variants/resolve")
  public ApiResponse<List<VariantScanResponse>> resolveVariants(@QueryParam("ids") String ids) {
    UUID tenantId = ctx.requireTenantId();
    if (ids == null || ids.isBlank()) {
      return ApiResponse.ok(List.of());
    }
    List<UUID> idList =
        java.util.Arrays.stream(ids.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .limit(200)
            .map(s -> com.storeql.web.Parsing.uuid(s, "ids"))
            .toList();
    java.util.Map<UUID, String> hsn = service.hsnCodes(tenantId, idList);
    var containers = service.depositContainers(tenantId, idList);
    return ApiResponse.ok(
        service.resolveVariants(tenantId, idList).stream()
            .map(
                vp ->
                    Mappers.toVariantScan(
                        vp.variant(),
                        vp.product(),
                        hsn.get(vp.variant().id()),
                        containers.get(vp.variant().id())))
            .toList());
  }

  /**
   * Gets a variant by id.
   *
   * @param productId the product id (path parameter)
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(summary = "Get a variant by id")
  @APIResponse(responseCode = "404", description = "Variant not found, or it is not this product's")
  @Tag(name = "Variants")
  @GET
  @Path("/products/{id}/variants/{variantId}")
  public ApiResponse<VariantResponse> getVariant(
      @PathParam("id") UUID productId, @PathParam("variantId") UUID variantId) {
    return ApiResponse.ok(
        Mappers.toVariant(service.getVariantOf(ctx.requireTenantId(), productId, variantId)));
  }

  /**
   * Updates a variant.
   *
   * @param productId the product id (path parameter)
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(summary = "Update a variant")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Variants")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/{id}/variants/{variantId}")
  public ApiResponse<VariantResponse> updateVariant(
      @PathParam("id") UUID productId,
      @PathParam("variantId") UUID variantId,
      UpdateVariantRequest req) {
    service.requireLineHeld(ctx, productId);
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toVariant(service.updateVariant(ctx, productId, variantId, req)));
  }

  /**
   * Delists a variant.
   *
   * <p>Soft delete: sets the variant's status to INACTIVE. The variant leaves the shop and the till
   * at every store its line is sold at, so it is management's, and of the whole business, as
   * delisting the line is: an owner, or a manager held to no store. Who may is asked before the
   * variant is looked up.
   *
   * @param productId the product id (path parameter)
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 403 FORBIDDEN} below management; {@code 403
   *     BUSINESS_WIDE_ONLY} for a manager held to stores; {@code 404} no such variant in this
   *     business
   */
  @Operation(
      summary = "Delist a variant",
      description =
          "Soft delete: sets the variant's status to INACTIVE. It no longer scans at the till or"
              + " lists in the shop, at every store its line is sold at; the row and its history"
              + " stay. For an owner or a manager of the whole business, whatever the line's range:"
              + " who may is judged before the variant is looked up.")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY),"
              + " a variant of a line of their own included: the variant is taken off sale at every"
              + " store its line is sold at, which is the whole business's to decide. Nothing"
              + " changes.")
  @APIResponse(
      responseCode = "404",
      description =
          "No such variant in this business, or it is not this product's (VARIANT_NOT_FOUND)")
  @Tag(name = "Variants")
  @DELETE
  @Path("/products/{id}/variants/{variantId}")
  public ApiResponse<VariantResponse> delistVariant(
      @PathParam("id") UUID productId, @PathParam("variantId") UUID variantId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toVariant(service.delistVariant(ctx, productId, variantId)));
  }

  /**
   * Puts a delisted variant back on sale.
   *
   * <p>The undo of {@link #delistVariant}, with the same door: management's, and of the whole
   * business, asked before the variant is looked up.
   *
   * @param productId the product id (path parameter)
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 403 FORBIDDEN} below management; {@code 403
   *     BUSINESS_WIDE_ONLY} for a manager held to stores; {@code 404} no such variant of this
   *     product in this business; {@code 409} not delisted, or its product is delisted
   */
  @Operation(
      summary = "Relist a delisted variant",
      description =
          "INACTIVE -> ACTIVE: the variant scans at the till and lists in the shop again, at every"
              + " store its line is sold at. For an owner or a manager of the whole business,"
              + " whatever the line's range: who may is judged before the variant is looked up.")
  @APIResponse(responseCode = "200", description = "Variant relisted")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " it puts the variant back on sale at every store its line is sold at. Nothing"
              + " changes.")
  @APIResponse(
      responseCode = "404",
      description =
          "No such variant in this business, or it is not this product's (VARIANT_NOT_FOUND)")
  @APIResponse(
      responseCode = "409",
      description =
          "The variant is on sale already (VARIANT_NOT_DELISTED), or its product is delisted"
              + " (VARIANT_PRODUCT_NOT_ON_SALE)")
  @Tag(name = "Variants")
  @POST
  @Path("/products/{id}/variants/{variantId}/relist")
  public ApiResponse<VariantResponse> relistVariant(
      @PathParam("id") UUID productId, @PathParam("variantId") UUID variantId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toVariant(service.relistVariant(ctx, productId, variantId)));
  }

  // ── UOM ──────────────────────────────────────────────────────────────────

  /** Lists UOM classes. */
  @Operation(summary = "List UOM classes")
  @Tag(name = "Units of Measure")
  @APIResponse(responseCode = "200", description = "List UOM classes")
  @GET
  @Path("/uom/classes")
  public ApiResponse<List<UomClassResponse>> listUomClasses() {
    return ApiResponse.ok(service.listUomClasses().stream().map(Mappers::toUomClass).toList());
  }

  /**
   * Lists UOM unit definitions.
   *
   * <p>Optionally filtered by ?class=.
   *
   * @param classCode the class code (query parameter)
   */
  @Operation(summary = "List UOM unit definitions", description = "Optionally filtered by ?class=.")
  @Tag(name = "Units of Measure")
  @APIResponse(responseCode = "200", description = "List UOM unit definitions")
  @GET
  @Path("/uom/units")
  public ApiResponse<List<UomDefinitionResponse>> listUomUnits(
      @QueryParam("class") String classCode) {
    return ApiResponse.ok(
        service.listUomDefinitions(classCode).stream().map(Mappers::toUomDefinition).toList());
  }

  /**
   * Converts a quantity between two UOMs.
   *
   * <p>Uses a variant-specific conversion factor when ?variant= is given and one exists, else falls
   * back to the standard class-wide factor.
   *
   * @param from the from (query parameter)
   * @param to the to (query parameter)
   * @param qty the qty (query parameter)
   * @param variantId the variant id (query parameter)
   * @throws com.storeql.web.ApiException {@code 400} from, to, or qty missing; {@code 404} no
   *     conversion path from the source to the target UOM
   */
  @Operation(
      summary = "Convert a quantity between two UOMs",
      description =
          "Uses a variant-specific conversion factor when ?variant= is given and one exists,"
              + " else falls back to the standard class-wide factor.")
  @APIResponse(responseCode = "400", description = "from, to, or qty missing")
  @APIResponse(
      responseCode = "404",
      description = "No conversion path from the source to the target UOM")
  @Tag(name = "Units of Measure")
  @GET
  @Path("/uom/convert")
  public ApiResponse<ConvertResult> convertUom(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("qty") BigDecimal qty,
      @QueryParam("variant") String variantId) {
    if (from == null || to == null || qty == null) {
      throw new com.storeql.web.ApiException(
          400, "MISSING_PARAM", "from, to, and qty are required", List.of(), null);
    }
    UUID variantUuid = parseOptional(variantId, "variant");
    UUID tenantId = variantUuid != null ? ctx.requireTenantId() : null;
    return ApiResponse.ok(
        service.convert(
            tenantId,
            variantUuid,
            from.toUpperCase(Locale.ROOT),
            to.toUpperCase(Locale.ROOT),
            qty));
  }

  /**
   * Upserts a variant-specific UOM conversion factor.
   *
   * <p>Creates or replaces the factor between two UOMs for a specific variant.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Upsert a variant-specific UOM conversion factor",
      description = "Creates or replaces the factor between two UOMs for a specific variant.")
  @Tag(name = "Units of Measure")
  @APIResponse(
      responseCode = "200",
      description = "Upsert a variant-specific UOM conversion factor")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/uom/item-conversions")
  public Response upsertItemConversion(UomItemConversionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID variantId = Ids.parse(req.variantId());
    service.requireVariantLineHeld(ctx, variantId);
    return Response.status(Response.Status.OK)
        .entity(
            ApiResponse.ok(
                Mappers.toUomItemConversion(
                    service.upsertItemConversion(
                        tenantId,
                        variantId,
                        req.fromUom().toUpperCase(Locale.ROOT),
                        req.toUom().toUpperCase(Locale.ROOT),
                        req.factor()))))
        .build();
  }

  /**
   * Lists variant-specific UOM conversions.
   *
   * <p>Optionally filtered by ?variant=.
   *
   * @param variantId the variant id (query parameter)
   */
  @Operation(
      summary = "List variant-specific UOM conversions",
      description = "Optionally filtered by ?variant=.")
  @Tag(name = "Units of Measure")
  @APIResponse(responseCode = "200", description = "List variant-specific UOM conversions")
  @GET
  @Path("/uom/item-conversions")
  public ApiResponse<List<UomItemConversionResponse>> listItemConversions(
      @QueryParam("variant") String variantId) {
    UUID tenantId = ctx.requireTenantId();
    UUID variantUuid = parseOptional(variantId, "variant");
    return ApiResponse.ok(
        service.listItemConversions(tenantId, variantUuid).stream()
            .map(Mappers::toUomItemConversion)
            .toList());
  }

  /**
   * Deletes a variant-specific UOM conversion.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} item conversion not found
   */
  @Operation(summary = "Delete a variant-specific UOM conversion")
  @APIResponse(responseCode = "404", description = "Item conversion not found")
  @Tag(name = "Units of Measure")
  @DELETE
  @Path("/uom/item-conversions/{id}")
  public Response deleteItemConversion(@PathParam("id") UUID id) {
    boolean deleted = service.deleteItemConversion(ctx, id);
    if (!deleted) {
      throw new com.storeql.web.ApiException(
          404, "CONVERSION_NOT_FOUND", "Item conversion not found", List.of(), null);
    }
    return Response.noContent().build();
  }

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  /**
   * Creates an item attribute template.
   *
   * @param req the request body
   * @return template created ({@code 201})
   */
  @Operation(summary = "Create an item attribute template")
  @APIResponse(responseCode = "201", description = "Template created")
  @Tag(name = "Item Templates")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining item templates is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/item-templates")
  public Response createTemplate(CreateItemTemplateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining item templates");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(
        Mappers.toTemplate(
            service.createTemplate(
                tenantId, req.name().trim(), req.description(), req.attributes())));
  }

  /** Lists item templates. */
  @Operation(summary = "List item templates")
  @Tag(name = "Item Templates")
  @APIResponse(responseCode = "200", description = "List item templates")
  @GET
  @Path("/item-templates")
  public ApiResponse<List<ItemTemplateResponse>> listTemplates() {
    return ApiResponse.ok(
        service.listTemplates(ctx.requireTenantId()).stream().map(Mappers::toTemplate).toList());
  }

  /**
   * Gets an item template by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} template not found
   */
  @Operation(summary = "Get an item template by id")
  @APIResponse(responseCode = "404", description = "Template not found")
  @Tag(name = "Item Templates")
  @GET
  @Path("/item-templates/{id}")
  public ApiResponse<ItemTemplateResponse> getTemplate(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toTemplate(service.getTemplate(ctx.requireTenantId(), id)));
  }

  /**
   * Deactivates an item template.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} template not found
   */
  @Operation(summary = "Deactivate an item template")
  @APIResponse(responseCode = "404", description = "Template not found")
  @Tag(name = "Item Templates")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining item templates is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/item-templates/{id}")
  public ApiResponse<ItemTemplateResponse> deactivateTemplate(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining item templates");
    return ApiResponse.ok(
        Mappers.toTemplate(service.deactivateTemplate(ctx.requireTenantId(), id)));
  }

  /**
   * Applies a template's attributes to a variant.
   *
   * <p>Publishes ItemTemplateApplied.
   *
   * @param templateId the template id (path parameter)
   * @param variantId the variant id (path parameter)
   */
  @Operation(
      summary = "Apply a template's attributes to a variant",
      description = "Publishes ItemTemplateApplied.")
  @Tag(name = "Item Templates")
  @APIResponse(responseCode = "200", description = "Apply a template's attributes to a variant")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/item-templates/{id}/apply/{variantId}")
  public ApiResponse<ItemTemplateApplicationResponse> applyTemplate(
      @PathParam("id") UUID templateId, @PathParam("variantId") UUID variantId) {
    service.requireVariantLineHeld(ctx, variantId);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toTemplateApplication(service.applyTemplate(tenantId, variantId, templateId)));
  }

  // ── Supplier / Customer Cross-References (Gap #33) ───────────────────────

  /**
   * Creates a supplier/customer cross-reference for a variant.
   *
   * <p>partyType must be SUPPLIER or CUSTOMER.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return cross-reference created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 400} partyType is not SUPPLIER or CUSTOMER, or
   *     partyId is not a UUID; {@code 404} variant not found
   */
  @Operation(
      summary = "Create a supplier/customer cross-reference for a variant",
      description = "partyType must be SUPPLIER or CUSTOMER.")
  @APIResponse(responseCode = "201", description = "Cross-reference created")
  @APIResponse(
      responseCode = "400",
      description = "partyType is not SUPPLIER or CUSTOMER, or partyId is not a UUID")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Cross-References")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/variants/{variantId}/cross-references")
  public Response createCrossReference(
      @PathParam("variantId") UUID variantId, CreateItemCrossReferenceRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    return created(
        Mappers.toCrossReference(
            service.createCrossReference(ctx.requireTenantId(), variantId, req)));
  }

  /**
   * Lists a variant's cross-references.
   *
   * <p>Optionally filtered by ?partyType= (SUPPLIER or CUSTOMER).
   *
   * @param variantId the variant id (path parameter)
   * @param partyType the party type (query parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(
      summary = "List a variant's cross-references",
      description = "Optionally filtered by ?partyType= (SUPPLIER or CUSTOMER).")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Cross-References")
  @GET
  @Path("/products/variants/{variantId}/cross-references")
  public ApiResponse<List<ItemCrossReferenceResponse>> listCrossReferences(
      @PathParam("variantId") UUID variantId, @QueryParam("partyType") String partyType) {
    return ApiResponse.ok(
        service.listCrossReferences(ctx.requireTenantId(), variantId, partyType).stream()
            .map(Mappers::toCrossReference)
            .toList());
  }

  /**
   * Deletes a cross-reference.
   *
   * @param variantId the variant id (path parameter)
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} cross reference not found
   */
  @Operation(summary = "Delete a cross-reference")
  @APIResponse(responseCode = "404", description = "Cross reference not found")
  @Tag(name = "Cross-References")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/variants/{variantId}/cross-references/{id}")
  public Response deleteCrossReference(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    service.requireVariantLineHeld(ctx, variantId);
    service.deleteCrossReference(ctx.requireTenantId(), variantId, id);
    return Response.noContent().build();
  }

  // ── Item Relationships (Gap #32) ─────────────────────────────────────────

  /**
   * Creates a related-item link between two variants.
   *
   * <p>relationshipType must be SUBSTITUTE or COMPLEMENTARY. A variant cannot relate to itself.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return relationship created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 400} relationshipType invalid, or relatedVariantId
   *     equals variantId; {@code 404} either variant not found
   */
  @Operation(
      summary = "Create a related-item link between two variants",
      description =
          "relationshipType must be SUBSTITUTE or COMPLEMENTARY. A variant cannot relate to"
              + " itself.")
  @APIResponse(responseCode = "201", description = "Relationship created")
  @APIResponse(
      responseCode = "400",
      description = "relationshipType invalid, or relatedVariantId equals variantId")
  @APIResponse(responseCode = "404", description = "Either variant not found")
  @Tag(name = "Item Relationships")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/variants/{variantId}/relationships")
  public Response createRelationship(
      @PathParam("variantId") UUID variantId, CreateItemRelationshipRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(Mappers.toRelationship(service.createRelationship(tenantId, variantId, req)));
  }

  /**
   * Lists a variant's item relationships.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(summary = "List a variant's item relationships")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Item Relationships")
  @GET
  @Path("/products/variants/{variantId}/relationships")
  public ApiResponse<List<ItemRelationshipResponse>> listRelationships(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listRelationships(tenantId, variantId).stream()
            .map(Mappers::toRelationship)
            .toList());
  }

  /**
   * Deletes an item relationship.
   *
   * @param variantId the variant id (path parameter)
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} item relationship not found
   */
  @Operation(summary = "Delete an item relationship")
  @APIResponse(responseCode = "404", description = "Item relationship not found")
  @Tag(name = "Item Relationships")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/variants/{variantId}/relationships/{id}")
  public Response deleteRelationship(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    service.requireVariantLineHeld(ctx, variantId);
    service.deleteRelationship(ctx.requireTenantId(), variantId, id);
    return Response.noContent().build();
  }

  // ── Item Revisions (Gap #12) ──────────────────────────────────────────────

  /**
   * Creates a dated revision of a variant's spec.
   *
   * <p>Publishes ItemRevisionCreated.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return revision created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 400} effectiveDate is not a valid date
   */
  @Operation(
      summary = "Create a dated revision of a variant's spec",
      description = "Publishes ItemRevisionCreated.")
  @APIResponse(responseCode = "201", description = "Revision created")
  @APIResponse(responseCode = "400", description = "effectiveDate is not a valid date")
  @Tag(name = "Item Revisions")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/variants/{variantId}/revisions")
  public Response createRevision(
      @PathParam("variantId") UUID variantId, CreateRevisionRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    java.time.LocalDate effectiveDate =
        com.storeql.web.Parsing.date(req.effectiveDate(), "effectiveDate");
    var rev =
        service.createRevision(
            tenantId, variantId, req.revision(), req.description(), effectiveDate);
    return created(Mappers.toRevision(rev));
  }

  /**
   * Lists a variant's revisions.
   *
   * @param variantId the variant id (path parameter)
   */
  @Operation(summary = "List a variant's revisions")
  @Tag(name = "Item Revisions")
  @APIResponse(responseCode = "200", description = "List a variant's revisions")
  @GET
  @Path("/products/variants/{variantId}/revisions")
  public ApiResponse<List<ItemRevisionResponse>> listRevisions(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listRevisions(tenantId, variantId).stream().map(Mappers::toRevision).toList());
  }

  /**
   * Gets a variant's current active revision.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no active revision for this variant
   */
  @Operation(summary = "Get a variant's current active revision")
  @APIResponse(responseCode = "404", description = "No active revision for this variant")
  @Tag(name = "Item Revisions")
  @GET
  @Path("/products/variants/{variantId}/revisions/current")
  public ApiResponse<ItemRevisionResponse> currentRevision(@PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toRevision(service.currentRevision(tenantId, variantId)));
  }

  /**
   * Gets a specific revision by id.
   *
   * @param variantId the variant id (path parameter)
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such revision
   */
  @Operation(summary = "Get a specific revision by id")
  @APIResponse(responseCode = "404", description = "No such revision")
  @Tag(name = "Item Revisions")
  @GET
  @Path("/products/variants/{variantId}/revisions/{id}")
  public ApiResponse<ItemRevisionResponse> getRevision(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toRevision(service.getRevision(tenantId, id)));
  }

  // ── Bulk Import ──────────────────────────────────────────────────────────

  /**
   * Import categories and products+variants in one call.
   *
   * <p>Body: { "categories": [...], "products": [...] }
   *
   * <p>Each category: { "name": "Electronics", "parentName": null } Each product: { "name": "...",
   * "categoryName": "Electronics", "brandName": "Apple", "sellableOnline": true, "sellablePos":
   * true, "variants": [{ "sku": "SKU-001", "barcode": "...", "unit": "EA" }] }
   *
   * <p>Duplicate categories are skipped. Duplicate SKUs return an error entry but the rest
   * continue. Always returns 200 with a result summary and any per-row errors.
   *
   * <p>The body is deliberately not passed to {@code Validations.validate} as a whole: every
   * category, product and variant carries constraints, and {@code ProductService.bulkImport} checks
   * them row by row, so that a row that breaks one is that row's error and the rest of the sheet is
   * still imported. Validating first would turn one blank name into a {@code 400} for everything.
   * What no row can be blamed for (a missing body, an unknown {@code mode}, a hole in a list such
   * as {@code "products":[{…}, null]}) is refused before anything is written; the hole by the
   * platform's own {@code Validations}, which the service hands the rows without their rules.
   */
  @Operation(
      summary = "Bulk-import categories and products/variants",
      description =
          "Imports categories then products+variants from a JSON payload. Never hard-fails the"
              + " batch: duplicate categories are skipped, a row that breaks a constraint or"
              + " names a store that is not an id is that row's error, duplicate SKUs return a"
              + " per-row error but the rest continue, and this always returns 200 with a result"
              + " summary plus any per-row errors. Only a missing body (BODY_REQUIRED), a mode"
              + " that is neither ADD nor REPLACE (IMPORT_MODE_INVALID) or a null entry in a list"
              + " (VALIDATION_FAILED, naming it: \"products[1]: must not be null\") is a 400, and"
              + " nothing is written then. Every store a row names (storeIds) is checked for the"
              + " whole sheet before its first row is written: one that is not the business's"
              + " is a 404 (PRODUCT_STORE_NOT_FOUND), one a manager held to stores does not keep"
              + " a 403 (STORE_ACCESS_DENIED), and stores tenant-svc cannot vouch for a 503"
              + " (TENANT_STORES_UNAVAILABLE); nothing is imported then. Where a row's product is"
              + " sold is then judged row by row, as the catalogue stands: a new product naming no"
              + " store is sold at every store, or, imported by a manager held to stores, at"
              + " theirs, as POST /admin/products does; and in REPLACE a row's stores are added to"
              + " the product it finds only as PUT /admin/products/{id}/stores would allow them, so"
              + " a product sold at every store is never narrowed to a held manager's stores — a"
              + " row refused so is that row's error (\"BUSINESS_WIDE_ONLY: …\"), nothing of it"
              + " written. REPLACE drops the variant already holding a row's SKU and writes the"
              + " row's own in its place, for an owner or a manager of the whole business only: a"
              + " manager held to stores never drops a variant, so a SKU of theirs that is already"
              + " held is that variant's error (\"BUSINESS_WIDE_ONLY: …\"), the variant there left"
              + " as it is, while the row's other variants are imported.")
  @APIResponse(
      responseCode = "400",
      description =
          "Request body is missing, mode is neither ADD nor REPLACE, or a list has a null entry;"
              + " nothing written")
  @APIResponse(
      responseCode = "403",
      description =
          "A row names a store the caller is not held to (STORE_ACCESS_DENIED); nothing written")
  @APIResponse(
      responseCode = "404",
      description =
          "A row names a store that is not one of this business's (PRODUCT_STORE_NOT_FOUND);"
              + " nothing written")
  @APIResponse(
      responseCode = "503",
      description =
          "Rows name stores and tenant-svc cannot say which stores are the business's"
              + " (TENANT_STORES_UNAVAILABLE); nothing written")
  @Tag(name = "Bulk Import")
  @POST
  @Path("/import")
  public ApiResponse<BulkImportResult> bulkImport(BulkImportRequest req) {
    if (req == null) {
      throw com.storeql.web.ApiException.badRequest(
          com.storeql.web.ErrorCodes.BODY_REQUIRED, "Request body required");
    }
    return ApiResponse.ok(service.bulkImport(ctx, req));
  }

  /**
   * Import a supplier catalogue CSV (GTBJ format). Body: JSON with {@code csv} (raw CSV text),
   * optional {@code mode} (ADD|REPLACE), optional {@code storeNameToId} map (store name → UUID
   * string, resolved client-side; each store the sheet uses is then checked against the business's
   * own, as tenant-svc lists them).
   *
   * <p>Validated as a whole before anything is written, and everything the sheet will ask of other
   * services is settled first too: see {@code ProductService.importSupplierCsv}.
   */
  @Operation(
      summary = "Import a supplier catalogue CSV",
      description =
          "Parses a raw CSV (GTBJ format) into categories/products/variants, then optionally"
              + " receives stock and/or sets prices for the imported variants via inventory-svc"
              + " and pricing-svc. storeNameToId is resolved client-side. Everything that can be"
              + " refused is refused before anything is written: a destination store, or a store"
              + " the sheet's Store column is mapped to, that is not the business's is a 404 and"
              + " one the caller is not held to a 403; prices without pricing.write are a 403;"
              + " and stores tenant-svc cannot vouch for, or a stock or price service that is not"
              + " available, are a 503; nothing is imported then. Stock and prices are then asked"
              + " as the caller (their stores, permissions and login), so inventory-svc and"
              + " pricing-svc judge the person, not the role tier; stock goes in calls of at most"
              + " storeql.product.inventory.receive-max-lines lines. Once the catalogue is"
              + " written, a stock or price step that fails is reported in stockErrors or"
              + " priceErrors beside the result, with a 200, and is not thrown. Once inventory-svc"
              + " or pricing-svc gives no answer no further call is made to it, and every line or"
              + " price not done is reported with the reason, in plain words. A quantity or price"
              + " that is not a plain number (digits and a decimal point: no comma, currency sign"
              + " or exponent) is not received or set, and is reported in stockErrors or"
              + " priceErrors by SKU; the variant keeps the supplier's case size and trade price"
              + " as written. A quantity stock is not kept in (not above zero, more than three"
              + " decimal places or fifteen whole digits) is reported in stockErrors by SKU and is"
              + " not sent, so it never stops the sheet's other lines being received. A price is money in the sheet's currency: one with"
              + " more decimal places than that currency has (ISO 4217: two for EUR, none for"
              + " JPY, three for KWD) or below zero is not set, and is reported in priceErrors by"
              + " SKU. A product row is held to the same store rules as POST /admin/import: a new"
              + " product with no Store is sold at every store, or, imported by a manager held to"
              + " stores, at theirs. And to the same REPLACE rule: a manager held to stores never"
              + " drops a variant already there, so a SKU of theirs that is already held is that"
              + " variant's error (\"BUSINESS_WIDE_ONLY: …\") and receives no stock and no price"
              + " from the sheet.")
  @APIResponse(
      responseCode = "400",
      description =
          "csv missing or blank (VALIDATION_FAILED), no body (BODY_REQUIRED), mode neither ADD"
              + " nor REPLACE (IMPORT_MODE_INVALID), storeId not an id (INVALID_UUID), currency"
              + " not ISO 4217 (CURRENCY_INVALID), CSV empty (CSV_EMPTY) or missing its"
              + " description column (CSV_MISSING_COLUMNS)")
  @APIResponse(
      responseCode = "403",
      description =
          "The destination store, or a store the Store column is mapped to, is not one the"
              + " caller is held to (STORE_ACCESS_DENIED), or the sheet carries prices and the"
              + " caller lacks pricing.write (PERMISSION_DENIED). Nothing was imported")
  @APIResponse(
      responseCode = "404",
      description =
          "The destination store, or a store the Store column is mapped to, is not one of this"
              + " business's (PRODUCT_STORE_NOT_FOUND). Nothing was imported")
  @APIResponse(
      responseCode = "503",
      description =
          "The request names stores and tenant-svc cannot say which are the business's"
              + " (TENANT_STORES_UNAVAILABLE), the sheet carries quantities for a store and"
              + " inventory-svc is not available (INVENTORY_UNAVAILABLE), or it carries prices and"
              + " pricing-svc is not available (PRICING_UNAVAILABLE), or the business's currency"
              + " cannot be read (TENANT_PROFILE_UNAVAILABLE). Nothing was imported")
  @Tag(name = "Bulk Import")
  @POST
  @Path("/import/supplier-csv")
  public ApiResponse<BulkImportResult> importSupplierCsv(
      com.storeql.product.dto.Dtos.SupplierCsvImportRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.importSupplierCsv(ctx, req));
  }

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  /**
   * Creates a merchandising catalog group.
   *
   * @param req the request body
   * @return catalog group created ({@code 201})
   */
  @Operation(summary = "Create a merchandising catalog group")
  @APIResponse(responseCode = "201", description = "Catalog group created")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining catalog groups is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/catalog-groups")
  public Response createCatalogGroup(CreateCatalogGroupRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining catalog groups");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var group = service.createCatalogGroup(tenantId, req);
    return created(Mappers.toCatalogGroup(group, List.of()));
  }

  /**
   * Lists catalog groups.
   *
   * <p>Includes each group's elements.
   */
  @Operation(summary = "List catalog groups", description = "Includes each group's elements.")
  @Tag(name = "Catalog Groups")
  @APIResponse(responseCode = "200", description = "List catalog groups")
  @GET
  @Path("/catalog-groups")
  public ApiResponse<List<CatalogGroupResponse>> listCatalogGroups() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listCatalogGroups(tenantId).stream()
            .map(
                g ->
                    Mappers.toCatalogGroup(
                        g,
                        service.listCatalogGroupElements(tenantId, g.id()).stream()
                            .map(Mappers::toCatalogGroupElement)
                            .toList()))
            .toList());
  }

  /**
   * Gets a catalog group by id.
   *
   * <p>Includes the group's elements.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} catalog group not found
   */
  @Operation(summary = "Get a catalog group by id", description = "Includes the group's elements.")
  @APIResponse(responseCode = "404", description = "Catalog group not found")
  @Tag(name = "Catalog Groups")
  @GET
  @Path("/catalog-groups/{id}")
  public ApiResponse<CatalogGroupResponse> getCatalogGroup(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var group = service.getCatalogGroup(tenantId, id);
    var elements =
        service.listCatalogGroupElements(tenantId, id).stream()
            .map(Mappers::toCatalogGroupElement)
            .toList();
    return ApiResponse.ok(Mappers.toCatalogGroup(group, elements));
  }

  /**
   * Deactivates a catalog group.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} catalog group not found
   */
  @Operation(summary = "Deactivate a catalog group")
  @APIResponse(responseCode = "404", description = "Catalog group not found")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining catalog groups is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/catalog-groups/{id}")
  public Response deactivateCatalogGroup(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining catalog groups");
    service.deactivateCatalogGroup(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  /**
   * Adds an element (attribute) to a catalog group.
   *
   * <p>dataType must be TEXT, NUMBER, BOOLEAN, or DATE.
   *
   * @param groupId the group id (path parameter)
   * @param req the request body
   * @return element created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 400} dataType is not TEXT, NUMBER, BOOLEAN, or
   *     DATE; {@code 404} catalog group not found
   */
  @Operation(
      summary = "Add an element (attribute) to a catalog group",
      description = "dataType must be TEXT, NUMBER, BOOLEAN, or DATE.")
  @APIResponse(responseCode = "201", description = "Element created")
  @APIResponse(responseCode = "400", description = "dataType is not TEXT, NUMBER, BOOLEAN, or DATE")
  @APIResponse(responseCode = "404", description = "Catalog group not found")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining catalog groups is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/catalog-groups/{groupId}/elements")
  public Response createCatalogGroupElement(
      @PathParam("groupId") UUID groupId, CreateCatalogGroupElementRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining catalog groups");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(
        Mappers.toCatalogGroupElement(service.createCatalogGroupElement(tenantId, groupId, req)));
  }

  /**
   * Deletes a catalog group element.
   *
   * @param groupId the group id (path parameter)
   * @param elementId the element id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} catalog group element not found
   */
  @Operation(summary = "Delete a catalog group element")
  @APIResponse(responseCode = "404", description = "Catalog group element not found")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining catalog groups is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/catalog-groups/{groupId}/elements/{elementId}")
  public Response deleteCatalogGroupElement(
      @PathParam("groupId") UUID groupId, @PathParam("elementId") UUID elementId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining catalog groups");
    service.deleteCatalogGroupElement(ctx.requireTenantId(), elementId);
    return Response.noContent().build();
  }

  /**
   * Assigns a variant to a catalog group.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return assignment created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 400} groupId missing or malformed; {@code 404}
   *     variant or catalog group not found
   */
  @Operation(summary = "Assign a variant to a catalog group")
  @APIResponse(responseCode = "201", description = "Assignment created")
  @APIResponse(responseCode = "400", description = "groupId missing or malformed")
  @APIResponse(responseCode = "404", description = "Variant or catalog group not found")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/variants/{variantId}/catalog-assignment")
  public Response assignCatalogGroup(
      @PathParam("variantId") UUID variantId, AssignCatalogGroupRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(
        Mappers.toCatalogAssignment(service.assignCatalogGroup(tenantId, variantId, req)));
  }

  /**
   * Gets a variant's catalog group assignment.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no catalog assignment for this variant
   */
  @Operation(summary = "Get a variant's catalog group assignment")
  @APIResponse(responseCode = "404", description = "No catalog assignment for this variant")
  @Tag(name = "Catalog Groups")
  @GET
  @Path("/products/variants/{variantId}/catalog-assignment")
  public ApiResponse<CatalogAssignmentResponse> getCatalogAssignment(
      @PathParam("variantId") UUID variantId) {
    return ApiResponse.ok(
        Mappers.toCatalogAssignment(
            service.getCatalogAssignment(ctx.requireTenantId(), variantId)));
  }

  /**
   * Updates a variant's catalog group element values.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   */
  @Operation(summary = "Update a variant's catalog group element values")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "200",
      description = "Update a variant's catalog group element values")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/variants/{variantId}/catalog-assignment")
  public ApiResponse<CatalogAssignmentResponse> updateCatalogAssignment(
      @PathParam("variantId") UUID variantId, UpdateCatalogAssignmentRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toCatalogAssignment(service.updateCatalogAssignment(tenantId, variantId, req)));
  }

  /**
   * Removes a variant's catalog group assignment.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no catalog assignment for this variant
   */
  @Operation(summary = "Remove a variant's catalog group assignment")
  @APIResponse(responseCode = "404", description = "No catalog assignment for this variant")
  @Tag(name = "Catalog Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/variants/{variantId}/catalog-assignment")
  public Response deleteCatalogAssignment(@PathParam("variantId") UUID variantId) {
    service.requireVariantLineHeld(ctx, variantId);
    service.deleteCatalogAssignment(ctx.requireTenantId(), variantId);
    return Response.noContent().build();
  }

  // ── Container Types (Gap #37) ────────────────────────────────────────────

  /**
   * Creates a container type.
   *
   * <p>Packaging/container type used for variant packing hierarchy.
   *
   * @param req the request body
   * @return container type created ({@code 201})
   */
  @Operation(
      summary = "Create a container type",
      description = "Packaging/container type used for variant packing hierarchy.")
  @APIResponse(responseCode = "201", description = "Container type created")
  @Tag(name = "Container Types")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining container types is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/container-types")
  public Response createContainerType(CreateContainerTypeRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining container types");
    Validations.validate(req);
    return created(
        Mappers.toContainerType(service.createContainerType(ctx.requireTenantId(), req)));
  }

  /** Lists container types. */
  @Operation(summary = "List container types")
  @Tag(name = "Container Types")
  @APIResponse(responseCode = "200", description = "List container types")
  @GET
  @Path("/container-types")
  public ApiResponse<List<ContainerTypeResponse>> listContainerTypes() {
    return ApiResponse.ok(
        service.listContainerTypes(ctx.requireTenantId()).stream()
            .map(Mappers::toContainerType)
            .toList());
  }

  /**
   * Gets a container type by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} container type not found
   */
  @Operation(summary = "Get a container type by id")
  @APIResponse(responseCode = "404", description = "Container type not found")
  @Tag(name = "Container Types")
  @GET
  @Path("/container-types/{id}")
  public ApiResponse<ContainerTypeResponse> getContainerType(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toContainerType(service.getContainerType(ctx.requireTenantId(), id)));
  }

  /**
   * Updates a container type.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} container type not found
   */
  @Operation(summary = "Update a container type")
  @APIResponse(responseCode = "404", description = "Container type not found")
  @Tag(name = "Container Types")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining container types is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @PUT
  @Path("/container-types/{id}")
  public ApiResponse<ContainerTypeResponse> updateContainerType(
      @PathParam("id") UUID id, UpdateContainerTypeRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining container types");
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toContainerType(service.updateContainerType(ctx.requireTenantId(), id, req)));
  }

  /**
   * Deactivates a container type.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} container type not found
   */
  @Operation(summary = "Deactivate a container type")
  @APIResponse(responseCode = "404", description = "Container type not found")
  @Tag(name = "Container Types")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining container types is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/container-types/{id}")
  public ApiResponse<ContainerTypeResponse> deactivateContainerType(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining container types");
    return ApiResponse.ok(
        Mappers.toContainerType(service.deactivateContainerType(ctx.requireTenantId(), id)));
  }

  /**
   * Links a variant to a container type.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return container link created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 404} variant or container type not found
   */
  @Operation(summary = "Link a variant to a container type")
  @APIResponse(responseCode = "201", description = "Container link created")
  @APIResponse(responseCode = "404", description = "Variant or container type not found")
  @Tag(name = "Container Types")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/variants/{variantId}/container-links")
  public Response createVariantContainerLink(
      @PathParam("variantId") UUID variantId, CreateVariantContainerLinkRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var link = service.createVariantContainerLink(tenantId, variantId, req);
    var ct = service.getContainerType(tenantId, link.containerTypeId());
    return created(Mappers.toVariantContainerLink(link, ct.code(), ct.name()));
  }

  /**
   * Lists a variant's container links.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(summary = "List a variant's container links")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Container Types")
  @GET
  @Path("/products/variants/{variantId}/container-links")
  public ApiResponse<List<VariantContainerLinkResponse>> listVariantContainerLinks(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listVariantContainerLinks(tenantId, variantId).stream()
            .map(
                l -> {
                  var ct = service.getContainerType(tenantId, l.containerTypeId());
                  return Mappers.toVariantContainerLink(l, ct.code(), ct.name());
                })
            .toList());
  }

  /**
   * Deletes a variant-to-container-type link.
   *
   * @param variantId the variant id (path parameter)
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} container link not found
   */
  @Operation(summary = "Delete a variant-to-container-type link")
  @APIResponse(responseCode = "404", description = "Container link not found")
  @Tag(name = "Container Types")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/variants/{variantId}/container-links/{id}")
  public Response deleteVariantContainerLink(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    service.requireVariantLineHeld(ctx, variantId);
    service.deleteVariantContainerLink(ctx.requireTenantId(), variantId, id);
    return Response.noContent().build();
  }

  // ── Item Attribute Groups (Gap #36) ─────────────────────────────────────

  /**
   * Lists structured attribute-group definitions.
   *
   * <p>Includes each group's fields.
   */
  @Operation(
      summary = "List structured attribute-group definitions",
      description = "Includes each group's fields.")
  @Tag(name = "Attribute Groups")
  @APIResponse(responseCode = "200", description = "List structured attribute-group definitions")
  @GET
  @Path("/attribute-groups")
  public ApiResponse<List<ItemAttributeGroupResponse>> listAttributeGroups() {
    return ApiResponse.ok(
        service.listAttributeGroups().stream()
            .map(
                g ->
                    Mappers.toAttributeGroup(
                        g,
                        service.listAttributeGroupFields(g.groupCode()).stream()
                            .map(Mappers::toAttributeGroupField)
                            .toList()))
            .toList());
  }

  /**
   * Gets a structured attribute group by code.
   *
   * <p>Includes the group's fields.
   *
   * @param groupCode the group code (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} attribute group not found
   */
  @Operation(
      summary = "Get a structured attribute group by code",
      description = "Includes the group's fields.")
  @APIResponse(responseCode = "404", description = "Attribute group not found")
  @Tag(name = "Attribute Groups")
  @GET
  @Path("/attribute-groups/{groupCode}")
  public ApiResponse<ItemAttributeGroupResponse> getAttributeGroup(
      @PathParam("groupCode") String groupCode) {
    var group = service.getAttributeGroup(groupCode);
    var fields =
        service.listAttributeGroupFields(group.groupCode()).stream()
            .map(Mappers::toAttributeGroupField)
            .toList();
    return ApiResponse.ok(Mappers.toAttributeGroup(group, fields));
  }

  /**
   * Sets a variant's values for an attribute group.
   *
   * @param variantId the variant id (path parameter)
   * @param groupCode the group code (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} attribute group or variant not found
   */
  @Operation(summary = "Set a variant's values for an attribute group")
  @APIResponse(responseCode = "404", description = "Attribute group or variant not found")
  @Tag(name = "Attribute Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/variants/{variantId}/attribute-groups/{groupCode}")
  public ApiResponse<VariantAttributeGroupValuesResponse> upsertVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId,
      @PathParam("groupCode") String groupCode,
      UpsertVariantAttributeGroupRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toVariantAttributeGroupValues(
            service.upsertVariantAttributeGroupValues(
                tenantId, variantId, groupCode, req.values())));
  }

  /**
   * Lists a variant's attribute group values (all groups).
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(summary = "List a variant's attribute group values (all groups)")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Attribute Groups")
  @GET
  @Path("/products/variants/{variantId}/attribute-groups")
  public ApiResponse<List<VariantAttributeGroupValuesResponse>> listVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listVariantAttributeGroupValues(tenantId, variantId).stream()
            .map(Mappers::toVariantAttributeGroupValues)
            .toList());
  }

  /**
   * Gets a variant's values for one attribute group.
   *
   * @param variantId the variant id (path parameter)
   * @param groupCode the group code (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no attribute group values for this group on
   *     this variant
   */
  @Operation(summary = "Get a variant's values for one attribute group")
  @APIResponse(
      responseCode = "404",
      description = "No attribute group values for this group on this variant")
  @Tag(name = "Attribute Groups")
  @GET
  @Path("/products/variants/{variantId}/attribute-groups/{groupCode}")
  public ApiResponse<VariantAttributeGroupValuesResponse> getVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId, @PathParam("groupCode") String groupCode) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toVariantAttributeGroupValues(
            service.getVariantAttributeGroupValues(tenantId, variantId, groupCode)));
  }

  /**
   * Removes a variant's values for one attribute group.
   *
   * @param variantId the variant id (path parameter)
   * @param groupCode the group code (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no attribute group values for this group on
   *     this variant
   */
  @Operation(summary = "Remove a variant's values for one attribute group")
  @APIResponse(
      responseCode = "404",
      description = "No attribute group values for this group on this variant")
  @Tag(name = "Attribute Groups")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/variants/{variantId}/attribute-groups/{groupCode}")
  public Response deleteVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId, @PathParam("groupCode") String groupCode) {
    service.requireVariantLineHeld(ctx, variantId);
    service.deleteVariantAttributeGroupValues(ctx.requireTenantId(), variantId, groupCode);
    return Response.noContent().build();
  }

  // ──────────────────────────────────────────────── category sets (Gap #39) ──

  /**
   * Creates a category set.
   *
   * <p>An alternate category hierarchy independent of the main category tree.
   *
   * @param req the request body
   * @return category set created ({@code 201})
   */
  @Operation(
      summary = "Create a category set",
      description = "An alternate category hierarchy independent of the main category tree.")
  @APIResponse(responseCode = "201", description = "Category set created")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining category sets is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/category-sets")
  public Response createCategorySet(CreateCategorySetRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining category sets");
    Validations.validate(req);
    var cs = service.createCategorySet(ctx.requireTenantId(), req);
    return created(Mappers.toCategorySet(cs));
  }

  /** Lists category sets. */
  @Operation(summary = "List category sets")
  @Tag(name = "Category Sets")
  @APIResponse(responseCode = "200", description = "List category sets")
  @GET
  @Path("/category-sets")
  public ApiResponse<List<CategorySetResponse>> listCategorySets() {
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listCategorySets(tenantId).stream().map(Mappers::toCategorySet).toList());
  }

  /**
   * Gets a category set by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category set not found
   */
  @Operation(summary = "Get a category set by id")
  @APIResponse(responseCode = "404", description = "Category set not found")
  @Tag(name = "Category Sets")
  @GET
  @Path("/category-sets/{id}")
  public ApiResponse<CategorySetResponse> getCategorySet(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toCategorySet(service.getCategorySet(ctx.requireTenantId(), id)));
  }

  /**
   * Updates a category set.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} category set not found
   */
  @Operation(summary = "Update a category set")
  @APIResponse(responseCode = "404", description = "Category set not found")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining category sets is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @PUT
  @Path("/category-sets/{id}")
  public ApiResponse<CategorySetResponse> updateCategorySet(
      @PathParam("id") UUID id, UpdateCategorySetRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining category sets");
    return ApiResponse.ok(
        Mappers.toCategorySet(service.updateCategorySet(ctx.requireTenantId(), id, req)));
  }

  /**
   * Deletes a category set.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category set not found
   */
  @Operation(summary = "Delete a category set")
  @APIResponse(responseCode = "404", description = "Category set not found")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining category sets is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/category-sets/{id}")
  public Response deleteCategorySet(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining category sets");
    service.deleteCategorySet(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  /**
   * Adds a category as a member of a category set.
   *
   * @param setId the set id (path parameter)
   * @param req the request body
   * @return member added ({@code 201})
   * @throws com.storeql.web.ApiException {@code 404} category set or category not found
   */
  @Operation(summary = "Add a category as a member of a category set")
  @APIResponse(responseCode = "201", description = "Member added")
  @APIResponse(responseCode = "404", description = "Category set or category not found")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining category sets is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @POST
  @Path("/category-sets/{id}/members")
  public Response addCategorySetMember(
      @PathParam("id") UUID setId, AddCategorySetMemberRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining category sets");
    Validations.validate(req);
    var m = service.addCategorySetMember(ctx.requireTenantId(), setId, req);
    return created(Mappers.toCategorySetMember(m));
  }

  /**
   * Lists a category set's member categories.
   *
   * @param setId the set id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category set not found
   */
  @Operation(summary = "List a category set's member categories")
  @APIResponse(responseCode = "404", description = "Category set not found")
  @Tag(name = "Category Sets")
  @GET
  @Path("/category-sets/{id}/members")
  public ApiResponse<List<CategorySetMemberResponse>> listCategorySetMembers(
      @PathParam("id") UUID setId) {
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listCategorySetMembers(tenantId, setId).stream()
            .map(Mappers::toCategorySetMember)
            .toList());
  }

  /**
   * Removes a category from a category set.
   *
   * @param setId the set id (path parameter)
   * @param categoryId the category id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category not a member of this set
   */
  @Operation(summary = "Remove a category from a category set")
  @APIResponse(responseCode = "404", description = "Category not a member of this set")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Maintaining category sets is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @DELETE
  @Path("/category-sets/{id}/members/{categoryId}")
  public Response deleteCategorySetMember(
      @PathParam("id") UUID setId, @PathParam("categoryId") UUID categoryId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Maintaining category sets");
    service.deleteCategorySetMember(ctx.requireTenantId(), setId, categoryId);
    return Response.noContent().build();
  }

  /**
   * Assigns a variant into a category set.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return assignment created ({@code 201})
   * @throws com.storeql.web.ApiException {@code 404} variant or category set not found
   */
  @Operation(summary = "Assign a variant into a category set")
  @APIResponse(responseCode = "201", description = "Assignment created")
  @APIResponse(responseCode = "404", description = "Variant or category set not found")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @POST
  @Path("/products/variants/{variantId}/category-set-assignments")
  public Response assignVariantCategorySet(
      @PathParam("variantId") UUID variantId, AssignVariantCategorySetRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    var a = service.assignVariantCategorySet(ctx.requireTenantId(), variantId, req);
    return created(Mappers.toVariantCategorySetAssignment(a));
  }

  /**
   * Lists a variant's category set assignments.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(summary = "List a variant's category set assignments")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Category Sets")
  @GET
  @Path("/products/variants/{variantId}/category-set-assignments")
  public ApiResponse<List<VariantCategorySetAssignmentResponse>> listVariantCategorySetAssignments(
      @PathParam("variantId") UUID variantId) {
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listVariantCategorySetAssignments(tenantId, variantId).stream()
            .map(Mappers::toVariantCategorySetAssignment)
            .toList());
  }

  /**
   * Removes a variant's category set assignment.
   *
   * @param variantId the variant id (path parameter)
   * @param setId the set id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} category set assignment not found
   */
  @Operation(summary = "Remove a variant's category set assignment")
  @APIResponse(responseCode = "404", description = "Category set assignment not found")
  @Tag(name = "Category Sets")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @DELETE
  @Path("/products/variants/{variantId}/category-set-assignments/{setId}")
  public Response deleteVariantCategorySetAssignment(
      @PathParam("variantId") UUID variantId, @PathParam("setId") UUID setId) {
    service.requireVariantLineHeld(ctx, variantId);
    service.deleteVariantCategorySetAssignment(ctx.requireTenantId(), variantId, setId);
    return Response.noContent().build();
  }

  // ─────────────────────────────────────────────────────────────────── utils

  private static Response created(Object body) {
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(body)).build();
  }

  private static UUID parseOptional(String s, String field) {
    return s == null || s.isBlank() ? null : com.storeql.web.Parsing.uuid(s, field);
  }

  // ── Food safety, origin, age restriction and selling by weight ─────────────
  //
  // Writes only. The reads live on /catalog because a shopper is entitled to the allergen
  // declaration and a till needs the age check, and neither runs as management.

  /**
   * Declares a variant's allergens.
   *
   * <p>Replaces the whole declaration. Sending an empty list is how a product is declared free from
   * all fourteen — a positive statement, not an omission — and it moves the variant from UNDECLARED
   * to DECLARED either way. The distinction the status column exists for: an empty list on an
   * UNDECLARED variant means nobody has checked, and must never be shown to a customer as 'free
   * from'.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return declared
   * @throws com.storeql.web.ApiException {@code 400} not one of the fourteen, presence not
   *     CONTAINS/MAY_CONTAIN, or declared twice; {@code 404} variant not found
   */
  @Operation(
      summary = "Declare a variant's allergens",
      description =
          "Replaces the whole declaration. Sending an empty list is how a product is declared free"
              + " from all fourteen — a positive statement, not an omission — and it moves the"
              + " variant from UNDECLARED to DECLARED either way.\n\n"
              + "The distinction the status column exists for: an empty list on an UNDECLARED"
              + " variant means nobody has checked, and must never be shown to a customer as"
              + " 'free from'.")
  @APIResponse(responseCode = "200", description = "Declared")
  @APIResponse(
      responseCode = "400",
      description = "Not one of the fourteen, presence not CONTAINS/MAY_CONTAIN, or declared twice")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Food safety")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/variants/{variantId}/allergens")
  public ApiResponse<VariantComplianceResponse> declareAllergens(
      @PathParam("variantId") UUID variantId, AllergenDeclarationRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toCompliance(
            service.declareAllergens(ctx.requireTenantId(), variantId, req, ctx.userId())));
  }

  /**
   * Sets origin, age restriction and how the item is sold.
   *
   * <p>Country of origin (ISO 3166-1 alpha-2), the age-restriction category if any, ingredients,
   * and the weighed-item fields: soldBy, net content and its unit, tare weight, and catchWeight for
   * items whose price is not knowable until they are on the scale.
   *
   * @param variantId the variant id (path parameter)
   * @param req the request body
   * @return updated
   * @throws com.storeql.web.ApiException {@code 400} bad country code, unknown UOM, negative tare,
   *     or sold by weight with no unit; {@code 404} variant not found
   */
  @Operation(
      summary = "Set origin, age restriction and how the item is sold",
      description =
          "Country of origin (ISO 3166-1 alpha-2), the age-restriction category if any,"
              + " ingredients, and the weighed-item fields: soldBy, net content and its unit, tare"
              + " weight, and catchWeight for items whose price is not knowable until they are on"
              + " the scale.")
  @APIResponse(responseCode = "200", description = "Updated")
  @APIResponse(
      responseCode = "400",
      description = "Bad country code, unknown UOM, negative tare, or sold by weight with no unit")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Food safety")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/variants/{variantId}/compliance")
  public ApiResponse<VariantComplianceResponse> setCompliance(
      @PathParam("variantId") UUID variantId, VariantComplianceRequest req) {
    service.requireVariantLineHeld(ctx, variantId);
    return ApiResponse.ok(
        Mappers.toCompliance(service.updateCompliance(ctx.requireTenantId(), variantId, req)));
  }

  /**
   * Products whose allergens have never been declared.
   *
   * <p>The list a food business is asked for when it is inspected, and the list that says which
   * shelves cannot lawfully be filled yet. Oldest first, because the ones that have been sitting
   * undeclared longest are the ones most likely to be on sale.
   *
   * @param limit the limit (query parameter)
   * @return variant ids, oldest first
   */
  @Operation(
      summary = "Products whose allergens have never been declared",
      description =
          "The list a food business is asked for when it is inspected, and the list that says"
              + " which shelves cannot lawfully be filled yet. Oldest first, because the ones that"
              + " have been sitting undeclared longest are the ones most likely to be on sale.")
  @APIResponse(responseCode = "200", description = "Variant ids, oldest first")
  @Tag(name = "Food safety")
  @GET
  @Path("/products/allergen-gaps")
  public ApiResponse<List<String>> allergenGaps(@QueryParam("limit") Integer limit) {
    return ApiResponse.ok(
        service.undeclaredVariants(ctx.requireTenantId(), limit == null ? 100 : limit).stream()
            .map(UUID::toString)
            .toList());
  }

  /**
   * Everies product carrying one allergen.
   *
   * <p>The query a recall runs. Optionally filtered to ?presence=CONTAINS or MAY_CONTAIN; both are
   * returned by default, because a withdrawal usually has to cover both.
   *
   * @param code the code (path parameter)
   * @param presence the presence (query parameter)
   * @return variant ids
   */
  @Operation(
      summary = "Every product carrying one allergen",
      description =
          "The query a recall runs. Optionally filtered to ?presence=CONTAINS or MAY_CONTAIN; both"
              + " are returned by default, because a withdrawal usually has to cover both.")
  @APIResponse(responseCode = "200", description = "Variant ids")
  @Tag(name = "Food safety")
  @GET
  @Path("/products/by-allergen/{code}")
  public ApiResponse<List<String>> byAllergen(
      @PathParam("code") String code, @QueryParam("presence") String presence) {
    return ApiResponse.ok(
        service.variantsWithAllergen(ctx.requireTenantId(), code, presence).stream()
            .map(UUID::toString)
            .toList());
  }

  /**
   * Ages rules in force in a country.
   *
   * <p>The statutory defaults, with this tenant's overrides shadowing them. tenantOverride says
   * which is which.
   *
   * @param country the country (query parameter)
   * @return rules by category
   */
  @Operation(
      summary = "Age rules in force in a country",
      description =
          "The statutory defaults, with this tenant's overrides shadowing them. tenantOverride"
              + " says which is which.")
  @APIResponse(responseCode = "200", description = "Rules by category")
  @Tag(name = "Age restriction")
  @GET
  @Path("/age-restriction-rules")
  public ApiResponse<List<AgeRestrictionRuleResponse>> ageRules(
      @QueryParam("country") String country) {
    return ApiResponse.ok(
        service.ageRules(ctx.requireTenantId(), country).stream().map(Mappers::toAgeRule).toList());
  }

  /**
   * Sets this tenant's own age rule.
   *
   * <p>May be stricter than the statute and never laxer — a chain adopting Challenge-25 is making a
   * policy decision, a chain setting alcohol to 16 in the UK is committing an offence, and a system
   * that lets them configure it has helped.
   *
   * @param req the request body
   * @return rule set
   * @throws com.storeql.web.ApiException {@code 400} below the statutory minimum for that country
   */
  @Operation(
      summary = "Set this tenant's own age rule",
      description =
          "May be stricter than the statute and never laxer — a chain adopting Challenge-25 is"
              + " making a policy decision, a chain setting alcohol to 16 in the UK is committing"
              + " an offence, and a system that lets them configure it has helped.")
  @APIResponse(responseCode = "200", description = "Rule set")
  @APIResponse(responseCode = "400", description = "Below the statutory minimum for that country")
  @Tag(name = "Age restriction")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " Setting age-restriction rules is the whole business's, as it changes the catalogue at every store. Nothing"
              + " changes.")
  @PUT
  @Path("/age-restriction-rules")
  public ApiResponse<AgeRestrictionRuleResponse> setAgeRule(SetAgeRestrictionRuleRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    service.requireBusinessWideCatalogue(ctx, "Setting age-restriction rules");
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toAgeRule(service.setAgeRule(ctx.requireTenantId(), req, ctx.userId())));
  }

  // ── product safety information (01.12, GPSR art.19) ────────────────────────

  @Operation(
      summary = "A product's safety information",
      description =
          "The manufacturer, the EU responsible person and the warnings an online offer shows, with"
              + " whether the business's market requires them today and what is still missing.")
  @APIResponse(responseCode = "404", description = "No such product")
  @Tag(name = "Products")
  @GET
  @Path("/products/{id}/safety-information")
  public ApiResponse<com.storeql.product.dto.Dtos.SafetyInformationResponse> safetyInformation(
      @PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toSafetyInformation(service.safetyInformation(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "State a product's safety information",
      description =
          "Replaces the statement. Contacts are an e-mail address or an https:// URL, the country"
              + " ISO 3166-1 alpha-2; warnings, or noWarnings to state that none apply. Refused with"
              + " PRODUCT_SAFETY_INFORMATION_REQUIRED when the product is offered online where GPSR"
              + " binds the business and the statement would not do.")
  @APIResponse(responseCode = "400", description = "Malformed, or not enough for an online offer")
  @APIResponse(responseCode = "404", description = "No such product")
  @Tag(name = "Products")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the line is sold at every store or at a store beyond"
              + " theirs (BUSINESS_WIDE_ONLY): item master data is kept centrally, a branch edits"
              + " only a line local to its own stores. Nothing changes.")
  @PUT
  @Path("/products/{id}/safety-information")
  public ApiResponse<com.storeql.product.dto.Dtos.SafetyInformationResponse> setSafetyInformation(
      @PathParam("id") UUID id, com.storeql.product.dto.Dtos.SafetyInformationRequest req) {
    service.requireLineHeld(ctx, id);
    if (req == null) {
      throw com.storeql.web.ApiException.badRequest(
          "SAFETY_INFORMATION_REQUIRED", "a body is required");
    }
    return ApiResponse.ok(
        Mappers.toSafetyInformation(
            service.setSafetyInformation(ctx.requireTenantId(), id, req, ctx.userId())));
  }

  @Operation(
      summary = "Online products missing safety information",
      description =
          "Active products offered online that GPSR requires safety information for and that lack"
              + " some, with what each lacks. Empty where the regulation does not bind the business.")
  @Tag(name = "Products")
  @GET
  @Path("/products/safety-information/missing")
  public ApiResponse<List<com.storeql.product.dto.Dtos.MissingSafetyInformationResponse>>
      missingSafetyInformation() {
    return ApiResponse.ok(
        service.missingSafetyInformation(ctx.requireTenantId()).stream()
            .map(Mappers::toMissingSafety)
            .toList());
  }
}
