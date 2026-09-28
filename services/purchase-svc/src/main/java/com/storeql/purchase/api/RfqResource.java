package com.storeql.purchase.api;

import com.storeql.ids.Ids;
import com.storeql.purchase.dto.Dtos.CancelPurchaseOrderRequest;
import com.storeql.purchase.dto.RfqDtos.CreateRfqRequest;
import com.storeql.purchase.dto.RfqDtos.RfqAwardRequest;
import com.storeql.purchase.dto.RfqDtos.RfqQuoteRequest;
import com.storeql.purchase.mapper.RfqMappers;
import com.storeql.purchase.service.RfqService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * RFQ and sourcing: a request for quotation to several suppliers, the quotes as the buyer records
 * them, the comparison in the business's own money, and the award that raises the orders.
 */
@RequestScoped
@Path("/rfqs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "RFQs")
public class RfqResource {

  @Inject RfqService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Raise a request for quotation",
      description =
          "The lines wanted and the suppliers asked. Raised as DRAFT; issue it when it goes out."
              + " Buying roles: owner, manager, storekeeper.")
  @APIResponse(responseCode = "201", description = "Raised")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_RFQ_LINES_REQUIRED, PURCHASE_RFQ_SUPPLIERS_REQUIRED, PURCHASE_RFQ_LINE_DUPLICATE,"
              + " PURCHASE_RFQ_SUPPLIER_DUPLICATE")
  @APIResponse(responseCode = "404", description = "PURCHASE_SUPPLIER_NOT_FOUND")
  @POST
  public Response create(CreateRfqRequest req) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(RfqMappers.toDto(svc.create(ctx, req), svc.grades(ctx))))
        .build();
  }

  @Operation(summary = "List requests", description = "Newest first, at one status or all of them.")
  @APIResponse(responseCode = "200", description = "The requests")
  @APIResponse(responseCode = "400", description = "PURCHASE_RFQ_STATUS_INVALID")
  @GET
  public Response list(@QueryParam("status") String status, @QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.list(ctx, status, Cursor.clampLimit(limit)).stream()
                    .map(RfqMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Read a request",
      description =
          "With its lines, each supplier's quote and scorecard grade, the comparison in the"
              + " business's own money, and the awards.")
  @APIResponse(responseCode = "200", description = "The request")
  @APIResponse(responseCode = "404", description = "PURCHASE_RFQ_NOT_FOUND")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    return Response.ok(
            ApiResponse.ok(RfqMappers.toDto(svc.detail(ctx, Ids.parse(id)), svc.grades(ctx))))
        .build();
  }

  @Operation(
      summary = "Issue a request",
      description = "It has gone to the suppliers; quotes may be recorded.")
  @APIResponse(responseCode = "200", description = "Issued")
  @APIResponse(responseCode = "409", description = "PURCHASE_RFQ_NOT_DRAFT")
  @POST
  @Path("/{id}/issue")
  public Response issue(@PathParam("id") String id) {
    return Response.ok(
            ApiResponse.ok(RfqMappers.toDto(svc.issue(ctx, Ids.parse(id)), svc.grades(ctx))))
        .build();
  }

  @Operation(
      summary = "Record a supplier's quote",
      description =
          "What the supplier said, in their currency: terms and a price per line. Replaces any"
              + " earlier quote from them. A line left out is a line they did not price.")
  @APIResponse(responseCode = "200", description = "Recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_RFQ_SUPPLIER_NOT_INVITED, PURCHASE_RFQ_LINE_UNKNOWN, PURCHASE_RFQ_QUOTE_EMPTY,"
              + " PURCHASE_RFQ_LINE_DUPLICATE")
  @APIResponse(responseCode = "409", description = "PURCHASE_RFQ_NOT_ISSUED")
  @PUT
  @Path("/{id}/quotes/{supplierId}")
  public Response quote(
      @PathParam("id") String id, @PathParam("supplierId") String supplierId, RfqQuoteRequest req) {
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                RfqMappers.toDto(
                    svc.quote(ctx, Ids.parse(id), Ids.parse(supplierId), req), svc.grades(ctx))))
        .build();
  }

  @Operation(summary = "A supplier declines to quote")
  @APIResponse(responseCode = "200", description = "Recorded")
  @APIResponse(responseCode = "400", description = "PURCHASE_RFQ_SUPPLIER_NOT_INVITED")
  @APIResponse(responseCode = "409", description = "PURCHASE_RFQ_NOT_ISSUED")
  @POST
  @Path("/{id}/quotes/{supplierId}/decline")
  public Response decline(@PathParam("id") String id, @PathParam("supplierId") String supplierId) {
    return Response.ok(
            ApiResponse.ok(
                RfqMappers.toDto(
                    svc.decline(ctx, Ids.parse(id), Ids.parse(supplierId)), svc.grades(ctx))))
        .build();
  }

  @Operation(
      summary = "Award the request",
      description =
          "Which supplier gets which line, each at the price they quoted. Raises one DRAFT purchase"
              + " order per awarded supplier, in their currency, for the day the goods are needed;"
              + " a person submits it as any draft. Awarded once.")
  @APIResponse(responseCode = "200", description = "Awarded")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_RFQ_AWARDS_REQUIRED, PURCHASE_RFQ_AWARD_DUPLICATE, PURCHASE_RFQ_LINE_UNKNOWN")
  @APIResponse(
      responseCode = "409",
      description = "PURCHASE_RFQ_NOT_ISSUED, PURCHASE_RFQ_NOT_QUOTED")
  @POST
  @Path("/{id}/award")
  public Response award(@PathParam("id") String id, RfqAwardRequest req) {
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(RfqMappers.toDto(svc.award(ctx, Ids.parse(id), req), svc.grades(ctx))))
        .build();
  }

  @Operation(summary = "Cancel a request", description = "With a reason; not once awarded.")
  @APIResponse(responseCode = "200", description = "Cancelled")
  @APIResponse(responseCode = "409", description = "PURCHASE_RFQ_CLOSED")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id, CancelPurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                RfqMappers.toDto(svc.cancel(ctx, Ids.parse(id), req.reason()), svc.grades(ctx))))
        .build();
  }
}
