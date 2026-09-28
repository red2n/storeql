package com.storeql.purchase.api;

import com.storeql.ids.Ids;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.SupplierPerformanceService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Supplier lead-time tracking and scorecards: management's reading of how each supplier delivers.
 * The period is {@code from} to {@code to} inclusive, the last ninety days when unsaid.
 */
@RequestScoped
@Path("/suppliers")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Supplier Scorecards")
public class SupplierPerformanceResource {

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};

  @Inject SupplierPerformanceService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Every supplier's scorecard, ranked",
      description =
          "One card per supplier over the period: deliveries (lead days, on time against the"
              + " promise), fill (received against ordered over finished orders), quality (what"
              + " went back) and invoice accuracy, weighed into a score and a grade. The best"
              + " first; suppliers with nothing to judge last, unscored.")
  @APIResponse(responseCode = "200", description = "The scorecards")
  @APIResponse(responseCode = "400", description = "PURCHASE_PERIOD_INVALID")
  @GET
  @Path("/scorecards")
  public Response scorecards(@QueryParam("from") String from, @QueryParam("to") String to) {
    ctx.requireAnyRole(MANAGEMENT);
    return Response.ok(
            ApiResponse.ok(svc.scorecards(ctx, from, to).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "One supplier's scorecard")
  @APIResponse(responseCode = "200", description = "The scorecard")
  @APIResponse(responseCode = "404", description = "PURCHASE_SUPPLIER_NOT_FOUND")
  @GET
  @Path("/{id}/scorecard")
  public Response scorecard(
      @PathParam("id") String id, @QueryParam("from") String from, @QueryParam("to") String to) {
    ctx.requireAnyRole(MANAGEMENT);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.scorecard(ctx, Ids.parse(id), from, to))))
        .build();
  }

  @Operation(
      summary = "A supplier's deliveries as measured",
      description =
          "Each goods receipt against the supplier's orders in the period, newest first: when the"
              + " order went out, what was promised, when it arrived, the lead days and the days"
              + " late.")
  @APIResponse(responseCode = "200", description = "The deliveries")
  @APIResponse(responseCode = "404", description = "PURCHASE_SUPPLIER_NOT_FOUND")
  @GET
  @Path("/{id}/deliveries")
  public Response deliveries(
      @PathParam("id") String id, @QueryParam("from") String from, @QueryParam("to") String to) {
    ctx.requireAnyRole(MANAGEMENT);
    return Response.ok(
            ApiResponse.ok(
                svc.deliveries(ctx, Ids.parse(id), from, to).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
