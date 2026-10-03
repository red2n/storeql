package com.storeql.tenant.domain;

import com.storeql.tenant.domain.Workforce.Concern;
import com.storeql.tenant.domain.Workforce.Shift;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * What working-time law says about a roster, judged from data (workforce-rules slice 1).
 *
 * <p>Pure: the rules arrive already found for the store's country (see {@code
 * WorkingTimeRuleRepository}) and the roster's day is the <b>store's own</b>, through its zone. The
 * code holds no country's number: a store whose country has no rule gets no legal concern, only the
 * one that is true everywhere — nobody works two places at once.
 *
 * <p>Two codes are applied today, the two the platform always applied: {@link #MIN_DAILY_REST} and
 * {@link #BREAK_AFTER}. A rule with another code cannot exist (the table's check), so a rule is
 * never held that nothing reads.
 */
public final class WorkingTime {

  private WorkingTime() {}

  public static final String MIN_DAILY_REST = "MIN_DAILY_REST";
  public static final String BREAK_AFTER = "BREAK_AFTER";

  public static final String ADVISORY = "ADVISORY";
  public static final String UNLAWFUL = "UNLAWFUL";

  /** The concern codes on the wire, unchanged from before the rules became data. */
  public static final String REST_SHORT = "DAILY_REST_SHORT";

  public static final String NO_BREAK = "BREAK_EXPECTED";
  public static final String OVERLAPS = "SHIFTS_OVERLAP";

  /** A concern that comes from the law's data. */
  public static final String SOURCE_LAW = "LAW";

  /** A concern that is a fact of the roster itself, true in any country. */
  public static final String SOURCE_ROSTER = "ROSTER";

  /**
   * One rule of law as it reaches a country: its own window narrowed to the country's membership of
   * the regime it comes through.
   *
   * @param scope the regime or country the law is made in, for the person reading the citation
   */
  public record Rule(
      String code,
      BigDecimal value,
      String unit,
      String severity,
      String scope,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String citation) {

    public boolean inForceOn(LocalDate day) {
      return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    /** The rule's number as a length of time. */
    public Duration duration() {
      long minutes =
          switch (unit) {
            case "HOURS" -> value.multiply(BigDecimal.valueOf(60)).longValueExact();
            case "MINUTES" -> value.longValueExact();
            default -> throw new IllegalStateException("unit " + unit + " is not a length of time");
          };
      return Duration.ofMinutes(minutes);
    }
  }

  /**
   * Everything worth saying about one person's rostered shifts: overlaps, then what the rules say.
   *
   * @param shifts one person's shifts, in order of start
   * @param rules the rules that reach the store's country, in any window
   * @param zone the store's zone: the day a shift belongs to is the local day it starts
   */
  public static List<Concern> evaluate(List<Shift> shifts, List<Rule> rules, ZoneId zone) {
    List<Concern> out = new ArrayList<>(overlaps(shifts, zone));
    out.addAll(judge(shifts, rules, zone));
    return out;
  }

  /**
   * Overlapping shifts: a mistake rather than a judgement, so it needs no rule and holds anywhere.
   */
  public static List<Concern> overlaps(List<Shift> shifts, ZoneId zone) {
    List<Concern> out = new ArrayList<>();
    Shift previous = null;
    for (Shift s : shifts) {
      if (!s.live()) continue;
      if (previous != null && s.startsAt().isBefore(previous.endsAt())) {
        out.add(
            new Concern(
                OVERLAPS,
                "the shifts on "
                    + day(previous, zone)
                    + " and "
                    + day(s, zone)
                    + " overlap, and nobody works two places at once",
                ADVISORY,
                SOURCE_ROSTER,
                null));
      }
      previous = s;
    }
    return out;
  }

  /**
   * What the rules say about the shifts, overlaps left out. A rule counts on the local day of the
   * shift it judges, so a law that came into force or ended is applied to the shifts it covered.
   */
  public static List<Concern> judge(List<Shift> shifts, List<Rule> rules, ZoneId zone) {
    List<Concern> out = new ArrayList<>();
    Shift previous = null;
    for (Shift s : shifts) {
      if (!s.live()) continue;
      LocalDate today = day(s, zone);
      for (Rule r : rules) {
        if (!BREAK_AFTER.equals(r.code()) || !r.inForceOn(today)) continue;
        Duration after = r.duration();
        if (s.length().compareTo(after) > 0) {
          out.add(
              new Concern(
                  NO_BREAK,
                  "the shift on "
                      + today
                      + " runs "
                      + Workforce.hours(s.length())
                      + " hours, and a break is expected after "
                      + Workforce.hours(after)
                      + " ("
                      + r.citation()
                      + ")",
                  r.severity(),
                  SOURCE_LAW,
                  r.citation()));
        }
      }
      if (previous != null && !s.startsAt().isBefore(previous.endsAt())) {
        Duration rest = Duration.between(previous.endsAt(), s.startsAt());
        for (Rule r : rules) {
          if (!MIN_DAILY_REST.equals(r.code()) || !r.inForceOn(today)) continue;
          Duration least = r.duration();
          if (rest.compareTo(least) < 0) {
            out.add(
                new Concern(
                    REST_SHORT,
                    "only "
                        + Workforce.hours(rest)
                        + " hours between the shifts on "
                        + day(previous, zone)
                        + " and "
                        + today
                        + ", where "
                        + Workforce.hours(least)
                        + " are expected ("
                        + r.citation()
                        + ")",
                    r.severity(),
                    SOURCE_LAW,
                    r.citation()));
          }
        }
      }
      previous = s;
    }
    return out;
  }

  /** The day a shift belongs to: the day it starts, in the store's zone. */
  public static LocalDate day(Shift s, ZoneId zone) {
    return s.startsAt().atZone(zone).toLocalDate();
  }
}
