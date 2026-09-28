package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.AdjustRequest;
import com.storeql.inventory.dto.Dtos.BatchReceiveRequest;
import com.storeql.inventory.dto.Dtos.BatchReceiveResult;
import com.storeql.inventory.dto.Dtos.BatchResponse;
import com.storeql.inventory.dto.Dtos.LevelResponse;
import com.storeql.inventory.dto.Dtos.LevelSummaryResponse;
import com.storeql.inventory.dto.Dtos.MaterialStatusRequest;
import com.storeql.inventory.dto.Dtos.MovementResponse;
import com.storeql.inventory.dto.Dtos.PurgeMovementsRequest;
import com.storeql.inventory.dto.Dtos.PurgeResult;
import com.storeql.inventory.dto.Dtos.ReceiveRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Admin inventory ops: receive (manual), adjust, levels, batches, movements, purge. This is the
 * core "stock movement" surface — everything else (thresholds, planning, serials, demand history,
 * transfers, move orders, ABC analysis, safety stock, lot genealogy, cycle counting, physical
 * inventory, costing, kanban, ROP/EOQ, reference data, planning config, picking rules) was
 * extracted to its own {@code *Resource} class in this package (F2 audit finding: split by
 * sub-domain path group). All share the same {@code @Path("/admin/inventory")} class-level path;
 * JAX-RS routes by the combined class+method path, so this is safe as long as no two classes
 * declare the same method-level {@code @Path} (they don't — each endpoint moved to exactly one
 * class, verbatim).
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Stock Operations")
public class AdminResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  // ── receive ──────────────────────────────────────────────────────────────

  /**
   * Receives stock manually into a new batch.
   *
   * <p>Creates a new batch/lot for a variant at a store and records the StockReceived event.
   * Supports Idempotency-Key so a retried receipt does not double-count stock.
   *
   * @param idempotencyKey the idempotency key (header parameter)
   * @param req the request body
   * @return batch received ({@code 201})
   */
  @Operation(
      summary = "Receive stock manually into a new batch",
      description =
          "Creates a new batch/lot for a variant at a store and records the StockReceived event."
              + " Supports Idempotency-Key so a retried receipt does not double-count stock.")
  @APIResponse(responseCode = "201", description = "Batch received")
  @POST
  @Path("/receive")
  public Response receive(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      ReceiveRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    LocalDate expiry =
        req.expiryDate() == null || req.expiryDate().isBlank() ? null : parseDate(req.expiryDate());
    UUID zoneId =
        req.zoneId() == null || req.zoneId().isBlank() ? null : uuid(req.zoneId(), "zoneId");
    var batch =
        service.receive(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.qty(),
            req.batchNo(),
            req.costPrice(),
            expiry,
            "MANUAL",
            null,
            zoneId,
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null,
            req.ownership(),
            req.supplierId() == null || req.supplierId().isBlank()
                ? null
                : uuid(req.supplierId(), "supplierId"),
            req.dutyStatus());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toBatch(batch)))
        .build();
  }

  /**
   * Receives multiple items in one call.
   *
   * <p>Best-effort bulk receive: each line is processed independently, and any per-line failure is
   * collected in the result instead of aborting the whole batch.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Receive multiple items in one call",
      description =
          "Best-effort bulk receive: each line is processed independently, and any per-line"
              + " failure is collected in the result instead of aborting the whole batch.")
  @APIResponse(responseCode = "200", description = "Receive multiple items in one call")
  @POST
  @Path("/receive/batch")
  public ApiResponse<BatchReceiveResult> receiveBatch(BatchReceiveRequest req) {
    if (req == null || req.items() == null || req.items().isEmpty()) {
      return ApiResponse.ok(new BatchReceiveResult(0, List.of()));
    }
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    int received = 0;
    var errors = new java.util.ArrayList<String>();
    for (var item : req.items()) {
      try {
        service.receive(
            tenantId,
            uuid(item.storeId(), "storeId"),
            uuid(item.variantId(), "variantId"),
            item.qty(),
            null,
            null,
            null,
            "MANUAL",
            null,
            null,
            null);
        received++;
      } catch (Exception e) {
        errors.add(item.variantId() + ": " + e.getMessage());
      }
    }
    return ApiResponse.ok(new BatchReceiveResult(received, errors));
  }

  // ── adjust ───────────────────────────────────────────────────────────────

  /**
   * Adjusts stock by a signed delta.
   *
   * <p>Manual correction (e.g. stock-take variance, damage write-off) recorded as a StockAdjusted
   * movement. Supports Idempotency-Key.
   *
   * @param idempotencyKey the idempotency key (header parameter)
   * @param req the request body
   */
  @Operation(
      summary = "Adjust stock by a signed delta",
      description =
          "Manual correction (e.g. stock-take variance, damage write-off) recorded as a"
              + " StockAdjusted movement. Supports Idempotency-Key.")
  @APIResponse(responseCode = "200", description = "Adjust stock by a signed delta")
  @POST
  @Path("/adjust")
  public ApiResponse<String> adjust(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      AdjustRequest req) {
    // Writing stock off is the one warehouse action a tenant may keep from a storekeeper (20.10).
    ctx.requirePermission(com.storeql.web.Permissions.STOCK_ADJUST);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    service.adjust(
        tenantId,
        uuid(req.storeId(), "storeId"),
        uuid(req.variantId(), "variantId"),
        req.delta(),
        req.reason(),
        req.reasonCode(),
        ctx.userId(),
        idempotencyKey);
    return ApiResponse.ok("adjusted");
  }

  // ── levels ───────────────────────────────────────────────────────────────

  /**
   * Lists stock levels.
   *
   * <p>Cursor-paginated on-hand/reserved/available quantities per variant, optionally filtered by
   * store.
   *
   * @param store the store (query parameter)
   * @param after the after (query parameter)
   * @param limit the limit (query parameter)
   */
  @Operation(
      summary = "List stock levels",
      description =
          "Cursor-paginated on-hand/reserved/available quantities per variant, optionally"
              + " filtered by store.")
  @APIResponse(responseCode = "200", description = "List stock levels")
  @GET
  @Path("/levels")
  public ApiResponse<List<LevelResponse>> levels(
      @QueryParam("store") String store,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    int clamped = Cursor.clampLimit(limit);
    var page = service.levelsPage(tenantId, storeId, after, clamped);
    List<LevelResponse> items = page.levels().stream().map(Mappers::toLevel).toList();
    return ApiResponse.ok(items, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /** Aggregate KPI counts (total SKUs + low-stock) without paging the full levels list. */
  @Operation(
      summary = "Get stock level KPI summary",
      description = "Aggregate SKU count and low-stock count without paging the full levels list.")
  @APIResponse(responseCode = "200", description = "Get stock level KPI summary")
  @GET
  @Path("/levels/summary")
  public ApiResponse<LevelSummaryResponse> levelsSummary(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    return ApiResponse.ok(
        Mappers.toLevelSummary(service.levelsSummary(tenantId, storeId)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── batches ──────────────────────────────────────────────────────────────

  /**
   * Lists batches.
   *
   * <p>Filterable by store, variant, and material status.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @param materialStatus the material status (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(
      summary = "List batches",
      description = "Filterable by store, variant, and material status.")
  @APIResponse(responseCode = "200", description = "List batches")
  @GET
  @Path("/batches")
  public ApiResponse<List<BatchResponse>> listBatches(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("material_status") String materialStatus,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    String ms = materialStatus == null || materialStatus.isBlank() ? null : materialStatus;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listBatches(tenantId, storeId, variantId, ms, limit).stream()
            .map(Mappers::toBatch)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Gets a batch by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such batch
   */
  @Operation(summary = "Get a batch by id")
  @APIResponse(responseCode = "404", description = "No such batch")
  @GET
  @Path("/batches/{id}")
  public ApiResponse<BatchResponse> getBatch(@PathParam("id") UUID id) {
    var batch = service.getBatch(ctx.requireTenantId(), id);
    ctx.requireStoreAccess(batch.storeId()); // SJ-D74: a batch is read at the store that holds it
    return ApiResponse.ok(Mappers.toBatch(batch));
  }

  /**
   * Updates a batch's material status.
   *
   * <p>Sets a hold/release-style material status (e.g. QUARANTINE) with a reason.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} no such batch
   */
  @Operation(
      summary = "Update a batch's material status",
      description = "Sets a hold/release-style material status (e.g. QUARANTINE) with a reason.")
  @APIResponse(responseCode = "404", description = "No such batch")
  @PUT
  @Path("/batches/{id}/material-status")
  public ApiResponse<BatchResponse> updateMaterialStatus(
      @PathParam("id") UUID id, MaterialStatusRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getBatch(tenantId, id).storeId());
    var batch = service.updateMaterialStatus(tenantId, id, req.materialStatus(), req.reason());
    return ApiResponse.ok(Mappers.toBatch(batch));
  }

  // ── movements ────────────────────────────────────────────────────────────

  /**
   * Lists stock movements.
   *
   * <p>Append-only movement ledger, filterable by store, variant, and movement type.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @param type the type (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(
      summary = "List stock movements",
      description = "Append-only movement ledger, filterable by store, variant, and movement type.")
  @APIResponse(responseCode = "200", description = "List stock movements")
  @GET
  @Path("/movements")
  public ApiResponse<List<MovementResponse>> listMovements(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("type") String type,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listMovements(tenantId, storeId, variantId, type, limit).stream()
            .map(Mappers::toMovement)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── purge movements (Gap #30) ───────────────────────────────────────────

  /**
   * Purges old stock movements.
   *
   * <p>Permanently deletes movement history older than the given instant (Gap #30 — used for data
   * retention housekeeping, not exposed to regular admin users).
   *
   * @param req the request body
   */
  @Operation(
      summary = "Purge old stock movements",
      description =
          "Permanently deletes movement history older than the given instant (Gap #30 — used for"
              + " data retention housekeeping, not exposed to regular admin users).")
  @APIResponse(responseCode = "200", description = "Purge old stock movements")
  @POST
  @Path("/movements/purge")
  public ApiResponse<PurgeResult> purgeMovements(PurgeMovementsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    java.time.Instant before = com.storeql.web.Parsing.instant(req.before(), "before");
    int purged = service.purgeMovementsBefore(tenantId, before);
    return ApiResponse.ok(new PurgeResult(purged));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }

  private static LocalDate parseDate(String s) {
    return com.storeql.web.Parsing.date(s, "expiryDate");
  }
}
