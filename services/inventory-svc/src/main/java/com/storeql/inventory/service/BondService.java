package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.BondApproval;
import com.storeql.inventory.domain.Domain.BondRelease;
import com.storeql.inventory.domain.Domain.BondStock;
import com.storeql.inventory.domain.Domain.ExciseDutyRate;
import com.storeql.inventory.dto.Dtos.BondApprovalRequest;
import com.storeql.inventory.dto.Dtos.BondReleaseRequest;
import com.storeql.inventory.dto.Dtos.DutyRateRequest;
import com.storeql.inventory.repo.BondRepository;
import com.storeql.inventory.repo.InventoryRepository;
import com.storeql.service.Fx;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bonded and duty-suspended stock: which stores are approved warehouses, what duty one unit of a
 * variant crystallises, and the release to home use that draws bonded batches into duty-paid ones,
 * computes the duty and tells purchase-svc. The platform derives no duty rate from strength or
 * volume: management sets the figure per variant and says how it was arrived at.
 */
@ApplicationScoped
public class BondService {

  @Inject BondRepository repo;
  @Inject InventoryRepository inventory;
  @Inject TenantProfiles profiles;

  // ── Approvals ──────────────────────────────────────────────────────────────

  /**
   * @throws ApiException 400 {@code INVENTORY_BOND_REGIME_INVALID} for a regime that is neither
   *     EXCISE nor CUSTOMS
   */
  public BondApproval approve(TenantContext ctx, UUID storeId, BondApprovalRequest req) {
    String regime = req.regime().trim().toUpperCase(Locale.ROOT);
    if (!BondApproval.REGIME_EXCISE.equals(regime) && !BondApproval.REGIME_CUSTOMS.equals(regime)) {
      throw ApiException.badRequest(
          "INVENTORY_BOND_REGIME_INVALID", "regime must be EXCISE or CUSTOMS; got " + req.regime());
    }
    return repo.approve(
        new BondApproval(
            ctx.requireTenantId(),
            storeId,
            req.approvalNumber().trim(),
            regime,
            true,
            ctx.userId(),
            Instant.now(),
            null));
  }

  /**
   * @throws ApiException 404 {@code INVENTORY_BOND_APPROVAL_NOT_FOUND} when the store has no live
   *     approval
   */
  public void end(TenantContext ctx, UUID storeId) {
    if (!repo.end(ctx.requireTenantId(), storeId)) {
      throw ApiException.notFound(
          "INVENTORY_BOND_APPROVAL_NOT_FOUND", "store " + storeId + " has no live bond approval");
    }
  }

  public List<BondApproval> approvals(TenantContext ctx) {
    return repo.findApprovals(ctx.requireTenantId());
  }

  // ── Duty rates ─────────────────────────────────────────────────────────────

  public ExciseDutyRate setRate(TenantContext ctx, UUID variantId, DutyRateRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return repo.setRate(
        new ExciseDutyRate(
            tenantId,
            variantId,
            req.dutyPerUnit(),
            profiles.requireCurrency(tenantId),
            req.note() == null || req.note().isBlank() ? null : req.note().trim(),
            ctx.userId(),
            Instant.now()));
  }

  public List<ExciseDutyRate> rates(TenantContext ctx) {
    return repo.findRates(ctx.requireTenantId());
  }

  // ── Release to home use ────────────────────────────────────────────────────

  /**
   * Releases duty-suspended stock to home use at a bonded store: the duty is the variant's rate
   * times the quantity, rounded to the currency's minor unit.
   *
   * @throws ApiException 409 {@code INVENTORY_STORE_NOT_BONDED}; 409 {@code
   *     INVENTORY_DUTY_RATE_MISSING} when the variant has no duty per unit; 422 {@code
   *     INVENTORY_INSUFFICIENT_BONDED_STOCK} when less sits in bond than is released
   */
  public BondRelease release(TenantContext ctx, BondReleaseRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    UUID variantId = Parsing.uuid(req.variantId(), "variantId");
    ctx.requireStoreAccess(storeId);
    if (!repo.isBonded(tenantId, storeId)) {
      throw ApiException.conflict(
          "INVENTORY_STORE_NOT_BONDED",
          "store " + storeId + " is not approved as a bonded warehouse");
    }
    ExciseDutyRate rate =
        repo.findRate(tenantId, variantId)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "INVENTORY_DUTY_RATE_MISSING",
                        "variant "
                            + variantId
                            + " has no duty per unit; set one before releasing"));
    BigDecimal duty =
        req.qty()
            .multiply(rate.dutyPerUnit())
            .setScale(Fx.minorUnits(rate.currency()), RoundingMode.HALF_UP);
    BondRelease r =
        new BondRelease(
            Ids.newId(),
            tenantId,
            storeId,
            variantId,
            req.qty(),
            rate.dutyPerUnit(),
            duty,
            rate.currency(),
            req.reference() == null || req.reference().isBlank() ? null : req.reference().trim(),
            ctx.userId(),
            Instant.now());
    return inventory.releaseFromBond(
        r,
        new OutboxRow(
            "DutyReleased",
            "storeql.inventory.duty-released",
            tenantId,
            r.id(),
            Events.dutyReleased(r)));
  }

  public List<BondRelease> releases(TenantContext ctx, String storeId, String from, String to) {
    LocalDate f =
        from == null || from.isBlank() ? LocalDate.of(2000, 1, 1) : Parsing.date(from, "from");
    LocalDate t = to == null || to.isBlank() ? LocalDate.now() : Parsing.date(to, "to");
    if (f.isAfter(t)) {
      throw ApiException.badRequest(
          "INVENTORY_PERIOD_INVALID", "the period ends (" + t + ") before it starts (" + f + ")");
    }
    return repo.findReleases(ctx.requireTenantId(), Parsing.optionalUuid(storeId, "storeId"), f, t);
  }

  public List<BondStock> stock(TenantContext ctx, String storeId) {
    return repo.stockInBond(ctx.requireTenantId(), Parsing.optionalUuid(storeId, "storeId"));
  }

  /** The home currency the duty is owed in. */
  public String currency(TenantContext ctx) {
    return profiles.requireCurrency(ctx.requireTenantId());
  }
}
