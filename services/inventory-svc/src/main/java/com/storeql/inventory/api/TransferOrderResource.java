package com.storeql.inventory.api;

import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.inventory.dto.Dtos.CreateTransferOrderRequest;
import com.storeql.inventory.dto.Dtos.TransferOrderResponse;
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

/** Transfer orders (Gap #6): inter-store stock transfers. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transfer Orders")
public class TransferOrderResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;
  @Inject com.storeql.inventory.service.NetworkService network;

  /**
   * Creates a transfer order.
   *
   * <p>Requests one or more variants be transferred between two stores.
   *
   * @param req the request body
   * @return transfer order created ({@code 201})
   */
  @Operation(
      summary = "Create a transfer order",
      description = "Requests one or more variants be transferred between two stores.")
  @APIResponse(responseCode = "201", description = "Transfer order created")
  @POST
  @Path("/transfers")
  public Response createTransfer(CreateTransferOrderRequest req) {
    // SJ-D73: moving stock is the storekeeper's, never the till's. The sending store is held to the
    // caller by StoreScopeFilter (fromStoreId); the receiving store's keeper is held at receipt.
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = uuid(req.fromStoreId(), "fromStoreId");
    UUID toStore = uuid(req.toStoreId(), "toStoreId");
    List<TransferOrderLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new TransferOrderLine(
                        null,
                        tenantId,
                        null,
                        uuid(l.variantId(), "variantId"),
                        l.requestedQty(),
                        null,
                        null))
            .toList();
    var wl =
        service.createTransferOrder(
            tenantId, fromStore, toStore, req.transferType(), req.notes(), lines);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines())))
        .build();
  }

  /**
   * Lists transfer orders.
   *
   * <p>Filterable by store and status.
   *
   * @param store the store (query parameter)
   * @param status the status (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(summary = "List transfer orders", description = "Filterable by store and status.")
  @APIResponse(responseCode = "200", description = "List transfer orders")
  @GET
  @Path("/transfers")
  public ApiResponse<List<TransferOrderResponse>> listTransfers(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listTransferOrders(tenantId, storeId, status, limit).stream()
            .map(
                o -> Mappers.toTransferOrder(o, service.getTransferOrder(tenantId, o.id()).lines()))
            .toList());
  }

  /**
   * Gets a transfer order by id, with its lines.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such transfer order
   */
  @Operation(summary = "Get a transfer order by id, with its lines")
  @APIResponse(responseCode = "404", description = "No such transfer order")
  @GET
  @Path("/transfers/{id}")
  public ApiResponse<TransferOrderResponse> getTransfer(@PathParam("id") UUID id) {
    var wl = service.getTransferOrder(ctx.requireTenantId(), id);
    ctx.requireAnyStoreAccess(wl.order().fromStoreId(), wl.order().toStoreId());
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  /**
   * Ships a transfer order.
   *
   * <p>Marks the order shipped, deducting the shipped qty from the source store.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such transfer order; {@code 422} transfer
   *     order is not in a shippable state
   */
  @Operation(
      summary = "Ship a transfer order",
      description = "Marks the order shipped, deducting the shipped qty from the source store.")
  @APIResponse(responseCode = "404", description = "No such transfer order")
  @APIResponse(responseCode = "422", description = "Transfer order is not in a shippable state")
  @POST
  @Path("/transfers/{id}/ship")
  public ApiResponse<TransferOrderResponse> shipTransfer(@PathParam("id") UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getTransferOrder(tenantId, id).order().fromStoreId());
    var wl = service.shipTransferOrder(tenantId, id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  /**
   * Receives a transfer order.
   *
   * <p>Marks the order received, adding the shipped qty into the destination store.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such transfer order; {@code 422} transfer
   *     order is not in a receivable state
   */
  @Operation(
      summary = "Receive a transfer order",
      description = "Marks the order received, adding the shipped qty into the destination store.")
  @APIResponse(responseCode = "404", description = "No such transfer order")
  @APIResponse(responseCode = "422", description = "Transfer order is not in a receivable state")
  @POST
  @Path("/transfers/{id}/receive")
  public ApiResponse<TransferOrderResponse> receiveTransfer(@PathParam("id") UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getTransferOrder(tenantId, id).order().toStoreId());
    var wl = service.receiveTransferOrder(tenantId, id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  /**
   * Cancels a transfer order.
   *
   * <p>Only DRAFT or PENDING transfer orders can be cancelled.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 422} only PENDING transfer orders can be cancelled
   */
  @Operation(
      summary = "Cancel a transfer order",
      description = "Only DRAFT or PENDING transfer orders can be cancelled.")
  @APIResponse(
      responseCode = "422",
      description = "Only DRAFT or PENDING transfer orders can be cancelled")
  @POST
  @Path("/transfers/{id}/cancel")
  public ApiResponse<TransferOrderResponse> cancelTransfer(@PathParam("id") UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    var open = service.getTransferOrder(ctx.requireTenantId(), id).order();
    ctx.requireAnyStoreAccess(open.fromStoreId(), open.toStoreId());
    var cancelled = service.cancelTransferOrder(ctx.requireTenantId(), id);
    var wl = service.getTransferOrder(ctx.requireTenantId(), cancelled.id());
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  /**
   * Releases a transfer a replenishment run proposed: DRAFT becomes PENDING, for the warehouse to
   * ship.
   *
   * @param id the id (path parameter)
   */
  @Operation(
      summary = "Release a proposed transfer",
      description = "A DRAFT raised by a warehouse's replenishment run becomes PENDING.")
  @APIResponse(responseCode = "404", description = "TRANSFER_ORDER_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "INVENTORY_TRANSFER_NOT_DRAFT")
  @POST
  @Path("/transfers/{id}/release")
  public ApiResponse<TransferOrderResponse> releaseTransfer(@PathParam("id") UUID id) {
    var wl = network.release(ctx, id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
