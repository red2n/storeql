package com.storeql.customer.service;

import com.storeql.customer.domain.Domain.ExpiryRun;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.domain.LoyaltyProgramme.Tier;
import com.storeql.customer.dto.Dtos.SetLoyaltyProgrammeRequest;
import com.storeql.customer.dto.Dtos.TierRequest;
import com.storeql.customer.repo.LoyaltyProgrammeRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The business's loyalty programme (13.x): reading it, setting it — refused by name when its shape
 * cannot be honoured — and the sweep that lets points die and tiers fall on time.
 */
@ApplicationScoped
public class LoyaltyProgrammeService {

  private static final Logger LOG = System.getLogger(LoyaltyProgrammeService.class.getName());

  @Inject LoyaltyProgrammeRepository repo;

  public LoyaltyProgramme programme(TenantContext ctx) {
    return repo.programme(ctx.requireTenantId());
  }

  /**
   * Sets the programme. It covers every store's customers, so only a caller held to no store sets
   * it, as with the return policy.
   *
   * @throws ApiException {@code 403 BUSINESS_WIDE_ONLY} for a caller held to stores; {@code 400
   *     LOYALTY_TIERS_INVALID} for a ladder that cannot be honoured, {@code 400
   *     LOYALTY_EXPIRY_INVALID} for months out of range
   */
  public LoyaltyProgramme set(TenantContext ctx, SetLoyaltyProgrammeRequest req) {
    requireBusinessWide(ctx, "set");
    UUID tenantId = ctx.requireTenantId();
    List<Tier> tiers = new ArrayList<>();
    if (req.tiers() != null) {
      for (TierRequest t : req.tiers()) {
        if (t == null) {
          throw ApiException.badRequest(
              "LOYALTY_TIERS_INVALID", "a tier is a name, a threshold and a multiplier");
        }
        tiers.add(
            new Tier(
                t.name() == null ? null : t.name().trim(),
                t.threshold(),
                t.multiplier() == null ? BigDecimal.ONE : t.multiplier()));
      }
    }
    String refusal = LoyaltyProgramme.validate(req.expiryMonths(), req.qualifyingMonths(), tiers);
    if (refusal != null) {
      boolean months = refusal.contains("Months");
      throw ApiException.badRequest(
          months ? "LOYALTY_EXPIRY_INVALID" : "LOYALTY_TIERS_INVALID", refusal);
    }
    Instant now = Instant.now();
    LoyaltyProgramme p =
        new LoyaltyProgramme(
            tenantId,
            req.expiryMonths(),
            req.qualifyingMonths(),
            tiers,
            req.reason().trim(),
            ctx.userId(),
            now);
    repo.save(p, now);
    return repo.programme(tenantId);
  }

  /** Lets every business's dead points die and its tiers fall or rise, now. */
  public ExpiryRun sweep() {
    Instant now = Instant.now();
    int customers = 0;
    BigDecimal points = BigDecimal.ZERO;
    int retiered = 0;
    for (UUID tenantId : repo.tenantsWithRules()) {
      ExpiryRun run = sweep(tenantId, now);
      customers += run.customers();
      points = points.add(run.points());
      retiered += run.retiered();
    }
    return new ExpiryRun(customers, points, retiered);
  }

  /**
   * One business's sweep, as the management endpoint runs it. It expires points and re-tiers the
   * customers of every store, so only a caller held to no store runs it by hand — refused before
   * anything is read or written; the hourly sweeper is not a caller and is not asked.
   *
   * @throws ApiException {@code 403 BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  public ExpiryRun sweep(TenantContext ctx) {
    requireBusinessWide(ctx, "run by hand");
    return sweep(ctx.requireTenantId(), Instant.now());
  }

  /**
   * The programme covers every store's customers, so what changes it for the whole business needs a
   * caller held to no store (an owner, a business-wide manager, the platform).
   */
  private static void requireBusinessWide(TenantContext ctx, String what) {
    if (!ctx.storeIds().isEmpty()) {
      throw ApiException.forbidden(
          "BUSINESS_WIDE_ONLY",
          "the loyalty programme covers every store, so it is "
              + what
              + " by a caller held to no store");
    }
  }

  private ExpiryRun sweep(UUID tenantId, Instant now) {
    ExpiryRun run =
        repo.sweep(tenantId, now, CustomerService::expiredEvent, CustomerService::tierChangedEvent);
    if (run.customers() > 0 || run.retiered() > 0) {
      LOG.log(
          Level.INFO,
          "loyalty sweep for {0}: {1} points expired for {2} customers, {3} re-tiered",
          tenantId,
          run.points(),
          run.customers(),
          run.retiered());
    }
    return run;
  }
}
