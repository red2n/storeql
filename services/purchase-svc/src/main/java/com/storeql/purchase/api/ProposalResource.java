package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.ProposalRunRequest;
import com.storeql.purchase.dto.Dtos.ProposalRunResponse;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.ProposalService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** The automatic order proposal (06.x): run it for a store, read what it did. */
@Path("/purchase-orders/proposals")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Order proposals")
public class ProposalResource {

  @Inject ProposalService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Propose orders for a store",
      description =
          "Reads the store's reorder plans, stock on hand and forecast from inventory-svc, adds what"
              + " is already on order here, and for every item at or below its reorder point raises a"
              + " DRAFT purchase order on the supplier the business last bought it from (or one whose"
              + " item code names it) — one order per supplier, the quantity by the reorder-point"
              + " rule (the EOQ, or cover for the period ahead) bent to the supplier's order"
              + " modifiers, every line carrying the arithmetic that produced it. A person submits the"
              + " drafts; nothing is committed to a supplier here. Refused while a proposed order for"
              + " the store is still a draft (409 PURCHASE_PROPOSAL_OPEN) and when inventory-svc"
              + " cannot be read (503 PURCHASE_PROPOSAL_STOCK_UNAVAILABLE); without a forecast the"
              + " plan's average daily demand stands in and the line says so.")
  @APIResponse(
      responseCode = "200",
      description = "What the run raised, and what it skipped and why")
  @APIResponse(
      responseCode = "400",
      description = "storeId is not a UUID, or coverDays is outside 1..365")
  @APIResponse(
      responseCode = "409",
      description = "A proposed order for the store is still a draft")
  @APIResponse(responseCode = "503", description = "The stock position could not be read")
  @POST
  @Path("/run")
  public ApiResponse<ProposalRunResponse> run(ProposalRunRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toDto(service.run(ctx, req.storeId(), req.coverDays())));
  }

  @Operation(
      summary = "The store's proposal runs, latest first",
      description =
          "Each run with the orders it raised as they stand now and the items it skipped.")
  @APIResponse(responseCode = "200", description = "Runs")
  @APIResponse(responseCode = "400", description = "No store named, or one that is not a UUID")
  @GET
  public ApiResponse<List<ProposalRunResponse>> list(
      @QueryParam("store") String store, @QueryParam("limit") Integer limitParam) {
    if (store == null || store.isBlank()) {
      throw ApiException.badRequest("STORE_REQUIRED", "Name the store to read proposal runs for");
    }
    UUID storeId = Parsing.uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(service.list(ctx, storeId, limit).stream().map(Mappers::toDto).toList());
  }
}
