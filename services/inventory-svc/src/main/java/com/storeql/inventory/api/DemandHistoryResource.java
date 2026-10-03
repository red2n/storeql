package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.AggregateRequest;
import com.storeql.inventory.dto.Dtos.AggregateResult;
import com.storeql.inventory.dto.Dtos.DemandBucketResponse;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Demand history: SALE-movement aggregation into buckets. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Demand History")
public class DemandHistoryResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Aggregates SALE movements into demand buckets.
   *
   * <p>Upserts per-store/variant demand buckets (day/week/month) from SALE-type stock movements,
   * feeding safety-stock and ROP/EOQ computation.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Aggregate SALE movements into demand buckets",
      description =
          "Upserts per-store/variant demand buckets (day/week/month) from SALE-type stock"
              + " movements, feeding safety-stock and ROP/EOQ computation.")
  @APIResponse(responseCode = "200", description = "Aggregate SALE movements into demand buckets")
  @POST
  @Path("/demand/aggregate")
  public ApiResponse<AggregateResult> aggregateDemand(AggregateRequest req) {
    // The body is optional (every store, by week); one that is sent is held to the rules.
    if (req != null) Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId =
        req != null && req.storeId() != null && !req.storeId().isBlank()
            ? uuid(req.storeId(), "storeId")
            : null;
    String bucketType = req != null && req.bucketType() != null ? req.bucketType() : "WEEK";
    LocalDate since = null;
    if (req != null && req.since() != null && !req.since().isBlank()) {
      since = parseDate(req.since());
    }
    int bucketsUpserted = service.aggregateDemand(tenantId, storeId, bucketType, since);
    return ApiResponse.ok(new AggregateResult(bucketsUpserted, bucketType));
  }

  /**
   * Lists demand history buckets.
   *
   * <p>Filterable by store, variant, and bucket type.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @param bucketType the bucket type (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(
      summary = "List demand history buckets",
      description = "Filterable by store, variant, and bucket type.")
  @APIResponse(responseCode = "200", description = "List demand history buckets")
  @GET
  @Path("/demand/history")
  public ApiResponse<List<DemandBucketResponse>> listDemandHistory(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("bucket_type") String bucketType,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    String bt = bucketType == null || bucketType.isBlank() ? null : bucketType;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listDemandHistory(tenantId, storeId, variantId, bt, limit).stream()
            .map(Mappers::toDemandBucket)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }

  private static LocalDate parseDate(String s) {
    return com.storeql.web.Parsing.date(s, "expiryDate");
  }
}
