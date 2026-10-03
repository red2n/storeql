package com.storeql.order.service;

import com.storeql.order.domain.OrderSettings;
import com.storeql.order.repo.OrderSettingsRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * The waits a business sets on an order: how long an unpaid order is held and how long an order
 * waits for a price. What belongs to the whole business is set by a caller held to no store.
 */
@ApplicationScoped
public class OrderSettingsService {

  @Inject OrderSettingsRepository repo;

  /**
   * The business's settings, all off while it has set none.
   *
   * @param tenantId owning tenant
   */
  public OrderSettings get(UUID tenantId) {
    return repo.find(tenantId).orElse(OrderSettings.NONE);
  }

  /**
   * Sets the unpaid-order limit, leaving the price wait as it is.
   *
   * @param hours whole hours of at least 1, or null for the platform default
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY}; 400 {@code ORDER_PENDING_LIMIT_INVALID}
   */
  public OrderSettings setPendingLimit(UUID tenantId, Integer hours, TenantContext ctx) {
    requireBusinessWide(ctx);
    OrderSettings next = get(tenantId).withPendingLimit(hours);
    repo.save(tenantId, next, ctx.userId());
    return next;
  }

  /**
   * Sets the price-wait limits, leaving the unpaid-order limit as it is.
   *
   * @param flag minutes before a manager is told, or null for never
   * @param cancel minutes before the order is cancelled, or null for never
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY}; 400 {@code ORDER_PRICE_WAIT_INVALID}
   */
  public OrderSettings setPriceWait(
      UUID tenantId, Integer flag, Integer cancel, TenantContext ctx) {
    requireBusinessWide(ctx);
    OrderSettings next = get(tenantId).withPriceWait(flag, cancel);
    repo.save(tenantId, next, ctx.userId());
    return next;
  }

  private static void requireBusinessWide(TenantContext ctx) {
    if (!ctx.storeIds().isEmpty()) {
      throw ApiException.forbidden(
          "BUSINESS_WIDE_ONLY",
          "a limit that applies to the whole business is set by a caller held to no store");
    }
  }
}
