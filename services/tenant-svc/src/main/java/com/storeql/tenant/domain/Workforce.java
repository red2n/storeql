package com.storeql.tenant.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The roster and the time clock (store operations & workforce).
 *
 * <p>A shop's biggest controllable cost is its hours, and the platform had no record of them. Staff
 * were assigned to stores with roles; nothing said who was meant to be in on Tuesday, or who
 * actually was. Everything else in this domain leans on it — labour cost against sales cannot be
 * computed without hours, and an absence cannot be seen without a plan to compare it against.
 *
 * <p><b>This is not a till session.</b> iam-svc's POS session is about the drawer: a cashier may
 * open three tills in one shift and a storekeeper never opens one. Paying from till sessions would
 * pay a cashier for the gaps between tills and pay a storekeeper nothing.
 *
 * <p>Three rules run through the arithmetic. <b>Unpaid breaks come off the hours and paid ones do
 * not</b> — which is the employer's arrangement, kept rather than decided here. <b>An open entry
 * has no hours yet</b>, and is reported as open rather than as zero, because a zero looks like a
 * day nobody worked. And <b>a correction supersedes</b>: hours that can be quietly rewritten are
 * hours nobody can be held to.
 */
public final class Workforce {

  private Workforce() {}

  public static final String PLANNED = "PLANNED";
  public static final String PUBLISHED = "PUBLISHED";
  public static final String CANCELLED = "CANCELLED";
  public static final Set<String> SHIFT_STATUSES = Set.of(PLANNED, PUBLISHED, CANCELLED);

  public static final String SOURCE_CLOCK = "CLOCK";
  public static final String SOURCE_MANAGER = "MANAGER";

  public static final String BREAK_REST = "REST";
  public static final String BREAK_MEAL = "MEAL";
  public static final Set<String> BREAK_KINDS = Set.of(BREAK_REST, BREAK_MEAL);

  /** A rostered shift: what somebody is meant to work. */
  public record Shift(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID userId,
      Instant startsAt,
      Instant endsAt,
      String duty,
      String status,
      String note,
      String cancelledReason,
      Instant createdAt,
      UUID createdBy,
      Instant updatedAt) {

    public boolean live() {
      return !CANCELLED.equals(status);
    }

    public Duration length() {
      return Duration.between(startsAt, endsAt);
    }

    /**
     * The day a shift belongs to in UTC. Only the attendance report still reads days this way; a
     * roster's concerns read the store's own day ({@link WorkingTime#day}).
     */
    public LocalDate day() {
      return startsAt.atOffset(ZoneOffset.UTC).toLocalDate();
    }
  }

  /** A break inside a worked entry. */
  public record Rest(
      UUID id,
      UUID tenantId,
      UUID timeEntryId,
      Instant startedAt,
      Instant endedAt,
      String kind,
      boolean paid) {

    public boolean open() {
      return endedAt == null;
    }

    public Duration length() {
      return endedAt == null ? Duration.ZERO : Duration.between(startedAt, endedAt);
    }
  }

  /**
   * A worked entry: what somebody actually did.
   *
   * @param shiftId the rostered shift it answers, or null — an unplanned shift and an unworked plan
   *     are both real, so neither side requires the other
   * @param supersedes the entry a correction replaces; both stay on the record
   */
  public record Entry(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID userId,
      UUID shiftId,
      Instant clockedInAt,
      Instant clockedOutAt,
      String source,
      String note,
      String adjustedReason,
      UUID supersedes,
      UUID supersededBy,
      Instant createdAt,
      UUID createdBy,
      List<Rest> breaks) {

    public Entry {
      breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }

    public boolean open() {
      return clockedOutAt == null;
    }

    /** Whether this is the entry that stands, rather than one a correction has replaced. */
    public boolean stands() {
      return supersededBy == null;
    }

    /** The clock time, before breaks: null while the entry is still open. */
    public Duration onSite() {
      return clockedOutAt == null ? null : Duration.between(clockedInAt, clockedOutAt);
    }

    /** How much of the breaks is unpaid, and so comes off the hours. */
    public Duration unpaidBreaks() {
      Duration total = Duration.ZERO;
      for (Rest r : breaks) {
        if (!r.paid()) total = total.plus(r.length());
      }
      return total;
    }

    /**
     * The hours this entry is worth.
     *
     * <p>Null while the entry is open, deliberately: an open entry reported as zero looks like a
     * day nobody worked, and a payroll run must be able to tell the difference.
     */
    public Duration worked() {
      Duration site = onSite();
      if (site == null) return null;
      Duration net = site.minus(unpaidBreaks());
      return net.isNegative() ? Duration.ZERO : net;
    }

    /** The day an entry belongs to: the day it began. */
    public LocalDate day() {
      return clockedInAt.atOffset(ZoneOffset.UTC).toLocalDate();
    }
  }

  /**
   * Something about a roster worth saying out loud, with the instrument that asks for it.
   *
   * @param severity ADVISORY or UNLAWFUL (what the rule's data says; never refused by this)
   * @param source LAW when it comes from a rule of law (with its {@code citation}), ROSTER when it
   *     is a fact of the roster itself
   * @param citation the instrument, or null for a ROSTER concern
   */
  public record Concern(
      String code, String detail, String severity, String source, String citation) {}

  /**
   * Whether a correction would raise the person's paid minutes: the entry's length less unpaid
   * breaks, after against before. An entry still open has no figure yet, so closing it counts as a
   * raise from nothing (the forgotten clock-out is exactly the correction a second person should
   * see); a correction that leaves it open changes no hours.
   */
  public static boolean raisesPaidMinutes(Entry before, Entry after) {
    Duration was = before.worked();
    Duration now = after.worked();
    if (now == null) return false;
    return was == null || now.compareTo(was) > 0;
  }

  /** Hours to one decimal place, which is how a rota is read and discussed. */
  public static String hours(Duration d) {
    long minutes = Math.max(0, d.toMinutes());
    return (minutes / 60) + "." + ((minutes % 60) * 10 / 60);
  }

  /**
   * One person's day, planned against worked — the shape an attendance report is read in.
   *
   * @param planned the rostered minutes, zero when nobody rostered them
   * @param worked the clocked minutes, zero when they did not turn up
   * @param openEntry true when they are still on the clock, so the worked figure is not final
   * @param lateByMinutes how late the first clock-in was against the roster; negative for early
   */
  public record AttendanceDay(
      LocalDate day,
      UUID userId,
      UUID storeId,
      long planned,
      long worked,
      int entries,
      boolean openEntry,
      Long lateByMinutes) {

    /**
     * Rostered and <em>nothing clocked at all</em>: the case the report exists for.
     *
     * <p>Counted on entries and not on minutes. Somebody who clocked in and straight back out
     * worked no minutes but did turn up, and calling that an absence would put a disciplinary
     * question where a mis-tap is.
     */
    public boolean absent() {
      return planned > 0 && entries == 0;
    }

    /** Clocked with nothing rostered, which is as much a management fact as an absence. */
    public boolean unplanned() {
      return planned == 0 && entries > 0;
    }
  }

  /**
   * What an hour of somebody's time costs, from a date.
   *
   * <p>Not payroll: no salary, no deductions, no tax. A platform holding payroll would owe a great
   * deal more than this one promises, and the question a shop actually asks — what did this
   * Saturday cost me against what it took — needs only this.
   */
  public record PayRate(
      UUID id,
      UUID tenantId,
      UUID userId,
      LocalDate effectiveFrom,
      java.math.BigDecimal hourlyRate,
      String currency,
      String note,
      Instant createdAt,
      UUID createdBy) {}

  /**
   * What an entry cost, at the rate in force on the day it was worked.
   *
   * <p><b>The day, not today.</b> A rate that rose in April must not re-cost January: a labour
   * figure that moved when somebody got a pay rise would make last quarter's report disagree with
   * itself.
   *
   * @param rates the person's rates, newest first
   * @return the cost, or null when no rate was in force then — which is reported as unknown rather
   *     than as zero, because zero is a real rate somebody may be on
   */
  public static java.math.BigDecimal cost(Entry entry, List<PayRate> rates) {
    Duration worked = entry.worked();
    if (worked == null) return null;
    LocalDate day = entry.day();
    for (PayRate r : rates) {
      if (!r.effectiveFrom().isAfter(day)) {
        return r.hourlyRate()
            .multiply(java.math.BigDecimal.valueOf(worked.toMinutes()))
            .divide(java.math.BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
      }
    }
    return null;
  }
}
