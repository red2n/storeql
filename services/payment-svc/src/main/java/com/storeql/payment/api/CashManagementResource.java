package com.storeql.payment.api;

import com.storeql.payment.dto.Dtos.CloseTillRequest;
import com.storeql.payment.dto.Dtos.OpenTillRequest;
import com.storeql.payment.dto.Dtos.RecordCashDropRequest;
import com.storeql.payment.service.CashManagementService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Cash management: till float, cash drops, X-report (read-only), Z-report (close).
 *
 * <p>Roles: CASHIER opens a till; MANAGER or above closes (Z-report) and records cash drops.
 */
@RequestScoped
@Path("/admin/cash/till-sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Till Sessions")
public class CashManagementResource {

  @Inject CashManagementService svc;
  @Inject TenantContext ctx;

  /** Open a till session (record opening float). CASHIER or above. */
  @Operation(
      summary = "Open a till session",
      description =
          "Records the opening cash float for the store. Requires CASHIER, MANAGER, or" + " OWNER.")
  @APIResponse(responseCode = "201", description = "Till session opened")
  @APIResponse(responseCode = "403", description = "Caller lacks a cashier/manager/owner role")
  @POST
  public Response open(OpenTillRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID userId = ctx.userId();
    var session = svc.openTill(tenantId, userId, req, ctx);
    return Response.status(201).entity(ApiResponse.ok(session)).build();
  }

  /**
   * The caller's own open till at a store — the one a till screen resumes after a reload. Declared
   * before {@code /{id}} so the literal segment is never read as a session id.
   */
  @Operation(
      summary = "Get the caller's open till session at a store",
      description =
          "The session the caller opened at storeId and has not closed. Requires CASHIER, MANAGER,"
              + " or OWNER, and access to the store.")
  @APIResponse(responseCode = "200", description = "The caller's open till session")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: storeId missing or not a UUIDv7")
  @APIResponse(
      responseCode = "403",
      description = "Caller lacks a cashier/manager/owner role, or is not assigned to the store")
  @APIResponse(
      responseCode = "404",
      description = "TILL_SESSION_NOT_OPEN: the caller has no open till at the store")
  @GET
  @Path("/current")
  public Response current(@QueryParam("storeId") String storeId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID store = Parsing.uuid(storeId, "storeId");
    return Response.ok(
            ApiResponse.ok(
                svc.currentSession(ctx.requireTenantId(), store, ctx.requireUserId(), ctx)))
        .build();
  }

  /** Get current session status and float. */
  @Operation(summary = "Get a till session", description = "Current session status and float.")
  @APIResponse(responseCode = "200", description = "Till session found")
  @APIResponse(responseCode = "404", description = "Till session not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return Response.ok(ApiResponse.ok(svc.getSession(ctx.requireTenantId(), id, ctx))).build();
  }

  /** Record a cash drop (mid-shift safe drop). MANAGER or above. */
  @Operation(
      summary = "Record a cash drop",
      description = "Mid-shift safe drop against an open till session. Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "201", description = "Cash drop recorded")
  @APIResponse(responseCode = "400", description = "Till already closed, or invalid drop amount")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @APIResponse(responseCode = "404", description = "Till session not found")
  @POST
  @Path("/{id}/drops")
  public Response drop(@PathParam("id") UUID id, RecordCashDropRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID userId = ctx.userId();
    var drop = svc.recordDrop(tenantId, id, userId, req.amount(), req.notes(), ctx);
    return Response.status(201).entity(ApiResponse.ok(drop)).build();
  }

  /** X-report: mid-day read-only snapshot. Does not close the till. MANAGER or above. */
  @Operation(
      summary = "Get the X-report",
      description =
          "Mid-day read-only snapshot of the till session's totals. Does not close the"
              + " till. Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "200", description = "X-report generated")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @APIResponse(responseCode = "404", description = "Till session not found")
  @GET
  @Path("/{id}/x-report")
  public Response xReport(@PathParam("id") UUID id) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    return Response.ok(ApiResponse.ok(svc.xReport(ctx.requireTenantId(), id, ctx))).build();
  }

  /** Z-report: end-of-day close. Requires counted cash amount. MANAGER or above. */
  @Operation(
      summary = "Close the till (Z-report)",
      description =
          "End-of-day close: computes totals against the counted cash amount and closes the"
              + " session. Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "200", description = "Till closed, Z-report generated")
  @APIResponse(responseCode = "400", description = "Till already closed")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @APIResponse(responseCode = "404", description = "Till session not found")
  @POST
  @Path("/{id}/close")
  public Response close(@PathParam("id") UUID id, CloseTillRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(svc.zReport(ctx.requireTenantId(), id, req, ctx))).build();
  }
}
