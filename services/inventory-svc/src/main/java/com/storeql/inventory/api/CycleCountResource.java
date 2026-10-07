package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.CreateCycleCountRequest;
import com.storeql.inventory.dto.Dtos.CycleCountAdjustResult;
import com.storeql.inventory.dto.Dtos.CycleCountApproveResult;
import com.storeql.inventory.dto.Dtos.CycleCountHeaderResponse;
import com.storeql.inventory.dto.Dtos.CycleCountLineResponse;
import com.storeql.inventory.dto.Dtos.EnterCountRequest;
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

/**
 * Cycle counting (Gap #10): headers, lines, approve/adjust. Extracted from AdminResource.
 *
 * <p>A count ends in stock adjustments, so opening, approving and posting one is {@code
 * stock.adjust}, as an adjustment by hand is; entering a counted quantity is any staff's work at
 * the store. The store is named once, on the header, so every step by id holds the caller to that
 * store here (SJ-D74), and a list naming no store is scoped to the caller's.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cycle Counts")
public class CycleCountResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Creates a cycle count.
   *
   * <p>Generates a count header with one line per variant in the given ABC classes for the store,
   * pre-populated with system quantity.
   *
   * @param req the request body
   * @return cycle count created ({@code 201})
   */
  @Operation(
      summary = "Create a cycle count",
      description =
          "Generates a count header with one line per variant in the given ABC classes for the"
              + " store, pre-populated with system quantity.")
  @APIResponse(responseCode = "201", description = "Cycle count created")
  @POST
  @Path("/cycle-counts")
  public Response createCycleCount(CreateCycleCountRequest req) {
    ctx.requirePermission(Permissions.STOCK_ADJUST);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    var result =
        service.createCycleCount(
            tenantId,
            storeId,
            req.name(),
            req.abcClasses() != null ? req.abcClasses() : "A,B,C",
            req.tolerancePct() != null ? req.tolerancePct() : java.math.BigDecimal.valueOf(5));
    var resp = Mappers.toCycleCountHeader(result.header(), result.lines());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(resp, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Lists cycle counts.
   *
   * <p>Filterable by store and status.
   *
   * @param storeId the store id (query parameter)
   * @param status the status (query parameter)
   * @param limit the limit (query parameter)
   */
  @Operation(summary = "List cycle counts", description = "Filterable by store and status.")
  @APIResponse(responseCode = "200", description = "List cycle counts")
  @GET
  @Path("/cycle-counts")
  public ApiResponse<List<CycleCountHeaderResponse>> listCycleCounts(
      @QueryParam("storeId") UUID storeId,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    var headers = service.listCycleCounts(tenantId, ctx.scopeStore(storeId), status, lim);
    var items =
        headers.stream().map(cwl -> Mappers.toCycleCountHeader(cwl.header(), cwl.lines())).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Gets a cycle count by id, with its lines.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such cycle count
   */
  @Operation(summary = "Get a cycle count by id, with its lines")
  @APIResponse(responseCode = "404", description = "No such cycle count")
  @GET
  @Path("/cycle-counts/{id}")
  public ApiResponse<CycleCountHeaderResponse> getCycleCount(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var cwl = service.getCycleCount(tenantId, id);
    ctx.requireStoreAccess(cwl.header().storeId());
    return ApiResponse.ok(
        Mappers.toCycleCountHeader(cwl.header(), cwl.lines()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Enters a counted quantity for a cycle-count line.
   *
   * <p>Records the counted qty and computes variance against system qty.
   *
   * @param headerId the header id (path parameter)
   * @param lineId the line id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} countedQty must be >= 0; {@code 404} no such
   *     cycle count or count line
   */
  @Operation(
      summary = "Enter a counted quantity for a cycle-count line",
      description = "Records the counted qty and computes variance against system qty.")
  @APIResponse(responseCode = "400", description = "countedQty must be >= 0")
  @APIResponse(responseCode = "404", description = "No such cycle count or count line")
  @POST
  @Path("/cycle-counts/{id}/lines/{lineId}/count")
  public ApiResponse<CycleCountLineResponse> enterCount(
      @PathParam("id") UUID headerId, @PathParam("lineId") UUID lineId, EnterCountRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    requireStoreOf(tenantId, headerId);
    var line = service.enterCount(tenantId, headerId, lineId, req.countedQty());
    return ApiResponse.ok(Mappers.toCycleCountLine(line), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Approves a cycle count within tolerance.
   *
   * <p>Auto-approves lines whose variance percentage is within the count's tolerance and flags the
   * rest for manual review.
   *
   * @param headerId the header id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such cycle count
   */
  @Operation(
      summary = "Approve a cycle count within tolerance",
      description =
          "Auto-approves lines whose variance percentage is within the count's tolerance and flags"
              + " the rest for manual review.")
  @APIResponse(responseCode = "404", description = "No such cycle count")
  @POST
  @Path("/cycle-counts/{id}/approve")
  public ApiResponse<CycleCountApproveResult> approveCycleCount(@PathParam("id") UUID headerId) {
    ctx.requirePermission(Permissions.STOCK_ADJUST);
    UUID tenantId = ctx.requireTenantId();
    requireStoreOf(tenantId, headerId);
    var result = service.approveWithTolerance(tenantId, headerId);
    return ApiResponse.ok(
        new CycleCountApproveResult(result.autoApproved(), result.flagged()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Posts stock adjustments for an approved cycle count.
   *
   * <p>Writes a StockAdjusted movement per approved line with a non-zero variance.
   *
   * @param headerId the header id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such cycle count
   */
  @Operation(
      summary = "Post stock adjustments for an approved cycle count",
      description = "Writes a StockAdjusted movement per approved line with a non-zero variance.")
  @APIResponse(responseCode = "404", description = "No such cycle count")
  @POST
  @Path("/cycle-counts/{id}/adjust")
  public ApiResponse<CycleCountAdjustResult> adjustCycleCount(@PathParam("id") UUID headerId) {
    ctx.requirePermission(Permissions.STOCK_ADJUST);
    UUID tenantId = ctx.requireTenantId();
    requireStoreOf(tenantId, headerId);
    int adjusted = service.adjustCycleCount(tenantId, headerId, ctx.userId());
    return ApiResponse.ok(
        new CycleCountAdjustResult(adjusted), ApiResponse.Meta.of(ctx.requestId()));
  }

  /** Holds the caller to the count's store; a count of another business is 404, as before. */
  private void requireStoreOf(UUID tenantId, UUID headerId) {
    ctx.requireStoreAccess(service.getCycleCount(tenantId, headerId).header().storeId());
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
