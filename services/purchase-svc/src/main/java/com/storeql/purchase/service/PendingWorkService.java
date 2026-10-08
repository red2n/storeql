package com.storeql.purchase.service;

import com.storeql.purchase.config.ServiceConfig;
import com.storeql.purchase.domain.PendingWork;
import com.storeql.purchase.repo.PendingWorkRepository;
import com.storeql.web.ApiException;
import com.storeql.web.SystemHealthAccess;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * What waits for a person in purchasing, counted for the caller's business as a whole. Read by the
 * system-health screen through reporting-svc; every count is this service's own tables.
 */
@ApplicationScoped
public class PendingWorkService {

  @Inject PendingWorkRepository repo;
  @Inject ServiceConfig config;

  /**
   * The four queues, and whether orders are routed for approval at all.
   *
   * @param ctx the caller: needs {@code system.health} and must be held to no store
   * @throws ApiException 403 {@code SYSTEM_HEALTH_NOT_PERMITTED}, else 403 {@code
   *     BUSINESS_WIDE_ONLY} for a caller held to stores; 401 {@code NO_TENANT} with no business
   */
  public PendingWork read(TenantContext ctx) {
    SystemHealthAccess.require(ctx);
    UUID tenantId = ctx.requireTenantId();
    return new PendingWork(
        repo.purchaseOrdersPendingApproval(tenantId),
        repo.paymentRunsProposed(tenantId),
        repo.supplierInvoicesFlagged(tenantId),
        repo.accountingSyncsUncertain(tenantId),
        config.approvalEnabled());
  }
}
