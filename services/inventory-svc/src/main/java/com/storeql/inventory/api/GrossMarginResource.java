package com.storeql.inventory.api;

import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.GrossMarginService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gross margin and GMROI (19.7). Under {@code /admin/inventory/reports}, which the authorization
 * filter keeps to management (SJ-D19), like the stock-turn report beside it.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Gross Margin")
public class GrossMarginResource {

  @Inject GrossMarginService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Gross margin and GMROI per store or per product",
      description =
          "What the sales in the window earned, net of VAT, discounts and returns, against the cost"
              + " of the batches they drew down (less the cost of what came back) and the average"
              + " value of the stock held to make them. grossMargin = revenue - cogs; marginPercent"
              + " = grossMargin / revenue; gmroi = grossMargin / averageValue over the window, and"
              + " annualisedGmroi scales it to a year. Lowest margin first. Revenue arrives with each"
              + " fulfilled line; a sale from before that carried none is counted in"
              + " unpricedSaleQty, its cost still in cogs, so the margin is understated rather than"
              + " invented. from and to are required ISO-8601 instants; groupBy is STORE or"
              + " VARIANT. historyComplete is false when archived movements make the average a"
              + " floor.")
  @APIResponse(responseCode = "200", description = "Rows lowest margin first, with the caveats")
  @APIResponse(responseCode = "400", description = "Missing or bad from/to, or an unknown groupBy")
  @APIResponse(responseCode = "403", description = "Not a management role")
  @GET
  @Path("/reports/gross-margin")
  public Response grossMargin(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("groupBy") String groupBy,
      @QueryParam("limit") Integer limit) {
    UUID parsed = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(parsed);
    var report =
        service.report(
            ctx.requireTenantId(),
            stores,
            Parsing.instant(from, "from"),
            Parsing.instant(to, "to"),
            StockTurnResource.turnGrouping(groupBy),
            limit == null ? 20 : Math.max(1, Math.min(100, limit)));
    return Response.ok(
            ApiResponse.ok(
                Mappers.toGrossMarginReport(report), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
