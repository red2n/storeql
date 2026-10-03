package com.storeql.iam.api;

import com.storeql.iam.dto.SecurityEventDtos;
import com.storeql.iam.service.SecurityEventService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code GET /auth/admin/security-events}: the account-security trail of a business's logins — sign
 * in failures, second-factor lockouts, password changes, key and identity-provider changes — for
 * its owner or manager; every business's for the platform administrator.
 */
@Path("/auth/admin/security-events")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Security events")
public class SecurityEventResource {

  @Inject SecurityEventService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Security events of the business's logins",
      description =
          "OWNER or MANAGER (a manager held to stores is refused: the trail is business-wide), the"
              + " logins of their own business only; PLATFORM_ADMIN reads all. Newest first,"
              + " cursor-paginated (?after=&limit=), filters type, userId, from (inclusive), to"
              + " (exclusive). Never a secret, hash, token, IP address or link.")
  @APIResponse(responseCode = "200", description = "A page of events")
  @APIResponse(
      responseCode = "400",
      description =
          "SECURITY_EVENT_TYPE_INVALID, SECURITY_EVENT_PERIOD_INVALID, INVALID_UUID, INVALID_DATE")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN (not an owner or manager), BUSINESS_WIDE_ONLY (a manager held to stores)")
  @GET
  public ApiResponse<SecurityEventDtos.Page> list(
      @QueryParam("type") String type,
      @QueryParam("userId") String userId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID tenant;
    if (ctx.hasRole("PLATFORM_ADMIN")) {
      tenant = null;
    } else {
      ctx.requireAnyRole("OWNER", "MANAGER");
      if (!ctx.storeIds().isEmpty()) {
        // Business-wide, not a store the caller does not keep: no store is named here.
        throw ApiException.forbidden(
            "BUSINESS_WIDE_ONLY",
            "The security trail is business-wide, so it needs a caller who is not held to stores");
      }
      tenant = ctx.requireTenantId();
    }
    SecurityEventService.Page page =
        service.list(
            tenant,
            type,
            Parsing.optionalUuid(userId, "userId"),
            Parsing.optionalInstant(from, "from"),
            Parsing.optionalInstant(to, "to"),
            Parsing.optionalUuid(after, "after"),
            limit);
    return ApiResponse.ok(
        new SecurityEventDtos.Page(
            page.items().stream()
                .map(
                    e ->
                        new SecurityEventDtos.EventResponse(
                            e.id().toString(),
                            e.type(),
                            e.userId() == null ? null : e.userId().toString(),
                            e.email(),
                            e.tenantId() == null ? null : e.tenantId().toString(),
                            e.detail(),
                            e.at()))
                .toList(),
            page.nextCursor()));
  }
}
