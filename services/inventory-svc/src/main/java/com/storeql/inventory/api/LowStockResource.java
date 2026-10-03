package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.LowStockRowResponse;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
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
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The live low-stock report — third of the four in {@code docs/reporting-api-gap-analysis.md}.
 *
 * <p>Deliberately separate from {@code /planning/suggestions}: that endpoint returns the min/max
 * engine's persisted output from the last planning run and covers only manual thresholds. This one
 * reads live and honours safety stock and reorder points too.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Low Stock")
public class LowStockResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 20;

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Items below a configured reorder level.
   *
   * <p>Live, per item, against that item's own level rather than a flat number. Where a threshold,
   * a safety stock and a reorder point are all configured, the highest wins and the response names
   * which one bound. Availability is on hand minus held reservations, matching the levels list.
   * Items that have run out entirely are included — they are the most urgent case, and they have no
   * batch rows at all.
   *
   * @param storeId a named store (query parameter): checked against the caller's stores, else 403;
   *     unnamed, a caller held to no store reads the whole business and a caller held to some reads
   *     exactly those, combined
   * @param limit the limit (query parameter)
   * @return rows ordered by shortfall, deepest first
   * @throws com.storeql.web.ApiException {@code 400} malformed storeId; {@code 403}
   *     STORE_ACCESS_DENIED for a named store the caller does not keep
   */
  @Operation(
      summary = "Items below a configured reorder level",
      description =
          "Live, per item, against that item's own level rather than a flat number. Where a"
              + " threshold, a safety stock and a reorder point are all configured, the highest"
              + " wins and the response names which one bound. Availability is on hand minus held"
              + " reservations, matching the levels list. Items that have run out entirely are"
              + " included — they are the most urgent case, and they have no batch rows at all.")
  @APIResponse(responseCode = "200", description = "Rows ordered by shortfall, deepest first")
  @APIResponse(responseCode = "400", description = "Malformed storeId")
  @GET
  @Path("/reports/low-stock")
  public Response lowStock(
      @QueryParam("storeId") String storeId, @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    UUID parsed = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(parsed);
    List<LowStockRowResponse> rows =
        service.lowStockReport(ctx.requireTenantId(), stores, clamped).stream()
            .map(Mappers::toLowStockRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }
}
