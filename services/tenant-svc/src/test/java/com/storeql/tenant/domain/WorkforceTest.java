package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.Fx;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.Rest;
import com.storeql.tenant.domain.Workforce.Shift;
import java.math.BigDecimal;
import java.math.BigInteger;
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

  /** A correction of {@code supersedes}, as the service builds it from a request. */
  private static Entry correction(UUID supersedes, Instant in, Instant out, String reason) {
    return new Entry(
        Ids.newId(),
        T,
        STORE,
        WHO,
        null,
        in,
        out,
        Workforce.SOURCE_MANAGER,
        null,
        reason,
        supersedes,
        null,
        in,
        Ids.newId(),
        List.of());
  }

  @Test
  @DisplayName(
      "A retried correction is the same one only for the same entry, times and reason; to the"
          + " microsecond the record keeps")
  void sameCorrection() {
    UUID entry = Ids.newId();
    Instant in = at("2026-09-14T08:00:00Z");
    Instant out = at("2026-09-14T17:00:00.123456Z");
    Entry first = correction(entry, in, out, "terminal was down");

    // Built again from the same request: another id, another moment, the same correction.
    assertTrue(Workforce.sameCorrection(first, correction(entry, in, out, "terminal was down")));
    // A request may name a nanosecond the record never held; it is still the same correction.
    assertTrue(
        Workforce.sameCorrection(
            first, correction(entry, in, out.plusNanos(789), "terminal was down")));
    // Still open both times is the same; closed one time and open the other is not.
    Entry open = correction(entry, in, null, "clocked in late");
    assertTrue(Workforce.sameCorrection(open, correction(entry, in, null, "clocked in late")));
    assertFalse(Workforce.sameCorrection(open, correction(entry, in, out, "clocked in late")));
    assertFalse(Workforce.sameCorrection(first, correction(entry, in, null, "terminal was down")));

    assertFalse(
        Workforce.sameCorrection(first, correction(Ids.newId(), in, out, "terminal was down")),
        "another entry");
    assertFalse(
        Workforce.sameCorrection(
            first, correction(entry, in.minusSeconds(60), out, "terminal was down")),
        "another start");
    assertFalse(
        Workforce.sameCorrection(
            first, correction(entry, in, out.plusSeconds(1), "terminal was down")),
        "another end");
    assertFalse(
        Workforce.sameCorrection(first, correction(entry, in, out, "forgot to clock out")),
        "another reason");
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
    assertEquals(
        new java.math.BigDecimal("96.00"), Workforce.cost(inJanuary, rates, Fx::minorUnits));

    Entry inMay = entry("2026-05-20T09:00:00Z", "2026-05-20T17:00:00Z", List.of());
    assertEquals(new java.math.BigDecimal("108.00"), Workforce.cost(inMay, rates, Fx::minorUnits));
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
    assertEquals(
        new java.math.BigDecimal("96.00"),
        Workforce.cost(withLunch, List.of(rate), Fx::minorUnits));

    // No rate in force yet: unknown, not free. Zero is a rate somebody may be on, and a day shown
    // as
    // free labour is worse than one that says it does not know.
    Entry before = entry("2025-12-31T09:00:00Z", "2025-12-31T17:00:00Z", List.of());
    assertNull(Workforce.cost(before, List.of(rate), Fx::minorUnits));
    // And an open entry has no cost, because it has no hours.
    assertNull(
        Workforce.cost(
            entry("2026-02-03T09:00:00Z", null, List.of()), List.of(rate), Fx::minorUnits));
  }

  private static Workforce.PayRate rateIn(String amount, String currency) {
    return new Workforce.PayRate(
        Ids.newId(),
        T,
        WHO,
        java.time.LocalDate.of(2026, 1, 1),
        new java.math.BigDecimal(amount),
        currency,
        null,
        at("2026-01-01T00:00:00Z"),
        WHO);
  }

  @Test
  @DisplayName("An hour's cost is rounded to the rate's own currency: whole yen, a dinar's third")
  void costIsRoundedToTheCurrencysOwnMinorUnits() {
    // 7h20m: 440 minutes, a third of an hour that never divides evenly.
    Entry day = entry("2026-02-02T09:00:00Z", "2026-02-02T16:20:00Z", List.of());
    assertEquals(
        new java.math.BigDecimal("8067"),
        Workforce.cost(day, List.of(rateIn("1100", "JPY")), Fx::minorUnits),
        "1100 yen an hour for 7h20m is 8066.67 yen, paid as whole yen");
    assertEquals(
        new java.math.BigDecimal("12.833"),
        Workforce.cost(day, List.of(rateIn("1.750", "KWD")), Fx::minorUnits),
        "a dinar keeps its third decimal: 1.750 an hour for 7h20m is 12.8333");
    assertEquals(
        new java.math.BigDecimal("91.67"),
        Workforce.cost(day, List.of(rateIn("12.50", "EUR")), Fx::minorUnits),
        "a euro keeps two");
  }

  // ── what an hour costs, as it is sent ──────────────────────────────────────

  @Test
  @DisplayName(
      "An hourly rate is read only written out: an exponent, a word or a digit of another script"
          + " is no rate, and nothing is built from it")
  void anHourlyRateIsReadOnlyWrittenOut() {
    for (String text :
        List.of(
            "1E+999999999",
            "1E+2147483647",
            "0E+2147483647",
            "1E-2147483647",
            "1e5",
            "12.5E1",
            "Infinity",
            "NaN",
            "0x10",
            "",
            "   ",
            "12,50",
            "1_000",
            "\u0661\u0662",
            "12.5.0",
            ".",
            "+",
            "-",
            "1 2",
            "12.50 GBP",
            "--1",
            "9".repeat(10_000))) {
      assertTrue(Workforce.writtenRate(text).isEmpty(), text);
    }
    assertTrue(Workforce.writtenRate(null).isEmpty());
    assertEquals(new BigDecimal("12.50"), Workforce.writtenRate(" 12.50 ").orElseThrow());
    assertEquals(new BigDecimal("10.4167"), Workforce.writtenRate("10.4167").orElseThrow());
    assertEquals(new BigDecimal("12"), Workforce.writtenRate("+12").orElseThrow());
    assertEquals(new BigDecimal("0.5"), Workforce.writtenRate(".5").orElseThrow());
    assertEquals(new BigDecimal("12"), Workforce.writtenRate("12.").orElseThrow());
    assertEquals(
        new BigDecimal("-1.00"),
        Workforce.writtenRate("-1.00").orElseThrow(),
        "read as sent; less than nothing is the service's refusal, not the reader's");
  }

  @Test
  @DisplayName(
      "Whether a rate fits pay_rates.hourly_rate is judged at constant cost, never by an int"
          + " subtraction that wraps round and lets 1E+2147483647 through")
  void aRateFitsItsColumnWhateverItsSize() {
    for (String fits :
        List.of("0", "0.0000", "0.00000000", "12.50000000", "99999999.9999", "10.4167", "-1")) {
      assertTrue(Workforce.rateFits(new BigDecimal(fits)), fits);
    }
    for (String refused : List.of("123456789", "12.34567", "0.00001", "100000000.0000")) {
      assertFalse(Workforce.rateFits(new BigDecimal(refused)), refused);
    }
    for (BigDecimal wraps :
        List.of(
            new BigDecimal("1E+2147483647"),
            new BigDecimal("0E+2147483647"),
            new BigDecimal("9E+2147483647"),
            new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE),
            new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE),
            new BigDecimal(BigInteger.TEN.pow(400), 400))) {
      assertFalse(Workforce.rateFits(wraps), wraps::toString);
    }
  }
}
