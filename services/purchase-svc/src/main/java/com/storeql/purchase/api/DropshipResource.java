package com.storeql.purchase.api;

import com.storeql.ids.Ids;
import com.storeql.purchase.dto.Dtos.CreateDropshipArrangementRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.DropshipService;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Dropship arrangements: which supplier fulfils a variant per order. Management only. */
@RequestScoped
@Path("/admin/dropship/arrangements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Dropship")
public class DropshipResource {

  @Inject DropshipService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Arrange for a supplier to fulfil a variant per order",
      description =
          "Stock the business never holds: inventory-svc is told, so the variant is available with"
              + " none on the shelf and a checkout hold on it draws nothing; each confirmed order"
              + " with the variant raises a DRAFT purchase order for this supplier at this cost,"
              + " shipped to the customer. One live arrangement per variant.")
  @APIResponse(responseCode = "201", description = "Arrangement made")
  @APIResponse(responseCode = "404", description = "PURCHASE_SUPPLIER_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "PURCHASE_DROPSHIP_ARRANGEMENT_EXISTS")
  @POST
  public Response create(CreateDropshipArrangementRequest req) {
    Validations.validate(req);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(svc.create(ctx, req)))).build();
  }

  @Operation(summary = "List dropship arrangements", description = "Live ones first.")
  @APIResponse(responseCode = "200", description = "The arrangements")
  @GET
  public Response list(@QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.list(ctx, Cursor.clampLimit(limit)).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "End an arrangement",
      description = "The variant is fulfilled from stock again, and inventory-svc is told.")
  @APIResponse(responseCode = "200", description = "Arrangement ended")
  @APIResponse(responseCode = "404", description = "PURCHASE_DROPSHIP_ARRANGEMENT_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "PURCHASE_DROPSHIP_ARRANGEMENT_ENDED")
  @POST
  @Path("/{id}/end")
  public Response end(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.end(ctx, Ids.parse(id))))).build();
  }
}
