package com.storeql.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Terminals;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The simulator reads its test amounts from the last two minor units of the amount <b>in its own
 * currency</b>: a yen's are whole yen, a dinar's are fils. Read at an assumed two places, a yen
 * amount could never decline and a dinar's third decimal would be lost.
 */
class SimulatedCardTerminalTest {

  private final SimulatedCardTerminal terminal = new SimulatedCardTerminal();

  {
    terminal.scheme = "VISA";
    terminal.applicationLabel = "VISA DEBIT";
  }

  private String sale(String amount, String currency) {
    return terminal
        .sale(
            new CardTerminal.Request(
                Ids.newId(),
                "SN-1",
                new BigDecimal(amount),
                currency,
                Ids.newId(),
                Ids.newId().toString()))
        .state();
  }

  @Test
  @DisplayName("Pounds: .01 declines, .02 cancels, .03 times out, .04 fails, the rest approve")
  void pounds() {
    assertEquals(Terminals.DECLINED, sale("10.01", "GBP"));
    assertEquals(Terminals.CANCELLED, sale("10.02", "GBP"));
    assertEquals(Terminals.TIMED_OUT, sale("10.03", "GBP"));
    assertEquals(Terminals.FAILED, sale("10.04", "GBP"));
    assertEquals(Terminals.APPROVED, sale("10.10", "GBP"));
    assertEquals(Terminals.APPROVED, sale("10.5", "GBP"));
  }

  @Test
  @DisplayName("Yen: the test amounts are whole yen, so 1001 declines and 1003 times out")
  void yen() {
    assertEquals(Terminals.DECLINED, sale("1001", "JPY"));
    assertEquals(Terminals.TIMED_OUT, sale("1003", "JPY"));
    assertEquals(Terminals.APPROVED, sale("1000", "JPY"));
    assertEquals(Terminals.APPROVED, sale("1010", "JPY"));
  }

  @Test
  @DisplayName("Dinars: the test amounts are fils, so 1.001 declines and 1.010 approves")
  void dinars() {
    assertEquals(Terminals.DECLINED, sale("1.001", "KWD"));
    assertEquals(Terminals.FAILED, sale("1.004", "KWD"));
    assertEquals(Terminals.APPROVED, sale("1.010", "KWD"));
    assertEquals(Terminals.APPROVED, sale("1.100", "KWD"));
  }

  private String refund(String amount, String currency) {
    return terminal
        .refund(
            new CardTerminal.Request(
                Ids.newId(),
                "SN-1",
                new BigDecimal(amount),
                currency,
                Ids.newId(),
                Ids.newId().toString()),
            "SIM-ORIGINAL")
        .state();
  }

  @Test
  @DisplayName(
      "A refund: .01 is refused by the issuer, .04 fails, .05 is never answered (the refund that"
          + " may have gone through), the rest are put back — .03 included, so a timed-out sale"
          + " seen approved can be put back in full")
  void refunds() {
    assertEquals(Terminals.DECLINED, refund("10.01", "GBP"));
    assertEquals(Terminals.FAILED, refund("10.04", "GBP"));
    assertEquals(Terminals.TIMED_OUT, refund("10.05", "GBP"));
    assertEquals(Terminals.TIMED_OUT, refund("1005", "JPY"));
    assertEquals(Terminals.TIMED_OUT, refund("1.005", "KWD"));
    assertEquals(Terminals.APPROVED, refund("9.03", "GBP"));
    assertEquals(Terminals.APPROVED, refund("10.00", "GBP"));
    assertEquals(Terminals.APPROVED, sale("10.05", "GBP"), "a sale of .05 is an ordinary approval");
  }
}
