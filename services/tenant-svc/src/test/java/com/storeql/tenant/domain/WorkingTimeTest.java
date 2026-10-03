package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.tenant.domain.WorkingTime.Rule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a roster is judged by: rules that arrive as data, in the store's own zone. The numbers below
 * are fixtures for the arithmetic, the same as the EU pack the first migration carries.
 */
class WorkingTimeTest {

  private static final UUID T = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID WHO = Ids.newId();
  private static final ZoneId UTC = ZoneId.of("UTC");

  private static final String CITE_REST = "Directive 2003/88/EC art. 3";
  private static final String CITE_BREAK = "Directive 2003/88/EC art. 4";

  private static final List<Rule> EU =
      List.of(
          new Rule(
              WorkingTime.MIN_DAILY_REST,
              BigDecimal.valueOf(11),
              "HOURS",
              WorkingTime.ADVISORY,
              "EU",
              LocalDate.of(2004, 8, 2),
              null,
              CITE_REST),
          new Rule(
              WorkingTime.BREAK_AFTER,
              BigDecimal.valueOf(6),
              "HOURS",
              WorkingTime.ADVISORY,
              "EU",
              LocalDate.of(2004, 8, 2),
              null,
              CITE_BREAK));

  private static Instant at(String iso) {
    return Instant.parse(iso);
  }

  private static Shift shift(String from, String to) {
    return shift(from, to, Workforce.PUBLISHED);
  }

  private static Shift shift(String from, String to, String status) {
    return new Shift(
        Ids.newId(),
        T,
        STORE,
        WHO,
        at(from),
        at(to),
        null,
        status,
        null,
        Workforce.CANCELLED.equals(status) ? "store closed" : null,
        at(from),
        WHO,
        at(from));
  }

  @Test
  @DisplayName("Too little rest is said with the rule's citation and severity, and never refused")
  void restIsFlaggedFromData() {
    var concerns =
        WorkingTime.evaluate(
            List.of(
                shift("2026-09-14T16:00:00Z", "2026-09-14T22:00:00Z"),
                shift("2026-09-15T06:00:00Z", "2026-09-15T12:00:00Z")),
            EU,
            UTC);
    assertEquals(1, concerns.size(), concerns.toString());
    var c = concerns.get(0);
    assertEquals(WorkingTime.REST_SHORT, c.code());
    assertEquals(WorkingTime.ADVISORY, c.severity());
    assertEquals(WorkingTime.SOURCE_LAW, c.source());
    assertEquals(CITE_REST, c.citation());
    assertTrue(c.detail().contains("8.0") && c.detail().contains("11.0"), c.detail());
    assertTrue(c.detail().contains("art. 3"));

    var both =
        WorkingTime.evaluate(
            List.of(
                shift("2026-09-14T14:00:00Z", "2026-09-14T22:00:00Z"),
                shift("2026-09-15T06:00:00Z", "2026-09-15T12:00:00Z")),
            EU,
            UTC);
    assertEquals(2, both.size(), both.toString());
  }

  @Test
  @DisplayName("A store whose country has no rule gets no legal concern")
  void noPackNoLegalConcern() {
    var concerns =
        WorkingTime.evaluate(
            List.of(
                shift("2026-09-14T08:00:00Z", "2026-09-14T22:00:00Z"),
                shift("2026-09-15T02:00:00Z", "2026-09-15T06:00:00Z")),
            List.of(),
            UTC);
    assertTrue(concerns.isEmpty(), "no rule, no flag: " + concerns);
  }

  @Test
  @DisplayName("A long shift is flagged and a six-hour one is not; the threshold is the rule's")
  void breakAfterIsTheRulesNumber() {
    assertEquals(
        WorkingTime.NO_BREAK,
        WorkingTime.evaluate(
                List.of(shift("2026-09-14T08:00:00Z", "2026-09-14T17:00:00Z")), EU, UTC)
            .get(0)
            .code());
    assertTrue(
        WorkingTime.evaluate(
                List.of(shift("2026-09-14T08:00:00Z", "2026-09-14T14:00:00Z")), EU, UTC)
            .isEmpty(),
        "six hours is not yet more than six");
    var eightHours =
        List.of(
            new Rule(
                WorkingTime.BREAK_AFTER,
                BigDecimal.valueOf(480),
                "MINUTES",
                WorkingTime.UNLAWFUL,
                "XX",
                LocalDate.of(2000, 1, 1),
                null,
                "Test Act s. 1"));
    var nine = shift("2026-09-14T08:00:00Z", "2026-09-14T17:00:00Z");
    var concerns = WorkingTime.evaluate(List.of(nine), eightHours, UTC);
    assertEquals(1, concerns.size());
    assertEquals(WorkingTime.UNLAWFUL, concerns.get(0).severity());
    assertEquals("Test Act s. 1", concerns.get(0).citation());
  }

  @Test
  @DisplayName(
      "Overlapping shifts are reported anywhere, with no rule, and the rest is not also reported")
  void overlapsNeedNoRule() {
    var shifts =
        List.of(
            shift("2026-09-14T08:00:00Z", "2026-09-14T14:00:00Z"),
            shift("2026-09-14T13:00:00Z", "2026-09-14T18:00:00Z"));
    var none = WorkingTime.evaluate(shifts, List.of(), UTC);
    assertEquals(1, none.size());
    assertEquals(WorkingTime.OVERLAPS, none.get(0).code());
    assertEquals(WorkingTime.SOURCE_ROSTER, none.get(0).source());
    var withRules = WorkingTime.evaluate(shifts, EU, UTC);
    assertFalse(withRules.stream().anyMatch(c -> WorkingTime.REST_SHORT.equals(c.code())));
  }

  @Test
  @DisplayName("A cancelled shift is not rostered, so it raises nothing")
  void cancelledShiftsRaiseNothing() {
    assertTrue(
        WorkingTime.evaluate(
                List.of(
                    shift("2026-09-14T22:00:00Z", "2026-09-15T06:00:00Z", Workforce.CANCELLED),
                    shift("2026-09-15T08:00:00Z", "2026-09-15T12:00:00Z")),
                EU,
                UTC)
            .isEmpty());
  }

  @Test
  @DisplayName("The day a shift belongs to is the store's own: UTC+13, UTC-8 and a DST change")
  void theDayIsTheStores() {
    // 22:00 UTC on the 14th is 11:00 on the 15th in Auckland (UTC+13 in September) and 15:00 on the
    // 14th in Los Angeles (UTC-7 in September, -8 in winter).
    Shift s = shift("2026-09-14T22:00:00Z", "2026-09-15T02:00:00Z");
    assertEquals(LocalDate.of(2026, 9, 15), WorkingTime.day(s, ZoneId.of("Pacific/Auckland")));
    assertEquals(LocalDate.of(2026, 9, 14), WorkingTime.day(s, ZoneId.of("America/Los_Angeles")));
    Shift winter = shift("2026-12-15T05:00:00Z", "2026-12-15T09:00:00Z");
    assertEquals(
        LocalDate.of(2026, 12, 14),
        WorkingTime.day(winter, ZoneId.of("America/Los_Angeles")),
        "UTC-8: 05:00 UTC is the evening before");

    // The same long shift is judged on the store's day, so a law that starts on the 15th reaches it
    // in Auckland and not in Los Angeles.
    Rule from15th =
        new Rule(
            WorkingTime.BREAK_AFTER,
            BigDecimal.valueOf(3),
            "HOURS",
            WorkingTime.ADVISORY,
            "XX",
            LocalDate.of(2026, 9, 15),
            null,
            "Test Act");
    Shift four = shift("2026-09-14T22:00:00Z", "2026-09-15T02:00:00Z");
    assertEquals(
        1,
        WorkingTime.evaluate(List.of(four), List.of(from15th), ZoneId.of("Pacific/Auckland"))
            .size());
    assertTrue(
        WorkingTime.evaluate(List.of(four), List.of(from15th), ZoneId.of("America/Los_Angeles"))
            .isEmpty());

    // A shift across a spring-forward night is judged by elapsed time: 22:00 to 06:00 London on the
    // night the clocks go forward (2026-03-29) is seven hours of elapsed time, not eight.
    Shift dst = shift("2026-03-28T22:00:00Z", "2026-03-29T05:00:00Z");
    assertEquals(7 * 60, dst.length().toMinutes());
  }

  @Test
  @DisplayName("A rule counts only in its window: a law that ended does not judge later shifts")
  void aRuleHasItsWindow() {
    Rule ended =
        new Rule(
            WorkingTime.BREAK_AFTER,
            BigDecimal.valueOf(6),
            "HOURS",
            WorkingTime.ADVISORY,
            "EU",
            LocalDate.of(2004, 8, 2),
            LocalDate.of(2020, 1, 31),
            "Directive 2003/88/EC art. 4");
    assertTrue(
        WorkingTime.evaluate(
                List.of(shift("2026-09-14T08:00:00Z", "2026-09-14T17:00:00Z")), List.of(ended), UTC)
            .isEmpty());
    assertEquals(
        1,
        WorkingTime.evaluate(
                List.of(shift("2019-09-14T08:00:00Z", "2019-09-14T17:00:00Z")), List.of(ended), UTC)
            .size());
  }
}
