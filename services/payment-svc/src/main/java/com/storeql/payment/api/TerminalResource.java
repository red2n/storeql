package com.storeql.payment.api;

import com.storeql.ids.Ids;
import com.storeql.payment.dto.TerminalDtos;
import com.storeql.payment.mapper.TerminalMappers;
import com.storeql.payment.service.TerminalService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The register of EMV terminals a business has (07.16).
 *
 * <p>Management's: which devices exist, at which store, and which have been retired. Taking a card
 * on one is {@link TerminalPaymentResource}, the till's — two classes rather than one, for a reason
 * JAX-RS makes unavoidable and which that class explains.
 *
 * <p><b>No route here accepts a card number.</b> The terminal reads the card; this platform sends
 * an amount and receives a verdict.
 */
@Path("/admin/payments/terminals")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Card terminals")
public class TerminalResource {

  @Inject TerminalService svc;
  @Inject TenantContext ctx;

  // ── the register ────────────────────────────────────────────────────────────

  @Operation(
      summary = "Register an EMV terminal for a store",
      description =
          "A terminal belongs to a store: it is a physical object on a counter, and a payment taken on"
              + " it is taken there. The vendor must be configured on this deployment — said here,"
              + " rather than at the till with a customer waiting. SIMULATED is always available and"
              + " says on the record that nothing left the building.")
  @APIResponse(responseCode = "201", description = "Terminal registered")
  @APIResponse(responseCode = "409", description = "Vendor not configured, or label/serial taken")
  @POST
  public Response register(TerminalDtos.RegisterRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    // Belt to the gateway's braces: nothing card-shaped may arrive by any route, including a label.
    TerminalService.refuseCardData(req.label(), req.serial());
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    var t =
        svc.register(
            ctx.requireTenantId(),
            storeId,
            req.label(),
            req.vendor(),
            req.serial(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(TerminalMappers.toDto(t))).build();
  }

  @Operation(
      summary = "The terminals this business has",
      description =
          "Retired ones included, so history reads: a payment points at the device it was taken on.")
  @GET
  public ApiResponse<List<TerminalDtos.TerminalResponse>> list() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(TerminalMappers.terminals(svc.list(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Retire a terminal",
      description =
          "Never deleted: payments point at it. Its label is then free for the device that replaces"
              + " it, which is what happens when one is swapped after a fault. The reason is"
              + " optional, and so is the body; one that is sent is held to its limit (300"
              + " characters), and a longer one is refused as a whole with the terminal left in"
              + " service. A manager held to other stores cannot retire this store's terminal."
              + " Not while it holds a card payment that is not settled (at the machine, approved"
              + " and neither recorded nor put back, or timed out with nobody's word on it — a"
              + " refund too): settle it first, the way the guard on a new sale says. Nor while"
              + " it is the last machine of its vendor in service at its store and cards that"
              + " vendor's machines took there are owed money back (a card goes back through the"
              + " machine that took it or another of its vendor there): register its replacement"
              + " first, ask it again, or record how each was given back another way.")
  @APIResponse(responseCode = "200", description = "Retired")
  @APIResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: the reason is longer than 300 characters")
  @APIResponse(
      responseCode = "403",
      description = "Not management, or STORE_ACCESS_DENIED: held to stores that are not this one")
  @APIResponse(
      responseCode = "404",
      description = "TERMINAL_NOT_FOUND: no such terminal for this business")
  @APIResponse(
      responseCode = "409",
      description =
          "TERMINAL_ALREADY_RETIRED; TERMINAL_UNSETTLED_APPROVAL: it holds a card payment that is"
              + " not settled, one detail per payment"
              + " (attemptId=…;orderId=…;amount=…;currency=…;onCard=…;state=…;standing=…;kind=…);"
              + " TERMINAL_REFUNDS_OWED: it is the last of its vendor at its store and money is"
              + " owed back to cards, one detail per sum, the oldest fifty"
              + " (dueId=…;attemptId=…;orderId=…;amount=…;currency=…;state=…)")
  @POST
  @Path("/{id}/retire")
  public ApiResponse<TerminalDtos.TerminalResponse> retire(
      @PathParam("id") UUID id, TerminalDtos.RetireRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    // The reason is optional, so the body is too; one that is sent is held to its limits.
    if (req != null) {
      Validations.validate(req);
    }
    UUID tenantId = ctx.requireTenantId();
    // The business's own terminal (404 otherwise), then at a store the caller may act at.
    ctx.requireStoreAccess(svc.get(tenantId, id).storeId());
    return ApiResponse.ok(
        TerminalMappers.toDto(svc.retire(tenantId, id, req == null ? null : req.reason())));
  }

  /** The vendors this deployment can talk to, so a screen offers only those. */
  @Operation(
      summary = "The terminal vendors this deployment can talk to",
      description =
          "A vendor whose credentials are not configured is not offered, so a business is not asked to"
              + " pair a device the platform cannot reach.")
  @GET
  @Path("/vendors")
  public ApiResponse<List<String>> vendors() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(svc.availableVendors());
  }

  private static UUID uuid(String value, String field) {
    try {
      return Ids.parse(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "TERMINAL_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }
}
