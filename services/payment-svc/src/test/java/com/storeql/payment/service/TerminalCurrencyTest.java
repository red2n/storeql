package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.provider.CardTerminal;
import com.storeql.payment.repo.TerminalRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A card is taken in the business's own currency. The till names the currency of a sale, and a
 * business trades in one (its profile, through {@code TenantProfiles}): a sale in any other is
 * refused before anything is claimed or asked of the machine, rather than charged in a currency the
 * business does not sell in. When the business's own cannot be read just now, the till is not
 * stopped over it.
 */
class TerminalCurrencyTest {

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final Terminals.Terminal terminal =
      new Terminals.Terminal(
          Ids.newId(),
          tenant,
          store,
          "till",
          "SIMULATED",
          null,
          Terminals.ACTIVE,
          null,
          Instant.now(),
          Instant.now());

  private final AtomicReference<Terminals.Attempt> stored = new AtomicReference<>();
  private TerminalRepository repo;
  private CardTerminal device;

  private TerminalService wired(Function<UUID, Optional<String>> tenantSvc) {
    device = mock(CardTerminal.class);
    when(device.vendor()).thenReturn("SIMULATED");
    when(device.sale(any()))
        .thenReturn(
            new Terminals.Outcome(
                Terminals.APPROVED,
                "VISA",
                "4242",
                "AUTH01",
                "A0",
                "VISA",
                "CHIP",
                "PIN",
                "r",
                null));
    @SuppressWarnings("unchecked")
    Instance<CardTerminal> devices = mock(Instance.class);
    when(devices.iterator()).thenAnswer(inv -> List.of(device).iterator());
    repo = mock(TerminalRepository.class);
    when(repo.find(tenant, terminal.id())).thenReturn(Optional.of(terminal));
    when(repo.claim(any(), anyString()))
        .thenAnswer(
            inv -> {
              stored.set(inv.getArgument(0));
              return stored.get();
            });
    when(repo.settle(any(), any(), any()))
        .thenReturn(new CardSettlement.Answered(Terminals.REQUESTED, true, false));
    when(repo.attempt(any(), any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
    TerminalService svc = new TerminalService();
    svc.repo = repo;
    svc.terminals = devices;
    svc.profiles = TenantProfiles.forTest(tenantSvc, Clock.systemUTC());
    return svc;
  }

  private static Function<UUID, Optional<String>> trades(String currency, String country) {
    return id ->
        Optional.of(
            "{\"data\":{\"id\":\""
                + id
                + "\",\"currency\":\""
                + currency
                + "\",\"country\":\""
                + country
                + "\"}}");
  }

  private Terminals.Attempt sale(TerminalService svc, String amount, String currency) {
    return svc.sale(
        tenant,
        terminal.id(),
        Ids.newId(),
        new BigDecimal(amount),
        currency,
        Ids.newId(),
        Ids.newId().toString());
  }

  @Test
  @DisplayName("A sale in the business's own currency is taken")
  void theBusinesssOwnCurrencyIsTaken() {
    TerminalService svc = wired(trades("KWD", "KW"));
    sale(svc, "1.125", "KWD");
    verify(device).sale(any());
  }

  @Test
  @DisplayName(
      "A sale in another currency is refused 409 TERMINAL_CURRENCY_MISMATCH naming the business's,"
          + " before anything is claimed or asked of the machine")
  void anotherCurrencyIsRefused() {
    TerminalService svc = wired(trades("JPY", "JP"));
    for (String other : new String[] {"GBP", "USD", "EUR"}) {
      ApiException e = assertThrows(ApiException.class, () -> sale(svc, "12.50", other));
      assertEquals(409, e.status(), other);
      assertEquals("TERMINAL_CURRENCY_MISMATCH", e.code(), other);
      assertEquals(List.of("currency=JPY"), e.details(), other);
    }
    verify(repo, never()).claim(any(), anyString());
    verify(device, never()).sale(any());
  }

  @Test
  @DisplayName(
      "A malformed amount or an unknown code is refused first, as before: 400, not a mismatch")
  void aBadRequestIsStillABadRequest() {
    TerminalService svc = wired(trades("GBP", "GB"));
    assertEquals(
        "CURRENCY_INVALID",
        assertThrows(ApiException.class, () -> sale(svc, "12.50", "XYZ")).code());
    assertEquals(
        "TERMINAL_AMOUNT_INVALID",
        assertThrows(ApiException.class, () -> sale(svc, "1250.5", "JPY")).code());
  }

  @Test
  @DisplayName(
      "When the business's currency cannot be read just now, the till is not stopped over it")
  void anUnreadableProfileDoesNotStopTheTill() {
    TerminalService svc = wired(id -> Optional.empty());
    sale(svc, "12.50", "GBP");
    verify(device).sale(any());
    TerminalService failing =
        wired(
            id -> {
              throw new IllegalStateException("tenant-svc is down");
            });
    sale(failing, "12.50", "GBP");
    verify(device).sale(any());
  }
}
