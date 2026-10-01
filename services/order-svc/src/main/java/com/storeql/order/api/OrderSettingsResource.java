package com.storeql.order.api;

import com.storeql.order.domain.OrderSettings;
import com.storeql.order.dto.Dtos.PendingLimitRequest;
import com.storeql.order.dto.Dtos.PendingLimitResponse;
import com.storeql.order.dto.Dtos.PriceWaitRequest;
import com.storeql.order.dto.Dtos.PriceWaitResponse;
import com.storeql.order.service.OrderSettingsService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The waits a business sets on an order, both on one row: how long an unpaid order is held
 * (fulfilment-overrides slice 2) and how long an order waits for a price (unit-pricing slice 5).
 * Management reads; changing them is for a caller held to no store. Both are off until set.
 */
@ApplicationScoped
@Path("/admin/orders/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Order settings")
public class OrderSettingsResource {

  @Inject OrderSettingsService svc;
  @Inject TenantContext ctx;

  @Inject
  @ConfigProperty(name = "storeql.order.pending-sweeper.ttl-hours", defaultValue = "24")
  int platformDefaultHours;

  /**
   * The unpaid-order limit in force.
   *
   * @return the business's own limit and the hours that apply
   */
  @Operation(summary = "Get the unpaid-order limit")
  @APIResponse(responseCode = "200", description = "The limit in force")
  @GET
  @Path("/pending-limit")
  public ApiResponse<PendingLimitResponse> getPendingLimit() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(pending(svc.get(ctx.requireTenantId())));
  }

  /**
   * Sets the unpaid-order limit.
   *
   * @param req the hours, or null for the platform default
   * @return the limit now in force
   */
  @Operation(summary = "Set the unpaid-order limit")
  @APIResponse(responseCode = "200", description = "The limit now in force")
  @APIResponse(responseCode = "400", description = "ORDER_PENDING_LIMIT_INVALID")
  @APIResponse(responseCode = "403", description = "BUSINESS_WIDE_ONLY for a caller held to stores")
  @PUT
  @Path("/pending-limit")
  public ApiResponse<PendingLimitResponse> putPendingLimit(PendingLimitRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Integer hours = req == null ? null : req.pendingLimitHours();
    return ApiResponse.ok(pending(svc.setPendingLimit(ctx.requireTenantId(), hours, ctx)));
  }

  /**
   * The price-wait limits in force.
   *
   * @return the two limits, null while off
   */
  @Operation(summary = "Get the price-wait limits")
  @APIResponse(responseCode = "200", description = "The limits in force")
  @GET
  @Path("/price-wait")
  public ApiResponse<PriceWaitResponse> getPriceWait() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(priceWait(svc.get(ctx.requireTenantId())));
  }

  /**
   * Sets the price-wait limits.
   *
   * @param req the minutes before a manager is told and before the order is cancelled
   * @return the limits now in force
   */
  @Operation(summary = "Set the price-wait limits")
  @APIResponse(responseCode = "200", description = "The limits now in force")
  @APIResponse(responseCode = "400", description = "ORDER_PRICE_WAIT_INVALID")
  @APIResponse(responseCode = "403", description = "BUSINESS_WIDE_ONLY for a caller held to stores")
  @PUT
  @Path("/price-wait")
  public ApiResponse<PriceWaitResponse> putPriceWait(PriceWaitRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Integer flag = req == null ? null : req.flagMinutes();
    Integer cancel = req == null ? null : req.cancelMinutes();
    return ApiResponse.ok(priceWait(svc.setPriceWait(ctx.requireTenantId(), flag, cancel, ctx)));
  }

  private PendingLimitResponse pending(OrderSettings s) {
    return new PendingLimitResponse(
        s.pendingLimitHours(), s.pendingHours(platformDefaultHours), s.pendingLimitHours() == null);
  }

  private static PriceWaitResponse priceWait(OrderSettings s) {
    return new PriceWaitResponse(s.priceWaitFlagMinutes(), s.priceWaitCancelMinutes());
  }
}
