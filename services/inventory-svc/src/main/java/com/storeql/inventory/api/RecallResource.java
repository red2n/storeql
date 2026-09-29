package com.storeql.inventory.api;

import com.storeql.inventory.domain.Recall.ActiveItem;
import com.storeql.inventory.domain.Recall.Disposition;
import com.storeql.inventory.domain.Recall.Status;
import com.storeql.inventory.dto.RecallDtos.ActiveRecallItemResponse;
import com.storeql.inventory.dto.RecallDtos.ReasonRequest;
import com.storeql.inventory.dto.RecallDtos.RecallResponse;
import com.storeql.inventory.dto.RecallDtos.RecallSummaryResponse;
import com.storeql.inventory.dto.RecallDtos.StoreActionRequest;
import com.storeql.inventory.mapper.RecallMappers;
import com.storeql.inventory.service.RecallService;
import com.storeql.inventory.service.RecallService.RecordStoreAction;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Recalls as store staff work them: the list the till checks items against, what each recall holds,
 * and recording what a store found and did.
 *
 * <p>Under {@code /admin/inventory/} so any staff role reaches it by path: a cashier's till reads
 * the active list, and a storekeeper pulls the stock. Opening, closing and cancelling a recall is
 * management work in {@link RecallSetupResource}, under a path the filter gates to management.
 */
@Path("/admin/inventory/recalls")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Recalls")
public class RecallResource {

  @Inject RecallService service;
  @Inject TenantContext ctx;

  /**
   * Lists recalls.
   *
   * <p>Newest first, cursor-paginated, optionally by status.
   *
   * @param status the status (query parameter)
   * @param after the after (query parameter)
   * @param limit the limit (query parameter)
   */
  @Operation(
      summary = "List recalls",
      description = "Newest first, cursor-paginated, optionally by status.")
  @APIResponse(responseCode = "200", description = "List recalls")
  @GET
  public ApiResponse<List<RecallSummaryResponse>> list(
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = service.list(ctx.requireTenantId(), parseStatus(status), after, limit);
    return ApiResponse.ok(
        page.items().stream().map(RecallMappers::toSummary).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Thes items under an open recall.
   *
   * <p>Every scope line of every open recall. The till keeps this list and checks each item against
   * it, so a scan never waits on the network.
   *
   * <p>With {@code endedSince}, also every scope line of a recall closed or cancelled at or after
   * that moment, each with {@code endedAt} and {@code endedAs}. order-svc asks this for a till sale
   * replayed from an offline queue, which is judged against every recall open when it was rung up —
   * one that has ended since included. Without it the answer is the till's list, unchanged.
   *
   * @param endedSince an ISO instant with its offset, or absent for the open recalls only
   * @throws ApiException {@code 400 RECALL_ENDED_SINCE_INVALID} for a value that is not an instant
   */
  @Operation(
      summary = "The items under an open recall",
      description =
          "Every scope line of every open recall. The till keeps this list and checks each item"
              + " against it, so a scan never waits on the network. With ?endedSince=<ISO"
              + " instant>, also the lines of every recall closed or cancelled at or after it,"
              + " each with endedAt and endedAs (CLOSED or CANCELLED): what order-svc judges a"
              + " till sale replayed from an offline queue against.")
  @APIResponse(responseCode = "200", description = "The items under an open recall")
  @APIResponse(
      responseCode = "400",
      description = "RECALL_ENDED_SINCE_INVALID: endedSince is not an instant")
  @GET
  @Path("/active")
  public ApiResponse<List<ActiveRecallItemResponse>> active(
      @QueryParam("endedSince") String endedSince) {
    UUID tenantId = ctx.requireTenantId();
    List<ActiveItem> lines =
        endedSince == null || endedSince.isBlank()
            ? service.active(tenantId)
            : service.openOrEndedSince(tenantId, instantOf(endedSince));
    return ApiResponse.ok(
        lines.stream().map(RecallMappers::toActiveItem).toList(),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  private static Instant instantOf(String text) {
    try {
      return OffsetDateTime.parse(text.strip()).toInstant();
    } catch (DateTimeException e) {
      throw new ApiException(
          400,
          "RECALL_ENDED_SINCE_INVALID",
          "endedSince must be an instant with its offset, e.g. 2026-09-29T10:15:30Z",
          List.of(),
          e);
    }
  }

  /**
   * Gets a recall.
   *
   * <p>Its scope, every batch it holds, what each store recorded, and progress.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such recall
   */
  @Operation(
      summary = "Get a recall",
      description = "Its scope, every batch it holds, what each store recorded, and progress.")
  @APIResponse(responseCode = "404", description = "No such recall")
  @GET
  @Path("/{id}")
  public ApiResponse<RecallResponse> get(@PathParam("id") String id) {
    return ApiResponse.ok(
        RecallMappers.toRecall(service.get(ctx.requireTenantId(), Parsing.uuid(id, "id"))),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Records what a store found and did.
   *
   * <p>RETURNED_TO_SUPPLIER or DESTROYED takes the store's held stock off the books with a movement
   * attributed to the caller; HELD_FOR_COLLECTION does not. A store may record more than once.
   *
   * @param id the id (path parameter)
   * @param storeId the store id (path parameter)
   * @param req the request body
   * @return recorded ({@code 201})
   * @throws com.storeql.web.ApiException {@code 403} not assigned to that store; {@code 409} the
   *     recall is not open
   */
  @Operation(
      summary = "Record what a store found and did",
      description =
          "RETURNED_TO_SUPPLIER or DESTROYED takes the store's held stock off the books with a"
              + " movement attributed to the caller; HELD_FOR_COLLECTION does not. A store may"
              + " record more than once.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(responseCode = "403", description = "Not assigned to that store")
  @APIResponse(responseCode = "409", description = "The recall is not open")
  @POST
  @Path("/{id}/stores/{storeId}/actions")
  public Response recordStoreAction(
      @PathParam("id") String id, @PathParam("storeId") String storeId, StoreActionRequest req) {
    Validations.validate(req);
    var action =
        service.recordStoreAction(
            new RecordStoreAction(
                ctx.requireTenantId(),
                ctx.requireUserId(),
                Parsing.uuid(id, "id"),
                Parsing.uuid(storeId, "storeId"),
                req.qtyFound(),
                Disposition.valueOf(req.disposition()),
                req.noticeDisplayed(),
                req.notes()),
            ctx::requireStoreAccess);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                RecallMappers.toStoreAction(action), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Releases a batch found not to be affected.
   *
   * <p>Only a batch held because its lot or date was unknown. Its status is restored once no open
   * recall holds it.
   *
   * @param id the id (path parameter)
   * @param batchId the batch id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} the recall does not hold that batch; {@code
   *     409} the batch is certainly in scope, or released
   */
  @Operation(
      summary = "Release a batch found not to be affected",
      description =
          "Only a batch held because its lot or date was unknown. Its status is restored once no"
              + " open recall holds it.")
  @APIResponse(responseCode = "404", description = "The recall does not hold that batch")
  @APIResponse(responseCode = "409", description = "The batch is certainly in scope, or released")
  @POST
  @Path("/{id}/batches/{batchId}/release")
  public ApiResponse<RecallResponse> release(
      @PathParam("id") String id, @PathParam("batchId") String batchId, ReasonRequest req) {
    Validations.validate(req);
    var detail =
        service.release(
            ctx.requireTenantId(),
            ctx.requireUserId(),
            Parsing.uuid(id, "id"),
            Parsing.uuid(batchId, "batchId"),
            req.reason(),
            ctx::requireStoreAccess);
    return ApiResponse.ok(RecallMappers.toRecall(detail), ApiResponse.Meta.of(ctx.requestId()));
  }

  private static Status parseStatus(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Status.valueOf(value.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "RECALL_STATUS_INVALID", "status must be OPEN, CLOSED or CANCELLED", List.of(), e);
    }
  }
}
