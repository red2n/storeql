package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.service.Fx;
import com.storeql.tenant.domain.Proration.Prorated;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a mid-period plan change is worth (21.9). The cases that matter are the ones an invoice gets
 * argued about: the change on the first day and on the last, the credit of what was actually
 * billed, and the arithmetic a business can repeat against a calendar.
 */
class ProrationTest {

  private static final LocalDate START = LocalDate.of(2026, 9, 1);
  private static final LocalDate END = LocalDate.of(2026, 10, 1); // 30 days

  private static BigDecimal money(String amount) {
    return new BigDecimal(amount);
  }

  private static Prorated on(String billed, String replacement, LocalDate changeOn) {
    return Proration.onChange(money(billed), money(replacement), START, END, changeOn, 2);
  }

  @Test
  @DisplayName(
      "An upgrade half-way through credits the old price and charges the new, for the same days")
  void anUpgradeCreditsTheOldAndChargesTheNew() {
    // 15 days left of 30: half of each price.
    Prorated p = on("10.00", "20.00", LocalDate.of(2026, 9, 16));

    assertEquals(30, p.daysInPeriod());
    assertEquals(15, p.daysRemaining());
    assertEquals(money("5.00"), p.credit());
    assertEquals(money("10.00"), p.debit());
    assertEquals(
        money("5.00"), p.net(), "the business pays the difference for the days it has left");
    assertTrue(p.any());
  }

  @Test
  @DisplayName("A downgrade is the same arithmetic the other way, and owes nothing")
  void aDowngradeIsTheSameArithmeticTheOtherWay() {
    Prorated p = on("20.00", "10.00", LocalDate.of(2026, 9, 16));

    assertEquals(money("10.00"), p.credit());
    assertEquals(money("5.00"), p.debit());
    assertEquals(money("-5.00"), p.net(), "owed back, not owed");
  }

  @Test
  @DisplayName("The credit is of what was billed, not of what the plan costs today")
  void theCreditIsOfWhatWasBilled() {
    // The business is on an old, cheaper price. Crediting at today's £20 would hand back money it
    // never paid — the proration bug every billing system has had at least once.
    Prorated p = on("12.00", "20.00", LocalDate.of(2026, 9, 21));

    assertEquals(10, p.daysRemaining());
    assertEquals(money("4.00"), p.credit(), "10 of 30 days at the £12 it was actually billed");
    assertEquals(money("6.67"), p.debit(), "10 of 30 days at £20, to the penny");
  }

  @Test
  @DisplayName(
      "A change on the first day is the whole period; one on the last day is worth nothing")
  void theEdgesOfThePeriod() {
    Prorated first = on("10.00", "20.00", START);
    assertEquals(30, first.daysRemaining());
    assertEquals(money("10.00"), first.credit());
    assertEquals(money("20.00"), first.debit());

    Prorated last = on("10.00", "20.00", END);
    assertEquals(0, last.daysRemaining());
    assertEquals(money("0.00"), last.credit());
    assertEquals(money("0.00"), last.debit());
    assertFalse(last.any(), "nothing to write on the invoice");

    Prorated after = on("10.00", "20.00", END.plusDays(9));
    assertFalse(after.any(), "a change after the period ends belongs to the next one");

    Prorated before = on("10.00", "20.00", START.minusDays(5));
    assertEquals(30, before.daysRemaining(), "a change before it began is the whole period");
  }

  @Test
  @DisplayName("Rounding lands on the penny, and a period that is not a period is refused")
  void roundingAndRefusals() {
    // A third of £10 is 3.333…: an invoice says 3.33.
    Prorated third = on("10.00", "0.00", LocalDate.of(2026, 9, 21));
    assertEquals(money("3.33"), third.credit());
    assertEquals(money("0.00"), third.debit(), "a free plan debits nothing");

    // Two places, always — an invoice does not print 5 or 5.0.
    assertEquals("5.00", on("10.00", "0.00", LocalDate.of(2026, 9, 16)).credit().toPlainString());

    assertThrows(
        IllegalArgumentException.class,
        () -> Proration.onChange(money("1"), money("1"), END, START, START, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> Proration.onChange(money("1"), money("1"), START, START, START, 2));
  }

  @Test
  @DisplayName("A figure that makes no sense is worth nothing, never a credit")
  void nonsenseIsNeverACredit() {
    LocalDate mid = LocalDate.of(2026, 9, 16);
    assertEquals(money("0.00"), Proration.onChange(null, money("10"), START, END, mid, 2).credit());
    assertEquals(
        money("0.00"),
        Proration.onChange(money("-50"), money("10"), START, END, mid, 2).credit(),
        "a negative price does not become money back");
    assertEquals(money("0.00"), Proration.onChange(money("10"), null, START, END, mid, 2).debit());
  }

  @Test
  @DisplayName("A year is prorated by its own length, not by an assumed one")
  void aYearIsProratedByItsOwnLength() {
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate to = LocalDate.of(2027, 1, 1); // 365 days
    Prorated p =
        Proration.onChange(money("365.00"), money("730.00"), from, to, LocalDate.of(2026, 7, 1), 2);

    assertEquals(365, p.daysInPeriod());
    assertEquals(184, p.daysRemaining(), "1 July to 1 January");
    assertEquals(money("184.00"), p.credit());
    assertEquals(money("368.00"), p.debit());
  }

  @Test
  @DisplayName("A share is rounded to the currency's own minor units: whole yen, a dinar's third")
  void aShareIsRoundedToTheCurrencysOwnMinorUnits() {
    // 7 days of 30: 1000 yen is 233.33…, billed as 233; 10.000 dinars is 2.3333…, as 2.333.
    LocalDate seventhLast = LocalDate.of(2026, 9, 24);
    Prorated yen =
        Proration.onChange(
            money("1000"), money("3000"), START, END, seventhLast, Fx.minorUnits("JPY"));
    assertEquals(money("233"), yen.credit());
    assertEquals(money("700"), yen.debit());
    Prorated dinar =
        Proration.onChange(
            money("10.000"), money("20.000"), START, END, seventhLast, Fx.minorUnits("KWD"));
    assertEquals(money("2.333"), dinar.credit());
    assertEquals(money("4.667"), dinar.debit());
    // Nothing owed is nought at the currency's scale too, so a yen invoice never prints 0.00.
    assertEquals(
        money("0"),
        Proration.onChange(null, money("10"), START, END, seventhLast, Fx.minorUnits("JPY"))
            .credit());
  }
}
