package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure — no clock but the one given to each call, no I/O. Every store here trades in its own zone;
 * none of London/New York/Sydney/Kolkata/Kathmandu is more "normal" than another to this code.
 */
class WindowsTest {

  private static final UUID STORE = Ids.newId();

  private static Windows.Window window(
      UUID id, String type, int weekday, String start, String end, int capacity, int cutoff) {
    return new Windows.Window(
        id,
        STORE,
        type,
        weekday,
        LocalTime.parse(start),
        LocalTime.parse(end),
        capacity,
        cutoff,
        true);
  }

  private static Windows.Window window(String start, String end) {
    return window(Ids.newId(), Windows.DELIVERY, 1, start, end, 5, 0);
  }

  // ── Clock-change days keep local times ──────────────────────────────────────

  @Test
  @DisplayName("Europe/London: a 17:00-19:00 window keeps its local times across both 2026 changes")
  void europeLondonAcrossBothClockChanges() {
    ZoneId london = ZoneId.of("Europe/London");
    Windows.Window w = window("17:00", "19:00");

    // 29 Mar 2026: BST begins at 01:00 UTC; by 17:00 local the store is already on +01:00.
    var springForward = Windows.occurrenceAt(w, london, LocalDate.of(2026, 3, 29));
    assertEquals(LocalTime.of(17, 0), springForward.localStartTime());
    assertEquals(LocalTime.of(19, 0), springForward.localEndTime());
    assertEquals(Instant.parse("2026-03-29T16:00:00Z"), springForward.startsAt());
    assertEquals(Instant.parse("2026-03-29T18:00:00Z"), springForward.endsAt());

    // 25 Oct 2026: BST ends at 02:00 local; by 17:00 local the store is back on +00:00.
    var fallBack = Windows.occurrenceAt(w, london, LocalDate.of(2026, 10, 25));
    assertEquals(LocalTime.of(17, 0), fallBack.localStartTime());
    assertEquals(LocalTime.of(19, 0), fallBack.localEndTime());
    assertEquals(Instant.parse("2026-10-25T17:00:00Z"), fallBack.startsAt());
    assertEquals(Instant.parse("2026-10-25T19:00:00Z"), fallBack.endsAt());

    // The same local window sits on a different UTC offset either side of the change: local time
    // was held, not the offset.
    assertEquals(ZoneOffset.ofHours(1), springForward.startsAt().atZone(london).getOffset(), "BST");
    assertEquals(ZoneOffset.ofHours(0), fallBack.startsAt().atZone(london).getOffset(), "GMT");
  }

  @Test
  @DisplayName("America/New_York: a 17:00-19:00 window on the 8 Mar 2026 spring-forward day")
  void americaNewYorkSpringForward() {
    ZoneId ny = ZoneId.of("America/New_York");
    Windows.Window w = window("17:00", "19:00");
    var occ = Windows.occurrenceAt(w, ny, LocalDate.of(2026, 3, 8));
    assertEquals(LocalTime.of(17, 0), occ.localStartTime());
    // EDT (-04:00) applies by 17:00 on the day clocks spring forward at 02:00.
    assertEquals(Instant.parse("2026-03-08T21:00:00Z"), occ.startsAt());
    assertEquals(Instant.parse("2026-03-08T23:00:00Z"), occ.endsAt());
  }

  @Test
  @DisplayName(
      "A spring-gap local time (America/New_York, 8 Mar 2026) moves forward as java.time does")
  void aSpringGapLocalTimeMovesForward() {
    ZoneId ny = ZoneId.of("America/New_York");
    // 02:00-03:00 local does not exist that day: clocks jump straight from 02:00 to 03:00.
    Windows.Window w = window(Ids.newId(), Windows.DELIVERY, 1, "02:30", "04:00", 5, 0);
    var occ = Windows.occurrenceAt(w, ny, LocalDate.of(2026, 3, 8));
    ZonedDateTime resolved = occ.startsAt().atZone(ny);
    // java.time moves a gap's local time forward by the gap's own length (one hour here), landing
    // unambiguously in EDT.
    assertEquals(LocalTime.of(3, 30), resolved.toLocalTime());
    assertEquals(ZoneOffset.ofHours(-4), resolved.getOffset());
    // The window still reports the local time exactly as it was set, not the resolved one — a
    // reader is told what the store meant, and the instant is what it became.
    assertEquals(LocalTime.of(2, 30), occ.localStartTime());
  }

  @Test
  @DisplayName("Australia/Sydney: a 17:00-19:00 window on the 4 Oct 2026 spring-forward day")
  void australiaSydneySpringForward() {
    ZoneId sydney = ZoneId.of("Australia/Sydney");
    Windows.Window w = window("17:00", "19:00");
    var occ = Windows.occurrenceAt(w, sydney, LocalDate.of(2026, 10, 4));
    assertEquals(LocalTime.of(17, 0), occ.localStartTime());
    // AEDT (+11:00) applies by 17:00 on the day clocks spring forward at 02:00.
    assertEquals(Instant.parse("2026-10-04T06:00:00Z"), occ.startsAt());
    assertEquals(Instant.parse("2026-10-04T08:00:00Z"), occ.endsAt());
  }

  @Test
  @DisplayName("Asia/Kolkata (+05:30, no DST): the offset never moves, whatever the date")
  void asiaKolkataHasNoDst() {
    ZoneId kolkata = ZoneId.of("Asia/Kolkata");
    Windows.Window w = window("17:00", "19:00");
    for (LocalDate date :
        List.of(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 7, 15), LocalDate.of(2026, 12, 31))) {
      var occ = Windows.occurrenceAt(w, kolkata, date);
      assertEquals(date.atTime(11, 30).toInstant(ZoneOffset.UTC), occ.startsAt(), date.toString());
      assertEquals(date.atTime(13, 30).toInstant(ZoneOffset.UTC), occ.endsAt(), date.toString());
    }
  }

  @Test
  @DisplayName("Asia/Kathmandu (+05:45, no DST): a quarter-hour offset most zones never need")
  void asiaKathmanduHasAQuarterHourOffset() {
    ZoneId kathmandu = ZoneId.of("Asia/Kathmandu");
    Windows.Window w = window("17:00", "19:00");
    var occ = Windows.occurrenceAt(w, kathmandu, LocalDate.of(2026, 6, 1));
    assertEquals(Instant.parse("2026-06-01T11:15:00Z"), occ.startsAt());
    assertEquals(Instant.parse("2026-06-01T13:15:00Z"), occ.endsAt());
  }

  // ── occurrences(): seven days, cut-off, sorting ──────────────────────────────

  @Test
  @DisplayName("occurrences() always answers exactly seven days, even with no windows at all")
  void alwaysSevenDays() {
    List<Windows.Day> days =
        Windows.occurrences(List.of(), ZoneId.of("UTC"), Instant.parse("2026-06-01T00:00:00Z"));
    assertEquals(Windows.HORIZON_DAYS, days.size());
    for (Windows.Day day : days) {
      assertTrue(day.occurrences().isEmpty());
    }
    assertEquals(LocalDate.of(2026, 6, 1), days.get(0).date());
    assertEquals(LocalDate.of(2026, 6, 7), days.get(6).date());
  }

  @Test
  @DisplayName("An occurrence past its cut-off (now + cutoff >= start) is left out; others stand")
  void pastCutOffIsDroppedFromTheList() {
    ZoneId utc = ZoneId.of("UTC");
    // Monday 2026-06-01 is a Monday (weekday 1).
    Windows.Window w = window(Ids.newId(), Windows.DELIVERY, 1, "10:00", "12:00", 3, 60);
    Instant justInsideCutoff = Instant.parse("2026-06-01T09:00:00Z"); // exactly now+60=start
    Instant justOutsideCutoff = Instant.parse("2026-06-01T08:59:00Z"); // now+60 < start

    var droppedDay = Windows.occurrences(List.of(w), utc, justInsideCutoff).get(0);
    assertTrue(droppedDay.occurrences().isEmpty(), "now + cutoff >= start: closed");

    var keptDay = Windows.occurrences(List.of(w), utc, justOutsideCutoff).get(0);
    assertEquals(1, keptDay.occurrences().size(), "one minute earlier: still open");
    assertEquals(Instant.parse("2026-06-01T10:00:00Z"), keptDay.occurrences().get(0).startsAt());
  }

  @Test
  @DisplayName("Occurrences of a day are earliest first")
  void occurrencesAreSortedEarliestFirst() {
    ZoneId utc = ZoneId.of("UTC");
    Windows.Window late = window(Ids.newId(), Windows.DELIVERY, 1, "18:00", "20:00", 3, 0);
    Windows.Window early = window(Ids.newId(), Windows.DELIVERY, 1, "09:00", "11:00", 3, 0);
    var day =
        Windows.occurrences(List.of(late, early), utc, Instant.parse("2026-06-01T00:00:00Z"))
            .get(0);
    assertEquals(2, day.occurrences().size());
    assertEquals(LocalTime.of(9, 0), day.occurrences().get(0).localStartTime());
    assertEquals(LocalTime.of(18, 0), day.occurrences().get(1).localStartTime());
  }

  @Test
  @DisplayName("An inactive window contributes no occurrence on any of the seven days")
  void anInactiveWindowContributesNothing() {
    ZoneId utc = ZoneId.of("UTC");
    Windows.Window inactive =
        new Windows.Window(
            Ids.newId(),
            STORE,
            Windows.DELIVERY,
            1,
            LocalTime.of(9, 0),
            LocalTime.of(11, 0),
            3,
            0,
            false);
    var days = Windows.occurrences(List.of(inactive), utc, Instant.parse("2026-06-01T00:00:00Z"));
    for (Windows.Day day : days) {
      assertTrue(day.occurrences().isEmpty());
    }
  }

  @Test
  @DisplayName("A window's occurrence falls only on the day matching its own weekday")
  void aWindowsOccurrenceFallsOnlyOnItsOwnWeekday() {
    ZoneId utc = ZoneId.of("UTC");
    // 2026-06-01 is a Monday (ISO weekday 1); a Tuesday (2) window has nothing that day and one
    // occurrence the day after.
    Windows.Window tuesday = window(Ids.newId(), Windows.DELIVERY, 2, "09:00", "11:00", 3, 0);
    var days = Windows.occurrences(List.of(tuesday), utc, Instant.parse("2026-06-01T00:00:00Z"));
    assertTrue(days.get(0).occurrences().isEmpty(), "Monday: not this window's day");
    assertEquals(1, days.get(1).occurrences().size(), "Tuesday: this window's day");
    assertEquals(LocalDate.of(2026, 6, 2), days.get(1).date());
    for (int i = 2; i < days.size(); i++) {
      assertTrue(days.get(i).occurrences().isEmpty(), days.get(i).date().toString());
    }
  }

  // ── occurrenceForInstant(): what a checkout's chosen slot is checked against ────

  @Test
  @DisplayName(
      "A real occurrence in the next seven days is found, and the horizon's edges are exact")
  void occurrenceForInstantFindsARealOccurrenceWithinTheHorizon() {
    ZoneId utc = ZoneId.of("UTC");
    // 2026-06-01 is a Monday; the horizon runs Mon 1 Jun .. Sun 7 Jun (seven days, day6 = Sunday).
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    Windows.Window monday = window(Ids.newId(), Windows.DELIVERY, 1, "10:00", "12:00", 3, 0);
    Windows.Window sunday = window(Ids.newId(), Windows.DELIVERY, 7, "10:00", "12:00", 3, 0);

    // Today (day 0, Monday) is in the horizon for the Monday window.
    assertTrue(
        Windows.occurrenceForInstant(monday, utc, now, Instant.parse("2026-06-01T10:00:00Z"))
            .isPresent());
    // Sunday 7 June is day 6 — the last day the horizon includes — and is in it for the Sunday
    // window.
    assertTrue(
        Windows.occurrenceForInstant(sunday, utc, now, Instant.parse("2026-06-07T10:00:00Z"))
            .isPresent());
    // The following Sunday (14 June) is the very same weekday and the very same local time, but
    // one week further out — outside the seven-day horizon.
    assertTrue(
        Windows.occurrenceForInstant(sunday, utc, now, Instant.parse("2026-06-14T10:00:00Z"))
            .isEmpty());
    // Yesterday is before the horizon.
    assertTrue(
        Windows.occurrenceForInstant(monday, utc, now, Instant.parse("2026-05-31T10:00:00Z"))
            .isEmpty());
  }

  @Test
  @DisplayName(
      "A startsAt that is not this window's exact occurrence, on the wrong weekday, or on an"
          + " inactive window is not found — ORDER_SLOT_UNKNOWN territory")
  void occurrenceForInstantRefusesWhatDoesNotMatch() {
    ZoneId utc = ZoneId.of("UTC");
    Instant now = Instant.parse("2026-06-01T00:00:00Z"); // Monday
    Windows.Window active = window(Ids.newId(), Windows.DELIVERY, 1, "10:00", "12:00", 3, 0);
    // A quarter hour off the real occurrence.
    assertTrue(
        Windows.occurrenceForInstant(active, utc, now, Instant.parse("2026-06-01T10:15:00Z"))
            .isEmpty());
    // A day that is not the window's weekday (Tuesday, not Monday).
    assertTrue(
        Windows.occurrenceForInstant(active, utc, now, Instant.parse("2026-06-02T10:00:00Z"))
            .isEmpty());
    // An inactive window offers no occurrence at all, even at its exact time.
    Windows.Window inactive =
        new Windows.Window(
            active.id(),
            STORE,
            Windows.DELIVERY,
            1,
            LocalTime.of(10, 0),
            LocalTime.of(12, 0),
            3,
            0,
            false);
    assertTrue(
        Windows.occurrenceForInstant(inactive, utc, now, Instant.parse("2026-06-01T10:00:00Z"))
            .isEmpty());
  }

  @Test
  @DisplayName("pastCutoff: now + cutoff >= start is closed; a moment earlier is not")
  void pastCutoffBoundary() {
    Windows.Window w = window(Ids.newId(), Windows.DELIVERY, 1, "10:00", "12:00", 3, 30);
    Instant startsAt = Instant.parse("2026-06-01T10:00:00Z");
    assertTrue(
        Windows.pastCutoff(w, startsAt, Instant.parse("2026-06-01T09:30:00Z")),
        "exactly at cutoff");
    assertTrue(
        Windows.pastCutoff(w, startsAt, Instant.parse("2026-06-01T09:31:00Z")), "past cutoff");
    assertFalse(
        Windows.pastCutoff(w, startsAt, Instant.parse("2026-06-01T09:29:59Z")), "one second short");
    assertTrue(
        Windows.pastCutoff(w, startsAt, Instant.parse("2026-06-01T11:00:00Z")),
        "already in the past");
  }

  // ── problems(): shape and overlap ────────────────────────────────────────────

  @Test
  @DisplayName("A window with a sound shape and no overlap has no problems")
  void aSoundWindowHasNoProblems() {
    Windows.Window w = window(Ids.newId(), Windows.DELIVERY, 3, "17:00", "19:00", 5, 30);
    assertTrue(Windows.problems(w, List.of()).isEmpty());
  }

  @Test
  @DisplayName("Touching windows (15-17 and 17-19) do not overlap")
  void touchingWindowsDoNotOverlap() {
    Windows.Window first = window(Ids.newId(), Windows.DELIVERY, 3, "15:00", "17:00", 5, 0);
    Windows.Window second = window(Ids.newId(), Windows.DELIVERY, 3, "17:00", "19:00", 5, 0);
    assertTrue(Windows.problems(second, List.of(first)).isEmpty());
    assertTrue(Windows.problems(first, List.of(second)).isEmpty());
  }

  @Test
  @DisplayName("Windows that truly overlap are refused, naming the clashing window")
  void trueOverlapIsRefused() {
    Windows.Window first = window(Ids.newId(), Windows.DELIVERY, 3, "15:00", "17:00", 5, 0);
    Windows.Window second = window(Ids.newId(), Windows.DELIVERY, 3, "16:00", "18:00", 5, 0);
    List<String> problems = Windows.problems(second, List.of(first));
    assertEquals(1, problems.size());
    assertTrue(problems.get(0).contains("overlaps"), problems.get(0));
  }

  @Test
  @DisplayName(
      "A window overlapping itself (an update that changes nothing material) is not refused")
  void aWindowDoesNotOverlapItself() {
    Windows.Window w = window(Ids.newId(), Windows.DELIVERY, 3, "15:00", "17:00", 5, 0);
    assertTrue(Windows.problems(w, List.of(w)).isEmpty());
  }

  @Test
  @DisplayName("An inactive candidate is never refused for overlapping an active window")
  void anInactiveCandidateNeverOverlaps() {
    Windows.Window active = window(Ids.newId(), Windows.DELIVERY, 3, "15:00", "17:00", 5, 0);
    Windows.Window inactiveCandidate =
        new Windows.Window(
            Ids.newId(),
            STORE,
            Windows.DELIVERY,
            3,
            LocalTime.of(16, 0),
            LocalTime.of(18, 0),
            5,
            0,
            false);
    assertTrue(Windows.problems(inactiveCandidate, List.of(active)).isEmpty());
  }

  @Test
  @DisplayName("An overlap with an inactive other window is not a problem")
  void overlappingAnInactiveOtherIsNotAProblem() {
    Windows.Window inactiveOther =
        new Windows.Window(
            Ids.newId(),
            STORE,
            Windows.DELIVERY,
            3,
            LocalTime.of(15, 0),
            LocalTime.of(17, 0),
            5,
            0,
            false);
    Windows.Window candidate = window(Ids.newId(), Windows.DELIVERY, 3, "16:00", "18:00", 5, 0);
    assertTrue(Windows.problems(candidate, List.of(inactiveOther)).isEmpty());
  }

  @Test
  @DisplayName("Every shape rule is checked: weekday, type, start/end, capacity, cut-off")
  void everyInvalidInputIsNamed() {
    assertTrue(
        Windows.problems(
                window(Ids.newId(), Windows.DELIVERY, 0, "17:00", "19:00", 5, 0), List.of())
            .stream()
            .anyMatch(p -> p.contains("weekday")));
    assertTrue(
        Windows.problems(
                window(Ids.newId(), Windows.DELIVERY, 8, "17:00", "19:00", 5, 0), List.of())
            .stream()
            .anyMatch(p -> p.contains("weekday")));
    assertTrue(
        Windows.problems(window(Ids.newId(), "COLLECTION", 3, "17:00", "19:00", 5, 0), List.of())
            .stream()
            .anyMatch(p -> p.contains("fulfilmentType")));
    assertTrue(
        Windows.problems(
                window(Ids.newId(), Windows.DELIVERY, 3, "19:00", "17:00", 5, 0), List.of())
            .stream()
            .anyMatch(p -> p.contains("startTime")));
    assertTrue(
        Windows.problems(
                window(Ids.newId(), Windows.DELIVERY, 3, "17:00", "17:00", 5, 0), List.of())
            .stream()
            .anyMatch(p -> p.contains("startTime")),
        "start equal to end is not before it");
    assertTrue(
        Windows.problems(
                window(Ids.newId(), Windows.DELIVERY, 3, "17:00", "19:00", 0, 0), List.of())
            .stream()
            .anyMatch(p -> p.contains("capacity")));
    assertTrue(
        Windows.problems(
                window(Ids.newId(), Windows.DELIVERY, 3, "17:00", "19:00", 5, -1), List.of())
            .stream()
            .anyMatch(p -> p.contains("cutoffMinutes")));
    // A window with every shape flaw at once names every one of them, not just the first found.
    List<String> many =
        Windows.problems(window(Ids.newId(), "BAD", 9, "19:00", "17:00", -1, -5), List.of());
    assertEquals(5, many.size());
  }

  @Test
  @DisplayName("An unsound shape is never checked for overlap: there is nothing sound to compare")
  void anUnsoundShapeSkipsTheOverlapCheck() {
    Windows.Window overlapping = window(Ids.newId(), Windows.DELIVERY, 3, "15:00", "17:00", 5, 0);
    Windows.Window brokenCandidate =
        window(Ids.newId(), Windows.DELIVERY, 3, "19:00", "17:00", 5, 0); // start after end
    List<String> problems = Windows.problems(brokenCandidate, List.of(overlapping));
    assertEquals(1, problems.size(), "only the shape problem, no overlap noise: " + problems);
    assertTrue(problems.get(0).contains("startTime"));
  }

  @Test
  @DisplayName("occurrenceAt reports the local times exactly as the window set them")
  void occurrenceAtReportsExactLocalTimes() {
    Windows.Window w = window("06:15", "07:45");
    var occ = Windows.occurrenceAt(w, ZoneId.of("UTC"), LocalDate.of(2026, 1, 1));
    assertEquals(LocalTime.of(6, 15), occ.localStartTime());
    assertEquals(LocalTime.of(7, 45), occ.localEndTime());
    assertEquals(w.id(), occ.windowId());
    assertEquals(w.capacity(), occ.capacity());
  }
}
