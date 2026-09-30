package com.storeql.tenant.api;

import com.storeql.tenant.dto.SwitchingDtos.CancelRequest;
import com.storeql.tenant.dto.SwitchingDtos.ExtendRequest;
import com.storeql.tenant.dto.SwitchingDtos.GiveNoticeRequest;
import com.storeql.tenant.dto.SwitchingDtos.StatusResponse;
import com.storeql.tenant.mapper.SwitchingMappers;
import com.storeql.tenant.service.SwitchingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/tenant/switching}: the owner gives notice to leave the platform, taking the
 * business's data elsewhere or having it erased, extends the transitional period once, or withdraws
 * the notice while it runs (21.14, EU Data Act art.25). The owner alone: it ends the contract.
 */
@Path("/admin/tenant/switching")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Switching")
public class SwitchingResource {

  @Inject SwitchingService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Where the business's leaving stands",
      description =
          "The latest notice with its dates, the stage it has reached, and, once erasure has"
              + " started, what each service erased and which have yet to answer.")
  @APIResponse(responseCode = "200", description = "The notice")
  @APIResponse(responseCode = "404", description = "SWITCHING_NO_NOTICE")
  @GET
  public ApiResponse<StatusResponse> status() {
    return ApiResponse.ok(
        SwitchingMappers.toStatus(service.requireStatus(owner())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Give notice",
      description =
          "SWITCH: notice of at most two months, then a 30-day transitional period and at least 30"
              + " days to retrieve the data, then erasure. ERASE: the data is erased when notice"
              + " ends. One notice at a time.")
  @APIResponse(responseCode = "201", description = "The notice")
  @APIResponse(
      responseCode = "400",
      description = "SWITCHING_INTENT_UNKNOWN, SWITCHING_NOTICE_INVALID, SWITCHING_NOTICE_TOO_LONG")
  @APIResponse(responseCode = "409", description = "SWITCHING_NOTICE_ALREADY_GIVEN")
  @POST
  public Response give(GiveNoticeRequest req) {
    BusinessWide.require(ctx);
    Validations.validate(req);
    UUID tenantId = owner();
    return Response.status(201)
        .entity(
            ApiResponse.ok(
                SwitchingMappers.toStatus(
                    service.give(tenantId, ctx.requireUserId(), req.intent(), req.noticeEndsOn())),
                ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(
      summary = "Extend the transitional period",
      description = "Once, while it runs, to at most seven months after notice ends.")
  @APIResponse(responseCode = "200", description = "The notice")
  @APIResponse(
      responseCode = "400",
      description = "SWITCHING_EXTENSION_INVALID, SWITCHING_EXTENSION_TOO_LONG")
  @APIResponse(
      responseCode = "409",
      description =
          "SWITCHING_ALREADY_EXTENDED, SWITCHING_NOT_EXTENDABLE, SWITCHING_TRANSITION_OVER,"
              + " SWITCHING_CLOSED")
  @POST
  @Path("/extend")
  public ApiResponse<StatusResponse> extend(ExtendRequest req) {
    BusinessWide.require(ctx);
    Validations.validate(req);
    UUID tenantId = owner();
    return ApiResponse.ok(
        SwitchingMappers.toStatus(
            service.extend(tenantId, ctx.requireUserId(), req.transitionEndsOn())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Withdraw the notice", description = "While notice runs, with a reason.")
  @APIResponse(responseCode = "200", description = "The withdrawn notice")
  @APIResponse(responseCode = "409", description = "SWITCHING_TOO_LATE_TO_CANCEL, SWITCHING_CLOSED")
  @POST
  @Path("/cancel")
  public ApiResponse<StatusResponse> cancel(CancelRequest req) {
    BusinessWide.require(ctx);
    Validations.validate(req);
    UUID tenantId = owner();
    return ApiResponse.ok(
        SwitchingMappers.toStatus(service.cancel(tenantId, ctx.requireUserId(), req.reason())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  private UUID owner() {
    ctx.requireAnyRole("OWNER");
    return ctx.requireTenantId();
  }
}
