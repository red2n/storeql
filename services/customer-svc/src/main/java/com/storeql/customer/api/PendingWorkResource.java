package com.storeql.customer.api;

import com.storeql.customer.mapper.Mappers;
import com.storeql.customer.service.PendingWorkService;
import com.storeql.web.ApiResponse;
import com.storeql.web.PendingWorkCount;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code GET /admin/pending-work}: how many privacy requests wait for an answer (the system-health
 * screen), counted up to {@link PendingWorkCount#CAP}.
 *
 * <p>Under {@code /admin/}, so the authorisation filter holds it to management by path ({@code 403
 * FORBIDDEN} for a storekeeper, a cashier or a shopper); the permission and the business-wide rule
 * are asked in the service.
 */
@Path("/admin/pending-work")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Pending Work")
public class PendingWorkResource {

  @Inject PendingWorkService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "What waits for a person in customer records",
      description =
          "The count, for the caller's whole business, of privacy requests in OPEN. The count is"
              + " bounded: it is read up to "
              + PendingWorkCount.CAP
              + " and no further, so a count of "
              + PendingWorkCount.CAP
              + " means "
              + PendingWorkCount.CAP
              + " or more. Needs the system.health permission and a caller held to no store.")
  @APIResponse(responseCode = "200", description = "The count, at most " + PendingWorkCount.CAP)
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; SYSTEM_HEALTH_NOT_PERMITTED: no system.health;"
              + " BUSINESS_WIDE_ONLY: a caller held to stores")
  @GET
  public Response get() {
    return Response.ok(
            ApiResponse.ok(Mappers.toDto(service.read(ctx)), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
