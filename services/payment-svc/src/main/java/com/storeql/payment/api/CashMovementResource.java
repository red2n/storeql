package com.storeql.payment.api;

import com.storeql.ids.Ids;
import com.storeql.payment.dto.Dtos.CashMovementRequest;
import com.storeql.payment.dto.Dtos.GenerateZReportRequest;
import com.storeql.payment.service.CashMovementService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Pay-in / pay-out (petty cash movements) and daily Z-report settlement. MANAGER or above only —
 * these are reconciliation-level operations.
 */
@Path("/admin/cash")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cash Movements")
public class CashMovementResource {

  @Inject CashMovementService svc;
  @Inject TenantContext ctx;

  /** Record a pay-in or pay-out against an open till session. */
  @Operation(
      summary = "Record a pay-in or pay-out",
      description =
          "Petty cash movement against an open till session. direction must be PAY_IN or PAY_OUT."
              + " Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "201", description = "Cash movement recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "INVALID_DIRECTION, or CASH_AMOUNT_INVALID: finer than the business's currency's minor"
              + " unit (whole yen, a dinar's three places); VALIDATION_FAILED")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @POST
  @Path("/movements")
  public Response recordMovement(
      @HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CashMovementRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID recordedBy = ctx.userId();
    var movement = svc.recordMovement(tenantId, recordedBy, req, ctx, idempotencyKey);
    return Response.status(201)
        .entity(ApiResponse.ok(movement, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** List movements for a till session. */
  @Operation(
      summary = "List cash movements",
      description =
          "All pay-in/pay-out movements recorded for the given till session. Requires"
              + " MANAGER or OWNER.")
  @APIResponse(responseCode = "200", description = "Movements for the till session")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @GET
  @Path("/movements")
  public ApiResponse<?> listMovements(@QueryParam("tillSessionId") String tillSessionId) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    UUID tenantId = ctx.requireTenantId();
    UUID sessionId = Ids.parse(tillSessionId);
    return ApiResponse.ok(
        svc.listMovements(tenantId, sessionId), ApiResponse.Meta.of(ctx.requestId()));
  }

  /** Generate (or retrieve) the daily Z-report for a store. */
  @Operation(
      summary = "Generate the daily Z-report",
      description =
          "Generates (or retrieves an existing) end-of-day Z-report for a store and business date."
              + " Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "201", description = "Z-report written")
  @APIResponse(responseCode = "200", description = "The day was already settled: stored report")
  @APIResponse(
      responseCode = "400",
      description =
          "CASH_AMOUNT_INVALID: the count is finer than the report's currency's minor unit;"
              + " CURRENCY_INVALID; Z_REPORT_CORRECTION_REASON_REQUIRED; VALIDATION_FAILED")
  @APIResponse(responseCode = "409", description = "Z_REPORT_SESSIONS_OPEN or Z_REPORT_NOT_LATEST")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @POST
  @Path("/z-report")
  public Response generateZReport(GenerateZReportRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID generatedBy = ctx.userId();
    var outcome = svc.generateZReport(tenantId, generatedBy, req, ctx);
    // 201 when this request wrote the report; 200 when the day was already settled and the stored
    // report is answered.
    return Response.status(outcome.written() ? 201 : 200)
        .entity(ApiResponse.ok(outcome.report(), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** Retrieve an existing Z-report by store + date. */
  @Operation(
      summary = "Get the Z-report for a store and date",
      description = "Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "200", description = "Z-report found")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @APIResponse(responseCode = "404", description = "No Z-report for that store and date")
  @GET
  @Path("/z-report")
  public ApiResponse<?> getZReport(
      @QueryParam("storeId") String storeId,
      @QueryParam("businessDate") String businessDate,
      @QueryParam("version") Integer version) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.TILL_MANAGE);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        svc.getZReport(tenantId, Ids.parse(storeId), businessDate, version, ctx),
        ApiResponse.Meta.of(ctx.requestId()));
  }
}
