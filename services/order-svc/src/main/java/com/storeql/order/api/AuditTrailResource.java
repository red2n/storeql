package com.storeql.order.api;

import com.storeql.order.dto.Dtos.AuditEventResponse;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.AuditTrailService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
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
 * The business audit trail (20.11): every discount, void, no-sale, cancellation and return this
 * service recorded, and every offline till sale recorded although a recall or an unfit scale would
 * have stopped it, newest first, naming who did it.
 *
 * <p>Under {@code /admin/} so the shared {@code AdminAuthorizationFilter} gates it by path: a
 * cashier or storekeeper is refused before this class is reached, and a method added later cannot
 * be left open by a forgotten role check. Read-only by construction — there is no method here that
 * writes, and the logs it reads are append-only.
 */
@RequestScoped
@Path("/admin/audit")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Audit trail")
public class AuditTrailResource {

  @Inject AuditTrailService svc;
  @Inject TenantContext ctx;

  /**
   * One page of the trail.
   *
   * @param store one store, or every store the caller may see
   * @param actor one member of staff, by user id
   * @param type DISCOUNT, VOID, NO_SALE, CANCEL, RETURN, PRICED, OFFLINE_SALE_OF_RECALLED_ITEM or
   *     OFFLINE_SALE_ON_UNFIT_SCALE; every log when omitted
   * @param from inclusive start as an ISO-8601 instant
   * @param to exclusive end as an ISO-8601 instant
   * @param after the previous page's {@code meta.nextCursor}
   * @param limit page size, 1..100, default 20
   * @return the events newest first, with {@code meta.nextCursor} while more remain
   * @throws com.storeql.web.ApiException {@code 400} for an unknown type, a malformed cursor, date
   *     or id, or a period that ends before it starts; {@code 403} when the caller is not
   *     management or asks for a store outside their scope
   */
  @Operation(
      summary = "The business audit trail",
      description =
          "Every discount granted, sale voided, drawer opened without a sale, order cancelled and"
              + " return taken, and every till sale replayed from the offline queue that was"
              + " recorded although a recall covered a line, or a scale not fit for trade weighed"
              + " one, when it was rung up — newest first, each naming the member of staff"
              + " responsible."
              + " Filterable by store, actor, type and period; cursor-paginated. Management-only.")
  @APIResponse(responseCode = "200", description = "A page of events")
  @APIResponse(
      responseCode = "400",
      description = "Unknown type, malformed cursor, date or id, or from is not before to")
  @APIResponse(responseCode = "403", description = "Not management, or a store outside scope")
  @GET
  @Path("/events")
  public ApiResponse<List<AuditEventResponse>> events(
      @QueryParam("store") String store,
      @QueryParam("actor") String actor,
      @QueryParam("type") String type,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page =
        svc.list(
            ctx,
            store,
            actor,
            type,
            Parsing.optionalInstant(from, "from"),
            Parsing.optionalInstant(to, "to"),
            after,
            Cursor.clampLimit(limit));
    return ApiResponse.ok(
        page.items().stream().map(Mappers::toDto).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }
}
