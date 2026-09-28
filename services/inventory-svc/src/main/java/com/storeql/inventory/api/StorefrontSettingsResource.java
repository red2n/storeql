package com.storeql.inventory.api;

import com.storeql.inventory.domain.Domain.StorefrontStockSettings;
import com.storeql.inventory.dto.Dtos.StorefrontStockSettingsRequest;
import com.storeql.inventory.dto.Dtos.StorefrontStockSettingsResponse;
import com.storeql.inventory.service.StorefrontSettingsService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * "Only N left" on the storefront: a business-wide threshold, off until an owner — or a manager
 * held to no store, since this is a business-wide setting, not one store's — sets it. {@code GET
 * /inventory/availability} reads it (via {@link StorefrontSettingsService}) to decide whether a
 * store's available quantity is shown as a whole-unit count; see {@link
 * com.storeql.inventory.domain.OnlyLeft}.
 *
 * <p>Under {@code /admin/inventory/}, so the staff tier already admits any staff role for the
 * {@code GET} (the read any staff of the business is meant to have); {@code PUT} narrows further,
 * in this class, to the business-wide roles named above — a store-held manager, a storekeeper and a
 * cashier are refused here even though the filter let them through.
 */
@Path("/admin/inventory/storefront-settings")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Storefront Settings")
public class StorefrontSettingsResource {

  @Inject StorefrontSettingsService service;
  @Inject TenantContext ctx;

  /**
   * Reads the business's current "only N left" threshold.
   *
   * <p>Any staff role of the business may read it — off ({@code lowStockThreshold: null}) until an
   * owner or a business-wide manager sets one.
   */
  @Operation(
      summary = "Read the storefront low-stock threshold",
      description =
          "Off (lowStockThreshold null) until an owner, or a manager held to no store, sets one."
              + " Any staff of the business may read it.")
  @APIResponse(responseCode = "200", description = "The current setting")
  @GET
  public ApiResponse<StorefrontStockSettingsResponse> get() {
    return ApiResponse.ok(
        toResponse(service.get(ctx.requireTenantId())), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Sets or clears the threshold.
   *
   * @param req the new threshold, or null to switch the feature off
   * @throws ApiException {@code 403} FORBIDDEN unless the caller is OWNER, or a MANAGER held to no
   *     store; {@code 400} INVENTORY_LOW_STOCK_THRESHOLD_INVALID outside 1..1000
   */
  @Operation(
      summary = "Set the storefront low-stock threshold",
      description =
          "A business-wide setting: OWNER, or a MANAGER held to no store. A store-held manager, a"
              + " storekeeper and a cashier are refused.")
  @APIResponse(responseCode = "200", description = "The setting as stored")
  @APIResponse(responseCode = "400", description = "INVENTORY_LOW_STOCK_THRESHOLD_INVALID")
  @APIResponse(responseCode = "403", description = "FORBIDDEN")
  @PUT
  public ApiResponse<StorefrontStockSettingsResponse> put(StorefrontStockSettingsRequest req) {
    requireOwnerOrBusinessWideManager();
    Validations.validate(req);
    StorefrontStockSettings s =
        service.update(ctx.requireTenantId(), ctx.requireUserId(), req.lowStockThreshold());
    return ApiResponse.ok(toResponse(s), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * OWNER always; PLATFORM_ADMIN too, on the same footing the rest of this service's business-wide,
   * management-only writes give it (e.g. {@code YieldResource}'s template writes, {@code
   * BondResource}'s rates) — it is held to no store either. A MANAGER only when held to no store:
   * this setting has no per-store meaning, so a store-held manager is refused exactly as a
   * storekeeper or cashier is.
   */
  private void requireOwnerOrBusinessWideManager() {
    if (ctx.hasRole("OWNER") || ctx.hasRole("PLATFORM_ADMIN")) {
      return;
    }
    if (ctx.hasRole("MANAGER") && ctx.storeIds().isEmpty()) {
      return;
    }
    throw ApiException.forbidden(
        "FORBIDDEN", "Only the owner or a business-wide manager may change this setting");
  }

  private static StorefrontStockSettingsResponse toResponse(StorefrontStockSettings s) {
    return new StorefrontStockSettingsResponse(
        s.lowStockThreshold(),
        s.updatedAt() == null ? null : s.updatedAt().toString(),
        s.updatedBy() == null ? null : s.updatedBy().toString());
  }
}
