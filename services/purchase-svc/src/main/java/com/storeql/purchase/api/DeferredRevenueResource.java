package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.DeferredRevenueSettingsRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.DeferredRevenueService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Deferred revenue for loyalty points and gift card breakage (17.11). Management held to no store:
 * the points and the cards are the business's, spent at any of its stores.
 */
@RequestScoped
@Path("/nominal-ledger/deferred-revenue")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Nominal Ledger")
public class DeferredRevenueResource {

  @Inject DeferredRevenueService deferred;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Deferred revenue for loyalty points and gift cards",
      description =
          "The estimates in force (a point's value, the share of points and of gift card value"
              + " expected never to be used) with their history; the points outstanding, the"
              + " income deferred against them (2330) and points spent before their earning"
              + " reached the ledger; loyalty events waiting for estimates; and gift cards loaded,"
              + " spent, breakage recognised (4031) and the liability left since the ledger began"
              + " to follow them. Management only.")
  @APIResponse(responseCode = "200", description = "Where deferred revenue stands")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, or BUSINESS_WIDE_ONLY: a caller held to stores; points and gift"
              + " cards are the whole business's")
  @GET
  public Response view() {
    return Response.ok(ApiResponse.ok(Mappers.toDto(deferred.view(ctx)))).build();
  }

  @Operation(
      summary = "Set the estimates",
      description =
          "Management only. A point's value in the tenant's currency (above 0, at most 1000, four"
              + " decimal places) and the two breakage estimates as percentages (0 to 95, two"
              + " decimal places), with the reason for them. Earlier estimates are kept and a"
              + " change applies from now on. Loyalty events that arrived before any estimates"
              + " were set are posted in the same transaction, oldest first.")
  @APIResponse(responseCode = "200", description = "The estimates saved, and where things stand")
  @APIResponse(
      responseCode = "400",
      description =
          "A missing field or reason, PURCHASE_POINT_VALUE_INVALID or"
              + " PURCHASE_BREAKAGE_OUT_OF_RANGE")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, or BUSINESS_WIDE_ONLY: a caller held to stores; points and gift"
              + " cards are the whole business's")
  @PUT
  @Path("/settings")
  public Response setEstimates(DeferredRevenueSettingsRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(deferred.setEstimates(ctx, req)))).build();
  }
}
