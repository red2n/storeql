package com.storeql.pricing.api;

import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
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

/** HMRC MTD VAT return endpoint: computes boxes 1-9 per VAT Notice 700 s.17. */
@RequestScoped
@Path("/vat-return")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "VAT Return")
public class VatReturnResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Computes HMRC MTD VAT return boxes 1-9 for a period.
   *
   * <p>Box 4 (input VAT on purchases) and box 7 (net purchases) come from the supplier invoices
   * purchase-svc captured, by invoice date (SJ-D39). Boxes 8 and 9, goods traded with the EU, are
   * zero: nothing records that trade.
   *
   * @param from inclusive ISO-8601 lower bound on the tax point
   * @param to exclusive ISO-8601 upper bound
   * @return the nine box figures with the period they cover
   * @throws com.storeql.web.ApiException {@code 400} when the period is malformed or not strictly
   *     increasing
   */
  @Operation(
      summary = "Compute the MTD VAT return for a period",
      description =
          "Computes HMRC Making Tax Digital VAT return boxes 1-9 for the given date range. Boxes 4"
              + " (input VAT) and 7 (net purchases) come from the supplier invoices captured in"
              + " purchasing, by invoice date; boxes 8 and 9 (goods traded with the EU) are zero.")
  @APIResponse(responseCode = "200", description = "Computed VAT return boxes 1-9")
  @APIResponse(
      responseCode = "400",
      description = "from/to query params missing, or from is not before to")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER, MANAGER or PLATFORM_ADMIN")
  @GET
  public Response compute(@QueryParam("from") String from, @QueryParam("to") String to) {
    // A tenant's statutory tax position, and this path is not under /admin/, so
    // AdminAuthorizationFilter never gated it: any authenticated caller in the tenant could read
    // it, a CASHIER or a signed-in storefront customer included. Management roles only.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    if (from == null || from.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_FROM", "from query param required (ISO-8601)");
    if (to == null || to.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_TO", "to query param required (ISO-8601)");
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.computeVatReturn(ctx, from, to)))).build();
  }
}
