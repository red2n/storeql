package com.storeql.order.api;

import com.storeql.order.domain.RecallNotice.Remedy;
import com.storeql.order.domain.RecallNotice.Resolution;
import com.storeql.order.domain.RecallNotice.Status;
import com.storeql.order.dto.RecallNoticeDtos.NoticeResponse;
import com.storeql.order.dto.RecallNoticeDtos.ProgressResponse;
import com.storeql.order.dto.RecallNoticeDtos.RemedyRequest;
import com.storeql.order.dto.RecallNoticeDtos.ResolveRequest;
import com.storeql.order.mapper.RecallNoticeMappers;
import com.storeql.order.service.RecallNoticeService;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A recall's notices to buyers (GPSR arts.35–37). The shopper reads their own and chooses a remedy;
 * staff read a recall's, choose for a buyer at the counter, and settle them.
 *
 * <p>Under {@code /orders/recall-notices}: {@code /mine} and {@code /{id}/remedy} are the two
 * shapes the authorisation filter opens to a shopper, each keyed on the token's login here; the
 * rest is default-deny and needs a staff role, which the resource also insists on.
 */
@Path("/orders/recall-notices")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Recall Notices")
public class RecallNoticeResource {

  private static final String[] STAFF = {
    "PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER"
  };

  @Inject RecallNoticeService service;
  @Inject TenantContext ctx;

  /**
   * The signed-in shopper's own recall notices, newest first.
   *
   * @throws com.storeql.web.ApiException {@code NO_CUSTOMER} (401) when the token carries no login
   */
  @Operation(
      summary = "The signed-in shopper's own recall notices",
      description = "Notices issued to the orders this login placed at this shop, newest first.")
  @APIResponse(responseCode = "200", description = "The shopper's notices")
  @APIResponse(responseCode = "401", description = "No customer identity on the token")
  @GET
  @Path("/mine")
  public ApiResponse<List<NoticeResponse>> mine() {
    UUID tenantId = ctx.requireTenantId();
    UUID loginId = requireLogin();
    return ApiResponse.ok(
        service.mine(tenantId, loginId).stream().map(RecallNoticeMappers::toNotice).toList(),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Chooses the remedy: the shopper on their own notice, or staff for a buyer at the counter.
   *
   * @param id the notice
   * @param req the remedy
   * @throws com.storeql.web.ApiException {@code 404} no such notice, or not this shopper's; {@code
   *     409} already chosen, not offered, or settled
   */
  @Operation(
      summary = "Choose the remedy",
      description =
          "The buyer chooses among what the recall offered, once. A shopper acts on a notice"
              + " issued to their own login; staff act on any notice of the business.")
  @APIResponse(responseCode = "200", description = "The notice with the remedy recorded")
  @APIResponse(responseCode = "404", description = "No such notice, or not this shopper's")
  @APIResponse(responseCode = "409", description = "Already chosen, not offered, or settled")
  @POST
  @Path("/{id}/remedy")
  public ApiResponse<NoticeResponse> choose(@PathParam("id") String id, RemedyRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID noticeId = Parsing.uuid(id, "id");
    Remedy remedy = Remedy.valueOf(req.remedy());
    var detail =
        isStaff()
            ? service.chooseAsStaff(tenantId, noticeId, ctx.requireUserId(), remedy)
            : service.chooseAsShopper(tenantId, noticeId, requireLogin(), remedy);
    return ApiResponse.ok(
        RecallNoticeMappers.toNotice(detail), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * A recall's notices, or one order's, newest first, cursor-paginated. Staff only.
   *
   * @param recallId the recall (query parameter); required unless {@code orderId} is given
   * @param orderId the order (query parameter): its notices, so a return dialog can offer to settle
   *     one; only notices at stores the caller keeps are returned
   * @param status restrict to one status (query parameter)
   */
  @Operation(
      summary = "List recall notices, by recall or by order",
      description =
          "Every order a recall reached and how far each buyer has got (recallId), or the notices"
              + " issued against one order (orderId), across recalls; either or both. Asked by"
              + " order, only notices at the caller's stores are returned: another business's"
              + " order, or one at a store the caller is not held to, gives an empty list. Staff"
              + " only.")
  @APIResponse(responseCode = "200", description = "The notices")
  @APIResponse(responseCode = "400", description = "Neither recallId nor orderId was given")
  @APIResponse(responseCode = "403", description = "Not a member of staff")
  @GET
  public ApiResponse<List<NoticeResponse>> list(
      @QueryParam("recallId") String recallId,
      @QueryParam("orderId") String orderId,
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole(STAFF);
    var page =
        service.list(
            ctx.requireTenantId(),
            recallId == null || recallId.isBlank() ? null : Parsing.uuid(recallId, "recallId"),
            orderId == null || orderId.isBlank() ? null : Parsing.uuid(orderId, "orderId"),
            ctx.storeIds(),
            parseStatus(status),
            after,
            limit);
    return ApiResponse.ok(
        page.items().stream().map(RecallNoticeMappers::toNotice).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * How a recall's buyers stand. Staff only.
   *
   * @param recallId the recall (query parameter, required)
   */
  @Operation(
      summary = "How a recall's buyers stand",
      description =
          "How many orders the recall reached, how many named a buyer, and how many have chosen"
              + " a remedy or been settled. Staff only.")
  @APIResponse(responseCode = "200", description = "The figures")
  @GET
  @Path("/progress")
  public ApiResponse<ProgressResponse> progress(@QueryParam("recallId") String recallId) {
    ctx.requireAnyRole(STAFF);
    UUID recall = Parsing.uuid(recallId, "recallId");
    return ApiResponse.ok(
        RecallNoticeMappers.toProgress(recall, service.progress(ctx.requireTenantId(), recall)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Settles a notice: replacement handed over, repair done, or nothing wanted. A refund is recorded
   * as a return of the order naming the notice. Staff only.
   *
   * @param id the notice
   * @param req the resolution and notes
   * @throws com.storeql.web.ApiException {@code 400} REFUNDED here; {@code 403} staff held to other
   *     stores than the notice's ({@code STORE_ACCESS_DENIED}); {@code 404} no such notice; {@code
   *     409} already settled
   */
  @Operation(
      summary = "Settle a notice",
      description =
          "A replacement handed over, a repair done, or a buyer who wanted nothing. A refund is"
              + " recorded through POST /orders/{id}/returns naming the notice. Staff only.")
  @APIResponse(responseCode = "200", description = "The settled notice")
  @APIResponse(responseCode = "400", description = "A refund belongs on a return")
  @APIResponse(responseCode = "403", description = "Staff not held to the notice's store")
  @APIResponse(responseCode = "404", description = "No such notice")
  @APIResponse(responseCode = "409", description = "Already settled")
  @POST
  @Path("/{id}/resolve")
  public ApiResponse<NoticeResponse> resolve(@PathParam("id") String id, ResolveRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole(STAFF);
    var detail =
        service.resolve(
            ctx.requireTenantId(),
            Parsing.uuid(id, "id"),
            ctx.requireUserId(),
            Resolution.valueOf(req.resolution()),
            req.notes(),
            ctx);
    return ApiResponse.ok(
        RecallNoticeMappers.toNotice(detail), ApiResponse.Meta.of(ctx.requestId()));
  }

  private boolean isStaff() {
    for (String role : STAFF) {
      if (ctx.hasRole(role)) return true;
    }
    return false;
  }

  private UUID requireLogin() {
    UUID loginId = ctx.userId();
    if (loginId == null) {
      throw ApiException.unauthorized("NO_CUSTOMER", "a customer token is required");
    }
    return loginId;
  }

  private static Status parseStatus(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Status.valueOf(value.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "RECALL_NOTICE_STATUS_INVALID",
          "status must be ISSUED, UNIDENTIFIED, REMEDY_CHOSEN or RESOLVED",
          List.of(),
          e);
    }
  }
}
