package com.storeql.order.service;

import com.storeql.order.domain.ReturnPolicy;
import com.storeql.order.dto.Dtos.ReturnPolicyRequest;
import com.storeql.order.repo.ReturnPolicyRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * A business's return policy (intent/return-controls.md): what a cashier may take back alone. The
 * policy is per business; a business that never set one gets {@link ReturnPolicy#DEFAULT}.
 */
@ApplicationScoped
public class ReturnPolicyService {

  /**
   * The policy with the currency its ceilings are in.
   *
   * @param policy the policy in force
   * @param currency the business's home currency, or null when it cannot be read just now
   * @param usingDefault whether the business has set nothing
   */
  public record View(ReturnPolicy policy, String currency, boolean usingDefault) {}

  @Inject ReturnPolicyRepository repo;
  @Inject TenantStatusRepository tenantStatusRepo;
  @Inject TenantProfiles profiles;

  /**
   * The policy in force for a business: its own, else the default.
   *
   * @param tenantId owning tenant
   * @return the policy
   */
  public ReturnPolicy effective(UUID tenantId) {
    return repo.find(tenantId).orElse(ReturnPolicy.DEFAULT);
  }

  /**
   * The policy in force and whether it is the default.
   *
   * @param tenantId owning tenant
   * @return the policy, the currency of its ceilings, and whether it is the default
   */
  public View view(UUID tenantId) {
    var own = repo.find(tenantId);
    String currency =
        tenantStatusRepo
            .findCurrency(tenantId)
            .orElseGet(
                () -> profiles.find(tenantId).map(TenantProfiles.Profile::currency).orElse(null));
    return new View(own.orElse(ReturnPolicy.DEFAULT), currency, own.isEmpty());
  }

  /**
   * Sets the business's policy, replacing any earlier one.
   *
   * @param tenantId owning tenant
   * @param req the new policy, already validated
   * @param ctx caller context; supplies who changed it
   * @return the policy as now in force
   */
  public View set(UUID tenantId, ReturnPolicyRequest req, TenantContext ctx) {
    repo.save(
        tenantId,
        new ReturnPolicy(
            req.windowDays(), req.cashierCeiling(), req.noReceiptAllowed(), req.noReceiptCeiling()),
        ctx.userId());
    return view(tenantId);
  }
}
