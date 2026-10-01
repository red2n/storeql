package com.storeql.reporting.api;

import com.storeql.ids.Ids;
import com.storeql.reporting.mapper.Mappers;
import com.storeql.reporting.service.ReportingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Thin JAX-RS resource for the back-office inventory reports — validate, delegate to {@link
 * ReportingService}, wrap in envelope. No logic here.
 *
 * <p>Every figure served here comes from reporting-svc's own event-sourced projections, never from
 * a join against inventory-svc: the numbers are eventually consistent with the owning service.
 */
@Path("/admin/reports/inventory")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Inventory Reports")
public class AdminResource {

  @Inject ReportingService service;
  @Inject TenantContext ctx;

  /**
   * Gap #47: cross-store on-hand snapshot.
   *
   * <p>A store that is named is checked against the caller's assignment (403 {@code
   * STORE_ACCESS_DENIED} otherwise); with none named, a caller held to no store reads the whole
   * business and a caller held to some reads exactly those, added together (SJ-D74).
   */
  @Operation(
      summary = "Cross-store on-hand snapshot",
      description =
          "On-hand quantity per store/variant, projected from consumed stock-movement events."
              + " Optionally filtered by store and/or variant. A store that is named must be one"
              + " the caller may act at; naming none reads the caller's own stores added"
              + " together, or the whole business for an unrestricted caller.")
  @APIResponse(responseCode = "200", description = "On-hand rows plus a grand total")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
  @APIResponse(
      responseCode = "403",
      description = "storeId names a store the caller is not assigned to")
  @GET
  @Path("/on-hand")
  public ApiResponse<Object> onHand(
      @QueryParam("storeId") String storeId, @QueryParam("variantId") String variantId) {
    Set<UUID> stores = ctx.reportStores(storeId != null ? Ids.parse(storeId) : null);
    var rows =
        service.onHand(
            ctx.requireTenantId(), stores, variantId != null ? Ids.parse(variantId) : null);
    return ApiResponse.ok(Mappers.toOnHandReport(rows));
  }

  /**
   * Gap #48: supply/demand netting — on-hand + open in-transit supply lines.
   *
   * <p>Store scoping follows the same rule as {@link #onHand}.
   */
  @Operation(
      summary = "Supply/demand netting report",
      description =
          "Nets on-hand quantity against open in-transit supply lines per store/variant, to show"
              + " net available. Optionally filtered by store and/or variant. A store that is"
              + " named must be one the caller may act at; naming none reads the caller's own"
              + " stores added together, or the whole business for an unrestricted caller. The"
              + " window is from/to (inclusive yyyy-MM-dd, UTC); with no from it is the last 90"
              + " days (storeql.reporting.movement-stats.default-days) and it is never longer"
              + " than 366 days (max-days).")
  @APIResponse(responseCode = "200", description = "Netting rows")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
  @APIResponse(
      responseCode = "403",
      description = "storeId names a store the caller is not assigned to")
  @GET
  @Path("/supply-demand")
  public ApiResponse<Object> supplyDemand(
      @QueryParam("storeId") String storeId, @QueryParam("variantId") String variantId) {
    Set<UUID> stores = ctx.reportStores(storeId != null ? Ids.parse(storeId) : null);
    var result =
        service.supplyDemandNetting(
            ctx.requireTenantId(), stores, variantId != null ? Ids.parse(variantId) : null);
    return ApiResponse.ok(Mappers.toNettingReport(result));
  }

  /**
   * Gap #49: movement statistics bucketed by day/week/month.
   *
   * <p>Store scoping follows the same rule as {@link #onHand}.
   */
  @Operation(
      summary = "Movement statistics report",
      description =
          "Stock in/out/net movement totals per store/variant, bucketed by the given number of"
              + " days (e.g. 1 for daily, 7 for weekly, 30 for monthly-ish buckets). A store that"
              + " is named must be one the caller may act at; naming none reads the caller's own"
              + " stores added together, or the whole business for an unrestricted caller.")
  @APIResponse(responseCode = "200", description = "Movement statistic rows")
  @APIResponse(
      responseCode = "400",
      description =
          "storeId or variantId is not a valid UUID, from/to is not a yyyy-MM-dd date, or the"
              + " period is backwards or longer than the allowed window")
  @APIResponse(
      responseCode = "403",
      description = "storeId names a store the caller is not assigned to")
  @GET
  @Path("/movement-stats")
  public ApiResponse<Object> movementStats(
      @QueryParam("storeId") String storeId,
      @QueryParam("variantId") String variantId,
      @QueryParam("bucketDays") @DefaultValue("7") int bucketDays,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    Set<UUID> stores = ctx.reportStores(storeId != null ? Ids.parse(storeId) : null);
    var stats =
        service.movementStats(
            ctx.requireTenantId(),
            stores,
            variantId != null ? Ids.parse(variantId) : null,
            bucketDays,
            from == null || from.isBlank()
                ? null
                : Parsing.date(from, "from").atStartOfDay(ZoneOffset.UTC).toInstant(),
            to == null || to.isBlank()
                ? null
                : Parsing.date(to, "to").plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    return ApiResponse.ok(Mappers.toMovementStatsReport(stats));
  }
}
