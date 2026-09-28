package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.DeferredRevenue;
import com.storeql.purchase.domain.DeferredRevenue.PointsOutcome;
import com.storeql.purchase.domain.DeferredRevenue.PointsPool;
import com.storeql.purchase.domain.DeferredRevenue.Source;
import com.storeql.purchase.domain.Domain.DeferredRevenueSettings;
import com.storeql.purchase.domain.Domain.DeferredRevenueView;
import com.storeql.purchase.domain.Domain.GiftCardLoad;
import com.storeql.purchase.domain.Domain.LoyaltyEvent;
import com.storeql.purchase.dto.Dtos.DeferredRevenueSettingsRequest;
import com.storeql.purchase.repo.DeferredRevenueRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Deferred revenue for loyalty points and gift card breakage (17.11): turns what customer-svc
 * announces about points and order-svc about gift cards into journals, on the estimates the
 * tenant's accountant has set. Like sales posting (17.7), postings are dated the day they are
 * received and are not refused in a closed period: the points were earned, and a refused event
 * would be redelivered forever.
 */
@ApplicationScoped
public class DeferredRevenueService {

  static final String GIFT_CARD_SPENT_CONSUMER = "purchase-svc/gift-card-breakage";

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};

  @Inject DeferredRevenueRepository repo;
  @Inject TenantProfiles tenants;

  /** The estimates in force, their history, and where the points and gift cards stand. */
  public DeferredRevenueView view(TenantContext ctx) {
    ctx.requireAnyRole(MANAGEMENT);
    return repo.view(ctx.requireTenantId());
  }

  /**
   * Sets new estimates, and posts every loyalty event that was waiting for them. Earlier estimates
   * are kept; a change applies from now on, as a change in an accounting estimate does.
   *
   * @throws ApiException {@code 400} naming the estimate that is out of range; {@code 403} for
   *     anyone but management
   */
  public DeferredRevenueView setEstimates(TenantContext ctx, DeferredRevenueSettingsRequest req) {
    ctx.requireAnyRole(MANAGEMENT);
    UUID tenantId = ctx.requireTenantId();
    String refusal =
        DeferredRevenue.refusal(
            req.pointValue(), req.pointsBreakagePct(), req.giftCardBreakagePct());
    if (DeferredRevenue.CODE_POINT_VALUE_INVALID.equals(refusal)) {
      throw ApiException.badRequest(
          refusal, "a point is worth more than 0 and at most 1000, to four decimal places");
    }
    if (refusal != null) {
      throw ApiException.badRequest(
          refusal, "a breakage estimate is a percentage from 0 to 95, to two decimal places");
    }
    repo.saveSettings(
        new DeferredRevenueSettings(
            Ids.newId(),
            tenantId,
            tenants.currencyOr(tenantId, null),
            req.pointValue(),
            req.pointsBreakagePct(),
            req.giftCardBreakagePct(),
            req.reason().trim(),
            ctx.userId(),
            null),
        DeferredRevenueService::postPoints);
    return repo.view(tenantId);
  }

  /** Records a loyalty event, and posts it when estimates are set. Once per event. */
  public boolean loyaltyEvent(LoyaltyEvent event) {
    return repo.recordLoyaltyEvent(event, DeferredRevenueService::postPoints);
  }

  /** Posts a gift card sold or reloaded to the liability. Once per card transaction. */
  public boolean giftCardLoaded(GiftCardLoad load) {
    if (load.amount() == null || load.amount().signum() <= 0) return false;
    var posting =
        DeferredRevenue.giftCardLoaded(
            new Source(load.tenantId(), load.transactionId(), load.storeId(), today()),
            load.kind(),
            load.paidBy(),
            load.amount());
    return repo.recordGiftCardLoad(load, posting);
  }

  /** Recognises the breakage that goes with a gift card spent as tender. Once per payment. */
  public boolean giftCardSpent(
      UUID paymentId, UUID tenantId, UUID orderId, UUID storeId, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) return false;
    var src = new Source(tenantId, orderId, storeId, today());
    return repo.recordGiftCardSpent(
        Ids.derived(paymentId, "gift-card-breakage"),
        GIFT_CARD_SPENT_CONSUMER,
        tenantId,
        (settings, pool) -> DeferredRevenue.giftCardRedeemed(src, settings, pool, amount));
  }

  static PointsOutcome postPoints(
      DeferredRevenue.Settings settings, PointsPool pool, LoyaltyEvent e, UUID storeId) {
    var src =
        new Source(e.tenantId(), e.orderId() != null ? e.orderId() : e.eventId(), storeId, today());
    return switch (e.kind()) {
      case LoyaltyEvent.EARNED ->
          DeferredRevenue.earned(src, settings, pool, e.points(), e.orderTotal(), e.orderTax());
      case LoyaltyEvent.REDEEMED -> DeferredRevenue.redeemed(src, settings, pool, e.points());
      case LoyaltyEvent.EXPIRED -> DeferredRevenue.expired(src, settings, pool, e.points());
      default -> DeferredRevenue.adjusted(src, settings, pool, e.points());
    };
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}
