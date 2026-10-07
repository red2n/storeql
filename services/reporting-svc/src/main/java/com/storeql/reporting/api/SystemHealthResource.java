package com.storeql.reporting.api;

import com.storeql.reporting.mapper.Mappers;
import com.storeql.reporting.service.WaitingWorkService;
import com.storeql.web.ApiResponse;
import com.storeql.web.PendingWorkCount;
import com.storeql.web.SystemHealthAccess;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Thin JAX-RS resource for the system-health screen's waiting work: check who is asking, delegate
 * to {@link WaitingWorkService}, wrap in the envelope. The business is the caller's own, from the
 * token; nothing in the request can name another.
 */
@Path("/admin/reports/system-health")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "System Health")
public class SystemHealthResource {

  @Inject WaitingWorkService service;
  @Inject TenantContext ctx;

  /** How many things of each kind wait for a person in the caller's business. */
  @Operation(
      summary = "Work waiting for a person",
      description =
          "How many purchase orders, supplier payment runs, flagged supplier invoices, uncertain"
              + " accounting pushes, card refunds and privacy requests wait for a person, each"
              + " counted by the service that owns it and read at once. A kind whose service could"
              + " not be reached in time has a null count and is named in unreachable: unknown,"
              + " not zero. Each count is read to at most "
              + PendingWorkCount.CAP
              + " rows of a queue and stops there: a count that reached the cap has capped: true"
              + " and means that many or more. The"
              + " answer is kept for a few seconds per business"
              + " (storeql.reporting.waiting-work.cache-millis), so generatedAt can be that old."
              + " Needs the system.health permission and a caller held to no store, judged before"
              + " anything kept is read.")
  @APIResponse(responseCode = "200", description = "One item per kind, in a fixed order")
  @APIResponse(
      responseCode = "403",
      description =
          "SYSTEM_HEALTH_NOT_PERMITTED, BUSINESS_WIDE_ONLY (held to stores), or a role below"
              + " management")
  @GET
  @Path("/waiting-work")
  public ApiResponse<Object> waitingWork() {
    SystemHealthAccess.require(ctx);
    return ApiResponse.ok(Mappers.toWaitingWorkReport(service.waitingWork(ctx.requireTenantId())));
  }
}
