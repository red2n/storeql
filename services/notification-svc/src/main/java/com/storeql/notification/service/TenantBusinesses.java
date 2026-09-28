package com.storeql.notification.service;

import com.storeql.service.TenantProfiles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

/** The business as tenant-svc, which owns it, describes it; cached there for minutes. */
@ApplicationScoped
class TenantBusinesses implements Businesses {

  @Inject TenantProfiles profiles;

  @Override
  public Optional<String> country(UUID tenantId) {
    return profiles.find(tenantId).map(TenantProfiles.Profile::country);
  }

  @Override
  public Optional<String> name(UUID tenantId) {
    return profiles.businessName(tenantId);
  }

  @Override
  public boolean sandbox(UUID tenantId) {
    return profiles.isSandbox(tenantId);
  }

  @Override
  public Optional<String> currency(UUID tenantId) {
    return profiles.find(tenantId).map(TenantProfiles.Profile::currency);
  }
}
