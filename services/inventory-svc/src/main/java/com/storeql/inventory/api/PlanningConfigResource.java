package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.LotUomConversionResponse;
import com.storeql.inventory.dto.Dtos.ParLevelResponse;
import com.storeql.inventory.dto.Dtos.UpsertLotUomConversionRequest;
import com.storeql.inventory.dto.Dtos.UpsertParLevelRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
 * Lot UOM conversions (Gap #26) and PAR levels (Gap #27) — matches {@code
 * PlanningConfigRepository}'s grouping at the data layer. Extracted from AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Planning Configuration")
public class PlanningConfigResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Upserts a lot's unit-of-measure conversion.
   *
   * <p>Defines the factor to convert between two UOMs for a specific batch.
   *
   * @param batchId the batch id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} uOM conversion factor must be positive
   */
  @Operation(
      summary = "Upsert a lot's unit-of-measure conversion",
      description = "Defines the factor to convert between two UOMs for a specific batch.")
  @APIResponse(responseCode = "400", description = "UOM conversion factor must be positive")
  @PUT
  @Path("/lots/{batchId}/uom-conversions")
  public ApiResponse<LotUomConversionResponse> upsertLotUomConversion(
      @PathParam("batchId") UUID batchId, UpsertLotUomConversionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var c =
        service.upsertLotUomConversion(
            tenantId, batchId, req.fromUom(), req.toUom(), req.factor(), req.notes());
    return ApiResponse.ok(Mappers.toLotUomConversion(c));
  }

  /**
   * Lists a batch's UOM conversions.
   *
   * @param batchId the batch id (path parameter)
   */
  @Operation(summary = "List a batch's UOM conversions")
  @APIResponse(responseCode = "200", description = "List a batch's UOM conversions")
  @GET
  @Path("/lots/{batchId}/uom-conversions")
  public ApiResponse<List<LotUomConversionResponse>> listLotUomConversions(
      @PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listLotUomConversions(tenantId, batchId).stream()
            .map(Mappers::toLotUomConversion)
            .toList());
  }

  /**
   * Upserts a PAR level.
   *
   * <p>Sets the target periodic-automatic-replenishment quantity for a variant at a store.
   *
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} parQty must be positive
   */
  @Operation(
      summary = "Upsert a PAR level",
      description =
          "Sets the target periodic-automatic-replenishment quantity for a variant at a"
              + " store.")
  @APIResponse(responseCode = "400", description = "parQty must be positive")
  @PUT
  @Path("/par-levels")
  public ApiResponse<ParLevelResponse> upsertParLevel(UpsertParLevelRequest req) {
    // A par level is the target the store replenishes to: management's, at a store the caller
    // keeps.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(uuid(req.storeId(), "storeId"));
    var p =
        service.upsertParLevel(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.parQty(),
            req.uom(),
            req.reviewCycle());
    return ApiResponse.ok(Mappers.toParLevel(p));
  }

  /**
   * Lists PAR levels for a store.
   *
   * @param store the store (query parameter)
   */
  @Operation(summary = "List PAR levels for a store")
  @APIResponse(responseCode = "200", description = "List PAR levels for a store")
  @GET
  @Path("/par-levels")
  public ApiResponse<List<ParLevelResponse>> listParLevels(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listParLevels(tenantId, storeId).stream().map(Mappers::toParLevel).toList());
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
