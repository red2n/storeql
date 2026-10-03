package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.ApplyLandedCostRequest;
import com.storeql.purchase.dto.Dtos.LandedCostResponse;
import com.storeql.purchase.dto.Dtos.ReverseLandedCostRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.LandedCostService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Landed costs (07.x): freight, duty, insurance and the like charged against a goods receipt,
 * spread over its lines, posted to the stock and announced to inventory. Thin: every decision is
 * the service's.
 */
@Path("/landed-costs")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Landed Costs")
public class LandedCostResource {

  @Inject LandedCostService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Apply a landed charge to a goods receipt",
      description =
          "Spreads the charge over the receipt's lines BY_VALUE (at the order's prices) or"
              + " BY_QUANTITY, exactly; posts Dr Stock / Cr 2110 Landed Costs Accrued; publishes"
              + " LandedCostApplied so inventory-svc lifts the cost of the receipt's batches."
              + " Supports Idempotency-Key.")
  @APIResponse(responseCode = "201", description = "Applied, with its lines")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_LANDED_INVALID (charge type, basis or amount), PURCHASE_LANDED_CURRENCY_MISMATCH,"
              + " PURCHASE_AMOUNT_TOO_PRECISE (an amount finer than the order's currency's minor"
              + " units)")
  @APIResponse(
      responseCode = "404",
      description = "PURCHASE_GRN_NOT_FOUND, PURCHASE_SUPPLIER_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "PURCHASE_PERIOD_CLOSED")
  @APIResponse(
      responseCode = "422",
      description = "PURCHASE_LANDED_NOTHING_RECEIVED, PURCHASE_LANDED_NO_BASIS")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a buying role, or STORE_ACCESS_DENIED: the receipt is another store's than the"
              + " caller's")
  @POST
  public Response apply(
      @HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      ApplyLandedCostRequest req) {
    Validations.validate(req);
    var c = svc.apply(req, ctx, idempotencyKey);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(c, svc.lines(ctx, c.id()))))
        .build();
  }

  @Operation(
      summary = "List landed charges",
      description =
          "By receipt (?grId=), by order (?poId=), or every charge in the business; newest first.")
  @APIResponse(responseCode = "404", description = "The receipt or order is not this business's")
  @APIResponse(
      responseCode = "403",
      description =
          "STORE_ACCESS_DENIED: the receipt or order named is another store's than the caller's")
  @GET
  public Response list(@QueryParam("grId") UUID grId, @QueryParam("poId") UUID poId) {
    List<LandedCostResponse> out =
        svc.list(ctx, grId, poId).stream().map(c -> Mappers.toDto(c, List.of())).toList();
    return Response.ok(ApiResponse.ok(out)).build();
  }

  @Operation(summary = "One landed charge, with its lines")
  @APIResponse(responseCode = "404", description = "PURCHASE_LANDED_NOT_FOUND")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the charge landed at another store than the caller's")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    var c = svc.get(ctx, id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(c, svc.lines(ctx, id)))).build();
  }

  @Operation(
      summary = "Reverse a landed charge",
      description =
          "With a reason. Posts the mirror and publishes LandedCostReversed; the charge and its lines"
              + " stay, marked. Apply a new charge for the corrected figure.")
  @APIResponse(responseCode = "200", description = "Reversed")
  @APIResponse(responseCode = "400", description = "PURCHASE_LANDED_REASON_REQUIRED")
  @APIResponse(responseCode = "404", description = "PURCHASE_LANDED_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description = "PURCHASE_LANDED_REVERSED, PURCHASE_PERIOD_CLOSED")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a buying role, or STORE_ACCESS_DENIED: the charge landed at another store than"
              + " the caller's")
  @POST
  @Path("/{id}/reversal")
  public Response reverse(@PathParam("id") UUID id, ReverseLandedCostRequest req) {
    Validations.validate(req);
    var c = svc.reverse(id, req, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(c, svc.lines(ctx, id)))).build();
  }
}
