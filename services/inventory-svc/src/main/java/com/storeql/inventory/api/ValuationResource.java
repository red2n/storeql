package com.storeql.inventory.api;

import com.storeql.inventory.domain.Domain.ValuationGrouping;
import com.storeql.inventory.dto.Dtos.ValuationRowResponse;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiException;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The stock valuation report — what the inventory on hand is worth.
 *
 * <p>Named in {@code docs/reporting-api-gap-analysis.md} as designed but unbuilt. It is the second
 * of the four reports listed there; the shrinkage report was the first.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Valuation")
public class ValuationResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 20;

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Values the stock on hand.
   *
   * <p>Values remaining batch quantities on the costing basis configured per store and variant:
   * FIFO values each batch at its own cost, AVERAGE values the holding at the configured standard
   * cost. Group by STORE for a rollup or VARIANT for the detail. Quantities that carry no cost are
   * reported as unvaluedQty rather than valued at zero, so the figure is never silently
   * understated.
   *
   * @param storeId a named store (query parameter): checked against the caller's stores, else 403;
   *     unnamed, a caller held to no store reads the whole business and a caller held to some reads
   *     exactly those, combined
   * @param groupBy the group by (query parameter)
   * @param limit the limit (query parameter)
   * @return rows ordered by value, largest holding first
   * @throws com.storeql.web.ApiException {@code 400} unknown groupBy or malformed storeId; {@code
   *     403} STORE_ACCESS_DENIED for a named store the caller does not keep
   */
  @Operation(
      summary = "Value the stock on hand",
      description =
          "Values remaining batch quantities on the costing basis configured per store and variant:"
              + " FIFO values each batch at its own cost, AVERAGE values the holding at the"
              + " configured standard cost. Group by STORE for a rollup or VARIANT for the detail."
              + " Quantities that carry no cost are reported as unvaluedQty rather than valued at"
              + " zero, so the figure is never silently understated.")
  @APIResponse(responseCode = "200", description = "Rows ordered by value, largest holding first")
  @APIResponse(responseCode = "400", description = "Unknown groupBy or malformed storeId")
  @GET
  @Path("/reports/valuation")
  public Response valuation(
      @QueryParam("storeId") String storeId,
      @QueryParam("groupBy") String groupBy,
      @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    UUID parsed = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(parsed);
    List<ValuationRowResponse> rows =
        service.valuationReport(ctx.requireTenantId(), stores, grouping(groupBy), clamped).stream()
            .map(Mappers::toValuationRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /** Defaults to STORE: "what is our stock worth?" is asked at site level before line level. */
  private static ValuationGrouping grouping(String raw) {
    if (raw == null || raw.isBlank()) return ValuationGrouping.STORE;
    try {
      return ValuationGrouping.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "INVENTORY_INVALID_GROUPING",
          "groupBy must be STORE or VARIANT — got: " + raw,
          List.of(),
          e);
    }
  }
}
