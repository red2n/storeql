package com.storeql.purchase.api;

import com.storeql.ids.Ids;
import com.storeql.purchase.dto.Dtos.CreateConsignmentSettlementRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.ConsignmentService;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Consignment stock, the buyer's side: what has sold of a supplier's stock and is owed for, and the
 * settlements that gather it. Management only.
 */
@RequestScoped
@Path("/admin/consignment")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Consignment")
public class ConsignmentResource {

  @Inject ConsignmentService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "List consignment sales",
      description =
          "Each sale inventory-svc drew from a batch the supplier still owns, at the order's"
              + " price: owed to the supplier the moment it sold. Newest first; ?settled=false for"
              + " what a settlement has not yet taken.")
  @APIResponse(responseCode = "200", description = "The sales")
  @GET
  @Path("/sales")
  public Response sales(
      @QueryParam("supplierId") String supplierId,
      @QueryParam("settled") Boolean settled,
      @QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.listSales(ctx, supplierId, settled, Cursor.clampLimit(limit)).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Settle a supplier's consignment sales for a period",
      description =
          "Gathers every unsettled sale of the supplier sold within the period into one statement"
              + " the supplier invoices against; a sale is settled once. Nothing left to settle is"
              + " PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE.")
  @APIResponse(responseCode = "201", description = "The settlement")
  @APIResponse(responseCode = "400", description = "PURCHASE_CONSIGNMENT_PERIOD_INVALID")
  @APIResponse(responseCode = "404", description = "PURCHASE_SUPPLIER_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE")
  @POST
  @Path("/settlements")
  public Response settle(CreateConsignmentSettlementRequest req) {
    Validations.validate(req);
    var settlement = svc.settle(ctx, req);
    return Response.status(201)
        .entity(
            ApiResponse.ok(Mappers.toDto(settlement, svc.settlementSales(ctx, settlement.id()))))
        .build();
  }

  @Operation(summary = "List consignment settlements", description = "Newest first.")
  @APIResponse(responseCode = "200", description = "The settlements")
  @GET
  @Path("/settlements")
  public Response settlements(
      @QueryParam("supplierId") String supplierId, @QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.listSettlements(ctx, supplierId, Cursor.clampLimit(limit)).stream()
                    .map(s -> Mappers.toDto(s, null))
                    .toList()))
        .build();
  }

  @Operation(summary = "Read a settlement", description = "With the sales it gathered.")
  @APIResponse(responseCode = "200", description = "The settlement and its sales")
  @APIResponse(responseCode = "404", description = "PURCHASE_CONSIGNMENT_SETTLEMENT_NOT_FOUND")
  @GET
  @Path("/settlements/{id}")
  public Response settlement(@PathParam("id") String id) {
    UUID settlementId = Ids.parse(id);
    var settlement = svc.getSettlement(ctx, settlementId);
    return Response.ok(
            ApiResponse.ok(Mappers.toDto(settlement, svc.settlementSales(ctx, settlementId))))
        .build();
  }
}
