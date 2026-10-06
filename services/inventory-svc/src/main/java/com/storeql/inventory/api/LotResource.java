package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.dto.Dtos.BatchResponse;
import com.storeql.inventory.dto.Dtos.CreateLotLinkRequest;
import com.storeql.inventory.dto.Dtos.ExpiringBatchResponse;
import com.storeql.inventory.dto.Dtos.LotActionResponse;
import com.storeql.inventory.dto.Dtos.LotGenealogyLinkResponse;
import com.storeql.inventory.dto.Dtos.LotGenealogyTreeResponse;
import com.storeql.inventory.dto.Dtos.LotMergeRequest;
import com.storeql.inventory.dto.Dtos.LotSplitRequest;
import com.storeql.inventory.dto.Dtos.UpdateGradeRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiResponse;
import com.storeql.web.HttpHeaders;
import com.storeql.web.IdempotencyKeys;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Lot/batch traceability: genealogy (Gap #11), split/merge (Gap #23), expiry alerts (Gap #24),
 * grade control (Gap #25). Extracted from AdminResource — bundled as one cohesive "lot-level batch
 * operations" theme; none of these share state at the controller layer, so the grouping here is
 * organizational. A write names a batch, and a caller held to stores is held to that batch's store.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Lot & Batch Traceability")
public class LotResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Links a parent and child batch for genealogy.
   *
   * <p>Records a lot-genealogy relation (e.g. split/merge/repack) between two batches.
   *
   * @param req the request body
   * @return genealogy link created ({@code 201})
   */
  @Operation(
      summary = "Link a parent and child batch for genealogy",
      description =
          "Records a lot-genealogy relation (e.g. split/merge/repack) between two batches.")
  @APIResponse(responseCode = "201", description = "Genealogy link created")
  @POST
  @Path("/lot-genealogy")
  public Response createLotLink(CreateLotLinkRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var link =
        service.createLotLink(
            tenantId,
            uuid(req.parentBatchId(), "parentBatchId"),
            uuid(req.childBatchId(), "childBatchId"),
            req.qty(),
            req.relationType(),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toLotLink(link), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Gets a batch's ancestor genealogy links.
   *
   * @param batchId the batch id (path parameter)
   */
  @Operation(summary = "Get a batch's ancestor genealogy links")
  @APIResponse(responseCode = "200", description = "Get a batch's ancestor genealogy links")
  @GET
  @Path("/lot-genealogy/batch/{batchId}/ancestors")
  public ApiResponse<LotGenealogyTreeResponse> getAncestors(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var ancestors =
        service.findAncestors(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(
        new LotGenealogyTreeResponse(batchId.toString(), ancestors, List.of()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Gets a batch's descendant genealogy links.
   *
   * @param batchId the batch id (path parameter)
   */
  @Operation(summary = "Get a batch's descendant genealogy links")
  @APIResponse(responseCode = "200", description = "Get a batch's descendant genealogy links")
  @GET
  @Path("/lot-genealogy/batch/{batchId}/descendants")
  public ApiResponse<LotGenealogyTreeResponse> getDescendants(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var descendants =
        service.findDescendants(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(
        new LotGenealogyTreeResponse(batchId.toString(), List.of(), descendants),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Lists a batch's direct genealogy links (one hop, parent and child).
   *
   * @param batchId the batch id (path parameter)
   */
  @Operation(summary = "List a batch's direct genealogy links (one hop, parent and child)")
  @APIResponse(
      responseCode = "200",
      description = "List a batch's direct genealogy links (one hop, parent and child)")
  @GET
  @Path("/lot-genealogy/batch/{batchId}/links")
  public ApiResponse<List<LotGenealogyLinkResponse>> getDirectLinks(
      @PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var links =
        service.findDirectLinks(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(links, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Splits a batch into a new child batch.
   *
   * <p>Moves qty out of the source batch into a newly created batch of the same store and variant,
   * which keeps the source's lot number unless one is named and is linked to it in the lot
   * genealogy. A recall of the source's lot reaches the child. Held, bonded and consignment stock
   * splits into stock in the same condition. Sent again with the same Idempotency-Key, a split
   * answers the first and moves nothing.
   *
   * @param idempotencyKey the idempotency key (header parameter), a UUIDv7
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} malformed Idempotency-Key; {@code 403} the
   *     batch is at a store the caller is not held to; {@code 404} source batch not found; {@code
   *     409} the key made a different action; {@code 422} split qty exceeds remaining qty on source
   *     batch
   */
  @Operation(
      summary = "Split a batch into a new child batch",
      description =
          "Moves qty out of the source batch into a newly created batch of the same store and"
              + " variant, which keeps the source's lot number unless one is named and is linked"
              + " to it in the lot genealogy. A recall of the source's lot reaches the child."
              + " Held, bonded and consignment stock splits into stock in the same condition."
              + " Sent again with the same Idempotency-Key, a split answers the first and moves"
              + " nothing.")
  @APIResponse(responseCode = "400", description = "Malformed Idempotency-Key")
  @APIResponse(
      responseCode = "403",
      description = "The batch is at a store the caller is not held to")
  @APIResponse(responseCode = "404", description = "Source batch not found")
  @APIResponse(responseCode = "409", description = "The Idempotency-Key made a different action")
  @APIResponse(
      responseCode = "422",
      description = "Split qty exceeds remaining qty on source batch")
  @POST
  @Path("/lots/split")
  public ApiResponse<LotActionResponse> splitLot(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey, LotSplitRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID sourceBatchId = Ids.parse(req.sourceBatchId());
    ctx.requireStoreAccess(service.getBatch(tenantId, sourceBatchId).storeId());
    var result =
        service.splitLot(
            tenantId,
            sourceBatchId,
            req.qty(),
            req.batchNo(),
            req.notes(),
            ctx.userId(),
            IdempotencyKeys.effective(idempotencyKey, null));
    return ApiResponse.ok(Mappers.toLotAction(result.action()));
  }

  /**
   * Merges qty from a source batch into a target batch.
   *
   * <p>Moves qty from the source batch into the target batch, which must be at the same store, of
   * the same variant, in the same material status and grade and have the same ownership and duty
   * status. The two are linked in the lot genealogy, so a recall of either's lot reaches the merged
   * batch. The target's unit cost becomes the average of the two weighted by quantity and its
   * use-by date the earlier of the two. Sent again with the same Idempotency-Key, a merge answers
   * the first and moves nothing.
   *
   * @param idempotencyKey the idempotency key (header parameter), a UUIDv7
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} malformed Idempotency-Key; {@code 403} a batch
   *     is at a store the caller is not held to; {@code 404} source or target batch not found;
   *     {@code 409} the key made a different action; {@code 422} merge qty exceeds remaining qty on
   *     source batch, or the two batches cannot be merged
   */
  @Operation(
      summary = "Merge qty from a source batch into a target batch",
      description =
          "Moves qty from the source batch into the target batch, which must be at the same store,"
              + " of the same variant, in the same material status and grade and have the same"
              + " ownership and duty status. The two are linked in the lot genealogy, so a recall"
              + " of either's lot reaches the merged batch. The target's unit cost becomes the"
              + " average of the two weighted by quantity and its use-by date the earlier of the"
              + " two. Sent again with the same Idempotency-Key, a merge answers the first and"
              + " moves nothing.")
  @APIResponse(responseCode = "400", description = "Malformed Idempotency-Key")
  @APIResponse(
      responseCode = "403",
      description = "A batch is at a store the caller is not held to")
  @APIResponse(responseCode = "404", description = "Source or target batch not found")
  @APIResponse(responseCode = "409", description = "The Idempotency-Key made a different action")
  @APIResponse(
      responseCode = "422",
      description =
          "Merge qty exceeds remaining qty on source batch, or the two batches cannot be merged"
              + " (INVENTORY_LOT_MERGE_*)")
  @POST
  @Path("/lots/merge")
  public ApiResponse<LotActionResponse> mergeLot(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey, LotMergeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID sourceBatchId = Ids.parse(req.sourceBatchId());
    UUID targetBatchId = Ids.parse(req.targetBatchId());
    ctx.requireStoreAccess(service.getBatch(tenantId, sourceBatchId).storeId());
    ctx.requireStoreAccess(service.getBatch(tenantId, targetBatchId).storeId());
    var result =
        service.mergeLot(
            tenantId,
            sourceBatchId,
            targetBatchId,
            req.qty(),
            req.notes(),
            ctx.userId(),
            IdempotencyKeys.effective(idempotencyKey, null));
    return ApiResponse.ok(Mappers.toLotAction(result.action()));
  }

  /**
   * Lists split/merge actions recorded against a batch.
   *
   * @param batchId the batch id (path parameter)
   */
  @Operation(summary = "List split/merge actions recorded against a batch")
  @APIResponse(
      responseCode = "200",
      description = "List split/merge actions recorded against a batch")
  @GET
  @Path("/lots/{batchId}/actions")
  public ApiResponse<List<LotActionResponse>> listLotActions(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listLotActions(tenantId, batchId).stream().map(Mappers::toLotAction).toList());
  }

  /**
   * Lists batches nearing expiry.
   *
   * <p>Batches for a store expiring within the given number of days (default 30).
   *
   * @param store the store (query parameter)
   * @param withinDays the within days (query parameter)
   * @throws com.storeql.web.ApiException {@code 400} withinDays must be 1-3650
   */
  @Operation(
      summary = "List batches nearing expiry",
      description = "Batches for a store expiring within the given number of days (default 30).")
  @APIResponse(responseCode = "400", description = "withinDays must be 1-3650")
  @GET
  @Path("/batches/expiring")
  public ApiResponse<List<ExpiringBatchResponse>> listExpiringBatches(
      @QueryParam("store") String store, @QueryParam("withinDays") Integer withinDays) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    int days = withinDays == null ? 30 : withinDays;
    return ApiResponse.ok(
        service.listExpiringBatches(tenantId, storeId, days).stream()
            .map(Mappers::toExpiringBatch)
            .toList());
  }

  /**
   * Updates a batch's quality grade.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 400} grade must not be blank; {@code 403} the batch
   *     is at a store the caller is not held to; {@code 404} no such batch
   */
  @Operation(summary = "Update a batch's quality grade")
  @APIResponse(responseCode = "400", description = "grade must not be blank")
  @APIResponse(
      responseCode = "403",
      description = "The batch is at a store the caller is not held to")
  @APIResponse(responseCode = "404", description = "No such batch")
  @PUT
  @Path("/batches/{id}/grade")
  public ApiResponse<BatchResponse> updateBatchGrade(
      @PathParam("id") UUID id, UpdateGradeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(service.getBatch(tenantId, id).storeId());
    return ApiResponse.ok(Mappers.toBatch(service.updateBatchGrade(tenantId, id, req.grade())));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
