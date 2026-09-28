package com.storeql.iam.api;

import com.storeql.iam.dto.ApiKeyDtos;
import com.storeql.iam.service.ApiKeyService;
import com.storeql.web.ApiException;
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
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What an API key may do right now (22.7), for the gateway — the one caller that holds a key it did
 * not mint and must decide whose request it is. Under {@code /platform} so only the platform's own
 * identity reaches it: a business cannot probe keys, and a signed-in person cannot ask about one
 * they found.
 */
@Path("/platform/api-keys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "API keys")
public class ApiKeyIntrospectionResource {

  @Inject ApiKeyService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "What an API key may do",
      description =
          "PLATFORM_ADMIN only; the gateway's question. Active with the business, the tier and the"
              + " stores, or inactive with why: unknown, revoked, expired, tenant suspended.")
  @POST
  @Path("/introspect")
  public ApiResponse<ApiKeyDtos.IntrospectionResponse> introspect(
      ApiKeyDtos.IntrospectRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    if (req == null || req.key() == null || req.key().isBlank()) {
      throw ApiException.badRequest("API_KEY_MISSING", "The key to ask about");
    }
    return ApiResponse.ok(service.introspect(req.key().trim()));
  }
}
