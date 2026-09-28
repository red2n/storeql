package com.storeql.purchase.api;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.LineAllocation;
import com.storeql.purchase.dto.CrossDockDtos.AllocateLineRequest;
import com.storeql.purchase.dto.CrossDockDtos.LineAllocationResponse;
import com.storeql.purchase.service.CrossDockService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Cross-docking: a warehouse order's lines allocated to the shops it serves, so the delivery goes
 * straight across the dock to them.
 */
@RequestScoped
@Path("/purchase-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cross-docking")
public class CrossDockResource {

  @Inject CrossDockService svc;
  @Inject TenantContext ctx;

  @Operation(summary = "A warehouse order's allocations", description = "Every line's, by shop.")
  @APIResponse(responseCode = "200", description = "The allocations")
  @GET
  @Path("/{id}/allocations")
  public Response allocations(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(dto(svc.allocations(ctx, Ids.parse(id))))).build();
  }

  @Operation(
      summary = "Allocate a line to the shops the warehouse serves",
      description =
          "Replaces the line's allocations while the order is a draft; empty clears them.")
  @APIResponse(responseCode = "200", description = "The order's allocations")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_ALLOCATION_NOT_A_WAREHOUSE, PURCHASE_ALLOCATION_NOT_SERVED,"
              + " PURCHASE_ALLOCATION_EXCEEDS_LINE, PURCHASE_ALLOCATION_QTY_INVALID")
  @APIResponse(
      responseCode = "409",
      description = "PURCHASE_ALLOCATION_ORDER_NOT_DRAFT, PURCHASE_ALLOCATION_STOCK_NOT_OWNED")
  @PUT
  @Path("/{id}/lines/{lineId}/allocations")
  public Response allocate(
      @PathParam("id") String id, @PathParam("lineId") String lineId, AllocateLineRequest req) {
    Validations.validate(req);
    List<CrossDockService.Allocation> requested =
        req.allocations().stream()
            .map(
                a -> {
                  if (a == null) {
                    throw ApiException.badRequest(
                        "VALIDATION_FAILED", "allocations: an allocation must not be null");
                  }
                  return new CrossDockService.Allocation(Ids.parse(a.storeId()), a.qty());
                })
            .toList();
    return Response.ok(
            ApiResponse.ok(dto(svc.allocate(ctx, Ids.parse(id), Ids.parse(lineId), requested))))
        .build();
  }

  @Operation(
      summary = "Allocate a line by the shops' needs",
      description = "The served shops' current needs, shared fairly; the buyer can change it.")
  @APIResponse(responseCode = "200", description = "The order's allocations")
  @POST
  @Path("/{id}/lines/{lineId}/allocations/fill")
  public Response fill(@PathParam("id") String id, @PathParam("lineId") String lineId) {
    return Response.ok(
            ApiResponse.ok(dto(svc.fillFromNeeds(ctx, Ids.parse(id), Ids.parse(lineId)))))
        .build();
  }

  private static List<LineAllocationResponse> dto(List<LineAllocation> rows) {
    return rows.stream()
        .map(
            a ->
                new LineAllocationResponse(
                    a.id(), a.poLineId(), a.variantId(), a.storeId(), a.qty()))
        .toList();
  }
}
