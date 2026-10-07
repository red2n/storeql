package com.storeql.order.api;

import com.storeql.order.dto.Dtos.ReturnPolicyRequest;
import com.storeql.order.dto.Dtos.ReturnPolicyResponse;
import com.storeql.order.service.ReturnPolicyService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The business's return policy (intent/return-controls.md). Management only: {@code /admin/} is
 * gated by the shared filter, and the role is checked again here so the rule is visible where it
 * applies.
 */
@ApplicationScoped
@Path("/admin/return-policy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Return policy")
public class ReturnPolicyResource {

  @Inject ReturnPolicyService svc;
  @Inject TenantContext ctx;

  /**
   * The policy in force: the business's own, or the default while it has set none.
   *
   * @return the window, the ceilings and the currency they are in
   */
  @Operation(
      summary = "Get the return policy",
      description =
          "The business's return policy: the window in days, the cashier ceiling and the"
              + " no-receipt rule, ceilings in the home currency. Until one is set the default"
              + " applies: 30 days, no cashier ceiling, no-receipt returns off.")
  @APIResponse(responseCode = "200", description = "The policy in force")
  @GET
  public ApiResponse<ReturnPolicyResponse> get() {
    ctx.requireAnyRole("OWNER", "MANAGER", "PLATFORM_ADMIN");
    return ApiResponse.ok(toDto(svc.view(ctx.requireTenantId())));
  }

  /**
   * Sets the policy.
   *
   * @param req the window, the cashier ceiling and the no-receipt rule
   * @return the policy now in force
   */
  @Operation(
      summary = "Set the return policy",
      description =
          "Replaces the business's return policy. A return outside it needs a member of staff"
              + " holding sales.refund.")
  @APIResponse(responseCode = "200", description = "The policy now in force")
  @APIResponse(responseCode = "400", description = "Window outside 1..3650 or a negative ceiling")
  @APIResponse(
      responseCode = "403",
      description = "BUSINESS_WIDE_ONLY: a manager held to stores cannot set the whole business's")
  @PUT
  public ApiResponse<ReturnPolicyResponse> put(ReturnPolicyRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER", "PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(toDto(svc.set(ctx.requireTenantId(), req, ctx)));
  }

  private static ReturnPolicyResponse toDto(ReturnPolicyService.View v) {
    var p = v.policy();
    return new ReturnPolicyResponse(
        p.windowDays(),
        p.cashierCeiling(),
        p.noReceiptAllowed(),
        p.noReceiptCeiling(),
        v.currency(),
        v.usingDefault());
  }
}
