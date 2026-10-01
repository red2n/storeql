package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.ComputeRopResult;
import com.storeql.inventory.dto.Dtos.RopPlanResponse;
import com.storeql.inventory.dto.Dtos.UpdateOrderModifiersRequest;
import com.storeql.inventory.dto.Dtos.UpsertRopPlanRequest;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reorder point / EOQ plans (Gap #19), including their order-modifier fields (Gap #28's ROP half —
 * matches {@code ReorderPointRepository}'s grouping at the data layer). Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reorder Point & EOQ")
public class ReorderPointResource {

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Upserts a reorder-point/EOQ plan.
   *
   * <p>Sets the lead time, ordering cost, holding cost %, and unit cost inputs used to compute the
   * variant's ROP and economic order quantity.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Upsert a reorder-point/EOQ plan",
      description =
          "Sets the lead time, ordering cost, holding cost %, and unit cost inputs used"
              + " to compute the variant's ROP and economic order quantity. Management only;"
              + " reading a plan stays open to staff.")
  @APIResponse(responseCode = "200", description = "Upsert a reorder-point/EOQ plan")
  @APIResponse(responseCode = "403", description = "Not management")
  @PUT
  @Path("/rop-plans")
  public ApiResponse<RopPlanResponse> upsertRopPlan(UpsertRopPlanRequest req) {
    // Ordering cost, holding cost and unit cost are the business's money figures: management sets
    // them, staff read the plans they produce.
    ctx.requireAnyRole(MANAGEMENT);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        Mappers.toRopPlan(
            service.upsertRopPlan(
                tenantId,
                storeId,
                variantId,
                req.leadTimeDays(),
                req.orderingCost(),
                req.holdingCostPct(),
                req.unitCost())));
  }

  /**
   * Lists ROP/EOQ plans for a store.
   *
   * @param store the store (query parameter)
   */
  @Operation(summary = "List ROP/EOQ plans for a store")
  @APIResponse(responseCode = "200", description = "List ROP/EOQ plans for a store")
  @GET
  @Path("/rop-plans")
  public ApiResponse<List<RopPlanResponse>> listRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        service.listRopPlans(tenantId, storeId).stream().map(Mappers::toRopPlan).toList());
  }

  /**
   * Gets the ROP/EOQ plan for a specific variant at a store.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @throws com.storeql.web.ApiException {@code 404} rOP plan not found
   */
  @Operation(summary = "Get the ROP/EOQ plan for a specific variant at a store")
  @APIResponse(responseCode = "404", description = "ROP plan not found")
  @GET
  @Path("/rop-plans/by-variant")
  public ApiResponse<RopPlanResponse> getRopPlan(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(Mappers.toRopPlan(service.getRopPlan(tenantId, storeId, variantId)));
  }

  /**
   * Recomputes ROP and EOQ for a store's plans.
   *
   * <p>Recalculates avgDailyDemand-derived rop and eoq for every plan at the store.
   *
   * @param store the store (query parameter)
   */
  @Operation(
      summary = "Recompute ROP and EOQ for a store's plans",
      description = "Recalculates avgDailyDemand-derived rop and eoq for every plan at the store.")
  @APIResponse(responseCode = "200", description = "Recompute ROP and EOQ for a store's plans")
  @POST
  @Path("/rop-plans/compute")
  public ApiResponse<ComputeRopResult> computeRopPlans(@QueryParam("store") String store) {
    ctx.requireAnyRole(MANAGEMENT);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    ctx.requireStoreAccess(storeId);
    int count = service.computeRopPlans(tenantId, storeId);
    return ApiResponse.ok(new ComputeRopResult(count));
  }

  /**
   * Updates a ROP plan's order modifiers.
   *
   * <p>Sets min/max order quantity and lot-size multiplier applied to the computed EOQ (Gap #28).
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} rOP plan not found
   */
  @Operation(
      summary = "Update a ROP plan's order modifiers",
      description =
          "Sets min/max order quantity and lot-size multiplier applied to the computed"
              + " EOQ (Gap #28).")
  @APIResponse(responseCode = "403", description = "Not management, or STORE_ACCESS_DENIED")
  @APIResponse(responseCode = "404", description = "ROP plan not found")
  @PUT
  @Path("/rop-plans/{id}/order-modifiers")
  public ApiResponse<RopPlanResponse> updateRopModifiers(
      @PathParam("id") UUID id, UpdateOrderModifiersRequest req) {
    // The order modifiers shape what gets ordered: management's, at a store the caller keeps. A
    // plan of another business is not found, whatever role asks.
    ctx.requireAnyRole(MANAGEMENT);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getRopPlanById(tenantId, id).storeId());
    return ApiResponse.ok(
        Mappers.toRopPlan(
            service.updateRopOrderModifiers(
                tenantId, id, req.minOrderQty(), req.maxOrderQty(), req.lotMultiplier())));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
