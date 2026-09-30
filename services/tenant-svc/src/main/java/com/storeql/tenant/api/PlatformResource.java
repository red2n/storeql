package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.CurrencyRepublishResponse;
import com.storeql.tenant.dto.Dtos.TenantResponse;
import com.storeql.tenant.dto.Dtos.TenantStatusRequest;
import com.storeql.tenant.dto.PlanDtos;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.mapper.PlanMappers;
import com.storeql.tenant.service.PlanService;
import com.storeql.tenant.service.TenantService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

/** Platform-admin endpoints — PLATFORM_ADMIN role required on every method. */
@Path("/platform")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Platform")
public class PlatformResource {

  @Inject PlanService plans;

  @Inject TenantService service;
  @Inject TenantContext ctx;

  /**
   * Cross-tenant list of every business on the platform.
   *
   * <p>One of the few reads that deliberately crosses tenant boundaries, so it is gated on {@code
   * PLATFORM_ADMIN} rather than an ordinary tenant role.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of tenants, with the next cursor in {@code meta}
   * @throws com.storeql.web.ApiException {@code 403} when the caller is not a {@code
   *     PLATFORM_ADMIN}
   */
  @Operation(
      summary = "List all tenants",
      description =
          "Cross-tenant platform-admin list. Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100."
              + " Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @GET
  @Path("/tenants")
  public ApiResponse<List<TenantResponse>> listAllTenants(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    var page = service.listAllTenants(after, Cursor.clampLimit(limit));
    var tenants = page.items().stream().map(Mappers::toTenant).toList();
    return ApiResponse.ok(tenants, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "One business, as the platform sees it",
      description =
          "Including **why** it is switched off, which is the question the console has to be able to"
              + " answer: only a business suspended for NON_PAYMENT comes back when it pays, and one"
              + " an ADMINISTRATOR switched off never does. Without this route the reason is recorded"
              + " and unreadable.")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @APIResponse(responseCode = "404", description = "No such business")
  @GET
  @Path("/tenants/{tenantId}")
  public ApiResponse<TenantResponse> oneTenant(@PathParam("tenantId") UUID tenantId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(Mappers.toTenant(service.getTenant(tenantId)));
  }

  /**
   * The business a network's delivery lands with (07.13, the transport seam).
   *
   * <p>purchase-svc asks this, platform-wide, when an access point delivers an e-invoice: the
   * document names its buyer by electronic address and VAT identifier, and only one active business
   * may hold what it names.
   *
   * @param scheme the address's EAS scheme, with {@code id}
   * @param id the identifier within the scheme
   * @param vatNumber the buyer's VAT identifier, read when no address is given
   * @return the business
   * @throws com.storeql.web.ApiException {@code 400} for half an address or nothing named; {@code
   *     403} when the caller is not a {@code PLATFORM_ADMIN}; {@code 404} when no active business
   *     holds it; {@code 409} when more than one does
   */
  @Operation(
      summary = "Which business holds an e-invoicing address",
      description =
          "By scheme and id (a Peppol participant identifier), else by vatNumber. The one active"
              + " business holding it; 404 when none does, 409 when more than one does, since a"
              + " delivery to a shared address lands nowhere. Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "400", description = "Half an address, or nothing named")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @APIResponse(responseCode = "404", description = "No active business holds it")
  @APIResponse(responseCode = "409", description = "More than one active business holds it")
  @GET
  @Path("/tenants/by-einvoice-address")
  public ApiResponse<TenantResponse> receiver(
      @QueryParam("scheme") String scheme,
      @QueryParam("id") String id,
      @QueryParam("vatNumber") String vatNumber) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(Mappers.toTenant(service.receiver(scheme, id, vatNumber)));
  }

  /**
   * Suspends or reactivates a tenant.
   *
   * <p>Publishes {@code TenantStatusChanged} so the effect reaches the services that must act on it
   * — iam-svc locking staff out, cart/order-svc refusing trade — rather than only flipping a row
   * here.
   *
   * @param tenantId the tenant whose status to change; taken from the path, as this is a
   *     cross-tenant platform operation
   * @param req the new status, {@code ACTIVE} or {@code INACTIVE}, and the reason, required to
   *     suspend
   * @return the tenant with its new status
   * @throws com.storeql.web.ApiException {@code 400} when the status is neither, or {@code
   *     TENANT_STATUS_REASON_REQUIRED} when a suspension gives no reason; {@code 403} when the
   *     caller is not a {@code PLATFORM_ADMIN}; {@code 404} when the tenant does not exist
   */
  @Operation(
      summary = "Suspend or reactivate a tenant",
      description =
          "Sets a tenant's status to ACTIVE or INACTIVE and publishes TenantStatusChanged so other"
              + " services (e.g. iam-svc locking out staff) can react. Suspending needs a reason,"
              + " kept with who and when. Requires PLATFORM_ADMIN.")
  @APIResponse(
      responseCode = "400",
      description = "status must be ACTIVE or INACTIVE; TENANT_STATUS_REASON_REQUIRED")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @PATCH
  @Path("/tenants/{tenantId}/status")
  public ApiResponse<TenantResponse> patchTenantStatus(
      @PathParam("tenantId") UUID tenantId, TenantStatusRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.patchTenantStatus(tenantId, req, ctx.userId())));
  }

  /**
   * Puts a business on a plan (21.8).
   *
   * <p>Refused when the business already uses more than the plan allows, and the refusal names what
   * is over: moving somebody onto a plan they do not fit would either have to take their stores
   * away or leave them quietly past a limit they are now told they have.
   *
   * @param tenantId the business to move
   * @param req the plan, and why
   * @return the plan it is now on, with each limit against what it is using
   * @throws com.storeql.web.ApiException 404 when there is no such business or plan; 409 {@code
   *     PLAN_NOT_SOLD}, {@code PLAN_LIMIT_EXCEEDED_NOW}
   */
  @Operation(
      summary = "Put a business on a plan",
      description =
          "PLATFORM_ADMIN. Refused when the business already exceeds what the plan allows"
              + " (PLAN_LIMIT_EXCEEDED_NOW), naming each limit it is over.")
  @APIResponse(responseCode = "409", description = "The plan is not on sale, or does not fit")
  @PUT
  @jakarta.ws.rs.Path("/tenants/{tenantId}/plan")
  public ApiResponse<PlanDtos.TenantPlanResponse> putOnPlan(
      @PathParam("tenantId") UUID tenantId, PlanDtos.TenantPlanRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(
        PlanMappers.toDto(
            plans.putOnPlan(tenantId, ctx.requireUserId(), req.planId(), req.reason())));
  }

  /**
   * Re-publishes {@code TenantCurrencyDeclared} so downstream projections can be rebuilt.
   *
   * <p>A repair tool, not a migration: a tenant onboarded before a consumer existed has no currency
   * projection there, and that consumer silently falls back to a platform default — so the tenant
   * trades in the wrong currency with nothing to signal it. Safe to run repeatedly, because each
   * replay carries fresh event ids and is therefore re-applied rather than deduped away.
   *
   * @param tenantId one tenant to re-announce, or {@code null} for every tenant
   * @return how many tenants were announced; zero when the named tenant has no currency recorded
   * @throws com.storeql.web.ApiException {@code 400} when {@code tenantId} is not a UUID; {@code
   *     403} when the caller is not a {@code PLATFORM_ADMIN}
   */
  @Operation(
      summary = "Re-announce tenants' declared currencies",
      description =
          "Publishes TenantCurrencyDeclared for every tenant that has a currency recorded, or for"
              + " one tenant with ?tenantId=. Consumers keep a local projection of this so they can"
              + " stamp money-bearing rows without calling this service; a tenant onboarded before"
              + " such a consumer existed has no projection and silently falls back to a default"
              + " currency. This is how that is repaired. Safe to run repeatedly. Requires"
              + " PLATFORM_ADMIN.")
  @APIResponse(responseCode = "200", description = "Number of tenants announced")
  @APIResponse(responseCode = "400", description = "tenantId is not a UUID")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @POST
  @Path("/tenants/republish-currency")
  public ApiResponse<CurrencyRepublishResponse> republishCurrency(
      @QueryParam("tenantId") String tenantId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    int announced = service.republishTenantCurrencies(Parsing.optionalUuid(tenantId, "tenantId"));
    return ApiResponse.ok(new CurrencyRepublishResponse(announced));
  }
}
