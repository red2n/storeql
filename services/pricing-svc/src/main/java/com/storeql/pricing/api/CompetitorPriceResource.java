package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos.BatchCompetitorPricesRequest;
import com.storeql.pricing.dto.Dtos.BatchCompetitorPricesResult;
import com.storeql.pricing.dto.Dtos.RecordCompetitorPriceRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.RepricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Permissions;
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

/** What rivals charge, as seen (03.x): append-only observations per variant. Management only. */
@RequestScoped
@Path("/admin/competitor-prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Competitor Prices")
public class CompetitorPriceResource {

  @Inject RepricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Record a competitor's price",
      description =
          "One rival's price for one variant on one day, in the business's own currency, optionally"
              + " in one price zone. Append-only: a later sighting is a new row, and the freshest"
              + " per rival is what a repricing rule answers.")
  @APIResponse(responseCode = "201", description = "Observation recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "PRICING_COMPETITOR_CURRENCY_MISMATCH, PRICING_COMPETITOR_DATE_INVALID,"
              + " PRICING_ZONE_UNKNOWN")
  @POST
  public Response record(RecordCompetitorPriceRequest req) {
    Validations.validate(req);
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(svc.record(ctx, req)))).build();
  }

  @Operation(
      summary = "Record competitor prices in bulk",
      description =
          "Up to 500 observations in one call, as an import would send them; all or none are kept,"
              + " and the first refusal names the row's problem.")
  @APIResponse(responseCode = "200", description = "How many were recorded")
  @POST
  @Path("/batch")
  public Response batch(BatchCompetitorPricesRequest req) {
    Validations.validate(req);
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.ok(
            ApiResponse.ok(
                new BatchCompetitorPricesResult(svc.recordBatch(ctx, req.observations()))))
        .build();
  }

  @Operation(
      summary = "List competitor prices",
      description = "Newest first, optionally for one variant or one zone; at most 100.")
  @APIResponse(responseCode = "200", description = "The observations")
  @GET
  public Response list(
      @QueryParam("variantId") String variantId,
      @QueryParam("zoneId") String zoneId,
      @QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.listObservations(ctx, variantId, zoneId, Cursor.clampLimit(limit)).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }
}
