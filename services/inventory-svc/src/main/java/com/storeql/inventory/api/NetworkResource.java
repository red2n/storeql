package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.dto.NetworkDtos;
import com.storeql.inventory.dto.NetworkDtos.ServingRequest;
import com.storeql.inventory.dto.NetworkDtos.TransferProposalRequest;
import com.storeql.inventory.mapper.NetworkMappers;
import com.storeql.inventory.service.NetworkService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Depot / DC replenishment: which warehouse serves which shop, what a shop buys direct, the
 * sourcing purchase-svc reads, and the transfer proposals a warehouse raises for its shops.
 */
@RequestScoped
@Path("/admin/inventory/network")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Depot Replenishment")
public class NetworkResource {

  @Inject NetworkService svc;
  @Inject com.storeql.inventory.service.CrossDockService crossDock;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The network",
      description = "Each shop, its warehouse and what it buys direct.")
  @APIResponse(responseCode = "200", description = "The network")
  @GET
  @Path("/serving")
  public Response network() {
    return Response.ok(
            ApiResponse.ok(svc.network(ctx).stream().map(NetworkMappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Set a shop's warehouse",
      description = "Replaces an earlier one; what the shop buys direct stays.")
  @APIResponse(responseCode = "200", description = "Set")
  @APIResponse(
      responseCode = "400",
      description =
          "INVENTORY_SERVING_SELF, INVENTORY_SERVING_STORE_UNKNOWN,"
              + " INVENTORY_SERVING_NOT_A_WAREHOUSE, INVENTORY_SERVING_WAREHOUSE_TO_WAREHOUSE,"
              + " INVENTORY_SERVING_LEAD_TIME_INVALID")
  @PUT
  @Path("/serving")
  public Response setServing(ServingRequest req) {
    Validations.validate(req);
    var s =
        svc.setServing(
            ctx, Ids.parse(req.storeId()), Ids.parse(req.warehouseId()), req.leadTimeDays());
    return Response.ok(
            ApiResponse.ok(
                NetworkMappers.toDto(new NetworkService.ShopServing(s, java.util.List.of()))))
        .build();
  }

  @Operation(summary = "Take a shop out of the network", description = "It buys everything direct.")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "INVENTORY_SERVING_NOT_FOUND")
  @DELETE
  @Path("/serving/{storeId}")
  public Response removeServing(@PathParam("storeId") String storeId) {
    svc.removeServing(ctx, Ids.parse(storeId));
    return Response.noContent().build();
  }

  @Operation(summary = "The shop buys this product direct from its supplier")
  @APIResponse(responseCode = "204", description = "Marked")
  @APIResponse(responseCode = "404", description = "INVENTORY_SERVING_NOT_FOUND")
  @PUT
  @Path("/serving/{storeId}/direct/{variantId}")
  public Response buyDirect(
      @PathParam("storeId") String storeId, @PathParam("variantId") String variantId) {
    svc.buyDirect(ctx, Ids.parse(storeId), Ids.parse(variantId));
    return Response.noContent().build();
  }

  @Operation(summary = "The product comes from the warehouse again")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "INVENTORY_SERVING_EXCEPTION_NOT_FOUND")
  @DELETE
  @Path("/serving/{storeId}/direct/{variantId}")
  public Response fromWarehouse(
      @PathParam("storeId") String storeId, @PathParam("variantId") String variantId) {
    svc.fromWarehouse(ctx, Ids.parse(storeId), Ids.parse(variantId));
    return Response.noContent().build();
  }

  @Operation(
      summary = "How a store is supplied",
      description =
          "A shop's warehouse and what it buys direct; for a warehouse, its shops and what they"
              + " are expected to need. Read by purchase-svc's order proposal.")
  @APIResponse(responseCode = "200", description = "The sourcing")
  @GET
  @Path("/sourcing")
  public Response sourcing(@QueryParam("storeId") String storeId) {
    return Response.ok(ApiResponse.ok(NetworkMappers.toDto(svc.sourcing(ctx, Ids.parse(storeId)))))
        .build();
  }

  @Operation(
      summary = "Propose transfers to a warehouse's shops",
      description =
          "Per shop and product, the reorder-point rule; a fair share when the warehouse is short;"
              + " one DRAFT transfer per shop with a reason on every line, for a person to release."
              + " Idempotent on the Idempotency-Key.")
  @APIResponse(responseCode = "201", description = "Proposed")
  @APIResponse(
      responseCode = "400",
      description = "INVENTORY_SERVING_NOT_A_WAREHOUSE, INVENTORY_PROPOSAL_COVER_INVALID")
  @APIResponse(
      responseCode = "409",
      description = "INVENTORY_PROPOSAL_NOTHING_SERVED, INVENTORY_PROPOSAL_OPEN")
  @POST
  @Path("/proposals")
  public Response propose(
      TransferProposalRequest req, @HeaderParam("Idempotency-Key") String idempotencyKey) {
    Validations.validate(req);
    var r =
        svc.run(
            ctx,
            Ids.parse(req.warehouseId()),
            req.coverDays(),
            idempotencyKey == null || idempotencyKey.isBlank()
                ? null
                : idempotencyKey.toLowerCase(Locale.ROOT));
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(NetworkMappers.toDto(r.run(), r.transfers())))
        .build();
  }

  @Operation(
      summary = "What each store holds of some products",
      description =
          "Per store, what is available of each product named, and which the supplier fulfils per"
              + " order — order-svc's read when it routes an online order. Staff only.")
  @APIResponse(responseCode = "200", description = "The stock by store")
  @GET
  @Path("/stock")
  public Response stock(@QueryParam("variants") String variants) {
    java.util.List<java.util.UUID> ids =
        variants == null || variants.isBlank()
            ? java.util.List.of()
            : java.util.Arrays.stream(variants.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(Ids::parse)
                .toList();
    if (ids.size() > 200) {
      throw ApiException.badRequest("VALIDATION_FAILED", "variants: at most 200");
    }
    var stock = svc.stockByStore(ctx, ids);
    return Response.ok(
            ApiResponse.ok(
                new NetworkDtos.StockByStoreResponse(
                    stock.levels().stream()
                        .map(
                            l ->
                                new NetworkDtos.StoreStockResponse(
                                    l.storeId(), l.variantId(), l.available()))
                        .toList(),
                    java.util.List.copyOf(stock.dropship()))))
        .build();
  }

  @Operation(
      summary = "What a purchase order still owes the shops across the dock",
      description =
          "Per shop and product, as purchase-svc last announced it, less what deliveries already"
              + " sent across.")
  @APIResponse(responseCode = "200", description = "What is owed")
  @GET
  @Path("/crossdock")
  public Response owed(@QueryParam("purchaseOrderId") String purchaseOrderId) {
    return Response.ok(
            ApiResponse.ok(
                crossDock.owed(ctx, Ids.parse(purchaseOrderId)).stream()
                    .map(
                        o ->
                            new NetworkDtos.OwedResponse(
                                o.warehouseId(), o.storeId(), o.variantId(), o.qty()))
                    .toList()))
        .build();
  }

  @Operation(
      summary = "The shops' needs for a product, and their shares of a quantity",
      description =
          "What each shop the warehouse serves needs of the product now, and how the quantity"
              + " would be shared among them fairly. Read by purchase-svc's cross-dock fill.")
  @APIResponse(responseCode = "200", description = "The shares")
  @GET
  @Path("/needs")
  public Response needs(
      @QueryParam("warehouseId") String warehouseId,
      @QueryParam("variantId") String variantId,
      @QueryParam("qty") String qty) {
    BigDecimal amount;
    try {
      amount = qty == null || qty.isBlank() ? BigDecimal.ZERO : new BigDecimal(qty);
    } catch (NumberFormatException e) {
      throw new ApiException(400, "VALIDATION_FAILED", "qty: not a number", java.util.List.of(), e);
    }
    return Response.ok(
            ApiResponse.ok(
                svc.needShares(ctx, Ids.parse(warehouseId), Ids.parse(variantId), amount).stream()
                    .map(NetworkMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "A warehouse's proposal runs",
      description = "Newest first, the last twenty.")
  @APIResponse(responseCode = "200", description = "The runs")
  @GET
  @Path("/proposals")
  public Response runs(@QueryParam("warehouseId") String warehouseId) {
    return Response.ok(
            ApiResponse.ok(
                svc.runs(ctx, Ids.parse(warehouseId)).stream()
                    .map(r -> NetworkMappers.toDto(r, java.util.List.of()))
                    .toList()))
        .build();
  }
}
