package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos.BatchProductVatCategoriesResult;
import com.storeql.pricing.dto.Dtos.BatchUpsertProductVatCategoriesRequest;
import com.storeql.pricing.dto.Dtos.UpsertProductVatCategoryRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Assign HMRC VAT codes to product variants. */
@RequestScoped
@Path("/product-vat-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Product VAT Categories")
public class ProductVatCategoryResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Assigns a variant to a VAT code.
   *
   * <p>The code is checked against the tenant's own rates, so a typo cannot leave a product
   * pointing at a band that does not exist and silently falling back to standard rate at checkout.
   *
   * @param req the variant and the VAT code to assign it
   * @return the stored assignment
   * @throws com.storeql.web.ApiException {@code 404} when the VAT code is not configured
   */
  @Operation(
      summary = "Assign a VAT category to a variant",
      description = "Sets the HMRC VAT code applied to a product variant's price resolution.")
  @APIResponse(responseCode = "200", description = "VAT category assigned")
  @APIResponse(responseCode = "403", description = "Not a manager, owner or platform admin")
  @APIResponse(responseCode = "404", description = "VAT code not found")
  @POST
  public Response upsert(UpsertProductVatCategoryRequest req) {
    // What a product is taxed at is a management decision, not a till's (SJ-D56).
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return Response.status(200)
        .entity(ApiResponse.ok(Mappers.toDto(svc.upsertProductVatCategory(req, ctx))))
        .build();
  }

  /**
   * Assigns many variants to VAT codes in one call, all or none.
   *
   * <p>How a catalogue import and a go-live fix-up give a priced shop its categories: a business
   * that sells at shelf prices cannot price an item that has none. Every row is checked first; one
   * wrong row refuses the call and is named, so a half-categorised catalogue is never left behind.
   *
   * @param req up to 500 assignments, one per variant
   * @return how many variants were assigned
   * @throws com.storeql.web.ApiException {@code 400 PRICING_VAT_BATCH_INVALID} naming the rows
   */
  @Operation(
      summary = "Assign VAT categories to many variants",
      description =
          "All or none: every row is checked against this business's VAT rates before any is"
              + " written. At most 500 rows, one per variant.")
  @APIResponse(responseCode = "200", description = "Every variant assigned")
  @APIResponse(responseCode = "400", description = "PRICING_VAT_BATCH_INVALID: nothing assigned")
  @POST
  @Path("/batch")
  public Response batch(BatchUpsertProductVatCategoriesRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                new BatchProductVatCategoriesResult(svc.upsertProductVatCategories(req, ctx))))
        .build();
  }

  /**
   * Reads a variant's VAT assignment.
   *
   * <p>Reports absence as a 404, unlike price resolution, which charges an uncategorised variant
   * the business's standard rate (T1): the admin screen needs to know a product was never
   * categorised.
   *
   * @param variantId the variant to look up
   * @return the assignment
   * @throws com.storeql.web.ApiException {@code 404} when none is assigned
   */
  @Operation(
      summary = "Get a variant's VAT category",
      description = "Looks up the VAT code assigned to a product variant.")
  @APIResponse(responseCode = "200", description = "VAT category found")
  @APIResponse(responseCode = "404", description = "No VAT category assigned for this variant")
  @GET
  @Path("/{variantId}")
  public Response get(@PathParam("variantId") UUID variantId) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getProductVatCategory(ctx, variantId))))
        .build();
  }
}
