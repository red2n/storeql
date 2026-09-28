package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.ForecastResponse;
import com.storeql.inventory.dto.Dtos.ForecastRunRequest;
import com.storeql.inventory.dto.Dtos.ForecastRunResponse;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.ForecastService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The statistical demand forecast (06.x): run it for a store, read what it says. Under {@code
 * /admin/inventory}, so the storekeeper who plans the store's orders can run it; a store-scoped
 * caller is held to their stores by {@link StoreScopeFilter} for the store a request names and here
 * for the one in a path.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Demand Forecast")
public class ForecastResource {

  @Inject ForecastService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Run the demand forecast for a store",
      description =
          "Folds yesterday's sales into the daily demand buckets, then forecasts every variant with"
              + " history at the store (or one variant) over the horizon, replacing earlier forecasts."
              + " The method is chosen by the shape of the demand: exponential smoothing with a"
              + " day-of-week profile for a steady seller, Croston with the Syntetos–Boylan"
              + " correction for an intermittent one, a plain mean under fourteen days of history."
              + " Each forecast carries its own accuracy from a hold-out of its history — MAPE,"
              + " bias and MASE, null where they cannot be honestly computed — and the reorder-point"
              + " computation takes its expected demand over the lead time from here when a forecast"
              + " exists. Returns how many variants were forecast, by which method, and their mean"
              + " MAPE.")
  @APIResponse(responseCode = "200", description = "The run's summary")
  @APIResponse(
      responseCode = "400",
      description = "storeId or variantId is not a UUID, or horizonDays is outside 1..365")
  @POST
  @Path("/forecasts/run")
  public ApiResponse<ForecastRunResponse> run(ForecastRunRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId =
        req.variantId() == null || req.variantId().isBlank()
            ? null
            : uuid(req.variantId(), "variantId");
    return ApiResponse.ok(
        Mappers.toForecastRun(service.run(tenantId, storeId, variantId, req.horizonDays())));
  }

  @Operation(
      summary = "The forecasts at a store",
      description =
          "One row per forecast variant, newest run first, each with its method, expected demand"
              + " over the next 7 and 28 days and its accuracy; the daily points are on the single"
              + " forecast. A caller assigned to one store reads that store without naming it.")
  @APIResponse(responseCode = "200", description = "Forecasts")
  @APIResponse(responseCode = "400", description = "No store named, or one that is not a UUID")
  @GET
  @Path("/forecasts")
  public ApiResponse<List<ForecastResponse>> list(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = ctx.scopeStore(store == null || store.isBlank() ? null : uuid(store, "store"));
    if (storeId == null) {
      throw ApiException.badRequest("STORE_REQUIRED", "Name the store to read forecasts for");
    }
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    int limit = limitParam == null || limitParam < 1 ? 100 : Math.min(limitParam, 500);
    return ApiResponse.ok(
        service.list(tenantId, storeId, variantId, limit).stream()
            .map(f -> Mappers.toForecast(f, false))
            .toList());
  }

  @Operation(
      summary = "One variant's forecast at a store, with its daily points",
      description =
          "The expected quantity for each day of the horizon, the day-of-week profile when there"
              + " is one, and the forecast's report on itself.")
  @APIResponse(responseCode = "200", description = "The forecast")
  @APIResponse(responseCode = "404", description = "No forecast has been run for this variant here")
  @GET
  @Path("/forecasts/{storeId}/{variantId}")
  public ApiResponse<ForecastResponse> get(
      @PathParam("storeId") UUID storeId, @PathParam("variantId") UUID variantId) {
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        Mappers.toForecast(service.get(ctx.requireTenantId(), storeId, variantId), true));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
