package com.storeql.inventory.api;

import com.storeql.inventory.domain.Domain.MoveOrderLine;
import com.storeql.inventory.dto.Dtos.CreateMoveOrderRequest;
import com.storeql.inventory.dto.Dtos.MoveOrderResponse;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Move orders (Gap #5): intra-store zone-to-zone stock moves. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Move Orders")
public class MoveOrderResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Creates a move order.
   *
   * <p>Requests one or more variants be moved from one zone to another within the same store.
   *
   * @param req the request body
   * @return move order created ({@code 201})
   */
  @Operation(
      summary = "Create a move order",
      description =
          "Requests one or more variants be moved from one zone to another within the"
              + " same store.")
  @APIResponse(responseCode = "201", description = "Move order created")
  @POST
  @Path("/move-orders")
  public Response createMoveOrder(CreateMoveOrderRequest req) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER); // SJ-D73
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = uuid(req.fromStoreId(), "fromStoreId");
    UUID toStore = uuid(req.toStoreId(), "toStoreId");
    ctx.requireStoreAccess(toStore); // SJ-D74: a move stays within the caller's stores
    List<MoveOrderLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new MoveOrderLine(
                        null,
                        tenantId,
                        null,
                        uuid(l.variantId(), "variantId"),
                        l.requestedQty(),
                        null))
            .toList();
    var order =
        service.createMoveOrder(
            tenantId, fromStore, toStore, req.fromZone(), req.toZone(), req.notes(), lines);
    var withLines = service.getMoveOrder(tenantId, order.id());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toMoveOrder(withLines.order(), withLines.lines())))
        .build();
  }

  /**
   * Lists move orders.
   *
   * <p>Filterable by store and status.
   *
   * @param store the store (query parameter)
   * @param status the status (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(summary = "List move orders", description = "Filterable by store and status.")
  @APIResponse(responseCode = "200", description = "List move orders")
  @GET
  @Path("/move-orders")
  public ApiResponse<List<MoveOrderResponse>> listMoveOrders(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listMoveOrders(tenantId, storeId, status, limit).stream()
            .map(o -> Mappers.toMoveOrder(o, service.getMoveOrder(tenantId, o.id()).lines()))
            .toList());
  }

  /**
   * Gets a move order by id, with its lines.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such move order
   */
  @Operation(summary = "Get a move order by id, with its lines")
  @APIResponse(responseCode = "404", description = "No such move order")
  @GET
  @Path("/move-orders/{id}")
  public ApiResponse<MoveOrderResponse> getMoveOrder(@PathParam("id") UUID id) {
    var wl = service.getMoveOrder(ctx.requireTenantId(), id);
    ctx.requireAnyStoreAccess(wl.order().fromStoreId(), wl.order().toStoreId());
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  /**
   * Picks a move order.
   *
   * <p>Marks the move order picked, deducting the moved qty from the source zone.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such move order; {@code 422} move order is
   *     not in a pickable state
   */
  @Operation(
      summary = "Pick a move order",
      description = "Marks the move order picked, deducting the moved qty from the source zone.")
  @APIResponse(responseCode = "404", description = "No such move order")
  @APIResponse(responseCode = "422", description = "Move order is not in a pickable state")
  @POST
  @Path("/move-orders/{id}/pick")
  public ApiResponse<MoveOrderResponse> pickMoveOrder(@PathParam("id") UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getMoveOrder(tenantId, id).order().fromStoreId());
    var wl = service.pickMoveOrder(tenantId, id);
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  /**
   * Cancels a move order.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such move order; {@code 422} move order
   *     cannot be cancelled in its current state
   */
  @Operation(summary = "Cancel a move order")
  @APIResponse(responseCode = "404", description = "No such move order")
  @APIResponse(
      responseCode = "422",
      description = "Move order cannot be cancelled in its current state")
  @POST
  @Path("/move-orders/{id}/cancel")
  public ApiResponse<MoveOrderResponse> cancelMoveOrder(@PathParam("id") UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getMoveOrder(tenantId, id).order().fromStoreId());
    var cancelled = service.cancelMoveOrder(tenantId, id);
    var wl = service.getMoveOrder(ctx.requireTenantId(), cancelled.id());
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
