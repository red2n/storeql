package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.AccountingPeriodResponse;
import com.storeql.inventory.dto.Dtos.CostingMethodResponse;
import com.storeql.inventory.dto.Dtos.OpenPeriodRequest;
import com.storeql.inventory.dto.Dtos.UpsertCostingMethodRequest;
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
 * Costing methods (Gap #17) and accounting periods — bundled together as they were in
 * AdminResource's original section (matches {@code CostingRepository}'s grouping at the data
 * layer). Extracted from AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Costing & Accounting Periods")
public class CostingResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Sets the costing method for a variant at a store.
   *
   * <p>method must be FIFO or AVERAGE.
   *
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} method is not FIFO or AVERAGE
   */
  @Operation(
      summary = "Set the costing method for a variant at a store",
      description =
          "method must be FIFO or AVERAGE. Management only, at a store the caller keeps; reading"
              + " stays open to staff.")
  @APIResponse(responseCode = "400", description = "method is not FIFO or AVERAGE")
  @APIResponse(responseCode = "403", description = "Not management, or STORE_ACCESS_DENIED")
  @PUT
  @Path("/costing-methods")
  public ApiResponse<CostingMethodResponse> upsertCostingMethod(UpsertCostingMethodRequest req) {
    // The standard cost values the stock: management's, at a store the caller keeps.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID variantId = uuid(req.variantId(), "variantId");
    return ApiResponse.ok(
        Mappers.toCostingMethod(
            service.upsertCostingMethod(
                tenantId, storeId, variantId, req.method(), req.averageCost())));
  }

  /**
   * Lists costing methods for a store.
   *
   * @param store the store (query parameter)
   */
  @Operation(summary = "List costing methods for a store")
  @APIResponse(responseCode = "200", description = "List costing methods for a store")
  @GET
  @Path("/costing-methods")
  public ApiResponse<List<CostingMethodResponse>> listCostingMethods(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listCostingMethods(tenantId, storeId).stream()
            .map(Mappers::toCostingMethod)
            .toList());
  }

  /**
   * Gets the costing method for a specific variant at a store.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @throws com.storeql.web.ApiException {@code 404} costing method not found
   */
  @Operation(summary = "Get the costing method for a specific variant at a store")
  @APIResponse(responseCode = "404", description = "costing method not found")
  @GET
  @Path("/costing-methods/by-variant")
  public ApiResponse<CostingMethodResponse> getCostingMethod(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    return ApiResponse.ok(
        Mappers.toCostingMethod(service.getCostingMethod(tenantId, storeId, variantId)));
  }

  /**
   * Opens a new accounting period for a store.
   *
   * @param req the request body
   * @return period opened ({@code 201})
   */
  @Operation(summary = "Open a new accounting period for a store")
  @APIResponse(responseCode = "201", description = "Period opened")
  @POST
  @Path("/accounting-periods")
  public Response openPeriod(OpenPeriodRequest req) {
    // Opening a period decides when the books close: management's, at a store the caller keeps.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    var period = service.openPeriod(tenantId, storeId, req.periodName(), req.periodDate());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toPeriod(period)))
        .build();
  }

  /**
   * Lists accounting periods for a store.
   *
   * @param store the store (query parameter)
   */
  @Operation(summary = "List accounting periods for a store")
  @APIResponse(responseCode = "200", description = "List accounting periods for a store")
  @GET
  @Path("/accounting-periods")
  public ApiResponse<List<AccountingPeriodResponse>> listPeriods(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listPeriods(tenantId, storeId).stream().map(Mappers::toPeriod).toList());
  }

  /**
   * Gets an accounting period by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} accounting period not found
   */
  @Operation(summary = "Get an accounting period by id")
  @APIResponse(responseCode = "404", description = "accounting period not found")
  @GET
  @Path("/accounting-periods/{id}")
  public ApiResponse<AccountingPeriodResponse> getPeriod(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toPeriod(service.getPeriod(tenantId, id)));
  }

  /**
   * Closes an accounting period.
   *
   * <p>Locks the period so no further costed movements can post into it.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} accounting period not found
   */
  @Operation(
      summary = "Close an accounting period",
      description = "Locks the period so no further costed movements can post into it.")
  @APIResponse(responseCode = "404", description = "accounting period not found")
  @POST
  @Path("/accounting-periods/{id}/close")
  public ApiResponse<AccountingPeriodResponse> closePeriod(@PathParam("id") UUID id) {
    // Closing locks costed movements: management's, at the period's store. A period of another
    // business is not found, whatever role asks.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getPeriod(tenantId, id).storeId());
    return ApiResponse.ok(Mappers.toPeriod(service.closePeriod(tenantId, id)));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
