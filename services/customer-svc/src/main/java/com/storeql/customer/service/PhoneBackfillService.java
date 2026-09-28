package com.storeql.customer.service;

import com.storeql.customer.repo.CustomerRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.UUID;

/**
 * The start-up backfill of {@code customers.phone_e164}: every tenant with a customer row still
 * needing it, tried against that business's own regions — its home country, then its stores' —
 * never a country named in code.
 *
 * <p>Tenant by tenant, so one business whose regions cannot be read right now (tenant-svc down)
 * never blocks another's, and is tried again on a later start rather than on every retry of this
 * one: each tenant's regions are read at most once per run, and a tenant that fails that read is
 * simply left for next time — nothing of its rows is touched or marked.
 */
@ApplicationScoped
public class PhoneBackfillService {

  private static final Logger LOG = System.getLogger(PhoneBackfillService.class.getName());

  /** The most tenants one run looks at; generous, and a safety bound rather than a real limit. */
  private static final int MAX_TENANTS_PER_RUN = 10_000;

  /** The most rows one batch, within one tenant, corrects at a time. */
  private static final int ROW_BATCH = 500;

  @Inject CustomerRepository repo;
  @Inject TenantProfiles profiles;

  /**
   * Runs one full pass: every tenant the backfill still owes work to, once each, in full when its
   * regions can be read.
   *
   * @return how many rows were stamped in total across every tenant this pass reached
   */
  public int run() {
    int total = 0;
    for (UUID tenantId : repo.distinctTenantsNeedingPhoneBackfill(MAX_TENANTS_PER_RUN)) {
      total += backfillOneTenant(tenantId);
    }
    return total;
  }

  private int backfillOneTenant(UUID tenantId) {
    String home;
    try {
      home = profiles.requireCountry(tenantId);
    } catch (ApiException e) {
      LOG.log(
          Level.INFO,
          "phone backfill: tenant {0}'s country could not be read, trying again later: {1}",
          tenantId,
          e.getMessage());
      return 0;
    }
    Collection<String> stores;
    try {
      stores = profiles.stores(tenantId, null).countries().values();
    } catch (ApiException e) {
      LOG.log(
          Level.INFO,
          "phone backfill: tenant {0}'s stores could not be read, trying again later: {1}",
          tenantId,
          e.getMessage());
      return 0;
    }
    int fixedForTenant = 0;
    int fixed;
    do {
      fixed = repo.phoneBackfillBatchForTenant(tenantId, home, stores, ROW_BATCH);
      fixedForTenant += fixed;
    } while (fixed == ROW_BATCH);
    return fixedForTenant;
  }
}
