package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.Rest;
import com.storeql.tenant.domain.Workforce.Shift;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hours a day is worth, and what is worth saying about a rota.
 *
 * <p>The cases that matter are the ones that cost somebody money or a night's rest: an unpaid break
 * coming off the hours and a paid one not, an open entry reported as open rather than as zero, and
 * a rota that leaves eight hours between two shifts.
 */
class WorkforceTest {

  private static final UUID T = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID WHO = Ids.newId();

  private static Instant at(String iso) {
    return Instant.parse(iso);
  }

  private static Rest rest(String from, String to, boolean paid) {
    return new Rest(
        Ids.newId(),
        T,
        Ids.newId(),
        at(from),
        to == null ? null : at(to),
        Workforce.BREAK_REST,
        paid);
  }

  private static Entry entry(String in, String out, List<Rest> breaks) {
    return new Entry(
        Ids.newId(),
        T,
        STORE,
        WHO,
        null,
        at(in),
        out == null ? null : at(out),
        Workforce.SOURCE_CLOCK,
        null,
        null,
        null,
        null,
        at(in),
        WHO,
        breaks);
  }

  private static Shift shift(String from, String to) {
    return new Shift(
        Ids.newId(),
        T,
        STORE,
        WHO,
        at(from),
        at(to),
        null,
        Workforce.PUBLISHED,
        null,
        null,
        at(from),
        WHO,
        at(from));
  }

  @Test
  @DisplayName("An unpaid break comes off the hours; a paid one does not")
  void breaksAndPay() {
    // The employer's arrangement, kept rather than decided here — but kept exactly, because this is
    // the number somebody is paid on.
    Entry unpaid =
        entry(
            "2026-09-14T08:00:00Z",
            "2026-09-14T16:30:00Z",
            List.of(rest("2026-09-14T12:00:00Z", "2026-09-14T12:30:00Z", false)));
    assertEquals(Duration.ofHours(8), unpaid.worked());
    assertEquals(Duration.ofMinutes(30), unpaid.unpaidBreaks());
    assertEquals(Duration.ofMinutes(510), unpaid.onSite(), "on site is eight and a half");

    Entry paid =
        entry(
            "2026-09-14T08:00:00Z",
            "2026-09-14T16:30:00Z",
            List.of(rest("2026-09-14T12:00:00Z", "2026-09-14T12:30:00Z", true)));
    assertEquals(Duration.ofMinutes(510), paid.worked());
  }

  @Test
  @DisplayName("An open entry has no hours yet, and says so rather than answering zero")
  void anOpenEntryIsNotZero() {
    // Zero looks like a day nobody worked, and a payroll run must be able to tell the difference.
    Entry open = entry("2026-09-14T08:00:00Z", null, List.of());
    assertTrue(open.open());
    assertNull(open.worked());
    assertNull(open.onSite());

    // An open break contributes nothing until it ends, so the arithmetic of a day is never
    // negative.
    Entry openBreak =
        entry(
            "2026-09-14T08:00:00Z",
            "2026-09-14T12:00:00Z",
            List.of(rest("2026-09-14T11:00:00Z", null, false)));
    assertEquals(Duration.ofHours(4), openBreak.worked());
  }

  @Test
  @DisplayName("Breaks longer than the shift cannot make the hours negative")
  void neverNegative() {
    Entry odd =
        entry(
            "2026-09-14T08:00:00Z",
            "2026-09-14T09:00:00Z",
            List.of(rest("2026-09-14T08:00:00Z", "2026-09-14T10:00:00Z", false)));
    assertEquals(Duration.ZERO, odd.worked());
  }

  @Test
  @DisplayName(
      "A correction raises paid minutes only when it lengthens the hours net of unpaid breaks")
  void raisesPaidMinutes() {
    Entry before = entry("2026-09-14T08:00:00Z", "2026-09-14T16:00:00Z", List.of());
    assertTrue(
        Workforce.raisesPaidMinutes(
            before, entry("2026-09-14T08:00:00Z", "2026-09-14T17:00:00Z", List.of())));
    assertFalse(
        Workforce.raisesPaidMinutes(
            before, entry("2026-09-14T08:00:00Z", "2026-09-14T15:00:00Z", List.of())),
        "shorter hours are not a raise");
    assertFalse(
        Workforce.raisesPaidMinutes(
            before, entry("2026-09-14T08:00:00Z", "2026-09-14T16:00:00Z", List.of())),
        "the same hours are not a raise");
    assertFalse(
        Workforce.raisesPaidMinutes(
            before,
            entry(
                "2026-09-14T07:00:00Z",
                "2026-09-14T16:00:00Z",
                List.of(rest("2026-09-14T12:00:00Z", "2026-09-14T13:00:00Z", false)))),
        "an hour more on site less an unpaid hour is the same pay");
    assertTrue(
        Workforce.raisesPaidMinutes(
            entry("2026-09-14T08:00:00Z", null, List.of()),
            entry("2026-09-14T08:00:00Z", "2026-09-14T16:00:00Z", List.of())),
        "closing an open entry raises hours from nothing");
  }

  @Test
  @DisplayName("A day rostered and not worked is an absence; worked and not rostered is not")
  void attendance() {
    var absent =
        new Workforce.AttendanceDay(
            java.time.LocalDate.of(2026, 9, 14), WHO, STORE, 480, 0, 0, false, null);
    assertTrue(absent.absent());
    assertFalse(absent.unplanned());

    var unplanned =
        new Workforce.AttendanceDay(
            java.time.LocalDate.of(2026, 9, 14), WHO, STORE, 0, 300, 1, false, null);
    assertTrue(unplanned.unplanned());
    assertFalse(unplanned.absent());

    // Still on the clock: not an absence, whatever the worked figure says so far.
    var onTheClock =
        new Workforce.AttendanceDay(
            java.time.LocalDate.of(2026, 9, 14), WHO, STORE, 480, 0, 1, true, 5L);
    assertFalse(onTheClock.absent());

    // Clocked in and straight back out: no minutes, but they turned up. Counting an absence on
    // minutes would put a disciplinary question where a mis-tap is.
    var misTap =
        new Workforce.AttendanceDay(
            java.time.LocalDate.of(2026, 9, 14), WHO, STORE, 480, 0, 1, false, 0L);
    assertFalse(misTap.absent());
  }

  @Test
  @DisplayName("Hours read the way a rota is discussed: one decimal place")
  void hoursRead() {
    assertEquals("8.0", Workforce.hours(Duration.ofHours(8)));
    assertEquals("7.5", Workforce.hours(Duration.ofMinutes(450)));
    assertEquals("0.0", Workforce.hours(Duration.ofMinutes(-30)), "never negative");
  }

  @Test
  @DisplayName("An hour costs what it cost on the day, not what it costs now")
  void costUsesTheRateOfTheDay() {
    // A rise in April must not re-cost January, or last quarter's labour figure would disagree with
    // itself the day somebody got a pay rise.
    var january =
        new Workforce.PayRate(
            Ids.newId(),
            T,
            WHO,
            java.time.LocalDate.of(2026, 1, 1),
            new java.math.BigDecimal("12.00"),
            "GBP",
            null,
            at("2026-01-01T00:00:00Z"),
            WHO);
    var april =
        new Workforce.PayRate(
            Ids.newId(),
            T,
            WHO,
            java.time.LocalDate.of(2026, 4, 1),
            new java.math.BigDecimal("13.50"),
            "GBP",
            null,
            at("2026-04-01T00:00:00Z"),
            WHO);
    var rates = List.of(april, january); // newest first, as the repository reads them

    Entry inJanuary = entry("2026-01-20T09:00:00Z", "2026-01-20T17:00:00Z", List.of());
    assertEquals(new java.math.BigDecimal("96.00"), Workforce.cost(inJanuary, rates));

    Entry inMay = entry("2026-05-20T09:00:00Z", "2026-05-20T17:00:00Z", List.of());
    assertEquals(new java.math.BigDecimal("108.00"), Workforce.cost(inMay, rates));
  }

  @Test
  @DisplayName("An unpaid break is not paid for, and an uncosted day is unknown rather than free")
  void costRespectsBreaksAndSilence() {
    var rate =
        new Workforce.PayRate(
            Ids.newId(),
            T,
            WHO,
            java.time.LocalDate.of(2026, 1, 1),
            new java.math.BigDecimal("12.00"),
            "GBP",
            null,
            at("2026-01-01T00:00:00Z"),
            WHO);
    Entry withLunch =
        entry(
            "2026-02-02T08:00:00Z",
            "2026-02-02T16:30:00Z",
            List.of(rest("2026-02-02T12:00:00Z", "2026-02-02T12:30:00Z", false)));
    assertEquals(new java.math.BigDecimal("96.00"), Workforce.cost(withLunch, List.of(rate)));

    // No rate in force yet: unknown, not free. Zero is a rate somebody may be on, and a day shown
    // as
    // free labour is worse than one that says it does not know.
    Entry before = entry("2025-12-31T09:00:00Z", "2025-12-31T17:00:00Z", List.of());
    assertNull(Workforce.cost(before, List.of(rate)));
    // And an open entry has no cost, because it has no hours.
    assertNull(Workforce.cost(entry("2026-02-03T09:00:00Z", null, List.of()), List.of(rate)));
  }
}
