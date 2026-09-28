package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.CreateStoreRequest;
import com.storeql.tenant.dto.Dtos.CreateTenantRequest;
import com.storeql.tenant.dto.Dtos.OnboardRequest;
import com.storeql.tenant.dto.Dtos.OnboardResponse;
import com.storeql.tenant.dto.Dtos.OnboardingStatus;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.TenantService;
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
 * Onboarding endpoints (docs/onboarding-and-locations.md §4-5).
 *
 * <p>{@code POST /onboarding/tenants}: the caller has NO tenant yet — we bind the new tenant to the
 * authenticated userId (from the gateway-forwarded identity). Afterwards, {@code tenantId} comes
 * from the JWT/context.
 */
@Path("/onboarding")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Onboarding")
public class OnboardingResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  /**
   * Single-call onboarding: creates the tenant and its first store together.
   *
   * <p>Preferred over calling {@link #createTenant} then {@link #createStore}, because the tenant
   * id is minted here and passed straight through — the caller never needs a refreshed JWT carrying
   * the new tenant claim, so there is no async race to lose.
   *
   * @param req the business details and the first store's details in one payload
   * @return {@code 201} with the created tenant and store
   */
  @Operation(
      summary = "Onboard a new tenant and its first store",
      description =
          "Single-call onboarding: creates the tenant AND the first store (+ default zone)"
              + " atomically. No JWT refresh needed.")
  @APIResponse(responseCode = "201", description = "Tenant and first store created")
  @POST
  public Response onboard(OnboardRequest req) {
    Validations.validate(req);
    UUID ownerUserId = ctx.requireUserId();
    var result = service.onboard(ownerUserId, ctx.email(), req);
    var body =
        new OnboardResponse(Mappers.toTenant(result.tenant()), Mappers.toStore(result.store()));
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(body, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Creates the business and binds the authenticated caller as its OWNER.
   *
   * <p>The caller has no tenant claim yet, so the owner is taken from the gateway-forwarded
   * identity rather than from a tenant in the token.
   *
   * @param req the business name, legal name, country and currency
   * @return {@code 201} with the created tenant
   */
  @Operation(
      summary = "Create the business",
      description =
          "The caller has no tenant yet — the new tenant is bound to the authenticated userId as"
              + " OWNER.")
  @APIResponse(responseCode = "201", description = "Tenant created")
  @POST
  @Path("/tenants")
  public Response createTenant(CreateTenantRequest req) {
    Validations.validate(req);
    UUID ownerUserId = ctx.requireUserId();
    var tenant = service.createTenant(ownerUserId, ctx.email(), req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTenant(tenant), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Creates the tenant's default store and its DEFAULT zone.
   *
   * <p>Reachable with a caller-supplied tenant id (the gateway's onboarding carve-out), so the
   * caller is additionally checked to be that tenant's owner — the tenant id alone is not proof.
   *
   * @param req the store's name, code, type and address
   * @return {@code 201} with the created store
   * @throws com.storeql.web.ApiException {@code TENANT_ACCESS_DENIED} (403) when the caller does
   *     not own the tenant
   */
  @Operation(
      summary = "Create the first/default store",
      description =
          "Creates the default store (+ its DEFAULT zone) for the caller's tenant, taken from the"
              + " JWT/context.")
  @APIResponse(responseCode = "201", description = "Default store created")
  @POST
  @Path("/stores")
  public Response createStore(CreateStoreRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result = service.createDefaultStore(tenantId, ctx.requireUserId(), req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(Mappers.toStore(result.store()), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Setup-checklist state: tenant active, default store present, and what to do next.
   *
   * @return the checklist flags and the next-step prompts
   * @throws com.storeql.web.ApiException {@code TENANT_ACCESS_DENIED} (403) when the caller does
   *     not own the tenant; {@code TENANT_NOT_FOUND} (404) when it does not exist
   */
  @Operation(
      summary = "Get onboarding status",
      description = "Setup-checklist state: tenant active, default store present, and next steps.")
  @APIResponse(responseCode = "200", description = "The checklist flags and the next-step prompts")
  @GET
  @Path("/status")
  public ApiResponse<OnboardingStatus> status() {
    return ApiResponse.ok(service.onboardingStatus(ctx.requireTenantId(), ctx.requireUserId()));
  }
}
