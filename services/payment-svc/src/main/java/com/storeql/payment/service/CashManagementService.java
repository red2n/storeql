package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.CashDrop;
import com.storeql.payment.domain.Domain.TillSession;
import com.storeql.payment.dto.Dtos.CashDropResponse;
import com.storeql.payment.dto.Dtos.CloseTillRequest;
import com.storeql.payment.dto.Dtos.OpenTillRequest;
import com.storeql.payment.dto.Dtos.TenderSummary;
import com.storeql.payment.dto.Dtos.TillReportResponse;
import com.storeql.payment.dto.Dtos.TillSessionResponse;
import com.storeql.payment.repo.CashManagementRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Till sessions and their X/Z reports — the cash-drawer side of POS.
 *
 * <p>Every entry point resolves the session first and asserts the caller has access to its store,
 * so a cashier cannot read or close another store's till; the current-till lookup checks the store
 * it is named before it looks.
 */
@ApplicationScoped
public class CashManagementService {

  @Inject CashManagementRepository repo;

  /**
   * Opens a till session with its starting float.
   *
   * @param tenantId owning tenant
   * @param openedBy the cashier opening the till
   * @param req the store and the float the drawer starts with
   * @param ctx caller context, checked for access to the store
   * @return the newly opened session
   */
  public TillSessionResponse openTill(
      UUID tenantId, UUID openedBy, OpenTillRequest req, TenantContext ctx) {
    UUID storeId = Ids.parse(req.storeId());
    ctx.requireStoreAccess(storeId);
    TillSession session =
        new TillSession(
            Ids.newId(),
            tenantId,
            storeId,
            openedBy,
            req.floatAmount(),
            TillSession.STATUS_OPEN,
            null,
            null,
            Instant.now(),
            null);
    return toSessionResponse(repo.openTill(session));
  }

  /**
   * Reads one till session.
   *
   * @param tenantId owning tenant
   * @param sessionId the session to read
   * @param ctx caller context, checked for access to the session's store
   * @return the session
   * @throws ApiException {@code TILL_SESSION_NOT_FOUND} (404) when no such session exists in this
   *     tenant
   */
  public TillSessionResponse getSession(UUID tenantId, UUID sessionId, TenantContext ctx) {
    return toSessionResponse(requireSession(tenantId, sessionId, ctx));
  }

  /**
   * The caller's own open till at a store: the session they opened there and have not closed. The
   * store is checked first, so a store the caller does not keep is refused before it is looked at.
   *
   * @param tenantId owning tenant
   * @param storeId the store the caller is at
   * @param openedBy the caller, whose session it must be
   * @param ctx caller context, checked for access to the store
   * @return the open session, the latest opened should there be more than one
   * @throws ApiException {@code STORE_ACCESS_DENIED} (403) for a store the caller does not keep;
   *     {@code TILL_SESSION_NOT_OPEN} (404) when the caller has no open till there
   */
  public TillSessionResponse currentSession(
      UUID tenantId, UUID storeId, UUID openedBy, TenantContext ctx) {
    ctx.requireStoreAccess(storeId);
    return repo.findOpenSession(tenantId, storeId, openedBy)
        .map(CashManagementService::toSessionResponse)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "TILL_SESSION_NOT_OPEN", "You have no open till session at this store"));
  }

  /**
   * Records a cash drop — money moved out of the drawer to the safe mid-shift.
   *
   * <p>Drops reduce the cash the Z-report expects to find in the drawer at close.
   *
   * @param tenantId owning tenant
   * @param sessionId the open till session the drop is against
   * @param recordedBy the cashier recording the drop
   * @param amount the amount removed; must be positive
   * @param notes free-text context, may be {@code null}
   * @param ctx caller context, checked for access to the session's store
   * @return the recorded drop
   * @throws ApiException {@code TILL_CLOSED} (400) when the session is already closed; {@code
   *     INVALID_DROP_AMOUNT} (400) when the amount is not positive
   */
  public CashDropResponse recordDrop(
      UUID tenantId,
      UUID sessionId,
      UUID recordedBy,
      BigDecimal amount,
      String notes,
      TenantContext ctx) {
    TillSession session = requireSession(tenantId, sessionId, ctx);
    if (!TillSession.STATUS_OPEN.equals(session.status())) {
      throw ApiException.badRequest("TILL_CLOSED", "Till session is already closed");
    }
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("INVALID_DROP_AMOUNT", "Drop amount must be positive");
    }
    CashDrop drop =
        new CashDrop(Ids.newId(), tenantId, sessionId, amount, recordedBy, notes, Instant.now());
    repo.recordDrop(drop);
    return new CashDropResponse(drop.id(), drop.tillSessionId(), drop.amount(), drop.createdAt());
  }

  /**
   * X-report: read-only snapshot of the current session's totals. Does not close the session.
   *
   * <p>Safe to run repeatedly mid-shift. {@code countedCash} and the over/short figure are absent,
   * since nothing has been counted yet.
   *
   * @param tenantId owning tenant
   * @param sessionId the session to report on
   * @param ctx caller context, checked for access to the session's store
   * @return the totals so far
   * @throws ApiException {@code TILL_SESSION_NOT_FOUND} (404) when no such session exists in this
   *     tenant
   */
  public TillReportResponse xReport(UUID tenantId, UUID sessionId, TenantContext ctx) {
    TillSession session = requireSession(tenantId, sessionId, ctx);
    return buildReport(session, null);
  }

  /**
   * Z-report: computes totals, records counted cash, closes the session.
   *
   * <p>The over/short figure is the counted cash less what the float, cash sales, cash refunds and
   * drops say should be in the drawer. Terminal — the session cannot be reopened afterwards.
   *
   * @param tenantId owning tenant
   * @param sessionId the session to close
   * @param req the cash actually counted in the drawer
   * @param ctx caller context, checked for access to the session's store
   * @return the final totals, including over/short
   * @throws ApiException {@code TILL_SESSION_NOT_FOUND} (404) when no such session exists in this
   *     tenant; {@code TILL_CLOSED} (400) when it is already closed
   */
  public TillReportResponse zReport(
      UUID tenantId, UUID sessionId, CloseTillRequest req, TenantContext ctx) {
    TillSession session = requireSession(tenantId, sessionId, ctx);
    if (!TillSession.STATUS_OPEN.equals(session.status())) {
      throw ApiException.badRequest("TILL_CLOSED", "Till session is already closed");
    }
    TillReportResponse report = buildReport(session, req.countedCash());
    BigDecimal overShort =
        req.countedCash()
            .subtract(
                report.expectedCashInTill() != null
                    ? report.expectedCashInTill()
                    : BigDecimal.ZERO);
    repo.closeTill(tenantId, sessionId, req.countedCash(), overShort);
    return report;
  }

  private TillReportResponse buildReport(TillSession session, BigDecimal countedCash) {
    Instant from = session.openedAt();
    Instant to = session.closedAt() != null ? session.closedAt() : Instant.now();

    List<Object[]> salesRows =
        repo.sumTendersByMethod(session.tenantId(), session.storeId(), from, to);
    List<Object[]> refundRows = repo.sumRefundsByMethod(session.tenantId(), from, to);

    Map<String, BigDecimal> sales = new HashMap<>();
    for (Object[] row : salesRows) {
      sales.put((String) row[0], (BigDecimal) row[1]);
    }
    Map<String, BigDecimal> refunds = new HashMap<>();
    for (Object[] row : refundRows) {
      refunds.put((String) row[0], (BigDecimal) row[1]);
    }

    Map<String, TenderSummary> summary = new HashMap<>();
    var allMethods = new java.util.HashSet<String>();
    allMethods.addAll(sales.keySet());
    allMethods.addAll(refunds.keySet());
    BigDecimal grossSales = BigDecimal.ZERO;
    BigDecimal totalRefunds = BigDecimal.ZERO;
    for (String method : allMethods) {
      BigDecimal s = sales.getOrDefault(method, BigDecimal.ZERO);
      BigDecimal r = refunds.getOrDefault(method, BigDecimal.ZERO);
      BigDecimal net = s.subtract(r);
      summary.put(method, new TenderSummary(s, r, net));
      grossSales = grossSales.add(s);
      totalRefunds = totalRefunds.add(r);
    }
    BigDecimal netSales = grossSales.subtract(totalRefunds);

    BigDecimal cashDropsTotal = repo.sumCashDrops(session.tenantId(), session.id());
    BigDecimal cashSales = sales.getOrDefault("CASH", BigDecimal.ZERO);
    BigDecimal cashRefunds = refunds.getOrDefault("CASH", BigDecimal.ZERO);
    BigDecimal expectedCash =
        session.floatAmount().add(cashSales).subtract(cashRefunds).subtract(cashDropsTotal);

    BigDecimal overShort = countedCash != null ? countedCash.subtract(expectedCash) : null;

    return new TillReportResponse(
        session.id(),
        session.storeId(),
        session.openedBy(),
        session.openedAt(),
        session.closedAt(),
        session.floatAmount(),
        Map.copyOf(summary),
        cashDropsTotal,
        expectedCash,
        countedCash,
        overShort,
        grossSales,
        totalRefunds,
        netSales);
  }

  private TillSession requireSession(UUID tenantId, UUID sessionId, TenantContext ctx) {
    TillSession session =
        repo.findSession(tenantId, sessionId)
            .orElseThrow(
                () -> ApiException.notFound("TILL_SESSION_NOT_FOUND", "Till session not found"));
    ctx.requireStoreAccess(session.storeId());
    return session;
  }

  private static TillSessionResponse toSessionResponse(TillSession s) {
    return new TillSessionResponse(
        s.id(),
        s.storeId(),
        s.openedBy(),
        s.floatAmount(),
        s.status(),
        s.countedCash(),
        s.overShort(),
        s.openedAt(),
        s.closedAt());
  }
}
