package com.storeql.customer.service;

import com.storeql.customer.domain.PendingWork;
import com.storeql.customer.repo.PendingWorkRepository;
import com.storeql.web.ApiException;
import com.storeql.web.SystemHealthAccess;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What waits for a person in customer records, counted for the caller's business as a whole. Read
 * by the system-health screen through reporting-svc; the count is this service's own table.
 */
@ApplicationScoped
public class PendingWorkService {

  @Inject PendingWorkRepository repo;

  /**
   * The privacy requests waiting for an answer, counted up to {@link
   * com.storeql.web.PendingWorkCount#CAP} (a count at the cap means that many or more).
   *
   * @param ctx the caller: needs {@code system.health} and must be held to no store
   * @throws ApiException 403 {@code SYSTEM_HEALTH_NOT_PERMITTED}, else 403 {@code
   *     BUSINESS_WIDE_ONLY} for a caller held to stores; 401 {@code NO_TENANT} with no business
   */
  public PendingWork read(TenantContext ctx) {
    SystemHealthAccess.require(ctx);
    return new PendingWork(repo.privacyRequestsOpen(ctx.requireTenantId()));
  }
}
