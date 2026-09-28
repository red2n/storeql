package com.storeql.iam.api;

import com.storeql.iam.domain.ApiKey;
import com.storeql.iam.dto.ApiKeyDtos;
import com.storeql.iam.service.ApiKeyService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A business's API keys (22.7): the owner mints and revokes them, an owner or a manager reads the
 * list. The key itself is in the answer to the minting and nowhere else.
 */
@Path("/auth/admin/api-keys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "API keys")
public class ApiKeyResource {

  @Inject ApiKeyService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Mint an API key",
      description =
          "OWNER only. The key acts in one staff tier — MANAGER, STOREKEEPER or CASHIER, never"
              + " OWNER — for the stores named or all of them, until the day given or until"
              + " revoked. The answer carries the key itself, once: it is not kept and cannot be"
              + " shown again.")
  @APIResponse(responseCode = "201", description = "The key, shown once")
  @APIResponse(
      responseCode = "400",
      description = "API_KEY_NAME_INVALID, API_KEY_ROLE_INVALID, API_KEY_EXPIRY_PAST, INVALID_UUID")
  @POST
  public Response mint(ApiKeyDtos.CreateRequest req) {
    ctx.requireAnyRole("OWNER");
    ApiKeyService.Minted minted = service.mint(ctx.requireTenantId(), ctx.requireUserId(), req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(ApiKeyDtos.CreatedResponse.of(toDto(minted.key()), minted.secret())))
        .build();
  }

  @Operation(
      summary = "The business's API keys",
      description =
          "OWNER or MANAGER. In the order they were made, revoked ones included and marked; never"
              + " the key itself. Cursor-paginated on the key's id.")
  @GET
  public ApiResponse<ApiKeyDtos.Page> list(
      @QueryParam("after") String after, @QueryParam("limit") @DefaultValue("20") int limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    ApiKeyService.Page page =
        service.list(
            ctx.requireTenantId(),
            after == null || after.isBlank() ? null : Parsing.uuid(after, "after"),
            limit);
    return ApiResponse.ok(
        new ApiKeyDtos.Page(
            page.items().stream().map(ApiKeyResource::toDto).toList(), page.nextCursor()));
  }

  @Operation(
      summary = "Revoke an API key",
      description =
          "OWNER only. The key stops at once (the gateway remembers a verdict for ten seconds at"
              + " most) and stays on the list, marked.")
  @APIResponse(responseCode = "404", description = "API_KEY_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "API_KEY_REVOKED: already revoked")
  @DELETE
  @Path("/{id}")
  public ApiResponse<ApiKeyDtos.KeyResponse> revoke(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER");
    return ApiResponse.ok(toDto(service.revoke(ctx.requireTenantId(), ctx.requireUserId(), id)));
  }

  static ApiKeyDtos.KeyResponse toDto(ApiKey k) {
    return new ApiKeyDtos.KeyResponse(
        k.id().toString(),
        k.name(),
        k.prefix(),
        k.role(),
        k.storeIds().stream().map(UUID::toString).toList(),
        k.createdBy().toString(),
        k.createdAt(),
        k.expiresAt(),
        k.lastUsedAt(),
        k.revokedAt(),
        k.revokedBy() == null ? null : k.revokedBy().toString(),
        k.sandbox());
  }
}
