package com.storeql.purchase.api;

import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PendingWorkService;
import com.storeql.web.ApiResponse;
import com.storeql.web.PendingWorkCount;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
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
 * {@code GET /admin/pending-work}: how many purchase orders, payment runs, supplier invoices and
 * accounting pushes wait for a person (the system-health screen).
 *
 * <p>Under {@code /admin/} and outside the staff-operable subtrees, so the authorisation filter
 * holds it to management by path ({@code 403 FORBIDDEN} for a storekeeper, a cashier or a shopper);
 * the permission and the business-wide rule are asked in the service.
 */
@RequestScoped
@Path("/admin/pending-work")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Pending Work")
public class PendingWorkResource {

  @Inject PendingWorkService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "What waits for a person in purchasing",
      description =
          "Counts, for the caller's whole business, of purchase orders PENDING_APPROVAL, payment"
              + " runs PROPOSED, supplier invoices FLAGGED and accounting pushes UNCERTAIN. Each"
              + " count stops at "
              + PendingWorkCount.CAP
              + ": a count of "
              + PendingWorkCount.CAP
              + " means "
              + PendingWorkCount.CAP
              + " or more. approvalsRouted is false when this deployment has no purchase approval"
              + " limits configured: no order is then held for approval for any business, so a zero"
              + " purchase-order count is not \"nothing waits\". Needs the system.health permission"
              + " and a caller held to no store.")
  @APIResponse(
      responseCode = "200",
      description = "The four counts and whether approvals are routed")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; SYSTEM_HEALTH_NOT_PERMITTED: no system.health;"
              + " BUSINESS_WIDE_ONLY: a caller held to stores")
  @GET
  public Response get() {
    return Response.ok(
            ApiResponse.ok(Mappers.toDto(svc.read(ctx)), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
