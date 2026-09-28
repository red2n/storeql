package com.storeql.inventory.service;

import com.storeql.inventory.domain.Domain.StorefrontStockSettings;
import com.storeql.inventory.repo.StorefrontSettingsRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * "Only N left" on the storefront: a business-wide threshold, off until an owner or a business-wide
 * manager sets one. {@code who} may change it is the resource's job (the business-wide-only rule
 * needs {@code TenantContext.storeIds()}, which belongs at the door); this class is left with the
 * one business rule that is genuinely its own — the range — plus the read.
 */
@ApplicationScoped
public class StorefrontSettingsService {

  private static final int MIN_THRESHOLD = 1;
  private static final int MAX_THRESHOLD = 1000;

  @Inject StorefrontSettingsRepository repo;

  /**
   * The business's current setting, or the off default when none has ever been set.
   *
   * @param tenantId owning tenant
   */
  public StorefrontStockSettings get(UUID tenantId) {
    return repo.find(tenantId).orElseGet(() -> StorefrontStockSettings.off(tenantId));
  }

  /**
   * The configured threshold alone, for the public storefront read. No role is asked here: the
   * caller is an unauthenticated storefront request, and only the tenant it names (from the
   * gateway's storefront whitelist) is honoured — exactly as {@code InventoryService.availability}
   * is already read under no identity.
   *
   * @param tenantId owning tenant
   * @return the threshold, or null when the feature is off for this business
   */
  public Integer thresholdFor(UUID tenantId) {
    return repo.find(tenantId).map(StorefrontStockSettings::lowStockThreshold).orElse(null);
  }

  /**
   * Sets or clears the business's threshold. The caller's role is checked by the resource before
   * this is reached (OWNER, or a MANAGER held to no store); this only validates the value itself.
   *
   * @param tenantId the business, from the verified token
   * @param userId who set it, from the verified token
   * @param lowStockThreshold 1..1000, or null to switch the feature off
   * @throws ApiException 400 {@code INVENTORY_LOW_STOCK_THRESHOLD_INVALID} outside 1..1000
   */
  public StorefrontStockSettings update(UUID tenantId, UUID userId, Integer lowStockThreshold) {
    if (lowStockThreshold != null
        && (lowStockThreshold < MIN_THRESHOLD || lowStockThreshold > MAX_THRESHOLD)) {
      throw ApiException.badRequest(
          "INVENTORY_LOW_STOCK_THRESHOLD_INVALID",
          "lowStockThreshold must be between " + MIN_THRESHOLD + " and " + MAX_THRESHOLD);
    }
    return repo.upsert(tenantId, lowStockThreshold, userId);
  }
}
