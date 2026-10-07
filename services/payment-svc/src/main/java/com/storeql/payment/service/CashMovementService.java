package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.dto.Dtos.CashMovementRequest;
import com.storeql.payment.dto.Dtos.CashMovementResponse;
import com.storeql.payment.dto.Dtos.GenerateZReportRequest;
import com.storeql.payment.dto.Dtos.ZReportResponse;
import com.storeql.payment.repo.CashMovementRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Pay-in / pay-out (petty cash) and daily Z-report settlement. */
@ApplicationScoped
public class CashMovementService {

  @Inject CashMovementRepository repo;
  @Inject com.storeql.service.TenantProfiles profiles;

  /**
   * Records a pay-in or pay-out against an open till session.
   *
   * @param tenantId owning tenant
   * @param recordedBy the cashier recording the movement
   * @param req the till session, store, direction, amount, reason and optional authoriser
   * @param ctx caller context, checked for access to the store
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retry does not double the
   *     movement; blank is treated as absent
   * @return the recorded movement
   * @throws ApiException {@code INVALID_DIRECTION} (400) when direction is not {@code PAY_IN} or
   *     {@code PAY_OUT}; {@code CASH_AMOUNT_INVALID} (400) for an amount finer than the business's
   *     currency's minor unit (not checked while that currency cannot be read)
   */
  public CashMovementResponse recordMovement(
      UUID tenantId,
      UUID recordedBy,
      CashMovementRequest req,
      TenantContext ctx,
      String idempotencyKey) {
    if (!"PAY_IN".equals(req.direction()) && !"PAY_OUT".equals(req.direction())) {
      throw new ApiException(
          400, "INVALID_DIRECTION", "direction must be PAY_IN or PAY_OUT", List.of());
    }
    UUID tillSessionId = Ids.parse(req.tillSessionId());
    UUID storeId = Ids.parse(req.storeId());
    ctx.requireStoreAccess(storeId);
    // Till cash is the business's own currency, no finer than its minor unit (whole yen, a
    // dinar's three places); refused, never rounded.
    Amounts.requireFits(
        req.amount(),
        Amounts.currencyOrNull(profiles, tenantId, null),
        CashManagementService.CASH_AMOUNT_INVALID);
    UUID authorisedBy = req.authorisedBy() == null ? null : Ids.parse(req.authorisedBy());
    return repo.insertMovement(
        tenantId,
        storeId,
        tillSessionId,
        req.direction(),
        req.amount(),
        req.reason(),
        authorisedBy,
        recordedBy,
        idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
  }

  /**
   * Lists the pay-ins and pay-outs recorded against one till session.
   *
   * @param tenantId owning tenant
   * @param tillSessionId the session whose movements to list
   * @return the movements, empty when none were recorded
   */
  public List<CashMovementResponse> listMovements(UUID tenantId, UUID tillSessionId) {
    return repo.listMovements(tenantId, tillSessionId);
  }

  /** What settling or reading a day answered: the report and whether this request wrote it. */
  public record ZOutcome(ZReportResponse report, boolean written) {}

  /**
   * Settles a store's local day, producing and storing its Z-report once. The day is the store's
   * own (its zone through {@code TenantProfiles.Stores.zoneOf}; UTC only when the zone cannot be
   * read, and the report then says so), and with no date named it is today there, never the
   * caller's device's day. A day already settled answers the stored report; a correction ({@code
   * correctionOf} and a {@code reason}) is a new version that names what it replaces.
   *
   * @param tenantId owning tenant
   * @param generatedBy the user settling the day
   * @param req the store, business date, counted cash, optional currency (the tenant's own when
   *     omitted) and optional correction
   * @param ctx caller context, checked for access to the store
   * @return the report, and whether this request wrote it
   * @throws ApiException {@code 400} when {@code businessDate} is not a valid date; {@code
   *     Z_REPORT_SESSIONS_OPEN} (409); {@code Z_REPORT_CORRECTION_REASON_REQUIRED} (400); {@code
   *     CASH_AMOUNT_INVALID} (400) for a count finer than the report's currency's minor unit;
   *     {@code Z_REPORT_NOT_FOUND} (404) and {@code Z_REPORT_NOT_LATEST} (409) for a correction
   */
  public ZOutcome generateZReport(
      UUID tenantId, UUID generatedBy, GenerateZReportRequest req, TenantContext ctx) {
    UUID storeId = Ids.parse(req.storeId());
    ctx.requireStoreAccess(storeId);
    UUID correctionOf = req.correctionOf() == null ? null : Ids.parse(req.correctionOf());
    String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().strip();
    if (correctionOf != null && reason == null) {
      throw ApiException.badRequest(
          "Z_REPORT_CORRECTION_REASON_REQUIRED", "A correction to a settled day needs a reason");
    }
    String currency = profiles.currencyOr(tenantId, req.currency());
    Amounts.requireFits(req.countedCash(), currency, CashManagementService.CASH_AMOUNT_INVALID);
    ZoneId zone = zoneOrNull(tenantId, storeId);
    LocalDate businessDate = dayOf(req.businessDate(), zone);
    var settled =
        repo.settleDay(
            tenantId,
            storeId,
            businessDate,
            zone == null ? ZoneId.of("UTC") : zone,
            zone == null,
            req.countedCash(),
            currency,
            generatedBy,
            correctionOf,
            reason);
    return new ZOutcome(settled.report(), settled.written());
  }

  /** The day named, or today in the store's zone (UTC when it cannot be read) when none is. */
  private static LocalDate dayOf(String businessDate, ZoneId zone) {
    if (businessDate == null || businessDate.isBlank()) {
      return LocalDate.now(zone == null ? ZoneId.of("UTC") : zone);
    }
    return com.storeql.web.Parsing.date(businessDate, "businessDate");
  }

  /** The store's own zone, or null when tenant-svc cannot say (never a guess). */
  private ZoneId zoneOrNull(UUID tenantId, UUID storeId) {
    try {
      return profiles.stores(tenantId, storeId).zoneOf(storeId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Reads a previously settled Z-report for a store and business date: the latest version, or the
   * one asked for.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose report to read
   * @param businessDate the business date, as an ISO date string; today in the store's zone when
   *     omitted
   * @param version the version to read, or null for the latest
   * @param ctx caller context, checked for access to the store
   * @return the stored Z-report
   * @throws ApiException {@code 400} when {@code businessDate} is not a valid date; {@code
   *     Z_REPORT_NOT_FOUND} (404) when that day has not been settled
   */
  public ZReportResponse getZReport(
      UUID tenantId, UUID storeId, String businessDate, Integer version, TenantContext ctx) {
    ctx.requireStoreAccess(storeId);
    LocalDate date = dayOf(businessDate, zoneOrNull(tenantId, storeId));
    return repo.findZReport(tenantId, storeId, date, version)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "Z_REPORT_NOT_FOUND", "No Z-report found for that store and date"));
  }
}
