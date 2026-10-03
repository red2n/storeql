package com.storeql.inventory.api;

import com.storeql.inventory.domain.Domain.ShrinkageGrouping;
import com.storeql.inventory.dto.Dtos.ShrinkageRowResponse;
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
 * The stock write-off (shrinkage) report.
 *
 * <p>Named in the reporting gap analysis as designed-but-unbuilt, and only answerable since
 * adjustments started carrying a reason code and an actor (SJ-D4): before that there was nothing to
 * group by, and the seeded reason-code vocabulary — DAMAGED, THEFT, FOUND, EXPIRY — had no reader.
 *
 * <p>Lives under {@code /admin/inventory} rather than in reporting-svc because the question is
 * entirely inside this service's own data. reporting-svc's {@code movement_events} projection
 * carries neither column, and only the manual adjust path publishes an event at all — a report
 * built there would silently omit cycle-count variances, which is where a good deal of real
 * shrinkage is discovered.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Shrinkage")
public class ShrinkageResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 20;

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Stocks write-offs, grouped.
   *
   * <p>Sums ADJUST movements over a period. Group by REASON to see what stock is being lost to, by
   * ACTOR to see who is writing it off, or by STORE to compare sites. Write-offs and finds are
   * reported separately, because a store that wrote off 100 units and found 100 others is not the
   * same as one that did nothing.
   *
   * @param storeId a named store (query parameter): checked against the caller's stores, else 403;
   *     unnamed, a caller held to no store reads the whole business and a caller held to some reads
   *     exactly those, combined
   * @param from the from (query parameter)
   * @param to the to (query parameter)
   * @param groupBy the group by (query parameter)
   * @return one row per group, heaviest write-off first
   * @throws com.storeql.web.ApiException {@code 400} unknown groupBy, unparseable timestamp, or
   *     from is not before to; {@code 403} STORE_ACCESS_DENIED for a named store the caller does
   *     not keep
   */
  @Operation(
      summary = "Stock write-offs, grouped",
      description =
          "Sums ADJUST movements over a period. Group by REASON to see what stock is being lost to,"
              + " by ACTOR to see who is writing it off, or by STORE to compare sites. Write-offs"
              + " and finds are reported separately, because a store that wrote off 100 units and"
              + " found 100 others is not the same as one that did nothing.")
  @APIResponse(responseCode = "200", description = "One row per group, heaviest write-off first")
  @APIResponse(
      responseCode = "400",
      description = "Unknown groupBy, unparseable timestamp, or from is not before to")
  @GET
  @Path("/reports/shrinkage")
  public Response shrinkage(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("groupBy") String groupBy) {
    UUID parsed = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(parsed);
    List<ShrinkageRowResponse> rows =
        service
            .shrinkageReport(
                ctx.requireTenantId(),
                stores,
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"),
                grouping(groupBy))
            .stream()
            .map(Mappers::toShrinkageRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /**
   * Whiches variants a write-off total is made of.
   *
   * <p>The variants behind a summary row, so an investigation can go from 'this member of staff
   * wrote off 400 units' to what they actually wrote off. Filter by reasonCode and/or actorId to
   * drill into one line of the grouped report.
   *
   * @param storeId a named store (query parameter): checked against the caller's stores, else 403;
   *     unnamed, a caller held to no store reads the whole business and a caller held to some reads
   *     exactly those, combined
   * @param from the from (query parameter)
   * @param to the to (query parameter)
   * @param reasonCode the reason code (query parameter)
   * @param actorId the actor id (query parameter)
   * @param limit the limit (query parameter)
   * @return one row per variant, heaviest write-off first
   * @throws com.storeql.web.ApiException {@code 400} unparseable timestamp, or from is not before
   *     to; {@code 403} STORE_ACCESS_DENIED for a named store the caller does not keep
   */
  @Operation(
      summary = "Which variants a write-off total is made of",
      description =
          "The variants behind a summary row, so an investigation can go from 'this member of staff"
              + " wrote off 400 units' to what they actually wrote off. Filter by reasonCode and/or"
              + " actorId to drill into one line of the grouped report.")
  @APIResponse(responseCode = "200", description = "One row per variant, heaviest write-off first")
  @APIResponse(
      responseCode = "400",
      description = "Unparseable timestamp, or from is not before to")
  @GET
  @Path("/reports/shrinkage/by-variant")
  public Response shrinkageByVariant(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("reasonCode") String reasonCode,
      @QueryParam("actorId") String actorId,
      @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    UUID parsed = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(parsed);
    List<ShrinkageRowResponse> rows =
        service
            .shrinkageByVariant(
                ctx.requireTenantId(),
                stores,
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"),
                reasonCode == null || reasonCode.isBlank()
                    ? null
                    : reasonCode.trim().toUpperCase(Locale.ROOT),
                Parsing.optionalUuid(actorId, "actorId"),
                clamped)
            .stream()
            .map(Mappers::toShrinkageRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /** Defaults to REASON — "what are we losing stock to?" is the question people open this with. */
  private static ShrinkageGrouping grouping(String raw) {
    if (raw == null || raw.isBlank()) return ShrinkageGrouping.REASON;
    try {
      return ShrinkageGrouping.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "INVENTORY_INVALID_GROUPING",
          "groupBy must be REASON, ACTOR or STORE — got: " + raw,
          List.of(),
          e);
    }
  }
}
