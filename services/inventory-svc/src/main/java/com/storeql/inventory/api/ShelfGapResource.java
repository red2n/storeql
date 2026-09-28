package com.storeql.inventory.api;

import com.storeql.inventory.dto.ShelfDtos;
import com.storeql.inventory.mapper.ShelfMappers;
import com.storeql.inventory.service.ShelfSpaceService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Replenishment read from the shelf rather than from a reorder level (07.17).
 *
 * <p>Deliberately separate from {@code /reports/low-stock}: that one asks whether the business will
 * run out, which is the warehouse's question. This one asks whether the bay looks full, which is
 * the shop floor's, and the two differ by exactly the shelf — 40 units on hand is plenty for a bay
 * that holds 12 and a gap in one that holds 60.
 *
 * <p>The capacity comes from product-svc's published planograms, projected here from the event
 * rather than read across a service boundary.
 *
 * <p><b>Who may read it:</b> OWNER, MANAGER, STOREKEEPER and PLATFORM_ADMIN — the shop floor's own
 * report, not a cashier's job, so a CASHIER is refused even though the common-web filter's staff
 * tier admits the path for every staff role. A caller held to stores (SJ-D74) reads only their own:
 * {@link TenantContext#requireStoreAccess} is asserted here as well as by {@code StoreScopeFilter},
 * so the check holds even if the filter did not run. A store id that belongs to another business is
 * not refused — the tenant filter in the query alone decides that, and answers with no rows rather
 * than 403 or 404.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Shelf Gaps")
public class ShelfGapResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 20;

  @Inject ShelfSpaceService service;
  @Inject TenantContext ctx;

  /**
   * What it would take to fill a store's shelves, deepest gap first.
   *
   * @param storeId the store whose shelves to read (query parameter, required)
   * @param limit rows to return, 1..100 (query parameter)
   * @return rows ordered by gap, deepest first
   */
  @Operation(
      summary = "What it would take to fill a store's shelves",
      description =
          "Capacity is summed across fixtures, because a line sited on a gondola and an end cap has"
              + " two bays to fill. Availability is on hand less what is held for an order — the same"
              + " definition the levels list uses, so two screens never disagree about one number. A"
              + " line with a shelf and no stock at all still appears: an empty bay is the case this"
              + " report exists for. `belowMinimum` flags a bay that looks picked over now, whatever"
              + " the reorder level says. Read by OWNER, MANAGER, STOREKEEPER and PLATFORM_ADMIN; a"
              + " CASHIER is refused, and a caller held to stores reads only the ones they keep.")
  @APIResponse(responseCode = "200", description = "Rows ordered by gap, deepest first")
  @APIResponse(responseCode = "400", description = "Missing or malformed storeId")
  @APIResponse(responseCode = "403", description = "FORBIDDEN (a cashier), or STORE_ACCESS_DENIED")
  @GET
  @Path("/reports/shelf-gaps")
  public Response shelfGaps(
      @QueryParam("storeId") String storeId, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER");
    UUID storeIdValue = Parsing.uuid(storeId, "storeId");
    ctx.requireStoreAccess(storeIdValue);
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    List<ShelfDtos.ShelfGapResponse> rows =
        ShelfMappers.gaps(service.gaps(ctx.requireTenantId(), storeIdValue, clamped));
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }
}
