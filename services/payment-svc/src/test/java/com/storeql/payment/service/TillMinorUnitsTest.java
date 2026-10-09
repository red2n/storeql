package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.TillSession;
import com.storeql.payment.dto.Dtos.CashMovementRequest;
import com.storeql.payment.dto.Dtos.CloseTillRequest;
import com.storeql.payment.dto.Dtos.GenerateZReportRequest;
import com.storeql.payment.dto.Dtos.OpenTillRequest;
import com.storeql.payment.repo.CashManagementRepository;
import com.storeql.payment.repo.CashMovementRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The cash in a drawer is counted in the business's own currency's minor units — whole yen, a
 * dinar's three places, a pound's two — at the open, a drop, a pay-in or pay-out, the close and the
 * day's settlement. A finer figure is refused before anything is written, never rounded.
 */
class TillMinorUnitsTest {

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID cashier = Ids.newId();
  private final TenantContext ctx = mock(TenantContext.class);

  private static TenantProfiles homeIn(String currency) {
    return TenantProfiles.forTest(
        id ->
            Optional.of(
                "{\"data\":{\"currency\":\""
                    + currency
                    + "\",\"country\":\"GB\",\"mode\":\"LIVE\"}}"),
        Clock.systemUTC());
  }

  private CashManagementService till(String currency, CashManagementRepository repo) {
    CashManagementService svc = new CashManagementService();
    svc.repo = repo;
    svc.profiles =
        currency == null
            ? TenantProfiles.forTest(id -> Optional.empty(), Clock.systemUTC())
            : homeIn(currency);
    return svc;
  }

  private CashManagementRepository openSessionRepo() {
    CashManagementRepository repo = mock(CashManagementRepository.class);
    when(repo.openTill(any())).thenAnswer(inv -> inv.getArgument(0));
    when(repo.findSession(any(), any()))
        .thenReturn(
            Optional.of(
                new TillSession(
                    Ids.newId(),
                    tenant,
                    store,
                    cashier,
                    new BigDecimal("100.0000"),
                    TillSession.STATUS_OPEN,
                    null,
                    null,
                    Instant.now(),
                    null)));
    when(repo.figures(any(), any())).thenReturn(CashManagementRepository.Figures.none());
    return repo;
  }

  private static void refused(String code, Executable call) {
    ApiException e = assertThrows(ApiException.class, call);
    assertEquals(400, e.status());
    assertEquals(code, e.code());
  }

  @Test
  @DisplayName("The opening float: a dinar's third place stands, a fourth is refused; whole yen")
  void theFloat() {
    CashManagementRepository repo = openSessionRepo();
    till("KWD", repo)
        .openTill(
            tenant,
            cashier,
            new OpenTillRequest(store.toString(), new BigDecimal("100.125"), null),
            ctx);
    till("JPY", repo)
        .openTill(
            tenant,
            cashier,
            new OpenTillRequest(store.toString(), new BigDecimal("10000"), null),
            ctx);

    for (String[] bad :
        new String[][] {{"KWD", "100.1255"}, {"JPY", "10000.5"}, {"GBP", "1.005"}}) {
      CashManagementRepository none = openSessionRepo();
      refused(
          "CASH_AMOUNT_INVALID",
          () ->
              till(bad[0], none)
                  .openTill(
                      tenant,
                      cashier,
                      new OpenTillRequest(store.toString(), new BigDecimal(bad[1]), null),
                      ctx));
      verify(none, never()).openTill(any());
    }
  }

  @Test
  @DisplayName("When the business's currency cannot be read, the till still opens")
  void anUnreadableCurrencyFailsOpen() {
    CashManagementRepository repo = openSessionRepo();
    till(null, repo)
        .openTill(
            tenant,
            cashier,
            new OpenTillRequest(store.toString(), new BigDecimal("100.1255"), null),
            ctx);
    verify(repo).openTill(any());
  }

  @Test
  @DisplayName("A drop: KWD 5.125 is taken, half a yen is refused with nothing recorded")
  void aDrop() {
    CashManagementRepository repo = openSessionRepo();
    till("KWD", repo).recordDrop(tenant, Ids.newId(), cashier, new BigDecimal("5.125"), null, ctx);
    verify(repo).recordDrop(any());

    CashManagementRepository none = openSessionRepo();
    refused(
        "INVALID_DROP_AMOUNT",
        () ->
            till("JPY", none)
                .recordDrop(tenant, Ids.newId(), cashier, new BigDecimal("5000.5"), null, ctx));
    verify(none, never()).recordDrop(any());
  }

  @Test
  @DisplayName("The close: a count finer than the currency is refused and the till stays open")
  void theClose() {
    for (String[] bad : new String[][] {{"KWD", "100.1255"}, {"JPY", "10000.5"}}) {
      CashManagementRepository repo = openSessionRepo();
      refused(
          "CASH_AMOUNT_INVALID",
          () ->
              till(bad[0], repo)
                  .zReport(
                      tenant,
                      Ids.newId(),
                      new CloseTillRequest(new BigDecimal(bad[1]), null),
                      ctx));
      verify(repo, never()).closeTill(any(), any(), any());
    }
  }

  private CashMovementService movements(String currency, CashMovementRepository repo) {
    CashMovementService svc = new CashMovementService();
    svc.repo = repo;
    svc.profiles = homeIn(currency);
    return svc;
  }

  private CashMovementRequest payIn(String amount) {
    return new CashMovementRequest(
        Ids.newId().toString(),
        store.toString(),
        "PAY_IN",
        new BigDecimal(amount),
        "float top-up",
        null);
  }

  @Test
  @DisplayName("A pay-in: KWD 1.125 is recorded; half a yen and a third penny are refused")
  void payInsAndOuts() {
    CashMovementRepository repo = mock(CashMovementRepository.class);
    movements("KWD", repo).recordMovement(tenant, cashier, payIn("1.125"), ctx, null);
    verify(repo).insertMovement(any(), any(), any(), any(), any(), any(), any(), any(), any());

    for (String[] bad : new String[][] {{"JPY", "500.5"}, {"GBP", "1.005"}, {"KWD", "1.1255"}}) {
      CashMovementRepository none = mock(CashMovementRepository.class);
      refused(
          "CASH_AMOUNT_INVALID",
          () -> movements(bad[0], none).recordMovement(tenant, cashier, payIn(bad[1]), ctx, null));
      verify(none, never())
          .insertMovement(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
  }

  @Test
  @DisplayName("The day's count is in the report's currency: KWD named, yen the business's own")
  void theDaysCount() {
    CashMovementRepository repo = mock(CashMovementRepository.class);
    when(repo.settleDay(
            any(), any(), any(), any(), anyBoolean(), any(), anyString(), any(), any(), any()))
        .thenReturn(new CashMovementRepository.Settled(null, true));
    CashMovementService svc = movements("JPY", repo);
    svc.generateZReport(
        tenant,
        cashier,
        new GenerateZReportRequest(
            store.toString(), "2026-10-01", new BigDecimal("1.125"), "KWD", null, null),
        ctx);
    verify(repo)
        .settleDay(
            any(), any(), any(), any(), anyBoolean(), any(), anyString(), any(), any(), any());

    CashMovementRepository none = mock(CashMovementRepository.class);
    refused(
        "CASH_AMOUNT_INVALID",
        () ->
            movements("JPY", none)
                .generateZReport(
                    tenant,
                    cashier,
                    new GenerateZReportRequest(
                        store.toString(), "2026-10-01", new BigDecimal("1000.5"), null, null, null),
                    ctx));
    verify(none, never())
        .settleDay(
            any(), any(), any(), any(), anyBoolean(), any(), anyString(), any(), any(), any());
  }
}
