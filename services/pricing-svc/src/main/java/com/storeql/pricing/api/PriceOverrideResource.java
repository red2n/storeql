package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos.CreatePriceOverrideRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #41 — POS price overrides. Append-only audit log of staff-approved ad-hoc price changes at
 * the point of sale. Requires STAFF or ADMIN role (enforced by AdminAuthorizationFilter).
 */
@RequestScoped
@Path("/admin/price-overrides")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Overrides")
public class PriceOverrideResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Records that a cashier sold at a different price, and why.
   *
   * <p>An audit row, not a price change: nothing here alters the price list the override departed
   * from.
   *
   * @param req the variant, store, original and override prices, reason and who authorised it
   * @return {@code 201} with the recorded override
   */
  @Operation(
      summary = "Record a POS price override",
      description =
          "Appends a staff-approved ad-hoc price change made at the point of sale. Requires an"
              + " admin/staff role (enforced by AdminAuthorizationFilter).")
  @APIResponse(responseCode = "201", description = "Price override recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED: a missing or negative price, or one with more decimals than the"
              + " business's currency has")
  @APIResponse(responseCode = "403", description = "Caller lacks a staff/admin role")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the prices are kept to the business currency's minor"
              + " units, and the currency could not be read")
  @POST
  public Response create(CreatePriceOverrideRequest req) {
    Validations.validate(req);
    var override = svc.createPriceOverride(ctx, req);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(override))).build();
  }

  /** Cursor-paginated: {@code ?after=<meta.nextCursor>&limit=1-100}. */
  /**
   * Cursor-paginated audit log of manual price overrides.
   *
   * @param storeId restrict to one store, or {@code null} for all
   * @param variantId restrict to one variant, or {@code null} for all
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of overrides, with the next cursor in {@code meta}
   */
  @Operation(
      summary = "List price overrides",
      description =
          "Append-only audit log of POS price overrides, optionally filtered by store or variant."
              + " Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of price overrides")
  @APIResponse(responseCode = "403", description = "Caller lacks a staff/admin role")
  @GET
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("variantId") String variantId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = svc.listPriceOverrides(ctx, storeId, variantId, after, Cursor.clampLimit(limit));
    var overrides = page.items().stream().map(Mappers::toDto).toList();
    return Response.ok(
            ApiResponse.ok(overrides, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }
}
