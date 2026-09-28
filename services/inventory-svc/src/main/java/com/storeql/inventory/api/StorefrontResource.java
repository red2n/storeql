package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.OnlyLeft;
import com.storeql.inventory.dto.Dtos.AvailabilityResponse;
import com.storeql.inventory.service.InventoryService;
import com.storeql.inventory.service.StorefrontSettingsService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public storefront availability. Returns a per-variant in-stock flag for a store so the guest shop
 * can show "Available / Out of stock" when a store hides prices. No quantities are exposed, save
 * one deliberate exception: {@code onlyLeft}, a business's own "only N left" count, off until the
 * business sets a threshold and never shown above it. Tenant comes from {@code X-Tenant-Id}
 * (gateway storefront whitelist); read-only, no identity required.
 */
@Path("/inventory/availability")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Storefront Availability")
public class StorefrontResource {

  @Inject InventoryService service;
  @Inject StorefrontSettingsService settings;
  @Inject TenantContext ctx;

  /**
   * Gets per-variant availability for a store.
   *
   * <p>Public, read-only in-stock/out-of-stock flag per variant, plus {@code onlyLeft} when a store
   * is named, the business has set a threshold, and what remains there is a positive whole number
   * at or below it — see {@link OnlyLeft#compute}. No other quantity is exposed.
   *
   * @param store the store (query parameter)
   * @throws com.storeql.web.ApiException {@code 400} store must be a UUID
   */
  @Operation(
      summary = "Get per-variant availability for a store",
      description =
          "Public, read-only in-stock/out-of-stock flag per variant, plus \"only N left\" at or"
              + " below a business's own threshold when one is set and a store is named. No other"
              + " quantity is exposed.")
  @APIResponse(responseCode = "400", description = "store must be a UUID")
  @GET
  public ApiResponse<List<AvailabilityResponse>> availability(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = null;
    if (store != null && !store.isBlank()) {
      try {
        storeId = Ids.parse(store.trim());
      } catch (IllegalArgumentException e) {
        throw new ApiException(
            400, "INVALID_STORE", "store must be a UUID", java.util.List.of(), e);
      }
    }
    boolean storeNamed = storeId != null;
    Integer threshold = storeNamed ? settings.thresholdFor(tenantId) : null;
    List<AvailabilityResponse> items =
        service.availability(tenantId, storeId).stream()
            .map(
                a ->
                    new AvailabilityResponse(
                        a.variantId().toString(),
                        a.inStock(),
                        a.dropship(),
                        OnlyLeft.compute(storeNamed, a.available(), threshold, a.dropship())))
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }
}
