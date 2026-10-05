package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CashExpectation;
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
  @Inject com.storeql.service.TenantProfiles profiles;

  /** The refusal for till cash finer than the business's currency's minor unit. */
  static final String CASH_AMOUNT_INVALID = "CASH_AMOUNT_INVALID";

  /**
   * Opens a till session with its starting float.
   *
   * @param tenantId owning tenant
   * @param openedBy the cashier opening the till
   * @param req the store and the float the drawer starts with
   * @param ctx caller context, checked for access to the store
   * @return the newly opened session
   * @throws ApiException {@code CASH_AMOUNT_INVALID} (400) for a float finer than the business's
   *     currency's minor unit
   */
  public TillSessionResponse openTill(
      UUID tenantId, UUID openedBy, OpenTillRequest req, TenantContext ctx) {
    UUID storeId = Ids.parse(req.storeId());
    ctx.requireStoreAccess(storeId);
    requireCash(tenantId, req.floatAmount(), CASH_AMOUNT_INVALID);
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
   *     INVALID_DROP_AMOUNT} (400) when the amount is not positive or is finer than the business's
   *     currency's minor unit
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
    requireCash(tenantId, amount, "INVALID_DROP_AMOUNT");
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
   *     tenant; {@code TILL_CLOSED} (400) when it is already closed; {@code CASH_AMOUNT_INVALID}
   *     (400) for a count finer than the business's currency's minor unit, the till left open
   */
  public TillReportResponse zReport(
      UUID tenantId, UUID sessionId, CloseTillRequest req, TenantContext ctx) {
    TillSession session = requireSession(tenantId, sessionId, ctx);
    if (!TillSession.STATUS_OPEN.equals(session.status())) {
      throw ApiException.badRequest("TILL_CLOSED", "Till session is already closed");
    }
    requireCash(tenantId, req.countedCash(), CASH_AMOUNT_INVALID);
    Instant closedAt = Instant.now();
    TillReportResponse open = buildReport(session, null, closedAt, null);
    BigDecimal overShort = cashExpectation(open, session).overShort(req.countedCash());
    String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
    // Hook: the approvals mechanism's action "till.close-variance" (a person other than the closer,
    // holding till.manage, agrees a close whose |overShort| is above the business's ceiling) is
    // checked HERE, before the write, once the approvals block exists (intent/approvals.md). The
    // business's variance tolerance (note required above it) belongs here too.
    UUID closedBy = ctx.userId();
    repo.closeTill(
        tenantId,
        sessionId,
        req.countedCash(),
        overShort,
        closedAt,
        closedBy,
        note,
        Events.tillSessionClosed(
            tenantId,
            sessionId,
            session.storeId(),
            session.openedBy(),
            closedBy,
            session.openedAt(),
            closedAt,
            session.floatAmount(),
            open.expectedCashInTill(),
            req.countedCash(),
            overShort,
            note));
    return buildReport(session, req.countedCash(), closedAt, note);
  }

  private static CashExpectation cashExpectation(TillReportResponse r, TillSession session) {
    return new CashExpectation(
        session.floatAmount(),
        r.cashSales(),
        r.cashRefunds(),
        r.payIns(),
        r.payOuts(),
        r.cashDropsTotal());
  }

  private TillReportResponse buildReport(TillSession session, BigDecimal countedCash) {
    Instant to = session.closedAt() != null ? session.closedAt() : Instant.now();
    return buildReport(session, countedCash, to, null);
  }

  /**
   * The session's figures over {@code [openedAt, to)}: tenders and refunds at the session's own
   * store only (tenant, then store, then the window), the session's own drops and pay-ins and
   * pay-outs, and the expectation from the one pure formula. Until registers exist the basis is the
   * window at the store.
   */
  private TillReportResponse buildReport(
      TillSession session, BigDecimal countedCash, Instant to, String note) {
    Instant from = session.openedAt();

    List<Object[]> salesRows =
        repo.sumTendersByMethod(session.tenantId(), session.storeId(), from, to);
    List<Object[]> refundRows =
        repo.sumRefundsByMethod(session.tenantId(), session.storeId(), from, to);

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
    BigDecimal payIns = repo.sumMovements(session.tenantId(), session.id(), "PAY_IN");
    BigDecimal payOuts = repo.sumMovements(session.tenantId(), session.id(), "PAY_OUT");
    BigDecimal cashSales = sales.getOrDefault("CASH", BigDecimal.ZERO);
    BigDecimal cashRefunds = refunds.getOrDefault("CASH", BigDecimal.ZERO);
    CashExpectation expectation =
        new CashExpectation(
            session.floatAmount(), cashSales, cashRefunds, payIns, payOuts, cashDropsTotal);
    BigDecimal expectedCash = expectation.expected();
    BigDecimal overShort = countedCash != null ? expectation.overShort(countedCash) : null;

    return new TillReportResponse(
        session.id(),
        session.storeId(),
        session.openedBy(),
        session.openedAt(),
        session.closedAt() != null ? session.closedAt() : (countedCash != null ? to : null),
        session.floatAmount(),
        Map.copyOf(summary),
        cashDropsTotal,
        expectedCash,
        countedCash,
        overShort,
        grossSales,
        totalRefunds,
        netSales,
        cashSales,
        cashRefunds,
        payIns,
        payOuts,
        "WINDOW",
        note);
  }

  /**
   * Cash in the drawer is the business's own currency (one business, one currency), so a figure for
   * it is no finer than that currency's minor unit: whole yen, a dinar's three places. Not checked
   * when the currency cannot be read right now, so a till is never stopped by a briefly unreachable
   * tenant-svc; the four-place columns hold the figure exactly either way.
   */
  private void requireCash(UUID tenantId, BigDecimal amount, String code) {
    Amounts.requireFits(amount, Amounts.currencyOrNull(profiles, tenantId, null), code);
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
