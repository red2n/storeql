package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.tenant.domain.StatutoryReturns.Return;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a statutory return falls due, and what state a period is in.
 *
 * <p>All of it derived, none of it stored. The cases worth the test are the ones a stored deadline
 * gets wrong: a quarter that straddles a year end, an offset expressed as "one month and seven
 * days" rather than as a number of days, and a return filed late — which is filed, whatever the
 * date says.
 */
class StatutoryReturnsTest {

  private static Return monthly(String dueAfter) {
    return new Return(
        "SAFT_PT",
        "COUNTRY",
        "PT",
        "SAF-T (PT)",
        StatutoryReturns.MONTHLY,
        dueAfter,
        "order-svc",
        "/admin/fiscal-receipts/export?format=saft-pt",
        "Portaria 302/2016",
        LocalDate.of(2020, 1, 1),
        null);
  }

  private static Return quarterly(String dueAfter) {
    return new Return(
        "VAT_RETURN_UK",
        "COUNTRY",
        "GB",
        "VAT return",
        StatutoryReturns.QUARTERLY,
        dueAfter,
        "pricing-svc",
        "/vat-return",
        "VATA 1994 sch.11",
        LocalDate.of(2019, 4, 1),
        null);
  }

  private static Return decadal() {
    return new Return(
        "EREPORTING_TX_FR",
        "COUNTRY",
        "FR",
        "E-reporting: transaction data",
        StatutoryReturns.DECADAL,
        "P10D",
        "order-svc",
        "/admin/ereporting/submissions",
        "CGI art. 290",
        LocalDate.of(2026, 9, 1),
        null);
  }

  @Test
  @DisplayName("Portugal's SAF-T is due on the 5th of the month after the month it reports")
  void aMonthlyReturn() {
    Return saft = monthly("P4D");
    LocalDate start =
        StatutoryReturns.periodStart(StatutoryReturns.MONTHLY, LocalDate.of(2026, 9, 14));
    LocalDate end = StatutoryReturns.periodEnd(StatutoryReturns.MONTHLY, start);

    assertEquals(LocalDate.of(2026, 9, 1), start);
    assertEquals(LocalDate.of(2026, 10, 1), end, "exclusive: the next period begins here");
    // The date the Portaria names, not an offset arithmetic happens to produce. P4D from the
    // exclusive end is the 5th; the same P4D from the period's last day would be the 4th.
    assertEquals(5, saft.dueOn(end).getDayOfMonth());
    assertEquals(LocalDate.of(2026, 10, 5), saft.dueOn(end));
    // And a short February does not move it, because the offset counts from the end, not the start.
    assertEquals(
        LocalDate.of(2026, 3, 5),
        saft.dueOn(StatutoryReturns.periodEnd(StatutoryReturns.MONTHLY, LocalDate.of(2026, 2, 1))));
  }

  @Test
  @DisplayName(
      "HMRC's \"one month and seven days\" lands on the 7th of every quarter, not 37 days on")
  void anOffsetIsAPeriodNotADayCount() {
    // The reason dueAfter is an ISO period and not a number of days. HMRC publishes the 7th for
    // every
    // quarter; a fixed day count cannot do that, because the months in between are different
    // lengths.
    Return vat = quarterly("P1M6D");

    assertEquals(LocalDate.of(2026, 5, 7), vat.dueOn(LocalDate.of(2026, 4, 1)), "Q1 is due 7 May");
    assertEquals(LocalDate.of(2026, 11, 7), vat.dueOn(LocalDate.of(2026, 10, 1)), "Q3, 7 November");
    assertEquals(LocalDate.of(2027, 2, 7), vat.dueOn(LocalDate.of(2027, 1, 1)), "Q4, 7 February");
    // The demonstration: 37 days happens to be right for one of those quarters and wrong for
    // another.
    assertEquals(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 10, 1).plusDays(37));
    assertEquals(LocalDate.of(2026, 5, 8), LocalDate.of(2026, 4, 1).plusDays(37), "a day late");
  }

  @Test
  @DisplayName("A quarter that straddles a year end is one quarter, and due in the new year")
  void aQuarterAcrossAYearEnd() {
    LocalDate inside = LocalDate.of(2026, 11, 20);
    LocalDate start = StatutoryReturns.periodStart(StatutoryReturns.QUARTERLY, inside);
    LocalDate end = StatutoryReturns.periodEnd(StatutoryReturns.QUARTERLY, start);

    assertEquals(LocalDate.of(2026, 10, 1), start, "the calendar quarter, not the month");
    assertEquals(LocalDate.of(2027, 1, 1), end);
    assertEquals(LocalDate.of(2027, 2, 7), quarterly("P1M6D").dueOn(end));
    assertEquals(
        LocalDate.of(2026, 7, 1),
        StatutoryReturns.previousPeriod(StatutoryReturns.QUARTERLY, start),
        "and walking back stays on the quarter boundary");
  }

  @Test
  @DisplayName("Every quarter of a year maps to its own calendar quarter")
  void everyMonthLandsInTheRightQuarter() {
    int[] expected = {1, 1, 1, 4, 4, 4, 7, 7, 7, 10, 10, 10};
    for (int month = 1; month <= 12; month++) {
      LocalDate start =
          StatutoryReturns.periodStart(StatutoryReturns.QUARTERLY, LocalDate.of(2026, month, 15));
      assertEquals(expected[month - 1], start.getMonthValue(), "month " + month);
      assertEquals(1, start.getDayOfMonth());
    }
  }

  @Test
  @DisplayName("Nothing is owed before the period has ended")
  void nothingIsOwedYet() {
    LocalDate end = LocalDate.of(2026, 10, 1);
    LocalDate due = LocalDate.of(2026, 10, 5);

    assertEquals(
        StatutoryReturns.NOT_DUE,
        StatutoryReturns.stateOf(end, due, null, LocalDate.of(2026, 9, 30)));
    // The day the period ends, it is owed: the period is exclusive of that day.
    assertEquals(
        StatutoryReturns.DUE, StatutoryReturns.stateOf(end, due, null, LocalDate.of(2026, 10, 1)));
  }

  @Test
  @DisplayName("It is due up to and including its date, and overdue only after it")
  void theBoundaryOfTheDueDate() {
    LocalDate end = LocalDate.of(2026, 10, 1);
    LocalDate due = LocalDate.of(2026, 10, 5);

    assertEquals(
        StatutoryReturns.DUE,
        StatutoryReturns.stateOf(end, due, null, due),
        "filing on the day it is due is filing on time");
    assertEquals(
        StatutoryReturns.OVERDUE, StatutoryReturns.stateOf(end, due, null, due.plusDays(1)));
  }

  @Test
  @DisplayName("A return filed late is filed, not overdue")
  void filedLateIsStillFiled() {
    // Calling it overdue after the fact would misrepresent the record: the question the state
    // answers
    // is "is there anything to do about this?", and there is not.
    LocalDate end = LocalDate.of(2026, 10, 1);
    LocalDate due = LocalDate.of(2026, 10, 5);
    StatutoryReturns.Filing late =
        new StatutoryReturns.Filing(
            com.storeql.ids.Ids.newId(),
            com.storeql.ids.Ids.newId(),
            "SAFT_PT",
            end.minusMonths(1),
            end,
            java.time.Instant.parse("2026-11-20T09:00:00Z"),
            com.storeql.ids.Ids.newId(),
            "AT-9912",
            "MANUAL",
            null,
            null,
            null,
            null);

    assertEquals(
        StatutoryReturns.FILED,
        StatutoryReturns.stateOf(end, due, late, LocalDate.of(2026, 12, 1)));
    assertFalse(
        new StatutoryReturns.Obligation(
                monthly("P4D"), end.minusMonths(1), end, due, StatutoryReturns.FILED, late)
            .actionable());
  }

  @Test
  @DisplayName("A return that ceased is not owed for periods after it ceased")
  void aReturnThatCeased() {
    Return ceased =
        new Return(
            "EC_SALES_LIST",
            "REGIME",
            "EU",
            "Recapitulative statement",
            StatutoryReturns.MONTHLY,
            "P20D",
            null,
            null,
            "Directive 2006/112/EC art. 262",
            LocalDate.of(2010, 1, 1),
            LocalDate.of(2026, 6, 30));

    assertTrue(ceased.inForceOn(LocalDate.of(2026, 6, 30)));
    assertFalse(ceased.inForceOn(LocalDate.of(2026, 7, 1)));
    assertFalse(ceased.producible(), "and the platform does not pretend it can produce this one");
  }

  @Test
  @DisplayName("An offset that cannot be read fails loudly rather than becoming due at once")
  void anUnreadableOffsetIsRefused() {
    // The alternative is worse than a crash: a return whose offset silently became zero would show
    // as
    // due the day its period ended, and as overdue the day after.
    assertThrows(IllegalArgumentException.class, () -> StatutoryReturns.period("five days"));
    assertThrows(IllegalArgumentException.class, () -> StatutoryReturns.period("P"));
    assertThrows(IllegalArgumentException.class, () -> StatutoryReturns.period("P0D"));
    assertThrows(IllegalArgumentException.class, () -> StatutoryReturns.period("P-5D"));
    assertEquals(7, StatutoryReturns.period("P7D").getDays());
  }

  @Test
  @DisplayName("An unknown frequency is refused, not guessed at")
  void anUnknownFrequencyIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> StatutoryReturns.periodStart("FORTNIGHTLY", LocalDate.of(2026, 9, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> StatutoryReturns.periodEnd("FORTNIGHTLY", LocalDate.of(2026, 9, 1)));
  }

  @Test
  @DisplayName("A filing a correction has replaced no longer stands")
  void aCorrectionReplacesItsPredecessor() {
    java.util.UUID first = com.storeql.ids.Ids.newId();
    java.util.UUID second = com.storeql.ids.Ids.newId();
    LocalDate start = LocalDate.of(2026, 9, 1);
    LocalDate end = LocalDate.of(2026, 10, 1);

    StatutoryReturns.Filing superseded =
        new StatutoryReturns.Filing(
            first,
            com.storeql.ids.Ids.newId(),
            "SAFT_PT",
            start,
            end,
            java.time.Instant.now(),
            com.storeql.ids.Ids.newId(),
            "AT-1",
            "MANUAL",
            null,
            null,
            second,
            "wrong figures");
    StatutoryReturns.Filing correction =
        new StatutoryReturns.Filing(
            second,
            superseded.tenantId(),
            "SAFT_PT",
            start,
            end,
            java.time.Instant.now(),
            superseded.filedBy(),
            "AT-2",
            "MANUAL",
            null,
            first,
            null,
            "corrected");

    assertFalse(superseded.stands(), "both stay on the record, but only one stands");
    assertTrue(correction.stands());
  }

  @Test
  @DisplayName("A ten-day period is the month's own third, not ten days counted off")
  void decadalPeriods() {
    // France reports transactions three times a month on the ordinary monthly VAT regime. The third
    // period is the trap: it runs from the 21st to the end of the month, which is 11 days in March
    // and 8 in February, so counting ten days would put a February sale in a March period and leave
    // three days of March reported twice.
    assertEquals(
        LocalDate.of(2026, 3, 1),
        StatutoryReturns.periodStart(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 10)),
        "the 10th is still the first period");
    assertEquals(
        LocalDate.of(2026, 3, 11),
        StatutoryReturns.periodStart(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 11)));
    assertEquals(
        LocalDate.of(2026, 3, 21),
        StatutoryReturns.periodStart(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 31)));

    assertEquals(
        LocalDate.of(2026, 3, 11),
        StatutoryReturns.periodEnd(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 1)));
    assertEquals(
        LocalDate.of(2026, 4, 1),
        StatutoryReturns.periodEnd(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 21)),
        "the last period of a month ends when the month does");
    assertEquals(
        LocalDate.of(2026, 3, 1),
        StatutoryReturns.periodEnd(StatutoryReturns.DECADAL, LocalDate.of(2026, 2, 21)),
        "and February's last period is eight days long, not ten");
  }

  @Test
  @DisplayName("Walking the ten-day calendar backwards crosses a month end correctly")
  void decadalWalksBack() {
    assertEquals(
        LocalDate.of(2026, 3, 11),
        StatutoryReturns.previousPeriod(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 21)));
    assertEquals(
        LocalDate.of(2026, 3, 1),
        StatutoryReturns.previousPeriod(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 11)));
    assertEquals(
        LocalDate.of(2026, 2, 21),
        StatutoryReturns.previousPeriod(StatutoryReturns.DECADAL, LocalDate.of(2026, 3, 1)),
        "the period before a month's first is the last of the month before");
  }

  @Test
  @DisplayName("E-reporting is due ten days after the period it reports")
  void eReportingIsDueTenDaysLater() {
    // The offset is added to the day AFTER the period, as every offset here is. A period ending on
    // the 10th (exclusive end: the 11th) is due on the 21st.
    Return fr = decadal();
    assertEquals(
        LocalDate.of(2026, 9, 21),
        fr.dueOn(StatutoryReturns.periodEnd(StatutoryReturns.DECADAL, LocalDate.of(2026, 9, 1))));
    assertEquals(
        LocalDate.of(2026, 10, 11),
        fr.dueOn(StatutoryReturns.periodEnd(StatutoryReturns.DECADAL, LocalDate.of(2026, 9, 21))),
        "the period to 30 September is due on 11 October");
  }
}
