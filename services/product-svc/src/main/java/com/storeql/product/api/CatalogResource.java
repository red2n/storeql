package com.storeql.product.api;

import com.storeql.ids.Ids;
import com.storeql.product.dto.Dtos.AgeCheckResponse;
import com.storeql.product.dto.Dtos.AllergenDeclarationResponse;
import com.storeql.product.dto.Dtos.AllergenResponse;
import com.storeql.product.dto.Dtos.CategoryResponse;
import com.storeql.product.dto.Dtos.ProductResponse;
import com.storeql.product.dto.Dtos.ScanResponse;
import com.storeql.product.dto.Dtos.VariantComplianceResponse;
import com.storeql.product.dto.Dtos.VariantResponse;
import com.storeql.product.dto.Dtos.VariantScanResponse;
import com.storeql.product.mapper.Mappers;
import com.storeql.product.service.ProductService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public storefront catalog. The tenant (which business's storefront) comes from {@code
 * X-Tenant-Id}, which the gateway resolves from the storefront's domain/subdomain (multi-tenant
 * SaaS standard). Only ACTIVE products show; the online list returns only {@code sellable_online}
 * products.
 *
 * <p>Search params on {@code GET /catalog/products}: {@code ?q=} (name contains, case-insensitive),
 * {@code ?sku=} (exact SKU), {@code ?barcode=} (exact barcode). At least one of q/sku/barcode
 * routes to the search path; without them, the standard filtered list is returned.
 *
 * <p>POS barcode scan: {@code GET /catalog/variants/by-barcode/{code}} returns a {@link
 * VariantScanResponse} embedding product context so the terminal needs only one round-trip.
 */
@Path("/catalog")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Catalog")
public class CatalogResource {

  @Inject ProductService service;
  @Inject TenantContext ctx;

  /**
   * Browses or search the public catalog.
   *
   * <p>Lists ACTIVE products; the online channel further filters to sellable_online, POS channel to
   * sellable_pos. At least one of q/sku/barcode routes to the search path; otherwise the standard
   * filtered list is returned. Tenant comes from X-Tenant-Id.
   *
   * @param category the category (query parameter)
   * @param q the q (query parameter)
   * @param sku the sku (query parameter)
   * @param barcode the barcode (query parameter)
   * @param store the store (query parameter)
   * @param channel the channel (query parameter)
   * @param limit the limit (query parameter)
   * @throws com.storeql.web.ApiException {@code 400} storefront tenant not resolved, or store is
   *     not a UUID
   */
  @Operation(
      summary = "Browse or search the public catalog",
      description =
          "Lists ACTIVE products; the online channel further filters to sellable_online, POS"
              + " channel to sellable_pos. At least one of q/sku/barcode routes to the search"
              + " path; otherwise the standard filtered list is returned. Tenant comes from"
              + " X-Tenant-Id.")
  @APIResponse(
      responseCode = "400",
      description = "storefront tenant not resolved, or store is not a UUID")
  @GET
  @Path("/products")
  public ApiResponse<List<ProductResponse>> list(
      @QueryParam("category") String category,
      @QueryParam("q") String q,
      @QueryParam("sku") String sku,
      @QueryParam("barcode") String barcode,
      @QueryParam("store") String store,
      @QueryParam("channel") String channel,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = requireTenant();
    int clamped = Cursor.clampLimit(limit);
    // When a store is given, only products assorted for that store (or sold everywhere) show.
    UUID storeId = parseOptionalUuid(store, "INVALID_STORE", "store must be a UUID");
    // channel=POS serves the in-store till (sellable_pos); default/ONLINE serves the storefront
    // (sellable_online). Catalog is cashier-reachable, so POS staff use this instead of /admin.
    boolean pos = "POS".equalsIgnoreCase(channel == null ? null : channel.trim());
    boolean onlineOnly = !pos;

    List<ProductResponse> items;
    if (q != null || sku != null || barcode != null) {
      String trimQ = blank(q) ? null : q.trim();
      String trimSku = blank(sku) ? null : sku.trim();
      String trimBarcode = blank(barcode) ? null : barcode.trim();
      // Search already returns all channels; narrow to POS only when the till asks.
      items =
          service
              .searchProducts(tenantId, trimQ, trimSku, trimBarcode, false, pos, storeId, clamped)
              .stream()
              .map(Mappers::toProduct)
              .toList();
    } else {
      UUID categoryId = parseOptionalUuid(category, "INVALID_CATEGORY", "category must be a UUID");
      items =
          service.listProducts(tenantId, categoryId, onlineOnly, pos, storeId, clamped).stream()
              .map(Mappers::toProduct)
              .toList();
    }
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /** Public category list for storefront browse-by-category. Tenant from {@code X-Tenant-Id}. */
  @Operation(
      summary = "List storefront categories",
      description = "Public category list for browse-by-category. Tenant from X-Tenant-Id.")
  @APIResponse(responseCode = "200", description = "List storefront categories")
  @GET
  @Path("/categories")
  public ApiResponse<List<CategoryResponse>> categories() {
    var items = service.listCategories(requireTenant()).stream().map(Mappers::toCategory).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Gets a storefront product by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such product for this tenant
   */
  @Operation(summary = "Get a storefront product by id")
  @APIResponse(responseCode = "404", description = "No such product for this tenant")
  @GET
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> get(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toProduct(service.getProduct(requireTenant(), id)));
  }

  /**
   * Lists a product's variants.
   *
   * @param id the id (path parameter)
   */
  @Operation(summary = "List a product's variants")
  @APIResponse(responseCode = "200", description = "List a product's variants")
  @GET
  @Path("/products/{id}/variants")
  public ApiResponse<List<VariantResponse>> variants(@PathParam("id") UUID id) {
    var items = service.listVariants(requireTenant(), id).stream().map(Mappers::toVariant).toList();
    return ApiResponse.ok(items);
  }

  /**
   * The product's primary image bytes (owner-uploaded). 404 when the product has no image — the
   * storefront/admin renders its colour-tile placeholder instead. Cached briefly so catalog pages
   * don't re-download on every visit but a replaced image shows up within a minute.
   */
  @Operation(
      summary = "Fetch a product's primary image",
      description =
          "Returns the owner-uploaded image bytes. 404 when the product has no image — the"
              + " caller should render a placeholder instead. Cached for 60 seconds.")
  @APIResponse(responseCode = "200", description = "Image bytes with their original Content-Type")
  @APIResponse(responseCode = "404", description = "Product has no image, or does not exist")
  @GET
  @Path("/products/{id}/image")
  public jakarta.ws.rs.core.Response image(@PathParam("id") UUID id) {
    var img = service.getProductImage(requireTenant(), id);
    return jakarta.ws.rs.core.Response.ok(img.bytes(), img.contentType())
        .header("Cache-Control", "public, max-age=60")
        .build();
  }

  /**
   * POS barcode-scan lookup. Returns the variant and its parent product in a single response so the
   * terminal does not need a second round-trip. Returns 404 when no active variant matches.
   */
  @Operation(
      summary = "Look up a variant by barcode",
      description =
          "POS barcode-scan lookup. Returns the variant and its parent product in a single"
              + " response so the terminal needs only one round-trip.")
  @APIResponse(responseCode = "400", description = "barcode is blank")
  @APIResponse(responseCode = "404", description = "No active variant found for this barcode")
  @GET
  @Path("/variants/by-barcode/{code}")
  public ApiResponse<VariantScanResponse> scanByBarcode(@PathParam("code") String code) {
    UUID tenantId = requireTenant();
    if (code == null || code.isBlank()) {
      throw ApiException.badRequest("INVALID_BARCODE", "barcode must not be blank");
    }
    var vp = service.findVariantByBarcode(tenantId, code.trim());
    var container =
        service
            .depositContainers(tenantId, java.util.List.of(vp.variant().id()))
            .get(vp.variant().id());
    return ApiResponse.ok(Mappers.toVariantScan(vp.variant(), vp.product(), null, container));
  }

  /**
   * Scans a code of any kind (07.15).
   *
   * <p>A query parameter and not a path segment, because a GS1 Digital Link is a URI: it carries
   * slashes and a query string of its own, and neither survives a path segment — a path-encoded one
   * arrives as a 404 with the code silently cut at its first slash.
   */
  @Operation(
      summary = "Look up a scanned code — plain barcode, GS1 DataMatrix or Digital Link QR",
      description =
          "One route for every kind of code a till meets. A plain EAN or UPC is matched as it always"
              + " was; a GS1 element string (DataMatrix, GS1-128, GS1 QR) or a GS1 Digital Link URI is"
              + " read first, and the item is then found by the GTIN it carried — which is what lets a"
              + " packet whose 2D code says 05012345678900 find the variant a shop entered as"
              + " 5012345678900. What the code carried besides the item — batch, expiry, weight, price"
              + " — comes back in `code`, unconverted and uninterpreted. A code that is not GS1 at all"
              + " (an internal code, a PLU, a shelf label) is matched exactly and `code` is null."
              + " Last, a SKU typed from under a label that will not scan: as written, else in any"
              + " case when only one variant answers to it.")
  @APIResponse(responseCode = "400", description = "code is blank")
  @APIResponse(responseCode = "404", description = "No active variant matches the code")
  @APIResponse(responseCode = "409", description = "The line is listed but not yet on sale")
  @GET
  @Path("/scan")
  public ApiResponse<ScanResponse> scan(@QueryParam("code") String code) {
    UUID tenantId = requireTenant();
    if (code == null || code.isBlank()) {
      throw ApiException.badRequest("INVALID_BARCODE", "code must not be blank");
    }
    var result = service.scan(tenantId, code);
    var variant = result.found().variant();
    var container =
        service.depositContainers(tenantId, java.util.List.of(variant.id())).get(variant.id());
    return ApiResponse.ok(
        new ScanResponse(
            Mappers.toVariantScan(variant, result.found().product(), null, container),
            Mappers.toScannedCode(result.scan())));
  }

  private UUID requireTenant() {
    if (ctx.tenantId() == null) {
      throw ApiException.badRequest(
          "NO_STOREFRONT", "Storefront tenant not resolved (X-Tenant-Id)");
    }
    return ctx.tenantId();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static UUID parseOptionalUuid(String s, String code, String message) {
    if (blank(s)) {
      return null;
    }
    try {
      return Ids.parse(s);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, code, message, List.of(), e);
    }
  }

  // ── Food safety and age restriction: reads a shopper and a till both need ──

  /**
   * Thes fourteen regulated allergens.
   *
   * <p>Reference data from Regulation (EU) 1169/2011 Annex II. Set by regulation, not by the
   * business, so it is read-only.
   *
   * @return the fourteen, by code
   */
  @Operation(
      summary = "The fourteen regulated allergens",
      description =
          "Reference data from Regulation (EU) 1169/2011 Annex II. Set by regulation, not by the"
              + " business, so it is read-only.")
  @APIResponse(responseCode = "200", description = "The fourteen, by code")
  @Tag(name = "Food safety")
  @GET
  @Path("/allergens")
  public ApiResponse<List<AllergenResponse>> allergens() {
    return ApiResponse.ok(service.listAllergens().stream().map(Mappers::toAllergen).toList());
  }

  /**
   * As product's allergen declaration.
   *
   * <p>Open to shoppers deliberately: since Natasha's Law a customer is entitled to this
   * information before buying, so putting it behind a login would defeat it. **Read the status, not
   * the list length.** UNDECLARED with an empty list means nobody has checked yet; DECLARED with an
   * empty list means the product has been checked and contains none of the fourteen. Treating the
   * first as the second is how an allergic customer is told a product is safe when nobody knows.
   *
   * @param variantId the variant id (path parameter)
   * @return the declaration and its status
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(
      summary = "A product's allergen declaration",
      description =
          "Open to shoppers deliberately: since Natasha's Law a customer is entitled to this"
              + " information before buying, so putting it behind a login would defeat it.\n\n"
              + "**Read the status, not the list length.** UNDECLARED with an empty list means"
              + " nobody has checked yet; DECLARED with an empty list means the product has been"
              + " checked and contains none of the fourteen. Treating the first as the second is"
              + " how an allergic customer is told a product is safe when nobody knows.")
  @APIResponse(responseCode = "200", description = "The declaration and its status")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Food safety")
  @GET
  @Path("/variants/{variantId}/allergens")
  public ApiResponse<AllergenDeclarationResponse> variantAllergens(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = requireTenant();
    var compliance = service.complianceOf(tenantId, variantId);
    var rows = service.allergensOf(tenantId, variantId);
    return ApiResponse.ok(
        new AllergenDeclarationResponse(
            variantId.toString(),
            compliance.allergenStatus(),
            rows.stream().map(Mappers::toAllergenEntry).toList(),
            rows.isEmpty() ? null : rows.get(0).declaredAt().toString()));
  }

  /**
   * Is this item age-restricted here, and from what age.
   *
   * <p>The question a till asks before it will take payment for a scanned line. Takes the country
   * the store is in, because the same bottle of wine is 18 in the UK, 20 in Japan and 21 in the US
   * — the restriction belongs to the product, the age belongs to the jurisdiction. A tenant's own
   * rule wins over the statutory default, and may only ever be stricter. `minimumAge` null means
   * the item is not restricted at all.
   *
   * @param variantId the variant id (path parameter)
   * @param country the country (query parameter)
   * @return the check to perform, or no restriction
   * @throws com.storeql.web.ApiException {@code 400} the item is restricted but this country has no
   *     rule for it — set one first; {@code 404} variant not found
   */
  @Operation(
      summary = "Is this item age-restricted here, and from what age",
      description =
          "The question a till asks before it will take payment for a scanned line. Takes the"
              + " country the store is in, because the same bottle of wine is 18 in the UK, 20 in"
              + " Japan and 21 in the US — the restriction belongs to the product, the age belongs"
              + " to the jurisdiction.\n\n"
              + "A tenant's own rule wins over the statutory default, and may only ever be"
              + " stricter. `minimumAge` null means the item is not restricted at all.\n\n"
              + "`bornBefore` is a date of birth rather than an age: when present, refuse anyone"
              + " born on or after it however old they are. The UK's generational tobacco ban"
              + " takes effect on 1 Jan 2027 and appears here from that day; a business that"
              + " adopts it, or an earlier date, sees its own at once.")
  @APIResponse(responseCode = "200", description = "The check to perform, or no restriction")
  @APIResponse(
      responseCode = "400",
      description = "The item is restricted but this country has no rule for it — set one first")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Age restriction")
  @GET
  @Path("/variants/{variantId}/age-check")
  public ApiResponse<AgeCheckResponse> ageCheck(
      @PathParam("variantId") UUID variantId, @QueryParam("country") String country) {
    UUID tenantId = requireTenant();
    var rule = service.ageCheck(tenantId, variantId, country);
    return ApiResponse.ok(
        rule == null
            ? new AgeCheckResponse(
                variantId.toString(),
                country == null ? null : country.toUpperCase(java.util.Locale.ROOT),
                false,
                null,
                null,
                false,
                null,
                false)
            : new AgeCheckResponse(
                variantId.toString(),
                rule.country(),
                true,
                rule.category(),
                rule.minimumAge(),
                rule.ageFromTenant(),
                rule.bornBefore() == null ? null : rule.bornBefore().toString(),
                rule.bornBeforeFromTenant()));
  }

  /**
   * Hows an item is sold, and where it is from.
   *
   * <p>Country of origin, ingredients, and whether the item is sold by the each or by weight — what
   * a shelf edge and a scale both need to price it.
   *
   * @param variantId the variant id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} variant not found
   */
  @Operation(
      summary = "How an item is sold, and where it is from",
      description =
          "Country of origin, ingredients, and whether the item is sold by the each or by weight —"
              + " what a shelf edge and a scale both need to price it.")
  @APIResponse(responseCode = "404", description = "Variant not found")
  @Tag(name = "Food safety")
  @GET
  @Path("/variants/{variantId}/compliance")
  public ApiResponse<VariantComplianceResponse> compliance(@PathParam("variantId") UUID variantId) {
    return ApiResponse.ok(Mappers.toCompliance(service.complianceOf(requireTenant(), variantId)));
  }

  /**
   * What the online offer shows about a product's safety (01.12).
   *
   * <p>Open to shoppers deliberately, like the allergen declaration: GPSR art.19 requires it to be
   * shown with the offer, before anyone signs in.
   *
   * @param id the product id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such product for this tenant
   */
  @Operation(
      summary = "A product's safety information, as the online offer shows it",
      description =
          "The manufacturer, the EU responsible person where the manufacturer is outside the EU, and"
              + " the warnings or the statement that none apply (Regulation (EU) 2023/988 art.19).")
  @APIResponse(responseCode = "404", description = "No such product for this tenant")
  @Tag(name = "Catalog")
  @GET
  @Path("/products/{id}/safety-information")
  public ApiResponse<com.storeql.product.dto.Dtos.SafetyInformationResponse> safetyInformation(
      @PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toSafetyInformation(service.safetyInformation(requireTenant(), id)));
  }
}
