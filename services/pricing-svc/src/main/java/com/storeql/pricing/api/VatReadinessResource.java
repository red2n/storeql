package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos.VatReadinessResponse;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** What stands between a business and shelf-price (tax-inclusive) selling. */
@RequestScoped
@Path("/admin/pricing/vat-readiness")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Price Lists")
public class VatReadinessResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * The priced variants with no VAT category, and the VAT codes with no rate.
   *
   * <p>A tax-inclusive list refuses to price a variant it cannot work the VAT of, and a quote of
   * one is refused; this is the list of what to fix first, by name once the screen has them.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null}
   * @param limit page size, 1..100
   * @return the report with one page of gaps
   */
  @Operation(
      summary = "VAT readiness",
      description =
          "Priced variants with no VAT category (cursor-paginated), how many in all, and VAT codes"
              + " variants are assigned to for which no rate is set. Management only.")
  @APIResponse(responseCode = "200", description = "The report")
  @APIResponse(responseCode = "403", description = "Caller cannot manage prices")
  @GET
  public Response get(@QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    ctx.requirePermission(Permissions.PRICING_WRITE);
    var page = svc.vatReadiness(ctx, after, Cursor.clampLimit(limit));
    return Response.ok(
            ApiResponse.ok(
                new VatReadinessResponse(
                    page.taxMode(),
                    page.variantsWithoutCategory(),
                    page.codesWithoutRate(),
                    page.gaps().stream().map(Mappers::toDto).toList(),
                    page.ready()),
                new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }
}
