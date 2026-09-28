package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.TenantResponse;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.SandboxService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A business's sandbox (22.8), under {@code /admin/tenant/sandbox}: read by the owner or a manager,
 * made and removed by the owner. The sandbox is a tenant of its own; getting into it is iam-svc's
 * {@code POST /auth/sandbox/token}, and a key for it is minted with {@code sandbox: true}.
 */
@Path("/admin/tenant/sandbox")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Sandbox")
public class SandboxResource {

  @Inject SandboxService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The business's sandbox",
      description =
          "OWNER or MANAGER. The active sandbox of the caller's business — or, from inside a"
              + " sandbox, the sandbox itself. A tenant like any other, with mode SANDBOX and"
              + " sandboxOf naming the live business.")
  @APIResponse(responseCode = "404", description = "SANDBOX_NOT_FOUND: the business has none")
  @GET
  public ApiResponse<TenantResponse> get() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Mappers.toTenant(service.get(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Make the business's sandbox",
      description =
          "OWNER only, from the live business. A tenant of its own on the SANDBOX plan, owned by the"
              + " same login, with the live business's default store copied in. One at a time.")
  @APIResponse(responseCode = "201", description = "The sandbox")
  @APIResponse(
      responseCode = "409",
      description = "SANDBOX_EXISTS: there is one already; SANDBOX_NESTED: called from inside one")
  @APIResponse(responseCode = "503", description = "SANDBOX_PLAN_MISSING")
  @POST
  public Response create() {
    ctx.requireAnyRole("OWNER");
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTenant(service.create(ctx.requireTenantId()))))
        .build();
  }

  @Operation(
      summary = "Remove the business's sandbox",
      description =
          "OWNER only, from the live business. Switched off with the reason SANDBOX_DELETED, its"
              + " keys and tokens refused from then on, and every service told to erase what it"
              + " held of it. Another can be made at once.")
  @APIResponse(responseCode = "404", description = "SANDBOX_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "SANDBOX_NESTED: called from inside one")
  @DELETE
  public ApiResponse<TenantResponse> delete() {
    ctx.requireAnyRole("OWNER");
    return ApiResponse.ok(
        Mappers.toTenant(service.delete(ctx.requireTenantId(), ctx.requireUserId())));
  }
}
