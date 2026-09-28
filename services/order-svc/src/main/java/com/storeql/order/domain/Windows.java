package com.storeql.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivery and collection slots (intent/delivery-and-collection-slots.md), pure: which occurrences
 * a store's fulfilment windows have over the next seven days in the store's own zone, and whether
 * one window's shape conflicts with another's. No database, no clock but the one given, no service
 * — every rule here is a function of its arguments alone.
 */
public final class Windows {

  private Windows() {}

  /** How many days ahead the storefront and checkout ever offer, counting today. */
  public static final int HORIZON_DAYS = 7;

  public static final String DELIVERY = "DELIVERY";
  public static final String PICKUP = "PICKUP";
  public static final List<String> TYPES = List.of(DELIVERY, PICKUP);

  /**
   * One window as a store set it: a weekly occasion, in the store's own wall-clock time, that takes
   * a fixed number of orders and closes some minutes before it starts.
   *
   * @param weekday ISO-8601: 1=Monday .. 7=Sunday
   */
  public record Window(
      UUID id,
      UUID storeId,
      String fulfilmentType,
      int weekday,
      LocalTime startTime,
      LocalTime endTime,
      int capacity,
      int cutoffMinutes,
      boolean active) {}

  /**
   * One occurrence of a window: the UTC instants of that occasion, and the same reading on the
   * store's own wall clock — computed once, here, so no client ever has to know the store's zone to
   * show its own time correctly.
   */
  public record Occurrence(
      UUID windowId,
      Instant startsAt,
      Instant endsAt,
      int capacity,
      LocalDate localDate,
      LocalTime localStartTime,
      LocalTime localEndTime) {}

  /** One of the next {@link #HORIZON_DAYS} days, with whatever occurrences still stand in it. */
  public record Day(LocalDate date, List<Occurrence> occurrences) {
    public Day {
      occurrences = List.copyOf(occurrences);
    }
  }

  /**
   * A window as it stands, with what the admin screen shows beside its shape: the zone it was set
   * in and who last touched it. A pure value — {@link
   * com.storeql.order.repo.FulfilmentWindowRepository} is the only place that reads or writes one,
   * but it is a {@code domain} type (not a {@code repo} one) so the {@code api} layer that shows it
   * may hold a reference without reaching into the repository layer directly.
   */
  public record WindowRecord(Window window, String timeZone, Instant updatedAt, UUID updatedBy) {}

  /**
   * The reasons {@code candidate} is not a window the platform can offer, or empty when it is one.
   * Checks the candidate's own shape (a weekday in range, a type it knows, a start before its end,
   * a capacity of at least one, a cut-off that is not negative) and, only once the shape itself is
   * sound, that it does not overlap another ACTIVE window in {@code others} — touching windows (one
   * ending exactly when the next starts) do not overlap.
   *
   * <p>Every window in {@code others} is assumed to already be the same store, the same fulfilment
   * type and the same weekday as {@code candidate}, and not {@code candidate} itself (by id) —
   * arranging that comparison set is the caller's, because only the caller knows how it read them.
   *
   * @return problem descriptions, each naming what is wrong; empty means the window may be saved
   */
  public static List<String> problems(Window candidate, List<Window> others) {
    List<String> out = new ArrayList<>();
    if (candidate.fulfilmentType() == null || !TYPES.contains(candidate.fulfilmentType())) {
      out.add("fulfilmentType must be DELIVERY or PICKUP");
    }
    if (candidate.weekday() < 1 || candidate.weekday() > 7) {
      out.add("weekday must be between 1 (Monday) and 7 (Sunday)");
    }
    boolean shapeSound =
        candidate.startTime() != null
            && candidate.endTime() != null
            && candidate.startTime().isBefore(candidate.endTime());
    if (!shapeSound) {
      out.add("startTime must be before endTime");
    }
    if (candidate.capacity() < 1) {
      out.add("capacity must be at least 1");
    }
    if (candidate.cutoffMinutes() < 0) {
      out.add("cutoffMinutes cannot be negative");
    }
    if (shapeSound && candidate.active()) {
      for (Window other : others) {
        if (!other.active() || other.id().equals(candidate.id())) {
          continue;
        }
        if (overlaps(candidate, other)) {
          out.add(
              "overlaps another active window of the same store, type and weekday ("
                  + other.startTime()
                  + "-"
                  + other.endTime()
                  + ")");
          break;
        }
      }
    }
    return out;
  }

  /** Half-open spans: {@code [start, end)}. Touching windows (17-19 and 19-21) do not overlap. */
  private static boolean overlaps(Window a, Window b) {
    return a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime());
  }

  /**
   * The occurrence {@code window} has on {@code date}, in {@code zone} — whatever day of the week
   * {@code date} falls on; {@link #occurrences} is what filters to the window's own weekday. Local
   * times are fixed to the calendar day with {@link ZonedDateTime#of(LocalDate, LocalTime,
   * ZoneId)}: on a day the clocks change they keep exactly the wall-clock reading the window was
   * set at (a shorter or longer gap to UTC), and a local time a spring-forward gap skips is moved
   * forward to the zone's next valid instant, exactly as {@code java.time} resolves it — nothing
   * here special-cases a clock change.
   */
  public static Occurrence occurrenceAt(Window window, ZoneId zone, LocalDate date) {
    ZonedDateTime start = ZonedDateTime.of(date, window.startTime(), zone);
    ZonedDateTime end = ZonedDateTime.of(date, window.endTime(), zone);
    return new Occurrence(
        window.id(),
        start.toInstant(),
        end.toInstant(),
        window.capacity(),
        date,
        window.startTime(),
        window.endTime());
  }

  /**
   * Whether {@code startsAt} is past {@code window}'s cut-off, measured from {@code now}: the
   * occurrence is closed once there are fewer than {@code cutoffMinutes} left before it starts —
   * which also covers an occurrence already in the past, since a negative gap is always less.
   */
  public static boolean pastCutoff(Window window, Instant startsAt, Instant now) {
    return !now.plus(Duration.ofMinutes(window.cutoffMinutes())).isBefore(startsAt);
  }

  /**
   * Whether {@code startsAt} is exactly the occurrence {@code window} has on its own calendar day
   * in {@code zone}, within the next {@link #HORIZON_DAYS} days counting from {@code now}'s day —
   * what a checkout's chosen slot is checked against. Says nothing about the cut-off: a stale but
   * structurally real occurrence is still found here, and {@link #pastCutoff} is asked apart, since
   * the two are refused with different codes.
   *
   * @return the occurrence, or empty when {@code window} is inactive, {@code startsAt}'s local day
   *     is not the window's weekday, is outside the horizon, or does not land on the window's exact
   *     start
   */
  public static Optional<Occurrence> occurrenceForInstant(
      Window window, ZoneId zone, Instant now, Instant startsAt) {
    if (!window.active() || zone == null || now == null || startsAt == null) {
      return Optional.empty();
    }
    LocalDate today = now.atZone(zone).toLocalDate();
    LocalDate date = startsAt.atZone(zone).toLocalDate();
    if (date.isBefore(today) || !date.isBefore(today.plusDays(HORIZON_DAYS))) {
      return Optional.empty();
    }
    if (date.getDayOfWeek().getValue() != window.weekday()) {
      return Optional.empty();
    }
    Occurrence occ = occurrenceAt(window, zone, date);
    return occ.startsAt().equals(startsAt) ? Optional.of(occ) : Optional.empty();
  }

  /**
   * The next {@link #HORIZON_DAYS} days from {@code now} in {@code zone} — always exactly that many
   * entries, one per calendar day, a day with nothing left an empty list rather than missing — each
   * with the active windows' occurrences that fall on it and have not yet passed their cut-off,
   * earliest first.
   *
   * @param windows the store's windows of one fulfilment type; an inactive one contributes nothing
   * @param zone the store's own IANA zone
   * @param now the instant "today" and every cut-off are measured from
   */
  public static List<Day> occurrences(List<Window> windows, ZoneId zone, Instant now) {
    LocalDate today = now.atZone(zone).toLocalDate();
    List<Day> days = new ArrayList<>(HORIZON_DAYS);
    for (int d = 0; d < HORIZON_DAYS; d++) {
      LocalDate date = today.plusDays(d);
      int isoWeekday = date.getDayOfWeek().getValue();
      List<Occurrence> occs = new ArrayList<>();
      for (Window w : windows) {
        if (!w.active() || w.weekday() != isoWeekday) {
          continue;
        }
        Occurrence occ = occurrenceAt(w, zone, date);
        if (pastCutoff(w, occ.startsAt(), now)) {
          continue;
        }
        occs.add(occ);
      }
      occs.sort(Comparator.comparing(Occurrence::startsAt).thenComparing(Occurrence::windowId));
      days.add(new Day(date, occs));
    }
    return days;
  }
}
