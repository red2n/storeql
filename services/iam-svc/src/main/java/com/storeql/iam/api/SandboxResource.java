package com.storeql.iam.api;

import com.storeql.iam.dto.Dtos.SandboxTokenResponse;
import com.storeql.iam.service.SandboxAccessService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Into the business's sandbox (22.8). */
@Path("/auth/sandbox")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Sandbox")
public class SandboxResource {

  @Inject SandboxAccessService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "A token for the business's sandbox",
      description =
          "OWNER only, from the live business. Trades the caller's token for one that names the"
              + " sandbox as its tenant — an owner there, with amr [sandbox] and no refresh token."
              + " The sandbox session lasts one access token; the live token stays valid.")
  @APIResponse(responseCode = "200", description = "The sandbox token")
  @APIResponse(responseCode = "404", description = "SANDBOX_NOT_FOUND: the business has none")
  @APIResponse(responseCode = "409", description = "SANDBOX_NESTED: called from inside a sandbox")
  @POST
  @Path("/token")
  public ApiResponse<SandboxTokenResponse> token() {
    ctx.requireAnyRole("OWNER");
    return ApiResponse.ok(service.enter(ctx.requireTenantId(), ctx.requireUserId()));
  }
}
