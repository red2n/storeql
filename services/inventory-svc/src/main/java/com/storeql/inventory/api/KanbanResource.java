package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.CreateKanbanCardRequest;
import com.storeql.inventory.dto.Dtos.KanbanCardResponse;
import com.storeql.inventory.dto.Dtos.TriggerKanbanRequest;
import com.storeql.inventory.dto.Dtos.UpdateOrderModifiersRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Kanban replenishment cards (Gap #18), including their order-modifier fields (Gap #28's kanban
 * half — matches {@code KanbanRepository}'s grouping at the data layer). Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Kanban Replenishment")
public class KanbanResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Creates a kanban replenishment card.
   *
   * <p>Defines a fixed reorder quantity card for a variant at a store, optionally sourced from
   * another store.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Create a kanban replenishment card",
      description =
          "Defines a fixed reorder quantity card for a variant at a store, optionally"
              + " sourced from another store.")
  @APIResponse(responseCode = "201", description = "Created")
  @POST
  @Path("/kanban-cards")
  public Response createKanbanCard(CreateKanbanCardRequest req) {
    // A card fixes what a store reorders: management's, at a store the caller keeps. Triggering
    // and replenishing a card stay the floor's.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID variantId = uuid(req.variantId(), "variantId");
    UUID sourceStoreId =
        req.sourceStoreId() != null && !req.sourceStoreId().isBlank()
            ? uuid(req.sourceStoreId(), "sourceStoreId")
            : null;
    var card =
        service.createKanbanCard(
            tenantId,
            storeId,
            variantId,
            req.kanbanType(),
            req.reorderQty(),
            sourceStoreId,
            req.supplierRef(),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toKanbanCard(card)))
        .build();
  }

  /**
   * Lists kanban cards.
   *
   * <p>Filterable by store and status.
   *
   * @param store the store (query parameter)
   * @param status the status (query parameter)
   */
  @Operation(summary = "List kanban cards", description = "Filterable by store and status.")
  @APIResponse(responseCode = "200", description = "List kanban cards")
  @GET
  @Path("/kanban-cards")
  public ApiResponse<List<KanbanCardResponse>> listKanbanCards(
      @QueryParam("store") String store, @QueryParam("status") String status) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listKanbanCards(tenantId, storeId, status).stream()
            .map(Mappers::toKanbanCard)
            .toList());
  }

  /**
   * Gets a kanban card by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} kanban card not found
   */
  @Operation(summary = "Get a kanban card by id")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @GET
  @Path("/kanban-cards/{id}")
  public ApiResponse<KanbanCardResponse> getKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.getKanbanCard(tenantId, id)));
  }

  /**
   * Triggers a kanban card.
   *
   * <p>Signals the card's reorder point has been hit, moving it to TRIGGERED status.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} kanban card not found
   */
  @Operation(
      summary = "Trigger a kanban card",
      description = "Signals the card's reorder point has been hit, moving it to TRIGGERED status.")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @POST
  @Path("/kanban-cards/{id}/trigger")
  public ApiResponse<KanbanCardResponse> triggerKanbanCard(
      @PathParam("id") UUID id, TriggerKanbanRequest req) {
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getKanbanCard(tenantId, id).storeId());
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.triggerKanbanCard(tenantId, id, req != null ? req.notes() : null)));
  }

  /**
   * Marks a kanban card as replenished.
   *
   * <p>Closes the reorder cycle for a triggered card.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} kanban card not found
   */
  @Operation(
      summary = "Mark a kanban card as replenished",
      description = "Closes the reorder cycle for a triggered card.")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @POST
  @Path("/kanban-cards/{id}/replenish")
  public ApiResponse<KanbanCardResponse> replenishKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getKanbanCard(tenantId, id).storeId());
    return ApiResponse.ok(Mappers.toKanbanCard(service.replenishKanbanCard(tenantId, id)));
  }

  /**
   * Updates a kanban card's order modifiers.
   *
   * <p>Sets min/max order quantity and lot-size multiplier applied when computing the actual
   * reorder qty (Gap #28).
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} kanban card not found
   */
  @Operation(
      summary = "Update a kanban card's order modifiers",
      description =
          "Sets min/max order quantity and lot-size multiplier applied when computing"
              + " the actual reorder qty (Gap #28).")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @PUT
  @Path("/kanban-cards/{id}/order-modifiers")
  public ApiResponse<KanbanCardResponse> updateKanbanModifiers(
      @PathParam("id") UUID id, UpdateOrderModifiersRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getKanbanCard(tenantId, id).storeId());
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.updateKanbanOrderModifiers(
                tenantId, id, req.minOrderQty(), req.maxOrderQty(), req.lotMultiplier())));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
