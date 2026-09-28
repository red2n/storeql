package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.DutyRelease;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Handle;
import com.storeql.purchase.domain.LedgerPosting;
import com.storeql.purchase.repo.DutyRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Excise duty, the buyer's side: what inventory-svc released from bond is owed to the revenue the
 * day it left — Excise Duty against Excise Duty Payable — once per announcement. The releases of a
 * period are what an excise return is made from.
 */
@ApplicationScoped
public class DutyService {

  static final String RELEASE_CONSUMER = "purchase-svc/duty-release";

  @Inject DutyRepository repo;
  @Inject TenantProfiles profiles;

  /**
   * Records a release inventory-svc announced and posts the duty it owes.
   *
   * @return whether it was recorded now; false when it already was
   */
  public boolean record(
      UUID eventId,
      UUID tenantId,
      UUID releaseId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal dutyPerUnit,
      BigDecimal dutyAmount,
      String currency,
      String reference,
      LocalDate releasedOn) {
    DutyRelease r =
        new DutyRelease(
            Ids.newId(),
            tenantId,
            eventId,
            releaseId,
            storeId,
            variantId,
            qty,
            dutyPerUnit == null ? BigDecimal.ZERO : dutyPerUnit,
            dutyAmount == null ? BigDecimal.ZERO : dutyAmount,
            currency,
            reference,
            releasedOn,
            Instant.now());
    return repo.recordOnce(eventId, RELEASE_CONSUMER, r, posting(r));
  }

  /** Dr Excise Duty, Cr Excise Duty Payable: the duty, the day the goods left bond. */
  static List<NominalLedgerEntry> posting(DutyRelease r) {
    if (r.dutyAmount().signum() <= 0) return List.of();
    return LedgerPosting.of(
            r.tenantId(),
            r.releasedOn(),
            "Duty on "
                + r.qty().toPlainString()
                + " x variant "
                + Handle.of(r.variantId())
                + " released from bond"
                + (r.reference() == null ? "" : " (" + r.reference() + ")"),
            Domain.SOURCE_DUTY_RELEASE,
            r.id(),
            r.storeId())
        .debit(Domain.CODE_EXCISE_DUTY, Domain.NAME_EXCISE_DUTY, r.dutyAmount())
        .credit(Domain.CODE_DUTY_PAYABLE, Domain.NAME_DUTY_PAYABLE, r.dutyAmount())
        .build();
  }

  /**
   * @throws ApiException 400 {@code PURCHASE_DUTY_PERIOD_INVALID} when the period ends before it
   *     starts
   */
  public List<DutyRelease> releases(TenantContext ctx, String from, String to) {
    LocalDate f =
        from == null || from.isBlank() ? LocalDate.of(2000, 1, 1) : Parsing.date(from, "from");
    LocalDate t = to == null || to.isBlank() ? LocalDate.now() : Parsing.date(to, "to");
    if (f.isAfter(t)) {
      throw ApiException.badRequest(
          "PURCHASE_DUTY_PERIOD_INVALID",
          "the period ends (" + t + ") before it starts (" + f + ")");
    }
    return repo.findReleases(ctx.requireTenantId(), f, t);
  }

  public String currency(TenantContext ctx) {
    return profiles.requireCurrency(ctx.requireTenantId());
  }
}
