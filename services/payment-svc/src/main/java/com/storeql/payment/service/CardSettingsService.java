package com.storeql.payment.service;

import com.storeql.payment.dto.CardSettingsDtos.Change;
import com.storeql.payment.dto.CardSettingsDtos.StandaloneCardResponse;
import com.storeql.payment.repo.CardSettingsRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Whether a store may take cards on a standalone machine. Only an owner loosens it (a manager
 * cannot loosen a fraud control); a manager may read it. The store must be one of the business's,
 * read from tenant-svc and failing closed when it cannot be read.
 */
@ApplicationScoped
public class CardSettingsService {

  private static final int HISTORY = 20;

  @Inject CardSettingsRepository repo;
  @Inject TenantProfiles profiles;

  /**
   * @throws ApiException 403 for anyone but an owner; 404 {@code STORE_NOT_FOUND} for a store that
   *     is not the business's; 503 {@code TENANT_STORES_UNAVAILABLE} when stores cannot be read
   */
  public StandaloneCardResponse set(TenantContext ctx, UUID storeId, boolean allowed) {
    ctx.requireAnyRole("OWNER");
    UUID tenantId = ctx.requireTenantId();
    requireStore(tenantId, storeId);
    ctx.requireStoreAccess(storeId);
    repo.set(tenantId, storeId, allowed, ctx.userId());
    return view(tenantId, storeId);
  }

  public StandaloneCardResponse get(TenantContext ctx, UUID storeId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    requireStore(tenantId, storeId);
    ctx.requireStoreAccess(storeId);
    return view(tenantId, storeId);
  }

  private void requireStore(UUID tenantId, UUID storeId) {
    if (!profiles.stores(tenantId, storeId).has(storeId)) {
      throw ApiException.notFound("STORE_NOT_FOUND", "No such store");
    }
  }

  private StandaloneCardResponse view(UUID tenantId, UUID storeId) {
    var now = repo.find(tenantId, storeId);
    var tenders = repo.tendersSince(tenantId, storeId, Instant.now().minus(Duration.ofDays(30)));
    return new StandaloneCardResponse(
        storeId.toString(),
        now.map(CardSettingsRepository.Setting::standaloneAllowed).orElse(false),
        now.map(s -> s.changedBy() == null ? null : s.changedBy().toString()).orElse(null),
        now.map(s -> s.changedAt().toString()).orElse(null),
        tenders.card(),
        tenders.standalone(),
        repo.changes(tenantId, storeId, HISTORY).stream()
            .map(
                c ->
                    new Change(
                        c.standaloneAllowed(),
                        c.changedBy() == null ? null : c.changedBy().toString(),
                        c.changedAt().toString()))
            .toList());
  }
}
