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
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.provider.CardTerminal;
import com.storeql.payment.repo.TerminalRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A card is taken in its currency's own minor units: a dinar has three, a yen none. A finer amount
 * is refused, never rounded, and what reaches the device is the amount at exactly those units.
 */
class TerminalMinorUnitsTest {

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID actor = Ids.newId();

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

  private final AtomicReference<CardTerminal.Request> asked = new AtomicReference<>();
  private final AtomicReference<Terminals.Attempt> stored = new AtomicReference<>();
  private TerminalRepository repo;
  private CardTerminal device;
  private TerminalService svc;

  private static final Terminals.Outcome APPROVED =
      new Terminals.Outcome(
          Terminals.APPROVED, "VISA", "4242", "AUTH01", "A0", "VISA", "CHIP", "PIN", "ref-1", null);

  @BeforeEach
  void wire() {
    device = mock(CardTerminal.class);
    when(device.vendor()).thenReturn("SIMULATED");
    when(device.sale(any()))
        .thenAnswer(
            inv -> {
              asked.set(inv.getArgument(0));
              return APPROVED;
            });
    when(device.refund(any(), any()))
        .thenAnswer(
            inv -> {
              asked.set(inv.getArgument(0));
              return APPROVED;
            });
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
    when(repo.claimRefund(any(), anyString()))
        .thenAnswer(
            inv -> {
              stored.set(inv.getArgument(0));
              return stored.get();
            });
    when(repo.settle(any(), any(), any()))
        .thenReturn(
            new com.storeql.payment.domain.CardSettlement.Answered(
                com.storeql.payment.domain.Terminals.REQUESTED, true, false));
    when(repo.attempt(any(), any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));

    svc = new TerminalService();
    svc.repo = repo;
    svc.terminals = devices;
  }

  private Terminals.Attempt sale(String amount, String currency) {
    return svc.sale(
        tenant,
        terminal.id(),
        Ids.newId(),
        new BigDecimal(amount),
        currency,
        actor,
        Ids.newId().toString());
  }

  private void refused(String amount, String currency, String code) {
    ApiException e = assertThrows(ApiException.class, () -> sale(amount, currency));
    assertEquals(400, e.status(), amount + " " + currency);
    assertEquals(code, e.code(), amount + " " + currency);
  }

  @Test
  @DisplayName("Three decimals of a dinar are taken and reach the device as three")
  void aDinarHasThreeDecimals() {
    sale("1.125", "KWD");
    assertEquals("1.125", asked.get().amount().toPlainString());
    assertEquals("1.125", stored.get().amount().toPlainString());
    // And a trailing zero is not precision.
    sale("1.1250", "KWD");
    assertEquals("1.125", asked.get().amount().toPlainString());
  }

  @Test
  @DisplayName("A fourth decimal of a dinar is refused, not rounded, and nothing is asked")
  void aFourthDecimalOfADinarIsRefused() {
    refused("1.1255", "KWD", "TERMINAL_AMOUNT_INVALID");
    verify(repo, never()).claim(any(), anyString());
    verify(device, never()).sale(any());
  }

  @Test
  @DisplayName("Yen are whole: 1250.00 reaches the device as 1250, and half a yen is refused")
  void yenAreWhole() {
    sale("1250.00", "JPY");
    assertEquals("1250", asked.get().amount().toPlainString());
    assertEquals("1250", stored.get().amount().toPlainString());

    refused("1250.5", "JPY", "TERMINAL_AMOUNT_INVALID");
  }

  @Test
  @DisplayName("Pounds keep two places, and a third is still refused")
  void poundsKeepTwo() {
    sale("12.5", "GBP");
    assertEquals("12.50", asked.get().amount().toPlainString());
    refused("1.005", "GBP", "TERMINAL_AMOUNT_INVALID");
    refused("0.00", "GBP", "TERMINAL_AMOUNT_INVALID");
    refused("-5.00", "GBP", "TERMINAL_AMOUNT_INVALID");
  }

  @Test
  @DisplayName("A code ISO 4217 does not know is refused, since its minor units are unknown")
  void anUnknownCurrencyIsRefused() {
    refused("12.50", "XYZ", "CURRENCY_INVALID");
    verify(device, never()).sale(any());
  }

  @Test
  @DisplayName("A refund is judged in the currency the card paid in")
  void aRefundIsInThePaymentsCurrency() {
    Terminals.Attempt paid = sale("2.500", "KWD");
    // The paid attempt as the database would give it back: approved, at the column's scale.
    Terminals.Attempt original =
        new Terminals.Attempt(
            paid.id(),
            tenant,
            store,
            terminal.id(),
            paid.orderId(),
            new BigDecimal("2.5000"),
            "KWD",
            Terminals.SALE,
            null,
            Terminals.APPROVED,
            null,
            "VISA",
            "4242",
            "AUTH01",
            "A0",
            "VISA",
            "CHIP",
            "PIN",
            "ref-1",
            null,
            Instant.now(),
            actor,
            Instant.now(),
            null,
            null);
    when(repo.attempt(tenant, original.id())).thenReturn(Optional.of(original));

    svc.refund(
        tenant, original.id(), new BigDecimal("1.125"), actor, Ids.newId().toString(), "faulty");
    assertEquals("1.125", asked.get().amount().toPlainString());

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.refund(
                    tenant,
                    original.id(),
                    new BigDecimal("0.1255"),
                    actor,
                    Ids.newId().toString(),
                    "faulty"));
    assertEquals("TERMINAL_AMOUNT_INVALID", e.code());

    ApiException tooMuch =
        assertThrows(
            ApiException.class,
            () ->
                svc.refund(
                    tenant,
                    original.id(),
                    new BigDecimal("3"),
                    actor,
                    Ids.newId().toString(),
                    "faulty"));
    assertEquals("TERMINAL_REFUND_TOO_LARGE", tooMuch.code());
    assertEquals(
        "That is more than the 2.500 taken on this card",
        tooMuch.getMessage(),
        "said in the dinar's own three places");
  }
}
