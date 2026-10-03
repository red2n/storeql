package com.storeql.tenant.api;

import com.storeql.tenant.dto.AuditDtos.EntryResponse;
import com.storeql.tenant.mapper.AuditMappers;
import com.storeql.tenant.service.AuditService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The business's admin change log. Under {@code /admin}, so the shared filter admits management
 * only.
 */
@ApplicationScoped
@Path("/admin/tenant/audit")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Audit")
public class AuditResource {

  @Inject AuditService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Who changed a store's status or till setting, or a staff assignment or role",
      description =
          "Newest first, cursor-paginated (?after=<meta.nextCursor>&limit=1-100). Filters: type,"
              + " actor (user id), store (store id), from and to (ISO-8601 instants). A caller"
              + " held to stores reads those stores' entries and the business-wide ones. Management"
              + " only.")
  @APIResponse(responseCode = "200", description = "The page of entries")
  @APIResponse(
      responseCode = "400",
      description = "AUDIT_TYPE_INVALID, AUDIT_RANGE_INVALID, INVALID_UUID or INVALID_DATE")
  @APIResponse(responseCode = "403", description = "STORE_ACCESS_DENIED for a store not theirs")
  @GET
  public ApiResponse<List<EntryResponse>> list(
      @QueryParam("type") String type,
      @QueryParam("actor") String actor,
      @QueryParam("store") String store,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = service.list(ctx, type, actor, store, from, to, after, Cursor.clampLimit(limit));
    return ApiResponse.ok(
        page.items().stream().map(AuditMappers::toEntry).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }
}
